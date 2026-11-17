package com.im.openchat.chat.ui

import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.im.openchat.R
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.MessageVideoRepository
import com.im.openchat.chat.data.VideoMessageBody
import com.im.openchat.chat.data.VideoFileInspection
import com.im.openchat.chat.data.VideoTranscoder
import com.im.openchat.chat.data.inspectVideoFile
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal val LocalVideoPlayback = staticCompositionLocalOf<VideoPlaybackController?> { null }

private val videoCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal class VideoPlaybackController(val repository: MessageVideoRepository) {
    var request by mutableStateOf<VideoPlaybackRequest?>(null)
        private set
    var floating by mutableStateOf(false)
        private set

    suspend fun open(roomId: String, message: Message, file: File) {
        repository.check(roomId, message)
        if (request?.let { it.roomId == roomId && it.message.id == message.id && it.message.body == message.body } != true) {
            request = VideoPlaybackRequest(roomId, message, file)
        }
        floating = false
    }

    fun minimize() { floating = true }
    fun restore() { floating = false }
    fun close() { request = null; floating = false }
}

internal data class VideoPlaybackRequest(val roomId: String, val message: Message, val file: File)

@Composable
internal fun VideoPlaybackHost(controller: VideoPlaybackController?) {
    val request = controller?.request ?: return
    key(controller, request) { VideoPlaybackWindow(controller, request) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlaybackWindow(controller: VideoPlaybackController, request: VideoPlaybackRequest) {
    val context = LocalContext.current.applicationContext
    val pictureInPicture = LocalVideoPictureInPicture.current
    val pipOwner = remember(controller, request) { Any() }
    val videoOnly = pictureInPicture?.videoOnly == true
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focusManager = LocalFocusManager.current
    LaunchedEffect(request) { focusManager.clearFocus(force = true) }
    BackHandler(enabled = !videoOnly) { controller.close() }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var wantsToPlay by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var progressError by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var compatibility by remember { mutableStateOf(false) }
    var transcoder by remember { mutableStateOf<VideoTranscoder?>(null) }
    var frameReady by remember { mutableStateOf(false) }
    var restartPosition by remember { mutableStateOf<Long?>(null) }
    val failedDecoders = remember { mutableSetOf<String>() }

    LaunchedEffect(controller, request, attempt) {
        val renderers = videoRenderers(context, failedDecoders)
        val active = ExoPlayer.Builder(context, renderers)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // 提前请求 HLS 分段，但不延迟首帧显示。
                    .setBufferDurationsMs(30_000, 60_000, 500, 1_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = active
        frameReady = false
        buffering = true
        playing = false
        var renderedFirstFrame = false
        var saveNeeded = false
        var lastSavedAt = 0L
        var localTranscoder: VideoTranscoder? = null
        var waitingForFrameSince: Long? = null
        var tracksChangedAt = android.os.SystemClock.elapsedRealtime()
        fun switchToCompatibility(): Boolean {
            if (compatibility) return false
            restartPosition = active.currentPosition.coerceAtLeast(0)
            compatibility = true
            active.pause()
            buffering = true
            waitingForFrameSince = null
            error = null
            attempt++
            return true
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === active) playing = isPlaying
            }
            override fun onRenderedFirstFrame() {
                if (player === active) {
                    renderedFirstFrame = true
                    frameReady = true
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (player !== active) return
                tracksChangedAt = android.os.SystemClock.elapsedRealtime()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player === active) {
                    wantsToPlay = playWhenReady
                    localTranscoder?.setPaused(!playWhenReady)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== active) return
                buffering = playbackState == Player.STATE_BUFFERING
                saveNeeded = true
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                ) saveNeeded = true
            }
            override fun onPlayerError(exception: PlaybackException) {
                if (player !== active) return
                buffering = false
                Log.w("OpenChatVideo", "Playback failed: ${exception.errorCodeName}")
                val failedDecoder = failedVideoDecoder(exception)
                val canRetryDecoder = failedDecoder != null && failedDecoders.size < 3 &&
                    failedDecoders.add(failedDecoder)
                if ((isUnsupportedVideoPlayback(exception) ||
                        exception.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) &&
                    switchToCompatibility()
                ) return
                if (canRetryDecoder) {
                    restartPosition = active.currentPosition.coerceAtLeast(0)
                    active.pause()
                    error = null
                    buffering = true
                    attempt++
                    return
                }
                active.pause()
                error = videoPlaybackError(exception)
            }
        }
        active.addListener(listener)
        active.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        wantsToPlay = active.playWhenReady
        suspend fun save(
            savedPosition: Long = active.currentPosition,
            savedDuration: Long = active.duration,
            ended: Boolean = active.playbackState == Player.STATE_ENDED,
            hasVideo: Boolean = active.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
        ) {
            if (!hasVideo || !renderedFirstFrame) return
            try {
                controller.repository.savePosition(
                    request.roomId, request.message, savedPosition, savedDuration, ended,
                )
                if (player === active) progressError = false
            } catch (_: IOException) {
                if (player === active) progressError = true
            }
        }
        try {
            val inspectedFile = inspectVideoFile(request.file)
            controller.repository.check(request.roomId, request.message)
            Log.d(
                "OpenChatVideo",
                "Local inspection: completed=${inspectedFile.completed}, video=${inspectedFile.videoTracks}, audio=${inspectedFile.audioTracks}",
            )
            val resumePosition = restartPosition
                ?: controller.repository.position(request.roomId, request.message)
            if (compatibility || !supportsVideoPlayback(context, request.file)) {
                compatibility = true
                localTranscoder = VideoTranscoder.create(context, request.file) {
                    controller.repository.check(request.roomId, request.message)
                }
                transcoder = localTranscoder
                val source = checkNotNull(localTranscoder)
                source.setPaused(!active.playWhenReady)
                duration = source.plan.durationMs
                val initialPosition = resumePosition.takeIf { it in 0 until duration } ?: 0L
                source.seekTo(initialPosition)
                active.setMediaSource(
                    HlsMediaSource.Factory(TranscodedVideoDataSource.Factory(source))
                        .setAllowChunklessPreparation(false)
                        .createMediaSource(MediaItem.fromUri(source.playlistUri)),
                    initialPosition,
                )
            } else {
                active.setMediaItem(MediaItem.fromUri(Uri.fromFile(request.file)), resumePosition)
            }
            active.prepare()
            while (true) {
                controller.repository.check(request.roomId, request.message)
                position = active.currentPosition.coerceAtLeast(0)
                duration = active.duration.takeIf { it > 0 } ?: localTranscoder?.plan?.durationMs ?: 0L
                val now = android.os.SystemClock.elapsedRealtime()
                // 轨道变更回调不能代表文件已全部就绪，应校验稳定后的 READY 状态。
                if (active.playbackState == Player.STATE_READY && now - tracksChangedAt >= 750 && error == null) {
                    videoTrackError(active.currentTracks, inspectedFile)?.let {
                        buffering = false
                        active.pause()
                        Log.w(
                            "OpenChatVideo",
                            "Video unavailable: playerVideo=${active.currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_VIDEO }}, " +
                                "selected=${active.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)}, systemVideo=${inspectedFile.videoTracks}",
                        )
                        if (!switchToCompatibility()) error = it
                    }
                }
                if (!renderedFirstFrame && active.isPlaying && error == null) {
                    val startedAt = waitingForFrameSince ?: now.also { waitingForFrameSince = it }
                    if (now - startedAt >= 10_000) {
                        active.pause()
                        Log.w("OpenChatVideo", "No video frame rendered after playback started")
                        if (!switchToCompatibility()) {
                            error = "视频画面未能输出，请重试播放"
                        }
                    }
                } else {
                    waitingForFrameSince = null
                }
                if (saveNeeded || now - lastSavedAt >= 1000) {
                    saveNeeded = false
                    lastSavedAt = now
                    save()
                }
                delay(250)
            }
        } catch (exception: CancellationException) {
            if (currentCoroutineContext().isActive && controller.request === request) controller.close()
            throw exception
        } catch (exception: IOException) {
            error = exception.message ?: "无法读取视频"
        } catch (_: RuntimeException) {
            Log.w("OpenChatVideo", "Playback initialization failed: VIDEO_PLAYBACK_RUNTIME_ERROR")
            error = "视频播放初始化失败，请重试"
        } finally {
            val finalPosition = active.currentPosition
            val finalDuration = active.duration
            val ended = active.playbackState == Player.STATE_ENDED
            val hadVideo = active.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            val cleanupTranscoder = localTranscoder
            localTranscoder?.cancel()
            active.pause()
            active.removeListener(listener)
            active.release()
            if (player === active) player = null
            if (transcoder === localTranscoder) transcoder = null
            localTranscoder = null
            videoCleanupScope.launch {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        save(finalPosition, finalDuration, ended, hadVideo)
                    }
                } finally {
                    withContext(NonCancellable) { cleanupTranscoder?.close() }
                }
            }
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(pictureInPicture, pipOwner) {
        pictureInPicture?.bind(
            owner = pipOwner,
            validate = {
                check(controller.request === request)
                controller.repository.check(request.roomId, request.message)
            },
            onClose = {
                player?.pause()
                controller.close()
            },
            onRestore = controller::restore,
        )
        onDispose { pictureInPicture?.unbind(pipOwner) }
    }
    SideEffect {
        pictureInPicture?.update(
            pipOwner,
            available = player != null && frameReady && error == null,
            wantsToPlay = wantsToPlay && player?.playbackState != Player.STATE_ENDED,
        )
    }
    val pane: @Composable (Modifier) -> Unit = { modifier ->
        VideoPlayerPane(
            player, controller, request, playing, wantsToPlay, buffering, compatibility,
            position, duration, error, progressError, landscape, videoOnly, modifier,
            onMinimize = {
                if (pictureInPicture?.supported == true) {
                    if (!pictureInPicture.enter()) Toast.makeText(
                        context,
                        if (!frameReady) "请等待视频画面就绪"
                        else "无法开启画中画，请检查系统画中画权限",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else controller.minimize()
            },
            onVideoBounds = { bounds, ratio -> pictureInPicture?.updateVideoBounds(pipOwner, bounds, ratio) },
            onRetry = {
                restartPosition = position
                error = null
                attempt++
            },
            onSeek = { target ->
                val time = target.coerceIn(0L, (duration - 1).coerceAtLeast(0L))
                player?.let {
                    transcoder?.seekTo(time)
                    it.seekTo(time)
                    position = time
                }
            },
        )
    }
    if (controller.floating && !videoOnly) {
        val density = LocalDensity.current
        var offset by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
            val width = minOf(240.dp, maxWidth)
            val height = minOf(width * 9 / 16, maxHeight)
            val maxX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
            val maxY = with(density) { (maxHeight - height).toPx().coerceAtLeast(0f) }
            pane(
                Modifier.offset {
                    IntOffset(offset.x.coerceIn(0f, maxX).roundToInt(), offset.y.coerceIn(0f, maxY).roundToInt())
                }.size(width, height).clip(RoundedCornerShape(6.dp))
                    .pointerInput(maxX, maxY) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offset = Offset((offset.x + drag.x).coerceIn(0f, maxX), (offset.y + drag.y).coerceIn(0f, maxY))
                        }
                    },
            )
        }
    } else {
        VideoPlaybackImmersiveMode(enabled = landscape && !videoOnly)
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                // 拦截黑边和空白控件区域的触摸，避免传递到播放器下方的聊天页面。
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
        ) {
            pane(
                Modifier.fillMaxSize()
                    .then(if (landscape || videoOnly) Modifier else Modifier.safeDrawingPadding()),
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerPane(
    player: ExoPlayer?,
    controller: VideoPlaybackController,
    request: VideoPlaybackRequest,
    playing: Boolean,
    wantsToPlay: Boolean,
    buffering: Boolean,
    compatibility: Boolean,
    position: Long,
    duration: Long,
    error: String?,
    progressError: Boolean,
    landscape: Boolean,
    videoOnly: Boolean,
    modifier: Modifier,
    onMinimize: () -> Unit,
    onVideoBounds: (android.graphics.Rect, Float) -> Unit,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val floating = controller.floating && !videoOnly
    val view = LocalView.current
    DisposableEffect(view, playing) {
        val previous = view.keepScreenOn
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = previous }
    }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        VideoPlaybackLayout(
            overlay = landscape && !floating,
            keepControlsVisible = error != null,
            videoOnly = videoOnly,
            compactRestore = if (floating) {
                {
                    ToolButton(
                        ImageVector.vectorResource(R.drawable.ic_video_expand),
                        "还原播放窗口",
                        size = 36.dp,
                        onClick = controller::restore,
                    )
                }
            } else null,
            modifier = modifier,
            header = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (request.message.body as? VideoMessageBody)?.extra?.fileName ?: "视频",
                        Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    ToolButton(
                        ImageVector.vectorResource(if (floating) R.drawable.ic_video_expand else R.drawable.ic_video_minimize),
                        if (floating) "还原播放窗口"
                        else if (LocalVideoPictureInPicture.current?.supported == true) "画中画播放"
                        else "悬浮播放",
                        size = 36.dp,
                        onClick = { if (floating) controller.restore() else onMinimize() },
                    )
                    ToolButton(Icons.Default.Close, "关闭视频", size = 36.dp, onClick = controller::close)
                }
            },
            controls = { onInteraction ->
                Column {
                    key(player) {
                        VideoPlaybackControls(
                            wantsToPlay = wantsToPlay,
                            canPlay = player != null || error != null,
                            failed = error != null,
                            position = position,
                            duration = duration,
                            floating = floating,
                            onPlayPause = {
                                onInteraction()
                                if (error != null) onRetry() else if (wantsToPlay) player?.pause() else {
                                    if (player?.playbackState == Player.STATE_ENDED) onSeek(0)
                                    player?.play()
                                }
                            },
                            onSeek = {
                                onInteraction()
                                onSeek(it)
                            },
                        )
                    }
                    if (progressError && !floating) Text(
                        "播放进度暂未保存",
                        Modifier.padding(horizontal = 8.dp), fontSize = 11.sp,
                    )
                    if (!floating) VideoPlaybackActions()
                }
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VideoPlaybackContent(
                    player = player,
                    floating = floating,
                    extra = (request.message.body as? VideoMessageBody)?.extra,
                    zoomEnabled = !floating && !videoOnly,
                    onVideoBounds = onVideoBounds,
                )
                if (!floating && !videoOnly && buffering && wantsToPlay && error == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                    if (compatibility && !videoOnly) Text(
                        "正在转码…", Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                    )
                }
                if (error != null && !floating && !videoOnly) Column(
                    Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, fontSize = 12.sp, maxLines = 3)
                    ToolButton(Icons.Default.Refresh, "重试播放", onClick = onRetry)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun videoTrackError(tracks: Tracks, inspectedFile: VideoFileInspection): String? {
    if (tracks.isEmpty) return null
    val videos = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (videos.isEmpty()) return if (inspectedFile.videoTracks > 0) {
        "本地文件含视频轨道，但播放器未能解析，需要兼容转码"
    } else {
        "未能识别视频轨道，可能是封装或编码不兼容"
    }
    // 已选轨道可能超过设备声明的能力，但仍有可能成功解码。
    if (tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) return null
    if (!tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
        val mimeType = videos.firstOrNull { it.length > 0 }?.getTrackFormat(0)?.sampleMimeType
        val codec = when (mimeType) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265/HEVC"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/av01" -> "AV1"
            else -> "未知编码"
        }
        return "当前设备不支持此视频编码（$codec）"
    }
    return "视频轨道未能启用，请重试播放"
}

private fun videoPlaybackError(exception: PlaybackException): String {
    val detail = when (exception.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "视频解码器初始化失败"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
            "无法获取设备解码器"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "当前设备不支持此视频编码"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "视频规格超出当前设备解码能力"
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "视频解码失败"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "暂不支持此视频封装格式"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "本地视频文件不存在"
        else -> "视频播放失败"
    }
    return "$detail（${exception.errorCodeName}）"
}
