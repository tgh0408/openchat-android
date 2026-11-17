package com.im.openchat.common.network

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** 公开头像按 URL 缓存到私有文件，优先读本地，不携带 API 凭据。 */
internal object AvatarImages {
    /** Reuse decoded pixels without disk access or coroutine dispatch during composition. */
    fun peek(address: String): Bitmap? {
        val url = normalizedAddress(address) ?: return null
        return CachedImages.peek("avatars", url, 256)
    }

    /**
     * 可见头像按需读取缓存，未命中时下载并缩采样。
     * @param context 仅使用应用上下文定位缓存。
     * @param address 公开资料返回的 HTTP(S) 地址，不接受凭据和片段。
     * @return 最长边约 256 像素的头像，失败为 null；成功缓存文件，取消传播。
     */
    suspend fun load(context: Context, address: String): Bitmap? {
        val url = normalizedAddress(address) ?: return null
        return try {
            // 仅在本地缓存未命中时提供公开地址，不发送业务凭据。
            CachedImages.load(context, "avatars", url, 2L * 1024 * 1024, 256) { url }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            null
        }
    }

    private fun normalizedAddress(address: String): String? {
        if (address.length > 2048 || address.any { it <= ' ' }) return null
        val url = address.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        return url.toString()
    }
}
