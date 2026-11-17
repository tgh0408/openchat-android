package com.im.openchat.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.im.openchat.chat.data.VideoMessageExtra
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlaybackContent(
    player: Player?,
    floating: Boolean,
    extra: VideoMessageExtra?,
    zoomEnabled: Boolean = !floating,
    onVideoBounds: (android.graphics.Rect, Float) -> Unit = { _, _ -> },
) {
    var videoSize by remember(player) { mutableStateOf(player?.videoSize ?: VideoSize.UNKNOWN) }
    var surfaceSize by remember(player, floating) { mutableStateOf(IntSize.Zero) }
    DisposableEffect(player, floating) {
        val listener = object : Player.Listener {
            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                surfaceSize = IntSize(width, height)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                // 分段切换时可能报告 UNKNOWN，此时保留上一次实际显示尺寸。
                if (videoDisplayRatio(size.width, size.height, size.pixelWidthHeightRatio) != null) {
                    videoSize = size
                }
            }
        }
        player?.addListener(listener)
        player?.videoSize?.let(listener::onVideoSizeChanged)
        player?.surfaceSize?.let { listener.onSurfaceSizeChanged(it.width, it.height) }
        onDispose { player?.removeListener(listener) }
    }
    val actualRatio = videoDisplayRatio(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
    val ratio = actualRatio ?: videoDisplayRatio(extra?.thumbWidth ?: 0, extra?.thumbHeight ?: 0) ?: (16f / 9f)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val target = fitVideoSurface(constraints.maxWidth, constraints.maxHeight, ratio)
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        var transform by remember(player, floating, zoomEnabled, target, viewport) { mutableStateOf(VideoViewportTransform()) }
        val gestureState = rememberTransformableState { _, zoom, pan, _ ->
            transform = transformVideoViewport(transform, zoom, pan, target, viewport)
        }
        var laidOutSize by remember { mutableStateOf(IntSize.Zero) }
        var layoutReady by remember(player, floating, actualRatio, target) { mutableStateOf(false) }
        LaunchedEffect(player, floating, actualRatio, target, laidOutSize, surfaceSize) {
            if (actualRatio != null && target.width > 0 && target.height > 0 &&
                laidOutSize == target && surfaceSize == target
            ) {
                // 等待 Compose 完成布局且实际输出画面采用新尺寸。
                // 这里只等待布局，不等待首帧，避免暂停播放时一直卡住。
                withFrameNanos { }
                layoutReady = true
            }
        }
        Box(
            Modifier.fillMaxSize().clipToBounds()
                .transformable(
                    state = gestureState,
                    canPan = { transform.scale > 1f },
                    enabled = zoomEnabled,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(
                    with(density) { target.width.toDp() },
                    with(density) { target.height.toDp() },
                ).onGloballyPositioned {
                    laidOutSize = it.size
                    val bounds = it.boundsInWindow()
                    onVideoBounds(
                        android.graphics.Rect(
                            bounds.left.roundToInt(), bounds.top.roundToInt(),
                            bounds.right.roundToInt(), bounds.bottom.roundToInt(),
                        ),
                        ratio,
                    )
                }
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offset.x
                        translationY = transform.offset.y
                    },
            ) {
                // 只变换纹理，不调整尺寸或替换解码器的输出画面。
                ContentFrame(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    // 外层容器负责等比适配，ContentFrame 不得异步调整其尺寸。
                    contentScale = ContentScale.FillBounds,
                    shutter = {},
                )
            }
        }
        if (!layoutReady) Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

internal data class VideoViewportTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

internal fun transformVideoViewport(
    current: VideoViewportTransform,
    zoomChange: Float,
    panChange: Offset,
    content: IntSize,
    viewport: IntSize,
): VideoViewportTransform {
    if (!zoomChange.isFinite() || zoomChange <= 0f ||
        !panChange.x.isFinite() || !panChange.y.isFinite()
    ) return current
    val scale = (current.scale * zoomChange).coerceIn(1f, 5f)
    if (scale == 1f) return VideoViewportTransform()
    val maxX = ((content.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((content.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    val offset = current.offset + panChange
    return VideoViewportTransform(
        scale,
        Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY)),
    )
}

internal fun videoDisplayRatio(width: Int, height: Int, pixelRatio: Float = 1f): Float? {
    if (width <= 0 || height <= 0 || !pixelRatio.isFinite() || pixelRatio <= 0f) return null
    return (width.toFloat() * pixelRatio / height).takeIf { it.isFinite() && it > 0f }
}

internal fun fitVideoSurface(maxWidth: Int, maxHeight: Int, ratio: Float): IntSize {
    if (maxWidth <= 0 || maxHeight <= 0) return IntSize.Zero
    require(ratio.isFinite() && ratio > 0f)
    return if (maxWidth.toFloat() / maxHeight > ratio) {
        IntSize((maxHeight * ratio).roundToInt().coerceIn(1, maxWidth), maxHeight)
    } else {
        IntSize(maxWidth, (maxWidth / ratio).roundToInt().coerceIn(1, maxHeight))
    }
}
