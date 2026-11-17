package com.im.openchat.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.im.openchat.chat.data.Conversation

/**
 * 展示会话管理菜单，无返回值；交互只触发回调，不直接修改数据。
 *
 * @param title 会话对应的联系人名称。
 * @param conversation 当前会话，用于显示置顶和免打扰状态。
 * @param onDismiss 关闭菜单时触发。
 * @param onTogglePin 点击置顶选项时触发。
 * @param onToggleMute 点击免打扰选项时触发。
 * @param onDelete 点击删除选项时触发，由调用方打开确认框。
 */
@Composable
internal fun ConversationMenuDialog(
    title: String,
    conversation: Conversation,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextButton(onClick = onTogglePin) {
                    Icon(Icons.Default.Star, null); Spacer(Modifier.width(12.dp))
                    Text(if (conversation.pinned) "取消置顶" else "置顶会话")
                }
                TextButton(onClick = onToggleMute) {
                    Icon(Icons.Default.Notifications, null); Spacer(Modifier.width(12.dp))
                    Text(if (conversation.muted) "关闭免打扰" else "消息免打扰")
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, null); Spacer(Modifier.width(12.dp)); Text("删除会话")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 展示删除会话确认框，无返回值；不直接删除数据，仅转发确认或取消事件。
 *
 * @param onDismiss 取消或关闭确认框时触发。
 * @param onConfirm 确认删除时触发，由调用方删除本地会话并持久化。
 */
@Composable
internal fun DeleteConversationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除会话？") },
        text = { Text("此会话及本机聊天记录将被删除，联系人会保留。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 展示尚未接入功能的提示，无返回值，不请求权限、修改会话或发起网络请求。
 *
 * @param action 用户选择的功能名称。
 * @param onDismiss 关闭提示时触发。
 */
@Composable
internal fun UnavailableChatActionDialog(action: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action) },
        text = {
            Text(
                when (action) {
                    "语音通话" -> "语音通话尚未接入服务端，当前不会发起真实通话。"
                    "群视频通话" -> "群通话尚未接入，当前不会发起真实通话或发送邀请。"
                    "语音输入", "语音消息", "表情", "视频通话", "图片", "文件" ->
                        "${action}尚未接入，当前不会录音、上传或发送内容。"
                    "发起群聊", "添加聊天成员" -> "群聊创建尚未接入，当前不会创建群聊或发送邀请。"
                    "清空聊天记录" -> "聊天记录清理尚未接入，当前不会删除本机或远端聊天记录。"
                    "扫一扫" -> "扫码功能尚未接入，当前不会打开相机。"
                    else -> "${action}尚未接入服务端，当前不会修改远端数据。"
                },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
