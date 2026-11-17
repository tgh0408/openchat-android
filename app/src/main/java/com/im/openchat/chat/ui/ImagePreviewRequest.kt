package com.im.openchat.chat.ui

import com.im.openchat.chat.data.Message

/** Freeze the browsing order, but resolve live bodies so recalled images cannot survive in a preview. */
internal data class ImagePreviewRequest(
    val messageIds: List<String>,
    val initialMessageId: String,
) {
    fun resolve(messages: List<Message>): List<Message> {
        val current = messages.associateBy { it.id }
        return messageIds.mapNotNull { current[it]?.takeIf { message -> message.image?.url != null } }
    }
}

internal fun imagePreviewRequest(messages: List<Message>, selectedId: String): ImagePreviewRequest? {
    val ids = messages.filter { it.image?.url != null }.map { it.id }
    return if (selectedId in ids) ImagePreviewRequest(ids, selectedId) else null
}
