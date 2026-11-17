package com.im.openchat.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import com.im.openchat.R
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.chat.data.searchConversations
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.RoomType
import com.im.openchat.chat.data.formatConversationTime
import com.im.openchat.common.data.ChatData
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.AvatarFrame
import com.im.openchat.common.ui.GroupAvatar
import com.im.openchat.common.ui.EmptyState
import com.im.openchat.common.ui.SearchBox
import com.im.openchat.common.ui.ToolButton

/**
 * 展示居中标题、36dp 搜索与新增按钮和带指向箭头的灰色菜单，未读数使用红色徽标，会话之间仅以一条横线分隔。
 *
 * @param data 当前联系人和会话数据。
 * @param onOpen 点击会话时回传联系人标识。
 * @param onNew 选择新增菜单项时回传“发起群聊”“添加朋友”或“扫一扫”。
 * @param loading 当前授权是否正在首次加载或手动刷新会话，自动同步不显示加载条，已有缓存仍可浏览。
 * @param error 获取或缓存失败的可见提示。
 * @param canRefresh 是否具有可请求服务端的授权。
 * @param onRefresh 点击刷新或重试时触发，由调用方合并请求。
 * @param avatarRepository 当前授权的头像仓库，可见项单独读取本地资料并按需补齐远端缓存。
 * @param updateNotice 在会话列表顶部、重连提示上方展示应用更新操作。
 * 无返回值，渲染界面；长按菜单仅展示 UI，搜索筛选本地会话并管理输入焦点。
 */
