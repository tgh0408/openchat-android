package com.im.openchat.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.im.openchat.chat.data.Message
import com.im.openchat.common.ui.ToolButton

/**
 * Fixed-width send state placed before an outgoing message bubble.
 *
 * The empty state keeps the bubble position stable after a successful send.
 */
@Composable
internal fun ChatMessageSendStatus(
    message: Message,
    enabled: Boolean,
    onRetry: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.width(32.dp)
            .testTag("message_send_status:${message.id}"),
        contentAlignment = Alignment.Center,
    ) {
        when (message.status) {
            "pending" -> CircularProgressIndicator(
                Modifier.size(16.dp).testTag("message_send_progress:${message.id}"),
                strokeWidth = 2.dp,
            )
            "failed" -> CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.error,
            ) {
                ToolButton(
                    Icons.Default.Refresh,
                    "\u91cd\u8bd5\u53d1\u9001\u6d88\u606f",
                    size = 32.dp,
                    enabled = enabled,
                    onClick = { onRetry(message) },
                )
            }
        }
    }
}
