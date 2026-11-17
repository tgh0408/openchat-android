package com.im.openchat.chat.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoPlaybackControls(
    wantsToPlay: Boolean,
    canPlay: Boolean,
    failed: Boolean,
    position: Long,
    duration: Long,
    floating: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val seekEnabled = canPlay && duration > 0 && !failed
    var seek by remember(seekEnabled) { mutableStateOf<Float?>(null) }
    val fraction = seek ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val displayedPosition = seek?.let { (it * duration).toLong() } ?: position
    val label = when {
        failed -> "重试播放"
        wantsToPlay -> "暂停"
        else -> "播放"
    }
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color(0xFF808080),
        disabledThumbColor = Color(0xFF808080),
        disabledActiveTrackColor = Color(0xFF808080),
        disabledInactiveTrackColor = Color(0xFF454545),
    )
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (floating) 4.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (floating) 4.dp else 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = onPlayPause,
                enabled = canPlay,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color(0xFF808080),
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            ) {
                Icon(
                    when {
                        failed -> Icons.Default.Refresh
                        wantsToPlay -> ImageVector.vectorResource(R.drawable.ic_video_pause)
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = label,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${videoTime(displayedPosition)} / ${videoTime(duration)}",
                Modifier.padding(start = 4.dp),
                color = Color(0xFF808080),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 仅缩窄可见控件，触摸、键盘和无障碍目标仍保持标准 48dp。
            Slider(
                value = fraction,
                onValueChange = { seek = it },
                onValueChangeFinished = {
                    seek?.let { onSeek((it * duration).toLong()) }
                    seek = null
                },
                enabled = seekEnabled,
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                    contentDescription = "播放进度"
                },
                colors = colors,
                thumb = {
                    Box(
                        modifier = Modifier.height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(8.dp)
                                .background(if (seekEnabled) Color.White else Color(0xFF808080), CircleShape),
                        )
                    }
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = Modifier.height(4.dp),
                        enabled = seekEnabled,
                        colors = colors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                    )
                },
            )
        }
    }
}

@Composable
internal fun VideoPlaybackActions() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoPlaybackAction(R.drawable.ic_video_forward, "转发")
        VideoPlaybackAction(R.drawable.ic_video_save, "保存")
        VideoPlaybackAction(R.drawable.ic_video_grid, "九宫格")
        VideoPlaybackAction(R.drawable.ic_chat_more, "更多")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlaybackAction(@DrawableRes icon: Int, label: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        // 目前仅显示占位外观；功能实现前不要提供可执行操作。
        IconButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(disabledContentColor = Color.White),
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(Color(0xFF454545), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
