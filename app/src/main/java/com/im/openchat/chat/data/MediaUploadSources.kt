package com.im.openchat.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.im.openchat.common.data.accountStoragePartition
import com.im.openchat.common.network.CachedVideoFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Private copies survive picker permission expiry; source paths never leave this device. */
internal class MediaUploadSources(context: Context, server: String, uid: String) {
    private val context = context.applicationContext
    private val partition = accountStoragePartition(server, uid)
    private val root = File(this.context.noBackupFilesDir, "upload/$partition")

    suspend fun installationId(): String = installationLock.withLock {
        withContext(Dispatchers.IO) {
            val marker = File(context.noBackupFilesDir, "upload-installation")
            val file = AtomicFile(marker)
            val saved = try {
                file.openRead().use {
                    if (it.channel.size() != 36L) throw IOException("本机上传标识无效")
                    it.readBytes().toString(Charsets.US_ASCII)
                }
            } catch (exception: FileNotFoundException) {
                if (marker.exists()) throw IOException("本机上传标识无法读取", exception)
                null
            }
            if (saved != null) {
                if (!Regex("[a-f0-9-]{36}").matches(saved)) throw IOException("本机上传标识不可用")
                saved
            } else {
                val value = UUID.randomUUID().toString()
                val output = file.startWrite()
                try {
                    output.write(value.toByteArray(Charsets.US_ASCII))
                    file.finishWrite(output)
                } catch (exception: Exception) {
                    file.failWrite(output)
                    throw exception
                }
                value
            }
        }
    }

