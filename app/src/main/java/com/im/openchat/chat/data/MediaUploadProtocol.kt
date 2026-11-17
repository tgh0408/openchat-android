package com.im.openchat.chat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException

internal const val UPLOAD_PART_BYTES = 16 * 1024 * 1024

internal fun encodeUploadPartDigests(parts: List<String>): String =
    Json.encodeToString(ListSerializer(String.serializer()), parts)

internal fun decodeUploadPartDigests(value: String): List<String> = try {
    Json.decodeFromString(ListSerializer(String.serializer()), value)
} catch (exception: Exception) {
    throw IOException("本机分片摘要无效", exception)
}

@Serializable
internal data class FileUploadSource(
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val digest: String,
    val partDigests: List<String>,
    val cancelRequested: Boolean = false,
)

internal data class VideoUploadMetadata(
    val duration: Long, val thumbWidth: Int, val thumbHeight: Int, val poster: ByteArray,
)

internal data class MediaUploadSource(val file: FileUploadSource, val video: VideoUploadMetadata? = null) {
    val targetType: MessageType get() = if (video == null) MessageType.FILE else MessageType.VIDEO
    fun placeholder() = TemporaryMessageBody(extra = TemporaryMessageExtra(
        targetType, file.fileName, file.fileSize, video?.duration, video?.thumbWidth, video?.thumbHeight, false,
    ))
}

@Serializable
internal data class TemporaryUploadMetadata(
    val targetType: MessageType,
    val fileName: String,
    val fileSize: String,
    val duration: String? = null,
    val thumbWidth: Int? = null,
    val thumbHeight: Int? = null,
)

@Serializable
internal data class CreateUploadRequest(
    val roomId: String, val clientMsgId: String, val body: TemporaryUploadMetadata,
    val msgType: MessageType = MessageType.TEMPORARY,
)

@Serializable
internal data class UploadIdentityRequest(val roomId: String, val clientMsgId: String)

@Serializable
internal data class UploadStateRequest(val roomId: String, val msgIds: List<String>)

@Serializable
internal data class MultipartInitiateRequest(
    val roomId: String, val clientMsgId: String, val targetType: MessageType,
    val fileName: String, val fileSize: String, val sha256: String,
)

@Serializable
internal data class MultipartIdentity(val sessionKey: String, val uploadId: String)

@Serializable
internal data class MultipartPartRequest(val sessionKey: String, val uploadId: String, val partNumber: Int)

@Serializable
internal data class MultipartPart(val partNumber: Int, val etag: String, val size: Long)

@Serializable
internal data class MultipartState(
    val sessionKey: String,
    val uploadId: String,
    val downloadUrl: String,
    val fileSize: Long,
    val partSize: Int,
    val completed: Boolean,
    val parts: List<MultipartPart>,
) {
    fun validate(size: Long) {
        if (size !in 1..6L * 1024 * 1024 * 1024 ||
            !Regex("[a-f0-9]{64}").matches(sessionKey) || uploadId.isBlank() || uploadId.length > 512 ||
            uploadId.any { it <= ' ' } || fileSize != size || partSize != UPLOAD_PART_BYTES ||
            parts.size > (size + partSize - 1) / partSize
        ) throw IOException("上传会话无效")
        val seen = mutableSetOf<Int>()
        parts.forEach {
            val offset = (it.partNumber.toLong() - 1) * partSize
            if (it.partNumber < 1 || offset >= size || !seen.add(it.partNumber) ||
                it.size != minOf(partSize.toLong(), size - offset) ||
                it.etag.isBlank() || it.etag.length > 1024
            ) throw IOException("已上传分片信息无效")
        }
    }
}

@Serializable
internal data class UploadUrl(val uploadUrl: String)

@Serializable
internal data class PosterUploadRequest(
    val roomId: String, val clientMsgId: String, val scene: Int = 4, val fileName: String = "poster.jpg",
)

@Serializable
internal data class PosterUploadUrls(val uploadUrl: String, val downloadUrl: String)

@Serializable
internal data class CompletedUploadBody(
    val content: String,
    val uploadAddress: String,
    val fileName: String,
    val fileSize: String,
    val thumbUrl: String? = null,
    val thumbSize: String? = null,
    val thumbWidth: Int? = null,
    val thumbHeight: Int? = null,
    val duration: String? = null,
)

@Serializable
internal data class CompleteUploadRequest(
    val roomId: String, val msgId: String, val clientMsgId: String,
    val body: CompletedUploadBody? = null, val fileBody: CompletedUploadBody? = null,
)

internal fun MediaUploadSource.createRequest(roomId: String, clientMsgId: String) = CreateUploadRequest(
    roomId, clientMsgId, TemporaryUploadMetadata(
        targetType, file.fileName, file.fileSize.toString(),
        video?.duration?.toString(), video?.thumbWidth, video?.thumbHeight,
    ),
)
