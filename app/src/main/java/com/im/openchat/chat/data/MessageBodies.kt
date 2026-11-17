package com.im.openchat.chat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 按服务端 MessageTypeEnum 区分的正文对象，不向业务层暴露 JSON 树。 */
@Serializable
sealed interface MessageBody

/** 对应 TextMsgHandler 返回的 TotalMsgResp。 */
@Serializable
@SerialName("text")
data class TextMessageBody(
    val content: String,
    val urlContentMap: Map<String, MessageUrlInfo>? = null,
    val atUidList: List<Long>? = null,
    val reply: MessageReplyInfo? = null,
) : MessageBody

/** 对应服务端 RecallMsgResp，不包含被撤回的原文或附件地址。 */
@Serializable
@SerialName("recalled")
data class RecallMessageBody(
    val recallUid: Long? = null,
    val recallName: String? = null,
    val recallGroupNickname: String? = null,
) : MessageBody

/** 对应 TemporaryMsgHandler 返回的上传占位，不含可下载资源地址。 */
@Serializable
@SerialName("temporary")
data class TemporaryMessageBody(
    val content: String? = null,
    val extra: TemporaryMessageExtra? = null,
) : MessageBody

/** 对应 ImgMsgHandler 的 TotalMsgResp；当前 extra 实际为 FileMsgDTO，不是 ImgMsgDTO。 */
@Serializable
@SerialName("image")
data class ImageMessageBody(
    val content: String? = null,
    val extra: FileMessageExtra? = null,
    val urlContentMap: Map<String, MessageUrlInfo>? = null,
    val atUidList: List<Long>? = null,
    val reply: MessageReplyInfo? = null,
) : MessageBody

/** 对应 VideoMsgHandler 的 TotalMsgResp。 */
@Serializable
@SerialName("video")
data class VideoMessageBody(
    val content: String? = null,
    val extra: VideoMessageExtra? = null,
) : MessageBody

/** 对应 FileMsgHandler 的 TotalMsgResp。 */
@Serializable
@SerialName("file")
data class FileMessageBody(
    val content: String? = null,
    val extra: FileMessageExtra? = null,
    val urlContentMap: Map<String, MessageUrlInfo>? = null,
    val atUidList: List<Long>? = null,
    val reply: MessageReplyInfo? = null,
) : MessageBody

/** 对应 type=7 的 VoiceMsgHandler；其扩展为 FileMsgDTO，没有录音秒数字段。 */
@Serializable
@SerialName("voice")
data class VoiceMessageBody(
    val content: String? = null,
    val extra: FileMessageExtra? = null,
    val urlContentMap: Map<String, MessageUrlInfo>? = null,
    val atUidList: List<Long>? = null,
    val reply: MessageReplyInfo? = null,
) : MessageBody

/** 对应 type=8 的 SoundMsgHandler，录音时长来自 SoundMsgDTO.second。 */
@Serializable
@SerialName("audio")
data class SoundMessageBody(
    val content: String? = null,
    val extra: SoundMessageExtra? = null,
) : MessageBody

/** 对应 EmojiMsgHandler 的 TotalMsgResp。 */
@Serializable
@SerialName("emoji")
data class EmojiMessageBody(
    val content: String? = null,
    val extra: EmojiMessageExtra? = null,
    val urlContentMap: Map<String, MessageUrlInfo>? = null,
    val atUidList: List<Long>? = null,
    val reply: MessageReplyInfo? = null,
) : MessageBody

/** 对应 SystemMsgHandler，完整正文及结构化昵称快照均为服务端返回值。 */
@Serializable
@SerialName("system")
data class SystemMessageBody(
    val content: String,
    val extra: SystemMessageExtra? = null,
) : MessageBody

/** 对应 RtcCallMsgHandler 的 TotalMsgResp，不发起任何通话。 */
@Serializable
@SerialName("rtc-call")
data class RtcCallMessageBody(
    val content: String,
    val extra: RtcCallMessageExtra? = null,
) : MessageBody

/** 未识别类型不解码或保留其原始正文，但仍保留外层消息 ID 和时间。 */
@Serializable
@SerialName("unknown")
data object UnknownMessageBody : MessageBody

/** 对应服务端 UrlInfo。 */
@Serializable
data class MessageUrlInfo(
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
)

/** 对应 TotalMsgResp.ReplyMsg，独立于消息外层的 replyId 和 gapCount。 */
@Serializable
data class MessageReplyInfo(
    val replyId: Long? = null,
    val gapCount: Long? = null,
)

/** 对应公开 FileMsgDTO；忽略服务端存储用的 upLoadPath，不将其保存在 Android 消息中。 */
@Serializable
data class FileMessageExtra(
    val fileName: String? = null,
    val size: Long? = null,
)

/** 对应 VideoMsgDTO，duration 使用毫秒，size 和 thumbSize 使用字节。 */
@Serializable
data class VideoMessageExtra(
    val fileName: String? = null,
    val size: Long? = null,
    val duration: Long? = null,
    val thumbUrl: String? = null,
    val thumbSize: Long? = null,
    val thumbWidth: Int? = null,
    val thumbHeight: Int? = null,
)

/** 对应 SoundMsgDTO，second 使用秒。 */
@Serializable
data class SoundMessageExtra(
    val fileName: String? = null,
    val size: Long? = null,
    val second: Int? = null,
)

/** 对应 EmojisMsgDTO。 */
@Serializable
data class EmojiMessageExtra(
    val emojiId: Long? = null,
    val description: String? = null,
)

/** 对应 TemporaryMsgDTO，嵌套 targetType 同样按协议值映射为具名枚举。 */
@Serializable
data class TemporaryMessageExtra(
    val targetType: MessageType,
    val fileName: String,
    val size: Long,
    val duration: Long? = null,
    val thumbWidth: Int? = null,
    val thumbHeight: Int? = null,
    val cancelled: Boolean,
)

/** 对应系统扩展的 kind 多态字段；具体字段交由生成的序列化器读取。 */
@Serializable
sealed interface SystemMessageExtra

/** 对应 GroupInvitationMsgDTO。 */
@Serializable
@SerialName("group-invitation")
data class GroupInvitationMessageExtra(
    val inviterName: String,
    val memberName: String,
) : SystemMessageExtra

/** 对应 GroupRemovalMsgDTO。 */
@Serializable
@SerialName("group-removal")
data class GroupRemovalMessageExtra(
    val operatorName: String,
    val memberUid: Long,
    val memberName: String,
) : SystemMessageExtra

/** RTC 摘要使用服务端 RtcCallMsgDTO 的名称值，不与 RTC HTTP 接口的序号混用。 */
@Serializable
enum class MessageRtcMediaMode { AUDIO, VIDEO }

/** 对应 RtcCallMsgDTO.state 的名称值，不使用枚举 ordinal。 */
@Serializable
enum class MessageRtcCallState { RINGING, ACCEPTED, CONNECTED, REJECTED, CANCELED, ENDED, EXPIRED }

/** 对应 RtcCallMsgDTO；Date 文本按服务端配置解析后以 UTC 持久化，不依赖设备时区。 */
@Serializable
data class RtcCallMessageExtra(
    val callId: String,
    val mediaMode: MessageRtcMediaMode,
    val state: MessageRtcCallState,
    val endReason: String? = null,
    val endedByUid: Long? = null,
    val acceptedAt: String? = null,
    val endedAt: String? = null,
    val durationSeconds: Long? = null,
)