@Composable
internal fun ChatsScreen(
    data: ChatData,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
    loading: Boolean = false,
    error: String? = null,
    canRefresh: Boolean = false,
    onRefresh: () -> Unit = {},
    sseConnectionFailed: Boolean = false,
    onSseReconnect: () -> Unit = {},
    avatarRepository: AvatarRepository? = null,
    updateNotice: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val conversations = data.searchConversations(query)
    var newMenuExpanded by remember { mutableStateOf(false) }
    var menuConversationId by remember { mutableStateOf<String?>(null) }
    var menuAnchorWindow by remember { mutableStateOf(Offset.Zero) }
    var conversationListOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentConversationListOrigin by rememberUpdatedState(conversationListOrigin)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 展开搜索框后请求输入焦点并显示键盘，无返回值；离开组合时取消此任务。
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }
    // 返回时收起搜索、清空筛选并释放输入焦点，无参数、无返回值。
    BackHandler(enabled = searchExpanded) {
        searchExpanded = false
        query = ""
        focusManager.clearFocus()
    }
    Column {
        Surface {
            Box(
                Modifier.fillMaxWidth().height(56.dp)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    "OpenChat", Modifier.align(Alignment.Center).padding(horizontal = 100.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                if (canRefresh) {
                    ToolButton(
                        Icons.Default.Refresh, "刷新会话", Modifier.align(Alignment.CenterStart),
                        enabled = !loading, size = HeaderToolButtonSize, onClick = onRefresh,
                    )
                }
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(
                        ImageVector.vectorResource(R.drawable.ic_common_search), "搜索会话",
                        size = HeaderToolButtonSize,
                        // 点击时切换搜索框；收起时清空筛选并释放焦点，无参数、无返回值。
                        onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) {
                                query = ""
                                focusManager.clearFocus()
                            }
                        },
                    )
                    Box {
                        ToolButton(
                            ImageVector.vectorResource(R.drawable.ic_common_add),
                            "新建会话", size = HeaderToolButtonSize,
                            // 点击新增按钮展开菜单并释放输入焦点，无参数、无返回值。
                            onClick = { focusManager.clearFocus(); newMenuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = newMenuExpanded,
                            // 点击菜单外部或返回时关闭菜单，无参数、无返回值。
                            onDismissRequest = { newMenuExpanded = false },
                            modifier = Modifier.testTag("chats_new_menu"),
                            offset = DpOffset(0.dp, 4.dp),
                            shape = NewConversationMenuShape,
                            containerColor = if (data.profile.darkMode) Color(0xFF333333) else Color(0xFFF2F2F2),
                            tonalElevation = 0.dp,
                            shadowElevation = 4.dp,
                        ) {
                            Spacer(Modifier.height(NewMenuPointerHeight))
                            listOf(
                                "发起群聊" to R.drawable.ic_common_chat,
                                "添加朋友" to R.drawable.ic_common_addfriend,
                                "扫一扫" to R.drawable.ic_common_scan,
                            ).forEach { (action, icon) ->
                                DropdownMenuItem(
                                    text = { Text(action) },
                                    // 选择菜单项后先关闭菜单，再回传选项；无返回值。
                                    onClick = { newMenuExpanded = false; onNew(action) },
                                    leadingIcon = {
                                        Icon(painterResource(icon), null, Modifier.size(22.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (searchExpanded) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(searchFocusRequester),
            ) {
                SearchBox(
                    query,
                    // 接收输入文本并筛选本地会话，无返回值，不修改存储。
                    onQuery = { query = it },
                    placeholder = "搜索联系人或聊天记录",
                )
            }
        }
        updateNotice()
        if (sseConnectionFailed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当前无法连接网络，",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
                Text(
                    "点我重连",
                    modifier = Modifier
                        .clickable(
                            role = Role.Button,
                            onClick = onSseReconnect,
                        )
                        .testTag("sse_reconnect"),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("conversations_loading"))
        if (error != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                )
                if (canRefresh) ToolButton(Icons.Default.Refresh, "重试获取会话", enabled = !loading, onClick = onRefresh)
            }
        }
        LazyColumn(
            modifier = Modifier
                .onGloballyPositioned {
                    conversationListOrigin = it.localToWindow(Offset.Zero)
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        menuAnchorWindow = currentConversationListOrigin + down.position
                    }
                },
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (conversations.isEmpty() && !loading && error == null) item {
                EmptyState(
                    if (query.isBlank()) "暂无会话" else "没有找到相关会话",
                    if (query.isBlank()) "从通讯录选择联系人，开始聊天" else "试试其他联系人或聊天记录",
                )
            }
            itemsIndexed(conversations, key = { _, conversation -> conversation.contactId }) { index, conversation ->
                val contact = data.contacts.firstOrNull { it.id == conversation.contactId }
                val profiles by rememberConversationTitleProfiles(conversation, avatarRepository)
                val title = conversation.title.ifBlank {
                    if (conversation.roomType == RoomType.GROUP) {
                        profiles.mapNotNull { it?.name?.takeIf(String::isNotBlank) }.joinToString(",").ifBlank { "群聊" }
                    } else contact?.name.orEmpty()
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("conversation_${conversation.contactId}")
                        .combinedClickable(
                            onClick = { onOpen(conversation.contactId) },
                            onLongClickLabel = "会话菜单",
                            onLongClick = {
                                focusManager.clearFocus()
                                newMenuExpanded = false
                                menuConversationId = conversation.contactId
                            },
                        ),
                    shape = RectangleShape,
                    color = if (conversation.pinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        if (menuConversationId == conversation.contactId) {
                            ConversationContextMenu(
                                anchorWindow = menuAnchorWindow,
                                onDismiss = { menuConversationId = null },
                            )
                        }
                        Row(
                        Modifier
                            .height(64.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConversationAvatar(
                            conversation, contact?.id.orEmpty(), contact?.avatar.orEmpty(), title, avatarRepository,
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                title,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                conversationPreviewText(conversation, data, avatarRepository),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .width(104.dp)
                                .padding(start = 8.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                if (conversation.roomType == null) conversation.time else formatConversationTime(conversation.activeTime),
                                modifier = Modifier.testTag("conversation_time_${conversation.contactId}"),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (conversation.unread > 0) {
                                    Box(
                                        Modifier
                                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                            .testTag("conversation_unread_${conversation.contactId}")
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                            .padding(horizontal = 5.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            if (conversation.unread > 99) "99+" else "${conversation.unread}",
                                            color = MaterialTheme.colorScheme.onError,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                                if (conversation.muted) {
                                    Icon(
                                        painterResource(R.drawable.ic_common_mute),
                                        contentDescription = "消息免打扰",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                        }
                        if (index < conversations.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 86.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 展示单聊头像、最多九位成员的群头像或文件助手图标。
 * @param conversation 当前会话的真实头像资料。
 * @param localUid 本地联系人 UID，远端会话不使用 contactId 作为 UID。
 * @param localAvatar 本地联系人头像，只用于非远端会话。
 * @param title 无障碍描述及缺图占位使用的名称。
 * @param repository 当前授权共享的公共头像仓库。
 * 无返回值；组合头像图片由 Avatar 的可取消效果优先读本地缓存。
 */
@Composable
private fun ConversationAvatar(
    conversation: Conversation,
    localUid: String,
    localAvatar: String,
    title: String,
    repository: AvatarRepository?,
) {
    when {
        conversation.roomType == RoomType.FILE_ASSISTANT -> AvatarFrame(
            size = ConversationAvatarSize,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Image(
                painterResource(R.drawable.file_util),
                title,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        conversation.roomType == RoomType.GROUP -> GroupAvatar(
            conversation.avatarUids, title, repository, conversation.contactId, ConversationAvatarSize,
        )
        else -> Avatar(
            uid = if (conversation.roomType == null) localUid else conversation.participantId.orEmpty(),
            name = title, size = ConversationAvatarSize, repository = repository,
            roomId = conversation.contactId.takeIf { conversation.roomType != null },
            avatar = if (conversation.roomType == null) localAvatar else conversation.avatar,
        )
    }
}

private val ConversationAvatarSize = 48.dp
private val HeaderToolButtonSize = 32.dp
private val NewMenuPointerHeight = 8.dp

private object NewConversationMenuShape : Shape {
    /**
     * 生成顶部三角与圆角菜单连为一体的轮廓，三角对齐新增按钮中心。
     *
     * @param size 菜单实际绘制尺寸。
     * @param layoutDirection 当前布局方向，用于镜像三角位置。
     * @param density 将菜单圆角、三角尺寸及按钮半宽换算为像素。
     * @return 用于背景、裁剪和阴影的连续轮廓；不修改界面状态。
     */
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val pointerHeight = with(density) { NewMenuPointerHeight.toPx() }
        val pointerHalfWidth = with(density) { 7.dp.toPx() }
        val radius = with(density) { 6.dp.toPx() }
        val buttonHalfWidth = with(density) { (HeaderToolButtonSize / 2).toPx() }
        val pointerCenter = if (layoutDirection == LayoutDirection.Ltr) size.width - buttonHalfWidth else buttonHalfWidth
        return Outline.Generic(Path().apply {
            moveTo(radius, pointerHeight)
            lineTo(pointerCenter - pointerHalfWidth, pointerHeight)
            lineTo(pointerCenter, 0f)
            lineTo(pointerCenter + pointerHalfWidth, pointerHeight)
            lineTo(size.width - radius, pointerHeight)
            quadraticTo(size.width, pointerHeight, size.width, pointerHeight + radius)
            lineTo(size.width, size.height - radius)
            quadraticTo(size.width, size.height, size.width - radius, size.height)
            lineTo(radius, size.height)
            quadraticTo(0f, size.height, 0f, size.height - radius)
            lineTo(0f, pointerHeight + radius)
            quadraticTo(0f, pointerHeight, radius, pointerHeight)
            close()
        })
    }
}
