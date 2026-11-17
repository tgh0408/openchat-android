package com.im.openchat.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im.openchat.R
import com.im.openchat.address.data.Contact
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.FileMessageBody
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.MessageImageRepository
import com.im.openchat.chat.data.RecallMessageBody
import com.im.openchat.chat.data.RoomType
import com.im.openchat.chat.data.RtcCallMessageBody
import com.im.openchat.chat.data.SystemMessageBody
import com.im.openchat.chat.data.TemporaryMessageBody
import com.im.openchat.chat.data.VideoMessageBody
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton
import com.im.openchat.common.ui.LocalPageTransitionRunning
import com.im.openchat.common.ui.rememberWindowForeground
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 展示已缓存的真实聊天内容，空历史保持空白，不使用静态示例填充。
 *
 * @param contact 当前聊天对象，用于标题和对方头像。
 * @param conversation 当前会话及按时间升序保存的消息。
 * @param ownAvatar 当前用户的头像地址。
 * @param ownName 当前用户的头像描述名称。
 * @param onBack 返回列表时触发。
 * @param onMenu 打开会话管理时触发。
 * @param onUnavailable 点击未接入的输入功能时回传功能名称。
 * @param onSend 将非空草稿加入发送队列，保存失败时保留输入。
 * @param loadingHistory 当前会话是否正在补齐历史。
 * @param historyError 本次历史请求失败信息，为空时不显示错误提示。
 * @param hasMoreHistory 是否仍可请求更早的服务端历史。
 * @param onLoadOlder 滚到顶部或点击向上按钮时请求旧历史。
 * @param onRetryHistory 点击重试时恢复失败的历史请求。
 * @param avatarRepository 头像资料的独立懒加载仓库，按每条消息发送者读取。
 * @param ownUid 当前登录用户 UID，用于本人头像缺少地址时按需补齐。
 * @param messageImages 本地优先的图片读取仓库。
 * @param readReady 首次同步和未读快照已就绪且未被分页或重连补查阻塞，普通后台补查不暂停阅读。
 * @param onRead 实际阅读消息的服务端 ID，交给授权内队列保存并合并上报。
 * @param preparingUnread 首次未读校准尚未完成，只暂缓底部阅读位置的旧角标，不阻塞聊天内容。
 * 无返回值，渲染界面；发送后清空草稿，补入旧历史保持消息锚点，新消息在底部附近时跟随。
 */
