package com.im.openchat.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.im.openchat.chat.data.Conversation
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendDetailsScreen(
    conversation: Conversation,
    name: String,
    busy: Boolean,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onAction: (String) -> Unit,
    onPinned: (Boolean) -> Unit,
    onMuted: (Boolean) -> Unit,
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val background = if (light) Color(0xFFEDEDED) else MaterialTheme.colorScheme.surfaceContainerLow
    val secondary = if (light) Color(0xFFAAAAAA) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().background(background).testTag("friend_details")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回单聊", size = 48.dp, onClick = onBack)
            }
            Text(
                "聊天信息", Modifier.weight(1f).testTag("friend_details_title"),
                fontSize = 18.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(56.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("friend_details_list")) {
            item("members") {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        Modifier.width(56.dp).testTag("friend_details_profile")
                            .clickable(
                                enabled = !conversation.participantId.isNullOrBlank(),
                                role = Role.Button, onClickLabel = "查看用户资料", onClick = onProfile,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(
                            uid = conversation.participantId.orEmpty(), name = name, size = 56.dp,
                            repository = avatarRepository, roomId = conversation.contactId,
                            avatar = conversation.avatar,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            name, Modifier.fillMaxWidth(), fontSize = 12.sp, color = secondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val inset = 0.5.dp.toPx()
                            drawRoundRect(
                                color = secondary, topLeft = Offset(inset, inset),
                                size = Size(size.width - inset * 2, size.height - inset * 2),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                                ),
                            )
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text("添加聊天成员") } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = { onAction("添加聊天成员") }, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Default.Add, "添加聊天成员", Modifier.size(32.dp), tint = secondary)
                            }
                        }
                    }
                }
            }
            item("history_gap") { Spacer(Modifier.height(8.dp)) }
            item("history") { FriendDetailsAction("查找聊天记录", onAction) }
            item("preferences_gap") { Spacer(Modifier.height(8.dp)) }
            item("mute") {
                FriendDetailsSwitch("消息免打扰", conversation.muted, !busy, onMuted)
                FriendDetailsDivider()
            }
            item("pin") {
                FriendDetailsSwitch("置顶聊天", conversation.pinned, !busy, onPinned)
                FriendDetailsDivider()
            }
            item("reminder") {
                FriendDetailsSwitch("提醒", false, false, {}, unavailable = true)
            }
            item("background_gap") { Spacer(Modifier.height(8.dp)) }
            item("background") { FriendDetailsAction("设置当前聊天背景", onAction) }
            item("clear_gap") { Spacer(Modifier.height(8.dp)) }
            item("clear") { FriendDetailsAction("清空聊天记录", onAction) }
            item("report_gap") { Spacer(Modifier.height(8.dp)) }
            item("report") { FriendDetailsAction("投诉", onAction) }
            item("bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FriendDetailsAction(label: String, onAction: (String) -> Unit) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button) { onAction(label) }
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
            tint = if (light) Color(0xFFB5B5B5) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FriendDetailsSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    unavailable: Boolean = false,
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val offTrack = if (light) Color(0xFFE5E5E5) else MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked, enabled = enabled, onCheckedChange = onChange,
            thumbContent = {},
            modifier = Modifier.semantics {
                contentDescription = label
                if (unavailable) stateDescription = "未接入"
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF07C160), checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
                uncheckedTrackColor = offTrack,
                disabledUncheckedThumbColor = Color.White,
                disabledUncheckedTrackColor = offTrack,
                disabledUncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun FriendDetailsDivider() {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(
            Modifier.padding(start = 16.dp), thickness = 0.5.dp,
            color = if (light) Color(0xFFF0F0F0) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
    }
}
