package com.im.openchat.chat.rtc

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val RTC_GROUP_AVATAR_HIGHLIGHT_MS = 1_500L

@Composable
internal fun RtcGroupMemberLayout(
    members: List<RtcGroupMemberTile>,
    videoUids: Set<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    avatarContent: @Composable (RtcGroupMemberTile, Dp) -> Unit,
    videoContent: @Composable (String) -> Unit,
) {
    val videoMembers = members.filter { it.uid in videoUids }
    BoxWithConstraints(modifier.testTag("rtc_group_members")) {
        val availableHeight = maxHeight
        val side = minOf(maxWidth / 3, availableHeight / 3)
        if (videoMembers.isEmpty()) {
            Column(Modifier.align(Alignment.Center).size(side * 3)) {
                repeat(3) { row ->
                    Row(Modifier.fillMaxWidth().height(side)) {
                        repeat(3) { column ->
                            Box(Modifier.size(side)) {
                                members.getOrNull(row * 3 + column)?.let { member ->
                                    key(member.uid) { avatarContent(member, side) }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val videoOrder = videoMembers.map { it.uid }
            val avatarOrder = members.map { it.uid }
            val videoState = rememberLazyListState()
            val avatarState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var navigation by remember { mutableStateOf<Job?>(null) }
            var highlightedUid by remember { mutableStateOf<String?>(null) }
            // A changed roster invalidates an in-flight index; subsequent taps resolve the UID again.
            DisposableEffect(videoOrder, avatarOrder, enabled) {
                onDispose {
                    navigation?.cancel()
                    highlightedUid = null
                }
            }
            Row(Modifier.align(Alignment.Center).width(side * 3).fillMaxHeight().clipToBounds()) {
                LazyColumn(
                    Modifier.width(side * 2).fillMaxHeight().clipToBounds()
                        .testTag("rtc_group_videos"),
                    state = videoState,
                    userScrollEnabled = enabled,
                    contentPadding = PaddingValues(bottom = (availableHeight - side * 2).coerceAtLeast(0.dp)),
                ) {
                    items(videoMembers, key = { it.uid }) { member ->
                        Box(Modifier.size(side * 2).clipToBounds()
                            .testTag("rtc_group_video_${member.uid}")
                            .clickable(enabled = enabled, onClickLabel = "定位成员头像") {
                                navigation?.cancel()
                                highlightedUid = null
                                navigation = scope.launch {
                                    val index = avatarOrder.indexOf(member.uid)
                                    if (index >= 0) {
                                        avatarState.animateScrollToItem(index)
                                        highlightedUid = member.uid
                                        delay(RTC_GROUP_AVATAR_HIGHLIGHT_MS.milliseconds)
                                        highlightedUid = null
                                    }
                                }
                            }) {
                            videoContent(member.uid)
                        }
                    }
                }
                LazyColumn(
                    Modifier.width(side).fillMaxHeight().clipToBounds()
                        .testTag("rtc_group_avatars"),
                    state = avatarState,
                    userScrollEnabled = enabled,
                ) {
                    items(members, key = { it.uid }) { member ->
                        val hasVideo = member.uid in videoUids
                        Box(Modifier.size(side).clipToBounds()
                            .testTag("rtc_group_avatar_${member.uid}")
                            .then(if (hasVideo) Modifier.clickable(
                                enabled = enabled,
                                onClickLabel = "定位成员视频",
                            ) {
                                navigation?.cancel()
                                highlightedUid = null
                                navigation = scope.launch {
                                    val index = videoOrder.indexOf(member.uid)
                                    if (index >= 0) videoState.animateScrollToItem(index)
                                }
                            } else Modifier)) {
                            avatarContent(member, side)
                            if (highlightedUid == member.uid && enabled) {
                                Box(Modifier.matchParentSize()
                                    .border(3.dp, Color(0xFF4ADE80))
                                    .testTag("rtc_group_highlight_${member.uid}"))
                            }
                        }
                    }
                }
            }
        }
    }
}
