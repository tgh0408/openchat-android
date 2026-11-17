package com.im.openchat.chat.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.im.openchat.chat.data.VideoTranscoder
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
internal class TranscodedVideoDataSource(private val transcoder: VideoTranscoder) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var data: ByteArray? = null
    private var offset = 0
    private var remaining = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        try {
            val bytes = transcoder.read(dataSpec.uri)
            if (dataSpec.position !in 0..bytes.size.toLong()) throw IOException("VIDEO_TRANSCODE_INVALID_RANGE")
            offset = dataSpec.position.toInt()
            remaining = bytes.size - offset
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                remaining = minOf(remaining.toLong(), dataSpec.length).toInt()
            }
            data = bytes
            opened = true
            transferStarted(dataSpec)
            return remaining.toLong()
        } catch (exception: IOException) {
            uri = null
            throw exception
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0) return C.RESULT_END_OF_INPUT
        val bytes = data ?: throw IOException("VIDEO_TRANSCODE_SOURCE_CLOSED")
        val count = minOf(length, remaining)
        bytes.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri?.let(transcoder::cancelRead)
        uri = null
        data = null
        remaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val transcoder: VideoTranscoder) : DataSource.Factory {
        override fun createDataSource(): DataSource = TranscodedVideoDataSource(transcoder)
    }
}
