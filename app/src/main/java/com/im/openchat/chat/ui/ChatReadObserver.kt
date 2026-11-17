package com.im.openchat.chat.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.canConfirmRead
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

internal class ChatReadObserver {
    private var viewport by mutableStateOf<Rect?>(null)
    private var messageBounds by mutableStateOf<Pair<String, Rect>?>(null)

    val viewportModifier: Modifier = Modifier.onGloballyPositioned { viewport = it.boundsInWindow() }

    fun messageModifier(id: String): Modifier = Modifier.onGloballyPositioned {
        messageBounds = id to it.boundsInWindow()
    }

    fun isVisible(id: String): Boolean {
        val area = viewport ?: return false
        val measured = messageBounds?.takeIf { it.first == id }?.second ?: return false
        return area.width > 0 && area.height > 0 && measured.width > 0 && measured.height > 0 &&
            measured.overlaps(area)
    }
}

/** 观察已测量的消息正文，而不只是惰性列表行或请求滚动到的位置。 */
@Composable
internal fun rememberChatReadObserver(
    roomId: String,
    messages: List<Message>,
    list: LazyListState,
    enabled: Boolean,
    foreground: Boolean,
    onRead: (String) -> Unit,
): Pair<ChatReadObserver, String?> {
    val observer = remember(roomId) { ChatReadObserver() }
    val candidate = messages.lastOrNull { it.canConfirmRead }
    val currentCandidate by rememberUpdatedState(candidate)
    val currentMessages by rememberUpdatedState(messages)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnRead by rememberUpdatedState(onRead)
    val currentForeground by rememberUpdatedState(foreground)
    LaunchedEffect(observer, list) {
        snapshotFlow {
            val id = currentCandidate?.id
            if (currentEnabled && currentForeground && id != null && !list.canScrollBackward &&
                list.layoutInfo.totalItemsCount == currentMessages.size &&
                list.layoutInfo.visibleItemsInfo.any { it.key == currentCandidate?.chatItemKey } && observer.isVisible(id)
            ) id else null
        }.distinctUntilChanged().collect { id ->
            if (id != null) currentOnRead(id)
        }
    }
    return observer to candidate?.id
}
