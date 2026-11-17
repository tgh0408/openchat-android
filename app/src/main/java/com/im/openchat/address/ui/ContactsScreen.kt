package com.im.openchat.address.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R
import com.im.openchat.address.data.DirectoryFriend
import com.im.openchat.address.data.groupDirectoryFriends
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.SearchBox
import com.im.openchat.common.ui.ToolButton
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** 参考图的扁平通讯录首页；只展示 PC 已有入口，字母分组沿用后端排序。 */
@Composable
internal fun ContactsScreen(
    friends: List<DirectoryFriend>,
    unread: Int,
    loading: Boolean,
    error: String?,
    unreadError: String?,
    avatarRepository: AvatarRepository?,
    onFriend: (DirectoryFriend) -> Unit,
    onRequests: () -> Unit,
    onGroups: () -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    var indexVisible by remember { mutableStateOf(false) }
    var indexInteraction by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress, activeLetter, indexInteraction) {
        if (listState.isScrollInProgress || activeLetter != null) {
            indexVisible = true
        } else if (indexVisible) {
            delay(5_000.milliseconds)
            indexVisible = false
        }
    }
    val groups = remember(friends, query) { groupDirectoryFriends(friends, query) }
    val indexes = remember(groups) {
        buildMap {
            put("↑", 0)
            var index = 2
            groups.forEach { (letter, rows) ->
                put(letter, index)
                index += rows.size + 1
            }
        }
    }
    BackHandler(searchExpanded) { searchExpanded = false; query = ""; focus.clearFocus() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(
                        "通讯录", Modifier.align(Alignment.Center).padding(horizontal = 100.dp),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    )
                    ToolButton(
                        Icons.Default.Refresh, "刷新通讯录", Modifier.align(Alignment.CenterStart),
                        enabled = !loading, size = 40.dp, onClick = onRefresh,
                    )
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        ToolButton(
                            ImageVector.vectorResource(R.drawable.ic_common_search), "搜索通讯录", size = 40.dp,
                            onClick = {
                                searchExpanded = !searchExpanded
                                if (!searchExpanded) { query = ""; focus.clearFocus() }
                            },
                        )
                        ToolButton(
                            ImageVector.vectorResource(R.drawable.ic_common_add), "添加朋友",
                            size = 40.dp, onClick = onAdd,
                        )
                    }
                }
                if (searchExpanded) Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    SearchBox(query, { query = it }, "姓名、备注或 UID")
                }
            }
        }
        // 错误区域放在列表外，避免异步状态改变字母索引偏移。
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AddressError(error, onRefresh)
        AddressError(unreadError, onRefresh)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val letters = remember { listOf("↑") + ('A'..'Z').map(Char::toString) + "#" }
            val indexWidth = with(LocalDensity.current) { (12.sp.toDp() + 8.dp).coerceAtLeast(20.dp) }
            val labelHeight = with(LocalDensity.current) { 14.sp.toDp().value + 4f }
            val labelStep = ceil(labelHeight * letters.size / (maxHeight.value.coerceAtMost(560f) - 16f)
                .coerceAtLeast(1f)).toInt().coerceAtLeast(1)
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize().testTag("address_list"),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "applications") {
                    AddressShortcut("新的朋友", R.drawable.ic_common_addfriend, Color(0xFFFFA032), unread,
                        onRequests, endPadding = indexWidth + 12.dp)
                }
                item(key = "groups") {
                    AddressShortcut("群聊", R.drawable.ic_common_address, Color(0xFF08BC68), 0,
                        onGroups, endPadding = indexWidth + 12.dp)
                }
                groups.forEach { (letter, rows) ->
                    item(key = "letter-$letter") {
                        Text(
                            letter,
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("address_section_$letter"),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(rows, key = { "friend-${it.uid}" }) { friend ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onFriend(friend) }
                                .padding(start = 16.dp).testTag("address_friend_${friend.uid}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(friend.uid.toString(), friend.displayName, 44.dp, avatarRepository)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Box(Modifier.heightIn(min = 64.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    Text(friend.displayName,
                                        Modifier.padding(end = indexWidth + 12.dp).testTag("address_name_${friend.uid}"),
                                        fontSize = 17.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
                item(key = "count") {
                    Text(
                        if (groups.isEmpty()) {
                            if (loading) "正在读取联系人" else if (error != null) "联系人暂不可用"
                            else if (query.isBlank()) "暂无联系人" else "没有匹配的联系人"
                        } else "${groups.values.sumOf { it.size }} 位联系人",
                        Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = indexVisible,
                modifier = Modifier.align(Alignment.CenterEnd).width(indexWidth)
                    .heightIn(max = 560.dp).fillMaxHeight(),
                enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it },
            ) {
                Column(
                    Modifier.fillMaxSize().testTag("address_index")
                        .padding(vertical = 8.dp).pointerInput(indexes) {
                            try {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // 将当前纵坐标映射到固定索引槽，仅存在的分组触发滚动。
                                    fun jump(y: Float) {
                                        val letter = letters[(y / size.height.coerceAtLeast(1) * letters.size).toInt()
                                            .coerceIn(letters.indices)]
                                        activeLetter = letter
                                        indexes[letter]?.let { listState.requestScrollToItem(it) }
                                    }
                                    down.consume()
                                    jump(down.position.y)
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { change ->
                                            if (change.pressed) jump(change.position.y)
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    activeLetter = null
                                    indexInteraction++
                                }
                            } finally {
                                activeLetter = null
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    letters.forEachIndexed { index, letter ->
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .semantics { contentDescription = if (letter == "↑") "返回通讯录顶部" else "跳到 $letter" }
                                .clickable(enabled = letter in indexes) {
                                    indexVisible = true
                                    indexInteraction++
                                    indexes[letter]?.let { listState.requestScrollToItem(it) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 短横屏只稀疏绘制标签，全部索引仍可拖动和无障碍选择。
                            if (index == letters.lastIndex || index % labelStep == 0 && index <= letters.lastIndex - labelStep) {
                                Text(letter, Modifier.requiredHeight(labelHeight.dp).wrapContentHeight(),
                                    fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
                                    color = if (letter in indexes) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
            activeLetter?.let {
                Surface(Modifier.align(Alignment.Center).size(64.dp), shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface) {
                    Box(contentAlignment = Alignment.Center) { Text(it, fontSize = 28.sp) }
                }
            }
        }
    }
}

/** 固定入口使用仓库原有图标与原生头像外框，不添加扩展图标依赖。 */
@Composable
private fun AddressShortcut(
    title: String,
    icon: Int,
    color: Color,
    unread: Int,
    onClick: () -> Unit,
    endPadding: Dp,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = RoundedCornerShape(5.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(27.dp), tint = Color.White)
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(Modifier.heightIn(min = 64.dp).fillMaxWidth().padding(end = endPadding),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 17.sp)
                if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        }
    }
}

/** 可恢复错误始终可见；重试只发起调用方提供的当前授权读取。 */
@Composable
internal fun AddressError(error: String?, onRetry: () -> Unit) {
    if (error == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        ToolButton(Icons.Default.Refresh, "重试", onClick = onRetry)
    }
}
