package com.im.openchat.chat.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/** Slot sizes depend on the viewport and font, never on transfer state or available actions. */
@Composable
internal fun ChatAttachmentLayout(
    name: String,
    size: String,
    @DrawableRes icon: Int,
    status: String,
    tag: String,
    iconTag: String = "$tag:icon",
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val nameLines = if (density.fontScale > 1.3f) 2 else 1
    val nameHeight = with(density) { 22.sp.toDp() * nameLines }
    val sizeHeight = with(density) { 17.sp.toDp() }
    BoxWithConstraints(Modifier.width(248.dp).testTag(tag).padding(horizontal = 12.dp, vertical = 6.dp)) {
        // Reserve lines for the longest built-in status, independent of the current state.
        val charactersPerLine = ((maxWidth - 92.dp) / with(density) { 12.sp.toDp() }).toInt().coerceAtLeast(1)
        val statusLines = ceil(10.0 / charactersPerLine).toInt()
        val statusHeight = with(density) { 17.sp.toDp() * statusLines }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name, Modifier.fillMaxWidth().height(nameHeight).testTag("$tag:name"),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    fontSize = 16.sp, lineHeight = 22.sp,
                    minLines = nameLines, maxLines = nameLines, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    size, Modifier.fillMaxWidth().height(sizeHeight).testTag("$tag:size"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                AttachmentStatus(
                    status, statusColor, Modifier.fillMaxWidth().height(statusHeight), tag, statusLines,
                )
            }
            Box(Modifier.size(80.dp, 60.dp).testTag("$tag:iconArea"), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(icon), contentDescription = null,
                    modifier = Modifier.size(40.dp, 50.dp).testTag(iconTag),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun AttachmentStatus(status: String, color: Color, modifier: Modifier, tag: String, lines: Int) {
    Text(
        status, modifier.testTag("$tag:status"), color = color,
        style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        fontSize = 12.sp, lineHeight = 17.sp,
        minLines = lines, maxLines = lines, overflow = TextOverflow.Ellipsis,
    )
}
