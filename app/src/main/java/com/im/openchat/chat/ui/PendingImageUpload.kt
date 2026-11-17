package com.im.openchat.chat.ui

import com.im.openchat.chat.data.FileMessageExtra
import com.im.openchat.chat.data.ImageMessageBody
import com.im.openchat.chat.data.ImageUploadDraft
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.formatChatTime
import java.util.UUID

/** Display-only entries; never persisted or passed to the message send queue. */
internal data class PendingImageUpload(
    val roomId: String,
    val id: String = "image-upload:${UUID.randomUUID()}",
    val sentAt: Long = System.currentTimeMillis(),
    val draft: ImageUploadDraft? = null,
    val queued: Boolean = false,
    val error: String? = null,
) {
    val messageId: String get() = draft?.clientMsgId ?: id

    fun isPublished(messages: List<Message>): Boolean {
        val clientId = draft?.clientMsgId ?: return false
        return messages.any { it.outgoing && (it.id == clientId || it.clientMsgId == clientId) }
    }

    fun asMessage(): Message = Message(
        id = messageId,
        text = "",
        outgoing = true,
        time = formatChatTime(sentAt, "yyyy-MM-dd HH:mm"),
        body = draft?.uploaded ?: ImageMessageBody(extra = draft?.let { FileMessageExtra(it.name, it.size) }),
        clientMsgId = draft?.clientMsgId,
        status = if (error == null) "pending" else "failed",
        sentAt = sentAt,
    )
}

internal fun mergePendingImages(
    roomId: String,
    messages: List<Message>,
    pending: List<PendingImageUpload>,
): List<Message> {
    val local = pending.filter { it.roomId == roomId && !it.isPublished(messages) }
    if (local.isEmpty()) return messages
    // Keep the authoritative order intact, including equal-timestamp server messages.
    val result = messages.toMutableList()
    for (item in local) {
        val index = result.indexOfFirst { it.sentAt > item.sentAt }
        result.add(if (index < 0) result.size else index, item.asMessage())
    }
    return result
}
