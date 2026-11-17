package com.im.openchat.address.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.address.data.DirectoryProfile
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton

/** 资料展示不创建会话；只有发消息回调可以进入真实房间。 */
@Composable
internal fun UserProfileScreen(
    target: UserProfileTarget,
    profile: DirectoryProfile?,
    loading: Boolean,
    error: String?,
    opening: Boolean,
    openError: String?,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onMessage: () -> Unit,
    onUnavailable: (String) -> Unit,
    onFriendDetails: () -> Unit,
    onFriendSettings: () -> Unit = { onUnavailable("资料设置") },
    onAddFriend: () -> Unit = {},
    messageEnabled: Boolean = profile != null,
    addFriend: Boolean = false,
    addSubmitted: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f
    val surface = if (light) Color.White else colors.surface
    val background = if (light) Color(0xFFEDEDED) else colors.surfaceContainerLow
    val secondary = if (light) Color(0xFF888888) else colors.onSurfaceVariant
    val actionColor = if (light) Color(0xFF576B95) else Color(0xFFB4C5E9)
    val name = if (profile != null) profile.remark?.takeIf(String::isNotBlank) ?: profile.name
        else target.name.ifBlank { "用户 ${target.uid}" }

    Surface(color = surface, contentColor = colors.onSurface) {
        Column(Modifier.fillMaxSize().background(background).testTag("directory_profile")) {
            Row(
                Modifier.fillMaxWidth().background(surface).height(56.dp).testTag("directory_profile_header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", onClick = onBack)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(
                        Icons.Default.MoreVert, "更多", Modifier.rotate(90f),
                        onClick = onFriendSettings,
                    )
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("directory_profile_list"), state = listState) {
                item("identity") {
                    Row(
                        Modifier.fillMaxWidth().background(surface)
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Box(Modifier.size(64.dp).testTag("directory_profile_avatar")) {
                            Avatar(
                                target.uid.toString(), name, 64.dp, avatarRepository,
                                avatar = profile?.avatar ?: target.avatar,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                name, Modifier.fillMaxWidth().testTag("directory_profile_name"),
                                fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.onSurface,
                            )
                            Text(
                                "昵称：${profile?.name ?: target.friend?.name ?: target.name.ifBlank { "用户 ${target.uid}" }}",
                                Modifier.fillMaxWidth().testTag("directory_profile_nickname"),
                                fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp, color = secondary,
                            )
                            Text(
                                "UID：${target.uid}", Modifier.fillMaxWidth(),
                                fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp, color = secondary,
                            )
                            if (profile?.blacklisted == true) {
                                Text("已加入黑名单", fontSize = 14.sp, letterSpacing = 0.sp, color = secondary)
                            }
                        }
                    }
                }
                if (loading || error != null) item("profile_status") {
                    Column(Modifier.fillMaxWidth().background(surface)) {
                        if (loading) LinearProgressIndicator(
                            Modifier.fillMaxWidth().testTag("directory_profile_loading"),
                        )
                        AddressError(error, onRetry)
                    }
                }
                item("profile_divider") { DirectoryProfileDivider(surface, inset = true) }
                item("friend_info") {
                    DirectoryProfileRow(
                        "朋友资料", surface, secondary, minHeight = 80,
                        detail = profile?.personalitySignature?.takeIf(String::isNotBlank),
                        enabled = profile != null && !loading && error == null && !opening,
                        onClick = onFriendDetails,
                    )
                }
                item("actions_gap") { Spacer(Modifier.height(8.dp)) }
                if (openError != null) item("open_error") {
                    Text(
                        openError, Modifier.fillMaxWidth().background(surface).padding(16.dp),
                        color = colors.error, fontSize = 13.sp, letterSpacing = 0.sp,
                    )
                }
                if (profile?.blacklisted != true) {
                    item("message") {
                        val primaryLabel = when {
                            opening -> if (addFriend) "正在发送" else "正在进入"
                            addSubmitted -> "申请已发送"
                            addFriend -> "添加到通讯录"
                            else -> "发消息"
                        }
                        val primaryIcon = if (addFriend) R.drawable.ic_common_addfriend else R.drawable.ic_common_chat
                        DirectoryProfileAction(
                            primaryLabel, primaryIcon, surface, actionColor,
                            enabled = !loading && error == null && messageEnabled && !opening && !addSubmitted,
                            tag = "directory_profile_message",
                            onClick = if (addFriend) onAddFriend else onMessage,
                        )
                    }
                    item("actions_divider") { DirectoryProfileDivider(surface) }
                    item("call") {
                        DirectoryProfileAction(
                            "音视频通话", R.drawable.ic_chat_video_call, surface, actionColor,
                            tag = "directory_profile_call", onClick = { onUnavailable("音视频通话") },
                        )
                    }
                }
                item("bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DirectoryProfileRow(
    label: String,
    surface: Color,
    secondary: Color,
    minHeight: Int = 56,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = minHeight.dp).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, fontSize = 17.sp, letterSpacing = 0.sp, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(
                detail, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = secondary,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = secondary)
    }
}

@Composable
private fun DirectoryProfileAction(
    label: String,
    icon: Int,
    surface: Color,
    color: Color,
    enabled: Boolean = true,
    tag: String,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().background(surface).testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(23.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(
            label, Modifier.widthIn(max = 240.dp).weight(1f, fill = false),
            fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, color = tint,
        )
    }
}

@Composable
private fun DirectoryProfileDivider(surface: Color, inset: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(surface)) {
        HorizontalDivider(
            Modifier.padding(start = if (inset) 16.dp else 0.dp), thickness = 0.5.dp,
            color = if (surface.luminance() > 0.5f) Color(0xFFF0F0F0) else colors.outlineVariant,
        )
    }
}
