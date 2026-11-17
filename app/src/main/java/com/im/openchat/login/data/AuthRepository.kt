package com.im.openchat.login.data

import android.content.Context
import android.util.Base64
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** 服务端以 401/403 拒绝令牌或会话身份不一致时标记失效，调用方可清除令牌并保留账号。 */
internal class InvalidSessionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 按 Web/Electron 认证协议请求登录并校验返回的会话。
 *
 * @param api 与当前服务地址的其他 API 及 SSE 共用的网络客户端。
 */
class AuthRepository(
    private val api: ApiClient,
) {
    /** 调用前先清除本机认证；网络失败交给界面提示，不恢复已经退出的会话。 */
    suspend fun logout(session: AuthSession) {
        val response = try {
            api.logout(session.accessToken)
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode in setOf(401, 403)) return
            throw IOException("无法确认服务端退出", exception)
        }
        parseLogoutResponse(response.body)
    }

    /** 登录响应不含昵称；个人页使用当前授权单独读取本人公开资料。 */
    suspend fun loadCurrentUser(user: AuthUser): AuthUser {
        val response = try {
            api.get("/capi/user/userinfo")
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode in setOf(401, 403)) {
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("用户资料获取失败，请稍后重试", exception)
        }
        return parseCurrentUser(response.body, user)
    }

    /**
     * 为尚未提供共享客户端的调用方创建认证客户端，不发起网络请求。
     *
     * @param context 应用上下文，用于读取设备标识，不持有 Activity。
     * @param baseUrl 登录服务根地址，不包含具体接口路径。
     */
    constructor(context: Context, baseUrl: String) : this(ApiClient(context.applicationContext, baseUrl))

    /**
     * 提交账号密码，解析登录响应并使用 globalUid 校验当前会话。
     *
     * @param account 登录账号，对应服务端请求体的 userId。
     * @param password 登录密码，仅用于本次请求，不写入存储。
     * @return 已通过服务端业务校验的认证会话。
     * @throws IOException 网络失败、HTTP 失败或服务端返回无效数据时抛出。
     * 无密码持久化副作用；成功后仅在内存中更新 ApiClient 的后续 token。
     */
    suspend fun login(account: String, password: String): AuthSession {
        val normalizedAccount = account.trim()
        if (normalizedAccount.isBlank() || password.isBlank()) {
            throw IOException("请输入有效的账号和密码")
        }
        val response = try {
            api.postJson(
                path = "/capi/user/public/login",
                json = JSONObject()
                    .put("userId", normalizedAccount)
                    .put("password", password)
                    .toString(),
            )
        } catch (exception: ApiHttpException) {
            throw IOException(
                if (exception.response.statusCode == 401 || exception.response.statusCode == 403) {
                    "账号或密码错误"
                } else {
                    readErrorMessage(exception.response.body, "登录服务请求失败")
                },
                exception,
            )
        } catch (exception: CancellationException) {
            throw exception
        }
        val session = parseLoginSession(response.body, normalizedAccount)
        return validateSession(session)
    }

    /**
     * 用保存或刚登录的 token 请求 globalUid，核对身份后刷新 LiveKit 地址。
     *
     * @param session 待验证的会话，其账号和令牌来自本地存储或登录接口。
     * @return 已通过服务端校验、更新内容库密钥及媒体地址的会话，供调用方加密缓存。
     * @throws InvalidSessionException 服务端拒绝令牌或返回的身份不匹配。
     * @throws IOException 网络或响应异常；此时不能认定令牌过期。
     * 成功后在内存中保留令牌供后续请求使用，失败或取消时清除内存令牌。
     */
    suspend fun validateSession(session: AuthSession): AuthSession {
        if (session.accessToken.isBlank() || session.accessToken.length > 16384 || session.user.id.isBlank()) {
            api.updateToken(null)
            throw InvalidSessionException("登录已过期，请重新登录")
        }
        api.updateToken(session.accessToken)
        try {
            val validation = api.get("/capi/user/globalUid")
            val validationPassword = parseGlobalUidValidation(validation.body, session.user.id)
            val rtcServerUrl = loadOptionalRtcServerUrl()
            return session.copy(clientSqlPassword = validationPassword, rtcServerUrl = rtcServerUrl)
        } catch (exception: CancellationException) {
            api.updateToken(null)
            throw exception
        } catch (exception: ApiHttpException) {
            api.updateToken(null)
            if (exception.response.statusCode == 401 || exception.response.statusCode == 403) {
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException(readErrorMessage(exception.response.body, "登录校验服务请求失败"), exception)
        } catch (exception: IOException) {
            api.updateToken(null)
            throw exception
        }
    }

    /** 配置最多等待三秒；失败清空媒体地址，只有明确失效或外部取消才中断登录，无持久化副作用。 */
    private suspend fun loadOptionalRtcServerUrl(): String {
        return try {
            withTimeoutOrNull(3000) {
                parseRtcServerUrl(api.get("/capi/rtc/config").body)
            }.orEmpty()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode in setOf(401, 403)) throw exception
            ""
        } catch (exception: IOException) {
            ""
        }
    }
}

