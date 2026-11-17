package com.im.openchat.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R
import com.im.openchat.chat.data.MessageRtcCallState
import com.im.openchat.chat.data.MessageRtcMediaMode
import com.im.openchat.chat.data.RtcCallMessageBody

@Composable
internal fun ChatRtcCall(
    body: RtcCallMessageBody,
    ownUid: String,
    onVideoCall: (() -> Unit)? = null,
    onAudioCall: (() -> Unit)? = null,
) {
    val mode = body.extra?.mediaMode
    val canRedial = when (mode) {
        MessageRtcMediaMode.VIDEO -> onVideoCall != null
        MessageRtcMediaMode.AUDIO -> onAudioCall != null
        null -> false
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(
                enabled = canRedial,
                role = Role.Button,
                onClickLabel = if (mode == MessageRtcMediaMode.AUDIO) "快速回拨语音通话" else "快速回拨视频通话",
                onClick = {
                    if (mode == MessageRtcMediaMode.AUDIO) onAudioCall?.invoke()
                    else onVideoCall?.invoke()
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (mode != null) {
            Icon(
                imageVector = if (mode == MessageRtcMediaMode.VIDEO) {
                    ImageVector.vectorResource(R.drawable.ic_chat_video_call)
                } else Icons.Default.Phone,
                contentDescription = if (mode == MessageRtcMediaMode.VIDEO) {
                    "视频通话"
                } else "语音通话",
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = rtcCallLabel(body, ownUid),
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
    }
}

/** 摘要消息的发送者是呼叫方，不一定是结束通话的一方。 */
internal fun rtcCallLabel(body: RtcCallMessageBody, ownUid: String): String {
    val call = body.extra ?: return "通话记录"
    val actor = call.endedByUid?.takeIf { it > 0 }
    val self = ownUid.toLongOrNull()?.takeIf { it > 0 }
    val actorLabel = when {
        actor == null || self == null -> ""
        actor == self -> "你"
        else -> "对方"
    }
    return when (call.state) {
        MessageRtcCallState.REJECTED -> "${actorLabel}已拒绝"
        MessageRtcCallState.CANCELED -> "${actorLabel}已取消"
        MessageRtcCallState.EXPIRED -> "对方无响应"
        MessageRtcCallState.ENDED -> {
            val seconds = call.durationSeconds?.takeIf { it >= 0 }
                ?: return "通话已结束"
            val minutes = (seconds / 60).toString().padStart(2, '0')
            val remainder = (seconds % 60).toString().padStart(2, '0')
            "通话时长 $minutes:$remainder"
        }
        else -> "通话记录"
    }
}
