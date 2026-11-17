package com.im.openchat.chat.data

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

/** 签名地址仅在当前传输期间有效。 */
internal class MessageDownloadAuthorization(
    private val api: ApiClient,
    private val validate: (String, Boolean) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    suspend fun load(roomId: String, messageId: String, poster: Boolean = false): String {
        validate(roomId, true)
        val response = try {
            api.postJson(
                "/capi/chat/msg/download/url",
                JSONObject().put("msgId", messageId).put("roomId", roomId).toString(),
            )
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode in setOf(401, 403)) {
                onUnauthorized()
                throw CancellationException("Resource authorization expired")
            }
            throw IOException("下载授权失败，请重试", exception)
        }
        validate(roomId, true)
        try {
            val root = JSONObject(response.body)
            if (root.optInt("code") in setOf(401, 403)) {
                onUnauthorized()
                throw CancellationException("Resource authorization expired")
            }
            if (root.opt("success") != true || root.opt("code") != 200) {
                throw IOException("下载授权失败，请重试")
            }
            val data = root.getJSONObject("data")
            val expiresIn = data.opt("expiresIn") as? Int
            if (data.opt("msgId") != messageId || expiresIn == null || expiresIn !in 1..86400) {
                throw IOException("下载授权数据无效")
            }
            val address = data.getString(if (poster) "thumbDownloadUrl" else "downloadUrl")
            val url = address.toHttpUrlOrNull()
            if (address.length > 8192 || address.any { it <= ' ' } || url == null ||
                url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.fragment != null || url.query.isNullOrEmpty()
            ) throw IOException("下载地址无效")
            return url.toString()
        } catch (exception: org.json.JSONException) {
            throw IOException("下载授权数据无效", exception)
        }
    }
}
