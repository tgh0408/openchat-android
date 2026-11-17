package com.im.openchat.chat.data

import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** 对应服务端 ChatMessagePageReq；首页 cursor 为空字符串。 */
@Serializable
internal data class MessageHistoryRequest(val roomId: Long, val pageSize: Int, val cursor: String)

/** 对应 ChatMessageReq 的普通文本请求。 */
@Serializable
internal data class TextMessageRequest(
    val roomId: Long,
    val clientMsgId: String,
    val body: TextMessageBody,
    val msgType: MessageType,
)

/** 对应服务端 ApiResult，先读取状态再将 data 反序列化为具体响应对象。 */
@Serializable
private data class HistoryApiResult(
    val code: Int,
    val success: Boolean = false,
    val errMsg: String? = null,
    val data: JsonElement = JsonNull,
)

/** 对应服务端 CursorPageBaseResp，保留首页、末页及消息列表的真实类型。 */
@Serializable
internal data class MessageHistoryPageResponse(
    val cursor: String? = null,
    val isFirst: Boolean,
    val isLast: Boolean,
    val list: List<ChatMessageResponse>,
)

/** 对应服务端 ChatMessageResp，外层发送者与消息详情分开。 */
@Serializable
internal data class ChatMessageResponse(val fromUser: ChatMessageUser, val message: ChatMessageDetail)

/** 对应 ChatMessageResp.UserInfo；Long 字符串直接解码为 Kotlin Long，不经过浮点数。 */
@Serializable
internal data class ChatMessageUser(val uid: Long)

/** 资源 message 也承载好友等通知，先区分业务信封再解码聊天详情。 */
@Serializable
private data class ResourceMessageEnvelope(
    val eventType: JsonElement? = null,
    val fromUser: ChatMessageUser? = null,
    val message: JsonElement = JsonNull,
)

/** 对应 ChatMessageResp.Message，body 在协议边界即成为具体 Kotlin 正文对象。 */
@Serializable(with = ChatMessageDetailSerializer::class)
internal data class ChatMessageDetail(
    val id: Long,
    val roomId: Long,
    val sendTime: Long,
    val type: MessageType,
    val body: MessageBody,
    val clientMsgId: String? = null,
    val replyId: Long? = null,
    val gapCount: Int? = null,
)

/** 仅供序列化边界使用的消息信封；业务仓库不接触延迟解码的 body。 */
@Serializable
private data class ChatMessageWireDetail(
    val id: Long,
    val roomId: Long,
    val sendTime: Long,
    val type: MessageType,
    val body: JsonElement = JsonNull,
    val clientMsgId: String? = null,
    val replyId: Long? = null,
    val gapCount: Int? = null,
)

/** 显式映射服务端整数消息类型，也用于上传占位嵌套的 targetType。 */
internal object MessageTypeSerializer : KSerializer<MessageType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageType", PrimitiveKind.INT)

    /**
     * 按协议值读取消息类型。
     * @param decoder 序列化库提供的整数解码器。
     * @return 具名类型，未知值映射 UNKNOWN；无副作用。
     */
    override fun deserialize(decoder: Decoder): MessageType = MessageType.fromWireValue(decoder.decodeInt())

    /**
     * 输出消息类型的显式协议值，不使用枚举序号。
     * @param encoder 序列化库提供的整数编码器。
     * @param value 已知消息类型；UNKNOWN 不可作为服务端协议发送。
     * 无返回值；仅写入当前编码器。
     */
    override fun serialize(encoder: Encoder, value: MessageType) {
        if (value == MessageType.UNKNOWN) throw SerializationException("未知消息类型不能编码为服务端协议")
        encoder.encodeInt(value.wireValue)
    }
}

/** 将兄弟字段 type 映射为正文序列化器，元数据和正文属性均由生成代码读取。 */
internal object ChatMessageDetailSerializer : KSerializer<ChatMessageDetail> {
    override val descriptor: SerialDescriptor = ChatMessageWireDetail.serializer().descriptor

