package com.im.openchat.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** 长按会话的纯展示菜单；选项仅关闭弹窗，不修改会话或发起请求。 */
@Composable
internal fun ConversationContextMenu(anchorWindow: Offset, onDismiss: () -> Unit) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(anchorWindow, margin) {
        ConversationMenuPositionProvider(anchorWindow, margin)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .testTag("conversation_context_menu")
                .widthIn(min = 152.dp, max = 240.dp)
                .width(IntrinsicSize.Max),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf("标为已读", "置顶该聊天", "不显示该聊天", "删除该聊天").forEach { action ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(action, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

private class ConversationMenuPositionProvider(
    private val anchorWindow: Offset,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorX = anchorWindow.x.roundToInt()
        val anchorY = anchorWindow.y.roundToInt()
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorX
        } else {
            anchorX - popupContentSize.width
        }
        // 下方不足时向上展开，并保留屏幕边距，避免末尾会话的菜单越界。
        val preferredY = if (anchorY + popupContentSize.height <= windowSize.height - margin) {
            anchorY
        } else {
            anchorY - popupContentSize.height
        }
        return IntOffset(
            preferredX.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            preferredY.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
        )
    }
}
