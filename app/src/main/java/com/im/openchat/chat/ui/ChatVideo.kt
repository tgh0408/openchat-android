package com.im.openchat.chat.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.chat.data.Message
import com.im.openchat.R
import com.im.openchat.chat.data.VideoMessageBody
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@Composable
internal fun ChatVideo(roomId: String, message: Message) {
    val playback = LocalVideoPlayback.current
    val remoteAllowed = LocalMediaRemoteAllowed.current
    val videos = playback?.repository
    val extra = (message.body as? VideoMessageBody)?.extra
    val ratio = ((extra?.thumbWidth ?: 16).toFloat() / (extra?.thumbHeight ?: 9).coerceAtLeast(1))
        .coerceIn(0.45f, 2.4f)
    val scope = rememberCoroutineScope()
    var poster by remember(message.id, message.body, videos) { mutableStateOf<Bitmap?>(null) }
    var posterFailed by remember(message.id, message.body, videos) { mutableStateOf(false) }
    var posterAttempt by remember(message.id, videos) { mutableIntStateOf(0) }
    var localFile by remember(message.id, message.body, videos) { mutableStateOf<File?>(null) }
    var error by remember(message.id, message.body, videos) { mutableStateOf<String?>(null) }
    var download by remember(message.id, message.body, videos) { mutableStateOf<Job?>(null) }
    val progress = remember(message.id, message.body, videos) { MutableStateFlow(0L to (extra?.size ?: 0L)) }
    val bytes by progress.collectAsState()
    val downloadIcon = ImageVector.vectorResource(R.drawable.ic_video_download)
    var downloading by remember(message.id, message.body, videos) { mutableStateOf(false) }
    var checking by remember(message.id, message.body, videos) { mutableStateOf(videos != null) }

    LaunchedEffect(roomId, message.id, message.body, videos, posterAttempt) {
        if (videos == null) return@LaunchedEffect
        posterFailed = false
        try {
            poster = videos.poster(roomId, message)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            posterFailed = true
        }
    }
    LaunchedEffect(roomId, message.id, message.body, videos, downloading) {
        if (downloading) return@LaunchedEffect
        checking = videos != null
        try {
            localFile = videos?.cached(roomId, message)
            if (localFile == null && videos != null) {
                progress.value = videos.partialSize(roomId, message) to (extra?.size ?: 0L)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            error = exception.message
        } finally {
            checking = false
        }
    }
    DisposableEffect(roomId, message.id, message.body, videos) {
        onDispose { download?.cancel() }
    }
    LaunchedEffect(remoteAllowed) { if (!remoteAllowed) download?.cancel() }
    val activate = {
        if (!downloading && !checking && videos != null && (remoteAllowed || localFile != null)) {
            error = null
            downloading = true
            download = scope.launch {
                try {
                    val file = videos.cached(roomId, message)
                    if (file != null) {
                        localFile = file
                        downloading = false
                        checking = true
                        playback.open(roomId, message, file)
                    } else {
                        localFile = null
                        // 首次操作下载视频，之后的操作打开播放窗口。
                        localFile = videos.download(roomId, message) { received, total ->
                            progress.value = received to total
                        }
                        downloading = false
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    error = exception.message ?: "视频下载失败"
                } finally {
                    downloading = false
                    checking = false
                }
            }
        }
    }
    Box(
        Modifier
            .sizeIn(maxWidth = 216.dp, maxHeight = 288.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
            .clickable(enabled = videos != null && !downloading && !checking && (remoteAllowed || localFile != null),
                onClick = { activate() }),
        contentAlignment = Alignment.Center,
    ) {
        poster?.let {
            Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                    } else if (downloading) {
                        val total = bytes.second
                        if (total > 0) CircularProgressIndicator(
                            progress = { (bytes.first.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.size(48.dp), color = Color.White, strokeWidth = 3.dp,
                        ) else CircularProgressIndicator(Modifier.size(48.dp), color = Color.White, strokeWidth = 3.dp)
                        ToolButton(ImageVector.vectorResource(R.drawable.ic_video_pause), "暂停下载", size = 36.dp,
                            onClick = { download?.cancel() })
                    } else {
                        ToolButton(
                            when { error != null -> Icons.Default.Refresh; localFile != null -> Icons.Default.PlayArrow; else -> downloadIcon },
                            error ?: when {
                                videos == null -> "视频暂不可用"
                                localFile != null -> "播放视频"
                                bytes.first > 0 -> "继续下载"
                                else -> "下载视频"
                            },
                            enabled = videos != null && (remoteAllowed || localFile != null), onClick = { activate() },
                        )
                    }
                }
            }
            if (posterFailed && !downloading) {
                ToolButton(
                    Icons.Default.Refresh, "重试封面",
                    modifier = Modifier.align(Alignment.TopEnd), size = 32.dp,
                    onClick = { posterAttempt++ },
                )
            }
        }
        Text(
            videoTime(extra?.duration ?: 0),
            Modifier.align(Alignment.BottomEnd).padding(5.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp),
            color = Color.White, fontSize = 12.sp,
        )
        if (downloading) Text(
            "${if (bytes.second > 0) bytes.first * 100 / bytes.second else 0}%",
            Modifier.align(Alignment.BottomStart).padding(5.dp),
            color = Color.White, fontSize = 12.sp,
        )
        if (!downloading && !checking && localFile == null && bytes.first > 0 && error == null) Text(
            "已暂停 ${if (bytes.second > 0) bytes.first * 100 / bytes.second else 0}%",
            Modifier.align(Alignment.BottomStart).padding(5.dp),
            color = Color.White, fontSize = 12.sp,
        )
        if (error != null || videos == null) Text(
            when {
                videos == null -> "不可用"
                localFile != null -> "检查失败"
                else -> "下载失败"
            },
            Modifier.align(Alignment.BottomStart).padding(5.dp)
                .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 3.dp),
            color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp, maxLines = 1,
        )
    }
}

internal fun videoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
