package com.im.openchat.address.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.im.openchat.address.data.AddressRepository
import com.im.openchat.address.data.DirectoryProfile
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.chat.data.RoomType
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.LocalPageBack
import com.im.openchat.common.ui.LocalPageNavigation
import com.im.openchat.common.ui.LocalPageTransitionRunning
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** 所有资料入口共用本页；离页或换人会取消读取、申请及进入会话任务。 */
@Composable
internal fun UserProfileRoute(
    repository: AddressRepository,
    target: UserProfileTarget,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onOpenRoom: suspend (Long, RoomType, Long?) -> Unit,
    onUnavailable: (String) -> Unit,
    onEditSelf: () -> Unit = { onUnavailable("个人资料") },
    onChanged: () -> Unit = {},
    onFriendRemoved: (Long) -> Unit = { onChanged() },
) = key(repository, target) {
    var profile by remember { mutableStateOf<DirectoryProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var isFriend by remember { mutableStateOf<Boolean?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var addSubmitted by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var remark by rememberSaveable { mutableStateOf("") }
    var friendDetailsOpen by remember { mutableStateOf(false) }
    var friendSettingsOpen by remember { mutableStateOf(false) }
    var settingsConfirmation by remember { mutableStateOf<FriendSettingsConfirmation?>(null) }
    var settingsNeedsRefresh by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    val action = rememberAddressAction()
    val navigate = LocalPageNavigation.current
    val pageBack = LocalPageBack.current
    val transitioning = LocalPageTransitionRunning.current
    val profileListState = rememberLazyListState()

    LaunchedEffect(retry) {
        loading = true
        error = null
        isFriend = null
        try {
            val self = target.uid == repository.ownerUid
            val friend = if (self) null else repository.listFriends().firstOrNull { it.uid == target.uid }
            // 已删除但被拉黑的关系可能仍保留房间号，只有好友目录才能确认好友关系。
            val boundRoom = profile?.roomId
            profile = if (!self && boundRoom != null) {
                // Deletion/unblocking can legitimately remove the returned direct room.
                repository.userProfile(target.copy(friend = null, sourceRoomId = boundRoom))
            } else {
                repository.userProfile(target.copy(
                    friend = friend?.takeIf { it.roomId != null && target.sourceRoomId == null },
                    sourceRoomId = if (self) null else target.sourceRoomId ?: target.friend?.roomId,
                ))
            }
            isFriend = friend != null
            if (settingsNeedsRefresh) {
                settingsNeedsRefresh = false
                if (friend == null || profile?.blacklisted == true) onFriendRemoved(target.uid)
                else onChanged()
            }
        }
        catch (exception: CancellationException) { throw exception }
        catch (exception: IOException) { error = exception.message ?: "用户资料读取失败" }
        finally { loading = false }
    }

    val isSelf = target.uid == repository.ownerUid
    val refreshSettings = {
        if (!action.busy && !deleted) {
            action.cancel()
            settingsConfirmation = null
            loading = true
            retry++
        }
    }
    val canManage = !isSelf && !loading && error == null && !settingsNeedsRefresh &&
        !deleted && (profile?.roomId ?: 0) > 0 && (isFriend == true || profile?.blacklisted == true)
    val changeBlacklist: (Boolean) -> Unit = { blocked ->
        val current = profile
        val room = current?.roomId
        if (canManage && !action.busy && !transitioning && current != null && room != null &&
            current.blacklisted != blocked
        ) action.launch {
            try {
                repository.setBlacklisted(current.uid, room, blocked)
                profile = current.copy(blacklisted = blocked)
                settingsConfirmation = null
                if (blocked) {
                    onFriendRemoved(current.uid)
                } else {
                    // Removing a blacklist never proves that a deleted friendship was restored.
                    isFriend = null
                    loading = true
                    retry++
                    onChanged()
                }
            } catch (exception: IOException) {
                settingsNeedsRefresh = true
                throw exception
            }
        }
    }
    val back = {
        val reconcile = friendSettingsOpen && (action.busy || settingsNeedsRefresh)
        action.cancel()
        settingsConfirmation = null
        if (reconcile && !deleted) {
            settingsNeedsRefresh = true
            loading = true
            retry++
        }
        if (friendSettingsOpen) {
            pageBack { friendSettingsOpen = false }
        } else if (friendDetailsOpen) {
            pageBack { friendDetailsOpen = false }
        } else {
            onBack()
        }
    }
    BackHandler(enabled = !transitioning, onBack = back)

    if (friendSettingsOpen) {
        FriendSettingsScreen(
            profile = profile,
            onBack = back,
            onUnavailable = onUnavailable,
            busy = loading || action.busy || deleted,
            error = error ?: action.error,
            canManage = canManage && !transitioning,
            canDelete = canManage && isFriend == true && !transitioning,
            onRefresh = refreshSettings,
            onBlacklistChange = { blocked ->
                if (canManage && !action.busy && !transitioning && settingsConfirmation == null) {
                    if (blocked) settingsConfirmation = FriendSettingsConfirmation.BLACKLIST
                    else changeBlacklist(false)
                }
            },
            onDelete = {
                if (canManage && isFriend == true && !action.busy && !transitioning &&
                    settingsConfirmation == null
                ) settingsConfirmation = FriendSettingsConfirmation.DELETE
            },
        )
    } else if (friendDetailsOpen) {
        FriendProfileDetailsScreen(
            profile = profile,
            onBack = back,
            onUnavailable = onUnavailable,
        )
    } else {
        UserProfileScreen(
            target = target,
            profile = profile,
            loading = loading,
            error = error,
            opening = action.busy,
            openError = action.error.takeUnless { addDialog },
            avatarRepository = avatarRepository,
            onBack = back,
            onRetry = { if (!loading) retry++ },
            onMessage = {
                if (!loading && error == null && profile != null && profile?.blacklisted != true &&
                    !isSelf && isFriend == true
                ) action.launch {
                    val room = repository.userMessageRoom(target.uid)
                    onOpenRoom(room, RoomType.FRIEND, target.uid)
                }
            },
            onUnavailable = {
                if (it == "资料设置" && isSelf) onEditSelf() else onUnavailable(it)
            },
            onFriendDetails = {
                if (!loading && error == null && profile != null && !action.busy) {
                    navigate { friendDetailsOpen = true }
                }
            },
            onFriendSettings = {
                if (isSelf) {
                    onEditSelf()
                } else if (!loading && error == null && profile != null &&
                    (isFriend == true || profile?.blacklisted == true) && !action.busy
                ) {
                    navigate { friendSettingsOpen = true }
                }
            },
            onAddFriend = {
                if (!loading && error == null && profile != null && profile?.blacklisted != true &&
                    !isSelf && isFriend == false
                ) addDialog = true
            },
            messageEnabled = profile != null && error == null && isFriend != null && !isSelf,
            addFriend = !isSelf && isFriend == false,
            addSubmitted = addSubmitted,
            listState = profileListState,
        )
    }

    val confirmation = settingsConfirmation
    if (friendSettingsOpen && confirmation != null) {
        val deleting = confirmation == FriendSettingsConfirmation.DELETE
        val name = profile?.remark?.takeIf(String::isNotBlank) ?: profile?.name.orEmpty()
        AlertDialog(
            modifier = Modifier.testTag("friend_settings_confirmation"),
            onDismissRequest = { if (!action.busy) settingsConfirmation = null },
            title = { Text(if (deleting) "删除朋友" else "加入黑名单") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (deleting) "确定删除“$name”？聊天记录会保留。"
                        else "确定将“$name”加入黑名单？",
                    )
                    action.error?.let {
                        Text(
                            it, Modifier.padding(top = 12.dp).testTag("friend_settings_confirmation_error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("friend_settings_confirm"),
                    enabled = !action.busy && !loading && !deleted && !transitioning &&
                        (settingsNeedsRefresh || canManage),
                    onClick = {
                        if (settingsNeedsRefresh) {
                            refreshSettings()
                        } else if (!deleting) {
                            changeBlacklist(true)
                        } else {
                            val current = profile
                            val room = current?.roomId
                            if (canManage && isFriend == true && current != null && room != null) action.launch {
                                try {
                                    repository.deleteFriend(current.uid, room)
                                    deleted = true
                                    isFriend = false
                                    settingsConfirmation = null
                                    onFriendRemoved(current.uid)
                                    onBack()
                                } catch (exception: IOException) {
                                    settingsNeedsRefresh = true
                                    throw exception
                                }
                            }
                        }
                    },
                ) { Text(if (settingsNeedsRefresh) "刷新状态" else if (deleting) "删除" else "确定") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("friend_settings_cancel"),
                    enabled = !action.busy,
                    onClick = { settingsConfirmation = null },
                ) { Text("取消") }
            },
        )
    }

    if (addDialog && profile != null) {
        AlertDialog(
            onDismissRequest = { if (!action.busy) addDialog = false },
            title = { Text("添加到通讯录") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { if (it.length <= 200) message = it },
                        modifier = Modifier.fillMaxWidth().testTag("profile_apply_message"),
                        label = { Text("申请说明") },
                        minLines = 3,
                        maxLines = 5,
                        enabled = !action.busy,
                    )
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { if (it.length <= 64) remark = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("profile_apply_remark"),
                        label = { Text("备注（可选）") },
                        singleLine = true,
                        enabled = !action.busy,
                    )
                    action.error?.let {
                        Text(it, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("profile_apply_submit"),
                    enabled = !action.busy && message.isNotBlank(),
                    onClick = {
                        action.launch {
                            if (repository.listFriends().any { it.uid == target.uid }) {
                                isFriend = true
                                addSubmitted = false
                                addDialog = false
                                onChanged()
                                return@launch
                            }
                            val added = repository.apply(target.uid, message, remark)
                            if (added == null) {
                                addSubmitted = true
                            } else {
                                isFriend = true
                                addSubmitted = false
                                profile = profile?.copy(
                                    roomId = added.roomId,
                                    remark = remark.trim().takeIf(String::isNotBlank),
                                )
                            }
                            addDialog = false
                            onChanged()
                        }
                    },
                ) { Text(if (action.busy) "正在发送" else "发送申请") }
            },
            dismissButton = {
                TextButton(enabled = !action.busy, onClick = { addDialog = false }) { Text("取消") }
            },
        )
    }
}

private enum class FriendSettingsConfirmation { BLACKLIST, DELETE }
