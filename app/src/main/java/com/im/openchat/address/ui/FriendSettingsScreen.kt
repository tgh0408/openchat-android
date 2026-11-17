package com.im.openchat.address.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.address.data.DirectoryProfile
import com.im.openchat.common.ui.ToolButton

@Composable
internal fun FriendSettingsScreen(
    profile: DirectoryProfile?,
    onBack: () -> Unit,
    onUnavailable: (String) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    canManage: Boolean = false,
    canDelete: Boolean = false,
    onRefresh: () -> Unit = {},
    onBlacklistChange: (Boolean) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f
    val surface = if (light) Color.White else colors.surface
    val background = if (light) Color(0xFFEDEDED) else colors.surfaceContainerLow
    val secondary = if (light) Color(0xFF888888) else colors.onSurfaceVariant
    val detail = profile?.remark?.takeIf(String::isNotBlank)
    val listState = rememberLazyListState()
    LaunchedEffect(error) {
        if (error != null) listState.scrollToItem(0)
    }

    Surface(
        modifier = Modifier.fillMaxSize().testTag("friend_settings").semantics {
            stateDescription = if (busy) "正在处理" else "就绪"
        },
        color = background,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(background).heightIn(min = 56.dp)
                    .testTag("friend_settings_header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", onClick = onBack)
                }
                Text(
                    "朋友设置",
                    Modifier.weight(1f).testTag("friend_settings_title"),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = 0.sp,
                    color = colors.onSurface,
                )
                Spacer(Modifier.size(56.dp))
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("friend_settings_list"),
                state = listState,
            ) {
                item("status") {
                    error?.let {
                        Text(
                            it,
                            Modifier.fillMaxWidth().background(surface)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("friend_settings_error"),
                            color = colors.error,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        TextButton(
                            enabled = !busy, onClick = onRefresh,
                            modifier = Modifier.testTag("friend_settings_refresh"),
                        ) { Text("刷新状态") }
                    }
                }
                item("profile_group") {
                    FriendSettingsArrowRow(
                        label = "设置朋友资料",
                        value = detail,
                        surface = surface,
                        secondary = secondary,
                        tag = "friend_settings_profile",
                        onClick = { onUnavailable("设置朋友资料") },
                    )
                    FriendSettingsArrowRow(
                        label = "朋友权限",
                        surface = surface,
                        secondary = secondary,
                        tag = "friend_settings_permissions",
                        onClick = { onUnavailable("朋友权限") },
                    )
                }
                item("recommend_gap") { FriendSettingsGap(background) }
                item("recommend_group") {
                    FriendSettingsArrowRow(
                        label = "把对方推荐给朋友",
                        surface = surface,
                        secondary = secondary,
                        tag = "friend_settings_recommend",
                        onClick = { onUnavailable("把对方推荐给朋友") },
                    )
                }
                item("star_gap") { FriendSettingsGap(background) }
                item("star") {
                    FriendSettingsSwitchRow(
                        label = "设为星标朋友",
                        surface = surface,
                        tag = "friend_settings_star",
                        unavailable = true,
                        onChange = { onUnavailable("设为星标朋友") },
                    )
                }
                item("blacklist_gap") { FriendSettingsGap(background) }
                item("blacklist") {
                    FriendSettingsSwitchRow(
                        label = "加入黑名单",
                        checked = profile?.blacklisted == true,
                        enabled = canManage && !busy,
                        surface = surface,
                        tag = "friend_settings_blacklist",
                        onChange = onBlacklistChange,
                    )
                }
                item("report") {
                    FriendSettingsArrowRow(
                        label = "投诉",
                        surface = surface,
                        secondary = secondary,
                        tag = "friend_settings_report",
                        onClick = { onUnavailable("投诉") },
                    )
                }
                item("delete_gap") { FriendSettingsGap(background) }
                item("delete") {
                    Row(
                        Modifier.fillMaxWidth().background(surface)
                            .clickable(enabled = canDelete && !busy, role = Role.Button, onClick = onDelete)
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .testTag("friend_settings_delete"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "删除",
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp,
                            color = if (!canDelete || busy) colors.onSurface.copy(alpha = 0.38f)
                                else if (light) Color(0xFFFA5151) else colors.error,
                        )
                    }
                }
                item("bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun FriendSettingsArrowRow(
    label: String,
    surface: Color,
    secondary: Color,
    tag: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(surface).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        value?.let {
            Text(
                it,
                Modifier.weight(0.75f),
                fontSize = 17.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                color = secondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = secondary,
        )
    }
    FriendSettingsDivider(surface)
}

@Composable
private fun FriendSettingsSwitchRow(
    label: String,
    surface: Color,
    tag: String,
    checked: Boolean = false,
    enabled: Boolean = true,
    unavailable: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f
    val offTrack = if (light) Color(0xFFE5E5E5) else colors.surfaceContainerHighest
    Row(
        Modifier.fillMaxWidth().background(surface).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
            color = colors.onSurface,
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            thumbContent = {},
            modifier = Modifier.semantics {
                contentDescription = label
                if (unavailable) stateDescription = "未接入"
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF07C160),
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
                uncheckedTrackColor = offTrack,
            ),
        )
    }
    FriendSettingsDivider(surface)
}

@Composable
private fun FriendSettingsDivider(surface: Color) {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(
        Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = if (surface.luminance() > 0.5f) Color(0xFFF0F0F0) else colors.outlineVariant,
    )
}

@Composable
private fun FriendSettingsGap(background: Color) {
    Spacer(Modifier.fillMaxWidth().height(8.dp).background(background))
}