    suspend fun documentName(uri: Uri): String = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw IOException("请选择系统文件选择器中的文件")
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        } ?: throw IOException("无法读取文件名")
        selectedFileKind(name)
        name
    }

    suspend fun prepare(uri: Uri, video: Boolean, expectedName: String? = null): MediaUploadSource {
        var selected: File? = null
        try {
            return withContext(Dispatchers.IO) { prepareFile(uri, video, expectedName) { selected = it } }
        } catch (exception: Exception) {
            // Also handle prompt cancellation while returning from the IO dispatcher.
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { selected?.delete() }
            throw exception
        }
    }

    suspend fun prepareCachedFile(original: File, name: String, size: Long, video: Boolean = false): MediaUploadSource {
        var selected: File? = null
        try {
            return withContext(Dispatchers.IO) {
                if (name.isBlank() || name.length > 255 || name.any { it.isISOControl() || it in "/\\" } ||
                    size !in 1..6L * 1024 * 1024 * 1024 || !original.isFile || original.length() != size
                ) throw IOException("聊天文件已失效，请重新选择")
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (video && (extension !in VIDEO_TYPES || name.lastIndexOf('.') <= 0 || name.any { it in "?#" })) {
                    throw IOException("该视频封装或文件名暂不支持上传")
                }
                if (video && size > 2L * 1024 * 1024 * 1024) throw IOException("视频不能超过 2 GiB")
                if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建上传目录")
                val file = File(root, "${UUID.randomUUID()}.${if (video) extension else "bin"}")
                selected = file
                original.inputStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var length = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            length += count
                            if (length > size) throw IOException("聊天文件已变化")
                            output.write(buffer, 0, count)
                        }
                        if (length != size) throw IOException("聊天文件不完整")
                        output.fd.sync()
                    }
                }
                if (video && !CachedVideoFiles.valid(file, size, extension)) throw IOException("视频格式无效")
                val (digest, parts) = digest(file)
                MediaUploadSource(
                    FileUploadSource(file.path, name, size, digest, parts),
                    if (video) videoMetadata(file) else null,
                )
            }
        } catch (exception: Exception) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { selected?.delete() }
            throw exception
        }
    }

    private suspend fun prepareFile(
        uri: Uri, video: Boolean, expectedName: String?, selected: (File) -> Unit,
    ): MediaUploadSource {
        val name = documentName(uri)
        if (expectedName != null && name != expectedName) throw IOException("文件名称已变化，请重新选择")
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (video && (extension !in VIDEO_TYPES || name.lastIndexOf('.') <= 0 || name.any { it in "?#" })) {
            throw IOException("该视频封装或文件名暂不支持上传")
        }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建上传目录")
        val file = File(root, "${UUID.randomUUID()}.${if (video) extension else "bin"}")
        selected(file)
        try {
            val maximum = if (video) 2L * 1024 * 1024 * 1024 else 6L * 1024 * 1024 * 1024
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var length = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        length += count
                        if (length > maximum) throw IOException(if (video) "视频不能超过 2 GiB" else "文件不能超过 6 GiB")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("无法读取所选文件")
            if (file.length() <= 0 || video && !CachedVideoFiles.valid(file, file.length(), extension)) {
                throw IOException("文件为空或视频格式无效")
            }
            val (digest, parts) = digest(file)
            return MediaUploadSource(
                FileUploadSource(file.path, name, file.length(), digest, parts),
                if (video) videoMetadata(file) else null,
            )
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }

    fun checkedFile(source: MediaUploadSource): File {
        val file = File(source.file.filePath)
        if (file.parentFile?.canonicalFile != root.canonicalFile || !file.isFile ||
            file.canonicalFile != File(root.canonicalFile, file.name) || file.length() != source.file.fileSize
        ) throw IOException("本机上传原文件已丢失或发生变化")
        return file
    }

    suspend fun verify(source: MediaUploadSource) = withContext(Dispatchers.IO) {
        val file = checkedFile(source)
        val (digest, parts) = digest(file)
        if (digest != source.file.digest || parts != source.file.partDigests) throw IOException("原文件已变化，不能继续发送")
    }

    suspend fun discard(source: MediaUploadSource) = withContext(Dispatchers.IO) {
        val file = File(source.file.filePath)
        if (file.parentFile?.canonicalFile != root.canonicalFile ||
            file.canonicalFile != File(root.canonicalFile, file.name)
        ) throw IOException("上传来源路径无效")
        if (file.exists() && !file.delete()) throw IOException("无法清理本机上传副本")
        Unit
    }

    private suspend fun digest(file: File): Pair<String, List<String>> {
        val whole = MessageDigest.getInstance("SHA-256")
        var part = MessageDigest.getInstance("SHA-256")
        val digests = mutableListOf<String>()
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = UPLOAD_PART_BYTES - (count % UPLOAD_PART_BYTES).toInt()
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                whole.update(buffer, 0, read)
                part.update(buffer, 0, read)
                count += read
                if (count % UPLOAD_PART_BYTES == 0L) {
                    digests += hex(part.digest())
                    part = MessageDigest.getInstance("SHA-256")
                }
            }
        }
        if (count % UPLOAD_PART_BYTES != 0L) digests += hex(part.digest())
        return hex(whole.digest()) to digests
    }

    private fun videoMetadata(file: File): VideoUploadMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it in 1..86_400_000 } ?: throw IOException("无法读取有效视频时长")
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0 || width.toLong() * height > 40_000_000) throw IOException("视频尺寸无效")
            val scale = minOf(1.0, 512.0 / maxOf(width, height))
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
            } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame == null) throw IOException("无法生成视频封面")
            val thumbnail = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
            try {
                val bytes = ByteArrayOutputStream().use { output ->
                    if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw IOException("视频封面生成失败")
                    output.toByteArray()
                }
                if (bytes.size !in 1..2 * 1024 * 1024) throw IOException("视频封面大小无效")
                return VideoUploadMetadata(duration, thumbnail.width, thumbnail.height, bytes)
            } finally {
                if (thumbnail !== frame) thumbnail.recycle()
                frame.recycle()
            }
        } catch (exception: RuntimeException) {
            throw IOException("该视频无法读取元数据，请重新选择", exception)
        } finally {
            retriever.release()
        }
    }

    companion object {
        private val installationLock = Mutex()
        val VIDEO_TYPES = mapOf(
            "mp4" to "video/mp4",
            "mov" to "video/quicktime", "mkv" to "video/x-matroska", "webm" to "video/webm",
            "avi" to "video/x-msvideo", "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
            "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
        )
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
