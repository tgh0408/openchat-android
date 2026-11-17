package com.im.openchat.chat.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun supportsVideoPlayback(context: Context, file: File): Boolean =
    withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(15_000) {
            val probe = ExoPlayer.Builder(
                context, DefaultRenderersFactory(context).setEnableDecoderFallback(true),
            ).build()
            try {
                // 提取并检查轨道，不分配解码器，也不中断当前播放。
                probe.trackSelectionParameters = probe.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                suspendCancellableCoroutine<Boolean> { continuation ->
                    probe.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (continuation.isActive &&
                                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)
                            ) {
                                continuation.resume(
                                    player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true),
                                )
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (!continuation.isActive) return
                            if (isUnsupportedVideoPlayback(error)) continuation.resume(false)
                            else continuation.resumeWithException(
                                IOException("无法检查视频，请重试"),
                            )
                        }
                    })
                    probe.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    probe.prepare()
                }
            } finally {
                probe.release()
            }
        } ?: throw IOException("视频格式检查超时，请重试")
    }

internal fun isUnsupportedVideoPlayback(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> true
    else -> false
}
