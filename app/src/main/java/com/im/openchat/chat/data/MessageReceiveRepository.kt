package com.im.openchat.chat.data

import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.ChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 当前授权的文本和图片收件箱；在主线程入队，由唯一消费者顺序落库。 */
internal class MessageReceiveRepository(
    private val store: ChatStore,
    private val owner: ChatDataOwner,
) {
    private val pending = linkedMapOf<String, HistoryMessage>()

    suspend fun enqueue(data: String): Boolean {
        // SSE 收集器运行在 Compose 主作用域，JSON 解码和校验应放在界面线程之外。
        val message = withContext(Dispatchers.Default) {
            MessageHistoryProtocol.decodeMessagePush(data)?.toHistoryMessage()
        } ?: return false
        pending[message.id] = message
        // 未出现在快照中的房间不无限积累；超出的旧推送由进入房间后的历史补齐。
        if (pending.size > 500) pending.remove(pending.keys.first())
        return true
    }

    suspend fun flush(
        conversations: () -> List<Conversation>,
        validate: () -> Unit,
        publish: (String, MessageHistoryResult) -> Unit,
    ) {
        validate()
        for ((roomId, messages) in pending.values.toList().groupBy { it.roomId }) {
            validate()
            val room = conversations().firstOrNull { it.contactId == roomId && it.roomType != null } ?: continue
            if (!room.groupRemoved) {
                // 不推进连续历史边界；复用事务去重，并按 clientMsgId 原子替换发送占位。
                store.saveMessageHistoryPage(owner, roomId, messages, null)
                validate()
                val result = store.loadMessageHistory(owner, roomId)
                validate()
                publish(roomId, result)
            }
            for (message in messages) {
                if (pending[message.id] == message) pending.remove(message.id)
            }
        }
    }
}
