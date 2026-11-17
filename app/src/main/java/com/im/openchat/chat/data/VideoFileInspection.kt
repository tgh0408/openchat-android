package com.im.openchat.chat.data

import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 独立的容器检查工具，不是解码器或替代播放引擎。 */
internal data class VideoFileInspection(
    val completed: Boolean,
    val videoTracks: Int,
    val audioTracks: Int,
) {
    companion object {
        val Unknown = VideoFileInspection(false, 0, 0)
    }
}

internal suspend fun inspectVideoFile(file: File): VideoFileInspection = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        // 检查提供给 ExoPlayer 的同一份私有文件，不访问远程地址。
        file.inputStream().use { input ->
            extractor.setDataSource(input.fd)
            var video = 0
            var audio = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else null
                when {
                    mime?.startsWith("video/") == true -> video++
                    mime?.startsWith("audio/") == true -> audio++
                }
            }
            VideoFileInspection(true, video, audio)
        }
    } catch (_: IOException) {
        VideoFileInspection.Unknown
    } catch (_: RuntimeException) {
        // 平台提取器可能拒绝其他提取器支持的容器格式。
        VideoFileInspection.Unknown
    } finally {
        extractor.release()
    }
}
