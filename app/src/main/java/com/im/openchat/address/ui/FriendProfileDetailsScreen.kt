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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.address.data.DirectoryProfile
import com.im.openchat.common.ui.ToolButton

@Composable
internal fun FriendProfileDetailsScreen(
    profile: DirectoryProfile?,
    onBack: () -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f
    val surface = if (light) Color.White else colors.surface
    val background = if (light) Color(0xFFEDEDED) else colors.surfaceContainerLow
    val secondary = if (light) Color(0xFF888888) else colors.onSurfaceVariant
    val signature = profile?.personalitySignature?.takeIf(String::isNotBlank)

    Surface(
        modifier = Modifier.fillMaxSize().testTag("friend_profile_details"),
        color = background,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(background).heightIn(min = 56.dp)
                    .testTag("friend_profile_details_header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", onClick = onBack)
                }
                Text(
                    "朋友资料",
                    Modifier.weight(1f).testTag("friend_profile_details_title"),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = 0.sp,
                    color = colors.onSurface,
                )
                Spacer(Modifier.size(56.dp))
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("friend_profile_details_list"),
            ) {
                item("remark_section") { FriendProfileSectionTitle("备注", background, secondary) }
                item("remark_name") {
                    FriendProfileDetailRow(
                        label = "备注名",
                        value = profile?.remark?.takeIf(String::isNotBlank),
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("备注名") },
                    )
                }
                item("phone") {
                    FriendProfileDetailRow(
                        label = "电话",
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("电话") },
                    )
                }
                item("labels") {
                    FriendProfileDetailRow(
                        label = "标签",
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("标签") },
                    )
                }
                item("notes") {
                    FriendProfileDetailRow(
                        label = "备忘",
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("备忘") },
                    )
                }
                item("photos") {
                    FriendProfileDetailRow(
                        label = "照片",
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("照片") },
                    )
                }
                item("permissions_section") {
                    FriendProfileSectionTitle("朋友权限", background, secondary)
                }
                item("permissions") {
                    FriendProfileDetailRow(
                        label = "权限",
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("权限") },
                    )
                }
                item("more_section") { FriendProfileSectionTitle("更多信息", background, secondary) }
                item("common_groups") {
                    FriendProfileDetailRow(
                        label = "我和对方的共同群聊",
                        surface = surface,
                        secondary = secondary,
                        onClick = { onUnavailable("共同群聊") },
                    )
                }
                item("signature") {
                    FriendProfileDetailRow(
                        label = "签名",
                        value = signature,
                        surface = surface,
                        secondary = secondary,
                        showArrow = false,
                    )
                }
                item("added_time") {
                    FriendProfileDetailRow(
                        label = "添加时间",
                        surface = surface,
                        secondary = secondary,
                        showArrow = false,
                    )
                }
                item("bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun FriendProfileSectionTitle(title: String, background: Color, secondary: Color) {
    Box(
        Modifier.fillMaxWidth().background(background).heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    ) {
        Text(title, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp, color = secondary)
    }
}

@Composable
private fun FriendProfileDetailRow(
    label: String,
    surface: Color,
    secondary: Color,
    value: String? = null,
    showArrow: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = Modifier.fillMaxWidth().background(surface).testTag("friend_profile_detail_$label")
        .heightIn(min = 56.dp)
        .then(
            if (onClick != null) {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            },
        )
        .padding(horizontal = 16.dp, vertical = 14.dp)
    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label, Modifier.weight(if (value.isNullOrBlank()) 1f else 0.35f)
                .testTag("friend_profile_detail_label_$label"),
            fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        value?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                Modifier.weight(0.65f).testTag("friend_profile_detail_value_$label"),
                fontSize = 17.sp,
                lineHeight = 24.sp,
                color = secondary,
                letterSpacing = 0.sp,
                textAlign = TextAlign.End,
            )
        }
        if (showArrow) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp), tint = secondary)
        }
    }
    Box(Modifier.fillMaxWidth().background(surface)) {
        HorizontalDivider(
            Modifier.padding(start = 16.dp),
            thickness = 0.5.dp,
            color = if (surface.luminance() > 0.5f) Color(0xFFF0F0F0) else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
