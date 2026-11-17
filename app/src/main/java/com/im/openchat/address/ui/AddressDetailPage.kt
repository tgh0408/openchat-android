package com.im.openchat.address.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.address.data.*
import com.im.openchat.chat.data.RoomType
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 通讯录子页共享导航，业务状态随当前授权和页面销毁，不创建本地联系人。 */
@Composable
internal fun AddressDetailPage(
    page: String,
    book: AddressBookState,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onOpenRoom: suspend (Long, RoomType, Long?) -> Unit,
    onChanged: () -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit,
) {
    when (page) {
        "新的朋友" -> FriendApplicationsPage(book, avatarRepository, onBack, onChanged, onOpenRoom, onUserProfile)
        "添加朋友" -> AddFriendPage(book, avatarRepository, onBack, onOpenRoom, onChanged, onUserProfile)
        "群聊" -> DirectoryGroupsPage(book, avatarRepository, onBack, onOpenRoom)
    }
}

/** 页面内有副作用的命令一次只执行一个；网络异常保留页面和草稿，不自动重试。 */
internal class AddressAction(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    /** 同步锁定按钮后启动可取消任务，普通失败仅更新本页面提示。 */
    fun launch(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        job = scope.launch {
            try { block() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: IOException) { error = exception.message ?: "请求失败，请重试" }
            finally { busy = false }
        }
    }

    /** 群资料的内部返回不卸载整页，必须显式取消正在等待的进入请求。 */
    fun cancel() { job?.cancel(); error = null }
}

/** 命令状态绑定当前组合树，离开详情即取消等待。 */
@Composable
internal fun rememberAddressAction(): AddressAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { AddressAction(scope) }
}

