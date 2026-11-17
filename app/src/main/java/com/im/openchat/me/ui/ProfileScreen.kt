package com.im.openchat.me.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.common.data.ChatData
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.R
import com.im.openchat.me.data.Profile

/**
 * 展示个人资料及收藏、表情、设置三个入口，不提供在线状态管理。
 *
 * @param data 当前个人资料和联系人数据。
 * @param onEdit 点击资料时触发编辑回调。
 * @param onPage 点击入口时回传名称，由调用方导航或提示尚未接入。
 * @param avatarRepository 个人头像缺少地址时按 UID 查询的公共仓库。
 * 无返回值，渲染界面并按需加载头像；交互触发对应回调。
 */
@Composable
internal fun ProfileScreen(
    data: ChatData,
    onEdit: () -> Unit,
    onPage: (String) -> Unit,
    avatarRepository: AvatarRepository? = null,
) {
    val background = if (data.profile.darkMode) MaterialTheme.colorScheme.background else Color(0xFFEDEDED)
    Column(
        Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState()),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 192.dp)
                    .clickable(role = Role.Button, onClickLabel = "编辑资料", onClick = onEdit)
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    data.profile.userId, data.profile.name, 64.dp,
                    avatarRepository, avatar = data.profile.avatar,
                )
                Column(Modifier.weight(1f).padding(start = 20.dp, end = 12.dp)) {
                    Text(
                        data.profile.name, fontSize = 24.sp, lineHeight = 32.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        data.profile.userId.takeIf { it.isNotBlank() }?.let { "OpenChat ID：$it" } ?: "本机",
                        fontSize = 14.sp, lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ProfileMenuRow(R.drawable.ic_me_favorites, "收藏", divider = true) { onPage("收藏") }
        ProfileMenuRow(R.drawable.ic_me_emoji, "表情") { onPage("表情") }
        Spacer(Modifier.height(8.dp))
        ProfileMenuRow(R.drawable.ic_me_settings, "设置") { onPage("设置") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileMenuRow(
    @DrawableRes icon: Int,
    title: String,
    divider: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color.Unspecified)
                Text(
                    title, Modifier.weight(1f).padding(horizontal = 20.dp),
                    fontSize = 17.sp, lineHeight = 24.sp,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            if (divider) {
                HorizontalDivider(
                    Modifier.padding(start = 64.dp), thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                )
            }
        }
    }
}

/**
 * 展示个人资料编辑对话框，无返回值；输入修改草稿，保存时回传新资料。
 *
 * @param profile 用于初始化草稿的个人资料。
 * @param onDismiss 取消或关闭时触发。
 * @param onSave 保存时回传去除首尾空白后的资料，由调用方持久化。
 */
@Composable
internal fun EditProfileDialog(profile: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var bio by remember { mutableStateOf(profile.bio) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("编辑资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    // 接收昵称并限制为 40 字以内，无返回值，仅修改草稿。
                    value = name, onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("昵称") }, singleLine = true,
                )
                OutlinedTextField(
                    // 接收签名并限制为 120 字以内，无返回值，仅修改草稿。
                    value = bio, onValueChange = { if (it.length <= 120) bio = it },
                    label = { Text("个性签名") }, maxLines = 3,
                )
            }
        },
        confirmButton = {
            // 点击保存时回传去除首尾空白后的资料，无参数、无返回值。
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(profile.copy(name = name.trim(), bio = bio.trim())) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
