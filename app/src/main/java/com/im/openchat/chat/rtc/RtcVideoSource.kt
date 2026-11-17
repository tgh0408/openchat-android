package com.im.openchat.chat.rtc

import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/** Rendering is independent of whether the remote track belongs to P2P or LiveKit. */
internal interface RtcVideoSource {
    fun addRenderer(renderer: VideoSink)
    fun removeRenderer(renderer: VideoSink)
}

internal class RtcSdkVideoSource(private val track: VideoTrack) : RtcVideoSource {
    override fun addRenderer(renderer: VideoSink) = track.addRenderer(renderer)
    override fun removeRenderer(renderer: VideoSink) = track.removeRenderer(renderer)
}

/** The peer owns the native track; the UI never accesses a disposed receiver. */
internal class RtcDirectVideoSource : RtcVideoSource, VideoSink {
    private val sinks = linkedSetOf<VideoSink>()
    private var closed = false

    @Synchronized override fun addRenderer(renderer: VideoSink) {
        if (!closed) sinks += renderer
    }

    @Synchronized override fun removeRenderer(renderer: VideoSink) {
        sinks -= renderer
    }

    @Synchronized override fun onFrame(frame: VideoFrame) {
        if (!closed) sinks.forEach { it.onFrame(frame) }
    }

    // Wait for any in-flight delivery before the UI releases its renderer or the peer its track.
    @Synchronized fun close() {
        closed = true
        sinks.clear()
    }
}
