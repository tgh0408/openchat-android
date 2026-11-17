package com.im.openchat.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.chat.data.Conversation
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.rememberAvatarProfiles
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** 离开页面时取消写入任务；开关只反映已确认的状态。 */
@Composable
internal fun FriendDetailsRoute(
    conversation: Conversation,
    avatarRepository: AvatarRepository?,
    onBack: () -> Unit,
    onPinned: suspend (Boolean) -> Unit,
    onMuted: suspend (Boolean) -> Unit,
    onUnavailable: (String) -> Unit,
    onUserProfile: (UserProfileTarget) -> Unit = {},
    covered: Boolean = false,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val uid = conversation.participantId.orEmpty()
    val profiles by rememberAvatarProfiles(listOf(uid), avatarRepository, conversation.contactId)
    val profile = profiles.firstOrNull()
    val name = conversation.title.ifBlank { profile?.name.orEmpty() }.ifBlank { "用户 $uid" }

    fun perform(action: suspend () -> Unit) {
        if (busy || covered) return
        busy = true
        error = null
        actionJob = scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                error = exception.message ?: "会话设置保存失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(covered) { if (covered) { actionJob?.cancel(); error = null } }
    BackHandler(enabled = !covered) { onBack() }
    FriendDetailsScreen(
        conversation = conversation, name = name, busy = busy, avatarRepository = avatarRepository,
        onBack = onBack, onProfile = {
            uid.toLongOrNull()?.takeIf { it > 0 }?.let {
                onUserProfile(UserProfileTarget(
                    it, name, conversation.avatar, conversation.contactId.toLongOrNull()?.takeIf { room -> room > 0 },
                ))
            }
        }, onAction = onUnavailable,
        onPinned = { enabled -> perform { onPinned(enabled) } },
        onMuted = { enabled -> perform { onMuted(enabled) } },
    )
    if (!covered) error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null }, title = { Text("操作未完成") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("确定") } },
        )
    }
}
