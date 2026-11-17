package com.im.openchat.common.network.sse

/**
 * 建连原因，与服务端 common/sse/dto/SseConnectionType 同名，不使用数字或 ordinal 传输。
 * LOGIN 仅用于显式登录后的首次连接；恢复会话及后续重连使用 RECONNECT。
 */
enum class SseConnectionType {
    LOGIN,
    RECONNECT,
}

/** 对齐 PC 端资源连接状态，仅用于本机观察，不发送给服务端。 */
enum class SseStatus {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/**
 * 资源 SSE 的外层 event 名称，不代表 data 内的业务类型。
 *
 * @property wireName 服务端 SseEmitterManager、RtcSsePushService 实际发送的事件名。
 * UNKNOWN 仅用于本机兼容未知事件，原名称保留在 SseMessage.eventName 中。
 */
enum class SseEventType(val wireName: String?) {
    MESSAGE("message"),
    RTC("rtc"),
    AUTH_INVALIDATED("auth-invalidated"),
    UNKNOWN(null);

    companion object {
        /**
         * 将协议事件名映射为枚举，不猜测 data 结构。
         *
         * @param name OkHttp 返回的事件名；未指定时按 SSE 默认的 message 处理。
         * @return 已知枚举或 UNKNOWN，无副作用。
         */
        fun fromWireName(name: String?): SseEventType = when (name) {
            null, "", MESSAGE.wireName -> MESSAGE
            RTC.wireName -> RTC
            AUTH_INVALIDATED.wireName -> AUTH_INVALIDATED
            else -> UNKNOWN
        }
    }
}

/** 对齐服务端 auth-invalidated 正文的 reason 字符串，不混用 HTTP 状态码。 */
enum class SseInvalidationReason {
    SESSION_REPLACED,
    SESSION_EXPIRED,
}

/** 本地框架错误分类，不充当服务端业务类型或响应码。 */
enum class SseFailureKind {
    NETWORK,
    HTTP,
    PROTOCOL,
    END_OF_STREAM,
    AUTHENTICATION,
    INTERNAL,
}

/**
 * 单次授权的连接凭据，不依赖登录模块，不持久化、不生成包含令牌的 toString。
 *
 * @property userId 当前授权的字符串 uid，用于隔离事件和核对失效通知。
 * @property accessToken 服务端原始 JWT，框架不会替换、刷新或写入存储。
 * @property expiresAtSeconds 已由登录模块解析的 JWT Unix 秒级过期时间，可为空。
 */
class SseCredentials(
    val userId: String,
    internal val accessToken: String,
    val expiresAtSeconds: Long? = null,
) {
    init {
        require(userId.isNotBlank()) { "SSE userId must not be blank" }
        require(accessToken.isNotBlank() && accessToken.length <= 16384) { "Invalid SSE token" }
        // 逐字符校验 JWT 是否可安全写入请求头，返回布尔值，不修改凭据。
        require(accessToken.all { it.code in 33..126 }) { "Invalid SSE token characters" }
        require(expiresAtSeconds == null || expiresAtSeconds in 1..Long.MAX_VALUE / 1000) {
            "Invalid SSE expiry"
        }
    }
}

/**
 * 单条已解析协议帧；正文保持原样，不在框架中封装聊天、好友或 RTC 业务。
 *
 * @property userId 本事件所属账号，业务消费方应核对当前授权。
 * @property type 外层事件名的枚举映射。
 * @property eventName 原始 event 名称，未指定时为 message；未知名称不丢弃。
 * @property id 协议事件 ID；不能把它当作聊天消息 ID 或承诺服务端支持重放。
 * @property data 原始多行 data 合并后的文本。
 * @property receivedAtMillis 本机接收时的 Unix 毫秒时间。
 */
data class SseMessage(
    val userId: String,
    val type: SseEventType,
    val eventName: String,
    val id: String?,
    val data: String,
    val receivedAtMillis: Long,
)

/**
 * 可观察的连接故障，不含请求头、令牌或响应正文。
 *
 * @property kind 本地故障类别。
 * @property message 固定的故障说明，不包含业务正文或凭据。
 * @property httpStatusCode 服务端真实 HTTP 状态码，未取得响应时为空。
 */
data class SseFailure(
    val kind: SseFailureKind,
    val message: String,
    val httpStatusCode: Int? = null,
)

/**
 * 当前连接状态快照；失败保留到下次成功、显式启停或切换账号。
 *
 * @property userId 快照所属账号；未启动时为空。
 * @property status 本地连接状态枚举。
 * @property retryAttempt 连续不稳定连接次数，不是业务类型。
 * @property retryDelayMillis 下一次重连等待时间；未等待时为空。
 * @property failure 最近一次故障。
 * @property invalidationReason 已核对当前 uid 的失效原因或本地到期原因，HTTP 拒绝时可为空。
 */
data class SseConnectionState(
    val userId: String? = null,
    val status: SseStatus = SseStatus.STOPPED,
    val retryAttempt: Int = 0,
    val retryDelayMillis: Long? = null,
    val failure: SseFailure? = null,
    val invalidationReason: SseInvalidationReason? = null,
)
