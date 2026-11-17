package com.im.openchat.common.network

import android.util.AtomicFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A checkpoint never contains a signed URL, account token, or user-provided filename. */
internal object ResumableDownloads {
    @Serializable
    private data class Checkpoint(val size: Long, val etag: String)

    private val json = Json
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    internal class AuthorizationExpired : IOException("Download authorization expired")
    private class RestartRequired : IOException("Download resource changed")

    suspend fun partialSize(file: File, size: Long): Long = withContext(Dispatchers.IO) {
        val part = part(file)
        if (checkpoint(file, size) != null && part.isFile && part.length() in 1..size) part.length() else 0L
    }

    suspend fun download(
        address: String,
        file: File,
        size: Long,
        valid: (File) -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        for (attempt in 0..1) {
            try {
                transfer(address, file, size, valid, onProgress)
                return
            } catch (exception: RestartRequired) {
                if (attempt == 1) throw IOException("下载范围不受支持，请重试", exception)
            } catch (exception: AuthorizationExpired) {
                throw exception
            } catch (exception: IOException) {
                throw IOException("文件下载失败，请重试", exception)
            }
        }
    }

    private suspend fun transfer(
        address: String, file: File, size: Long, valid: (File) -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(size > 0)
        if (file.parentFile?.let { it.isDirectory || it.mkdirs() } != true) {
            throw IOException("Cannot create download directory")
        }
        if (file.isFile && valid(file)) {
            clear(file)
            onProgress(size, size)
            return@withContext
        }
        // A failed rename can leave a complete, validated part without its final cache index.
        val part = part(file)
        val saved = checkpoint(file, size)
        if (saved != null && part.isFile && part.length() == size && valid(part)) {
            currentCoroutineContext().ensureActive()
            commit(file, part)
            onProgress(size, size)
            return@withContext
        }
        if (saved == null || !part.isFile || part.length() !in 1 until size) clear(file)
        val offset = if (checkpoint(file, size) != null) part.length() else 0L
        val etag = checkpoint(file, size)?.etag
        onProgress(offset, size)
        val request = Request.Builder().url(address)
            .header("Accept-Encoding", "identity")
            .apply {
                if (offset > 0 && etag != null) {
                    header("Range", "bytes=$offset-")
                    header("If-Range", etag)
                }
            }.build()
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finished.complete(Unit)
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { result ->
                                if (!continuation.isActive) return
                                if (result.code in setOf(401, 403)) throw AuthorizationExpired()
                                if (result.code == 416) {
                                    clear(file)
                                    throw RestartRequired()
                                }
                                if (result.code != 200 && result.code != 206) throw IOException("HTTP ${result.code}")
                                val body = result.body
                                val encoding = result.header("Content-Encoding")
                                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                                    throw IOException("Encoded download cannot be resumed")
                                }
                                val responseEtag = strongEtag(result.header("ETag"))
                                val append = result.code == 206
                                val start = if (append) {
                                    val range = try {
                                        DownloadRange.parse(result.header("Content-Range"))
                                    } catch (exception: IOException) {
                                        clear(file)
                                        throw RestartRequired()
                                    }
                                    if (offset <= 0 || range.start != offset || range.total != size ||
                                        range.end != size - 1 || etag != responseEtag
                                    ) {
                                        clear(file)
                                        throw RestartRequired()
                                    }
                                    range.start
                                } else {
                                    clear(file)
                                    0L
                                }
                                val length = body.contentLength()
                                if (length >= 0 && length != size - start) {
                                    throw IOException("Unexpected download size")
                                }
                                if (responseEtag != null) saveCheckpoint(file, Checkpoint(size, responseEtag))
                                var count = start
                                var reportedAt = 0L
                                val buffer = ByteArray(64 * 1024)
                                onProgress(count, size)
                                FileOutputStream(part, append).use { output ->
                                    try {
                                        body.byteStream().use { input ->
                                            while (true) {
                                                if (!continuation.isActive) throw IOException("Download paused")
                                                val read = input.read(buffer)
                                                if (read < 0) break
                                                if (read.toLong() > size - count) {
                                                    clearCheckpoint(file)
                                                    throw IOException("Download exceeds declared size")
                                                }
                                                output.write(buffer, 0, read)
                                                count += read
                                                val now = System.nanoTime()
                                                if (now - reportedAt >= 100_000_000 || count == size) {
                                                    onProgress(count, size)
                                                    reportedAt = now
                                                }
                                            }
                                        }
                                    } finally {
                                        output.fd.sync()
                                    }
                                }
                                if (!continuation.isActive) return
                                if (count != size) throw IOException("Download is incomplete")
                                if (!valid(part)) {
                                    clear(file)
                                    throw IOException("Downloaded file is invalid")
                                }
                                commit(file, part)
                            }
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (exception: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        } finally {
                            // Without a strong validator we cannot safely append on a later request.
                            try {
                                if (checkpoint(file, size) == null) part.delete()
                            } finally {
                                finished.complete(Unit)
                            }
                        }
                    }
                })
            }
        } finally {
            // Keep the caller's resource lock until the cancelled writer has closed and synced.
            withContext(NonCancellable) { finished.await() }
        }
    }

    private fun part(file: File) = File(file.parentFile, "${file.name}.part")
    private fun metadata(file: File) = AtomicFile(File(file.parentFile, "${file.name}.resume"))

    private fun strongEtag(value: String?): String? = value?.takeIf {
        it.length in 2..1024 && it.startsWith('"') && it.endsWith('"') &&
            it.substring(1, it.lastIndex).all { character -> character.code in 33..126 && character != '"' }
    }

    private fun checkpoint(file: File, size: Long): Checkpoint? {
        return try {
            val bytes = metadata(file).openRead().use { input ->
                val buffer = ByteArray(2049)
                val count = input.read(buffer)
                if (count !in 1..2048 || input.read() != -1) return null
                buffer.copyOf(count)
            }
            json.decodeFromString<Checkpoint>(bytes.toString(Charsets.UTF_8)).takeIf {
                it.size == size && strongEtag(it.etag) == it.etag
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCheckpoint(file: File, value: Checkpoint) {
        val atomic = metadata(file)
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(Checkpoint.serializer(), value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
    }

    private fun clearCheckpoint(file: File) = metadata(file).delete()

    private fun clear(file: File) {
        clearCheckpoint(file)
        val part = part(file)
        if (part.exists() && !part.delete()) throw IOException("Cannot discard download checkpoint")
    }

    private fun commit(file: File, part: File) {
        if (!part.renameTo(file)) throw IOException("Cannot commit downloaded file")
        clearCheckpoint(file)
    }
}
