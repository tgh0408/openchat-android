package com.im.openchat.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R

internal const val CHAT_MORE_PANEL_DURATION_MS = 260

@Composable
internal fun ChatMorePanelTransition(
    visibleState: MutableTransitionState<Boolean>,
    panelHeight: Dp,
    imeBottom: Int,
    onAction: (String) -> Unit,
    attachmentsEnabled: Boolean = true,
) {
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = Modifier.fillMaxWidth().testTag("chat_more_transition").clipToBounds()
            .layout { measurable, constraints ->
                val panel = measurable.measure(constraints)
                // 父布局已为输入法预留空间，面板退出时共用该空间。
                layout(panel.width, (panel.height - imeBottom).coerceAtLeast(0)) {
                    panel.placeRelative(0, 0)
                }
            },
                // 底部对齐的列布局若从顶部改变高度，会导致整个面板滑动。
        enter = expandVertically(
            animationSpec = tween(CHAT_MORE_PANEL_DURATION_MS, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ),
        exit = shrinkVertically(
            animationSpec = tween(CHAT_MORE_PANEL_DURATION_MS, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ChatMorePanel(
                onAction = {
                    if (visibleState.isIdle && visibleState.currentState && visibleState.targetState) onAction(it)
                },
                modifier = Modifier.fillMaxWidth().height(panelHeight),
                enabled = visibleState.isIdle && visibleState.currentState && visibleState.targetState,
                attachmentsEnabled = attachmentsEnabled,
            )
        }
    }
}

@Composable
internal fun ChatMorePanel(
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    attachmentsEnabled: Boolean = true,
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val actions = listOf(
        "相册" to ImageVector.vectorResource(R.drawable.ic_chat_album),
        "拍摄" to ImageVector.vectorResource(R.drawable.ic_chat_camera),
        "视频通话" to ImageVector.vectorResource(R.drawable.ic_chat_video_call),
        "位置" to Icons.Default.LocationOn,
        "红包" to ImageVector.vectorResource(R.drawable.ic_chat_red_packet),
        "礼物" to ImageVector.vectorResource(R.drawable.ic_chat_gift),
        "转账" to ImageVector.vectorResource(R.drawable.ic_chat_transfer),
        "文件" to ImageVector.vectorResource(R.drawable.ic_chat_file),
    )
    Column(
        modifier.testTag("chat_more_panel")
            .background(if (light) Color(0xFFF6F6F6) else MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState(), enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        actions.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, icon) ->
                    val actionEnabled = enabled &&
                        (attachmentsEnabled || label !in setOf("相册", "拍摄", "文件"))
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = actionEnabled, role = Role.Button, onClick = { onAction(label) })
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (light) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon, contentDescription = null, modifier = Modifier.size(30.dp),
                                tint = (if (light) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.onSurface)
                                    .copy(alpha = if (actionEnabled) 1f else 0.38f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            label, Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (actionEnabled) 1f else 0.38f),
                            fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
