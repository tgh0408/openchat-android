package com.im.openchat.chat.data

import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

internal data class PendingConversationRead(val roomId: String, val lastReadMsgId: String)

@Serializable
private data class ConversationReadRequest(val roomId: Long, val lastReadMsgId: Long)

@Serializable
private data class ConversationReadResponse(
    val code: Int,
    val success: Boolean = false,
    val errMsg: String? = null,
    val data: JsonElement = JsonNull,
)

internal fun String.isServerMessageId(): Boolean =
    toLongOrNull()?.takeIf { it > 0 }?.toString() == this

internal val Message.canConfirmRead: Boolean
    get() = status == "sent" && id.isServerMessageId()

/** 已读确认与消息接口使用相同的 Long 精度和授权约定。 */
internal object ConversationReadProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(read: PendingConversationRead): String {
        if (!read.roomId.isServerMessageId() || !read.lastReadMsgId.isServerMessageId()) {
            throw IOException("已读消息标识无效")
        }
        return json.encodeToString(ConversationReadRequest(read.roomId.toLong(), read.lastReadMsgId.toLong()))
    }

    fun decode(response: String, submittedId: String): String {
        try {
            val result = json.decodeFromString<ConversationReadResponse>(response)
            if (result.code in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
            if (!result.success || result.code != 200) {
                throw IOException(result.errMsg?.takeIf(String::isNotBlank) ?: "已读确认失败，将稍后重试")
            }
            val id = json.decodeFromJsonElement<Long>(result.data)
            if (id <= 0 || !submittedId.isServerMessageId() || id < submittedId.toLong()) {
                throw IOException("已读确认位置倒退")
            }
            return id.toString()
        } catch (exception: SerializationException) {
            throw IOException("已读服务返回的数据无效", exception)
        }
    }
}
