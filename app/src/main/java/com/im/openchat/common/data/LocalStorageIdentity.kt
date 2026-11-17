package com.im.openchat.common.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** 规范化本地存储所属服务；保留协议、非默认端口和路径，不发起网络请求。 */
internal fun storageServerIdentity(server: String): String {
    val url = server.trim().toHttpUrl()
    val localHttp = url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1")
    require(url.isHttps || localHttp) { "服务器地址必须使用 HTTPS" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "服务器地址不能包含凭据、查询参数或片段"
    }
    return url.toString().trimEnd('/')
}

/** 取规范化完整服务器地址 SHA-256 的前 16 位，保留协议、端口和服务路径隔离。 */
internal fun storageServerHash(server: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(storageServerIdentity(server).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(16)

/** 生成服务器摘要与正整数 UID 两层相对路径，供内容库及账号文件共用。 */
internal fun accountStoragePartition(server: String, uid: String): String {
    require(Regex("[1-9][0-9]{0,18}").matches(uid) && uid.toLongOrNull() != null) {
        "内容库账号标识无效"
    }
    return "${storageServerHash(server)}/$uid"
}

/** 返回服务器目录内的账号库相对路径，不包含原始地址或密钥。 */
internal fun encryptedDatabaseName(owner: ChatDataOwner): String =
    "${accountStoragePartition(owner.serverUrl, owner.userId)}.sqlite"
