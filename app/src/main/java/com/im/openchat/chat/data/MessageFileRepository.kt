package com.im.openchat.chat.data

import android.content.Context
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

internal class MessageFileRepository(
    context: Context,
    private val api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
    private val validate: (String, Message, Boolean) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    private val root = File(context.applicationContext.noBackupFilesDir, "attachment")
    private val partition = accountStoragePartition(owner.serverUrl, owner.userId)

    suspend fun status(roomId: String, message: Message): Pair<Boolean, Long> {
        validate(roomId, message, false)
        val item = resource(message)
        val result = lock(item).withLock {
            val cached = cached(item)
            (cached != null) to if (cached != null) item.size else ResumableDownloads.partialSize(file(item), item.size)
        }
        validate(roomId, message, false)
        return result
    }

    suspend fun download(roomId: String, message: Message, onProgress: (Long, Long) -> Unit): File {
        validate(roomId, message, false)
        val item = resource(message)
        return lock(item).withLock {
            validate(roomId, message, false)
            cached(item)?.let { return@withLock it }
            val file = file(item)
            transfers.withPermit {
                for (attempt in 0..1) {
                    validate(roomId, message, true)
                    val address = MessageDownloadAuthorization(
                        api, { _, remote -> validate(roomId, message, remote) }, onUnauthorized,
                    ).load(roomId, message.id)
                    validate(roomId, message, true)
                    try {
                        ResumableDownloads.download(
                            address, file, item.size,
                            { it.isFile && it.length() == item.size }, onProgress,
                        )
                        break
                    } catch (exception: ResumableDownloads.AuthorizationExpired) {
                        if (attempt == 1) throw IOException("下载授权已过期，请重试", exception)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            validate(roomId, message, false)
            store.saveAttachmentFile(owner, item.key, "$partition/${file.name}")
            validate(roomId, message, false)
            file
        }
    }

    private suspend fun cached(item: Resource): File? {
        val expected = "$partition/${item.key}.bin"
        val path = store.loadAttachmentFile(owner, item.key)
        if (path != null && path != expected) return null
        return withContext(Dispatchers.IO) { file(item).takeIf { it.isFile && it.length() == item.size } }
    }

    private fun file(item: Resource) = File(root, "$partition/${item.key}.bin")
    private fun lock(item: Resource) = locks[(item.key.hashCode() and Int.MAX_VALUE) % locks.size]

    private fun resource(message: Message): Resource {
        val body = message.body as? FileMessageBody ?: throw IOException("文件不可用")
        val address = body.content.orEmpty()
        val url = address.toHttpUrlOrNull()
        val size = body.extra?.size ?: 0L
        val name = body.extra?.fileName.orEmpty()
        if (address.length !in 1..2048 || address.any { it <= ' ' } || url == null ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            size !in 1..6L * 1024 * 1024 * 1024 || name.isBlank() || name.length > 255 ||
            name.any { it.isISOControl() || it in "/\\" }
        ) throw IOException("文件信息无效")
        return Resource(CachedVideoFiles.key(address), size)
    }

    private data class Resource(val key: String, val size: Long)

    private companion object {
        val locks = Array(64) { Mutex() }
        val transfers = Semaphore(2)
    }
}
