package com.im.openchat.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.accountStoragePartition
import com.im.openchat.common.data.ChatStore
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.CachedVideoFiles
import com.im.openchat.common.network.ResumableDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.Locale

internal class MessageVideoRepository(
    context: Context,
    api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
    private val validate: (String, Message, Boolean) -> Unit,
    onUnauthorized: () -> Unit,
) {
    private val root = File(context.applicationContext.noBackupFilesDir, "video")
    private val partition = accountStoragePartition(owner.serverUrl, owner.userId)
    private val authorize: suspend (String, Message, Boolean) -> String = { roomId, message, poster ->
        MessageDownloadAuthorization(api, { _, remote -> validate(roomId, message, remote) }, onUnauthorized)
            .load(roomId, message.id, poster)
    }

    fun check(roomId: String, message: Message) = validate(roomId, message, false)

    suspend fun cached(roomId: String, message: Message): File? {
        check(roomId, message)
        val resource = resource(message, false)
        val file = cached(resource)
        check(roomId, message)
        return file
    }

    suspend fun partialSize(roomId: String, message: Message): Long {
        check(roomId, message)
        val item = resource(message, false)
        val bytes = locks[(item.key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            ResumableDownloads.partialSize(File(root, "$partition/${item.key}.${item.extension}"), item.size)
        }
        check(roomId, message)
        return bytes
    }

    suspend fun poster(roomId: String, message: Message): Bitmap {
        val file = ensure(roomId, message, true) { _, _ -> }
        val bitmap = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0 ||
                options.outWidth.toLong() * options.outHeight > 100_000_000
            ) {
                file.delete()
                throw IOException("视频封面无效")
            }
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > 512) sample *= 2
            options.inSampleSize = sample
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.path, options) ?: run {
                file.delete()
                throw IOException("视频封面无效")
            }
        }
        check(roomId, message)
        return bitmap
    }

    suspend fun download(roomId: String, message: Message, onProgress: (Long, Long) -> Unit): File =
        ensure(roomId, message, false, onProgress)

    suspend fun position(roomId: String, message: Message): Long {
        check(roomId, message)
        val seconds = store.loadVideoPosition(owner, resource(message, false).key)
        check(roomId, message)
        return (seconds * 1000).toLong().coerceAtLeast(0)
    }

    suspend fun savePosition(roomId: String, message: Message, position: Long, duration: Long, ended: Boolean) {
        check(roomId, message)
        if (duration <= 0 || position < 0) return
        store.saveVideoPosition(
            owner, resource(message, false).key,
            if (ended) 0.0 else position.coerceAtMost(duration) / 1000.0, duration / 1000.0,
        )
    }

    private suspend fun ensure(
        roomId: String, message: Message, poster: Boolean, onProgress: (Long, Long) -> Unit,
    ): File {
        check(roomId, message)
        val item = resource(message, poster)
        return locks[(item.key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            check(roomId, message)
            cached(item)?.let { return@withLock it }
            val file = File(root, "$partition/${item.key}.${item.extension}")
            transfers.withPermit {
                validate(roomId, message, true)
                for (attempt in 0..1) {
                    val address = authorize(roomId, message, poster)
                    validate(roomId, message, true)
                    try {
                        CachedVideoFiles.download(address, file, item.size, item.extension, onProgress)
                        break
                    } catch (exception: ResumableDownloads.AuthorizationExpired) {
                        if (attempt == 1) throw IOException("下载授权已过期，请重试", exception)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(roomId, message)
            store.saveVideoFile(owner, item.key, "$partition/${file.name}")
            check(roomId, message)
            file
        }
    }

    private suspend fun cached(item: Resource): File? {
        val expected = "$partition/${item.key}.${item.extension}"
        val path = store.loadVideoFile(owner, item.key)
        if (path != null && path != expected) return null
        val file = File(root, expected)
        // Recover an atomic commit interrupted before the cache index was saved.
        return withContext(Dispatchers.IO) {
            file.takeIf { CachedVideoFiles.valid(it, item.size, item.extension) }
        }
    }

    private fun resource(message: Message, poster: Boolean): Resource {
        val body = message.body as? VideoMessageBody ?: throw IOException("视频不可用")
        val extra = body.extra ?: throw IOException("视频信息不完整")
        val address = (if (poster) extra.thumbUrl else body.content).orEmpty()
        val url = address.toHttpUrlOrNull()
        if (address.length !in 1..2048 || address.any { it <= ' ' } || url == null ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null
        ) throw IOException("视频资源无效")
        val size = (if (poster) extra.thumbSize else extra.size) ?: 0
        if (size !in 1..(if (poster) 2L * 1024 * 1024 else 2L * 1024 * 1024 * 1024)) {
            throw IOException("视频资源大小无效")
        }
        val name = extra.fileName.orEmpty()
        val extension = if (poster) "jpg" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!poster && (extension !in extensions || name.lastIndexOf('.') <= 0 ||
            name.any { it < ' ' || it in "/\\?#" })
        ) throw IOException("视频格式无效")
        return Resource(CachedVideoFiles.key(address), size, extension)
    }

    private data class Resource(val key: String, val size: Long, val extension: String)

    private companion object {
        val locks = Array(64) { Mutex() }
        val transfers = Semaphore(2)
        val extensions = setOf("mp4", "m4v", "mkv", "avi", "mov", "wmv", "flv", "webm", "mpg", "mpeg", "3gp")
    }
}
