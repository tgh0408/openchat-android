package com.im.openchat.chat.data

import com.im.openchat.common.network.SignedUploads
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Locale

internal const val MAX_IMAGE_UPLOAD_BYTES = 50L * 1024 * 1024

@Serializable
internal data class ImageUploadRequest(val fileName: String, val scene: Int = 4)

@Serializable
internal data class ImageUploadUrls(val uploadUrl: String, val downloadUrl: String)

@Serializable
internal data class ImageSendBody(
    val content: String,
    val uploadAddress: String,
    val fileName: String,
    val fileSize: String,
)

@Serializable
internal data class ImageMessageRequest(
    val roomId: Long,
    val clientMsgId: String,
    val body: ImageSendBody,
    val msgType: MessageType = MessageType.IMAGE,
)

internal fun validateImageName(name: String) {
    if (name.isBlank() || name.length > 255 ||
        name.any { it < ' ' || it == '\u007f' || it == '/' || it == '\\' }
    ) throw IOException("图片名称无效")
}

internal fun imageUploadContentType(name: String, size: Long, header: ByteArray): String {
    validateImageName(name)
    if (size !in 1..MAX_IMAGE_UPLOAD_BYTES) throw IOException("图片大小无效或超过 50 MiB")
    fun starts(vararg bytes: Int) =
        header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 255) == bytes[it] }
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> if (starts(137, 80, 78, 71, 13, 10, 26, 10)) "image/png" else null
        "jpg", "jpeg" -> if (starts(255, 216, 255)) "image/jpeg" else null
        "gif" -> if (starts(71, 73, 70, 56, 55, 97) || starts(71, 73, 70, 56, 57, 97)) "image/gif" else null
        "bmp" -> if (starts(66, 77)) "image/bmp" else null
        else -> null
    } ?: throw IOException("请选择有效的 JPG/JPEG、PNG、GIF 或 BMP 图片")
}

internal fun validateImageDimensions(width: Int, height: Int) {
    if (width <= 0 || height <= 0 || width.toLong() * height > 40_000_000L) {
        throw IOException("图片尺寸无效或像素超过 4000 万")
    }
}

internal fun ImageMessageBody.toSendBody(): ImageSendBody {
    val url = content ?: throw IOException("图片地址无效")
    if (url.length > 2048) throw IOException("图片地址无效")
    SignedUploads.address(url, false)
    val name = extra?.fileName ?: throw IOException("图片名称无效")
    validateImageName(name)
    val size = extra?.size ?: throw IOException("图片大小无效")
    if (size !in 1..MAX_IMAGE_UPLOAD_BYTES) throw IOException("图片大小无效或超过 50 MiB")
    return ImageSendBody(url, url, name, size.toString())
}
