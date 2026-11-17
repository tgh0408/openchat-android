package com.im.openchat.me.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 单行展示版本和更新入口，忽略版本操作放在更新内容弹窗中。 */
@Composable
internal fun AndroidUpdateBanner(state: AndroidUpdateState) {
    val release = state.release ?: return
    if (!state.showBanner) return
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp)
            .testTag("android_update_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "新版本 ${release.version}",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = state::updateNow,
            enabled = !state.savingIgnore,
            modifier = Modifier.weight(0.85f),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text("立即更新", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            onClick = state::showDetails,
            enabled = !state.savingIgnore,
            modifier = Modifier.weight(1.2f),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text("查看更新内容", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
