package com.im.openchat.chat.data

import com.im.openchat.common.config.AppConfig
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * SQLite 正文遵循 PC 端存储约定，不等同于 HTTP 正文约定。
 * 当前消息读写应通过 MessageHistoryProtocol 使用此适配器。
 */
internal object PcMessageContent {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val optionalJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Resource(val url: String?, val fileName: String, val fileSize: Long?)

    @Serializable
    private data class Emoji(val emojiId: String, val url: String?)

    @Serializable
    private data class Recall(
        val recallUid: String?,
        val recallName: String?,
        val recallGroupNickname: String? = null,
    )

    @Serializable
    private data class Video(
        val url: String?, val fileName: String, val fileSize: Long?,
        val thumbUrl: String?, val thumbSize: Long?, val thumbWidth: Int,
        val thumbHeight: Int, val duration: Long,
    )

    @Serializable
    private data class Audio(val url: String, val fileName: String, val fileSize: Long, val second: Int)

    @Serializable
    private data class Upload(
        val targetType: Int, val cancelled: Boolean,
        val fileName: String, val fileSize: Long, val duration: Long? = null,
        val thumbWidth: Int? = null, val thumbHeight: Int? = null,
    )

    @Serializable
    private data class Invitation(
        val kind: String = "group-invitation",
        val inviterName: String, val memberName: String, val content: String,
    )

    @Serializable
    private data class Removal(
        val kind: String = "group-removal", val operatorName: String,
        val memberUid: String, val memberName: String, val content: String,
    )

    @Serializable
    private data class SystemKind(val kind: String? = null)

    @Serializable
    private data class Rtc(
        val callId: String, val mediaMode: Int, val state: Int,
        val endReason: String?, val endedByUid: String?, val acceptedAt: String?,
        val endedAt: String?, val durationSeconds: Long,
    )

    fun storageType(body: MessageBody, type: MessageType): String =
        if (body is TemporaryMessageBody && body.extra?.cancelled == true) "upload-cancelled" else type.storageValue

    fun encode(body: MessageBody): String = when (body) {
        is TextMessageBody -> body.content
        is ImageMessageBody -> json.encodeToString(image(body))
        is FileMessageBody -> json.encodeToString(file(body.content, body.extra))
        // PC 当前不会收到线上的类型 7；保留其身份和元数据。
        is VoiceMessageBody -> json.encodeToString(file(body.content, body.extra))
        is EmojiMessageBody -> {
            val id = body.extra?.emojiId?.takeIf { it > 0 }?.toString()
                ?: throw IOException("表情包标识无效")
            json.encodeToString(Emoji(id, publicUrl(body.content, emoji = true)))
        }
        is RecallMessageBody -> {
            if (body.recallUid != null && body.recallUid <= 0 ||
                listOf(body.recallName, body.recallGroupNickname).any {
                    it != null && (it.length > 64 || '\u0000' in it)
                }
            ) throw IOException("撤回消息格式无效")
            optionalJson.encodeToString(Recall(
                body.recallUid?.toString(),
                body.recallName.takeIf { body.recallUid != null },
                body.recallGroupNickname.takeIf { body.recallUid != null },
            ))
        }
        is VideoMessageBody -> json.encodeToString(video(body))
        is SoundMessageBody -> audio(body)?.let { json.encodeToString(it) } ?: "{}"
        is TemporaryMessageBody -> optionalJson.encodeToString(upload(body))
        is SystemMessageBody -> encodeSystem(body)
        is RtcCallMessageBody -> json.encodeToString(rtc(body))
        UnknownMessageBody -> throw IOException("未知消息不能写入正文")
    }