/** 群聊目录独立页面，按 PC 名称/前九位成员生成默认名称，点击资料后才进入聊天。 */
@Composable
private fun DirectoryGroupsPage(
    book: AddressBookState,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onOpenRoom: suspend (Long, RoomType, Long?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<DirectoryGroup?>(null) }
    val navigate = LocalPageNavigation.current
    val back = LocalPageBack.current
    val action = rememberAddressAction()
    var names by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(book.groups, avatarRepository, query.isNotBlank(), selected) {
        if (query.isBlank() || selected != null) return@LaunchedEffect
        // 搜索未命名群时只补名称资料，不批量下载头像图片。
        for (row in book.groups.filter { it.name.isBlank() }) {
            val memberNames = row.avatarUidList.orEmpty().map {
                avatarRepository?.load(it.toString(), row.roomId.toString())?.name
            }
            names = names + (row.roomId to directoryGroupName(row, memberNames))
        }
    }
    BackHandler(selected != null) { back { action.cancel(); selected = null } }
    LaunchedEffect(book) { book.refresh() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("群聊") {
            if (selected != null) back { action.cancel(); selected = null }
            else { action.cancel(); onBack() }
        }
        val group = selected
        if (group != null) {
            val profiles by rememberAvatarProfiles(group.avatarUidList.orEmpty().map(Long::toString), avatarRepository, group.roomId.toString())
            val name = directoryGroupName(group, profiles.map { it?.name })
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupAvatar(group.avatarUidList.orEmpty().map(Long::toString), name, avatarRepository, group.roomId.toString(), 64.dp)
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text("${group.memberCount} 位成员", fontSize = 14.sp)
                action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Button(enabled = !action.busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { action.launch { onOpenRoom(group.roomId, RoomType.GROUP, null) } }) {
                    Text(if (action.busy) "正在进入" else "进入群聊")
                }
            }
        } else {
            Box(Modifier.padding(16.dp)) { SearchBox(query, { query = it }, "搜索群聊") }
            AddressError(book.groupsError, book::refresh)
            if (book.groupsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val visible = book.groups.filter {
                query.isBlank() || (it.name.takeIf(String::isNotBlank) ?: names[it.roomId].orEmpty())
                    .contains(query.trim(), true)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.roomId }) { row ->
                    val profiles by rememberAvatarProfiles(
                        row.avatarUidList.orEmpty().map(Long::toString), avatarRepository, row.roomId.toString(),
                    )
                    val name = directoryGroupName(row, profiles.map { it?.name })
                    SideEffect {
                        if (profiles.any { it != null } && names[row.roomId] != name) names = names + (row.roomId to name)
                    }
                    Row(Modifier.fillMaxWidth().clickable { navigate { selected = row } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GroupAvatar(row.avatarUidList.orEmpty().map(Long::toString), name, avatarRepository, row.roomId.toString())
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                            Text("${row.memberCount} 位成员", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (visible.isEmpty() && !book.groupsLoading && book.groupsError == null) item {
                    Text(if (query.isBlank()) "暂无群聊" else "没有匹配的群聊", Modifier.padding(24.dp))
                }
            }
        }
    }
}

/** PC 默认群名只取前九位成员公开名，不更改原始群名。 */
internal fun directoryGroupName(group: DirectoryGroup, names: List<String?>): String =
    group.name.takeIf(String::isNotBlank) ?: names.take(9).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(",").ifBlank { "群聊" }

/** 添加朋友通过真实搜索确认关系，再明确提交申请，完全替代本地联系人表单。 */
@Composable
internal fun AddFriendPage(
    book: AddressBookState,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onOpenRoom: suspend (Long, RoomType, Long?) -> Unit,
    onChanged: () -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<DirectorySearchResult?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var remark by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    val action = rememberAddressAction()
    Column(Modifier.fillMaxSize()) {
        PageHeader("添加朋友", onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, {
                        if (it.length <= 128 && it != query) {
                            query = it
                            result = null
                            notice = null
                            submitted = false
                            message = ""
                            remark = ""
                        }
                    },
                    Modifier.weight(1f), singleLine = true, enabled = !action.busy,
                    label = { Text("OpenChat 号或手机号") },
                )
                ToolButton(Icons.Default.Search, "查找用户", enabled = !action.busy && query.isNotBlank(), onClick = {
                    result = null
                    notice = null
                    submitted = false
                    action.launch { result = book.repository.search(query) }
                })
            }
            if (action.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            result?.let { found ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onUserProfile(UserProfileTarget(
                            found.profile.uid, found.profile.name, found.profile.avatar.orEmpty(), friend = found.friend,
                        ))
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(found.profile.uid.toString(), found.profile.name, repository = avatarRepository,
                        avatar = found.profile.avatar.orEmpty())
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(found.profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("UID: ${found.profile.uid}", fontSize = 12.sp)
                    }
                }
                when {
                    found.self -> Text("这是你自己")
                    found.friend != null -> Button(enabled = !action.busy, onClick = {
                        action.launch {
                            val profile = book.repository.profile(found.friend)
                            val room = profile.roomId ?: throw IOException("该好友暂不可聊天")
                            onOpenRoom(room, RoomType.FRIEND, profile.uid)
                        }
                    }) { Text("发消息") }
                    else -> {
                        OutlinedTextField(
                            message, { if (it.length <= 200) message = it }, Modifier.fillMaxWidth(),
                            label = { Text("申请说明") }, maxLines = 4, enabled = !action.busy && !submitted,
                        )
                        OutlinedTextField(
                            remark, { if (it.length <= 64) remark = it }, Modifier.fillMaxWidth(),
                            label = { Text("备注") }, singleLine = true, enabled = !action.busy && !submitted,
                        )
                        Button(enabled = !action.busy && !submitted && message.isNotBlank(), onClick = {
                            action.launch {
                                val added = book.repository.apply(found.profile.uid, message, remark)
                                submitted = true
                                notice = if (added == null) "申请已发送" else "已添加"
                                if (added != null) result = found.copy(friend = DirectoryFriend(
                                    added.uid, added.roomId, found.profile.name, remark.trim().takeIf(String::isNotBlank),
                                    remark.trim().ifBlank { found.profile.name }, added.indexLetter,
                                ))
                                onChanged()
                            }
                        }) { Text("发送申请") }
                    }
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
