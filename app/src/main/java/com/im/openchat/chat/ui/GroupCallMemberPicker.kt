package com.im.openchat.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.chat.data.GroupDetailsRepository
import com.im.openchat.chat.data.GroupMember
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal val GroupCallColors = darkColorScheme(
    primary = Color(0xFF32C779),
    onPrimary = Color(0xFF102218),
    background = Color(0xFF111111),
    surface = Color(0xFF222222),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF999999),
    surfaceVariant = Color(0xFF333333),
    outline = Color(0xFF666666),
    outlineVariant = Color(0xFF333333),
)

@Composable
internal fun GroupCallMemberPicker(
    repository: GroupDetailsRepository,
    room: String,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onStart: (List<GroupMember>) -> Unit,
    ownAvatar: String = "",
) = key(repository, room) {
    var members by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        loading = true
        error = null
        try {
            val current = repository.members(room)
            coroutineContext.ensureActive()
            if (current.none { it.uid == repository.ownerUid }) {
                throw IOException("你已不在该群中")
            }
            members = current.filter { it.uid != repository.ownerUid }
            selected = selected.intersect(members.map { it.uid }.toSet())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            error = exception.message ?: "群成员读取失败，请重试"
        } finally {
            loading = false
        }
    }
    BackHandler { onBack() }
    GroupCallMemberScreen(
        members = members, ownUid = repository.ownerUid, room = room,
        selected = selected, query = query, loading = loading, error = error,
        avatarRepository = avatarRepository, ownAvatar = ownAvatar, onBack = onBack,
        onQuery = { query = it.take(128) },
        onToggle = { uid ->
            if (!loading && error == null &&
                uid != repository.ownerUid && members.any { it.uid == uid }
            ) {
                selected = if (uid in selected) selected - uid else selected + uid
            }
        },
        onRetry = { if (!loading) revision++ },
        onStart = {
            if (!loading && error == null && selected.isNotEmpty() &&
                selected.all { uid -> uid != repository.ownerUid && members.any { it.uid == uid } }
            ) {
                val byUid = members.associateBy { it.uid }
                onStart(selected.mapNotNull(byUid::get))
            }
        },
    )
}

@Composable
internal fun GroupCallMemberScreen(
    members: List<GroupMember>,
    ownUid: Long,
    room: String,
    selected: Set<Long>,
    query: String,
    loading: Boolean,
    error: String?,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onRetry: () -> Unit,
    onStart: () -> Unit,
    ownAvatar: String = "",
) = MaterialTheme(colorScheme = GroupCallColors) {
    val candidates = remember(members, ownUid) { members.filter { it.uid != ownUid } }
    val chosen = remember(candidates, selected) {
        val byUid = candidates.associateBy { it.uid }
        selected.mapNotNull(byUid::get)
    }
    val visible = remember(candidates, query) {
        val term = query.trim()
        candidates.filter {
            term.isEmpty() || it.username.contains(term, true) ||
                it.nickname.orEmpty().contains(term, true) || it.uid.toString().contains(term)
        }
    }
    val background = MaterialTheme.colorScheme.surface
    val header = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onSurface
    val enabled = !loading && error == null
    val accent = MaterialTheme.colorScheme.primary
    val selectionColor = Color(0xFF003D22)
    Surface(color = background, contentColor = foreground) {
        Column(Modifier.fillMaxSize().testTag("group_call_members")) {
            Row(
                Modifier.fillMaxWidth().background(header).heightIn(min = 56.dp).testTag("group_call_header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回群聊", onClick = onBack)
                }
                Text(
                    "选择成员", Modifier.weight(1f).testTag("group_call_title"),
                    fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                )
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    ToolButton(Icons.Default.Refresh, "刷新群成员", enabled = !loading, onClick = onRetry)
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().height(88.dp).testTag("group_call_selected"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = ownUid) {
                    Box(
                        Modifier.size(64.dp).testTag("group_call_selected_$ownUid")
                            .semantics {
                                contentDescription = "我，已默认选择"
                                this.selected = true
                            },
                    ) {
                        Box(Modifier.clearAndSetSemantics {}) {
                            Avatar(ownUid.toString(), "我", 64.dp, avatarRepository, avatar = ownAvatar)
                        }
                    }
                }
                items(chosen, key = { it.uid }) { member ->
                    Box(
                        Modifier.size(64.dp).testTag("group_call_selected_${member.uid}")
                            .semantics { contentDescription = "取消选择 ${member.label}" }
                            .clickable(enabled = enabled, role = Role.Button) { onToggle(member.uid) },
                    ) {
                        Box(Modifier.clearAndSetSemantics {}) {
                            Avatar(member.uid.toString(), member.label, 64.dp, avatarRepository, room)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag("group_call_search")
                        .semantics { contentDescription = "搜索群成员" },
                    textStyle = TextStyle(color = foreground, fontSize = 18.sp, lineHeight = 26.sp),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) Text(
                                "搜索", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 18.sp, lineHeight = 26.sp,
                            )
                            field()
                        }
                    },
                )
                if (query.isNotEmpty()) ToolButton(Icons.Default.Close, "清空搜索", onClick = { onQuery("") })
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("group_call_list")) {
                when {
                    loading -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp).testTag("group_call_loading")
                                    .semantics { contentDescription = "正在加载群成员" },
                            )
                        }
                    }
                    error != null -> item("error") {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                    visible.isEmpty() -> item("empty") {
                        Text(
                            if (candidates.isEmpty()) "暂无可选择的群成员" else "暂无匹配的群成员",
                            Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> items(visible, key = { it.uid }) { member ->
                        val checked = member.uid in selected
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .testTag("group_call_member_${member.uid}")
                                .toggleable(checked, enabled = enabled, role = Role.Checkbox) { onToggle(member.uid) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.clearAndSetSemantics {}) {
                                Avatar(member.uid.toString(), member.label, 44.dp, avatarRepository, room)
                            }
                            Text(
                                member.label, Modifier.weight(1f), fontSize = 18.sp, lineHeight = 26.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                Modifier.size(26.dp)
                                    .background(if (checked) selectionColor else Color.Transparent, CircleShape)
                                    .border(2.dp, if (checked) selectionColor else MaterialTheme.colorScheme.outline, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) Icon(
                                    Icons.Default.Check, contentDescription = null, modifier = Modifier.size(19.dp),
                                    tint = Color.White,
                                )
                            }
                        }
                        HorizontalDivider(
                            Modifier.padding(start = 72.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            Button(
                onClick = onStart, enabled = enabled && chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)
                    .testTag("group_call_start"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary),
            ) {
                Text(
                    if (chosen.isEmpty()) "发起通话" else "发起通话 (${chosen.size})",
                    fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
