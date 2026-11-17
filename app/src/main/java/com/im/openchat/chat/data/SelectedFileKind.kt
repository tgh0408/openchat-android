package com.im.openchat.chat.data

import java.io.IOException
import java.util.Locale

internal enum class SelectedFileKind { IMAGE, VIDEO, FILE }

/** The suffix chooses the pipeline, never replaces that pipeline's byte validation. */
internal fun selectedFileKind(name: String): SelectedFileKind {
    if (name.isBlank() || name.length > 255 || name.any { it.isISOControl() || it in "/\\" }) {
        throw IOException("文件名无效")
    }
    val extension = if (name.lastIndexOf('.') > 0) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif", "tif", "tiff", "svg" -> SelectedFileKind.IMAGE
        "mp4", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpg", "mpeg", "m4v", "3gp",
        "3g2", "ts", "mts", "m2ts", "vob", "ogv" -> SelectedFileKind.VIDEO
        else -> SelectedFileKind.FILE
    }
}
