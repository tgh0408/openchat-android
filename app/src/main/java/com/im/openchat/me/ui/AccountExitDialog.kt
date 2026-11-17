package com.im.openchat.me.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal enum class AccountExitAction(val title: String) {
    SwitchAccount("切换账号"),
    SignOut("退出登录"),
}

@Composable
internal fun AccountExitDialog(
    action: AccountExitAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Text(
                when (action) {
                    AccountExitAction.SwitchAccount -> "退出当前账号并登录其他账号？本机聊天记录将保留。"
                    AccountExitAction.SignOut -> "确定退出当前账号？本机聊天记录将保留。"
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action.title) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