/** 解析登录后的媒体地址；仅允许 WSS 或回环 WS，不接受凭据、查询参数和片段，无存储副作用。 */
internal fun parseRtcServerUrl(body: String): String {
    val root = parseSuccessfulResponse(body, "LiveKit 配置")
    val server = (root.optJSONObject("data")?.opt("serverUrl") as? String)?.trim()
        ?: throw IOException("LiveKit 配置响应无效")
    if (server.isEmpty()) return ""
    if (server.isBlank() || server.length > 2048 ||
        !(server.startsWith("wss://") || server.startsWith("ws://"))
    ) throw IOException("LiveKit 服务地址无效")
    val url = server.replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://").toHttpUrlOrNull()
        ?: throw IOException("LiveKit 服务地址无效")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null ||
        (!url.isHttps && url.host !in setOf("localhost", "127.0.0.1", "::1"))
    ) throw IOException("LiveKit 地址须为 WSS，本机可使用 WS")
    return server.removeSuffix("/")
}

internal fun parseLogoutResponse(body: String) {
    val root = try {
        JSONObject(body)
    } catch (exception: org.json.JSONException) {
        throw IOException("退出服务返回的数据无效", exception)
    }
    if (root.optInt("code") in setOf(401, 403)) return
    if (root.opt("success") != true || root.opt("code") != 200 || !root.isNull("data")) {
        throw IOException("无法确认服务端退出")
    }
}

internal fun parseCurrentUser(body: String, user: AuthUser): AuthUser {
    val root = try {
        JSONObject(body)
    } catch (exception: org.json.JSONException) {
        throw IOException("用户资料服务返回的数据无效", exception)
    }
    if (root.optInt("code") in setOf(401, 403)) {
        throw InvalidSessionException("登录已过期，请重新登录")
    }
    if (root.opt("success") != true || root.opt("code") != 200) {
        throw IOException("用户资料获取失败，请稍后重试")
    }
    val data = root.optJSONObject("data")
        ?: throw IOException("用户资料服务返回的数据无效")
    if (parseUid(data.opt("uid")) != user.id) {
        throw IOException("用户资料与当前账号不一致")
    }
    fun field(key: String, limit: Int): String? {
        if (data.isNull(key)) return null
        val value = data.opt(key)
        if (value !is String || value.length > limit || '\u0000' in value) {
            throw IOException("用户资料字段无效")
        }
        return value.trim().ifBlank { null }
    }
    return user.copy(
        nickname = field("name", 256) ?: user.nickname.ifBlank { "用户 ${user.id}" },
        avatar = field("avatar", 2048)?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        signature = field("personalitySignature", 512),
    )
}

/**
 * 解析登录接口的统一业务响应和 JWT 字段。
 *
 * @param body 登录接口返回的 JSON 正文。
 * @param account 本次请求使用的账号，用于组成用户资料。
 * @return 规范化后的认证会话。
 * 无存储副作用；解析失败时抛出带用户可见提示的 IOException。
 */
internal fun parseLoginSession(body: String, account: String): AuthSession {
    val root = parseSuccessfulResponse(body, "登录")
    val data = root.optJSONObject("data")
        ?: throw IOException("登录服务返回的数据无效")
    val uid = parseUid(data.opt("uid"))
        ?: throw IOException("登录服务返回的数据无效")
    val token = data.optString("token").trim()
    val clientSqlPassword = data.optString("clientSqlPassword").trim()
    if (token.isBlank() || token.length > 16384 || clientSqlPassword.isBlank() || clientSqlPassword.length > 256) {
        throw IOException("登录服务返回的数据无效")
    }
    val expiresAt = parseJwtExpiry(token, uid)
    return AuthSession(
        accessToken = token,
        expiresAt = expiresAt,
        user = AuthUser(
            id = uid,
            account = account,
            nickname = "用户 $uid",
        ),
        clientSqlPassword = clientSqlPassword,
    )
}

/**
 * 校验登录后的 globalUid 响应，确保 token 对应本次登录用户。
 *
 * @param body globalUid 接口返回的 JSON 正文。
 * @param expectedUid 登录响应中的用户 uid。
 * @return 服务端校验返回的本地内容库密钥。
 * 无存储副作用；身份不一致或业务响应无效时抛出 IOException。
 */
