package com.im.openchat.chat.data

import com.im.openchat.common.data.ChatData
import com.im.openchat.address.data.Contact

/** 对齐服务端 RoomTypeEnum 的房间类型，协议值不使用 ordinal。 */
enum class RoomType(val wireValue: Int) {
    GROUP(1),
    FRIEND(2),
    FILE_ASSISTANT(3);

    companion object {
        /**
         * 将服务端数值映射为房间类型。
         * @param value 服务端协议值。
         * @return 对应类型，未知值返回 null；无副作用。
         */
        fun fromWireValue(value: Int): RoomType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 消息保留真实发送者和类型化正文；新建消息的 sentAt 为发送时间的 UTC 毫秒值。 */
data class Message(
    val id: String,
    val text: String,
    val outgoing: Boolean,
    val time: String,
    val senderId: String = "",
    val body: MessageBody = TextMessageBody(text),
    val clientMsgId: String? = null,
    val status: String = "sent",
    val sentAt: Long = 0L,
    val replyId: String? = null,
) {
    // 从已解码的图片正文生成展示元数据；无参数、无外部副作用，不再解析 JSON。
    val image: MessageImage? = (body as? ImageMessageBody)?.toMessageImage()
    val emoji: MessageEmoji? = (body as? EmojiMessageBody)?.toMessageEmoji()
}

/** 图片的公开资源标识和展示名称；地址无效时保留不可用占位，不直接下载公开地址。 */
data class MessageImage(val url: String?, val name: String)

/** 表情直接使用公开图片地址；无效资源保留不可用占位。 */
data class MessageEmoji(val id: Long?, val url: String?, val description: String)

/** 会话摘要与本地消息分开保存；远端会话的 contactId 为真实 roomId，并非对方 UID。 */
data class Conversation(
    val contactId: String,
    val messages: List<Message> = emptyList(),
    val unread: Int = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val time: String = "",
    val roomType: RoomType? = null,
    val title: String = "",
    val avatar: String = "",
    val avatarUids: List<String> = emptyList(),
    val participantId: String? = null,
    val preview: String? = null,
    val activeTime: Long = 0,
    val lastMsgId: String? = null,
    val lastReadMsgId: String? = null,
    val groupRemoved: Boolean = false,
    /** 仅派生列表展示携带的完整摘要来源，不持久化或改写远端摘要。 */
    val previewMessage: Message? = null,
)

/**
 * 将会话的展示资料交给复用的聊天界面，不把房间写入通讯录。
 * @return 以真实房间号作为导航标识的展示对象；无副作用。
 */
fun Conversation.asContact(): Contact = Contact(
    id = contactId, name = title.ifBlank { "群聊" }, role = "", avatar = avatar,
    group = roomType == RoomType.GROUP,
)

/**
 * 合并远端会话快照，保留本地历史和本次列表未返回的旧会话。
 * @param remote 当前授权获取的完整可见会话快照，摘要不会转换成消息。
 * @return 合并后的数据；不修改原对象或写入存储。
 */
fun ChatData.mergeConversations(remote: List<Conversation>): ChatData {
    val merged = conversations.associateBy { it.contactId }.toMutableMap()
    for (room in remote) {
        val previous = merged[room.contactId]
        merged[room.contactId] = if (room.groupRemoved && previous != null) {
            previous.copy(
                pinned = room.pinned, muted = room.muted, unread = 0, groupRemoved = true,
            )
        } else {
            room.copy(
                messages = previous?.messages.orEmpty(),
                avatar = room.avatar.ifBlank { previous?.avatar.orEmpty() },
            )
        }
    }
    return copy(conversations = merged.values.toList())
}

/**
 * 按会话标题、摘要或本地消息筛选，按置顶、活跃时间及房间号倒序排列远端会话。
 *
 * @param query 查询文本，忽略首尾空白和大小写。
 * @return 筛选后的会话列表；不修改原数据，无其他副作用。
 */
fun ChatData.searchConversations(query: String): List<Conversation> {
    val term = query.trim()
    // 保留联系人姓名或任意消息正文匹配的会话，返回筛选结果，不修改数据。
    return conversations.map { it.withLatestMessagePreview() }.filter { conversation ->
        conversation.title.contains(term, true) || conversation.preview?.contains(term, true) == true ||
            contacts.any { it.id == conversation.contactId && it.name.contains(term, true) } ||
            conversation.messages.any { it.text.contains(term, true) }
    }.sortedWith(
        compareByDescending<Conversation> { it.pinned }
            .thenByDescending { it.activeTime }
            .thenByDescending { if (it.roomType == null) 0 else it.contactId.length }
            .thenByDescending { if (it.roomType == null) "" else it.contactId },
    )
}

/**
 * 打开本地联系人会话并清除本地未读数；远端会话不在尚未读取消息时伪造已读。
 *
 * @param contactId 本地联系人标识或真实房间号。
 * @return 更新后的数据；联系人不存在时返回原数据，不写入存储。
 */
fun ChatData.open(contactId: String): ChatData {
    if (conversations.any { it.contactId == contactId && it.roomType != null }) return this
    if (contacts.none { it.id == contactId }) return this
    val existing = conversations.any { it.contactId == contactId }
    val activeTime = System.currentTimeMillis()
    // 已有会话仅复制目标项以清除未读数，其他项保持不变，不修改原列表。
    return copy(conversations = if (existing) conversations.map {
        if (it.contactId == contactId) it.copy(unread = 0, activeTime = activeTime) else it
    } else listOf(
        Conversation(
            contactId = contactId,
            activeTime = activeTime,
            time = formatConversationTime(activeTime),
        ),
    ) + conversations)
}

/**
 * 将非空文本追加到本地会话并移至列表前面，不向服务端投递。
 *
 * @param contactId 目标联系人标识。
 * @param text 待发送文本，会去除首尾空白。
 * @param id 新消息标识。
 * @param time 消息显示时间。
 * @return 更新后的数据；远端会话、文本为空或联系人不存在时返回原数据，不写入存储。
 */
fun ChatData.send(contactId: String, text: String, id: String, time: String): ChatData {
    if (conversations.any { it.contactId == contactId && it.roomType != null }) return this
    val trimmed = text.trim()
    if (trimmed.isEmpty() || contacts.none { it.id == contactId }) return this
    val opened = open(contactId)
    val conversation = opened.conversations.first { it.contactId == contactId }
    val activeTime = System.currentTimeMillis()
    return opened.copy(conversations = listOf(conversation.copy(
        messages = conversation.messages + Message(id, trimmed, true, time, sentAt = activeTime),
        time = time, activeTime = activeTime,
    )) + opened.conversations.filterNot { it.contactId == contactId })
}

/**
 * 切换会话置顶状态。
 *
 * @param id 会话对应的联系人标识。
 * @return 更新后的数据；不修改原数据或写入存储。
 */
fun ChatData.togglePin(id: String): ChatData = copy(
    // 仅复制目标会话并反转置顶状态，返回新列表，不修改原数据。
    conversations = conversations.map {
        if (it.contactId == id) it.copy(pinned = !it.pinned) else it
    },
)

/**
 * 切换会话的本地免打扰标记。
 *
 * @param id 会话对应的联系人标识。
 * @return 更新后的数据；不修改原数据，不配置系统通知或写入存储。
 */
fun ChatData.toggleMute(id: String): ChatData = copy(
    // 仅复制目标会话并反转免打扰标记，返回新列表，不修改原数据。
    conversations = conversations.map {
        if (it.contactId == id) it.copy(muted = !it.muted) else it
    },
)

/**
 * 删除指定会话及消息，保留联系人。
 *
 * @param id 会话对应的联系人标识。
 * @return 更新后的数据；不修改原数据或写入存储。
 */
fun ChatData.delete(id: String): ChatData = copy(
    conversations = conversations.filterNot { it.contactId == id },
)
