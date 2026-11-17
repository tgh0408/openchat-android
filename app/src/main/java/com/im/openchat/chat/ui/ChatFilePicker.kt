package com.im.openchat.chat.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.FileMessageBody
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.formatChatTime
import com.im.openchat.common.config.AppConfig
import com.im.openchat.common.ui.ToolButton
import java.util.Calendar
import java.util.TimeZone

private val FilePickerGreen = Color(0xFF07C160)
internal const val MAX_CHAT_FILE_SELECTION = 9
internal enum class FilePickerChannel { CHAT, FAVORITES, ALBUM }

internal fun selectableChatFiles(messages: List<Message>): List<Message> =
    messages.filter { message ->
        val body = message.body as? FileMessageBody
        val name = body?.extra?.fileName.orEmpty()
        message.status == "sent" && message.id.toLongOrNull()?.let { it > 0 } == true &&
            !body?.content.isNullOrBlank() && (body?.extra?.size ?: 0L) in 1..6L * 1024 * 1024 * 1024 &&
            name.isNotBlank() && name.length <= 255 && name.none { it.isISOControl() || it in "/\\" }
    }.sortedByDescending { it.sentAt }

internal fun reconcileChatFileSelection(selected: List<Message>, available: List<Message>): List<Message> {
    val current = available.associateBy { it.id }
    return selected.filter { current[it.id]?.body == it.body }
}

internal fun chatFileDateGroup(timestamp: Long, now: Long): String {
    if (timestamp <= 0) return "时间未知"
    fun day(value: Long) = Calendar.getInstance(TimeZone.getTimeZone(AppConfig.CHAT_TIME_ZONE_ID)).apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = day(now)
    val date = day(timestamp)
    val days = (today.timeInMillis - date.timeInMillis) / 86_400_000L
    return when {
        days == 0L -> "今天"
        days == 1L -> "昨天"
        days in 2..7 -> "前7天"
        days in 8..30 -> "前30天"
        date.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "${date.get(Calendar.MONTH) + 1}月"
        else -> "${date.get(Calendar.YEAR)}年"
    }
}

