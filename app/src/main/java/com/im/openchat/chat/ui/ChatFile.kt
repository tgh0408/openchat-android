package com.im.openchat.chat.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.im.openchat.R
import com.im.openchat.chat.data.FileMessageBody
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.MessageFileRepository
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

internal val LocalMessageFiles = staticCompositionLocalOf<MessageFileRepository?> { null }
internal val LocalMediaRemoteAllowed = staticCompositionLocalOf { true }

/**
 * 在外层消息气泡内展示文件名、字节大小及原生矢量类型图标。
 * @param body 统一协议入口提供的文件正文。
 * 无返回值，仅渲染 UI；名称缺失或含路径、控制字符时显示不可用占位。
 */
@Composable
internal fun ChatFile(body: FileMessageBody, roomId: String? = null, message: Message? = null) {
    val files = LocalMessageFiles.current
    val remoteAllowed = LocalMediaRemoteAllowed.current
    val scope = rememberCoroutineScope()
    var task by remember(roomId, message, files) { mutableStateOf<Job?>(null) }
    var downloading by remember(roomId, message, files) { mutableStateOf(false) }
    var checking by remember(roomId, message, files) { mutableStateOf(files != null && message != null) }
    var complete by remember(roomId, message, files) { mutableStateOf(false) }
    var error by remember(roomId, message, files) { mutableStateOf<String?>(null) }
    val progress = remember(roomId, message, files) { MutableStateFlow(0L to (body.extra?.size ?: 0L)) }
    val bytes by progress.collectAsState()
    LaunchedEffect(roomId, message, files, downloading) {
        if (files == null || roomId == null || message == null || downloading) return@LaunchedEffect
        checking = true
        try {
            val status = files.status(roomId, message)
            complete = status.first
            progress.value = status.second to (body.extra?.size ?: 0L)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            error = exception.message ?: "文件状态读取失败"
        } finally {
            checking = false
        }
    }
    DisposableEffect(roomId, message, files) { onDispose { task?.cancel() } }
    LaunchedEffect(remoteAllowed) { if (!remoteAllowed) task?.cancel() }
    val name = body.extra?.fileName?.trim()?.takeIf {
        it.isNotEmpty() && it.none { character ->
            character.isISOControl() || character == '/' || character == '\\'
        }
    } ?: "文件不可用"
    ChatAttachmentLayout(
        name = name, size = formatChatFileSize(body.extra?.size), icon = chatFileIcon(name),
        tag = "chat_file:${message?.id.orEmpty()}",
        status = if (files != null && message != null && roomId != null) {
            when {
                error != null -> "下载失败"
                complete -> "已下载"
                downloading -> "${if (bytes.second > 0) bytes.first * 100 / bytes.second else 0}%"
                bytes.first > 0 -> "已暂停 ${if (bytes.second > 0) bytes.first * 100 / bytes.second else 0}%"
                else -> "未下载"
            }
        } else "",
        statusColor = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        if (files != null && message != null && roomId != null && !complete) {
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)) {
                if (checking) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else ToolButton(
                    when {
                        downloading -> ImageVector.vectorResource(R.drawable.ic_video_pause)
                        error != null -> Icons.Default.Refresh
                        else -> ImageVector.vectorResource(R.drawable.ic_video_download)
                    },
                    when {
                        downloading -> "暂停下载"
                        error != null -> "重试下载"
                        bytes.first > 0 -> "继续下载"
                        else -> "下载文件"
                    },
                    size = 40.dp, enabled = remoteAllowed,
                    onClick = {
                        if (downloading) task?.cancel() else {
                            downloading = true
                            error = null
                            task = scope.launch {
                                try {
                                    files.download(roomId, message) { received, total ->
                                        progress.value = received to total
                                    }
                                    complete = true
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: IOException) {
                                    error = exception.message ?: "文件下载失败，请重试"
                                } finally {
                                    downloading = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 按不区分大小写的扩展名选择图标，仅影响展示，不推断 MIME 或可执行权限。
 * @param name 文件显示名称。
 * @return 对应类型的矢量资源，未知扩展名使用灰色问号文件。
 */
@DrawableRes
internal fun chatFileIcon(name: String): Int = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "txt", "md", "log" -> R.drawable.ic_chat_file_text
    "doc", "docx", "rtf", "odt" -> R.drawable.ic_chat_file_word
    "xls", "xlsx", "csv", "ods" -> R.drawable.ic_chat_file_sheet
    "ppt", "pptx", "odp" -> R.drawable.ic_chat_file_presentation
    "pdf" -> R.drawable.ic_chat_file_pdf
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz" -> R.drawable.ic_chat_file_archive
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg" -> R.drawable.ic_chat_file_image
    "mp4", "mkv", "avi", "mov", "webm", "wmv", "m4v", "3gp" -> R.drawable.ic_chat_file_video
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma" -> R.drawable.ic_chat_file_audio
    else -> R.drawable.ic_chat_file_unknown
}

/**
 * 将非负字节数按 1024 进位显示，使用稳定小数点，不将缺失或负数误报为零。
 * @param bytes 正文内的文件字节大小，可为空。
 * @return 带单位的大小文本或未知占位，无副作用。
 */
internal fun formatChatFileSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
