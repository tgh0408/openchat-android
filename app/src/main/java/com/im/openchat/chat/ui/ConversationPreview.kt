package com.im.openchat.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.formatPreview
import com.im.openchat.chat.data.previewNameUid
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.data.ChatData

/** 只查询可见行的实际展示用户，切换房间、发送者或授权后不复用旧名称。 */
@Composable
internal fun conversationPreviewText(
    conversation: Conversation,
    data: ChatData,
    repository: AvatarRepository?,
): String {
    val userId = data.owner?.userId ?: data.profile.userId
    val uid = conversation.previewNameUid()
    val contactName = data.contacts.firstOrNull { it.id == uid && !it.group }?.name
    var senderName by remember(repository, conversation.contactId, uid, userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, conversation.contactId, uid, userId, conversation.groupRemoved) {
        if (repository == null || uid == null || uid == userId || conversation.groupRemoved) return@LaunchedEffect
        senderName = repository.load(uid, conversation.contactId)?.name
        senderName = repository.loadMessageName(uid, conversation.contactId) ?: senderName
    }
    return conversation.formatPreview(userId, data.profile.name, senderName ?: contactName)
}
