package com.im.openchat.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.im.openchat.R

@Composable
internal fun ChatCallPicker(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.6f)
        }
        val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val surface = if (light) Color.White else MaterialTheme.colorScheme.surface
        val separator = if (light) Color(0xFFF5F5F5) else MaterialTheme.colorScheme.surfaceContainerLow
        BoxWithConstraints(Modifier.fillMaxSize().testTag("chat_call_picker")) {
            Box(
                Modifier.matchParentSize().testTag("chat_call_scrim")
                    .semantics { contentDescription = "取消通话选择" }
                    .clickable(
                        interactionSource = null, indication = null,
                        onClickLabel = "取消", onClick = onDismiss,
                    ),
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.8f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .testTag("chat_call_sheet"),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                color = surface,
            ) {
                Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                    ChatCallOption(
                        "视频通话", ImageVector.vectorResource(R.drawable.ic_chat_video_call),
                        onClick = { onSelect("video") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ChatCallOption("语音通话", Icons.Default.Phone, onClick = { onSelect("audio") })
                    Spacer(Modifier.fillMaxWidth().height(8.dp).background(separator))
                    ChatCallOption("取消", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ChatCallOption(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(
            label, modifier = Modifier.weight(1f, fill = false),
            fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
        )
    }
}