@Composable
internal fun ChatFilePicker(
    conversation: Conversation,
    peerName: String,
    ownUid: String,
    uploads: MediaUploads,
    onDismiss: () -> Unit,
) {
    val files = LocalMessageFiles.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var selected by remember { mutableStateOf<List<UploadSelection>>(emptyList()) }
    var channel by remember { mutableStateOf(FilePickerChannel.CHAT) }
    val album = rememberFilePickerAlbum(channel == FilePickerChannel.ALBUM, active)
    var awaitingDocument by remember { mutableStateOf(false) }
    var pendingDocument by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var closed by remember { mutableStateOf(false) }
    fun dismiss() {
        if (closed) return
        closed = true
        awaitingDocument = false
        pendingDocument = null
        onDismiss()
    }
    val available = remember(conversation.messages) { selectableChatFiles(conversation.messages) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (!closed && awaitingDocument) {
            awaitingDocument = false
            pendingDocument = uri
        }
    }
    LaunchedEffect(available) {
        val valid = reconcileChatFileSelection(
            selected.filterIsInstance<UploadSelection.ChatFile>().map { it.message }, available,
        ).map { it.id }.toSet()
        selected = selected.filter { item ->
            item !is UploadSelection.ChatFile || item.message.id in valid
        }
    }
    LaunchedEffect(album.assets, album.loading, album.access, album.error, channel) {
        if (channel == FilePickerChannel.ALBUM && !album.loading) {
            val allowed = if (album.access && album.error == null) album.assets.map { it.uri }.toSet() else emptySet()
            selected = selected.filter { it !is UploadSelection.Device || it.uri in allowed }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> active = true
                Lifecycle.Event.ON_PAUSE -> {
                    active = false
                    uploads.pauseAll(includeImages = false)
                    selected = emptyList()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            awaitingDocument = false
            pendingDocument = null
        }
    }
    LaunchedEffect(active, pendingDocument) {
        val uri = pendingDocument ?: return@LaunchedEffect
        if (active && !closed) {
            pendingDocument = null
            if (uploads.selectFiles(conversation.contactId, listOf(UploadSelection.Device(uri)))) {
                dismiss()
            } else error = "附件正在准备，请稍后重新选择"
        }
    }
    Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        ChatFilePickerContent(
            messages = if (active) available else emptyList(),
            selection = selected.filterIsInstance<UploadSelection.ChatFile>().map { it.message },
            peerName = peerName, ownUid = ownUid,
            busy = uploads.preparing, enabled = active && !closed && !awaitingDocument && !uploads.preparing,
            canSend = true, error = error,
            selectedCount = selected.size, channel = channel,
            onSelection = { messages ->
                if (files != null) {
                    val ids = messages.map { it.id }.toSet()
                    val previous = selected.filterIsInstance<UploadSelection.ChatFile>().map { it.message.id }.toSet()
                    selected = selected.filter { it !is UploadSelection.ChatFile || it.message.id in ids } +
                        messages.filter { it.id !in previous }.map { UploadSelection.ChatFile(it, files) }
                    error = null
                }
            },
            onDismiss = { dismiss() },
            onChannel = { channel = it; error = null },
            albumContent = { query, expanded, modifier ->
                FilePickerAlbumContent(
                    state = album, query = query, selectedOnly = expanded,
                    selected = selected.filterIsInstance<UploadSelection.Device>().map { it.uri }.toSet(),
                    selectionCount = selected.size,
                    enabled = active && !closed && !awaitingDocument && !uploads.preparing,
                    onToggle = { asset ->
                        val item = UploadSelection.Device(asset.uri)
                        selected = if (item in selected) selected - item
                            else if (selected.size < MAX_CHAT_FILE_SELECTION) selected + item else selected
                        error = null
                    },
                    modifier = modifier,
                )
            },
            onPhoneFiles = {
                if (!closed && !awaitingDocument) {
                    try {
                        awaitingDocument = true
                        error = null
                        document.launch(arrayOf("*/*"))
                    } catch (_: Exception) {
                        awaitingDocument = false
                        error = "无法打开系统文件选择器，请重试"
                    }
                }
            },
            onSend = {
                if (active && !closed && uploads.selectFiles(conversation.contactId, selected.toList())) {
                    dismiss()
                }
            },
        )
    }
}

