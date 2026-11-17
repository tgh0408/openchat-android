package com.im.openchat.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.im.openchat.R
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.MessageImageRepository
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 图片加载的单一结果，重试时重置状态，旧消息结果不复用到新图片。 */
private data class ImageLoad(val bitmap: ImageBitmap? = null, val error: String? = null)

/**
 * 图片按可见项懒加载，加载后按原图比例限制最大宽高，不保留背景留白。
 * @param roomId 当前真实房间号。
 * @param message 带图片元数据的真实缓存消息。
 * @param repository 当前授权的图片仓库，优先本地读取。
 * 无返回值；进入组合树后读取缓存或下载，失败提供重试，点击成功图片打开预览。
 */
@Composable
internal fun ChatImage(
    roomId: String,
    message: Message,
    repository: MessageImageRepository?,
    pending: PendingImageUpload? = null,
    onPreview: (Message) -> Unit = {},
) {
    var localThumbnail by remember(roomId, message.clientMsgId, repository) {
        mutableStateOf(pending?.draft?.thumbnail)
    }
    if (pending != null) {
        val bitmap = pending.draft?.thumbnail?.asImageBitmap()
        if (bitmap != null) {
            Image(
                bitmap, pending.draft?.name ?: "图片",
                Modifier.sizeIn(maxWidth = 216.dp, maxHeight = 288.dp)
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height).testTag("image_upload_thumbnail"),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                Modifier.width(216.dp).height(162.dp).testTag("image_upload_thumbnail"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_chat_album), "正在准备图片",
                    Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    var attempt by remember(message.id, message.image) { mutableIntStateOf(0) }
    val loaded by rememberImageLoad(roomId, message, repository, attempt, false)
    val bitmap = loaded.bitmap ?: localThumbnail?.asImageBitmap()
    if (loaded.bitmap != null && localThumbnail != null) SideEffect { localThumbnail = null }
    if (bitmap != null) {
        Image(
            bitmap, message.image?.name ?: "图片",
            Modifier.sizeIn(maxWidth = 216.dp, maxHeight = 288.dp)
                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .testTag("image_message_bitmap")
                .clickable { onPreview(message) },
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            Modifier.width(216.dp).heightIn(min = 162.dp).testTag("image_message_placeholder"),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loaded.error == null) {
                    if (message.outgoing && message.status == "pending") {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_chat_album), "正在加载图片",
                            Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Text(loaded.error.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ToolButton(
                        Icons.Default.Refresh, "重新加载图片", size = 36.dp,
                        // 重试只重新读取这张图片，已存在的本地缓存不会重复下载。
                        onClick = { attempt++ },
                    )
                }
            }
        }
    }
}

/**
 * 展示会话图片的全屏预览，原比例时左右翻页，放大时拖动图片。
 * @param roomId 当前真实房间号。
 * @param messages 按会话顺序排列且仍有效的图片消息。
 * @param initialMessageId 打开预览时选中的真实消息 ID。
 * @param repository 当前授权的图片仓库。
 * @param onDismiss 返回或单击预览时触发，无返回值。
 * 无返回值；读取更高分辨率位图，手势只修改当前预览状态，离开时取消效果。
 */
@Composable
internal fun ImagePreview(
    roomId: String,
    messages: List<Message>,
    initialMessageId: String,
    repository: MessageImageRepository?,
    onDismiss: () -> Unit,
) {
    if (messages.isEmpty()) return
    val pager = rememberPagerState(
        initialPage = messages.indexOfFirst { it.id == initialMessageId }.coerceAtLeast(0),
        pageCount = { messages.size },
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).testTag("image_preview"),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().testTag("image_preview_pager"),
                key = { messages[it].id },
            ) { page ->
                ImagePreviewPage(
                    roomId, messages[page], repository,
                    active = page == pager.currentPage,
                    onDismiss = onDismiss,
                )
            }
            Text(
                "${pager.currentPage.coerceAtMost(messages.lastIndex) + 1} / ${messages.size}",
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                    .testTag("image_preview_position"),
                color = Color.White, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun ImagePreviewPage(
    roomId: String,
    message: Message,
    repository: MessageImageRepository?,
    active: Boolean,
    onDismiss: () -> Unit,
) {
    var attempt by remember(roomId, message.id, message.image, repository) { mutableIntStateOf(0) }
    val loaded by rememberImageLoad(roomId, message, repository, attempt, true, enabled = active)
    val thumbnail by rememberImageLoad(roomId, message, repository, 0, false, enabled = false)
    var scale by remember(roomId, message.id, message.image, repository, active) { mutableFloatStateOf(1f) }
    var offset by remember(roomId, message.id, message.image, repository, active) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier.fillMaxSize().clipToBounds().testTag("image_preview_page_${message.id}")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "关闭图片预览",
                onClick = onDismiss,
            )
            // Let the pager own one-finger drags at fit scale; pinches and zoomed pans stay here.
            .transformable(state = transform, canPan = { scale > 1f }, enabled = active),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = loaded.bitmap ?: thumbnail.bitmap
        if (bitmap != null) Image(
            bitmap, message.image?.name ?: "图片",
            Modifier.fillMaxSize().testTag("image_preview_bitmap_${message.id}").graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x.coerceIn(-size.width * (scale - 1f) / 2, size.width * (scale - 1f) / 2)
                translationY = offset.y.coerceIn(-size.height * (scale - 1f) / 2, size.height * (scale - 1f) / 2)
            },
            contentScale = ContentScale.Fit,
        )
        when {
            loaded.error != null && active -> Column(
                Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(loaded.error.orEmpty(), color = Color.White)
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    ToolButton(Icons.Default.Refresh, "重试图片预览", onClick = { attempt++ })
                }
            }
            bitmap == null -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * 为气泡和预览共用可取消加载状态，旧消息或旧授权的结果不会显示到新资源上。
 * @param roomId 当前真实房间号。
 * @param message 待读取的图片消息。
 * @param repository 本次授权的图片仓库。
 * @param attempt 显式重试次数。
 * @param preview 是否使用预览分辨率。
 * @return 位图或错误状态；异步读缓存、必要时下载，取消继续传播。
 */
@Composable
private fun rememberImageLoad(
    roomId: String,
    message: Message,
    repository: MessageImageRepository?,
    attempt: Int,
    preview: Boolean,
    enabled: Boolean = true,
): State<ImageLoad> {
    // Keep message identity checks, but seed the first frame from the same authorized resource.
    val state = remember(roomId, message.id, message.image, repository, attempt, preview) {
        mutableStateOf(try {
            ImageLoad(bitmap = repository?.peek(roomId, message, preview)?.asImageBitmap())
        } catch (_: CancellationException) {
            // No coroutine is running here; an invalid authorization must not expose cached pixels.
            ImageLoad()
        } catch (exception: IOException) {
            ImageLoad(error = exception.message ?: "图片加载失败")
        })
    }
    // 加载副作用随可见项销毁，失败仅更新当前图片错误，不影响历史分页。
    LaunchedEffect(state, enabled) {
        if (!enabled || state.value.bitmap != null) return@LaunchedEffect
        state.value = try {
            if (repository == null) throw IOException("图片暂不可用")
            ImageLoad(bitmap = repository.load(roomId, message, preview).asImageBitmap())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            ImageLoad(error = exception.message ?: "图片加载失败")
        }
    }
    return state
}
