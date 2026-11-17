package com.im.openchat.common.network

import java.io.IOException

internal data class DownloadRange(val start: Long, val end: Long, val total: Long) {
    val length: Long get() = end - start + 1

    companion object {
        private val pattern = Regex("""bytes (\d+)-(\d+)/(\d+)""")

        fun parse(value: String?): DownloadRange {
            val match = pattern.matchEntire(value.orEmpty()) ?: throw IOException("Invalid download range")
            val (start, end, total) = match.destructured
            val range = DownloadRange(
                start.toLongOrNull() ?: throw IOException("Invalid range start"),
                end.toLongOrNull() ?: throw IOException("Invalid range end"),
                total.toLongOrNull() ?: throw IOException("Invalid range size"),
            )
            if (range.start < 0 || range.end < range.start || range.end >= range.total) {
                throw IOException("Invalid download range bounds")
            }
            return range
        }
    }
}
