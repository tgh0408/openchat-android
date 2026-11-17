package com.im.openchat.common.ui

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.im.openchat.address.data.AddressRepository
import com.im.openchat.address.data.AddressProtocol
import com.im.openchat.address.data.UserProfileTarget
import com.im.openchat.address.ui.AddressBookState
import com.im.openchat.address.ui.AddressDetailPage
import com.im.openchat.address.ui.ContactsScreen
import com.im.openchat.address.ui.UserProfileRoute
import com.im.openchat.chat.data.delete
import com.im.openchat.chat.data.ConversationRepository
import com.im.openchat.chat.data.ConversationPreferencesRepository
import com.im.openchat.chat.data.ConversationReadRepository
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.GroupDetailsRepository
import com.im.openchat.chat.data.MessageHistoryRepository
import com.im.openchat.chat.data.MessageImageRepository
import com.im.openchat.chat.data.MessageVideoRepository
import com.im.openchat.chat.data.MessageFileRepository
import com.im.openchat.chat.data.MediaUploadRepository
import com.im.openchat.chat.data.ImageUploadRepository
import com.im.openchat.chat.data.ImageMessageBody
import com.im.openchat.chat.data.TextMessageBody
import com.im.openchat.chat.ui.LocalMessageFiles
import com.im.openchat.chat.ui.LocalMediaUploads
import com.im.openchat.chat.ui.MediaUploads
import com.im.openchat.chat.data.MessageSendRepository
import com.im.openchat.chat.data.MessageReceiveRepository
import com.im.openchat.chat.data.RoomType
import com.im.openchat.chat.data.asContact
import com.im.openchat.chat.data.formatChatTime
import com.im.openchat.chat.data.mergeConversations
import com.im.openchat.chat.data.open
import com.im.openchat.chat.data.send
import com.im.openchat.chat.data.toggleMute
import com.im.openchat.chat.data.togglePin
import com.im.openchat.chat.ui.ChatScreen
import com.im.openchat.chat.ui.ChatsScreen
import com.im.openchat.chat.ui.ConversationMenuDialog
import com.im.openchat.chat.ui.DeleteConversationDialog
import com.im.openchat.chat.ui.GroupDetailsRoute
import com.im.openchat.chat.ui.GroupCallMemberPicker
import com.im.openchat.chat.ui.GroupCallColors
import com.im.openchat.chat.ui.FriendDetailsRoute
import com.im.openchat.chat.ui.UnavailableChatActionDialog
import com.im.openchat.chat.ui.LocalVideoPlayback
import com.im.openchat.chat.ui.VideoPlaybackController
import com.im.openchat.chat.ui.VideoPlaybackHost
import com.im.openchat.chat.rtc.RtcRepository
import com.im.openchat.chat.rtc.RtcMediaMode
import com.im.openchat.chat.rtc.RtcVideoController
import com.im.openchat.chat.rtc.RtcVideoHost
import com.im.openchat.common.data.*
import com.im.openchat.common.config.AppConfig
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.sse.ResourceSseClient
import com.im.openchat.common.network.sse.SseConnectionType
import com.im.openchat.common.network.sse.SseCredentials
import com.im.openchat.common.network.sse.SseFailure
import com.im.openchat.common.network.sse.SseFailureKind
import com.im.openchat.common.network.sse.SseInvalidationReason
import com.im.openchat.common.network.sse.SseMessage
import com.im.openchat.common.network.sse.SseLogStage
import com.im.openchat.common.network.sse.SseLogger
import com.im.openchat.common.network.sse.SseStatus
import com.im.openchat.common.network.sse.SseEventType
import com.im.openchat.common.ui.theme.OpenChatTheme
import com.im.openchat.login.ui.LoginScreen
import com.im.openchat.login.data.AuthRepository
import com.im.openchat.login.data.AuthSession
import com.im.openchat.login.data.InvalidSessionException
import com.im.openchat.me.ui.EditProfileDialog
import com.im.openchat.me.ui.AccountExitAction
import com.im.openchat.me.ui.AccountExitDialog
import com.im.openchat.me.ui.ProfileScreen
import com.im.openchat.me.ui.SettingsPage
import com.im.openchat.me.ui.SettingsHomeScreen
import com.im.openchat.me.ui.SettingsMoreScreen
import com.im.openchat.me.update.AndroidUpdateHost
import com.im.openchat.me.update.AndroidUpdateBanner
import com.im.openchat.me.update.rememberAndroidUpdateState
import com.im.openchat.me.ui.profileSettingsParent
import com.im.openchat.me.data.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private data class AccountExitRequest(
    val action: AccountExitAction,
    val session: AuthSession,
    val api: ApiClient,
)

/**
 * 展示登录入口或应用主界面，并管理本地数据、页面导航及对话框。
 *
 * @param onResourceMessage 接收当前授权的原始推送；默认只记录事件类型，不解析或记录正文。
 * 无返回值；启动时验证已保存会话，登录或恢复授权后建立唯一 SSE，未登录时停留在登录页；
 * 授权后获取并缓存真实会话，SSE 通知和重连合并刷新列表及当前历史；摘要不充当聊天记录。
 * 打开真实会话立即展示缓存，详情、首次历史和未读异步校准；上滑按游标获取旧历史，关闭页面取消请求。
 * 授权失效时清除会话并保留账号，离开授权界面时取消请求及连接；可触发名片分享。
 */
