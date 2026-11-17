package com.im.openchat.common.network.sse

import android.util.Log
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/** SSE 诊断阶段，仅用于本机日志，不充当协议类型或业务错误码。 */
internal enum class SseLogStage {
    CONNECT,
    READ,
    DISPATCH,
    RETRY_WAIT,
    CLOSE,
    INVALIDATION,
    APP_START,
    EVENT_CALLBACK,
}

/** 输出受控连接上下文和异常堆栈，不输出原始异常消息、请求、响应或业务正文。 */
internal object SseLogger {
    private const val TAG = "OpenChatSse"
    private const val MAX_THROWABLES = 8
    private const val MAX_FRAMES = 32
    private const val MAX_SUPPRESSED = 4
    private const val MAX_LINES = 160

    /**
     * 记录一次故障及其异常链，无返回值；按行写入 Logcat，避免单条超长日志截断整个堆栈。
     *
     * @param stage 发生故障时的连接或接入阶段。
     * @param exception 原始异常，仅提取类型、堆栈和因果关系，不调用其 toString 或打印 message。
     * @param connectionId 本次授权的进程内递增诊断编号，不含用户、设备或令牌信息。
     * @param connectionType 当前尝试使用的建连类型。
     * @param status 故障前的连接状态。
     * @param retryAttempt 连续不稳定连接次数。
     * @param durationMillis 当前尝试或关闭操作已经消耗的单调时钟毫秒数。
     * @param retryDelayMillis 本次安排的下一次退避时间。
     * @param failure 已脱敏的故障对象，仅记录其枚举及真实 HTTP 状态，不输出自由文本。
     * @param hasLastEventId 是否持有协议游标，不记录游标值。
     * @param eventType 接收回调失败时的受控事件类型，不记录原始事件名。
     * @param invalidationReason 已校验的失效原因，可为空。
     * 协程取消不作为故障；限制异常链、suppressed 数量和堆栈长度，不修改原异常。
     */
    @Synchronized
    fun failure(
        stage: SseLogStage,
        exception: Throwable? = null,
        connectionId: Long? = null,
        connectionType: SseConnectionType? = null,
        status: SseStatus? = null,
        retryAttempt: Int? = null,
        durationMillis: Long? = null,
        retryDelayMillis: Long? = null,
        failure: SseFailure? = null,
        hasLastEventId: Boolean? = null,
        eventType: SseEventType? = null,
        invalidationReason: SseInvalidationReason? = null,
    ) {
        if (exception is CancellationException) return
        val priority = if (
            failure?.kind == SseFailureKind.INTERNAL ||
            stage == SseLogStage.APP_START || stage == SseLogStage.EVENT_CALLBACK
        ) Log.ERROR else Log.WARN
        // 只拼接受控枚举、数字和布尔值；接口路径固定，不从 Request、URL 或异常文本提取内容。
        val context = buildString {
            append("stage=").append(stage.name)
            append(" connectionId=").append(connectionId ?: "none")
            append(" endpoint=").append(
                if (stage == SseLogStage.CLOSE) "/capi/resource/sse/close" else "/capi/resource/sse",
            )
            append(" connectionType=").append(connectionType?.name ?: "none")
            append(" status=").append(status?.name ?: "none")
            append(" retryAttempt=").append(retryAttempt ?: 0)
            append(" durationMillis=").append(durationMillis ?: 0)
            append(" retryDelayMillis=").append(retryDelayMillis ?: 0)
            append(" failureKind=").append(failure?.kind?.name ?: "none")
            append(" httpStatus=").append(failure?.httpStatusCode ?: "none")
            append(" hasLastEventId=").append(hasLastEventId ?: false)
            append(" eventType=").append(eventType?.name ?: "none")
            append(" invalidationReason=").append(invalidationReason?.name ?: "none")
        }
        Log.println(priority, TAG, "sse.failure $context")
        if (exception != null) {
            for (line in stackLines(exception)) {
                Log.println(priority, TAG, "sse.exception stage=${stage.name} connectionId=${connectionId ?: "none"} $line")
            }
        }
    }

    /**
     * 将异常链转换为仅含类型和源代码位置的有限行列表，保留 cause 和 suppressed 关系。
     *
     * @param root 待诊断的原始异常，不读取其 message、localizedMessage 或 toString。
     * @return 可安全按行记录的堆栈列表；循环引用、超量分支或超长堆栈用固定标记说明。
     * 只读取异常对象，不修改其堆栈、cause 或 suppressed。
     */
    private fun stackLines(root: Throwable): List<String> {
        val lines = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Pair<String, Throwable>>()
        pending.addLast("root" to root)
        while (pending.isNotEmpty() && seen.size < MAX_THROWABLES && lines.size < MAX_LINES) {
            val (relation, throwable) = pending.removeFirst()
            if (!seen.add(throwable)) {
                lines.add("$relation <cycle>")
                continue
            }
            lines.add("$relation type=${throwable.javaClass.name} message=<omitted>")
            val frames = throwable.stackTrace
            val frameCount = minOf(frames.size, MAX_FRAMES, MAX_LINES - lines.size)
            for (index in 0 until frameCount) {
                lines.add("at ${frames[index]}")
            }
            if (frames.size > frameCount) {
                if (lines.size >= MAX_LINES) lines[lines.lastIndex] = "<stack truncated>"
                else lines.add("<stack truncated>")
            }
            val cause = throwable.cause
            if (cause != null) pending.addLast("$relation.cause" to cause)
            val suppressed = throwable.suppressed
            for (index in 0 until minOf(suppressed.size, MAX_SUPPRESSED)) {
                pending.addLast("$relation.suppressed[$index]" to suppressed[index])
            }
            if (suppressed.size > MAX_SUPPRESSED && lines.size < MAX_LINES) lines.add("<suppressed truncated>")
        }
        if (pending.isNotEmpty()) {
            if (lines.size >= MAX_LINES) lines[lines.lastIndex] = "<exception chain truncated>"
            else lines.add("<exception chain truncated>")
        }
        return lines
    }
}
