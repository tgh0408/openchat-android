package com.im.openchat.common.network

import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal object CachedVideoFiles {

    fun key(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun valid(file: File, size: Long, extension: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        return try {
            val header = ByteArray(32)
            val length = file.inputStream().use { it.read(header) }
            fun matches(offset: Int, vararg bytes: Int) =
                offset + bytes.size <= length && bytes.indices.all { (header[offset + it].toInt() and 255) == bytes[it] }
            fun text(offset: Int, value: String) = matches(offset, *value.map { it.code }.toIntArray())
            if (extension == "jpg") matches(0, 255, 216, 255) else length >= 12 && when (extension) {
                "mp4", "m4v", "3gp" -> text(4, "ftyp")
                "mov" -> listOf("ftyp", "moov", "mdat", "wide", "free").any { text(4, it) }
                "mkv", "webm" -> matches(0, 0x1a, 0x45, 0xdf, 0xa3)
                "avi" -> text(0, "RIFF") && text(8, "AVI ")
                "wmv" -> matches(0, 0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11, 0xa6, 0xd9, 0, 0xaa, 0, 0x62, 0xce, 0x6c)
                "flv" -> matches(0, 70, 76, 86, 1) && (header[4].toInt() and 1) == 1
                "mpg", "mpeg" -> matches(0, 0, 0, 1, 0xba) || matches(0, 0, 0, 1, 0xb3)
                else -> false
            }
        } catch (_: IOException) {
            false
        }
    }

    /** 不使用 JWT、Cookie 或重定向；半文件不进入完整缓存。 */
    suspend fun download(
        address: String,
        file: File,
        size: Long,
        extension: String,
        onProgress: (Long, Long) -> Unit,
    ) = ResumableDownloads.download(address, file, size, { valid(it, size, extension) }, onProgress)
}
