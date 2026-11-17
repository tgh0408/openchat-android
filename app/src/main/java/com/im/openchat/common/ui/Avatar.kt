package com.im.openchat.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.im.openchat.R
import com.im.openchat.common.data.AvatarProfile
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.network.AvatarImages
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 提供普通头像和群头像共用的尺寸、圆角与背景。
 * @param size 头像外框边长。
 * @param containerColor 外框背景，默认透明。
 * @param shape 外框形状，默认使用头像圆角。
 * @param content 头像内容，仅渲染界面，无返回值。
 * 无返回值；仅渲染外框，不查询资料或图片。
 */
@Composable
internal fun AvatarFrame(
    size: Dp = 48.dp,
    containerColor: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(size / 6),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.size(size).clip(shape).background(containerColor),
        content = content,
    )
}

/**
 * 按 UID 展示头像，进入组合树后才查询资料、读取图片缓存并按需下载。
 * @param uid 目标用户 UID；为空或非服务端 UID 时不查询资料。
 * @param name 无障碍描述，空值时使用查询得到的公开昵称。
 * @param size 头像边长。
 * @param repository 当前授权共享的公共头像仓库。
 * @param roomId 可选的真实房间权限上下文，不是用户 UID。
 * @param avatar 已知的公开头像地址，可省略；提供时直接懒加载图片，避免重复查询已持久化的资料。
 * @param shape 头像形状，默认使用圆角。
 * 无返回值；加载和失败时显示默认头像，离开组合或身份变化时取消任务，不阻塞主线程。
 */
@Composable
internal fun Avatar(
    uid: String = "",
    name: String = "",
    size: Dp = 48.dp,
    repository: AvatarRepository? = null,
    roomId: String? = null,
    avatar: String = "",
    shape: Shape = RoundedCornerShape(size / 6),
) {
    val profiles by rememberAvatarProfiles(
        if (avatar.isBlank()) listOf(uid) else emptyList(), repository, roomId,
    )
    val profile = profiles.firstOrNull()
    val address = avatar.ifBlank { profile?.avatar.orEmpty() }
    val context = LocalContext.current.applicationContext
    val imageState = remember(context, repository, roomId, uid, address) {
        mutableStateOf<ImageBitmap?>(AvatarImages.peek(address)?.asImageBitmap())
    }
    // 可见头像先读图片缓存，缺失时才下载；状态按身份重建，旧请求不会在新用户上显示。
    LaunchedEffect(imageState) {
        if (imageState.value != null) return@LaunchedEffect
        imageState.value = AvatarImages.load(context, address)?.asImageBitmap()
    }
    val description = name.ifBlank { profile?.name.orEmpty() }.ifBlank { "头像" }
    AvatarFrame(size, shape = shape) {
        val image = imageState.value
        if (image == null) {
            Image(painterResource(R.drawable.avatar), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * 按服务端给出的成员 UID 顺序懒加载并拼接最多九位成员的群头像。
 * @param uids 群成员 UID 列表，不接受房间号代替用户 UID。
 * @param name 群头像的无障碍名称。
 * @param repository 当前授权共享的头像仓库。
 * @param roomId 群聊真实房间权限上下文。
 * @param size 群头像整体边长。
 * 无返回值；仅可见成员通过公共头像组件读缓存或请求网络，单个失败不阻塞其他成员。
 */
@Composable
internal fun GroupAvatar(
    uids: List<String>,
    name: String,
    repository: AvatarRepository?,
    roomId: String,
    size: Dp = 48.dp,
) {
    val members = uids.take(9)
    if (members.isEmpty()) {
        Avatar(name = name, size = size)
        return
    }
    val columns = when (members.size) {
        1 -> 1
        in 2..4 -> 2
        else -> 3
    }
    val gap = 1.dp
    val memberSize = (size - gap * (columns - 1)) / columns
    AvatarFrame(size, MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            members.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { uid ->
                        Avatar(uid, name, memberSize, repository, roomId)
                    }
                }
            }
        }
    }
}

/**
 * 为可见头像或依赖公开昵称的标题共享按 UID 的懒加载状态，不包含会话或通讯录业务规则。
 * @param uids 目标 UID，结果保持输入顺序；空列表不查询资料。
 * @param repository 当前授权的共享仓库，为空时保留占位。
 * @param roomId 可选房间权限上下文，离群时只允许读取已有资料。
 * @return 逐个补齐的资料状态；离开组合时取消子请求，成功结果由仓库写入 Room。
 */
@Composable
internal fun rememberAvatarProfiles(
    uids: List<String>,
    repository: AvatarRepository?,
    roomId: String? = null,
): State<List<AvatarProfile?>> {
    val state = remember(repository, roomId, uids) {
        mutableStateOf(uids.map { uid ->
            try {
                repository?.peek(uid, roomId)
            } catch (_: CancellationException) {
                // Authorization rejection during composition is an empty state, not a coroutine failure.
                null
            } catch (_: IOException) {
                null
            }
        })
    }
    // 每个 UID 独立发布结果，仓库合并相同 UID 的请求，不等待全部成员后再显示。
    LaunchedEffect(state) {
        if (repository == null) return@LaunchedEffect
        coroutineScope {
            uids.forEachIndexed { index, uid ->
                if (state.value[index] != null) return@forEachIndexed
                // 查询公开资料并只更新对应位置，取消随当前可见项传播。
                launch {
                    val profile = repository.load(uid, roomId)
                    state.value = state.value.toMutableList().apply { this[index] = profile }
                }
            }
        }
    }
    return state
}