internal fun parseGlobalUidValidation(body: String, expectedUid: String): String {
    val root = parseSuccessfulResponse(body, "登录校验")
    val data = root.optJSONObject("data")
        ?: throw IOException("登录校验服务返回的数据无效")
    if (parseUid(data.opt("uid")) != expectedUid) {
        throw InvalidSessionException("登录身份与当前账号不一致，请重新登录")
    }
    val clientSqlPassword = data.optString("clientSqlPassword").trim()
    if (clientSqlPassword.isBlank() || clientSqlPassword.length > 256) {
        throw IOException("登录校验服务返回的数据无效")
    }
    return clientSqlPassword
}

/**
 * 校验统一接口的 success 和 code 字段，并保留服务端错误文案。
 *
 * @param body 待解析的 JSON 正文。
 * @param serviceName 当前业务名称，用于生成兜底错误。
 * @return 业务成功的根 JSON 对象。
 * 无存储副作用；JSON 损坏或业务失败时抛出 IOException。
 */
private fun parseSuccessfulResponse(body: String, serviceName: String): JSONObject {
    val root = try {
        JSONObject(body)
    } catch (exception: Exception) {
        throw IOException("${serviceName}服务返回的数据无效", exception)
    }
    if (!root.optBoolean("success") || root.optInt("code", Int.MIN_VALUE) != 200) {
        throw IOException(readErrorMessage(body, "${serviceName}失败，请重试"))
    }
    return root
}

/**
 * 从 JSON 值中读取合法的正整数 uid。
 *
 * @param value JSON 中的 uid 原始值。
 * @return 规范化后的字符串 uid，格式不合法时返回 null。
 * 无副作用，不修改输入对象或存储。
 */
private fun parseUid(value: Any?): String? = when (value) {
    is Int -> value.takeIf { it > 0 }?.toString()
    is Long -> value.takeIf { it > 0 }?.toString()
    is Double -> value.takeIf { it.isFinite() && it > 0 && it == it.toLong().toDouble() }?.toLong()?.toString()
    is String -> value.takeIf { Regex("[1-9][0-9]{0,63}").matches(it) }
    else -> null
}

/**
 * 从 JWT 的 payload 中读取过期时间并校验 uid 声明。
 *
 * @param token 服务端返回的 JWT。
 * @param expectedUid 登录响应中的用户 uid。
 * @return JWT 的 Unix 秒级过期时间，未声明时为空。
 * 无网络和存储副作用；token 格式无效或已过期时抛出 IOException。
 */
private fun parseJwtExpiry(token: String, expectedUid: String): Long? {
    val parts = token.split('.')
    if (parts.size != 3) throw IOException("登录服务返回的 token 格式无效")
    val payload = try {
        val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (exception: Exception) {
        throw IOException("登录服务返回的 token 格式无效", exception)
    }
    val expiresAt = payload.opt("exp").asLongOrNull()
    if (expiresAt != null && expiresAt <= System.currentTimeMillis() / 1000) {
        throw IOException("登录令牌无效或已过期")
    }
    val tokenUid = payload.optionalString("uid")
    if (tokenUid != null && tokenUid != expectedUid) {
        throw IOException("登录令牌无效或已过期")
    }
    return expiresAt
}

/**
 * 将 JSON 数值转换为长整型，仅接受安全的整数值。
 *
 * @return 可用的 Long 值，类型或范围不符合时返回 null。
 * 无副作用。
 */
private fun Any?.asLongOrNull(): Long? = when (this) {
    is Int -> toLong()
    is Long -> this
    is Double -> takeIf { isFinite() && this == toLong().toDouble() }?.toLong()
    is String -> toLongOrNull()
    else -> null
}

/**
 * 读取可选的非空 JSON 字符串，过滤 JSONObject.NULL 和空白内容。
 *
 * @return 清理后的字符串，缺失或非字符串时返回 null。
 * 无副作用。
 */
private fun JSONObject.optionalString(name: String): String? {
    val value = opt(name)
    return value?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.ifBlank { null }
}

/**
 * 从失败响应中读取服务端错误文案，缺失时使用本地兜底提示。
 *
 * @param body 服务端返回的 JSON 或普通文本。
 * @param fallback 本地兜底错误。
 * @return 可展示给用户的错误文案。
 * 无存储副作用。
 */
private fun readErrorMessage(body: String, fallback: String): String = runCatching {
    JSONObject(body).optionalString("errMsg") ?: fallback
}.getOrDefault(fallback)
