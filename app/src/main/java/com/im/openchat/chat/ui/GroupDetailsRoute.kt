package com.im.openchat.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.im.openchat.address.data.AddressRepository
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.GroupDetails
import com.im.openchat.chat.data.GroupDetailsRepository
import com.im.openchat.chat.data.GroupMember
import com.im.openchat.common.data.AvatarRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** 所有详情读取和对话框任务都属于当前房间的可见组合。 */
@Composable
internal fun GroupDetailsRoute(
    conversation: Conversation,
    repository: GroupDetailsRepository,
    addressRepository: AddressRepository,
    avatarRepository: AvatarRepository?,
    imageNamespace: String,
    refreshVersion: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onPinned: suspend (Boolean) -> Unit,
    onMuted: suspend (Boolean) -> Unit,
    onExit: suspend () -> Unit,
    onUnavailable: (String) -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit = {},
    covered: Boolean = false,
) {
    var details by remember { mutableStateOf<GroupDetails?>(null) }
    var members by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val room = conversation.contactId
    val canManage = !loading && error == null && members.firstOrNull { it.uid == repository.ownerUid }?.canManage == true

    LaunchedEffect(repository, room, retry, refreshVersion, covered) {
        if (covered) return@LaunchedEffect
        loading = true
        error = null
        try {
            coroutineScope {
                val info = async { repository.details(room) }
                val people = async { repository.members(room) }
                val nextInfo = info.await()
                val nextMembers = people.await()
                details = nextInfo
                members = nextMembers
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            error = exception.message ?: "群信息读取失败，请重试"
        } finally {
            loading = false
        }
    }

    fun perform(action: suspend () -> Unit) {
        if (busy || covered) return
        busy = true
        actionError = null
        actionJob = scope.launch {
            try { action() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: IOException) { actionError = exception.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }

    LaunchedEffect(covered) {
        if (covered) { actionJob?.cancel(); dialog = null; actionError = null }
    }
    BackHandler(enabled = !covered) { onBack() }
    GroupDetailsScreen(
        conversation = conversation, details = details, members = members, ownUid = repository.ownerUid,
        loading = loading, error = error, busy = busy, avatarRepository = avatarRepository,
        onBack = onBack, onRetry = { retry++ },
        onAction = { action ->
            when (action) {
                "群聊名称", "群公告", "群二维码" -> {
                    if (details != null) { actionError = null; dialog = action }
                    else { actionError = "请先读取群信息，再重试"; retry++ }
                }
                "添加群成员", "退出群聊" -> { actionError = null; dialog = action }
                "移出群成员" -> if (canManage && !busy) { actionError = null; dialog = action }
                else -> onUnavailable(action)
            }
        },
        onMember = {
            onUserProfile(UserProfileTarget(it.uid, it.label, sourceRoomId = room.toLong()))
        },
        onPinned = { enabled -> perform { onPinned(enabled) } },
        onMuted = { enabled -> perform { onMuted(enabled) } },
    )
    val closeDialog = { if (!busy) { dialog = null; actionError = null }; Unit }
    when (dialog) {
        "群聊名称", "群公告" -> {
            val editingName = dialog == "群聊名称"
            GroupTextDialog(
                title = dialog!!,
                value = if (editingName) details?.name.orEmpty() else details?.notice?.description.orEmpty(),
                editable = canManage, maxLength = if (editingName) 256 else 300,
                busy = busy, error = actionError, onDismiss = closeDialog,
                onSave = { text ->
                    if (canManage) perform {
                        val updated = repository.update(
                            room, name = text.takeIf { editingName }, notice = text.takeUnless { editingName },
                        )
                        details = updated.copy(memberCount = updated.memberCount ?: details?.memberCount)
                        dialog = null
                        retry++
                        onChanged()
                    }
                },
            )
        }
        "群二维码" -> GroupQrDialog(details?.qrCode.orEmpty(), imageNamespace, closeDialog)
        "添加群成员", "移出群成员" -> key(dialog) {
            GroupMemberPicker(
                repository = repository, addressRepository = addressRepository, room = room,
                avatarRepository = avatarRepository, onDismiss = { dialog = null },
                onCompleted = { dialog = null; retry++; onChanged() },
                removing = dialog == "移出群成员",
            )
        }
        "退出群聊" -> AlertDialog(
            onDismissRequest = closeDialog,
            title = { Text("退出群聊") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("退出后将无法接收此群的新消息。确定退出？")
                    if (members.firstOrNull { it.uid == repository.ownerUid }?.role == 1) {
                        Text("群主不能直接退出群聊。", color = MaterialTheme.colorScheme.error)
                    }
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && !loading && error == null &&
                        members.any { it.uid == repository.ownerUid && it.role != 1 },
                    onClick = { perform { onExit() } },
                ) { Text(if (busy) "正在退出" else "退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = closeDialog) { Text("取消") } },
        )
    }
    if (dialog == null) actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null }, title = { Text("操作未完成") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text("确定") } },
        )
    }
}

@Composable
private fun GroupTextDialog(
    title: String,
    value: String,
    editable: Boolean,
    maxLength: Int,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(title) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editable) OutlinedTextField(
                    value = draft, onValueChange = { if (it.length <= maxLength) draft = it },
                    enabled = !busy, label = { Text(title) }, modifier = Modifier.fillMaxWidth(),
                    minLines = if (title == "群公告") 4 else 1, maxLines = 8,
                    supportingText = { Text("${draft.length}/$maxLength") },
                ) else Text(value.ifBlank { if (title == "群公告") "暂无群公告" else "未设置群名称" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!editable || draft.isNotBlank()),
                onClick = { if (editable) onSave(draft.trim()) else onDismiss() },
            ) { Text(if (!editable) "关闭" else if (busy) "正在保存" else "保存") }
        },
        dismissButton = {
            if (editable) TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}
