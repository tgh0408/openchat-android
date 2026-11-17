package com.im.openchat.common.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SignedUploads {
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()

    fun address(value: String, signed: Boolean): HttpUrl {
        val url = value.toHttpUrlOrNull()
        if (value.length !in 1..8192 || value.any { it <= ' ' } || url == null ||
            (!url.isHttps && !(url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1"))) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            (if (signed) url.query.isNullOrEmpty() else url.query != null)
        ) throw IOException("文件传输地址无效")
        return url
    }

    suspend fun put(address: String, bytes: ByteArray, contentType: String, requireEtag: Boolean = true) =
        withContext(Dispatchers.IO) {
            val url = SignedUploads.address(address, true)
            val request = Request.Builder().url(url)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .header("Content-MD5", android.util.Base64.encodeToString(
                    MessageDigest.getInstance("MD5").digest(bytes), android.util.Base64.NO_WRAP,
                )).build()
            upload(request, requireEtag)
        }

    /** Image uploads stream the original file without JWT, re-encoding, or multipart headers. */
    suspend fun putImage(address: String, file: File, contentType: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SignedUploads.address(address, true))
            .put(file.asRequestBody(contentType.toMediaType())).build()
        upload(request, requireEtag = false, timeoutMillis = 300_000)
    }

    private suspend fun upload(request: Request, requireEtag: Boolean, timeoutMillis: Long? = null) {
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val call = client.newCall(request)
                timeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finished.complete(Unit)
                        if (continuation.isActive) continuation.resumeWithException(IOException("文件上传失败，请重试"))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!it.isSuccessful || requireEtag && it.header("ETag").isNullOrBlank()) {
                                    throw IOException("上传尚未确认，请重试")
                                }
                            }
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (exception: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                })
            }
        } finally {
            withContext(NonCancellable) { finished.await() }
        }
    }
}