    fun decode(type: MessageType, content: String): MessageBody = try {
        when (type) {
            MessageType.TEXT -> TextMessageBody(content)
            MessageType.IMAGE -> json.decodeFromString<Resource>(content).let {
                ImageMessageBody(it.url, FileMessageExtra(it.fileName, it.fileSize))
            }
            MessageType.FILE -> json.decodeFromString<Resource>(content).let {
                FileMessageBody(it.url, FileMessageExtra(it.fileName, it.fileSize))
            }
            MessageType.VOICE -> json.decodeFromString<Resource>(content).let {
                VoiceMessageBody(it.url, FileMessageExtra(it.fileName, it.fileSize))
            }
            MessageType.EMOJI -> json.decodeFromString<Emoji>(content).let {
                EmojiMessageBody(it.url, EmojiMessageExtra(it.emojiId.toLongOrNull()))
            }
            MessageType.RECALL -> json.decodeFromString<Recall>(content).let {
                RecallMessageBody(it.recallUid?.toLongOrNull(), it.recallName, it.recallGroupNickname)
            }
            MessageType.VIDEO -> json.decodeFromString<Video>(content).let {
                VideoMessageBody(it.url, VideoMessageExtra(
                    it.fileName, it.fileSize, it.duration, it.thumbUrl,
                    it.thumbSize, it.thumbWidth, it.thumbHeight,
                ))
            }
            MessageType.SOUND -> if (content == "{}") SoundMessageBody() else {
                json.decodeFromString<Audio>(content).let {
                    SoundMessageBody(it.url, SoundMessageExtra(it.fileName, it.fileSize, it.second))
                }
            }
            MessageType.TEMPORARY -> json.decodeFromString<Upload>(content).let {
                TemporaryMessageBody(extra = TemporaryMessageExtra(
                    MessageType.fromWireValue(it.targetType), it.fileName, it.fileSize,
                    it.duration, it.thumbWidth, it.thumbHeight, it.cancelled,
                ))
            }
            MessageType.SYSTEM -> decodeSystem(content)
            MessageType.RTC_CALL -> json.decodeFromString<Rtc>(content).let {
                RtcCallMessageBody("", RtcCallMessageExtra(
                    it.callId,
                    when (it.mediaMode) {
                        0 -> MessageRtcMediaMode.AUDIO
                        1 -> MessageRtcMediaMode.VIDEO
                        else -> throw IOException("通话媒体类型无效")
                    },
                    terminalState(it.state), it.endReason, it.endedByUid?.toLongOrNull(),
                    it.acceptedAt, it.endedAt, it.durationSeconds,
                ))
            }
            MessageType.UNKNOWN -> UnknownMessageBody
        }
    } catch (exception: kotlinx.serialization.SerializationException) {
        throw IOException("聊天记录缓存内容无效，请重试", exception)
    }

    private fun image(body: ImageMessageBody): Resource = Resource(
        publicUrl(body.content),
        name(body.extra?.fileName) ?: "图片",
        body.extra?.size?.takeIf { it in 1..50L * 1024 * 1024 },
    )

    private fun file(url: String?, extra: FileMessageExtra?): Resource {
        val address = publicUrl(url)
        val name = name(extra?.fileName)
        val size = extra?.size?.takeIf { it in 1..6L * 1024 * 1024 * 1024 }
        return if (address != null && name != null && size != null) Resource(address, name, size)
        else Resource(null, "文件不可用", null)
    }

    private fun video(body: VideoMessageBody): Video {
        val extra = body.extra
        val url = publicUrl(body.content)
        val thumb = publicUrl(extra?.thumbUrl)
        val name = name(extra?.fileName)?.takeIf(::videoName)
        if (url == null || thumb == null || name == null || extra == null ||
            !bounded(extra.size, 2L * 1024 * 1024 * 1024) ||
            !bounded(extra.thumbSize, 2L * 1024 * 1024) ||
            !bounded(extra.thumbWidth?.toLong(), 1920) || !bounded(extra.thumbHeight?.toLong(), 1920) ||
            !bounded(extra.duration, 86_400_000)
        ) return Video(null, "视频.mp4", null, null, null, 160, 240, 0)
        return Video(url, name, extra.size, thumb, extra.thumbSize,
            extra.thumbWidth!!, extra.thumbHeight!!, extra.duration!!)
    }

    private fun audio(body: SoundMessageBody): Audio? {
        val extra = body.extra ?: return null
        val url = publicUrl(body.content) ?: return null
        val name = extra.fileName?.takeIf {
            Regex("[^/\\\\\\u0000-\\u001f\\u007f]{1,251}\\.wav").matches(it)
        } ?: return null
        val size = extra.size ?: return null
        if (size !in 32044..3_840_044L || (size - 44) % 2 != 0L ||
            extra.second != ((size - 44 + 31_999) / 32_000).toInt()
        ) return null
        return Audio(url, name, size, extra.second!!)
    }

    private fun upload(body: TemporaryMessageBody): Upload {
        val extra = body.extra ?: throw IOException("上传占位元数据无效")
        val name = name(extra.fileName) ?: throw IOException("上传文件名无效")
        val video = extra.targetType == MessageType.VIDEO
        if (extra.targetType != MessageType.FILE && !video ||
            extra.size !in 1..(if (video) 2L else 6L) * 1024 * 1024 * 1024 ||
            video && (!videoName(name) || !bounded(extra.duration, 86_400_000) ||
                !bounded(extra.thumbWidth?.toLong(), 1920) || !bounded(extra.thumbHeight?.toLong(), 1920)) ||
            !video && (extra.duration != null || extra.thumbWidth != null || extra.thumbHeight != null)
        ) throw IOException("上传占位元数据无效")
        return Upload(extra.targetType.wireValue, extra.cancelled, name, extra.size,
            extra.duration, extra.thumbWidth, extra.thumbHeight)
    }

