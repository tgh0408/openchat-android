package com.im.openchat.chat.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.MessageImageRepository
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import java.io.IOException

private data class EmojiLoad(val drawable: Drawable? = null, val error: String? = null)

/** 对齐 PC 的固定方形、等比完整展示和失败重试，不给透明表情添加气泡背景。 */
@Composable
internal fun ChatEmoji(roomId: String, message: Message, repository: MessageImageRepository?) {
    var attempt by remember(message.id, message.emoji) { mutableIntStateOf(0) }
    val state = remember(roomId, message.id, message.emoji, repository, attempt) { mutableStateOf(EmojiLoad()) }
    LaunchedEffect(state) {
        state.value = try {
            if (message.emoji?.url == null) throw IOException("表情包不可用")
            if (repository == null) throw IOException("表情包暂不可用")
            EmojiLoad(drawable = repository.loadEmoji(roomId, message))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            EmojiLoad(error = exception.message ?: "表情包加载失败")
        }
    }
    Box(Modifier.size(144.dp).testTag("chat_emoji:${message.id}"), contentAlignment = Alignment.Center) {
        val loaded = state.value
        val drawable = loaded.drawable
        if (drawable != null) {
            EmojiImage(drawable, message.emoji?.description ?: "表情包")
        } else {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loaded.error == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        loaded.error, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.emoji?.url != null) {
                        ToolButton(Icons.Default.Refresh, "重新加载表情包", size = 36.dp, onClick = { attempt++ })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiImage(drawable: Drawable, description: String) {
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { view ->
            (view.drawable as? Animatable)?.stop()
            view.setImageDrawable(null)
        },
        update = { view ->
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            view.contentDescription = description
        },
    )
    DisposableEffect(drawable, lifecycle) {
        val animation = drawable as? Animatable
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> animation?.start()
                Lifecycle.Event.ON_STOP -> animation?.stop()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) animation?.start()
        onDispose {
            lifecycle?.removeObserver(observer)
            animation?.stop()
        }
    }
}
