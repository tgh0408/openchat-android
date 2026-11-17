package com.im.openchat.chat.data

import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.ChatStore
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** 按授权隔离的文本/图片发件队列；HTTP 接收成功不代表消息已发送。 */
internal class MessageSendRepository(
    private val api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
) {
    private data class PendingMessage(val roomId: String, val clientMsgId: String, val request: String)

    private val requests = Channel<PendingMessage>(Channel.UNLIMITED)
    private val prepareMutex = Mutex()
    private var prepared = false

    suspend fun prepare() = prepareMutex.withLock {
        if (!prepared) {
            store.recoverPendingMessages(owner)
            prepared = true
        }
    }

    suspend fun queue(
        roomId: String,
        content: String,
        validate: () -> Unit,
        clientMsgId: String = UUID.randomUUID().toString(),
    ) = queueBody(roomId, TextMessageBody(content), validate, clientMsgId)

    suspend fun queueImage(
        roomId: String,
        image: ImageMessageBody,
        validate: () -> Unit,
        clientMsgId: String = UUID.randomUUID().toString(),
    ) = queueBody(roomId, image, validate, clientMsgId)

    private suspend fun queueBody(
        roomId: String, body: MessageBody, validate: () -> Unit, clientMsgId: String,
    ) {
        if (roomId.toLongOrNull()?.takeIf { it > 0 }?.toString() != roomId) {
            throw IOException("消息所属会话无效")
        }
        if (clientMsgId.isBlank() || clientMsgId.length > 64 || '\u0000' in clientMsgId) {
            throw IOException("客户端消息标识无效")
        }
        val request = when (body) {
            is TextMessageBody -> {
                if (body.content.isBlank() || body.content.length > 5000 || '\u0000' in body.content) {
                    throw IOException("消息不能为空，且不能超过5000个字符")
                }
                MessageHistoryProtocol.encodeTextRequest(TextMessageRequest(
                    roomId.toLong(), clientMsgId, body, MessageType.TEXT,
                ))
            }
            is ImageMessageBody -> MessageHistoryProtocol.encodeImageRequest(ImageMessageRequest(
                roomId.toLong(), clientMsgId, body.toSendBody(),
            ))
            else -> throw IOException("不支持的待发送消息类型")
        }
        validate()
        prepare()
        validate()
        // Once persisted, always hand off to the authorization-scoped queue, even if the picker closes.
        withContext(NonCancellable) {
            val queued = store.saveOutgoingMessage(owner, roomId, clientMsgId, body)
            if (queued) requests.trySend(PendingMessage(roomId, clientMsgId, request)).getOrThrow()
        }
    }

    /** 所有 HTTP 请求和确认计时任务均属于当前授权副作用。 */
    suspend fun run(
        validate: (String) -> Unit,
        publish: suspend (String) -> Unit,
        refresh: (String) -> Unit,
        reconcile: suspend (String) -> Unit,
        onUnauthorized: () -> Unit,
        onError: suspend (String) -> Unit,
    ): Unit = coroutineScope {
        for (message in requests) launch {
            try {
                validate(message.roomId)
                publish(message.roomId)
                val response = try {
                    withTimeoutOrNull(10_000) {
                        api.postJson(
                            "/capi/chat/msg",
                            message.request,
                        )
                    } ?: throw IOException("消息发送请求超时，请点击失败图标重试")
                } catch (exception: ApiHttpException) {
                    if (exception.response.statusCode in setOf(401, 403)) {
                        throw InvalidSessionException("登录已过期，请重新登录", exception)
                    }
                    throw IOException("消息发送失败（HTTP ${exception.response.statusCode}）", exception)
                }
                validate(message.roomId)
                MessageHistoryProtocol.decodeSendResult(response.body)
                refresh(message.roomId)
                delay(10_000)
                validate(message.roomId)
                // 离开房间页面后，通过单次历史检查同样可以确认发送结果。
                reconcile(message.roomId)
                validate(message.roomId)
                if (store.failPendingText(owner, message.roomId, message.clientMsgId)) {
                    publish(message.roomId)
                    onError("消息尚未确认，请点击失败图标重试")
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: InvalidSessionException) {
                onUnauthorized()
            } catch (exception: IOException) {
                validate(message.roomId)
                try {
                    if (store.failPendingText(owner, message.roomId, message.clientMsgId)) {
                        publish(message.roomId)
                        onError(exception.message ?: "消息发送失败，请重试")
                    }
                } catch (storageException: IOException) {
                    onError(storageException.message ?: "消息发送状态保存失败，请重试")
                }
            }
        }
    }
}
