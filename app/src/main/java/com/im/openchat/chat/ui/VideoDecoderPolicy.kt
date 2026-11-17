package com.im.openchat.chat.ui

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@androidx.annotation.OptIn(UnstableApi::class)
internal fun videoRenderers(context: Context, failedDecoders: Set<String> = emptySet()): DefaultRenderersFactory {
    val excluded = failedDecoders.toSet()
    return DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                .filterNot { it.name in excluded }
        }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun failedVideoDecoder(error: PlaybackException): String? =
    generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<MediaCodecDecoderException>()
        .firstOrNull()?.codecInfo
        ?.takeIf { it.mimeType.startsWith("video/") }?.name
