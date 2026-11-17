package com.im.openchat.login.data

/**
 * 表示当前账号身份及公开资料，登录响应不含资料时使用占位昵称。
 *
 * @property id 服务端用户 uid。
 * @property account 本次登录使用的账号。
 * @property nickname 服务端返回的昵称或本地生成的兜底昵称。
 * @property avatar 服务端返回的头像地址，可为空。
 * @property signature 服务端返回的个性签名，可为空。
 */
data class AuthUser(
    val id: String,
    val account: String,
    val nickname: String,
    val avatar: String? = null,
    val signature: String? = null,
)

/**
 * 表示已通过登录接口及会话校验的认证信息。
 *
 * @property accessToken 服务端 JWT。
 * @property expiresAt JWT 中的 Unix 秒级过期时间，未声明时为空。
 * @property user 当前登录用户资料。
 * @property clientSqlPassword 服务端返回的 SQLCipher 内容库密码；核验并取得有效值后才能打开库。
 * @property rtcServerUrl 本次登录校验获取的 LiveKit 地址，随服务器绑定的加密会话缓存。
 */
data class AuthSession(
    val accessToken: String,
    val expiresAt: Long?,
    val user: AuthUser,
    val clientSqlPassword: String? = null,
    val rtcServerUrl: String = "",
) {
    /** 诊断输出不包含 JWT、数据库密码或个人资料。 */
    override fun toString(): String = "AuthSession(redacted)"
}
