package com.im.openchat.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.accountStoragePartition
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.common.network.CachedImages
import com.im.openchat.common.network.SignedUploads
import com.im.openchat.login.data.InvalidSessionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal class ImageUploadDraft(
    val roomId: String,
    val clientMsgId: String,
    val name: String,
    val size: Long,
    val contentType: String,
    internal val file: File,
    internal val thumbnail: Bitmap? = null,
) {
    internal var uploaded: ImageMessageBody? = null
}

internal interface ImageUploadOperations {
    suspend fun prepare(roomId: String, uri: Uri): ImageUploadDraft
    suspend fun prepareCached(roomId: String, file: File, name: String, size: Long): ImageUploadDraft =
        throw IOException("聊天图片发送尚未就绪")
    suspend fun send(draft: ImageUploadDraft)
    suspend fun discard(draft: ImageUploadDraft)
}

internal class ImageUploadRepository(
    context: Context,
    private val api: ApiClient,
    private val owner: ChatDataOwner,
    private val sender: MessageSendRepository,
    private val validate: (String) -> Unit,
    private val onUnauthorized: () -> Unit,
) : ImageUploadOperations {
    private val context = context.applicationContext
    private val directory = File(this.context.cacheDir,
        "image-drafts/${accountStoragePartition(owner.serverUrl, owner.userId)}")

    override suspend fun prepare(roomId: String, uri: Uri): ImageUploadDraft = prepareSource(
        roomId,
        name = {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            } ?: throw IOException("无法读取图片名称，请重新选择")
        },
        open = { context.contentResolver.openInputStream(uri) },
    )

    override suspend fun prepareCached(roomId: String, file: File, name: String, size: Long): ImageUploadDraft =
        prepareSource(
            roomId, name = { name }, expectedSize = size,
            open = {
                if (!file.isFile || file.length() != size) throw IOException("聊天图片已变化")
                file.inputStream()
            },
        )

    private suspend fun prepareSource(
        roomId: String, name: () -> String, open: () -> InputStream?, expectedSize: Long? = null,
    ): ImageUploadDraft {
        validate(roomId)
        val clientId = UUID.randomUUID().toString()
        val file = File(directory, "$clientId.img")
        try {
            val draft = withContext(Dispatchers.IO) {
                val displayName = name()
                validateImageName(displayName)
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("无法准备图片")
                }
                var size = 0L
                open()?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > MAX_IMAGE_UPLOAD_BYTES) throw IOException("图片大小超过 50 MiB")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("无法读取所选图片")
                if (expectedSize != null && size != expectedSize) throw IOException("聊天图片已变化")
                val header = file.inputStream().use { input ->
                    val bytes = ByteArray(8)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    bytes.copyOf(count)
                }
                val contentType = imageUploadContentType(displayName, size, header)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                validateImageDimensions(options.outWidth, options.outHeight)
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 512) sample *= 2
                options.inSampleSize = sample
                options.inJustDecodeBounds = false
                val thumbnail = BitmapFactory.decodeFile(file.path, options)
                ensureActive()
                ImageUploadDraft(roomId, clientId, displayName, size, contentType, file, thumbnail)
            }
            validate(roomId)
            return draft
        } catch (exception: Exception) {
            withContext(Dispatchers.IO + NonCancellable) { file.delete() }
            throw exception
        }
    }

    override suspend fun send(draft: ImageUploadDraft) {
        validate(draft.roomId)
        val image = draft.uploaded ?: upload(draft).also { draft.uploaded = it }
        validate(draft.roomId)
        CachedImages.storeUploaded(context, "messages\n${owner.serverUrl}\n${owner.userId}",
            "${draft.roomId}\n${image.content}", draft.file, thumbnail = draft.thumbnail)
        validate(draft.roomId)
        sender.queueImage(draft.roomId, image, clientMsgId = draft.clientMsgId,
            validate = { validate(draft.roomId) })
    }

    override suspend fun discard(draft: ImageUploadDraft) {
        withContext(Dispatchers.IO + NonCancellable) { draft.file.delete() }
    }

    private suspend fun upload(draft: ImageUploadDraft): ImageMessageBody {
        val urls = try {
            val response = api.postJson("/capi/oss/upload/url",
                MessageHistoryProtocol.encodeTransfer(ImageUploadRequest.serializer(), ImageUploadRequest(draft.name)))
            validate(draft.roomId)
            MessageHistoryProtocol.decodeTransfer(ImageUploadUrls.serializer(), response.body)
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode in setOf(401, 403)) {
                onUnauthorized()
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("获取图片上传地址失败，请重试")
        } catch (exception: InvalidSessionException) {
            onUnauthorized()
            throw exception
        }
        if (urls.downloadUrl.length > 2048) throw IOException("图片地址无效")
        val image = ImageMessageBody(SignedUploads.address(urls.downloadUrl, false).toString(),
            FileMessageExtra(draft.name, draft.size))
        image.toSendBody()
        SignedUploads.address(urls.uploadUrl, true)
        validate(draft.roomId)
        // OSS failures are upload failures, not business-session expiration.
        SignedUploads.putImage(urls.uploadUrl, draft.file, draft.contentType)
        validate(draft.roomId)
        return image
    }
}
