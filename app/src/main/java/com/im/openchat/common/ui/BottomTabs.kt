package com.im.openchat.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R

/** Compact, equal-width tabs; system insets are outside the content's minimum height. */
@Composable
internal fun BottomTabs(
    selected: Int,
    unread: Int,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
    onTab: (Int) -> Unit,
) {
    // Reserve badge space for every tab, including when unread is zero.
    val badgeHeight = with(LocalDensity.current) { 12.sp.toDp().coerceAtLeast(16.dp) }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("bottom_navigation"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .windowInsetsPadding(windowInsets)
                .height(IntrinsicSize.Min)
                .selectableGroup()
                .testTag("bottom_navigation_content"),
        ) {
            listOf("会话", "通讯录", "我").forEachIndexed { index, label ->
                val active = selected == index
                val color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .testTag("bottom_tab_$index")
                        .selectable(selected = active, role = Role.Tab, onClick = { onTab(index) })
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.widthIn(max = 64.dp).fillMaxWidth(),
                    ) {
                        // Lift the icon 8dp while preserving the slot height and label position.
                        Box(
                            Modifier.align(Alignment.TopCenter)
                                .padding(top = badgeHeight - 9.dp, bottom = 8.dp)
                                .size(40.dp, 20.dp)
                                .background(
                                    if (active) color.copy(alpha = 0.08f) else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(
                                    when (index) {
                                        0 -> R.drawable.ic_common_chat
                                        1 -> R.drawable.ic_common_address
                                        else -> R.drawable.ic_common_me
                                    },
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).testTag("bottom_tab_icon_$index"),
                                tint = color,
                            )
                        }
                        if (index == 0 && unread > 0) {
                            Badge(Modifier.align(Alignment.TopEnd)) {
                                Text(
                                    if (unread > 99) "99+" else "$unread",
                                    modifier = Modifier.testTag("bottom_navigation_unread"),
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().testTag("bottom_tab_label_$index"),
                        color = color,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
