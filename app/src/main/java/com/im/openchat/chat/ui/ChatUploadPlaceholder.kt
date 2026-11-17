package com.im.openchat.chat.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.im.openchat.R
import com.im.openchat.chat.data.MessageType
import com.im.openchat.chat.data.TemporaryMessageBody
import com.im.openchat.chat.data.Message
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import java.io.IOException

internal enum class UploadPlaceholderTemplate(
    val description: String,
) {
    FILE_UPLOADING("文件上传中"),
    VIDEO_UPLOADING("视频上传中"),
    FILE_CANCELLED("文件已取消发送"),
    VIDEO_CANCELLED("视频已取消发送"),
    UNAVAILABLE("上传占位信息不可用"),
}

internal data class UploadPlaceholderContent(
    val name: String,
    val size: String,
    @param:DrawableRes val icon: Int,
)

internal fun uploadPlaceholderContent(body: TemporaryMessageBody): UploadPlaceholderContent {
    val extra = body.extra
    if (extra == null || uploadPlaceholderTemplate(body) == UploadPlaceholderTemplate.UNAVAILABLE) {
        return UploadPlaceholderContent("附件信息不可用", "大小未知", R.drawable.ic_chat_file_unknown)
    }
    val name = extra.fileName.trim().takeIf {
        it.isNotEmpty() && it.none { character ->
            character.isISOControl() || character == '/' || character == '\\'
        }
    } ?: if (extra.targetType == MessageType.VIDEO) "视频" else "文件"
    return UploadPlaceholderContent(
        name, formatChatFileSize(extra.size),
        if (extra.targetType == MessageType.VIDEO) R.drawable.ic_chat_file_video else chatFileIcon(name),
    )
}

internal fun uploadPlaceholderTemplate(body: TemporaryMessageBody): UploadPlaceholderTemplate {
    val extra = body.extra ?: return UploadPlaceholderTemplate.UNAVAILABLE
    return when (extra.targetType) {
        MessageType.FILE -> if (extra.cancelled) {
            UploadPlaceholderTemplate.FILE_CANCELLED
        } else UploadPlaceholderTemplate.FILE_UPLOADING
        MessageType.VIDEO -> if (extra.cancelled) {
            UploadPlaceholderTemplate.VIDEO_CANCELLED
        } else UploadPlaceholderTemplate.VIDEO_UPLOADING
        else -> UploadPlaceholderTemplate.UNAVAILABLE
    }
}

@Composable
internal fun ChatUploadPlaceholder(
    body: TemporaryMessageBody, messageId: String, roomId: String? = null, message: Message? = null,
) {
    val template = uploadPlaceholderTemplate(body)
    val content = uploadPlaceholderContent(body)
    val uploads = LocalMediaUploads.current
    val remoteAllowed = LocalMediaRemoteAllowed.current
    var available by remember(roomId, messageId, uploads) { mutableStateOf(false) }
    var cancelRequested by remember(roomId, messageId, uploads) { mutableStateOf(false) }
    var confirmCancel by remember(roomId, messageId) { mutableStateOf(false) }
    val state = if (roomId != null && message != null) uploads?.state(roomId, message) else null
    LaunchedEffect(roomId, message, uploads, state?.label) {
        if (roomId != null && message != null && uploads != null) {
            try {
                available = uploads.repository.available(roomId, message)
                cancelRequested = uploads.repository.cancellationRequested(roomId, message)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                available = false
            }
        } else available = false
    }
    val controllable = available && template in setOf(
        UploadPlaceholderTemplate.FILE_UPLOADING, UploadPlaceholderTemplate.VIDEO_UPLOADING,
    )
    val status = if (controllable) {
        if (cancelRequested && state?.active != true) "取消待确认"
        else if (state == null) "已暂停"
        else state.label + if (state.total > 0) {
            " ${(state.bytes.coerceIn(0L, state.total).toDouble() / state.total * 100).toInt()}%"
        } else ""
    } else template.description
    ChatAttachmentLayout(
        name = content.name, size = content.size, icon = content.icon, status = status,
        tag = "chat_upload:$messageId", iconTag = "chat_upload_template:${template.name}",
    ) {
        if (controllable && roomId != null && message != null && uploads != null) {
            UploadPlaceholderAction(
                if (state?.active == true) ImageVector.vectorResource(R.drawable.ic_video_pause) else Icons.Default.Refresh,
                if (state?.active == true) "暂停上传" else "继续发送",
                enabled = remoteAllowed && !cancelRequested,
                onClick = {
                    if (state?.active == true) uploads.pause(roomId, message)
                    else message.clientMsgId?.let { uploads.send(roomId, it) }
                },
            )
            UploadPlaceholderAction(Icons.Default.Close, "取消发送", enabled = remoteAllowed,
                onClick = { confirmCancel = true })
        }
    }
    if (confirmCancel && roomId != null && message != null && uploads != null) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("取消发送？") },
        confirmButton = {
            TextButton(onClick = {
                confirmCancel = false
                uploads.cancel(roomId, message)
            }) { Text("取消发送") }
        },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("继续保留") } },
    )
}

@Composable
private fun UploadPlaceholderAction(
    icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
) {
    Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)) {
        ToolButton(icon, label, size = 40.dp, enabled = enabled, onClick = onClick)
    }
}
