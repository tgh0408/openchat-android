package com.im.openchat.me.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.common.data.ChatData
import com.im.openchat.common.config.AppConfig
import com.im.openchat.common.ui.PageHeader
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt

internal fun profileSettingsParent(page: String?): String? = when (page) {
    "个人信息与权限", "界面与显示", "通知", "更多" -> "设置"
    "设备与同步" -> "更多"
    else -> null
}

@Composable
internal fun SettingsHomeScreen(data: ChatData, onBack: () -> Unit, onPage: (String) -> Unit) {
    SettingsMenuLayout("设置", data.profile.darkMode, onBack) {
        SettingsGroup("账号", listOf("个人资料", "账号安全", "个人信息与权限"), onPage)
        SettingsGroup("通用", listOf("通知", "界面与显示", "朋友权限", "存储空间", "更多"), onPage)
        SettingsGroup("功能", listOf("聊天", "音视频通话", "聊天记录管理", "其他功能"), onPage)
        SettingsGroup("登录", listOf("切换账号", "退出登录"), onPage, centered = true)
    }
}

/** 展示更多设置并转发选择事件；检查更新由应用级更新窗口处理，无直接网络副作用。 */
@Composable
internal fun SettingsMoreScreen(data: ChatData, onBack: () -> Unit, onPage: (String) -> Unit) {
    SettingsMenuLayout("更多", data.profile.darkMode, onBack) {
        Spacer(Modifier.height(12.dp))
        SettingsListRow("设备与同步", divider = true) { onPage("设备与同步") }
        // 转发用户明确的检查请求，不导航到未实现的通用设置页。
        SettingsListRow("检查更新", divider = false) { onPage("检查更新") }
    }
}

@Composable
private fun SettingsMenuLayout(
    title: String,
    darkMode: Boolean,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = if (darkMode) MaterialTheme.colorScheme.background else Color(0xFFEDEDED)
    Column(Modifier.fillMaxSize().background(background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings_menu_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 定位直接子容器，不将标题栏的父布局修饰符传入 ToolButton 的提示组件内部。
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
            }
            Text(
                title, Modifier.weight(1f).padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(56.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    items: List<String>,
    onPage: (String) -> Unit,
    centered: Boolean = false,
) {
    Text(
        title, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp,
    )
    items.forEachIndexed { index, item ->
        SettingsListRow(item, divider = index < items.lastIndex, centered = centered) { onPage(item) }
    }
}

@Composable
private fun SettingsListRow(
    title: String,
    divider: Boolean,
    centered: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, Modifier.weight(1f).padding(end = if (centered) 0.dp else 16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp, lineHeight = 24.sp,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                )
                if (!centered) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
            if (divider) {
                HorizontalDivider(
                    Modifier.padding(start = 16.dp), thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                )
            }
        }
    }
}

/**
 * 展示个人页的隐私、设备、主题或通知设置。
 *
 * @param page 设置页面名称。
 * @param data 当前个人资料和偏好。
 * @param onBack 点击返回时触发。
 * @param onUpdate 修改偏好时回传新数据，由调用方持久化。
 * @param fontScale 当前已保存的全局字体缩放比例。
 * @param onFontScaleChange 异步保存新字号，成功后由调用方应用到全局主题。
 * 无返回值，渲染界面并转发交互，不直接写入存储或配置系统通知。
 */
@Composable
internal fun SettingsPage(
    page: String,
    data: ChatData,
    onBack: () -> Unit,
    onUpdate: (ChatData) -> Unit,
    fontScale: Float,
    onFontScaleChange: suspend (Float) -> Unit,
) {
    var draftFontScale by remember(fontScale) { mutableFloatStateOf(fontScale) }
    var savingFontScale by remember { mutableStateOf(false) }
    var fontScaleError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * 保存用户确认的字号，成功后由调用方更新全局主题，失败时恢复已保存的比例。
     *
     * @param value 滑块停止拖动或恢复默认时提交的字体缩放比例。
     * 无返回值；启动随页面释放而取消的协程，维护保存进度和错误提示。
     */
    fun saveFontScale(value: Float) {
        if (savingFontScale) return
        savingFontScale = true
        fontScaleError = null
        // 异步保存独立的字号偏好；取消继续向上传播，磁盘异常反馈到当前页面。
        scope.launch {
            try {
                onFontScaleChange(value)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                draftFontScale = fontScale
                fontScaleError = "字体设置保存失败，请重试"
            } finally {
                savingFontScale = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(page, onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (page) {
                "个人信息与权限" -> {
                    InfoBlock("数据存储", "聊天记录和个人设置仅保存在此应用的本机私有目录。卸载应用将清除这些数据。")
                    InfoBlock("端到端加密", "当前消息未实现端到端加密。")
                    InfoBlock("备份", "聊天数据已排除系统云备份和设备迁移。")
                }
                "设备与同步" -> {
                    InfoBlock("当前设备", "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}")
                    InfoBlock("设备管理", "暂不支持管理其他登录设备。")
                }
                "界面与显示" -> {
                    InfoBlock("主题", "外观")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("浅色", "深色").forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = data.profile.darkMode == (index == 1),
                                // 点击后回传指定主题的新数据，无参数、无返回值。
                                onClick = { onUpdate(data.copy(profile = data.profile.copy(darkMode = index == 1))) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("字体大小", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${(draftFontScale * 100).roundToInt()}%")
                        }
                        Slider(
                            value = draftFontScale,
                            // 接收滑块比例并预览百分比，无返回值，拖动期间不写入存储。
                            onValueChange = { draftFontScale = it; fontScaleError = null },
                            // 结束拖动后保存当前比例，无参数、无返回值，成功后全局生效。
                            onValueChangeFinished = { saveFontScale(draftFontScale) },
                            valueRange = AppConfig.MIN_FONT_SCALE..AppConfig.MAX_FONT_SCALE,
                            steps = 8,
                            enabled = !savingFontScale,
                            modifier = Modifier.fillMaxWidth().testTag("font_scale_slider"),
                        )
                        TextButton(
                            // 恢复应用默认比例并持久化，无参数、无返回值，仍保留系统字体设置。
                            onClick = {
                                draftFontScale = AppConfig.DEFAULT_FONT_SCALE
                                saveFontScale(AppConfig.DEFAULT_FONT_SCALE)
                            },
                            enabled = !savingFontScale && draftFontScale != AppConfig.DEFAULT_FONT_SCALE,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("恢复默认字号") }
                        fontScaleError?.let { message ->
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                "通知" -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("接收消息通知", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(
                            checked = data.profile.notifications,
                            // 接收开关值并回传新通知偏好，无返回值，不配置系统通知。
                            onCheckedChange = { onUpdate(data.copy(profile = data.profile.copy(notifications = it))) },
                        )
                    }
                    InfoBlock("推送服务", "通知偏好保存在本机。远程消息推送尚未接入。")
                }
            }
        }
    }
}

/**
 * 展示设置页的一项说明，无返回值，仅渲染界面，无其他副作用。
 *
 * @param title 说明标题。
 * @param detail 说明内容。
 */
@Composable
private fun InfoBlock(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(detail, fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
