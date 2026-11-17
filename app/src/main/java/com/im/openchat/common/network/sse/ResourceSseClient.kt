package com.im.openchat.common.network.sse

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.common.network.ApiResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * 当前授权的资源 SSE 框架，不持有 Activity、不解析业务正文，也不直接清理登录或本地数据。
 *
 * @param api 应用统一复用的网络客户端，SSE 不修改它的当前令牌。
 * @param scope 连接所有者的生命周期作用域；取消该作用域会停止网络及重连。
 *
 * 应用应只复用一个实例，业务模块订阅 messages，不能每个页面各建一条连接。
 * 消费方须先订阅再 start；messages 不重放历史，无订阅者时不缓存或持久化事件。
 * start、stop、closeConnection 为可取消的挂起调用，内部串行，不能在回调中阻塞等待。
 */
class ResourceSseClient(
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SseConnectionState())
    private val mutableMessages = MutableSharedFlow<SseMessage>()

    /** 只读状态流，鉴权失败后停在 STOPPED 并保留 failure，交给上层决定登录交互。 */
    val state: StateFlow<SseConnectionState> = mutableState.asStateFlow()

    /** 当前授权的原始事件流，按序交付且不丢弃未知 event；慢消费者会施加背压。 */
    val messages: SharedFlow<SseMessage> = mutableMessages.asSharedFlow()

    @Volatile
    private var active: Connection? = null

    /** 单次授权的运行状态；切换授权时整组丢弃，不跨账号沿用协议游标。 */
    private class Connection(
        val credentials: SseCredentials,
        val initialType: SseConnectionType,
    ) {
        val diagnosticId = nextConnectionId.incrementAndGet()
        var currentType = initialType
        var stage = SseLogStage.CONNECT
        var attemptStartedAtNanos = System.nanoTime()
        var job: Job? = null
        var lastEventId: String? = null
    }

    /**
     * 启动单条资源连接；相同有效授权重复调用不重建，其他授权先取消并等待旧循环退出。
     *
     * @param credentials 已由登录模块校验的账号、令牌及可选过期时间。
     * @param connectionType 显式密码登录后的首次连接传 LOGIN，恢复已有会话传 RECONNECT。
     * 无返回值；发布连接状态并启动异步网络任务，不保存或刷新令牌。
     */
    suspend fun start(
        credentials: SseCredentials,
        connectionType: SseConnectionType = SseConnectionType.RECONNECT,
    ) {
        // 串行替换授权及连接任务，无返回值；等待旧连接退出后才发布新账号状态。
        mutex.withLock {
            scope.coroutineContext.ensureActive()
            val previous = active
            if (previous?.job?.isActive == true &&
                previous.credentials.userId == credentials.userId &&
                previous.credentials.accessToken == credentials.accessToken &&
                previous.credentials.expiresAtSeconds == credentials.expiresAtSeconds
            ) return
            previous?.job?.cancelAndJoin()
            val connection = Connection(credentials, connectionType)
            active = connection
            mutableState.value = SseConnectionState(
                userId = credentials.userId,
                status = if (connectionType == SseConnectionType.LOGIN) {
                    SseStatus.CONNECTING
                } else {
                    SseStatus.RECONNECTING
                },
            )
            // 立即进入受管理的循环，确保作用域恰好取消时也执行 finally；后续网络读取仍为异步。
            connection.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { run(connection) }
        }
    }

    /**
     * 停止本地连接并等待网络及重连任务释放，无返回值。
     *
     * 不请求服务端、不清除 API 令牌；适用于账号切换、授权撤销及生命周期结束。
     */
    suspend fun stop() {
        // 串行取消当前任务并清空本机状态，无返回值，不发送关闭通知。
        mutex.withLock {
            active?.job?.cancelAndJoin()
            active = null
            mutableState.value = SseConnectionState()
        }
    }

    /**
     * 先停止本地循环，再以原令牌通知服务端关闭；与 start 串行，防止关闭通知误伤新连接。
     *
     * @return 无运行连接时为空；否则返回服务端关闭接口的响应快照。
     * @throws IOException 关闭接口 HTTP 或网络失败，调用方可提示或记录失败；本地连接仍保持停止。
     * 最多等待现有网络层配置的 1500 毫秒；失败记录受控诊断日志后原样抛出，不清理授权或重试。
     */
    suspend fun closeConnection(): ApiResponse? {
        // 持锁等待原授权的关闭通知并返回响应，阻止同一实例提前启动新连接。
        return mutex.withLock {
            val connection = active ?: return@withLock null
            connection.job?.cancelAndJoin()
            active = null
            mutableState.value = SseConnectionState()
            val startedAt = System.nanoTime()
            try {
                api.closeEventStream("capi/resource/sse/close", connection.credentials.accessToken)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                SseLogger.failure(
                    stage = SseLogStage.CLOSE,
                    exception = exception,
                    connectionId = connection.diagnosticId,
                    connectionType = connection.currentType,
                    status = SseStatus.STOPPED,
                    durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
                    failure = if (exception is ApiHttpException) {
                        SseFailure(SseFailureKind.HTTP, "SSE 关闭通知失败", exception.response.statusCode)
                    } else {
                        SseFailure(SseFailureKind.NETWORK, "SSE 关闭通知失败")
                    },
                )
                throw exception
            }
        }
    }

    /**
     * 在所有者作用域中运行当前授权，处理到期和终止故障，无返回值。
     *
     * @param connection 本次独立授权的连接状态。
     * 取消始终向上传播；意外终止记录受控堆栈日志；退出时保留最终状态并释放引用，不触碰业务存储。
     */
    private suspend fun run(connection: Connection) {
        try {
            val expiresAt = connection.credentials.expiresAtSeconds
            if (expiresAt == null) {
                reconnect(connection)
            } else {
                val remainingMillis = expiresAt * 1000 - System.currentTimeMillis()
                // 将整条连接任务绑定 JWT 剩余寿命；超时会取消事件源、背压等待和退避。
                val completed = if (remainingMillis <= 0) null else withTimeoutOrNull(
                    remainingMillis.milliseconds
                ) {
                    reconnect(connection)
                    true
                }
                if (completed == null) invalidate(connection, SseInvalidationReason.SESSION_EXPIRED)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            val failure = SseFailure(SseFailureKind.INTERNAL, "SSE 框架终止，请检查接入参数")
            SseLogger.failure(
                stage = connection.stage,
                exception = exception,
                connectionId = connection.diagnosticId,
                connectionType = connection.currentType,
                status = mutableState.value.status,
                retryAttempt = mutableState.value.retryAttempt,
                durationMillis = (System.nanoTime() - connection.attemptStartedAtNanos) / 1_000_000,
                failure = failure,
                hasLastEventId = !connection.lastEventId.isNullOrEmpty(),
            )
            mutableState.value = mutableState.value.copy(
                status = SseStatus.STOPPED,
                retryDelayMillis = null,
                failure = failure,
            )
        } finally {
            if (active === connection) {
                mutableState.value = mutableState.value.copy(
                    status = SseStatus.STOPPED,
                    retryDelayMillis = null,
                )
                // 最后再摘除引用；并发 start 若已读取本连接，会等待本 Job 完整退出后再发布新状态。
                active = null
            }
        }
    }

    /**
     * 持续消费当前账号的连接，EOF 和传输故障采用 PC 端的有上限指数退避及抖动，无返回值。
     *
     * @param connection 本次独立授权及最近已交付的事件 ID。
     * 故障记录阶段、耗时、重试计划及受控异常链；401/403 或有效失效通知立即停止，不抢占登录权。
     */
    private suspend fun reconnect(connection: Connection) {
        var type = connection.initialType
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            connection.currentType = type
            connection.stage = SseLogStage.CONNECT
            val attemptStartedAt = System.nanoTime()
            connection.attemptStartedAtNanos = attemptStartedAt
            var openedAt: Long? = null
            mutableState.value = mutableState.value.copy(
                status = if (type == SseConnectionType.LOGIN) SseStatus.CONNECTING else SseStatus.RECONNECTING,
                retryDelayMillis = null,
            )
            try {
                consumeResourceStream(
                    api = api,
                    credentials = connection.credentials,
                    connectionType = type,
                    lastEventId = connection.lastEventId,
                    // 记录真正建连成功的单调时间并发布连接状态，无业务存储副作用。
                    onOpen = {
                        connection.stage = SseLogStage.READ
                        openedAt = System.nanoTime()
                        mutableState.value = SseConnectionState(
                            userId = connection.credentials.userId,
                            status = SseStatus.CONNECTED,
                        )
                    },
                    // 仅识别当前账号的授权撤销，其余正文按原样发送；交付后更新重连游标。
                    onMessage = { message ->
                        connection.stage = SseLogStage.DISPATCH
                        val reason = readInvalidation(message, connection)
                        if (reason != null) throw SessionInvalidated(reason)
                        mutableMessages.emit(message)
                        if (message.id != null) connection.lastEventId = message.id
                        connection.stage = SseLogStage.READ
                    },
                )
            } catch (exception: SessionInvalidated) {
                invalidate(connection, exception.reason)
                return
            } catch (exception: SseStreamException) {
                currentCoroutineContext().ensureActive()
                if (exception.failure.kind == SseFailureKind.AUTHENTICATION) {
                    SseLogger.failure(
                        stage = connection.stage,
                        exception = exception,
                        connectionId = connection.diagnosticId,
                        connectionType = type,
                        status = mutableState.value.status,
                        retryAttempt = attempt,
                        durationMillis = (System.nanoTime() - attemptStartedAt) / 1_000_000,
                        failure = exception.failure,
                        hasLastEventId = !connection.lastEventId.isNullOrEmpty(),
                    )
                    mutableState.value = mutableState.value.copy(
                        status = SseStatus.STOPPED,
                        retryDelayMillis = null,
                        failure = exception.failure,
                    )
                    return
                }
                // 仅按实际建连后的存活时间判定稳定，返回布尔值，不受系统墙上时钟调整影响。
                val stable = openedAt?.let { System.nanoTime() - it >= STABLE_WINDOW_NANOS } == true
                attempt = if (stable) 0 else if (attempt < Int.MAX_VALUE) attempt + 1 else attempt
                val retryDelay = retryDelayMillis(attempt)
                SseLogger.failure(
                    stage = connection.stage,
                    exception = exception,
                    connectionId = connection.diagnosticId,
                    connectionType = type,
                    status = mutableState.value.status,
                    retryAttempt = attempt,
                    durationMillis = (System.nanoTime() - attemptStartedAt) / 1_000_000,
                    retryDelayMillis = retryDelay,
                    failure = exception.failure,
                    hasLastEventId = !connection.lastEventId.isNullOrEmpty(),
                )
                mutableState.value = mutableState.value.copy(
                    status = SseStatus.RECONNECTING,
                    retryAttempt = attempt,
                    retryDelayMillis = retryDelay,
                    failure = exception.failure,
                )
                connection.stage = SseLogStage.RETRY_WAIT
                delay(retryDelay)
            }
            type = SseConnectionType.RECONNECT
        }
    }

    /**
     * 核对服务端专用失效通知，不将聊天正文、其他账号或未知原因解释成退出指令。
     *
     * @param message 当前连接收到的原始事件。
     * @param connection 当前授权的诊断上下文，通知格式无效时用于关联日志。
     * @return 已匹配的协议原因枚举，格式错误或不匹配时为空，不修改业务数据。
     * 通知 JSON 格式无效时记录受控诊断日志，不输出通知正文。
     */
    private fun readInvalidation(message: SseMessage, connection: Connection): SseInvalidationReason? {
        if (message.type != SseEventType.AUTH_INVALIDATED) return null
        val value = try {
            JSONObject(message.data)
        } catch (exception: JSONException) {
            SseLogger.failure(
                stage = SseLogStage.INVALIDATION,
                exception = exception,
                connectionId = connection.diagnosticId,
                connectionType = connection.currentType,
                status = mutableState.value.status,
                eventType = message.type,
                failure = SseFailure(SseFailureKind.PROTOCOL, "SSE 授权失效通知格式无效"),
            )
            return null
        }
        if (value.opt("uid") != message.userId) return null
        return when (value.opt("reason")) {
            SseInvalidationReason.SESSION_REPLACED.name -> SseInvalidationReason.SESSION_REPLACED
            SseInvalidationReason.SESSION_EXPIRED.name -> SseInvalidationReason.SESSION_EXPIRED
            else -> null
        }
    }

    /**
     * 记录受控失效原因并发布终态，无返回值；不直接清除令牌、导航或本地数据。
     *
     * @param connection 本次授权的诊断上下文，不记录其账号、令牌或实际游标。
     * @param reason 已核对的服务端原因，或已知 JWT 的本地到期原因。
     */
    private fun invalidate(connection: Connection, reason: SseInvalidationReason) {
        SseLogger.failure(
            stage = SseLogStage.INVALIDATION,
            connectionId = connection.diagnosticId,
            connectionType = connection.currentType,
            status = mutableState.value.status,
            invalidationReason = reason,
            failure = SseFailure(SseFailureKind.AUTHENTICATION, "SSE 授权已失效"),
        )
        mutableState.value = mutableState.value.copy(
            status = SseStatus.STOPPED,
            retryDelayMillis = null,
            failure = SseFailure(SseFailureKind.AUTHENTICATION, "SSE 授权已失效，请重新登录"),
            invalidationReason = reason,
        )
    }

    /**
     * 计算 PC 端同款 1 至 30 秒指数退避，并附加最多 249 毫秒抖动。
     *
     * @param attempt 实际连续不稳定连接次数，仅退避指数限制为最多五次，避免移位溢出。
     * @return 不超过 30 秒的下次等待时间，无外部副作用。
     */
    private fun retryDelayMillis(attempt: Int): Long =
        ((BASE_RETRY_MILLIS shl attempt.coerceIn(0, MAX_RETRY_EXPONENT)) + Random.nextLong(250))
            .coerceAtMost(MAX_RETRY_MILLIS)

    /** 在顺序消费中立即终止本连接，不将授权撤销当作可重试网络异常。 */
    private class SessionInvalidated(val reason: SseInvalidationReason) : IOException()

    private companion object {
        val nextConnectionId = AtomicLong()
        const val BASE_RETRY_MILLIS = 1000L
        const val MAX_RETRY_MILLIS = 30_000L
        const val MAX_RETRY_EXPONENT = 5
        const val STABLE_WINDOW_NANOS = 10_000_000_000L
    }
}
