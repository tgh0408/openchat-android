package com.im.openchat.common.network.sse

import com.im.openchat.common.network.ApiClient
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * 将传输故障和原始原因交回重连循环，不读取错误响应正文。
 * 原始 cause 仅供内部受控堆栈日志使用，不放入 UI 状态或直接打印异常消息。
 */
internal class SseStreamException(val failure: SseFailure, cause: Throwable? = null) :
    IOException(failure.message, cause)

/** 区分建连与数据回调，不用数字标记本地协议状态。 */
private sealed interface StreamSignal {
    data object Opened : StreamSignal
    data class Message(val value: SseMessage) : StreamSignal
}

/**
 * 将单次 OkHttp SSE 连接桥接为顺序挂起消费，标准帧解析和注释过滤由现有库负责。
 *
 * @param api 复用已有身份头和地址限制的网络客户端。
 * @param credentials 当前连接的固定授权快照。
 * @param connectionType 与服务端枚举同名的建连原因。
 * @param lastEventId 上次已交付的协议事件 ID，可为空。
 * @param onOpen 建连成功后通知重连循环，无返回值。
 * @param onMessage 顺序交付原始事件；允许挂起以施加背压，无返回值。
 * @throws SseStreamException HTTP、协议、网络失败或正常 EOF，供上层决定是否重连。
 * 无业务持久化；退出、异常和取消时始终取消缓冲队列及事件源。
 */
internal suspend fun consumeResourceStream(
    api: ApiClient,
    credentials: SseCredentials,
    connectionType: SseConnectionType,
    lastEventId: String?,
    onOpen: () -> Unit,
    onMessage: suspend (SseMessage) -> Unit,
) {
    val signals = Channel<StreamSignal>(capacity = 32)
    val headers = mutableMapOf(
        "Accept" to "text/event-stream",
        "Cache-Control" to "no-cache",
    )
    if (!lastEventId.isNullOrEmpty()) headers["Last-Event-ID"] = lastEventId
    var source: EventSource? = null
    try {
        source = api.openEventStream(
            path = "capi/resource/sse",
            accessToken = credentials.accessToken,
            query = mapOf("connectionType" to connectionType.name),
            headers = headers,
            listener = object : EventSourceListener() {
                /**
                 * 在 OkHttp 工作线程排队建连通知，无返回值；队列满时背压等待，取消时解除等待。
                 *
                 * @param eventSource 当前事件源。
                 * @param response 已通过 OkHttp SSE 状态及媒体类型检查的响应，不在这里读取正文。
                 */
                override fun onOpen(eventSource: EventSource, response: Response) {
                    signals.trySendBlocking(StreamSignal.Opened)
                }

                /**
                 * 将原始事件和账号快照排队，无返回值，不解析业务正文或丢弃未知事件。
                 *
                 * @param eventSource 当前事件源。
                 * @param id OkHttp 解析的协议事件 ID。
                 * @param type 外层 event 字段，缺少时使用 message。
                 * @param data OkHttp 合并多行 data 后的正文。
                 */
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val message = SseMessage(
                        userId = credentials.userId,
                        type = SseEventType.fromWireName(type),
                        eventName = type?.ifEmpty { null } ?: requireNotNull(SseEventType.MESSAGE.wireName),
                        id = id,
                        data = data,
                        receivedAtMillis = System.currentTimeMillis(),
                    )
                    signals.trySendBlocking(StreamSignal.Message(message))
                }

                /**
                 * 正常 EOF 关闭输入队列，无返回值；已排队事件消费完后由上层安排重连。
                 *
                 * @param eventSource 已结束的事件源。
                 */
                override fun onClosed(eventSource: EventSource) {
                    signals.close()
                }

                /**
                 * 将 HTTP 或连接失败归类并保留原始原因后关闭队列，无返回值，不记录令牌或响应正文。
                 *
                 * @param eventSource 已失败的事件源。
                 * @param t OkHttp 报告的原因，仅供框架诊断类型、堆栈及因果链，不向 UI 暴露原始异常文本。
                 * @param response 已取得的 HTTP 响应，可为空，由 OkHttp 管理关闭。
                 */
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val status = response?.code
                    val contentType = response?.body?.contentType()
                    val failure = when {
                        status == 401 || status == 403 -> SseFailure(
                            SseFailureKind.AUTHENTICATION, "SSE 授权已失效，请重新登录", status,
                        )
                        response != null && !response.isSuccessful -> SseFailure(
                            SseFailureKind.HTTP, "SSE 服务返回 HTTP 错误", status,
                        )
                        response != null &&
                            (contentType?.type != "text" || contentType.subtype != "event-stream") -> SseFailure(
                            SseFailureKind.PROTOCOL, "SSE 响应不是 text/event-stream", status,
                        )
                        else -> SseFailure(SseFailureKind.NETWORK, "SSE 网络连接失败", status)
                    }
                    signals.close(SseStreamException(failure, t))
                }
            },
        )
        for (signal in signals) {
            when (signal) {
                StreamSignal.Opened -> onOpen()
                is StreamSignal.Message -> onMessage(signal.value)
            }
        }
        throw SseStreamException(SseFailure(SseFailureKind.END_OF_STREAM, "SSE 数据流已结束"))
    } finally {
        // 先取消队列以解除 OkHttp 回调的背压等待，再关闭连接，不遗留阻塞工作线程。
        signals.cancel()
        source?.cancel()
    }
}
