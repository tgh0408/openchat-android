package com.im.openchat.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.im.openchat.common.ui.ToolButton

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ChatImageUploadStatus(
    busy: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onCancel: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    Column(
        Modifier.width(32.dp).testTag("image_send_status")
            .semantics { stateDescription = error ?: "正在发送图片" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(if (onCancel != null) "取消图片发送" else "正在发送图片") } },
                state = rememberTooltipState(),
            ) {
                Box(
                    Modifier.size(32.dp)
                        .semantics { contentDescription = if (onCancel != null) "取消图片发送" else "正在发送图片" }
                        .then(if (onCancel != null) Modifier.clickable(enabled = enabled, role = Role.Button) {
                            confirmCancel = true
                        } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp).testTag("image_send_progress"), strokeWidth = 2.dp,
                    )
                }
            }
        } else if (error != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                ToolButton(Icons.Default.Refresh, "重试图片发送", size = 32.dp, enabled = enabled, onClick = onRetry)
            }
            if (onCancel != null) ToolButton(
                Icons.Default.Close, "取消图片发送", size = 32.dp, enabled = enabled,
                onClick = { confirmCancel = true },
            )
        }
    }
    if (confirmCancel && onCancel != null) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("取消图片发送？") },
        text = { Text("取消本次选择中尚未入队的图片和视频，已入队消息不受影响。") },
        confirmButton = {
            TextButton(enabled = enabled, onClick = { confirmCancel = false; onCancel() }) { Text("取消发送") }
        },
        dismissButton = {
            TextButton(onClick = { confirmCancel = false }) { Text("继续发送") }
        },
    )
}