@Composable
internal fun ChatScreen(
    contact: Contact,
    conversation: Conversation,
    ownAvatar: String,
    ownName: String,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onUnavailable: (String) -> Unit,
    onSend: suspend (String) -> Unit,
    loadingHistory: Boolean = false,
    historyError: String? = null,
    hasMoreHistory: Boolean = false,
    onLoadOlder: () -> Unit = {},
    onRetryHistory: () -> Unit = {},
    avatarRepository: AvatarRepository? = null,
    ownUid: String = "",
    messageImages: MessageImageRepository? = null,
    onRetrySend: (Message) -> Unit = {},
    readReady: Boolean = false,
    onRead: (String) -> Unit = {},
    preparingUnread: Boolean = false,
    detailsOpen: Boolean = false,
    callWindowOpen: Boolean = false,
    onUserProfile: (UserProfileTarget) -> Unit = {},
    onGroupCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onAudioCall: (() -> Unit)? = null,
    onFilePickerVisibilityChanged: (Boolean) -> Unit = {},
) {
    var draft by rememberSaveable(contact.id) { mutableStateOf("") }
    var queueing by remember(contact.id) { mutableStateOf(false) }
    var sendError by remember(contact.id) { mutableStateOf<String?>(null) }
    var moreExpanded by remember(contact.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uploads = LocalMediaUploads.current
    var galleryOpen by remember(contact.id, uploads) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val pendingImages = uploads?.pendingImages.orEmpty()
    val messages = mergePendingImages(conversation.contactId, conversation.messages, pendingImages)
    val videoPlayback = LocalVideoPlayback.current
    val videoCoversChat = videoPlayback?.let { it.request != null && !it.floating } == true ||
        LocalVideoPictureInPicture.current?.videoOnly == true
    val mediaCovered = detailsOpen || callWindowOpen
    var filePickerOpen by remember(
        contact.id, conversation.contactId, conversation.roomType, conversation.groupRemoved,
        ownUid, uploads, detailsOpen, videoCoversChat,
    ) { mutableStateOf(false) }
    val filePickerVisibility by rememberUpdatedState(onFilePickerVisibilityChanged)
    SideEffect { filePickerVisibility(filePickerOpen) }
    DisposableEffect(Unit) { onDispose { filePickerVisibility(false) } }
    fun closeFilePicker() {
        filePickerOpen = false
        filePickerVisibility(false)
    }
    var imagePreview by remember(
        contact.id, conversation.contactId, messageImages, mediaCovered, videoCoversChat,
        conversation.groupRemoved, conversation.roomType,
    ) { mutableStateOf<ImagePreviewRequest?>(null) }
    val previewMessages = imagePreview?.resolve(conversation.messages).orEmpty()
    val imagePreviewOpen = imagePreview != null && previewMessages.isNotEmpty()
    var callPickerOpen by remember(
        contact.id, conversation.contactId, conversation.roomType, conversation.groupRemoved,
        ownUid, avatarRepository, messageImages, uploads, mediaCovered, videoCoversChat, galleryOpen, imagePreviewOpen, filePickerOpen,
    ) { mutableStateOf(false) }
    LaunchedEffect(imagePreview, previewMessages.isEmpty()) {
        if (previewMessages.isEmpty()) imagePreview = null
    }
    val cameraAllowed = !mediaCovered && !videoCoversChat && !galleryOpen && !imagePreviewOpen && !callPickerOpen && !filePickerOpen &&
        !conversation.groupRemoved && conversation.roomType != null
    val cameraCapture = key(conversation.contactId, uploads, cameraAllowed) {
        rememberChatPhotoCapture(
            enabled = cameraAllowed && uploads != null && !uploads.preparing,
            onPhoto = { photo ->
                uploads?.selectImage(conversation.contactId, photo.uri, onFinished = { photo.discard() }) == true
            },
            onError = { sendError = it },
        )
    }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    // A floating call blocks new read receipts and camera capture, but does not cover the composer.
    val morePanelVisibility = remember(contact.id, detailsOpen, videoCoversChat) {
        MutableTransitionState(false)
    }
    morePanelVisibility.targetState = moreExpanded && imeBottom == 0 && !detailsOpen && !videoCoversChat
    val transitioning = LocalPageTransitionRunning.current
    val foreground = rememberWindowForeground() && !videoCoversChat && !mediaCovered && !transitioning &&
        !galleryOpen && !imagePreviewOpen && !callPickerOpen && !filePickerOpen && !cameraCapture.state.pending
    val readEnabled = readReady && historyError == null &&
        conversation.roomType != null && !conversation.groupRemoved
    val (readObserver, readCandidate) = rememberChatReadObserver(
        contact.id, messages, list,
        enabled = readEnabled,
        foreground = foreground && morePanelVisibility.isIdle,
        onRead = onRead,
    )
    val profiles by rememberConversationTitleProfiles(conversation, avatarRepository)
    val title = conversation.title.ifBlank {
        if (contact.group) profiles.mapNotNull { it?.name?.takeIf(String::isNotBlank) }
            .joinToString(",").ifBlank { contact.name } else contact.name
    }
    val bottomFollowThresholdPx = with(density) { 160.dp.roundToPx() }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val morePanelHeight = minOf(300.dp, with(density) { windowHeightPx.toDp() } * 0.4f)
    BackHandler(enabled = (moreExpanded || morePanelVisibility.currentState || !morePanelVisibility.isIdle) &&
        !detailsOpen && !videoCoversChat && !transitioning && !callPickerOpen && !filePickerOpen
    ) {
        moreExpanded = false
    }
    LaunchedEffect(detailsOpen, videoCoversChat, conversation.groupRemoved) {
        if (detailsOpen || videoCoversChat) moreExpanded = false
        if (detailsOpen || videoCoversChat || conversation.groupRemoved) galleryOpen = false
    }
    fun launchAttachmentPicker(media: Boolean) {
        if (uploads == null || uploads.preparing || cameraCapture.state.pending ||
            conversation.groupRemoved || conversation.roomType == null
        ) {
            return
        }
        moreExpanded = false
        focusManager.clearFocus()
        keyboard?.hide()
        if (media) {
            galleryOpen = true
        } else {
            filePickerVisibility(true)
            filePickerOpen = true
        }
    }
    val latestMessageKey = messages.lastOrNull()?.chatItemKey
    var previousLatestKey by remember(contact.id) { mutableStateOf<String?>(null) }
    var previousImeBottom by remember(contact.id) { mutableIntStateOf(imeBottom) }
    val followLatest by remember(list, bottomFollowThresholdPx) {
        derivedStateOf {
            list.firstVisibleItemIndex <= 1 && list.firstVisibleItemScrollOffset <= bottomFollowThresholdPx
        }
    }
                // 进入页面时只延迟显示角标，保留缓存内容和已持久化的阅读位置。
    val deferUnread = preparingUnread && historyError == null &&
        conversation.roomType != null && !conversation.groupRemoved
    val displayedUnread = if (foreground && followLatest &&
        (deferUnread || readEnabled && readCandidate != null)
    ) {
        0
    } else {
        conversation.unread
    }
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val background = if (light) Color(0xFFEDEDED) else MaterialTheme.colorScheme.surfaceContainerLow
    val outgoingColor = if (light) Color(0xFF95EC69) else MaterialTheme.colorScheme.primaryContainer
    val outgoingContent = if (light) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.onPrimaryContainer
    // 先保存待发送消息再清空草稿；不收起键盘，避免重复提交正在入队的文本。
    val send: () -> Unit = {
        if (!conversation.groupRemoved && !queueing && draft.isNotBlank()) {
            val text = draft
            queueing = true
            sendError = null
            scope.launch {
                try {
                    onSend(text)
                    draft = ""
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    sendError = exception.message ?: "消息发送失败，请重试"
                } finally {
                    queueing = false
                }
            }
        }
    }
    // 在本次内容更新的测量前指定位置，避免先按旧 key 布局、再强制跳底的中间帧。
    SideEffect {
        if (latestMessageKey != null &&
            (latestMessageKey != previousLatestKey || imeBottom != previousImeBottom) &&
            (previousLatestKey == null || followLatest)
        ) {
            list.requestScrollToItem(0)
        }
        previousLatestKey = latestMessageKey
        previousImeBottom = imeBottom
    }
    // 用户滚到最早的可见消息时请求下一页；空历史和失败状态不自动循环请求。
    LaunchedEffect(contact.id, messages.firstOrNull()?.id, hasMoreHistory, loadingHistory, historyError) {
        if (!hasMoreHistory || loadingHistory || historyError != null || messages.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            list.isScrollInProgress && list.layoutInfo.visibleItemsInfo.any { it.key == messages.first().chatItemKey }
        }.distinctUntilChanged().collectLatest { reachedTop ->
            if (reachedTop) onLoadOlder()
        }
    }
    Column(Modifier.fillMaxSize().background(background)
        .then(if (detailsOpen || galleryOpen || imagePreviewOpen || callPickerOpen || filePickerOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
        Surface(color = background) {
            Box(Modifier.fillMaxWidth().height(60.dp)) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 90.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    if (contact.group) {
                        Text(
                            if (conversation.groupRemoved) "已退出群聊" else "群聊",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 44.dp, onClick = onBack)
                    if (displayedUnread > 0) {
                        Box(
                            Modifier.testTag("chat_unread").height(24.dp).widthIn(min = 24.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (displayedUnread > 99) "99+" else "$displayedUnread",
                                modifier = Modifier.padding(horizontal = 4.dp),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    ToolButton(
                        ImageVector.vectorResource(R.drawable.ic_chat_more), "会话管理",
                        size = 44.dp, onClick = {
                            moreExpanded = false
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onMenu()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (conversation.roomType == null && messages.isNotEmpty()) {
            Text(
                "本机消息 · 不会发送给对方",
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp, textAlign = TextAlign.Center,
            )
        }
        // 已有消息的后台补查不新增占位栏；有旧历史时原栏保持高度，错误仍在页面顶部可见。
        if (hasMoreHistory || historyError != null || loadingHistory && messages.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    loadingHistory -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    historyError != null -> {
                        Text(
                            historyError, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        ToolButton(Icons.Default.Refresh, "重试聊天记录", size = 36.dp, onClick = onRetryHistory)
                    }
                    else -> ToolButton(
                        Icons.Default.KeyboardArrowUp, "加载更早消息", size = 36.dp, onClick = onLoadOlder,
                    )
                }
            }
        }
        LazyColumn(
            state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_messages")
                .then(readObserver.viewportModifier)
                .pointerInput(contact.id) {
                    awaitEachGesture {
                // 仅观察手势，不拦截图片、视频或滚动操作。
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        moreExpanded = false
                    }
                },
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
            reverseLayout = true,
        ) {
            itemsIndexed(messages.asReversed(), key = { _, message -> message.chatItemKey }) { reverseIndex, message ->
                val index = messages.lastIndex - reverseIndex
                Column {
                    if (index == 0 || message.time != messages[index - 1].time) {
                        Text(
                            message.time,
                            Modifier.fillMaxWidth().padding(top = if (index == 0) 10.dp else 14.dp, bottom = 18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            fontSize = 12.sp, textAlign = TextAlign.Center,
                        )
                    }
                    Box(
                        if (message.id == readCandidate) readObserver.messageModifier(message.id) else Modifier,
                    ) {
                        when (val body = message.body) {
                            is SystemMessageBody -> ChatSystemMessage(body.content)
                            is RecallMessageBody -> ChatSystemMessage(
                                if (message.outgoing) {
                                    "我撤回了一条消息"
                                } else {
                                    "${body.recallGroupNickname?.takeIf(String::isNotBlank)
                                        ?: body.recallName?.takeIf(String::isNotBlank)
                                        ?: contact.name}撤回了一条消息"
                                },
                            )
                            else -> CompositionLocalProvider(LocalMediaRemoteAllowed provides !conversation.groupRemoved) {
                                ChatMessageBubble(
                                    message = message, contact = contact, ownAvatar = ownAvatar, ownName = ownName,
                                    outgoingColor = outgoingColor, outgoingContent = outgoingContent,
                                    roomId = conversation.contactId, avatarRepository = avatarRepository, messageImages = messageImages,
                                    ownUid = ownUid, avatarRoomId = conversation.contactId.takeIf { conversation.roomType != null },
                                    canRetrySend = !conversation.groupRemoved, onRetrySend = onRetrySend,
                                    pendingImage = pendingImages.firstOrNull {
                                        it.messageId == message.id && it.roomId == conversation.contactId &&
                                            !it.isPublished(conversation.messages)
                                    },
                                    onRetryImage = { id -> uploads?.retryImage(id) },
                                    onCancelImage = { id -> uploads?.cancelImage(id) },
                                    onPreviewImage = { selected ->
                                        if (!mediaCovered && !videoCoversChat && !galleryOpen && !filePickerOpen && !transitioning) {
                                            moreExpanded = false
                                            focusManager.clearFocus()
                                            keyboard?.hide()
                                            imagePreview = imagePreviewRequest(conversation.messages, selected.id)
                                        }
                                    },
                                    onVideoCall = onVideoCall,
                                    onAudioCall = onAudioCall,
                                    peerUid = conversation.participantId.takeIf { conversation.roomType == RoomType.FRIEND },
                                    onUserProfile = {
                                        moreExpanded = false
                                        focusManager.clearFocus()
                                        keyboard?.hide()
                                        onUserProfile(it)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Surface(color = if (light) Color(0xFFF6F6F6) else MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolButton(
                        ImageVector.vectorResource(R.drawable.ic_chat_voice), "语音输入",
                        Modifier.padding(bottom = 4.dp), size = 40.dp,
                        onClick = { onUnavailable("语音输入") },
                    )
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (!queueing) { draft = it; sendError = null } },
                        enabled = !conversation.groupRemoved,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                            .testTag("chat_draft")
                            .onFocusChanged { if (it.isFocused) moreExpanded = false }
                            .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface),
                        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        decorationBox = { innerTextField ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
                                    if (draft.isEmpty() && conversation.groupRemoved) {
                                        Text("已退出群聊", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    innerTextField()
                                }
                                ToolButton(
                                    ImageVector.vectorResource(R.drawable.ic_chat_mic), "语音消息", size = 36.dp,
                                    onClick = { onUnavailable("语音消息") },
                                )
                            }
                        },
                    )
                    ToolButton(
                        ImageVector.vectorResource(R.drawable.ic_chat_emoji), "表情", Modifier.padding(bottom = 4.dp), size = 40.dp,
                        onClick = { onUnavailable("表情") },
                    )
                    Box(Modifier.padding(bottom = 6.dp)) {
                        ToolButton(
                            ImageVector.vectorResource(R.drawable.ic_chat_add), "更多", size = 36.dp,
                            onClick = {
                                val opening = !moreExpanded
                                focusManager.clearFocus()
                                keyboard?.hide()
                                moreExpanded = opening
                            },
                        )
                    }
                }
                sendError?.let {
                    Text(
                        it, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    )
                }
                if (uploads != null && (uploads.preparingRoom == conversation.contactId ||
                    uploads.albumVideoRoom == conversation.contactId ||
                    uploads.fileSelectionRoom == conversation.contactId && uploads.fileSelectionPreparing)
                ) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (uploads.albumVideoRoom == conversation.contactId) "正在准备视频" else "正在准备附件",
                        Modifier.weight(1f), fontSize = 12.sp,
                    )
                    ToolButton(Icons.Default.Close, "停止准备附件", size = 36.dp, onClick = { uploads.pauseAll() })
                }
                if (uploads?.fileSelectionRoom == conversation.contactId && uploads.fileSelectionError != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(uploads.fileSelectionError.orEmpty(), Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        ToolButton(Icons.Default.Refresh, "重试所选附件", size = 36.dp, onClick = { uploads.retryImage() })
                        ToolButton(Icons.Default.Close, "取消剩余附件", size = 36.dp, onClick = { uploads.cancelImage() })
                    }
                }
                // 退出动画结束前保留页面组合；被覆盖或切换房间时取消动画。
                if (!detailsOpen && !videoCoversChat) key(morePanelVisibility) {
                    ChatMorePanelTransition(
                        visibleState = morePanelVisibility,
                        panelHeight = morePanelHeight,
                        imeBottom = imeBottom,
                        attachmentsEnabled = uploads != null && !uploads.preparing && !cameraCapture.state.pending &&
                            !conversation.groupRemoved && conversation.roomType != null,
                        onAction = { action ->
                            when (action) {
                                "相册" -> launchAttachmentPicker(media = true)
                                "拍摄" -> {
                                    if (callWindowOpen) {
                                        sendError = "通话中暂时无法拍摄，请结束通话后再试"
                                    } else {
                                        moreExpanded = false
                                        focusManager.clearFocus()
                                        keyboard?.hide()
                                        sendError = null
                                        cameraCapture.launch()
                                    }
                                }
                                "文件" -> launchAttachmentPicker(media = false)
                                "视频通话" -> {
                                    if (callWindowOpen && conversation.roomType != RoomType.FRIEND &&
                                        conversation.roomType != RoomType.GROUP) {
                                        sendError = "正在通话中，请结束当前通话后再发起"
                                    } else if (conversation.roomType == RoomType.GROUP &&
                                        !conversation.groupRemoved && onGroupCall != null
                                    ) {
                                        focusManager.clearFocus()
                                        keyboard?.hide()
                                        onGroupCall()
                                    } else if (conversation.roomType == RoomType.FRIEND && !conversation.groupRemoved) {
                                        focusManager.clearFocus()
                                        keyboard?.hide()
                                        callPickerOpen = true
                                    } else {
                                        onUnavailable(action)
                                    }
                                }
                                else -> onUnavailable(action)
                            }
                        },
                    )
                }
            }
        }
    }
    if (callPickerOpen) {
        ChatCallPicker(
            onDismiss = { callPickerOpen = false },
            onSelect = { action ->
                if (callPickerOpen) {
                    callPickerOpen = false
                    if (action == "video" && onVideoCall != null) {
                        moreExpanded = false
                        onVideoCall()
                    } else if (action == "audio" && onAudioCall != null) {
                        moreExpanded = false
                        onAudioCall()
                    } else {
                        onUnavailable(if (action == "audio") "语音通话" else "视频通话")
                    }
                }
            },
        )
    }
    imagePreview?.takeIf { imagePreviewOpen }?.let { request ->
        key(request, messageImages, conversation.contactId) {
            ImagePreview(
                conversation.contactId, previewMessages, request.initialMessageId, messageImages,
                onDismiss = { imagePreview = null },
            )
        }
    }
    if (galleryOpen && !detailsOpen && !videoCoversChat && !conversation.groupRemoved &&
        conversation.roomType != null && uploads != null
    ) PhotoGalleryPicker(
        onDismiss = { galleryOpen = false },
        onSend = { selections ->
            galleryOpen = false
            uploads.selectMedia(conversation.contactId, selections)
        },
    )
    if (filePickerOpen && uploads != null && !detailsOpen && !videoCoversChat &&
        !conversation.groupRemoved && conversation.roomType != null
    ) key(uploads, conversation.contactId) {
        ChatFilePicker(
            conversation = conversation,
            peerName = if (conversation.roomType == RoomType.FRIEND) contact.name else "",
            ownUid = ownUid, uploads = uploads,
            onDismiss = { closeFilePicker() },
        )
    }
}

/** 消息身份只取消息 ID；未确认的本地占位暂用其本地 ID，不以客户端标识合并历史。 */
internal val Message.chatItemKey: String
    get() = "message:$id"

/**
 * 渲染消息气泡，文件显示名称、大小及类型矢量图标，图片进入可见项后懒加载。
 * @param message 当前展示的真实缓存消息。
 * @param contact 对方资料，用于左侧头像。
 * @param ownAvatar 当前用户头像地址。
 * @param ownName 当前用户名称。
 * @param outgoingColor 发出气泡的主题适配背景色。
 * @param outgoingContent 发出气泡的文字及图标颜色。
 * @param roomId 当前真实房间号。
 * @param avatarRepository 当前授权的头像仓库。
 * @param ownUid 本人 UID，不使用房间号代替。
 * @param avatarRoomId 远端聊天的头像权限上下文，本地会话为 null。
 * @param messageImages 当前授权的图片缓存仓库。
 * 无返回值；可取消效果读取资料和图片缓存，缺失时请求网络并落地缓存，不触发通话。
 */
@Composable
private fun ChatMessageBubble(
    message: Message,
    contact: Contact,
    ownAvatar: String,
    ownName: String,
    outgoingColor: Color,
    outgoingContent: Color,
    roomId: String,
    avatarRepository: AvatarRepository?,
    messageImages: MessageImageRepository?,
    ownUid: String,
    avatarRoomId: String?,
    canRetrySend: Boolean,
    onRetrySend: (Message) -> Unit,
    pendingImage: PendingImageUpload?,
    onRetryImage: (String) -> Unit,
    onCancelImage: (String) -> Unit,
    onPreviewImage: (Message) -> Unit,
    onVideoCall: (() -> Unit)?,
    onAudioCall: (() -> Unit)?,
    peerUid: String?,
    onUserProfile: (UserProfileTarget) -> Unit,
) {
    val isEmoji = message.emoji != null
    val isImage = message.image != null
    val isVideo = message.body is VideoMessageBody
    val file = message.body as? FileMessageBody
    val upload = message.body as? TemporaryMessageBody
    val call = message.body as? RtcCallMessageBody
    val hasBubble = !isEmoji && !isImage && !isVideo
    val bubbleColor = when {
        !hasBubble -> Color.Transparent
        file != null || upload != null -> MaterialTheme.colorScheme.surface
        message.outgoing -> outgoingColor
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = if (message.outgoing && file == null && upload == null) {
        outgoingContent
    } else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!message.outgoing) {
            val uid = message.senderId.ifBlank { peerUid.orEmpty() }
            MessageProfileAvatar(
                uid = uid, name = if (contact.group) "" else contact.name,
                avatar = if (contact.group) "" else contact.avatar, avatarRepository = avatarRepository,
                sourceRoomId = avatarRoomId, tag = "message_avatar_${message.id}", onProfile = onUserProfile,
            )
            Spacer(Modifier.width(8.dp))
            if (hasBubble) BubbleTail(bubbleColor, false)
        }
        if (isImage && message.outgoing && file == null && upload == null) {
            Box(Modifier.width(32.dp).align(Alignment.CenterVertically)) {
                if (message.status in setOf("pending", "failed")) {
                    ChatImageUploadStatus(
                        busy = message.status == "pending",
                        error = if (message.status == "failed") pendingImage?.error ?: "图片发送失败，请重试" else null,
                        enabled = canRetrySend,
                        onRetry = {
                            if (pendingImage != null) onRetryImage(pendingImage.id) else onRetrySend(message)
                        },
                        onCancel = pendingImage?.takeUnless { it.queued }?.let { pending ->
                            { onCancelImage(pending.id) }
                        },
                    )
                }
            }
        } else if (message.outgoing && file == null && upload == null) {
            ChatMessageSendStatus(
                message = message,
                enabled = canRetrySend,
                onRetry = onRetrySend,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        Surface(
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 270.dp)
                .then(if (isImage) Modifier.testTag("message_image_${message.id}") else Modifier),
            shape = if (isEmoji || isImage || isVideo) RectangleShape else RoundedCornerShape(6.dp),
            color = bubbleColor, contentColor = contentColor,
        ) {
            if (isEmoji) {
                ChatEmoji(roomId, message, messageImages)
            } else if (isImage) {
                ChatImage(roomId, message, messageImages, pendingImage, onPreviewImage)
            } else if (isVideo) {
                ChatVideo(roomId, message)
            } else if (file != null) {
                ChatFile(file, roomId, message)
            } else if (upload != null) {
                ChatUploadPlaceholder(upload, message.id, roomId, message)
            } else if (call != null) {
                ChatRtcCall(call, ownUid, onVideoCall, onAudioCall)
            } else Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(message.text, fontSize = 16.sp, lineHeight = 23.sp)
            }
        }
        if (message.outgoing) {
            if (hasBubble) BubbleTail(bubbleColor, true)
            Spacer(Modifier.width(8.dp))
            MessageProfileAvatar(
                ownUid, ownName, ownAvatar, avatarRepository, null,
                "message_avatar_${message.id}", onUserProfile,
            )
        }
    }
}

@Composable
private fun MessageProfileAvatar(
    uid: String,
    name: String,
    avatar: String,
    avatarRepository: AvatarRepository?,
    sourceRoomId: String?,
    tag: String,
    onProfile: (UserProfileTarget) -> Unit,
) {
    val id = uid.toLongOrNull()?.takeIf { it > 0 }
    Box(
        Modifier.size(40.dp).testTag(tag).clickable(
            enabled = id != null, role = Role.Button, onClickLabel = "查看用户资料",
        ) {
            id?.let { onProfile(UserProfileTarget(
                it, name, avatar, sourceRoomId?.toLongOrNull()?.takeIf { room -> room > 0 },
            )) }
        },
    ) {
        Avatar(uid, name, 40.dp, avatarRepository, sourceRoomId, avatar)
    }
}

/**
 * 在气泡靠近头像的上缘绘制左右镜像的尖角，无返回值，仅绘制图形。
 * @param color 与消息气泡一致的背景色。
 * @param outgoing 是否指向右侧头像。
 */
@Composable
private fun BubbleTail(color: Color, outgoing: Boolean) {
    Canvas(Modifier.padding(top = 11.dp).size(width = 7.dp, height = 13.dp)) {
        val path = Path().apply {
            if (outgoing) {
                moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height)
            } else {
                moveTo(size.width, 0f); lineTo(0f, size.height / 2f); lineTo(size.width, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ChatSystemMessage(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
    )
}
