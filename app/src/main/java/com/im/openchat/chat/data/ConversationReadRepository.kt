package com.im.openchat.chat.data

import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.ChatStore
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 每个授权对应一个持久化已读队列，与当前选中的会话无关。 */
internal class ConversationReadRepository(
    private val api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
    private val stateMutex: Mutex,
) {
    private val requests = Channel<Boolean>(Channel.CONFLATED)

    fun schedule(immediate: Boolean = false) {
        // 新的已读不能替换等待中的重连或前台提交任务。
        if (immediate) {
            requests.tryReceive()
            requests.trySend(true)
        } else {
            val pending = requests.tryReceive().getOrNull()
            requests.trySend(pending == true)
        }
    }

    suspend fun record(
        roomId: String,
        messageId: String,
        canRecord: () -> Boolean,
        validate: () -> Unit,
        publish: (String, Int) -> Unit,
    ) {
        stateMutex.withLock {
            validate()
            if (!canRecord()) return
            val unread = store.recordConversationRead(owner, roomId, messageId)
            schedule()
            validate()
            publish(roomId, unread)
        }
    }

/** False 信号不会延长固定窗口；重连或切回前台的信号可以立即提交。 */
    private suspend fun waitWindow(millis: Long) {
        withTimeoutOrNull(millis) {
            while (!requests.receive()) { /* Coalesce without moving the deadline. */ }
        }
    }

    suspend fun run(
        validate: () -> Unit,
        publish: (String, Int) -> Unit,
        onUnauthorized: () -> Unit,
        onError: (String) -> Unit,
    ) {
        schedule(immediate = true)
        var retry = false
        var retryDelay = 1000L
        while (true) {
            if (retry) waitWindow(retryDelay)
            else if (!requests.receive()) waitWindow(1000L)
            while (requests.tryReceive().isSuccess) { /* Consume the current batch only. */ }
            validate()
            var failed = false
            try {
                var after = ""
                while (true) {
                    validate()
                    val pending = store.loadPendingConversationReads(owner, after)
                    if (pending.isEmpty()) break
                    for (read in pending) {
                        validate()
                        after = read.roomId
                        try {
                            val response = try {
                                withTimeoutOrNull(3000L) {
                                    api.postJson("/capi/chat/contact/read", ConversationReadProtocol.encode(read))
                                } ?: throw IOException("已读确认超时，将稍后重试")
                            } catch (exception: ApiHttpException) {
                                if (exception.response.statusCode in setOf(401, 403)) {
                                    throw InvalidSessionException("登录已过期，请重新登录", exception)
                                }
                                throw IOException("已读确认失败，将稍后重试", exception)
                            }
                            validate()
                            val acknowledged = ConversationReadProtocol.decode(response.body, read.lastReadMsgId)
                            stateMutex.withLock {
                                validate()
                                val unread = store.acknowledgeConversationRead(owner, read, acknowledged)
                                validate()
                                if (unread != null) publish(read.roomId, unread)
                            }
                        } catch (exception: InvalidSessionException) {
                            throw exception
                        } catch (exception: IOException) {
                            failed = true
                            onError(exception.message ?: "已读确认失败，将稍后重试")
                        }
                    }
                }
                validate()
                retry = store.loadPendingConversationReads(owner).isNotEmpty()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: InvalidSessionException) {
                onUnauthorized()
                return
            } catch (exception: IOException) {
                failed = true
                retry = true
                onError(exception.message ?: "已读同步失败，将稍后重试")
            }
            retryDelay = if (failed) (retryDelay * 2).coerceIn(5000L, 30_000L) else 1000L
        }
    }
}
