package com.im.openchat.common.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.im.openchat.common.config.AppConfig
import com.im.openchat.chat.data.HistoryMessage
import com.im.openchat.chat.data.MessageBody
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.PendingConversationRead
import com.im.openchat.chat.data.MessageHistoryResult
import com.im.openchat.chat.data.MessageHistoryState
import com.im.openchat.login.data.AuthSession
import com.im.openchat.login.data.AuthUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicReference

/**
 * 为应用提供偏好与加密认证存储，以及按服务器和账号隔离的加密 Room 会话库。
 *
 * @param Context 应用上下文；DataStore 文件位于应用私有 files/datastore 目录。
 */
internal val Context.openChatDataStore by preferencesDataStore(name = "openchat_local")

class ChatStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val dataStore = applicationContext.openChatDataStore
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val authSessionKey = stringPreferencesKey("auth_session")
    private val lastLoginAccountKey = stringPreferencesKey("last_login_account")
    private val fontScaleKey = floatPreferencesKey("font_scale")
    private val darkModeKey = booleanPreferencesKey("dark_mode")
    private val notificationsKey = booleanPreferencesKey("notifications")
    private var conversationDatabaseOwner: ChatDataOwner? = null
    private var conversationDatabase: ConversationDatabase? = null
    private val authorizedOwner = AtomicReference<ChatDataOwner?>(null)
    private val databaseMutex = Mutex()

    /**
     * 读取全局字体缩放，未配置、值无效或读取失败时使用默认比例。
     *
     * @return 限制在允许范围内的字体缩放比例，不修改存储。
     */
    suspend fun loadFontScale(): Float {
        val saved = try {
            dataStore.data.first()[fontScaleKey]
        } catch (_: IOException) {
            null
        }
        return if (saved != null && saved.isFinite()) {
            saved.coerceIn(AppConfig.MIN_FONT_SCALE, AppConfig.MAX_FONT_SCALE)
        } else {
            AppConfig.DEFAULT_FONT_SCALE
        }
    }

    /**
     * 保存全局字体缩放偏好，不修改聊天快照、服务器地址或认证信息。
     *
     * @param value 有限且位于允许范围内的字体缩放比例。
     * 无返回值；异步写入独立的字体配置键，非法值或写入失败时抛出异常。
     */
    suspend fun saveFontScale(value: Float) {
        require(value.isFinite() && value in AppConfig.MIN_FONT_SCALE..AppConfig.MAX_FONT_SCALE) {
            "字体缩放比例超出允许范围"
        }
        // 仅更新全局字号，保留同一 DataStore 中的其他配置。
        dataStore.edit { preferences ->
            preferences[fontScaleKey] = value
        }
    }

    /**
     * 读取已保存的服务器地址，未设置、仅含空白或读取失败时使用统一配置中的默认地址。
     *
     * @return 用户设置的服务器地址或默认地址，不修改存储内容。
     */
    suspend fun loadServerUrl(): String = try {
        dataStore.data.first()[serverUrlKey].orEmpty().ifBlank { AppConfig.DEFAULT_SERVER_URL }
    } catch (_: IOException) {
        AppConfig.DEFAULT_SERVER_URL
    }

    /**
     * 保存服务器地址到 Preferences DataStore，无返回值。
     *
     * @param value 已校验并规范化的服务器地址。
     * 关闭内容库，并在同次配置写入中清除原服务的认证及账号提示，不改写聊天记录。
     */
    suspend fun saveServerUrl(value: String) {
        val server = storageServerIdentity(value)
        closeDatabase()
        dataStore.edit { preferences ->
            preferences[serverUrlKey] = server
            preferences.remove(authSessionKey)
            preferences.remove(lastLoginAccountKey)
        }
    }

    /**
     * 解密当前服务器的认证会话；不存在时返回 null，无法解密时清除无效密文并要求重新登录。
     *
     * @param serverUrl 本次请求所属服务器，必须与保存密文时一致。
     * @return 待服务端重新校验的会话，不因此开放内容库。
     */
    suspend fun loadAuthSession(serverUrl: String): AuthSession? = withContext(Dispatchers.IO) {
        val saved = dataStore.data.first()[authSessionKey] ?: return@withContext null
        try {
            decodeAuthSession(LocalCredentialCipher.decrypt(saved, "session", serverUrl))
        } catch (exception: GeneralSecurityException) {
            discardUnreadableSession(saved)
            throw IOException("本地认证信息无法解密，请重新登录", exception)
        } catch (exception: IllegalArgumentException) {
            discardUnreadableSession(saved)
            throw IOException("本地认证信息无效，请重新登录", exception)
        } catch (exception: org.json.JSONException) {
            discardUnreadableSession(saved)
            throw IOException("本地认证信息无效，请重新登录", exception)
        } catch (exception: ProviderException) {
            throw IOException("系统密钥存储暂不可用，请重试", exception)
        }
    }

    /** 只移除本次读取的无效认证值，不解析旧明文，也不覆盖期间保存的新会话。 */
    private suspend fun discardUnreadableSession(saved: String) {
        dataStore.edit { preferences ->
            if (preferences[authSessionKey] == saved) preferences.remove(authSessionKey)
        }
    }

    /**
     * 从独立账号键读取上次成功登录的账号。
     *
     * @param serverUrl 当前服务器，用于认证密文归属。
     * @return 保存的账号或空字符串；删除无法解密的账号提示，不恢复密码或失效令牌。
     */
    suspend fun loadLastLoginAccount(serverUrl: String): String = withContext(Dispatchers.IO) {
        val preferences = try {
            dataStore.data.first()
        } catch (_: IOException) {
            return@withContext ""
        }
        val saved = preferences[lastLoginAccountKey] ?: return@withContext ""
        try {
            LocalCredentialCipher.decrypt(saved, "account", serverUrl)
        } catch (_: GeneralSecurityException) {
            discardUnreadableAccount(saved)
            ""
        } catch (_: IllegalArgumentException) {
            discardUnreadableAccount(saved)
            ""
        } catch (exception: ProviderException) {
            throw IOException("系统密钥存储暂不可用，请重试", exception)
        }
    }

    /** 删除无法恢复的账号提示，保留其他设置，不覆盖期间写入的新账号。 */
    private suspend fun discardUnreadableAccount(saved: String) {
        dataStore.edit { preferences ->
            if (preferences[lastLoginAccountKey] == saved) preferences.remove(lastLoginAccountKey)
        }
    }

    /**
     * 使用 Keystore 加密核验后的完整会话及账号提示，并原子写入 DataStore。
     *
     * @param session 已通过登录接口和会话校验的认证会话。
     * @param owner 本次登录已打开的内容库归属。
     * 无返回值；同时保存规范化服务器地址，不修改聊天数据。
     */
    suspend fun saveAuthSession(session: AuthSession, owner: ChatDataOwner): Unit = withContext(Dispatchers.IO) {
        try {
            databaseMutex.withLock {
                databaseFor(owner)
                require(session.user.id == owner.userId) { "认证账号与内容库不一致" }
                val encrypted = LocalCredentialCipher.encrypt(encodeAuthSession(session), "session", owner.serverUrl)
                val account = LocalCredentialCipher.encrypt(session.user.account, "account", owner.serverUrl)
                dataStore.edit { preferences ->
                    databaseFor(owner)
                    preferences[serverUrlKey] = storageServerIdentity(owner.serverUrl)
                    preferences[authSessionKey] = encrypted
                    preferences[lastLoginAccountKey] = account
                }
                Unit
            }
        } catch (exception: GeneralSecurityException) {
            throw IOException("无法安全保存认证信息，请重试", exception)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("无法安全保存认证信息，请重试", exception)
        }
    }

    /**
     * 清除本地认证会话，保留上次登录账号、聊天数据和服务器地址。
     *
     * 无参数、无返回值；关闭内容库并擦除驱动密码，只删除认证会话键。
     */
    suspend fun clearAuthSession() {
        closeDatabase()
        dataStore.edit { preferences ->
            preferences.remove(authSessionKey)
        }
    }

    /**
     * 只恢复未授权页面可用的全局设置，不打开账号内容库。
     *
     * @return 仅含全局设置的空业务快照，不读取其他存储格式。
     */
    suspend fun load(): ChatData {
        val preferences = dataStore.data.first()
        val darkMode = preferences[darkModeKey] ?: false
        val notifications = preferences[notificationsKey] ?: true
        return ChatData(profile = com.im.openchat.me.data.Profile(
            darkMode = darkMode, notifications = notifications,
        ))
    }

    /** 用服务端刚校验的密码打开当前账号库；读取失败或取消即关闭，不回退明文或旧密码。 */
    suspend fun loadFor(owner: ChatDataOwner, session: AuthSession): ChatData {
        currentCoroutineContext().ensureActive()
        require(session.user.id == owner.userId) { "认证账号与内容库不一致" }
        val password = session.clientSqlPassword
            ?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: throw IOException("服务端未提供有效的内容库密钥，请重新登录")
        authorizedOwner.set(owner)
        try {
            return withContext(Dispatchers.IO) {
                databaseMutex.withLock {
                    if (authorizedOwner.get() !== owner) throw CancellationException("内容库授权已变化")
                    closeDatabaseLocked()
                    val preferences = dataStore.data.first()
                    val opened = ConversationDatabase(
                        applicationContext, encryptedDatabaseName(owner), password,
                        server = storageServerIdentity(owner.serverUrl),
                    )
                    conversationDatabase = opened
                    conversationDatabaseOwner = owner
                    val loaded = opened.loadData(
                        ownerId = owner.userId,
                        darkMode = preferences[darkModeKey] ?: false,
                        notifications = preferences[notificationsKey] ?: true,
                        server = owner.serverUrl,
                    ).copy(owner = owner)
                    ensureActive()
                    databaseFor(owner)
                    loaded
                }
            }
        } catch (exception: CancellationException) {
            withContext(NonCancellable) { closeDatabase(owner) }
            throw exception
        } catch (exception: IOException) {
            withContext(NonCancellable) { closeDatabase(owner) }
            throw exception
        } catch (exception: RuntimeException) {
            withContext(NonCancellable) { closeDatabase(owner) }
            throw IOException("加密内容库打开失败，请确认账号密钥，原数据已保留", exception)
        }
    }

    /**
     * 将联系人、资料、申请、会话和消息写入当前账号的 Room 数据库。
     *
     * @param data 待保存的联系人、会话、申请和设置。
     * @param remoteSnapshots 仅传原始网络快照；普通界面保存不改写服务端未读基数。
     * 该挂起操作更新 Room 业务数据及 DataStore 中的主题、通知偏好，不改写服务器地址和认证会话。
     * 远端消息及历史游标由独立分页事务维护；取消直接传播，不转换为存储失败。
     */
    suspend fun save(data: ChatData, remoteSnapshots: List<Conversation> = emptyList()) {
        try {
            // 业务快照和历史页共用连接锁，数据库事务与连接切换不会交错。
            val owner = data.owner ?: throw IOException("请先登录再访问本地数据")
            databaseMutex.withLock { databaseFor(owner).save(data, remoteSnapshots) }
            dataStore.edit { preferences ->
                preferences[darkModeKey] = data.profile.darkMode
                preferences[notificationsKey] = data.profile.notifications
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("本地会话数据库保存失败", exception)
        }
    }

    internal suspend fun loadUnreadCounts(owner: ChatDataOwner): Map<String, Int> =
        readOperation(owner) { it.loadUnreadCounts() }

    internal suspend fun loadVideoFile(owner: ChatDataOwner, key: String): String? =
        videoOperation(owner) { it.loadVideoFile(key) }

    internal suspend fun saveVideoFile(owner: ChatDataOwner, key: String, path: String) =
        videoOperation(owner) { it.saveVideoFile(key, path) }

    internal suspend fun loadAttachmentFile(owner: ChatDataOwner, key: String): String? =
        videoOperation(owner) { it.loadAttachmentFile(key) }

    internal suspend fun saveAttachmentFile(owner: ChatDataOwner, key: String, path: String) =
        videoOperation(owner) { it.saveAttachmentFile(key, path) }

    internal suspend fun saveOutgoingUpload(
        owner: ChatDataOwner, roomId: String, clientMsgId: String, deviceId: String,
        source: com.im.openchat.chat.data.MediaUploadSource,
    ) = videoOperation(owner) { it.saveOutgoingUpload(roomId, owner.userId, clientMsgId, deviceId, source) }

    internal suspend fun loadUploadSource(
        owner: ChatDataOwner, roomId: String, clientMsgId: String, deviceId: String, video: Boolean,
    ): com.im.openchat.chat.data.MediaUploadSource? =
        videoOperation(owner) { it.loadUploadSource(roomId, clientMsgId, deviceId, video) }

    internal suspend fun markUploadCancelled(
        owner: ChatDataOwner, roomId: String, clientMsgId: String, deviceId: String, video: Boolean,
    ) = videoOperation(owner) { it.markUploadCancelled(roomId, clientMsgId, deviceId, video) }

    internal suspend fun loadVideoPosition(owner: ChatDataOwner, key: String): Double =
        videoOperation(owner) { it.loadVideoPosition(key) }

    internal suspend fun saveVideoPosition(owner: ChatDataOwner, key: String, position: Double, duration: Double) =
        videoOperation(owner) { it.saveVideoPosition(key, position, duration) }

    private suspend fun <T> videoOperation(owner: ChatDataOwner, block: suspend (ConversationDatabase) -> T): T = try {
        databaseMutex.withLock { block(databaseFor(owner)) }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("视频缓存读写失败，请重试", exception)
    }

    internal suspend fun recordConversationRead(owner: ChatDataOwner, roomId: String, messageId: String): Int =
        readOperation(owner) { it.recordConversationRead(owner.serverUrl, owner.userId, roomId, messageId) }

    internal suspend fun loadPendingConversationReads(owner: ChatDataOwner, after: String = ""): List<PendingConversationRead> =
        readOperation(owner) { it.loadPendingConversationReads(owner.serverUrl, after) }

    internal suspend fun acknowledgeConversationRead(
        owner: ChatDataOwner, read: PendingConversationRead, acknowledgedId: String,
    ): Int? = readOperation(owner) { it.acknowledgeConversationRead(owner.serverUrl, owner.userId, read, acknowledgedId) }

    private suspend fun <T> readOperation(owner: ChatDataOwner, block: suspend (ConversationDatabase) -> T): T = try {
        databaseMutex.withLock { block(databaseFor(owner)) }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("会话已读状态保存或读取失败，请重试", exception)
    }

    /**
     * 读取当前账号、房间的持久历史游标。
     * @param owner 当前授权的数据归属。
     * @param roomId 真实房间号。
     * @return 已确认区间或 null；串行读取 Room，不发起网络。
     */
    internal suspend fun loadMessageHistoryState(owner: ChatDataOwner, roomId: String): MessageHistoryState? {
        try {
            // 读取期间锁定当前账号连接，数据库异常交由聊天页显示，取消仍向上传播。
            return databaseMutex.withLock { databaseFor(owner).loadMessageHistoryState(roomId) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("聊天记录同步进度读取失败，请重试", exception)
        }
    }

    /**
     * 将一页历史与可选同步进度交给统一 Room 事务保存。
     * @param owner 当前授权的数据归属，不读取稍后变化的全局账号。
     * @param roomId 真实房间号。
     * @param messages 已校验的服务端页。
     * @param state 已完成对账的进度，中间页为 null。
     * 无返回值；写入失败包装为 IOException，取消继续传播，不改写认证和偏好。
     */
    internal suspend fun saveMessageHistoryPage(
        owner: ChatDataOwner,
        roomId: String,
        messages: List<HistoryMessage>,
        state: MessageHistoryState?,
    ) {
        try {
            // 当前页和进度共用一次事务；持锁期间不会切换或关闭账号数据库。
            databaseMutex.withLock {
                databaseFor(owner).saveMessageHistoryPage(roomId, messages, state, owner.serverUrl, owner.userId)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("聊天记录保存失败，请重试", exception)
        }
    }

    /**
     * 读取该账号房间缓存和分页能力，供聊天页先展示本地记录及失败重试。
     * @param owner 当前授权的数据归属。
     * @param roomId 真实房间号。
     * @return 已排序消息与是否还有远端历史；只读取 Room，无写入副作用。
     */
    internal suspend fun loadMessageHistory(owner: ChatDataOwner, roomId: String): MessageHistoryResult {
        try {
            // 与写入和账号切换顺序访问同一 Room 实例，不把读取失败当作空历史。
            return databaseMutex.withLock { databaseFor(owner).loadMessageHistory(owner.userId, roomId) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("聊天记录读取失败，请重试", exception)
        }
    }

    internal suspend fun saveOutgoingText(
        owner: ChatDataOwner, roomId: String, clientMsgId: String, content: String,
    ): Boolean = try {
        databaseMutex.withLock { databaseFor(owner).saveOutgoingText(roomId, owner.userId, clientMsgId, content) }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("待发送消息保存失败，请重试", exception)
    }

    internal suspend fun saveOutgoingMessage(
        owner: ChatDataOwner, roomId: String, clientMsgId: String, body: MessageBody,
    ): Boolean = try {
        databaseMutex.withLock { databaseFor(owner).saveOutgoingMessage(roomId, owner.userId, clientMsgId, body) }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("待发送消息保存失败，请重试", exception)
    }

    internal suspend fun failPendingText(owner: ChatDataOwner, roomId: String, clientMsgId: String): Boolean = try {
        databaseMutex.withLock { databaseFor(owner).failPendingText(roomId, owner.userId, clientMsgId) }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("消息发送状态保存失败，请重试", exception)
    }

    internal suspend fun recoverPendingMessages(owner: ChatDataOwner) {
        try {
            databaseMutex.withLock { databaseFor(owner).recoverPendingMessages(owner.userId) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("消息发送状态恢复失败，请重试", exception)
        }
    }

    /**
     * 读取当前账号与服务器下按 UID 缓存的公开头像资料。
     * @param owner 当前授权的服务器和账号。
     * @param uid 真实用户 ID。
     * @return 缓存资料或 null；通过统一连接锁读取 Room，不触发网络。
     */
    internal suspend fun loadAvatarProfile(owner: ChatDataOwner, uid: String): AvatarProfile? {
        try {
            // 与历史页及账号切换共用连接锁，避免在使用中关闭数据库。
            return databaseMutex.withLock { databaseFor(owner).loadAvatarProfile(owner.serverUrl, uid) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("头像资料缓存读取失败", exception)
        }
    }

    /**
     * 单独保存公开资料缓存，不改写完整会话快照或本机好友可见列表。
     * @param owner 请求所属的固定服务器和账号。
     * @param profile 已核验身份的公开资料。
     * 无返回值；通过 Room DAO 写入，取消传播，数据库失败转换为 IOException。
     */
    internal suspend fun saveAvatarProfile(owner: ChatDataOwner, profile: AvatarProfile) {
        try {
            // 按请求捕获的归属落库，独立缓存不会覆盖其他业务数据。
            databaseMutex.withLock { databaseFor(owner).saveAvatarProfile(owner.serverUrl, profile) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("头像资料缓存保存失败", exception)
        }
    }

    internal suspend fun loadMessageName(owner: ChatDataOwner, uid: String, roomId: String): String? = try {
        databaseMutex.withLock { databaseFor(owner).loadMessageName(uid, roomId) }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("消息名称缓存读取失败", exception)
    }

    internal suspend fun saveTargetProfile(
        owner: ChatDataOwner, profile: AvatarProfile, signature: String?,
        roomId: String?, remark: String?, groupNickname: String?,
    ) {
        try {
            databaseMutex.withLock {
                databaseFor(owner).saveTargetProfile(profile, signature, roomId, remark, groupNickname)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("用户资料及名称缓存保存失败", exception)
        }
    }

    /** 仅返回本次登录已打开的连接；相同服务器和 UID 的旧授权也不能重新打开内容库。 */
    private fun databaseFor(owner: ChatDataOwner): ConversationDatabase {
        if (authorizedOwner.get() !== owner || conversationDatabaseOwner !== owner) {
            throw CancellationException("本地内容库授权已失效")
        }
        return conversationDatabase ?: throw IOException("请重新登录后访问内容库")
    }

    /** 立即撤销本机数据库访问，不执行磁盘操作，供退出、服务器切换及界面销毁同步调用。 */
    fun revokeDatabaseAccess() {
        authorizedOwner.set(null)
    }

    /** 关闭指定授权的连接并擦除驱动密码；旧登录的清理不能关闭新的连接，不删除数据库文件。 */
    suspend fun closeDatabase(owner: ChatDataOwner? = null) {
        if (owner == null) revokeDatabaseAccess() else authorizedOwner.compareAndSet(owner, null)
        try {
            withContext(Dispatchers.IO) {
                databaseMutex.withLock {
                    if (owner == null || conversationDatabaseOwner === owner) closeDatabaseLocked()
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("内容库连接关闭失败，请重试", exception)
        }
    }

    /** 持有 databaseMutex 时释放连接；先取消引用，关闭异常也不留下可复用对象。 */
    private fun closeDatabaseLocked() {
        val previous = conversationDatabase
        conversationDatabase = null
        conversationDatabaseOwner = null
        previous?.close()
    }
}

/**
 * 将认证会话编码为 DataStore 使用的 JSON 字符串。
 *
 * @param session 待保存的认证会话。
 * @return 会话 JSON；不保存登录密码。
 * 无存储副作用。
 */
private fun encodeAuthSession(session: AuthSession): String = JSONObject().apply {
    put("accessToken", session.accessToken)
    put("expiresAt", session.expiresAt ?: JSONObject.NULL)
    put("clientSqlPassword", session.clientSqlPassword ?: JSONObject.NULL)
    put("rtcServerUrl", session.rtcServerUrl)
    put("user", JSONObject().apply {
        put("id", session.user.id)
        put("account", session.user.account)
        put("nickname", session.user.nickname)
        put("avatar", session.user.avatar ?: JSONObject.NULL)
        put("signature", session.user.signature ?: JSONObject.NULL)
    })
}.toString()

/**
 * 从 DataStore JSON 中恢复认证会话。
 *
 * @param json 已保存的会话 JSON。
 * @return 解析后的认证会话。
 * 无存储副作用；格式错误时抛出异常供调用方回退为空。
 */
private fun decodeAuthSession(json: String): AuthSession {
    val root = JSONObject(json)
    val user = root.getJSONObject("user")
    return AuthSession(
        accessToken = root.getString("accessToken"),
        expiresAt = if (root.isNull("expiresAt")) null else root.getLong("expiresAt"),
        clientSqlPassword = if (root.isNull("clientSqlPassword")) null else root.getString("clientSqlPassword"),
        rtcServerUrl = root.getString("rtcServerUrl"),
        user = AuthUser(
            id = user.getString("id"),
            account = user.getString("account"),
            nickname = user.getString("nickname"),
            avatar = if (user.isNull("avatar")) null else user.getString("avatar"),
            signature = if (user.isNull("signature")) null else user.getString("signature"),
        ),
    )
}
