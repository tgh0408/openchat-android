package com.im.openchat.address.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R
import com.im.openchat.address.data.ApplicationDirection
import com.im.openchat.address.data.ApplicationStatus
import com.im.openchat.address.data.ApplicationUnread
import com.im.openchat.address.data.FriendApplication
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.address.data.applicationStatusLabel
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton

@Composable
internal fun FriendApplicationsList(
    records: List<FriendApplication>,
    unread: List<ApplicationUnread>,
    avatarRepository: AvatarRepository?,
    query: String,
    onQuery: (String) -> Unit,
    listState: LazyListState,
    loading: Boolean,
    last: Boolean,
    error: String?,
    unreadError: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (FriendApplication) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onUnreadRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val background = if (colors.surface.luminance() > 0.5f) Color(0xFFEDEDED) else colors.background
    val secondary = if (colors.surface.luminance() > 0.5f) Color(0xFF888888) else colors.onSurfaceVariant
    val visible = remember(records, query) {
        records.filter { item ->
            listOf(item.name, item.message, item.peerUid.toString()).any { it.contains(query.trim(), true) }
        }
    }
    val unreadUids = remember(unread) { unread.map { it.uid }.toSet() }
    BackHandler(filtering) { filtering = false; onQuery(""); focus.clearFocus() }

    Column(Modifier.fillMaxSize().background(background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("applications_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", onClick = onBack)
            }
            Text(
                "新的朋友", Modifier.weight(1f).padding(vertical = 16.dp),
                color = colors.onSurface, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                ToolButton(Icons.Default.MoreVert, "更多", Modifier.rotate(90f), onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("添加朋友") }, onClick = { menu = false; onAdd() })
                    DropdownMenuItem(
                        text = { Text(if (filtering) "取消筛选" else "筛选申请") },
                        onClick = {
                            menu = false
                            filtering = !filtering
                            if (!filtering) { onQuery(""); focus.clearFocus() }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新") }, enabled = !loading,
                        onClick = { menu = false; onRefresh() },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            shape = RoundedCornerShape(8.dp), color = colors.surface,
        ) {
            if (filtering) {
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("applications_filter"),
                    textStyle = LocalTextStyle.current.copy(
                        color = colors.onSurface, fontSize = 16.sp, letterSpacing = 0.sp,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { field ->
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = secondary)
                            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                                if (query.isEmpty()) Text(
                                    "搜索姓名、说明或 UID", color = secondary, fontSize = 16.sp, letterSpacing = 0.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                field()
                            }
                            ToolButton(Icons.Default.Close, "取消筛选", onClick = {
                                filtering = false; onQuery(""); focus.clearFocus()
                            })
                        }
                    },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAdd)
                        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("applications_search"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "搜索 账号/手机号", color = secondary, fontSize = 16.sp, lineHeight = 22.sp,
                        letterSpacing = 0.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AddressError(error, onRetry)
        AddressError(unreadError, onUnreadRetry)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("applications_list"),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (visible.isNotEmpty()) item(key = "section") {
                // 当前 FriendApplyResp 没有时间字段，不能按申请 ID 或加载时间伪造三天分组。
                Text(
                    "好友申请", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    color = colors.onSurface, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
                )
            }
            items(visible, key = { it.applyId }) { item ->
                FriendApplicationRow(
                    item, item.status == ApplicationStatus.PENDING && item.peerUid in unreadUids,
                    avatarRepository, secondary, divider = item.applyId != visible.last().applyId,
                    onClick = { onSelect(item) },
                    onProfile = { onUserProfile(UserProfileTarget(item.peerUid, item.name)) },
                )
            }
            if (visible.isEmpty() && !loading && error == null) item(key = "empty") {
                Text(
                    if (query.isBlank()) "暂无好友申请" else "暂无匹配的好友申请",
                    Modifier.fillMaxWidth().padding(24.dp), color = secondary, textAlign = TextAlign.Center,
                )
            }
            if (!last && records.isNotEmpty()) item(key = "more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(enabled = !loading, onClick = onLoadMore) { Text("加载更多") }
                }
            }
        }
    }
}

@Composable
private fun FriendApplicationRow(
    application: FriendApplication,
    unread: Boolean,
    avatarRepository: AvatarRepository?,
    secondary: Color,
    divider: Boolean,
    onClick: () -> Unit,
    onProfile: () -> Unit,
) {
    var expanded by rememberSaveable(application.applyId, application.message) { mutableStateOf(false) }
    var overflowing by remember(application.message) { mutableStateOf(false) }
    val sent = application.direction == ApplicationDirection.SENT
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("application_${application.applyId}"),
                verticalAlignment = Alignment.Top,
            ) {
                BadgedBox(
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "查看用户资料", onClick = onProfile)
                        .testTag("application_avatar_${application.applyId}"),
                    badge = { if (unread) Badge(Modifier.testTag("application_unread_${application.applyId}")) },
                ) {
                    Avatar(application.peerUid.toString(), application.name, 44.dp, avatarRepository)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            application.name, Modifier.weight(1f).padding(end = 8.dp)
                                .testTag("application_name_${application.applyId}"),
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 23.sp,
                            letterSpacing = 0.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            Modifier.widthIn(max = 128.dp).testTag("application_status_${application.applyId}"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (sent) Icon(
                                painterResource(R.drawable.ic_address_application_sent), "已发出的申请",
                                Modifier.size(16.dp), tint = secondary,
                            )
                            Text(
                                applicationStatusLabel(application),
                                color = if (application.canApprove) MaterialTheme.colorScheme.primary else secondary,
                                fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, textAlign = TextAlign.End,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            if (sent) "我: ${application.message}" else application.message,
                            Modifier.weight(1f).testTag("application_message_${application.applyId}"),
                            color = secondary, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                        )
                        if (overflowing || expanded) Text(
                            if (expanded) "收起" else "展开",
                            Modifier.clickable(role = Role.Button) { expanded = !expanded }
                                .padding(start = 8.dp).testTag("application_expand_${application.applyId}"),
                            color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp, lineHeight = 20.sp,
                            letterSpacing = 0.sp,
                        )
                    }
                }
            }
            if (divider) HorizontalDivider(
                Modifier.padding(start = 68.dp), thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            )
        }
    }
}