@Composable
internal fun ChatFilePickerContent(
    messages: List<Message>,
    selection: List<Message>,
    peerName: String,
    ownUid: String,
    busy: Boolean,
    enabled: Boolean,
    canSend: Boolean,
    error: String?,
    onSelection: (List<Message>) -> Unit,
    onDismiss: () -> Unit,
    onChannel: (FilePickerChannel) -> Unit,
    onPhoneFiles: () -> Unit,
    onSend: () -> Unit,
    channel: FilePickerChannel = FilePickerChannel.CHAT,
    selectedCount: Int = selection.size,
    albumContent: @Composable (String, Boolean, Modifier) -> Unit = { _, _, modifier -> Spacer(modifier) },
) {
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Message?>(null) }
    val selectedIds = selection.map { it.id }.toSet()
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val background = if (light) Color.White else MaterialTheme.colorScheme.surface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val visible = messages.filter {
        (!expanded || it.id in selectedIds) &&
            (it.body as FileMessageBody).extra?.fileName.orEmpty().contains(query.trim(), ignoreCase = true)
    }
    val groups = visible.groupBy { chatFileDateGroup(it.sentAt, System.currentTimeMillis()) }
    LaunchedEffect(messages) {
        if (detail != null && messages.none { it.id == detail?.id && it.body == detail?.body }) detail = null
    }
    fun source(message: Message): String = when {
        message.senderId.isNotBlank() && message.senderId == ownUid -> "来自自己"
        message.senderId.isNotBlank() -> if (peerName.isNotBlank()) "来自$peerName" else "来自 UID ${message.senderId}"
        else -> "来源未知"
    }
    BackHandler {
        when {
            search -> { search = false; query = "" }
            expanded -> expanded = false
            else -> onDismiss()
        }
    }
    Column(Modifier.fillMaxSize().background(background)
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().testTag("chat_file_picker")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.onSurface) }
            Text("选择文件", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            ToolButton(Icons.Default.Search, "搜索文件", enabled = !busy, onClick = { search = !search; query = "" })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            listOf("聊天", "收藏", "手机相册", "手机文件").forEachIndexed { index, title ->
                val chosen = index == channel.ordinal
                Column(Modifier.widthIn(min = 72.dp).selectable(
                    selected = chosen, enabled = enabled && !busy, role = Role.Tab,
                    onClick = {
                        when (index) {
                            0 -> onChannel(FilePickerChannel.CHAT)
                            1 -> onChannel(FilePickerChannel.FAVORITES)
                            2 -> onChannel(FilePickerChannel.ALBUM)
                            3 -> onPhoneFiles()
                        }
                    },
                ).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, Modifier.padding(vertical = 12.dp), fontSize = 16.sp,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface else secondary)
                    Box(Modifier.width(44.dp).height(2.dp).background(
                        if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    ))
                }
            }
        }
        if (search) OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("file_search"),
            singleLine = true, placeholder = { Text("搜索文件名") },
            trailingIcon = { ToolButton(Icons.Default.Close, "清除搜索", onClick = { query = "" }) },
        )
        if (error != null) Text(error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        if (channel == FilePickerChannel.ALBUM) {
            albumContent(query, expanded, Modifier.weight(1f).fillMaxWidth())
        } else if (channel == FilePickerChannel.FAVORITES || visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    channel == FilePickerChannel.FAVORITES -> "收藏文件暂未接入"
                    query.isNotBlank() -> "未找到相关文件"
                    expanded -> "尚未选择文件"
                    else -> "当前聊天暂无文件"
                },
                Modifier.padding(24.dp), color = secondary,
            )
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_file_list")) {
            groups.forEach { (label, entries) ->
                item("date:$label") {
                    Text(label, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = secondary, fontSize = 14.sp)
                }
                items(entries, key = { it.id }) { message ->
                    val body = message.body as FileMessageBody
                    val name = body.extra?.fileName.orEmpty()
                    val checked = message.id in selectedIds
                    val selectable = enabled && !busy && (checked || selectedCount < MAX_CHAT_FILE_SELECTION)
                    Row(Modifier.fillMaxWidth().testTag("file_choice_${message.id}").toggleable(
                        value = checked, enabled = selectable, role = Role.Checkbox,
                        onValueChange = {
                            onSelection(if (checked) selection.filterNot { it.id == message.id } else selection + message)
                        },
                    ).padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp)
                            .background(if (checked) FilePickerGreen else Color.Transparent, CircleShape)
                            .border(1.5.dp, if (checked) FilePickerGreen else secondary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(16.dp))
                        Image(painterResource(chatFileIcon(name)), null, Modifier.size(44.dp, 56.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatChatFileSize(body.extra?.size), fontSize = 13.sp, color = secondary)
                            Text(
                                listOfNotNull(source(message), message.sentAt.takeIf { it > 0 }?.let {
                                    formatChatTime(it, "M月d日 HH:mm")
                                }).joinToString("  "),
                                fontSize = 12.sp, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ToolButton(Icons.Default.MoreVert, "文件详情：$name", size = 40.dp, onClick = { detail = message })
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        Surface(color = if (light) Color(0xFFF7F7F7) else MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ToolButton(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    if (expanded) "收起已选文件" else "查看已选文件",
                    onClick = {
                        expanded = !expanded
                        if (channel == FilePickerChannel.FAVORITES) onChannel(FilePickerChannel.CHAT)
                    },
                )
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                Button(
                    onClick = onSend, enabled = enabled && canSend && !busy && selectedCount > 0 &&
                        channel != FilePickerChannel.FAVORITES,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FilePickerGreen, contentColor = Color.White),
                    modifier = Modifier.testTag("send_selected_files"),
                ) { Text(if (selectedCount == 0) "发送" else "发送 ($selectedCount)") }
            }
        }
    }
    detail?.let { message ->
        val body = message.body as FileMessageBody
        AlertDialog(
            onDismissRequest = { detail = null }, title = { Text(body.extra?.fileName.orEmpty()) },
            text = { Text("${formatChatFileSize(body.extra?.size)}\n${source(message)}") },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
}
