package com.im.openchat.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 展示带提示文字的图标按钮，可单独指定按钮和触摸区域大小。
 *
 * @param icon 按钮显示的矢量图标。
 * @param label 无障碍描述及悬停提示。
 * @param modifier 按钮布局修饰符。
 * @param primary 是否使用主题主色背景。
 * @param enabled 是否允许点击。
 * @param size 按钮边长；小于 48dp 时同步缩小最小触摸范围，默认保持原有配置。
 * @param onClick 点击时触发的回调。
 * 无返回值，渲染按钮与提示，点击时仅触发回调，不直接修改业务数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val viewConfiguration = LocalViewConfiguration.current
    val buttonViewConfiguration = remember(viewConfiguration, size) {
        if (size < 48.dp) object : ViewConfiguration by viewConfiguration {
            // 将紧凑按钮的最小触摸边长限制为指定尺寸，无其他副作用。
            override val minimumTouchTargetSize = DpSize(size, size)
        } else viewConfiguration
    }
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides
            if (size < 48.dp) size else LocalMinimumInteractiveComponentSize.current,
        LocalViewConfiguration provides buttonViewConfiguration,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = onClick, enabled = enabled,
                modifier = modifier.size(size),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (primary) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ) { Icon(icon, label, Modifier.size(22.dp)) }
        }
    }
}

/**
 * 展示搜索输入框，无返回值；输入或清空时回传查询文本，不直接修改业务数据。
 *
 * @param query 当前查询文本。
 * @param onQuery 文本变化时触发，由调用方更新筛选状态。
 * @param placeholder 输入框为空时显示的提示。
 */
@Composable
internal fun SearchBox(query: String, onQuery: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, fontSize = 12.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(19.dp)) },
        trailingIcon = if (query.isNotEmpty()) {
            // 清空按钮回传空查询，无参数、无返回值。
            { ToolButton(Icons.Default.Close, "清空搜索", onClick = { onQuery("") }) }
        } else null,
        shape = RoundedCornerShape(8.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

/**
 * 展示分组标题和摘要，无返回值，仅渲染界面，无其他副作用。
 *
 * @param title 分组名称。
 * @param detail 分组数量等辅助信息。
 */
@Composable
internal fun SectionLabel(title: String, detail: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 展示空列表提示，无返回值，仅渲染界面，无其他副作用。
 *
 * @param title 空状态标题。
 * @param subtitle 补充说明。
 */
@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 展示超长时省略的单行文本，无返回值，仅渲染界面，无其他副作用。
 *
 * @param text 显示文本。
 * @param modifier 文本布局修饰符。
 * @param secondary 是否使用辅助文字样式。
 */
@Composable
internal fun SingleLine(text: String, modifier: Modifier = Modifier, secondary: Boolean = false) {
    Text(
        text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontSize = if (secondary) 12.sp else 14.sp,
        fontWeight = if (secondary) FontWeight.Normal else FontWeight.SemiBold,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 展示通讯录和设置子页面共用的标题栏。
 *
 * @param title 页面标题。
 * @param onBack 点击返回时触发，由调用方切换页面。
 * 无返回值，仅渲染界面并转发返回事件，无其他副作用。
 */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Surface {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 40.dp, onClick = onBack)
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
        }
    }
}