    /**
     * 读取类型化信封，再按后端 type 解码对应正文对象。
     * @param decoder 当前 HTTP JSON 解码器。
     * @return 不包含 JSON 树的消息详情；无副作用。
     */
    override fun deserialize(decoder: Decoder): ChatMessageDetail {
        val input = decoder as? JsonDecoder ?: throw SerializationException("聊天消息必须使用 JSON 解码")
        val wire = input.decodeSerializableValue(ChatMessageWireDetail.serializer())
        val body = MessageHistoryProtocol.decodeBody(input.json, wire.type, wire.body)
        return ChatMessageDetail(
            wire.id, wire.roomId, wire.sendTime, wire.type, body,
            wire.clientMsgId, wire.replyId, wire.gapCount,
        )
    }

    /**
     * 以服务端信封结构编码消息对象。
     * @param encoder 当前 JSON 编码器。
     * @param value 包含具名类型和正文对象的消息详情。
     * 无返回值；仅写入当前编码器，不发送请求或保存消息。
     */
    override fun serialize(encoder: Encoder, value: ChatMessageDetail) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("聊天消息必须使用 JSON 编码")
        output.encodeSerializableValue(
            ChatMessageWireDetail.serializer(),
            ChatMessageWireDetail(
                value.id, value.roomId, value.sendTime, value.type,
                MessageHistoryProtocol.encodeBody(output.json, value.body),
                value.clientMsgId, value.replyId, value.gapCount,
            ),
        )
    }
}