@Composable
fun OpenChatApp(onResourceMessage: (SseMessage) -> Unit = ::receiveResourceMessage) {
    val context = LocalContext.current
    val appView = LocalView.current
    val windowForeground = rememberWindowForeground()
    val store = remember { ChatStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(ChatData()) }
    var fontScale by remember { mutableFloatStateOf(AppConfig.DEFAULT_FONT_SCALE) }
    var serverUrl by remember { mutableStateOf(AppConfig.DEFAULT_SERVER_URL) }
    var apiClient by remember { mutableStateOf<ApiClient?>(null) }
    var authRepository by remember { mutableStateOf<AuthRepository?>(null) }
    var authSession by remember { mutableStateOf<AuthSession?>(null) }
    var sseConnectionType by remember { mutableStateOf(SseConnectionType.RECONNECT) }
    var sseConnectionFailed by remember { mutableStateOf(false) }
    var sseNotice by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sseLifecycleMutex = remember { Mutex() }
    val connectivityManager = remember(context) {
        checkNotNull(context.getSystemService(ConnectivityManager::class.java))
    }
    val networkRecoveryRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    val sseReconnectRequests = remember { Channel<AuthSession>(Channel.CONFLATED) }
    val currentAuthSession by rememberUpdatedState(authSession)
    val currentSseConnectionFailed by rememberUpdatedState(sseConnectionFailed)
    val receiveMessage by rememberUpdatedState(onResourceMessage)
    var lastLoginAccount by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var restoringSession by remember { mutableStateOf(true) }
    var updateRequest by remember(serverUrl) { mutableIntStateOf(0) }
    val updateState = if (!restoringSession && apiClient != null) rememberAndroidUpdateState(serverUrl) else null
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chatId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupDetailsOpen by remember(apiClient, authSession, chatId) { mutableStateOf(false) }
    var friendDetailsOpen by remember(apiClient, authSession, data.owner, chatId) { mutableStateOf(false) }
    var groupCallPickerOpen by remember(apiClient, authSession, data.owner, chatId) { mutableStateOf(false) }
    var filePickerOpen by remember(apiClient, authSession, data.owner, chatId) { mutableStateOf(false) }
    var groupDetailsRefresh by remember(apiClient, authSession) { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var userProfile by remember(apiClient, authSession, data.owner, chatId, page, tab) {
        mutableStateOf<UserProfileTarget?>(null)
    }
    var menuId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var unavailableAction by remember { mutableStateOf<String?>(null) }
    var editProfile by remember { mutableStateOf(false) }
    var accountExitAction by remember { mutableStateOf<AccountExitAction?>(null) }
    var pendingAccountExit by remember { mutableStateOf<AccountExitRequest?>(null) }
    val pageTransitions = key(apiClient, authSession, data.owner, restoringSession, pendingAccountExit != null) {
        rememberPageSlideState()
    }
    var accountExitBusy by remember { mutableStateOf(false) }
    var accountExitError by remember { mutableStateOf<String?>(null) }
    val conversationRefreshes = remember { Channel<Unit>(Channel.CONFLATED) }
    val conversationSyncMutex = remember { Mutex() }
    val conversationStateMutex = remember { Mutex() }
    var loadingConversations by remember { mutableStateOf(false) }
    var conversationLoadingRequested by remember { mutableStateOf(false) }
    var conversationError by remember { mutableStateOf<String?>(null) }
    val historyRequests = remember { Channel<Boolean>(Channel.CONFLATED) }
    val messageReceipts = remember { Channel<Unit>(Channel.CONFLATED) }
    var historyRoomId by remember { mutableStateOf<String?>(null) }
    var loadingHistory by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var hasMoreHistory by remember { mutableStateOf(false) }
    var retryOlderHistory by remember { mutableStateOf(false) }
    var historyGeneration by remember { mutableLongStateOf(0L) }
    var readReadyRoomId by remember { mutableStateOf<String?>(null) }

    // 页面销毁即撤销本地访问，独立完成连接关闭；正常关闭应用保留加密会话。
    DisposableEffect(store) {
        onDispose {
            store.revokeDatabaseAccess()
            scope.launch(NonCancellable) {
                try {
                    store.closeDatabase()
                } catch (_: IOException) {
                    Log.w("OpenChatStorage", "内容库关闭失败，访问权限已撤销")
                }
            }
        }
    }

    DisposableEffect(connectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkRecoveryRequests.trySend(Unit)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    LaunchedEffect(networkRecoveryRequests) {
        for (ignored in networkRecoveryRequests) {
            val session = currentAuthSession
            if (session != null && currentSseConnectionFailed) {
                sseConnectionType = SseConnectionType.RECONNECT
                sseConnectionFailed = false
                sseReconnectRequests.trySend(session)
            }
        }
    }

    // 只在当前授权和归属内复用历史同步器，不跨账号复用内存中的对账状态。
    val historyRepository = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val owner = data.owner
        if (api != null && authSession != null && owner != null) MessageHistoryRepository(api, store, owner)
        else null
    }
    val messageSender = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val owner = data.owner
        if (api != null && authSession != null && owner != null) MessageSendRepository(api, store, owner)
        else null
    }
    val messageReceiver = remember(apiClient, authSession, data.owner) {
        data.owner?.takeIf { apiClient != null && authSession != null }?.let {
            MessageReceiveRepository(store, it)
        }
    }
    val conversationReads = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val owner = data.owner
        if (api != null && authSession != null && owner != null) {
            ConversationReadRepository(api, store, owner, conversationStateMutex)
        } else null
    }

    /**
     * 发布可信授权并隔离不同服务器或账号的本机快照，无返回值。
     * @param session 已完成服务端核验的登录会话。
     * 修改个人展示资料和内存授权；保留全局主题及通知偏好，不发起网络请求。
     */
    fun activateSession(session: AuthSession) {
        val owner = ChatDataOwner(serverUrl.trimEnd('/'), session.user.id)
        val profile = data.profile.copy(
            userId = session.user.id, name = session.user.nickname,
            bio = session.user.signature.orEmpty(), avatar = session.user.avatar.orEmpty(),
        )
        data = if (data.owner == owner) data.copy(profile = profile)
        else ChatData(owner = owner, profile = profile)
        chatId = null
        page = null
        menuId = null
        deleteId = null
        accountExitAction = null
        conversationError = null
        sseConnectionFailed = false
        loadingConversations = true
        authSession = session
    }

    // 启动时读取本地配置和账号，再由服务端核验保存的令牌；失效时只清除会话，不清除账号。
    LaunchedEffect(store) {
        try {
            fontScale = store.loadFontScale()
            serverUrl = storageServerIdentity(store.loadServerUrl())
            val api = ApiClient(context.applicationContext, serverUrl)
            val repository = AuthRepository(api)
            apiClient = api
            authRepository = repository
            lastLoginAccount = store.loadLastLoginAccount(serverUrl)
            data = store.load()
            if (data.owner != null && data.owner?.serverUrl != serverUrl.trimEnd('/')) {
                data = ChatData(profile = Profile(
                    darkMode = data.profile.darkMode, notifications = data.profile.notifications,
                ))
            }
            store.loadAuthSession(serverUrl)?.let { saved ->
                lastLoginAccount = saved.user.account.ifBlank { lastLoginAccount }
                val owner = ChatDataOwner(serverUrl, saved.user.id)
                try {
                    val validated = repository.validateSession(saved)
                    val cached = store.loadFor(owner, validated)
                    store.saveAuthSession(validated, owner)
                    data = cached
                    sseConnectionType = SseConnectionType.RECONNECT
                    activateSession(validated)
                } catch (exception: InvalidSessionException) {
                    restoreError = exception.message
                    try {
                        store.clearAuthSession()
                    } catch (_: IOException) {
                        restoreError = "登录已过期，但清除本地会话失败，请稍后重试"
                    }
                } catch (exception: IOException) {
                    withContext(NonCancellable) { store.closeDatabase(owner) }
                    api.updateToken(null)
                    restoreError = exception.message ?: "无法验证登录状态，请检查网络后重新登录"
                } catch (exception: CancellationException) {
                    withContext(NonCancellable) { store.closeDatabase(owner) }
                    api.updateToken(null)
                    throw exception
                }
            }
        } catch (exception: IOException) {
            restoreError = exception.message ?: "本地内容库读取失败，原数据已保留"
        } finally {
            restoringSession = false
        }
    }

    /**
     * 撤销仍为当前授权的失效会话并返回登录页，无返回值。
     *
     * @param expectedSession 触发失效通知的会话，旧会话的迟到通知不影响新授权。
     * @param api 该会话使用的共享客户端，仅清除其内存令牌。
     * @param reason 已由 SSE 框架核对的失效原因，HTTP 拒绝时可为空。
     * 立即清除授权和导航状态，再异步删除已保存会话；清理期间阻止新登录，避免误删新令牌。
     */
    fun invalidateSession(expectedSession: AuthSession, api: ApiClient, reason: SseInvalidationReason?) {
        if (authSession !== expectedSession || apiClient !== api) return
        store.revokeDatabaseAccess()
        authSession = null
        api.updateToken(null)
        chatId = null
        page = null
        menuId = null
        deleteId = null
        unavailableAction = null
        editProfile = false
        accountExitAction = null
        tab = 0
        sseConnectionFailed = false
        sseNotice = null
        restoreError = when (reason) {
            SseInvalidationReason.SESSION_REPLACED -> "账号已在其他设备登录，请重新登录"
            SseInvalidationReason.SESSION_EXPIRED, null -> "登录已过期，请重新登录"
        }
        restoringSession = true
        // 在应用作用域清理会话，避免授权效果因状态变化取消时中断删除；失败保留可见提示。
        scope.launch {
            try {
                store.clearAuthSession()
            } catch (_: IOException) {
                restoreError = "登录已失效，但清除本地会话失败，请稍后重试"
            } finally {
                restoringSession = false
            }
        }
    }

    fun completeAccountExit(request: AccountExitRequest) {
        if (accountExitBusy) return
        accountExitBusy = true
        accountExitError = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // 用户已确认退出，先完成本机认证删除；远端通知最多三秒，不依赖页面继续存活。
            withContext(NonCancellable) {
                try {
                    store.clearAuthSession()
                } catch (_: IOException) {
                    accountExitError = "清除本机登录状态失败，请重试。"
                    accountExitBusy = false
                    return@withContext
                }
                restoreError = try {
                    AuthRepository(request.api).logout(request.session)
                    null
                } catch (_: IOException) {
                    "已退出本机，但未能确认服务端退出，请检查网络。"
                }
                lastLoginAccount = when (request.action) {
                    AccountExitAction.SwitchAccount -> ""
                    AccountExitAction.SignOut -> request.session.user.account
                }
                pendingAccountExit = null
                accountExitBusy = false
            }
        }
    }

    fun exitAccount(action: AccountExitAction) {
        if (pendingAccountExit != null) return
        val session = authSession ?: return
        val api = apiClient ?: return
        val request = AccountExitRequest(action, session, api)
        store.revokeDatabaseAccess()
        pendingAccountExit = request
        accountExitAction = null
        authSession = null
        api.updateToken(null)
        chatId = null
        page = null
        menuId = null
        deleteId = null
        unavailableAction = null
        editProfile = false
        sseConnectionFailed = false
        sseNotice = null
        restoreError = null
        tab = 0
        data = ChatData(profile = Profile(
            darkMode = data.profile.darkMode, notifications = data.profile.notifications,
        ))
        completeAccountExit(request)
    }

    /**
     * 校验懒加载资源所属的账号和房间，无返回值，不允许已离群会话新增远端资源请求。
     * @param session 请求捕获的认证对象。
     * @param api 请求捕获的客户端。
     * @param owner 固定服务器和账号归属。
     * @param roomId 资料或图片所属的真实房间。
     * @param remote 是否准备读取远端；本地缓存允许已离群会话继续查看。
     * 旧授权或房间失效时抛取消异常；离群后的远端访问抛 IOException，不修改状态。
     */
    fun validateMedia(session: AuthSession, api: ApiClient, owner: ChatDataOwner, roomId: String, remote: Boolean) {
        val room = data.conversations.firstOrNull { it.contactId == roomId }
        if (authSession !== session || apiClient !== api || data.owner != owner || room?.roomType == null) {
            throw CancellationException("资源加载环境已变化")
        }
        if (remote && room.groupRemoved) throw IOException("已退出群聊，只可查看本地图片")
    }

    fun validateTextSend(session: AuthSession, api: ApiClient, owner: ChatDataOwner, roomId: String) {
        validateMedia(session, api, owner, roomId, false)
        if (data.conversations.firstOrNull { it.contactId == roomId }?.groupRemoved == true) {
            throw IOException("已退出群聊，无法发送消息")
        }
    }

    fun publishUnread(roomId: String, unread: Int) {
        data = data.copy(conversations = data.conversations.map {
            if (it.contactId == roomId) it.copy(unread = unread) else it
        })
    }

            // 已读持久化和重试属于授权生命周期，不属于聊天页面。
    LaunchedEffect(conversationReads) {
        val reads = conversationReads ?: return@LaunchedEffect
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        val owner = data.owner ?: return@LaunchedEffect
        reads.run(
            validate = {
                if (authSession !== session || apiClient !== api || data.owner != owner) {
                    throw CancellationException("已读同步授权已变化")
                }
            },
            publish = ::publishUnread,
            onUnauthorized = { invalidateSession(session, api, null) },
            onError = {
                Log.w("OpenChatRead", "已读确认暂未完成，已保留本地进度")
            },
        )
    }
    LaunchedEffect(conversationReads, windowForeground) {
            // 在前后台切换时提交已有已读，不在此处创建新的已读记录。
        conversationReads?.schedule(immediate = true)
    }

    // 当前授权单独复用头像资料仓库；列表和消息中的可见头像共享请求及 Room 缓存。
    val avatarRepository = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else AvatarRepository(
            api, store, owner,
            // UID 查询只校验原授权；聊天额外复核真实房间，离群后不发起远端资料请求。
            validate = { roomId, remote ->
                if (authSession !== session || apiClient !== api || data.owner != owner) {
                    throw CancellationException("头像加载授权已变化")
                }
                if (roomId != null) validateMedia(session, api, owner, roomId, remote)
            },
            // 仅撤销当前捕获的授权，迟到的拒绝响应不能退出新登录。
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    // 图片下载客户端与业务认证分离，仓库只保留应用上下文和当前授权。
    val messageImages = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else MessageImageRepository(
            context.applicationContext, api, owner,
            // 图片只服务当前打开的聊天，切换页面后旧请求不得继续下载或展示。
            validate = { roomId, remote ->
                validateMedia(session, api, owner, roomId, remote)
                if (chatId != roomId) throw CancellationException("图片所属聊天已关闭")
            },
            // 资源授权被拒绝时复用现有的登录失效处理，不向 OSS 发送 JWT。
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val messageVideos = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else MessageVideoRepository(
            context.applicationContext, api, store, owner,
            validate = { roomId, message, remote ->
                validateMedia(session, api, owner, roomId, remote)
                val current = data.conversations.firstOrNull { it.contactId == roomId }
                    ?.messages?.firstOrNull { it.id == message.id }
                if (current?.body != message.body) throw CancellationException("Video message changed")
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val videoPlayback = remember(messageVideos) { messageVideos?.let { VideoPlaybackController(it) } }
    val rtcCalls = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else {
            val validate = {
                if (apiClient !== api || authSession !== session || data.owner != owner) {
                    throw CancellationException("通话授权已变化")
                }
            }
            val repository = RtcRepository(
                api, session.accessToken, session.user.id, validate,
                rtcServerUrl = session.rtcServerUrl,
                validateRoom = { roomId, peerUid ->
                    validate()
                    val room = ConversationRepository(api).info(roomId)
                    validate()
                    if (room.roomType != RoomType.FRIEND || room.participantId != peerUid ||
                        peerUid == session.user.id || room.groupRemoved
                    ) throw IOException("当前会话不能发起单聊视频通话")
                },
                onUnauthorized = { invalidateSession(session, api, null) },
                validateGroup = { roomId, invitees ->
                    validate()
                    val room = ConversationRepository(api).info(roomId)
                    validate()
                    if (room.roomType != RoomType.GROUP || room.groupRemoved)
                        throw IOException("当前会话不能发起群语音通话")
                    val members = GroupDetailsRepository(api, session.user.id.toLong(),
                        validate = { validate() },
                        onUnauthorized = { invalidateSession(session, api, null) }).members(roomId)
                    validate()
                    val uids = members.map { it.uid.toString() }.toSet()
                    if (session.user.id !in uids || invitees.any { it !in uids })
                        throw IOException("群成员已变化，请重新选择")
                },
                // 来电独立于当前聊天导航，复用真实会话快照的群类型、群名和成员状态。
                incomingGroupName = { call ->
                    validate()
                    val room = ConversationRepository(api).info(call.roomId)
                    validate()
                    when (room.roomType) {
                        RoomType.GROUP -> {
                            if (room.groupRemoved) throw IOException("你已不在该群中")
                            room.title.takeIf { it.isNotBlank() } ?: throw IOException("群名称读取失败")
                        }
                        RoomType.FRIEND -> {
                            if (room.participantId != call.callerUid || room.groupRemoved)
                                throw IOException("来电与当前单聊会话不匹配")
                            null
                        }
                        else -> throw IOException("该房间不支持通话")
                    }
                },
            )
            RtcVideoController(context.applicationContext, repository) {
                conversationRefreshes.trySend(Unit)
                if (chatId != null) historyRequests.trySend(false)
            }
        }
    }
    DisposableEffect(rtcCalls) { onDispose { rtcCalls?.dispose() } }
    val rtcLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(rtcCalls, rtcLifecycle) {
        rtcCalls?.setForeground(rtcLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) rtcCalls?.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) rtcCalls?.setForeground(false)
        }
        rtcLifecycle.addObserver(observer)
        onDispose { rtcLifecycle.removeObserver(observer) }
    }
    val currentRtcCalls by rememberUpdatedState(rtcCalls)
    LaunchedEffect(rtcCalls, chatId, userProfile, groupDetailsOpen, friendDetailsOpen, groupCallPickerOpen) {
        rtcCalls?.cancelDial()
        val callRoom = rtcCalls?.target?.roomId
        if (rtcCalls?.incoming != true && rtcCalls?.detachedFromChat != true &&
            callRoom != null && (chatId != callRoom || userProfile != null || groupDetailsOpen ||
                friendDetailsOpen || groupCallPickerOpen)
        ) rtcCalls.end()
    }
    LaunchedEffect(rtcCalls?.target) {
        if (rtcCalls?.incoming == true) videoPlayback?.close()
        if (rtcCalls?.target?.let { !it.incoming && it.groupName != null } == true)
            groupCallPickerOpen = false
    }
    val messageFiles = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else MessageFileRepository(
            context.applicationContext, api, store, owner,
            validate = { roomId, message, remote ->
                validateMedia(session, api, owner, roomId, remote)
                if (chatId != roomId) throw CancellationException("文件所属聊天已关闭")
                val current = data.conversations.firstOrNull { it.contactId == roomId }
                    ?.messages?.firstOrNull { it.id == message.id }
                if (current?.body != message.body) throw CancellationException("文件消息已变化")
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val mediaUploadRepository = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else MediaUploadRepository(
            context.applicationContext, api, store, owner,
            validate = { roomId ->
                validateTextSend(session, api, owner, roomId)
                if (chatId != roomId) throw CancellationException("上传所属聊天已关闭")
            },
            latest = { roomId, clientId ->
                data.conversations.firstOrNull { it.contactId == roomId }?.messages
                    ?.firstOrNull { it.clientMsgId == clientId && it.senderId == owner.userId }
            },
            publish = { roomId ->
                conversationStateMutex.withLock {
                    validateTextSend(session, api, owner, roomId)
                    val result = store.loadMessageHistory(owner, roomId)
                    validateTextSend(session, api, owner, roomId)
                    data = data.copy(conversations = data.conversations.map {
                        if (it.contactId == roomId) it.copy(messages = result.messages, unread = result.unread ?: it.unread) else it
                    })
                }
                conversationRefreshes.trySend(Unit)
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val imageUploadRepository = remember(apiClient, authSession, data.owner, messageSender) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        val sender = messageSender
        if (api == null || session == null || owner == null || sender == null) null else ImageUploadRepository(
            context.applicationContext, api, owner, sender,
            validate = { roomId ->
                validateTextSend(session, api, owner, roomId)
                if (chatId != roomId) throw CancellationException("图片所属聊天已关闭")
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val mediaUploads = remember(mediaUploadRepository, imageUploadRepository) {
        mediaUploadRepository?.let { repository ->
            MediaUploads(repository, scope, imageUploadRepository) { snackbarHostState.showSnackbar(it) }
        }
    }
    DisposableEffect(mediaUploads, chatId) {
        onDispose { mediaUploads?.pauseAll(clearImageDisplay = true) }
    }
    DisposableEffect(mediaUploads, appView) {
        val lifecycle = appView.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) mediaUploads?.cancelImage()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(mediaUploads, windowForeground) {
        // Image selection can return before window focus; use Activity pause to stop image uploads.
        if (!windowForeground) mediaUploads?.pauseAll(includeImages = false)
    }
    val uploadRoomRemoved = data.conversations.firstOrNull { it.contactId == chatId }?.groupRemoved == true
    LaunchedEffect(mediaUploads, uploadRoomRemoved) {
        if (uploadRoomRemoved) mediaUploads?.pauseAll()
    }
    val uploadMessages = data.conversations.firstOrNull { it.contactId == chatId }?.messages.orEmpty()
    LaunchedEffect(mediaUploads, chatId, uploadMessages) {
        chatId?.let { mediaUploads?.reconcile(it, uploadMessages) }
    }

    // 发送任务绑定授权而非聊天页面；切换会话不丢消息，退出或换号则取消旧请求和确认计时。
    LaunchedEffect(messageSender) {
        val sender = messageSender ?: return@LaunchedEffect
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        val owner = data.owner ?: return@LaunchedEffect
        val histories = historyRepository ?: return@LaunchedEffect

        fun validateSend(roomId: String) {
            validateMedia(session, api, owner, roomId, false)
            if (data.conversations.firstOrNull { it.contactId == roomId }?.groupRemoved == true) {
                throw CancellationException("已退出群聊，停止消息发送")
            }
        }

        suspend fun publish(roomId: String) {
            conversationStateMutex.withLock {
                validateSend(roomId)
                val result = store.loadMessageHistory(owner, roomId)
                validateSend(roomId)
                data = data.copy(conversations = data.conversations.map {
                    if (it.contactId == roomId) it.copy(messages = result.messages, unread = result.unread ?: it.unread) else it
                })
            }
        }

        try {
            sender.prepare()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            snackbarHostState.showSnackbar(exception.message ?: "消息发送状态恢复失败，请重试")
        }
        sender.run(
            validate = ::validateSend,
            publish = ::publish,
            refresh = { roomId ->
                conversationRefreshes.trySend(Unit)
                if (chatId == roomId) historyRequests.trySend(false)
            },
            reconcile = { roomId ->
                if (data.conversations.firstOrNull { it.contactId == roomId }?.messages?.any { it.status == "pending" } == true) {
                    histories.sync(roomId, false) { validateSend(roomId) }
                    publish(roomId)
                }
            },
            onUnauthorized = { invalidateSession(session, api, null) },
            onError = { message ->
                if (authSession === session && apiClient === api) snackbarHostState.showSnackbar(message)
            },
        )
    }

    // 收件箱独立于当前聊天页面，文本和图片落库后立即发布，不等待限频的历史请求。
    LaunchedEffect(messageReceiver) {
        val receiver = messageReceiver ?: return@LaunchedEffect
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        val owner = data.owner ?: return@LaunchedEffect
        fun validateReceipt() {
            if (authSession !== session || apiClient !== api || data.owner != owner) {
                throw CancellationException("消息接收授权已变化")
            }
        }
        for (ignored in messageReceipts) {
            try {
                conversationStateMutex.withLock {
                    receiver.flush(
                        conversations = { data.conversations },
                        validate = ::validateReceipt,
                        publish = { roomId, result ->
                            data = data.copy(conversations = data.conversations.map {
                                if (it.contactId == roomId) it.copy(
                                    messages = result.messages, unread = result.unread ?: it.unread,
                                ) else it
                            })
                        },
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                SseLogger.failure(
                    stage = SseLogStage.EVENT_CALLBACK,
                    exception = exception,
                    eventType = SseEventType.MESSAGE,
                    failure = SseFailure(SseFailureKind.INTERNAL, "SSE 聊天消息保存失败"),
                )
                sseNotice = "消息接收失败，请刷新会话重试"
            }
        }
    }

    // 每个授权仅有一个顺序同步任务；合并快速刷新并限制频率，取消时同时取消底层 HTTP。
    LaunchedEffect(apiClient, authSession) {
        val api = apiClient
        val session = authSession
        if (api == null || session == null) {
            loadingConversations = false
            conversationLoadingRequested = false
            conversationError = null
            return@LaunchedEffect
        }
        val repository = ConversationRepository(api)
        val owner = data.owner ?: return@LaunchedEffect
        while (conversationRefreshes.tryReceive().isSuccess) { /* 丢弃上一授权遗留的刷新信号。 */ }
        conversationLoadingRequested = true
        conversationRefreshes.trySend(Unit)
        var lastRequestAt = 0L
        for (ignored in conversationRefreshes) {
            // 自动同步静默执行；独立保留手动意图，避免被合并的 SSE 信号覆盖。
            loadingConversations = conversationLoadingRequested
            conversationLoadingRequested = false
            delay((4000L - (SystemClock.elapsedRealtime() - lastRequestAt)).coerceAtLeast(0L).milliseconds)
            if (authSession !== session || apiClient !== api) return@LaunchedEffect
            conversationError = null
            try {
                // 列表与点击详情顺序请求并保存，旧列表响应不会覆盖较新的详情快照。
                conversationSyncMutex.withLock {
                    if (authSession !== session || apiClient !== api) return@LaunchedEffect
                    lastRequestAt = SystemClock.elapsedRealtime()
                    val rooms = repository.list()
                    if (authSession !== session || apiClient !== api) return@LaunchedEffect
                    conversationStateMutex.withLock {
                        if (authSession !== session || apiClient !== api || data.owner != owner) return@LaunchedEffect
                        val next = data.mergeConversations(rooms)
                        store.save(next, remoteSnapshots = rooms)
                        val unread = store.loadUnreadCounts(owner)
                        if (authSession !== session || apiClient !== api || data.owner != owner) return@LaunchedEffect
                        data = data.mergeConversations(rooms).let { merged ->
                            merged.copy(conversations = merged.conversations.map {
                                it.copy(unread = unread[it.contactId] ?: it.unread)
                            })
                        }
                        // 首条推送可能早于房间快照；主表保存后再尝试落库暂存文本。
                        if (authSession === session && apiClient === api) messageReceipts.trySend(Unit)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: InvalidSessionException) {
                invalidateSession(session, api, null)
            } catch (exception: IOException) {
                if (authSession === session && apiClient === api) {
                    conversationError = exception.message ?: "获取会话失败，请检查网络后重试"
                }
            } finally {
                if (authSession === session && apiClient === api) {
                    loadingConversations = conversationLoadingRequested
                }
            }
        }
    }

    // 通讯录关系按 PC 只驻留当前授权；公开头像缓存仍复用原内容库。
    val addressBook = remember(apiClient, authSession, data.owner) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else AddressBookState(
            AddressRepository(
                api, session.user.id.toLong(),
                validate = {
                    if (authSession !== session || apiClient !== api || data.owner != owner) {
                        throw CancellationException("通讯录授权已变化")
                    }
                },
                onUnauthorized = { invalidateSession(session, api, null) },
            ),
        )
    }
    val groupDetailsRepository = remember(apiClient, authSession, data.owner, chatId) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else GroupDetailsRepository(
            api, session.user.id.toLong(),
            validate = { room ->
                if (apiClient !== api || authSession !== session || data.owner != owner ||
                    chatId != room || !groupDetailsOpen || userProfile != null ||
                    data.conversations.none { it.contactId == room && it.roomType == RoomType.GROUP && !it.groupRemoved }
                ) throw CancellationException("群详情页面或授权已变化")
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val groupCallPickerAllowed = !groupDetailsOpen && !friendDetailsOpen && userProfile == null &&
        pendingAccountExit == null && !(videoPlayback?.let { it.request != null && !it.floating } == true) &&
        data.conversations.any { it.contactId == chatId && it.roomType == RoomType.GROUP && !it.groupRemoved }
    val currentGroupCallPickerAllowed by rememberUpdatedState(groupCallPickerAllowed)
    LaunchedEffect(groupCallPickerAllowed, rtcCalls) {
        if (!groupCallPickerAllowed) {
            groupCallPickerOpen = false
            if (rtcCalls?.dialTarget?.groupName != null) rtcCalls.cancelDial()
        }
    }
    // 通话选择页独立校验可见性，不借用必须打开群详情的仓库授权。
    val groupCallMembersRepository = remember(apiClient, authSession, data.owner, chatId, groupCallPickerAllowed) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null || !groupCallPickerAllowed) null
        else GroupDetailsRepository(
            api, session.user.id.toLong(),
            validate = { room ->
                if (apiClient !== api || authSession !== session || data.owner != owner ||
                    chatId != room || !groupCallPickerOpen || groupDetailsOpen || friendDetailsOpen ||
                    userProfile != null || pendingAccountExit != null ||
                    videoPlayback?.let { it.request != null && !it.floating } == true ||
                    data.conversations.none { it.contactId == room && it.roomType == RoomType.GROUP && !it.groupRemoved }
                ) throw CancellationException("群通话成员选择页面或授权已变化")
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    val conversationPreferences = remember(apiClient, authSession, data.owner, chatId) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null) null else ConversationPreferencesRepository(
            api,
            validate = { room ->
                val conversation = data.conversations.firstOrNull { it.contactId == room }
                val visible = when (conversation?.roomType) {
                    RoomType.GROUP -> groupDetailsOpen && !conversation.groupRemoved
                    RoomType.FRIEND -> friendDetailsOpen
                    else -> false
                }
                if (apiClient !== api || authSession !== session || data.owner != owner ||
                    chatId != room || !visible || userProfile != null
                ) throw CancellationException("聊天信息页面或授权已变化")
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }
    LaunchedEffect(addressBook) { addressBook?.run() }
    LaunchedEffect(addressBook, tab, windowForeground) {
        if (windowForeground && tab == 1) addressBook?.refresh()
    }
    // 通讯录群可能没有本地会话，不能用聊天的本地房间校验阻止合法头像请求。
    val directoryAvatars = remember(addressBook) {
        val api = apiClient
        val session = authSession
        val owner = data.owner
        if (api == null || session == null || owner == null || addressBook == null) null else AvatarRepository(
            api, store, owner,
            validate = { roomId, remote ->
                if (authSession !== session || apiClient !== api || data.owner != owner) {
                    throw CancellationException("通讯录头像授权已变化")
                }
                if (remote && roomId != null && addressBook.groups.none { it.roomId.toString() == roomId }) {
                    throw CancellationException("通讯录群已失效")
                }
            },
            onUnauthorized = { invalidateSession(session, api, null) },
        )
    }

    val historyConversation = data.conversations.firstOrNull { it.contactId == chatId }
    LaunchedEffect(historyConversation?.groupRemoved) {
        if (historyConversation?.groupRemoved == true) {
            groupDetailsOpen = false
            userProfile = null
        }
    }
            // 这些标记只延迟入口角标，不延迟聊天页面或其缓存内容。
    var entryDetailPrepared by remember(apiClient, authSession, data.owner, chatId) { mutableStateOf(false) }
    var entryHistoryPrepared by remember(apiClient, authSession, data.owner, chatId) { mutableStateOf(false) }
    // 每次进入真实会话单独刷新详情，不依赖历史成功，也不随消息或未读数变化重复请求。
    LaunchedEffect(apiClient, authSession, data.owner, chatId, historyConversation?.roomType) {
        val roomId = chatId ?: return@LaunchedEffect
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        val owner = data.owner ?: return@LaunchedEffect
        if (historyConversation?.roomType == null) return@LaunchedEffect
        fun isCurrent(): Boolean = authSession === session && apiClient === api &&
            data.owner == owner && chatId == roomId &&
            data.conversations.any { it.contactId == roomId && it.roomType != null }

        try {
            conversationSyncMutex.withLock {
                if (!isCurrent()) return@LaunchedEffect
                val room = ConversationRepository(api).info(roomId)
                if (!isCurrent()) return@LaunchedEffect
                conversationStateMutex.withLock {
                    if (!isCurrent()) return@LaunchedEffect
                    store.save(data.mergeConversations(listOf(room)), remoteSnapshots = listOf(room))
                    val unread = store.loadUnreadCounts(owner)
                    if (!isCurrent()) return@LaunchedEffect
                    data = data.mergeConversations(listOf(room)).let { merged ->
                        merged.copy(conversations = merged.conversations.map {
                            it.copy(unread = unread[it.contactId] ?: it.unread)
                        })
                    }
                }
                if (isCurrent()) entryDetailPrepared = true
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: InvalidSessionException) {
            invalidateSession(session, api, null)
        } catch (exception: IOException) {
            if (isCurrent()) {
                entryDetailPrepared = true
                snackbarHostState.showSnackbar(
                    exception.message ?: "刷新会话详情失败，已保留本地数据，请重新进入会话重试",
                )
            }
        }
    }

    // 页面、授权或成员状态变化即取消旧 HTTP；同一页面的分页和刷新由单个消费者顺序执行。
    LaunchedEffect(apiClient, authSession, chatId, historyConversation?.roomType, historyConversation?.groupRemoved) {
        val generation = ++historyGeneration
        historyRoomId = chatId
        readReadyRoomId = null
        loadingHistory = false
        historyError = null
        hasMoreHistory = false
        retryOlderHistory = false
        while (historyRequests.tryReceive().isSuccess) { /* 丢弃上一页面的命令，不复用其分页方向。 */ }
        val roomId = chatId ?: return@LaunchedEffect
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        val owner = data.owner ?: return@LaunchedEffect
        val repository = historyRepository ?: return@LaunchedEffect
        if (historyConversation?.roomType == null) return@LaunchedEffect

        /**
         * 拒绝旧页面、旧授权及离群后的网络结果，无返回值。
         * 校验失败抛出取消异常，不修改状态、存储或令牌。
         */
        fun validateHistory() {
            val room = data.conversations.firstOrNull { it.contactId == roomId }
            if (historyGeneration != generation || authSession !== session || apiClient !== api || data.owner != owner ||
                chatId != roomId || room?.roomType == null || room.groupRemoved
            ) throw CancellationException("当前会话历史请求已失效")
        }

        /**
         * 将缓存结果合并到当前房间，不覆盖其他页面同时更新的资料或会话摘要。
         * 从 Room 读取当前消息、校准未读数和分页状态，不写入整份业务快照。
         */
        suspend fun publishHistory() = conversationStateMutex.withLock {
            val result = store.loadMessageHistory(owner, roomId)
            if (historyGeneration != generation || authSession !== session || apiClient !== api ||
                chatId != roomId || data.owner != owner
            ) return@withLock
            // 更新当前房间的消息和校准未读，保留名称、头像及其他房间。
            data = data.copy(conversations = data.conversations.map { room ->
                if (room.contactId == roomId) room.copy(messages = result.messages, unread = result.unread ?: room.unread) else room
            })
            hasMoreHistory = result.hasMore && !(
                data.conversations.firstOrNull { it.contactId == roomId }?.groupRemoved ?: true
            )
        }

        var prepared = false
        var lastLatestRequest = 0L
        historyRequests.trySend(false)
        for (older in historyRequests) {
                    // 后台继续补查时保留已完成的首次同步状态。
            loadingHistory = true
            historyError = null
            retryOlderHistory = older
            try {
                // 初次使用先确保房间主表存在，再读取该账号已缓存的历史；不把摘要构造成消息。
                if (!prepared) {
                    store.save(data)
                    publishHistory()
                    prepared = true
                }
                if (data.conversations.firstOrNull { it.contactId == roomId }?.groupRemoved == true) {
                    hasMoreHistory = false
                    return@LaunchedEffect
                }
                if (!older) {
                    delay((4000L - (SystemClock.elapsedRealtime() - lastLatestRequest)).coerceAtLeast(0L).milliseconds)
                    lastLatestRequest = SystemClock.elapsedRealtime()
                }
                validateHistory()
                repository.sync(roomId, older, ::validateHistory)
                publishHistory()
                if (!older) readReadyRoomId = roomId
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: InvalidSessionException) {
                invalidateSession(session, api, null)
                return@LaunchedEffect
            } catch (exception: IOException) {
                if (historyGeneration == generation && authSession === session && apiClient === api && chatId == roomId) {
                    if (!older) readReadyRoomId = null
                    historyError = exception.message ?: "读取聊天记录失败，请重试"
                    try {
                        // 失败仍展示已成功落库的中间页，不将异常解释为历史已读完。
                        publishHistory()
                    } catch (_: IOException) {
                        historyError = "聊天记录读取失败，请重试"
                    }
                }
            } finally {
                if (historyGeneration == generation && authSession === session && apiClient === api && chatId == roomId) {
                    loadingHistory = false
                    entryHistoryPrepared = true
                }
            }
        }
    }

    // 仅对已验证授权建连；跨效果互斥，确保旧效果取消并释放完成后才启动新连接。
    LaunchedEffect(apiClient, authSession) {
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        // 持有授权连接生命周期锁；即使 Compose 的新效果已启动，也要等待旧效果完成 finally。
        sseLifecycleMutex.withLock {
            // 连接及订阅都属于当前授权效果，取消时统一等待释放，不另建脱离生命周期的任务。
            coroutineScope {
                val sse = ResourceSseClient(api, this)
                val credentials = SseCredentials(session.user.id, session.accessToken, session.expiresAt)
                // 先完成原始事件订阅再建连，核对授权对象和客户端，过滤旧会话的迟到事件。
                launch(start = CoroutineStart.UNDISPATCHED) {
                    // 接收当前授权的原始事件并调用上层回调；失败记录受控堆栈并提示，不停止后续接收。
                    sse.messages.collect { message ->
                        if (authSession !== session || apiClient !== api ||
                            message.userId != session.user.id
                        ) return@collect
                        if (message.type == SseEventType.RTC) currentRtcCalls?.event(message.data)
                        // 快照和历史仍负责补齐缺口，已接入的文本和图片另行即时落库。
                        if (message.type == SseEventType.MESSAGE) {
                            if (AddressProtocol.isRefreshNotification(message.data, session.user.id.toLong())) {
                                addressBook?.refresh()
                                groupDetailsRefresh++
                            }
                            conversationRefreshes.trySend(Unit)
                            if (chatId != null) historyRequests.trySend(false)
                            try {
                                if (messageReceiver?.enqueue(message.data) == true) messageReceipts.trySend(Unit)
                            } catch (exception: IOException) {
                                SseLogger.failure(
                                    stage = SseLogStage.EVENT_CALLBACK,
                                    exception = exception,
                                    eventType = message.type,
                                    failure = SseFailure(SseFailureKind.PROTOCOL, "SSE 聊天消息数据无效"),
                                )
                                sseNotice = "消息推送数据无效，正在通过历史记录补查"
                            }
                        }
                        try {
                            receiveMessage(message)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            SseLogger.failure(
                                stage = SseLogStage.EVENT_CALLBACK,
                                exception = exception,
                                eventType = message.type,
                                failure = SseFailure(SseFailureKind.INTERNAL, "SSE 推送接收回调失败"),
                            )
                            sseNotice = "推送处理失败，请稍后重试"
                        }
                    }
                }
                // 先订阅状态再建连；状态枚举用于连接日志，不记录请求或账号信息。
                launch(start = CoroutineStart.UNDISPATCHED) {
                    // 接收当前账号的连接状态；网络失败提示后继续重试，授权失效立即返回登录页。
                    sse.state.collect { connection ->
                        if (authSession !== session || apiClient !== api) return@collect
                        if (connection.userId != session.user.id) return@collect
                        Log.d("OpenChatSse", "SSE 连接状态=${connection.status.name}")
                        when {
                            connection.failure?.kind == SseFailureKind.AUTHENTICATION ->
                                invalidateSession(session, api, connection.invalidationReason)
                            connection.failure != null -> {
                                sseConnectionFailed = true
                            }
                            connection.status == SseStatus.CONNECTED -> {
                                sseConnectionFailed = false
                                sseNotice = null
                                currentRtcCalls?.wake()
                                conversationReads?.schedule(immediate = true)
                                // 首次建连前也可能断网；每次连接成功都补查，不能依赖曾经连接成功。
                                addressBook?.refresh()
                                groupDetailsRefresh++
                                // 重连可能留下真实缺口，完成补查后才能恢复已读。
                                readReadyRoomId = null
                                conversationRefreshes.trySend(Unit)
                                if (chatId != null) historyRequests.trySend(false)
                            }
                        }
                    }
                }
                try {
                    sse.start(
                        credentials = credentials,
                        connectionType = sseConnectionType,
                    )
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        for (requestSession in sseReconnectRequests) {
                            if (requestSession !== session ||
                                authSession !== session ||
                                apiClient !== api
                            ) continue
                            sse.stop()
                            while (sseReconnectRequests.tryReceive().isSuccess) {
                                // 合并同一授权作用域内同时到达的重连请求。
                            }
                            if (authSession !== session || apiClient !== api) continue
                            sse.start(
                                credentials = credentials,
                                connectionType = SseConnectionType.RECONNECT,
                            )
                        }
                    }
                    awaitCancellation()
                } catch (exception: IllegalArgumentException) {
                    SseLogger.failure(
                        stage = SseLogStage.APP_START,
                        exception = exception,
                        connectionType = sseConnectionType,
                        failure = SseFailure(SseFailureKind.INTERNAL, "SSE 建连参数无效"),
                    )
                    sseConnectionFailed = true
                } finally {
                    // 取消后仍等待网络释放，无返回值；持锁期间不会建立下一条授权连接。
                    withContext(NonCancellable) { sse.stop() }
                }
            }
        }
    }

    // 展示连接或接收故障，通知变化时取消旧提示；不阻塞 SSE 的事件和状态订阅。
    LaunchedEffect(sseNotice) {
        // 将当前非空提示交给 Snackbar，等待其关闭，无业务状态或存储副作用。
        sseNotice?.let { message -> snackbarHostState.showSnackbar(message) }
    }

    /**
     * 用给定数据更新界面并异步保存到本机，失败展示提示，无返回值。
     * @param next 待展示和持久化的完整数据。
     */
    fun update(next: ChatData) {
        data = next
        // 将完整聊天快照写入 DataStore，不阻塞当前 UI 回调。
        scope.launch {
            if (data.owner != next.owner) return@launch
            try {
                store.save(next)
            } catch (_: IOException) {
                snackbarHostState.showSnackbar("本地数据保存失败，请重试")
            }
        }
    }

    // 进入个人页补齐本人资料；不依赖只含 UID/令牌的登录响应，也不重建授权及 SSE。
    LaunchedEffect(apiClient, authSession, tab == 2) {
        if (tab != 2) return@LaunchedEffect
        val api = apiClient ?: return@LaunchedEffect
        val session = authSession ?: return@LaunchedEffect
        val owner = data.owner ?: return@LaunchedEffect
        val previousProfile = data.profile
        fun isCurrent(): Boolean =
            tab == 2 && apiClient === api && authSession === session && data.owner == owner
        try {
            val user = AuthRepository(api).loadCurrentUser(session.user.copy(nickname = previousProfile.name))
            if (!isCurrent()) return@LaunchedEffect
            conversationStateMutex.withLock {
                if (!isCurrent()) return@LaunchedEffect
                val current = data.profile
                // 请求期间用户若编辑了资料，保留本次编辑；主题及通知始终使用当前值。
                val nextProfile = current.copy(
                    name = if (current.name == previousProfile.name) user.nickname else current.name,
                    avatar = if (current.avatar == previousProfile.avatar) user.avatar.orEmpty() else current.avatar,
                    bio = if (current.bio == previousProfile.bio) user.signature.orEmpty() else current.bio,
                )
                data = data.copy(profile = nextProfile)
                store.save(data)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: InvalidSessionException) {
            invalidateSession(session, api, null)
        } catch (_: IOException) {
            if (isCurrent()) snackbarHostState.showSnackbar("个人资料加载或保存失败，请重新进入“我”重试")
        }
    }

    /**
     * 打开指定本地联系人或真实房间；本地会话清除未读，远端由详情和实际阅读校准未读。
     * @param id 本地联系人标识或远端 roomId。
     */
    fun open(id: String) {
        if (chatId != id) pageTransitions.navigate {
            val next = data.open(id)
            if (next !== data) update(next)
            chatId = id
        }
    }

/** 将偏好和快照串行处理；只有服务端确认的目标值才能发布。 */
    suspend fun setConversationPreference(room: String, pinned: Boolean? = null, muted: Boolean? = null) {
        val repository = conversationPreferences ?: throw IOException("聊天信息尚未就绪")
        val api = apiClient ?: throw IOException("请重新登录")
        val session = authSession ?: throw IOException("请重新登录")
        val owner = data.owner ?: throw IOException("请重新登录")
        val type = data.conversations.firstOrNull { it.contactId == room }?.roomType
        fun validate() {
            val visible = when (type) {
                RoomType.GROUP -> groupDetailsOpen
                RoomType.FRIEND -> friendDetailsOpen
                else -> false
            }
            if (apiClient !== api || authSession !== session || data.owner != owner ||
                chatId != room || !visible || userProfile != null ||
                data.conversations.none { it.contactId == room && it.roomType == type && !it.groupRemoved }
            ) throw CancellationException("聊天信息页面或授权已变化")
        }
        conversationSyncMutex.withLock {
            validate()
            try {
                if (pinned != null) repository.setPinned(room, pinned)
                if (muted != null) repository.setMuted(room, muted)
            } finally {
                // 已取消的 HTTP 请求可能已经在服务端提交，需要重新对账快照。
                if (apiClient === api && authSession === session && data.owner == owner) {
                    conversationRefreshes.trySend(Unit)
                }
            }
            conversationStateMutex.withLock {
                validate()
                fun next() = data.copy(conversations = data.conversations.map {
                    if (it.contactId == room) it.copy(pinned = pinned ?: it.pinned, muted = muted ?: it.muted) else it
                })
                store.save(next())
                validate()
                data = next()
            }
        }
    }

    suspend fun exitCurrentGroup(room: String) {
        val repository = groupDetailsRepository ?: throw IOException("群信息尚未就绪")
        val api = apiClient ?: throw IOException("请重新登录")
        val session = authSession ?: throw IOException("请重新登录")
        val owner = data.owner ?: throw IOException("请重新登录")
        conversationSyncMutex.withLock {
            repository.exit(room)
            if (apiClient !== api || authSession !== session || data.owner != owner) {
                throw CancellationException("退出群聊授权已变化")
            }
            addressBook?.refresh()
            conversationRefreshes.trySend(Unit)
            conversationStateMutex.withLock {
                if (apiClient !== api || authSession !== session || data.owner != owner) {
                    throw CancellationException("退出群聊授权已变化")
                }
                fun next() = data.copy(conversations = data.conversations.map {
                    if (it.contactId == room) it.copy(groupRemoved = true, unread = 0) else it
                })
                try {
                    store.save(next())
                } catch (exception: IOException) {
                    scope.launch { snackbarHostState.showSnackbar("已退出群聊，本机状态保存失败，请刷新会话") }
                }
                if (apiClient !== api || authSession !== session || data.owner != owner) {
                    throw CancellationException("退出群聊授权已变化")
                }
                // 发布结果后直接退出，不挂起等待；销毁流程不能取消本地保存。
                data = next()
                if (chatId == room) {
                    groupDetailsOpen = false
                    chatId = null
                }
            }
        }
    }

    fun openUserProfile(target: UserProfileTarget) {
        if (addressBook == null || userProfile != null) return
        if (target.sourceRoomId != null &&
            data.conversations.any { it.contactId == target.sourceRoomId.toString() && it.groupRemoved }
        ) return
        pageTransitions.navigate { userProfile = target }
    }

    /** 用户点击发消息后恢复真实房间，保存原始快照但不推进已读，不创建假会话。 */
    suspend fun openDirectoryRoom(roomId: Long, type: RoomType, uid: Long?) {
        val api = apiClient ?: throw IOException("请重新登录")
        val session = authSession ?: throw IOException("请重新登录")
        val owner = data.owner ?: throw IOException("请重新登录")
        val sourcePage = page
        val sourceProfile = userProfile
        val sourceChat = chatId
        // 所有异步等待后复核原页面及授权，失效时不打开迟到的房间。
        fun validate() {
            if (apiClient !== api || authSession !== session || data.owner != owner ||
                page != sourcePage || userProfile !== sourceProfile || chatId != sourceChat
            ) throw CancellationException("通讯录页面已变化")
        }
        try {
            validate()
            if (type == RoomType.GROUP &&
                addressBook?.repository?.listGroups()?.none { it.roomId == roomId } != false
            ) throw IOException("你已不在该群中，或群聊已解散")
            conversationSyncMutex.withLock {
                validate()
                val room = ConversationRepository(api).info(roomId.toString())
                validate()
                if (room.roomType != type || room.groupRemoved ||
                    type == RoomType.FRIEND && room.participantId != uid?.toString()
                ) throw IOException("通讯录与会话身份不匹配，请刷新重试")
                conversationStateMutex.withLock {
                    validate()
                    store.save(data.mergeConversations(listOf(room)), remoteSnapshots = listOf(room))
                    val unread = store.loadUnreadCounts(owner)
                    validate()
                    data = data.mergeConversations(listOf(room)).let { merged ->
                        merged.copy(conversations = merged.conversations.map {
                            it.copy(unread = unread[it.contactId] ?: it.unread)
                        })
                    }
                    messageReceipts.trySend(Unit)
                    pageTransitions.navigate {
                        validate()
                        userProfile = null
                        groupDetailsOpen = false
                        friendDetailsOpen = false
                        chatId = room.contactId
                    }
                }
            }
        } catch (exception: InvalidSessionException) {
            invalidateSession(session, api, null)
            throw exception
        }
    }

    // 依次关闭会话、子页、回到会话标签或返回登录；离开主页时撤销内存授权以取消 SSE，保留已存会话。
    BackHandler(enabled = authSession != null && !restoringSession) {
        when {
            userProfile != null -> pageTransitions.back { userProfile = null }
            groupCallPickerOpen -> pageTransitions.back { groupCallPickerOpen = false }
            friendDetailsOpen -> pageTransitions.back { friendDetailsOpen = false }
            groupDetailsOpen -> pageTransitions.back { groupDetailsOpen = false }
            chatId != null -> pageTransitions.back {
                conversationReads?.schedule(immediate = true)
                chatId = null
            }
            page != null -> pageTransitions.back { page = profileSettingsParent(page) }
            tab != 0 -> pageTransitions.back { tab = 0 }
            else -> {
                authSession = null
                apiClient?.updateToken(null)
                sseNotice = null
            }
        }
    }
    BackHandler(enabled = pendingAccountExit != null) { }

    OpenChatTheme(darkTheme = data.profile.darkMode, fontScale = fontScale) {
        val view = LocalView.current
        val groupCallPageVisible = groupCallPickerOpen && groupCallPickerAllowed &&
            groupCallMembersRepository != null && !restoringSession && authSession != null
        // 根据当前主题更新系统栏图标颜色，无参数、无返回值。
        if (!view.isInEditMode) SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !data.profile.darkMode && !groupCallPageVisible
                isAppearanceLightNavigationBars = !data.profile.darkMode && !groupCallPageVisible
            }
        }
        PageSlideHost(pageTransitions) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = if (groupCallPageVisible) GroupCallColors.background else MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // 接收标签索引并切换主页，无返回值。
                if (!restoringSession && authSession != null && chatId == null && page == null && userProfile == null) {
                    BottomTabs(tab, data.conversations.sumOf { it.unread }) { next ->
                        pageTransitions.switchTab(tab, next) { tab = next }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val activeConversation = data.conversations.firstOrNull { it.contactId == chatId }
                val contact = if (activeConversation?.roomType != null) activeConversation.asContact()
                else data.contacts.firstOrNull { it.id == chatId }
                Box(Modifier.fillMaxSize().then(
                    if (userProfile != null) Modifier.clearAndSetSemantics {} else Modifier,
                )) {
                when {
                    pendingAccountExit != null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    restoringSession -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    authSession == null -> LoginScreen(
                        serverUrl = serverUrl,
                        initialAccount = lastLoginAccount,
                        initialError = restoreError,
                        // 接收校验后的服务器地址，切换认证客户端并写入私有存储，无返回值，不发送网络请求。
                        onSaveServer = { value ->
                            store.revokeDatabaseAccess()
                            apiClient?.updateToken(null)
                            restoringSession = true
                            // 保存成功才切换客户端，期间阻止登录，避免迟到的配置写入删除新会话。
                            scope.launch {
                                try {
                                    val normalized = storageServerIdentity(value)
                                    store.saveServerUrl(normalized)
                                    serverUrl = normalized
                                    val api = ApiClient(context.applicationContext, normalized)
                                    apiClient = api
                                    authRepository = AuthRepository(api)
                                    lastLoginAccount = ""
                                    restoreError = null
                                    data = ChatData(profile = Profile(
                                        darkMode = data.profile.darkMode, notifications = data.profile.notifications,
                                    ))
                                    chatId = null
                                    page = null
                                } catch (exception: IOException) {
                                    restoreError = exception.message ?: "服务器配置保存失败，请重试"
                                } finally {
                                    restoringSession = false
                                }
                            }
                        },
                        // 使用当前服务器地址请求登录并保存服务端返回的认证会话，无密码写入本地存储。
                        onLogin = { account, password ->
                            val repository = authRepository
                                ?: throw IllegalStateException("请先在服务器页设置服务器地址")
                            val api = apiClient
                                ?: throw IllegalStateException("请先在服务器页设置服务器地址")
                            val loginServer = serverUrl
                            val session = repository.login(account, password)
                            val owner = ChatDataOwner(loginServer, session.user.id)
                            try {
                                if (apiClient !== api || authSession != null) {
                                    throw CancellationException("登录环境已变化")
                                }
                                val cached = store.loadFor(owner, session)
                                if (apiClient !== api || authSession != null) {
                                    throw CancellationException("登录环境已变化")
                                }
                                store.saveAuthSession(session, owner)
                                if (apiClient !== api || authSession != null) {
                                    throw CancellationException("登录环境已变化")
                                }
                                data = cached
                            } catch (exception: Exception) {
                                withContext(NonCancellable) { store.closeDatabase(owner) }
                                api.updateToken(null)
                                throw exception
                            }
                            session
                        },
                        // 会话已验证并保存；发布本次授权及 LOGIN 建连原因，进入主页后由效果启动唯一 SSE。
                        onLoginSuccess = {
                            lastLoginAccount = it.user.account
                            restoreError = null
                            sseConnectionType = SseConnectionType.LOGIN
                            activateSession(it)
                        },
                    )
                    contact != null -> CompositionLocalProvider(
                        LocalVideoPlayback provides videoPlayback, LocalMessageFiles provides messageFiles,
                        LocalMediaUploads provides mediaUploads,
                    ) {
                        key(contact.id) { ChatScreen(
                        contact = contact,
                        conversation = activeConversation ?: Conversation(contact.id),
                        ownAvatar = data.profile.avatar,
                        ownName = data.profile.name,
                        ownUid = data.profile.userId,
                        avatarRepository = avatarRepository,
                        messageImages = messageImages,
                        detailsOpen = groupDetailsOpen || friendDetailsOpen || userProfile != null ||
                            groupCallPickerOpen || (rtcCalls?.windowVisible == true && !rtcCalls.floating),
                        callWindowOpen = rtcCalls?.windowVisible == true,
                        onGroupCall = if (groupCallMembersRepository != null && rtcCalls != null) ({
                            if (!pageTransitions.running) {
                                mediaUploads?.pauseAll()
                                videoPlayback?.close()
                                rtcCalls.beginGroup(contact.id, contact.name, contact.avatar) {
                                    if (currentRtcCalls === rtcCalls && chatId == contact.id &&
                                        currentGroupCallPickerAllowed && !pageTransitions.running) {
                                        pageTransitions.navigate {
                                            if (currentRtcCalls === rtcCalls && chatId == contact.id &&
                                                currentGroupCallPickerAllowed &&
                                                rtcLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                                groupCallPickerOpen = true
                                            }
                                        }
                                    }
                                }
                            }
                        }) else null,
                        onAudioCall = if (rtcCalls != null && activeConversation?.roomType == RoomType.FRIEND) ({
                            if (!pageTransitions.running) {
                                val peer = activeConversation.participantId
                                if (!peer.isNullOrBlank()) {
                                    mediaUploads?.pauseAll()
                                    videoPlayback?.close()
                                    rtcCalls.begin(
                                        contact.id, peer, contact.name, contact.avatar,
                                        mediaMode = RtcMediaMode.AUDIO,
                                    )
                                }
                            }
                        }) else null,
                        onVideoCall = if (rtcCalls != null && activeConversation?.roomType == RoomType.FRIEND) ({
                            if (!pageTransitions.running) {
                                val peer = activeConversation.participantId
                                if (!peer.isNullOrBlank()) {
                                    mediaUploads?.pauseAll()
                                    videoPlayback?.close()
                                    rtcCalls.begin(contact.id, peer, contact.name, contact.avatar)
                                }
                            }
                        }) else null,
                        onUserProfile = ::openUserProfile,
                        onFilePickerVisibilityChanged = { filePickerOpen = it },
                        preparingUnread = activeConversation?.roomType != null &&
                            (!entryHistoryPrepared || !entryDetailPrepared && readReadyRoomId == contact.id),
                        loadingHistory = historyRoomId == contact.id && loadingHistory,
                        historyError = historyError.takeIf { historyRoomId == contact.id && entryHistoryPrepared },
                        hasMoreHistory = historyRoomId == contact.id && hasMoreHistory,
                        readReady = entryHistoryPrepared && historyRoomId == contact.id && readReadyRoomId == contact.id &&
                            !(loadingHistory && retryOlderHistory) &&
                            activeConversation?.lastMsgId != null && activeConversation.lastReadMsgId != null,
                        onRead = { messageId ->
                            val reads = conversationReads
                            val api = apiClient
                            val session = authSession
                            val owner = data.owner
                            val roomId = contact.id
                            if (reads != null && api != null && session != null && owner != null) scope.launch {
                                try {
                                    reads.record(
                                        roomId, messageId,
                                        canRecord = {
                                            entryHistoryPrepared && !pageTransitions.running &&
                                                chatId == roomId && !groupDetailsOpen && !friendDetailsOpen &&
                                                !groupCallPickerOpen && !filePickerOpen && userProfile == null && rtcCalls?.windowVisible != true &&
                                                historyRoomId == roomId && readReadyRoomId == roomId &&
                                                !(loadingHistory && retryOlderHistory) && historyError == null &&
                                                data.conversations.any { it.contactId == roomId && !it.groupRemoved } &&
                                                appView.hasWindowFocus() &&
                                                appView.findViewTreeLifecycleOwner()?.lifecycle?.currentState
                                                    ?.isAtLeast(Lifecycle.State.RESUMED) == true
                                        },
                                        validate = {
                                            if (authSession !== session || apiClient !== api || data.owner != owner) {
                                                throw CancellationException("已读记录授权已变化")
                                            }
                                        },
                                        publish = ::publishUnread,
                                    )
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: IOException) {
                                    if (authSession === session && apiClient === api) {
                                        if (chatId == roomId) readReadyRoomId = null
                                        snackbarHostState.showSnackbar(exception.message ?: "保存已读进度失败，请重新进入会话")
                                    }
                                }
                            }
                        },
                        // 顶部触发旧历史分页；授权效果合并请求，不在 UI 回调直接进行网络读写。
                        onLoadOlder = { if (!loadingHistory && historyError == null) historyRequests.trySend(true) },
                        // 重试原分页方向；同步器会先修复失败的最新区间，再沿已保存游标请求旧页。
                        onRetryHistory = { if (!loadingHistory) historyRequests.trySend(retryOlderHistory) },
                        // 关闭当前会话，无参数、无返回值，仅修改导航状态。
                        onBack = { pageTransitions.back {
                            conversationReads?.schedule(immediate = true)
                            chatId = null
                        } },
                        // 打开当前会话菜单，无参数、无返回值。
                        onMenu = {
                            if (activeConversation?.roomType == RoomType.GROUP && !activeConversation.groupRemoved) {
                                pageTransitions.navigate { groupDetailsOpen = true }
                            } else if (activeConversation?.roomType == RoomType.FRIEND) {
                                pageTransitions.navigate { friendDetailsOpen = true }
                            } else menuId = contact.id
                        },
                        // 将尚未接入的输入或附件功能交给已有提示框，不发起网络请求。
                        onUnavailable = { unavailableAction = it },
                        // 真实会话先持久化待发送文本；成功入队后输入框才清空草稿。
                        onSend = { text ->
                            if (activeConversation?.roomType != null) {
                                val sender = messageSender ?: throw IOException("消息发送尚未就绪，请稍后重试")
                                val api = apiClient ?: throw IOException("请重新登录后发送消息")
                                val session = authSession ?: throw IOException("请重新登录后发送消息")
                                val owner = data.owner ?: throw IOException("请重新登录后发送消息")
                                validateTextSend(session, api, owner, contact.id)
                                store.save(data)
                                sender.queue(contact.id, text, validate = {
                                    validateTextSend(session, api, owner, contact.id)
                                })
                            } else {
                                val time = formatChatTime(System.currentTimeMillis(), "HH:mm")
                                update(data.send(contact.id, text, UUID.randomUUID().toString(), time))
                            }
                        },
                        onRetrySend = { message ->
                            val sender = messageSender
                            val api = apiClient
                            val session = authSession
                            val owner = data.owner
                            val clientMsgId = message.clientMsgId
                            if (sender != null && api != null && session != null && owner != null &&
                                clientMsgId != null && message.status == "failed"
                            ) scope.launch {
                                try {
                                    val validate = {
                                        validateTextSend(session, api, owner, contact.id)
                                    }
                                    when (val body = message.body) {
                                        is ImageMessageBody -> sender.queueImage(contact.id, body, validate, clientMsgId)
                                        is TextMessageBody -> sender.queue(contact.id, body.content, validate, clientMsgId)
                                        else -> throw IOException("该消息无法重试发送")
                                    }
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: IOException) {
                                    if (authSession === session && apiClient === api) {
                                        snackbarHostState.showSnackbar(exception.message ?: "消息重试失败，请稍后重试")
                                    }
                                }
                            }
                        },
                        ) }
                        if (groupCallPickerOpen && groupCallPickerAllowed && groupCallMembersRepository != null) {
                            Surface(Modifier.fillMaxSize()) {
                                GroupCallMemberPicker(
                                    repository = groupCallMembersRepository, room = contact.id,
                                    avatarRepository = avatarRepository,
                                    ownAvatar = data.profile.avatar,
                                    onBack = { pageTransitions.back { groupCallPickerOpen = false } },
                                    onStart = { members ->
                                        if (!pageTransitions.running && rtcCalls != null && members.isNotEmpty()) {
                                            mediaUploads?.pauseAll()
                                            videoPlayback?.close()
                                            rtcCalls.begin(contact.id, members.first().uid.toString(),
                                                contact.name, contact.avatar, groupName = contact.name,
                                                inviteeUids = members.map { it.uid.toString() },
                                                memberNames = members.associate { it.uid.toString() to it.label })
                                        }
                                    },
                                )
                            }
                        }
                        if (groupDetailsOpen && activeConversation?.roomType == RoomType.GROUP &&
                            !activeConversation.groupRemoved && groupDetailsRepository != null && addressBook != null
                        ) key(groupDetailsRepository, contact.id) {
                            Surface(Modifier.fillMaxSize()) {
                                GroupDetailsRoute(
                                    conversation = activeConversation,
                                    repository = groupDetailsRepository,
                                    addressRepository = addressBook.repository,
                                    avatarRepository = avatarRepository,
                                    imageNamespace = requireNotNull(data.owner).let {
                                        "group-qr\n${it.serverUrl}\n${it.userId}"
                                    },
                                    refreshVersion = groupDetailsRefresh,
                                    onBack = { pageTransitions.back { groupDetailsOpen = false } },
                                    onChanged = {
                                        addressBook.refresh()
                                        conversationRefreshes.trySend(Unit)
                                        historyRequests.trySend(false)
                                    },
                                    onPinned = { setConversationPreference(contact.id, pinned = it) },
                                    onMuted = { setConversationPreference(contact.id, muted = it) },
                                    onExit = { exitCurrentGroup(contact.id) },
                                    onUnavailable = { unavailableAction = it },
                                    onUserProfile = ::openUserProfile,
                                    covered = userProfile != null,
                                )
                            }
                        }
                        if (friendDetailsOpen && activeConversation?.roomType == RoomType.FRIEND &&
                            conversationPreferences != null
                        ) key(conversationPreferences, contact.id, activeConversation.participantId) {
                            Surface(Modifier.fillMaxSize()) {
                                FriendDetailsRoute(
                                    conversation = activeConversation, avatarRepository = avatarRepository,
                                    onBack = { pageTransitions.back { friendDetailsOpen = false } },
                                    onPinned = { setConversationPreference(contact.id, pinned = it) },
                                    onMuted = { setConversationPreference(contact.id, muted = it) },
                                    onUnavailable = { unavailableAction = it },
                                    onUserProfile = ::openUserProfile,
                                    covered = userProfile != null,
                                )
                            }
                        }
                    }
                    page in setOf("新的朋友", "添加朋友", "群聊") && addressBook != null ->
                        key(addressBook, page) {
                            AddressDetailPage(
                                page = page!!, book = addressBook,
                                avatarRepository = directoryAvatars,
                                onBack = { pageTransitions.back { page = null } },
                                onOpenRoom = ::openDirectoryRoom,
                                onUserProfile = ::openUserProfile,
                                onChanged = {
                                    addressBook.refresh()
                                    conversationRefreshes.trySend(Unit)
                                },
                            )
                        }
                    page == "设置" -> SettingsHomeScreen(
                        data = data, onBack = { pageTransitions.back { page = null } },
                        onPage = {
                            when (it) {
                                "个人资料" -> editProfile = true
                                "切换账号" -> accountExitAction = AccountExitAction.SwitchAccount
                                "退出登录" -> accountExitAction = AccountExitAction.SignOut
                                "个人信息与权限", "通知", "界面与显示", "更多" -> pageTransitions.navigate { page = it }
                                else -> unavailableAction = it
                            }
                        },
                    )
                    page == "更多" -> SettingsMoreScreen(
                        data = data, onBack = { pageTransitions.back { page = "设置" } },
                        // 更新打开应用级弹窗，其余入口保持已有设置导航。
                        onPage = {
                            if (it == "检查更新") updateRequest += 1
                            else pageTransitions.navigate { page = it }
                        },
                    )
                    page != null -> SettingsPage(
                        page = page!!, data = data,
                        // 返回设置入口，无参数、无返回值，仅修改导航状态。
                        onBack = { pageTransitions.back { page = profileSettingsParent(page) } }, onUpdate = ::update,
                        fontScale = fontScale,
                        // 接收字体比例并先持久化，成功后更新全局字号；写入失败交由设置页提示。
                        onFontScaleChange = { next ->
                            store.saveFontScale(next)
                            fontScale = next
                        },
                    )
                    tab == 0 -> ChatsScreen(
                        data = data,
                        updateNotice = { updateState?.let { AndroidUpdateBanner(it) } },
                        avatarRepository = avatarRepository,
                        loading = loadingConversations,
                        error = conversationError,
                        canRefresh = authSession != null,
                        sseConnectionFailed = sseConnectionFailed && authSession != null,
                        onSseReconnect = {
                            authSession?.let { session ->
                                sseConnectionType = SseConnectionType.RECONNECT
                                sseConnectionFailed = false
                                sseReconnectRequests.trySend(session)
                            }
                        },
                        // 手动请求同步真实会话；由唯一授权任务合并信号，不在 UI 回调直接请求网络。
                        onRefresh = {
                            conversationLoadingRequested = true
                            loadingConversations = true
                            conversationRefreshes.trySend(Unit)
                        },
                        onOpen = ::open,
                        // 添加朋友进入真实搜索和申请入口。
                        onNew = { action ->
                            when (action) {
                                "添加朋友" -> pageTransitions.navigate { page = "添加朋友" }
                                else -> unavailableAction = action
                            }
                        },
                    )
                    tab == 1 -> key(addressBook) { ContactsScreen(
                        friends = addressBook?.friends.orEmpty(),
                        unread = addressBook?.unread?.size ?: 0,
                        loading = addressBook?.friendsLoading == true,
                        error = addressBook?.friendsError,
                        unreadError = addressBook?.unreadError,
                        avatarRepository = directoryAvatars,
                        onFriend = { openUserProfile(UserProfileTarget(it.uid, it.displayName, friend = it)) },
                        onRequests = { pageTransitions.navigate { page = "新的朋友" } },
                        onGroups = { pageTransitions.navigate { page = "群聊" } },
                        onAdd = { pageTransitions.navigate { page = "添加朋友" } },
                        onRefresh = { addressBook?.refresh() },
                    ) }
                    else -> ProfileScreen(
                        data = data,
                        avatarRepository = avatarRepository,
                        // 打开资料编辑框，无参数、无返回值。
                        onEdit = { editProfile = true },
                        onPage = {
                            when (it) {
                                "设置" -> pageTransitions.navigate { page = it }
                                else -> unavailableAction = it
                            }
                        },
                    )
                }
                }
                val target = userProfile
                if (target != null && addressBook != null && authSession != null && !restoringSession) {
                    Surface(Modifier.fillMaxSize()) {
                        UserProfileRoute(
                            repository = addressBook.repository, target = target,
                            avatarRepository = if (target.sourceRoomId != null) avatarRepository else directoryAvatars,
                            onBack = { pageTransitions.back { userProfile = null } },
                            onOpenRoom = ::openDirectoryRoom,
                            onUnavailable = { unavailableAction = it },
                            onEditSelf = { editProfile = true },
                            onFriendRemoved = { uid ->
                                addressBook.removeFriend(uid)
                                conversationRefreshes.trySend(Unit)
                            },
                            onChanged = {
                                addressBook.refresh()
                                conversationRefreshes.trySend(Unit)
                            },
                        )
                    }
                }
            }
        }

        }

        rtcCalls?.let { RtcVideoHost(it, avatarRepository) }

        val menu = data.conversations.firstOrNull { it.contactId == menuId }
        if (menu != null) ConversationMenuDialog(
            title = menu.title.ifBlank { data.contacts.firstOrNull { it.id == menu.contactId }?.name.orEmpty() },
            conversation = menu,
            // 关闭会话菜单，无参数、无返回值。
            onDismiss = { menuId = null },
            // 本地会话切换置顶；远端写接口尚未接入，提示后关闭菜单，不伪造服务器状态。
            onTogglePin = {
                if (menu.roomType == null) update(data.togglePin(menu.contactId))
                else unavailableAction = "会话置顶"
                menuId = null
            },
            // 本地会话切换免打扰；远端仅显示已同步状态，不改写为未确认的本地值。
            onToggleMute = {
                if (menu.roomType == null) update(data.toggleMute(menu.contactId))
                else unavailableAction = "消息免打扰"
                menuId = null
            },
            // 本地会话打开删除确认；远端删除尚未接入，避免列表下一次同步时伪装恢复。
            onDelete = {
                if (menu.roomType == null) deleteId = menu.contactId
                else unavailableAction = "删除会话"
                menuId = null
            },
        )
        deleteId?.let { id ->
            DeleteConversationDialog(
                // 取消删除并关闭确认框，无参数、无返回值。
                onDismiss = { deleteId = null },
                // 删除会话并保存，必要时退出聊天页面，再关闭确认框，无参数、无返回值。
                onConfirm = {
                    update(data.delete(id)); if (chatId == id) chatId = null; deleteId = null
                },
            )
        }
        unavailableAction?.let { action ->
            UnavailableChatActionDialog(
                action = action,
                // 关闭提示并清除当前选项，无参数、无返回值，不修改业务数据。
                onDismiss = { unavailableAction = null },
            )
        }
        if (authSession != null) accountExitAction?.let { action ->
            AccountExitDialog(
                action = action,
                onDismiss = { accountExitAction = null },
                onConfirm = { exitAccount(action) },
            )
        }
        pendingAccountExit?.let { request ->
            accountExitError?.let { error ->
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("退出未完成") },
                    text = { Text(error) },
                    confirmButton = {
                        TextButton(onClick = { completeAccountExit(request) }, enabled = !accountExitBusy) {
                            Text("重试")
                        }
                    },
                )
            }
        }
        if (editProfile) EditProfileDialog(
            profile = data.profile,
            // 取消资料编辑，无参数、无返回值，不保存草稿。
            onDismiss = { editProfile = false },
            // 接收资料并保存，然后关闭编辑框，无返回值。
            onSave = { update(data.copy(profile = it)); editProfile = false },
        )
        VideoPlaybackHost(videoPlayback)
        if (updateState != null) {
            key(serverUrl) {
                AndroidUpdateHost(updateState, updateRequest)
            }
        }
    }
}

/**
 * 接收当前授权的原始事件并记录到达信息，无返回值，不解析或持久化业务数据。
 *
 * @param message SSE 事件，仅记录受控枚举类型，不输出令牌、账号、事件 ID 或正文。
 * 后续业务通过 OpenChatApp 的 onResourceMessage 回调接入，不在此处猜测业务协议。
 */
private fun receiveResourceMessage(message: SseMessage) {
    Log.d("OpenChatSse", "收到 SSE 推送，事件类型=${message.type.name}")
}