    private fun encodeSystem(body: SystemMessageBody): String {
        if (body.content.isBlank() || body.content.length > 65536 || '\u0000' in body.content) {
            throw IOException("系统提示正文无效")
        }
        return when (val extra = body.extra) {
            null -> body.content
            is GroupInvitationMessageExtra -> {
                if (!systemName(extra.inviterName) || !systemName(extra.memberName) ||
                    body.content != "${extra.inviterName} 邀请 ${extra.memberName} 进入了群聊"
                ) throw IOException("系统提示昵称快照无效")
                json.encodeToString(Invitation(
                    inviterName = extra.inviterName, memberName = extra.memberName, content = body.content,
                ))
            }
            is GroupRemovalMessageExtra -> {
                if (!systemName(extra.operatorName) || !systemName(extra.memberName) || extra.memberUid <= 0 ||
                    body.content != "${extra.memberName} 被 ${extra.operatorName} 移出群聊"
                ) throw IOException("系统提示昵称快照无效")
                json.encodeToString(Removal(
                    operatorName = extra.operatorName, memberUid = extra.memberUid.toString(),
                    memberName = extra.memberName, content = body.content,
                ))
            }
        }
    }

    private fun decodeSystem(content: String): SystemMessageBody {
        val kind = runCatching { json.decodeFromString<SystemKind>(content).kind }.getOrNull()
        return runCatching {
            val body = when (kind) {
                "group-invitation" -> json.decodeFromString<Invitation>(content).let {
                    SystemMessageBody(it.content, GroupInvitationMessageExtra(it.inviterName, it.memberName))
                }
                "group-removal" -> json.decodeFromString<Removal>(content).let {
                    SystemMessageBody(it.content, GroupRemovalMessageExtra(
                        it.operatorName, it.memberUid.toLong(), it.memberName,
                    ))
                }
                else -> SystemMessageBody(content)
            }
            encodeSystem(body)
            body
        }.getOrElse { SystemMessageBody(content) }
    }

    private fun rtc(body: RtcCallMessageBody): Rtc {
        val extra = body.extra ?: throw IOException("通话消息缺少摘要")
        val state = when (extra.state) {
            MessageRtcCallState.REJECTED -> 3
            MessageRtcCallState.CANCELED -> 4
            MessageRtcCallState.ENDED -> 5
            MessageRtcCallState.EXPIRED -> 6
            else -> throw IOException("通话摘要只接受终态")
        }
        if (extra.callId.isEmpty() || extra.callId.length > 128 || '\u0000' in extra.callId ||
            extra.durationSeconds == null || extra.durationSeconds < 0 ||
            extra.endedByUid != null && extra.endedByUid <= 0 ||
            extra.endReason?.let { it.length > 128 || '\u0000' in it } == true
        ) throw IOException("通话摘要无效")
        return Rtc(extra.callId, if (extra.mediaMode == MessageRtcMediaMode.AUDIO) 0 else 1,
            state, extra.endReason, extra.endedByUid?.toString(), timestamp(extra.acceptedAt),
            timestamp(extra.endedAt), extra.durationSeconds)
    }

    private fun terminalState(state: Int): MessageRtcCallState = when (state) {
        3 -> MessageRtcCallState.REJECTED
        4 -> MessageRtcCallState.CANCELED
        5 -> MessageRtcCallState.ENDED
        6 -> MessageRtcCallState.EXPIRED
        else -> throw IOException("通话摘要只接受终态")
    }

    /**
     * 将当前服务端 Date 文本或带时区 ISO 时间转换为 PC 持久化的 UTC 时间。
     * @param value 通话摘要时间，null 保持为空；无时区文本使用服务端配置，不使用设备时区。
     * @return UTC ISO 文本；格式无效时抛出 IOException，无存储副作用。
     */
    private fun timestamp(value: String?): String? {
        if (value == null) return null
        val (pattern, zone) = when {
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").matches(value) ->
                AppConfig.SERVER_DATE_TIME_PATTERN to AppConfig.SERVER_TIME_ZONE_ID
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})""").matches(value) ->
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" to "UTC"
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})""").matches(value) ->
                "yyyy-MM-dd'T'HH:mm:ssXXX" to "UTC"
            else -> throw IOException("通话摘要时间格式无效")
        }
        val position = java.text.ParsePosition(0)
        val date = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zone)
            isLenient = false
        }.parse(value, position)
        if (date == null || position.index != value.length) throw IOException("通话摘要时间格式无效")
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)
    }

    private fun publicUrl(value: String?, emoji: Boolean = false): String? {
        if (value == null || value.length > 2048 || value.any { it <= ' ' }) return null
        return value.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() &&
                (emoji || it.encodedQuery == null && it.encodedFragment == null)
        }?.toString()
    }

    private fun name(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= 255 && it.none { char ->
            char == '/' || char == '\\' || char < ' ' || char == '\u007f'
        }
    }

    private fun bounded(value: Long?, max: Long): Boolean = value != null && value in 1..max

    private fun videoName(value: String): Boolean =
        value.lastIndexOf('.') > 0 && '?' !in value && '#' !in value &&
            value.substringAfterLast('.', "").lowercase(Locale.US) in setOf(
                "mp4", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpeg", "mpg",
            )

    private fun systemName(value: String): Boolean =
        value.isNotBlank() && value.length <= 1024 && '\u0000' !in value
}