/** 历史、聊天推送和 Room 正文共用的类型编解码入口。 */
internal object MessageHistoryProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }
    private val transferJson = Json(json) {
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 将类型化请求序列化为 HTTP 正文。
     * @param request 已校验房间号和分页参数的请求对象。
     * @return JSON 请求文本；无副作用。
     */
    fun encodeRequest(request: MessageHistoryRequest): String = json.encodeToString(request)

    fun encodeTextRequest(request: TextMessageRequest): String = json.encodeToString(
        TextMessageRequest.serializer(), request,
    )

    fun encodeImageRequest(request: ImageMessageRequest): String =
        transferJson.encodeToString(ImageMessageRequest.serializer(), request)

    internal fun <T> encodeTransfer(serializer: KSerializer<T>, value: T): String =
        transferJson.encodeToString(serializer, value)

    internal fun decodeUploadSource(value: String): FileUploadSource = try {
        json.decodeFromString(FileUploadSource.serializer(), value)
    } catch (exception: SerializationException) {
        throw IOException("本机上传来源信息无效", exception)
    }

    internal fun <T> decodeTransfer(serializer: KSerializer<T>, response: String): T {
        try {
            val result = json.decodeFromString<HistoryApiResult>(response)
            if (result.code in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
            if (!result.success || result.code != 200) {
                throw IOException(result.errMsg?.takeIf(String::isNotBlank) ?: "文件传输请求失败，请重试")
            }
            return json.decodeFromJsonElement(serializer, result.data)
        } catch (exception: SerializationException) {
            throw IOException("文件传输服务返回的数据无效", exception)
        }
    }

    /** HTTP 成功仅表示已受理；最终状态由历史中的服务端消息确认。 */
    fun decodeSendResult(response: String) {
        try {
            val result = json.decodeFromString<HistoryApiResult>(response)
            if (result.code in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
            if (!result.success || result.code != 200) {
                throw IOException(result.errMsg?.takeIf(String::isNotBlank) ?: "消息发送失败，请重试")
            }
        } catch (exception: SerializationException) {
            throw IOException("消息发送服务返回的数据无效", exception)
        }
    }

    /**
     * 先判断服务端状态，再反序列化历史分页。
     * @param response HTTP 响应正文。
     * @return 后端分页响应对象；鉴权失效或解码失败上抛，不写入存储。
     */
    fun decodePage(response: String): MessageHistoryPageResponse {
        try {
            val result = json.decodeFromString<HistoryApiResult>(response)
            if (result.code in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
            if (!result.success || result.code != 200) {
                throw IOException(result.errMsg?.takeIf(String::isNotBlank) ?: "获取聊天记录失败，请重试")
            }
            return json.decodeFromJsonElement<MessageHistoryPageResponse>(result.data)
        } catch (exception: SerializationException) {
            throw IOException("聊天记录服务返回的数据无效", exception)
        }
    }

    /** 接入普通文本和图片推送，通知及其他消息类型仍交给原有刷新链路。 */
    fun decodeMessagePush(data: String): ChatMessageResponse? {
        try {
            val envelope = json.decodeFromString<ResourceMessageEnvelope>(data)
            if (envelope.eventType != null) return null
            val user = envelope.fromUser ?: return null
            if (envelope.message == JsonNull) return null
            val wire = json.decodeFromJsonElement<ChatMessageWireDetail>(envelope.message)
            if (wire.type != MessageType.TEXT && wire.type != MessageType.IMAGE) return null
            return ChatMessageResponse(user, json.decodeFromJsonElement<ChatMessageDetail>(envelope.message))
        } catch (exception: SerializationException) {
            throw IOException("聊天消息推送数据无效", exception)
        }
    }

    /**
     * 在协议边界按 type 选择生成的正文解码器。
     * @param format 当前响应使用的 JSON 格式。
     * @param type 从服务端 MessageTypeEnum 映射的具名类型。
     * @param body 仅存在于信封内部的原始正文节点。
     * @return 对应正文对象；未知类型不解析正文，无副作用。
     */
    fun decodeBody(format: Json, type: MessageType, body: JsonElement): MessageBody {
        if (type == MessageType.UNKNOWN) return UnknownMessageBody
        return format.decodeFromJsonElement(bodyDeserializer(type), body)
    }

    /**
     * 按正文的具体类型编码字段，不添加后端正文中不存在的根级多态标记。
     * @param format 当前信封或存储使用的 JSON 格式。
     * @param body 已构造的正文对象，系统扩展仍使用后端约定的 kind 标记。
     * @return 对应正文节点；无副作用，不保留未声明字段。
     */
    fun encodeBody(format: Json, body: MessageBody): JsonElement = when (body) {
        is TextMessageBody -> format.encodeToJsonElement(body)
        is RecallMessageBody -> format.encodeToJsonElement(body)
        is TemporaryMessageBody -> format.encodeToJsonElement(body)
        is ImageMessageBody -> format.encodeToJsonElement(body)
        is VideoMessageBody -> format.encodeToJsonElement(body)
        is FileMessageBody -> format.encodeToJsonElement(body)
        is VoiceMessageBody -> format.encodeToJsonElement(body)
        is SoundMessageBody -> format.encodeToJsonElement(body)
        is EmojiMessageBody -> format.encodeToJsonElement(body)
        is SystemMessageBody -> format.encodeToJsonElement(body)
        is RtcCallMessageBody -> format.encodeToJsonElement(body)
        UnknownMessageBody -> format.encodeToJsonElement(UnknownMessageBody)
    }

    /**
     * 将正文对象编码为 Room 的 content 列，不再保存任意服务端 JSON。
     * @param body 已按消息类型构造的正文对象。
     * @return 文本消息的纯正文或其他消息的结构化编码；无副作用。
     */
    fun encodeStoredBody(body: MessageBody): String = PcMessageContent.encode(body)

    internal fun storedMessageType(type: MessageType, body: MessageBody): String =
        PcMessageContent.storageType(body, type)

    /**
     * 从 Room 消息类型及 content 列恢复对应正文对象。
     * @param type Room 中保存的具名消息类型。
     * @param content 对应类型的正文编码。
     * @return 类型化正文；损坏数据上抛 IOException，不静默视为空历史，无副作用。
     */
    fun decodeStoredBody(type: MessageType, content: String): MessageBody {
        return PcMessageContent.decode(type, content)
    }

    /**
     * 获取与后端消息类型一一对应的生成解码器。
     * @param type 具名消息类型。
     * @return 该类型正文的解码器；无副作用，不反射或猜测字段。
     */
    private fun bodyDeserializer(type: MessageType): DeserializationStrategy<MessageBody> = when (type) {
        MessageType.TEXT -> TextMessageBody.serializer()
        MessageType.RECALL -> RecallMessageBody.serializer()
        MessageType.TEMPORARY -> TemporaryMessageBody.serializer()
        MessageType.IMAGE -> ImageMessageBody.serializer()
        MessageType.VIDEO -> VideoMessageBody.serializer()
        MessageType.FILE -> FileMessageBody.serializer()
        MessageType.VOICE -> VoiceMessageBody.serializer()
        MessageType.SOUND -> SoundMessageBody.serializer()
        MessageType.EMOJI -> EmojiMessageBody.serializer()
        MessageType.SYSTEM -> SystemMessageBody.serializer()
        MessageType.RTC_CALL -> RtcCallMessageBody.serializer()
        MessageType.UNKNOWN -> UnknownMessageBody.serializer()
    }
}
