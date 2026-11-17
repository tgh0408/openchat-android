package com.im.openchat.chat.data

/** 对齐 PC 本地/远端摘要选择，同时保留正文、发送者、类型、消息 ID 和时间。 */
internal fun Conversation.withLatestMessagePreview(): Conversation {
    if (roomType == null || groupRemoved) return this
    val latest = messages.asSequence()
        .filter { it.canConfirmRead && it.sentAt > 0L }
        .maxWithOrNull(compareBy<Message> { it.sentAt }.thenBy { it.id.toLong() }) ?: return this
    if (preview != null) {
        if (latest.sentAt < activeTime) return this
        // PC 同时刻优先使用已经格式化的远端摘要，撤回和系统提示除外。
        if (latest.sentAt == activeTime &&
            latest.body !is RecallMessageBody && latest.body !is SystemMessageBody
        ) return this
    }
    return copy(
        preview = latest.previewText(), previewMessage = latest,
        activeTime = maxOf(activeTime, latest.sentAt), time = formatConversationTime(maxOf(activeTime, latest.sentAt)),
    )
}

/** 只为原始消息摘要补资料；服务端摘要已经包含名称，不能再次添加发送者。 */
internal fun Conversation.previewNameUid(): String? {
    val message = previewMessage ?: return null
    return when (val body = message.body) {
        is SystemMessageBody -> null
        is RecallMessageBody -> body.recallUid?.toString()
        else -> message.senderId.takeIf { roomType != RoomType.FILE_ASSISTANT && it.isNotBlank() }
    }
}

internal fun Conversation.formatPreview(
    userId: String,
    userNickname: String,
    senderName: String? = null,
): String {
    if (roomType == null) return messages.lastOrNull()?.text ?: "暂无消息"
    val message = previewMessage ?: return preview ?: "暂无消息"
    when (val body = message.body) {
        is SystemMessageBody -> {
            val removal = body.extra as? GroupRemovalMessageExtra
            return if (removal != null && removal.memberUid.toString() == userId) {
                "你被 ${removal.operatorName} 移出群聊"
            } else body.content
        }
        is RecallMessageBody -> {
            val uid = body.recallUid?.toString() ?: return "消息已撤回"
            if (uid == userId) return "你撤回了一条消息"
            val name = senderName.nonBlank() ?: body.recallGroupNickname.nonBlank() ?:
                body.recallName.nonBlank() ?: "用户 $uid"
            return "${name}撤回了一条消息"
        }
        else -> {
            val uid = previewNameUid() ?: return message.previewText()
            val name = if (uid == userId) userNickname.nonBlank() else senderName.nonBlank()
            return "${name ?: "用户 $uid"}:${message.previewText()}"
        }
    }
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun Message.previewText(): String = when (val body = body) {
    is TextMessageBody -> body.content
    is ImageMessageBody -> "[图片]"
    is EmojiMessageBody -> "[表情包]"
    is VideoMessageBody -> "[视频]"
    is FileMessageBody -> "[文件]"
    is VoiceMessageBody, is SoundMessageBody -> "[语音]"
    is RtcCallMessageBody -> "[通话]"
    is TemporaryMessageBody -> when {
        body.extra?.cancelled == true -> "[已取消发送]"
        body.extra?.targetType == MessageType.FILE -> "[文件上传中]"
        else -> "[视频上传中]"
    }
    is SystemMessageBody -> body.content
    is RecallMessageBody -> "消息已撤回"
    UnknownMessageBody -> text
}
