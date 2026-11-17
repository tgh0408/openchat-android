package com.im.openchat.address.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.address.data.*
import com.im.openchat.chat.data.RoomType
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.PageHeader
import com.im.openchat.common.ui.LocalPageNavigation
import com.im.openchat.common.ui.LocalPageBack
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** 分页读取双向好友申请；列表打开不确认已读，刷新覆盖已加载页并保护已确认终态。 */
@Composable
internal fun FriendApplicationsPage(
    book: AddressBookState,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenRoom: suspend (Long, RoomType, Long?) -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit = {},
) {
    val navigate = LocalPageNavigation.current
    val back = LocalPageBack.current
    var records by remember { mutableStateOf<List<FriendApplication>>(emptyList()) }
    var pages by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedReset by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val requests = remember { Channel<Boolean>(Channel.CONFLATED) }
    LaunchedEffect(book.revision) { requests.trySend(true) }
    LaunchedEffect(book) {
        for (reset in requests) {
            val version = book.revision
            loading = true
            error = null
            failedReset = reset
            try {
                val target = if (reset) pages.coerceAtLeast(1) else pages + 1
                val merged: MutableMap<Long, FriendApplication> =
                    if (reset) linkedMapOf() else records.associateBy { it.applyId }.toMutableMap()
                var completed = pages
                var isLast = last
                for (page in (if (reset) 1 else target)..target) {
                    val response = book.repository.listApplications(page)
                    response.list.forEach { merged[it.applyId] = it }
                    completed = page
                    isLast = response.isLast
                    if (isLast) break
                }
                if (version != book.revision) continue
                val previous = records.associateBy { it.applyId }
                records = merged.values.map { item ->
                    val known = previous[item.applyId]
                    if (known != null && known.status != ApplicationStatus.PENDING && item.status == ApplicationStatus.PENDING) {
                        item.copy(status = known.status)
                    } else item
                }
                pages = completed
                last = isLast
            } catch (exception: CancellationException) { throw exception }
            catch (exception: IOException) {
                if (version == book.revision) error = exception.message ?: "好友申请读取失败"
            }
            finally { loading = false }
        }
    }
    val current = records.firstOrNull { it.applyId == selected }
    BackHandler(adding) { back { adding = false } }
    BackHandler(selected != null) { back { selected = null } }
    if (adding) {
        AddFriendPage(
            book, avatarRepository, onBack = { back { adding = false } },
            onOpenRoom = onOpenRoom, onChanged = onChanged,
            onUserProfile = onUserProfile,
        )
        return
    }
    if (current != null) {
        key(current.applyId) {
            FriendApplicationDetail(
                current, book, avatarRepository, onBack = { back { selected = null } },
                onUserProfile = onUserProfile,
                onUpdate = { transform ->
                    records = records.map { if (it.applyId == current.applyId) transform(it) else it }
                    onChanged()
                },
            )
        }
        return
    }
    FriendApplicationsList(
        records = records, unread = book.unread, avatarRepository = avatarRepository,
        query = query, onQuery = { query = it }, listState = listState,
        loading = loading, last = last, error = error, unreadError = book.unreadError,
        onBack = onBack, onAdd = { navigate { adding = true } },
        onSelect = { navigate { selected = it.applyId } },
        onRefresh = { if (!loading) requests.trySend(true) },
        onRetry = { if (!loading) requests.trySend(failedReset) },
        onUnreadRetry = book::refresh,
        onLoadMore = { if (!loading) requests.trySend(false) },
        onUserProfile = onUserProfile,
    )
}

/** 单条申请详情确认已读，回复和审批均需用户显式提交；写请求不自动重试。 */
@Composable
private fun FriendApplicationDetail(
    application: FriendApplication,
    book: AddressBookState,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onUpdate: ((FriendApplication) -> FriendApplication) -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit,
) {
    var reply by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var readAttempt by remember { mutableIntStateOf(0) }
    val action = rememberAddressAction()
    LaunchedEffect(application.applyId, readAttempt) {
        readError = null
        try {
            book.repository.readApplication(application.applyId)
            book.refresh()
        } catch (exception: CancellationException) { throw exception }
        catch (exception: IOException) { readError = exception.message ?: "申请已读确认失败" }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("好友申请", onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    onUserProfile(UserProfileTarget(application.peerUid, application.name))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(application.peerUid.toString(), application.name, 56.dp, avatarRepository)
                Text(application.name, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleLarge)
            }
            Text(applicationStatusLabel(application), color = MaterialTheme.colorScheme.onSurfaceVariant)
            application.msg.forEach { message ->
                Text("${if (message.uid == book.repository.ownerUid) "我" else application.name}：${message.msg}", fontSize = 14.sp)
            }
            AddressError(readError) { readAttempt++ }
            action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (application.status == ApplicationStatus.PENDING) {
                OutlinedTextField(reply, { if (it.length <= 200) reply = it }, Modifier.fillMaxWidth(),
                    label = { Text("回复内容") }, maxLines = 4, enabled = !action.busy)
                TextButton(enabled = reply.isNotBlank() && !action.busy, onClick = {
                    val text = reply.trim()
                    action.launch {
                        book.repository.reply(application, text)
                        reply = ""
                        onUpdate { latest ->
                            if (latest.msg == application.msg) {
                                latest.copy(msg = latest.msg + ApplicationMessage(book.repository.ownerUid, text))
                            } else latest
                        }
                    }
                }) { Text("发送回复") }
            }
            if (application.canApprove) Button(enabled = !action.busy, onClick = { confirm = true }) { Text("接受申请") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!action.busy) confirm = false },
        title = { Text("接受好友申请") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将 ${application.name} 添加为好友？")
                action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !action.busy, onClick = {
                action.launch {
                    book.repository.approve(application)
                    confirm = false
                    onUpdate { it.copy(status = ApplicationStatus.ACCEPTED) }
                }
            }) { Text(if (action.busy) "正在提交" else "接受") }
        },
        dismissButton = { TextButton(enabled = !action.busy, onClick = { confirm = false }) { Text("取消") } },
    )
}
