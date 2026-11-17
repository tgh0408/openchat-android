package com.im.openchat.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.GroupDetails
import com.im.openchat.chat.data.GroupMember
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton

private data class GroupMemberTile(val member: GroupMember? = null, val action: String = "")

@Composable
internal fun GroupDetailsScreen(
    conversation: Conversation,
    details: GroupDetails?,
    members: List<GroupMember>,
    ownUid: Long,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAction: (String) -> Unit,
    onMember: (GroupMember) -> Unit,
    onPinned: (Boolean) -> Unit,
    onMuted: (Boolean) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var memberLimit by remember { mutableIntStateOf(19) }
    val filtered = remember(members, query) {
        val term = query.trim()
        if (term.isEmpty()) members else members.filter {
            it.username.contains(term, true) || it.nickname.orEmpty().contains(term, true) ||
                it.uid.toString().contains(term)
        }
    }
    val shown = filtered.take(memberLimit)
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val background = if (light) Color(0xFFEDEDED) else MaterialTheme.colorScheme.surfaceContainerLow
    val surface = MaterialTheme.colorScheme.surface
    val canRemove = members.firstOrNull { it.uid == ownUid }?.canManage == true
    BackHandler(enabled = searching) { searching = false; query = ""; memberLimit = 19 }

    BoxWithConstraints(Modifier.fillMaxSize().background(background).testTag("group_details")) {
        val columns = ((maxWidth.value - 24f) / 64f).toInt().coerceIn(3, 5)
        val memberRows = remember(shown, columns, canRemove) {
            buildList {
                shown.forEach { add(GroupMemberTile(member = it)) }
                add(GroupMemberTile(action = "添加群成员"))
                if (canRemove) add(GroupMemberTile(action = "移出群成员"))
            }.chunked(columns)
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回群聊", size = 48.dp, onClick = onBack)
                }
                Text(
                    details?.memberCount?.let { "聊天信息($it)" } ?: "聊天信息",
                    Modifier.weight(1f).testTag("group_details_title"), fontSize = 18.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.Default.Search, "搜索群成员", size = 48.dp, onClick = { searching = !searching; query = "" })
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(128); memberLimit = 19 },
                    label = { Text("搜索群成员") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("group_details_list")) {
                if (loading || error != null) item("status") {
                    Row(
                        Modifier.fillMaxWidth().background(surface).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            error ?: "正在读取群信息",
                            Modifier.weight(1f), fontSize = 13.sp,
                            color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        if (!loading) ToolButton(Icons.Default.Refresh, "重试群信息", onClick = onRetry)
                    }
                }
                item("members_top") { Spacer(Modifier.fillMaxWidth().height(12.dp).background(surface)) }
                items(memberRows, key = { row -> "members:${row.first().member?.uid ?: row.first().action}" }) { row ->
                    Row(
                        Modifier.fillMaxWidth().background(surface).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { tile ->
                            val member = tile.member
                            Column(
                                Modifier.weight(1f).clickable(
                                    enabled = member != null || !loading && error == null && !busy && details != null,
                                    role = Role.Button,
                                    onClickLabel = if (member == null) tile.action else "查看${member.label}的资料",
                                ) { if (member == null) onAction(tile.action) else onMember(member) }
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (member == null) MemberActionIcon(tile.action) else Avatar(
                                    uid = member.uid.toString(), name = member.label, size = 48.dp,
                                    repository = avatarRepository, roomId = conversation.contactId,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    member?.label ?: " ", Modifier.fillMaxWidth(),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                )
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (query.isNotBlank() && filtered.isEmpty() && !loading) item("no_members") {
                    Text("暂无匹配的群成员", Modifier.fillMaxWidth().background(surface).padding(16.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item("members_bottom") { Spacer(Modifier.fillMaxWidth().height(12.dp).background(surface)) }
                if (filtered.size > shown.size || memberLimit > 19) item("more") {
                    Row(
                        Modifier.fillMaxWidth().background(surface).heightIn(min = 56.dp)
                            .clickable {
                                memberLimit = if (filtered.size > shown.size) memberLimit + 80 else 19
                            }.padding(12.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (filtered.size > shown.size) "更多群成员" else "收起群成员",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                        Icon(
                            if (filtered.size > shown.size) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item("info_gap") { GroupGap() }
                item("name") { GroupValueRow("群聊名称", details?.name?.ifBlank { conversation.title } ?: conversation.title) { onAction("群聊名称") } }
                item("qr") { GroupValueRow("群二维码", qr = true) { onAction("群二维码") } }
                item("notice") { GroupValueRow("群公告", details?.notice?.description.orEmpty()) { onAction("群公告") } }
                item("remark") { GroupValueRow("备注") { onAction("备注") } }
                item("history_gap") { GroupGap() }
                item("history") { GroupValueRow("查找聊天记录") { onAction("查找聊天记录") } }
                item("preferences_gap") { GroupGap() }
                item("mute") { GroupSwitchRow("消息免打扰", conversation.muted, !busy, onMuted) }
                item("pin") { GroupSwitchRow("置顶聊天", conversation.pinned, !busy, onPinned) }
                item("directory") { GroupSwitchRow("保存到通讯录", false, false) {} }
                item("nickname_gap") { GroupGap() }
                item("nickname") {
                    GroupValueRow("我在群里的昵称", members.firstOrNull { it.uid == ownUid }?.label.orEmpty()) {
                        onAction("我在群里的昵称")
                    }
                }
                item("show_names") { GroupSwitchRow("显示群成员昵称", true, false) {} }
                item("join_method") { GroupValueRow("进群方式", "暂无信息") { onAction("进群方式") } }
                item("join_time") { GroupValueRow("进群时间", "暂无信息", arrow = false) }
                item("actions_gap") { GroupGap() }
                item("background") { GroupValueRow("设置当前聊天背景") { onAction("设置当前聊天背景") } }
                item("clear") { GroupValueRow("清空聊天记录") { onAction("清空聊天记录") } }
                item("report") { GroupValueRow("投诉") { onAction("投诉") } }
                item("exit_gap") { GroupGap() }
                item("exit") {
                    Box(
                        Modifier.fillMaxWidth().background(surface)
                            .clickable(enabled = !busy, role = Role.Button) { onAction("退出群聊") }
                            .heightIn(min = 56.dp).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("退出群聊", color = MaterialTheme.colorScheme.error, fontSize = 17.sp)
                    }
                }
                item("bottom_gap") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun GroupGap() { Spacer(Modifier.height(8.dp)) }

@Composable
private fun MemberActionIcon(action: String) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(48.dp).semantics { contentDescription = action }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
        Icon(
            if (action == "移出群成员") ImageVector.vectorResource(R.drawable.ic_chat_group_remove) else Icons.Default.Add,
            null, Modifier.size(28.dp), tint = color,
        )
    }
}

@Composable
private fun GroupValueRow(
    label: String,
    value: String = "",
    arrow: Boolean = true,
    qr: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (value.isNotBlank()) Text(
                value, Modifier.weight(1f), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (qr) Icon(ImageVector.vectorResource(R.drawable.ic_chat_group_qr), null,
                Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    }
}

@Composable
private fun GroupSwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Switch(
                checked = checked, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = label },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFF07C160), checkedThumbColor = Color.White,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    }
}
