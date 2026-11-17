package com.im.openchat.chat.data

import java.util.Locale

/** 完整的 VOD 时间线；其中相互独立的分段可以按任意顺序编码。 */
internal class VideoTranscodePlan(val durationMs: Long, val videoStream: Long, val audioStream: Long?) {
    init {
        require(durationMs in 1..86_400_000)
        require(videoStream >= 0 && (audioStream == null || audioStream >= 0))
    }

    val segmentCount: Int = run {
        val count = ((durationMs + SEGMENT_MS - 1) / SEGMENT_MS).toInt()
        val finalLength = durationMs - (count - 1) * SEGMENT_MS
        if (count > 1 && finalLength < MIN_FINAL_SEGMENT_MS) count - 1 else count
    }

    fun indexAt(positionMs: Long): Int =
        (positionMs.coerceIn(0, durationMs - 1) / SEGMENT_MS).toInt().coerceAtMost(segmentCount - 1)

    fun startMs(index: Int): Long {
        require(index in 0 until segmentCount)
        return index * SEGMENT_MS
    }

    fun lengthMs(index: Int): Long {
        val remaining = durationMs - startMs(index)
        return if (index == segmentCount - 1 && remaining > SEGMENT_MS) {
            remaining
        } else {
            minOf(SEGMENT_MS, remaining)
        }
    }

    fun playlist(): String = buildString {
        val targetDuration = ((0 until segmentCount).maxOf { lengthMs(it) } + 999) / 1000
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:")
            .append(targetDuration).append('\n')
        append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        for (index in 0 until segmentCount) {
            // 每个独立编码的 TS 都会重置时间戳和 AAC 编码器延迟。
            if (index > 0) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:").append(seconds(lengthMs(index))).append(",\n")
            append("segment/").append(index).append(".ts\n")
        }
        append("#EXT-X-ENDLIST\n")
    }

    fun arguments(source: String, output: String, index: Int): Array<String> {
        val arguments = mutableListOf(
            "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
            "-threads", "2", "-hwaccel", "none", "-protocol_whitelist", "file",
            "-format_whitelist", "mov,matroska,webm,avi,asf,flv,mpeg,mpegvideo",
            "-ss", seconds(startMs(index)), "-i", source,
            "-map", "0:$videoStream",
        )
        audioStream?.let { arguments.addAll(listOf("-map", "0:$it")) }
        arguments.addAll(listOf(
            "-map_metadata", "-1", "-map_chapters", "-1", "-sn", "-dn",
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-profile:v", "baseline", "-level:v", "3.1", "-pix_fmt", "yuv420p",
            "-vf", "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1,fps=30",
            "-crf", "23", "-maxrate", "4M", "-bufsize", "8M",
            "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-bf", "0", "-threads", "2",
        ))
        if (audioStream != null) arguments.addAll(listOf(
            "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000",
            "-af", "aresample=async=1:first_pts=0",
        ))
        arguments.addAll(listOf(
            "-t", seconds(lengthMs(index)), "-avoid_negative_ts", "make_zero",
            "-muxdelay", "0", "-muxpreload", "0", "-mpegts_flags", "+resend_headers",
            "-f", "mpegts", output,
        ))
        return arguments.toTypedArray()
    }

    private fun seconds(value: Long): String = String.format(Locale.ROOT, "%.3f", value / 1000.0)

    companion object {
        const val SEGMENT_MS = 2_000L
        private const val MIN_FINAL_SEGMENT_MS = 100L
    }
}
