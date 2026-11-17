package com.im.openchat.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.im.openchat.address.data.AddressRepository
import com.im.openchat.address.data.DirectoryFriend
import com.im.openchat.chat.data.GroupDetailsRepository
import com.im.openchat.chat.data.GroupMember
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun GroupMemberPicker(
    repository: GroupDetailsRepository,
    addressRepository: AddressRepository,
    room: String,
    avatarRepository: AvatarRepository?,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    removing: Boolean = false,
) {
    var friends by remember { mutableStateOf<List<DirectoryFriend>>(emptyList()) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var excluded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository, room, retry, removing) {
        loading = true
        loadError = null
        submitError = null
        try {
            if (removing) {
                val current = repository.members(room)
                val operator = current.firstOrNull { it.uid == repository.ownerUid }
                    ?.takeIf { it.canManage } ?: throw IOException("当前已无管理员权限")
                val nextExcluded = current.filterNot(operator::canRemove).map { it.uid }.toSet()
                groupMembers = current
                friends = current.map {
                    DirectoryFriend(
                        uid = it.uid, name = it.username, displayName = it.label, indexLetter = "#",
                    )
                }
                excluded = nextExcluded
                selected = selected.intersect(current.map { it.uid }.toSet()) - nextExcluded
            } else coroutineScope {
                val contacts = async { addressRepository.listFriends() }
                val members = async { repository.members(room) }
                val nextFriends = contacts.await()
                val nextExcluded = members.await().map { it.uid }.toSet() + repository.ownerUid
                friends = nextFriends
                excluded = nextExcluded
                selected = selected.intersect(nextFriends.map { it.uid }.toSet()) - nextExcluded
            }
        } catch (exception: CancellationException) { throw exception }
        catch (exception: IOException) { loadError = exception.message ?: "联系人读取失败" }
        finally { loading = false }
    }
    fun select(uid: Long) {
        if (busy || loading || loadError != null || uid in excluded) return
        if (uid in selected) selected = selected - uid
        else if (selected.size < 100) selected = selected + uid
    }
    val canSubmit = !busy && !loading && loadError == null && selected.isNotEmpty() &&
        (!removing || groupMembers.firstOrNull { it.uid == repository.ownerUid }?.let { operator ->
            operator.canManage && selected.all { uid ->
                groupMembers.firstOrNull { it.uid == uid }?.let(operator::canRemove) == true
            }
        } == true)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${if (removing) "移出群成员" else "添加群成员"} (${selected.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it.take(128) }, label = { Text(if (removing) "搜索群成员" else "搜索好友") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                loadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
                submitError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (removing) TextButton(enabled = !busy && !loading, onClick = { retry++ }) {
                        Text("刷新群成员")
                    }
                }
                val visible = friends.filter {
                    query.isBlank() || it.displayName.contains(query.trim(), true) ||
                        it.name.contains(query.trim(), true) || it.uid.toString().contains(query.trim())
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(visible, key = { it.uid }) { friend ->
                        Row(
                            Modifier.fillMaxWidth().testTag("group_member_option_${friend.uid}").clickable(
                                enabled = !busy && !loading && loadError == null && friend.uid !in excluded,
                            ) { select(friend.uid) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                friend.uid.toString(), friend.displayName, 36.dp, avatarRepository,
                                roomId = room.takeIf { removing },
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(friend.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (friend.uid in excluded) Text(
                                    if (removing) "不可移出" else "已在群聊",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Checkbox(
                                modifier = Modifier.testTag("group_member_checkbox_${friend.uid}"),
                                checked = friend.uid in selected, onCheckedChange = { select(friend.uid) },
                                enabled = !busy && !loading && loadError == null && friend.uid !in excluded &&
                                    (selected.size < 100 || friend.uid in selected),
                            )
                        }
                    }
                    if (visible.isEmpty() && !loading && loadError == null) item {
                        Text(if (removing) "暂无匹配的群成员" else "暂无可选好友")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    busy = true
                    submitError = null
                    val uids = selected.toList()
                    scope.launch {
                        try {
                            if (removing) repository.removeMembers(room, uids) else repository.addMembers(room, uids)
                            onCompleted()
                        } catch (exception: CancellationException) { throw exception }
                        catch (exception: IOException) {
                            submitError = exception.message ?: if (removing) "移出失败，请重试" else "添加失败，请重试"
                        }
                        finally { busy = false }
                    }
                },
            ) {
                Text(if (removing) {
                    if (busy) "正在移出" else "移出"
                } else if (busy) "正在添加" else "添加")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
