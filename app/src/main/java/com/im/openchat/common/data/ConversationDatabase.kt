package com.im.openchat.common.data

import android.content.Context
import androidx.room.Room
import com.im.openchat.address.data.Contact
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.MessageBody
import com.im.openchat.chat.data.HistoryMessage
import com.im.openchat.chat.data.MessageHistoryProtocol
import com.im.openchat.chat.data.MessageType
import com.im.openchat.chat.data.TextMessageBody
import com.im.openchat.chat.data.RecallMessageBody
import com.im.openchat.chat.data.ImageMessageBody
import com.im.openchat.chat.data.FileUploadSource
import com.im.openchat.chat.data.MediaUploadSource
import com.im.openchat.chat.data.VideoUploadMetadata
import com.im.openchat.chat.data.encodeUploadPartDigests
import com.im.openchat.chat.data.decodeUploadPartDigests
import com.im.openchat.chat.data.MessageHistoryPosition
import com.im.openchat.chat.data.MessageHistoryResult
import com.im.openchat.chat.data.MessageHistoryState
import com.im.openchat.chat.data.RoomType
import com.im.openchat.chat.data.PendingConversationRead
import com.im.openchat.chat.data.isServerMessageId
import com.im.openchat.chat.data.formatConversationTime
import com.im.openchat.chat.data.formatChatTime
import com.im.openchat.me.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 通过 Room 和 SQLCipher 访问按服务器及账号隔离的内容库。
 *
 * @param context 应用上下文，用于创建应用私有 Room 数据库。
 * @param databaseName 当前账号对应的 Room 数据库文件名。
 * @param password 服务端核验后的内容库密码，不能缺省或为空。
 */
internal class ConversationDatabase(
    context: Context,
    databaseName: String,
    password: String,
    server: String = "",
) {
    private val localState = AndroidLocalStateStore(context, databaseName, server)
    private val passphrase = password.also {
        require(it.isNotBlank() && it.length <= 256) { "本地内容库密钥无效" }
    }.toByteArray(Charsets.UTF_8)
    private val database = createDatabase(context, databaseName)
    private val dao = database.conversationDao()

    /** 在私有数据库目录创建服务器分层，以绝对路径交给 Room；失败清理密码缓冲。 */
    private fun createDatabase(context: Context, databaseName: String): OpenChatRoomDatabase {
        try {
            System.loadLibrary("sqlcipher")
            val relativePath = File(databaseName)
            val parent = relativePath.parent
            val databasePath = if (parent == null) {
                databaseName
            } else {
                val baseFile = context.getDatabasePath(relativePath.name)
                val directory = File(requireNotNull(baseFile.parentFile), parent)
                check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) {
                    "无法创建内容库目录"
                }
                File(directory, baseFile.name).absolutePath
            }
            return Room.databaseBuilder(context, OpenChatRoomDatabase::class.java, databasePath)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(PcContentCallback(localState))
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        } catch (exception: UnsatisfiedLinkError) {
            passphrase.fill(0)
            throw IOException("当前设备无法加载加密数据库组件", exception)
        } catch (exception: RuntimeException) {
            passphrase.fill(0)
            throw exception
        }
    }

    /**
     * 关闭当前账号的 Room 数据库连接。
     *
     * 无参数、无返回值；释放 Room 持有的数据库资源。
     */
    fun close() {
        try {
            database.close()
        } finally {
            // Factory 在连接生命周期内持有同一缓冲，必须关闭后再擦除。
            passphrase.fill(0)
        }
    }

// 这些 PC 表已由 PcContentCallback 安装，不属于 Room 的实体集合。
    suspend fun loadVideoFile(key: String): String? = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(
            "SELECT local_path FROM video_files WHERE resource_key = ?", arrayOf(key),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun saveVideoFile(key: String, path: String) = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO video_files(resource_key, local_path) VALUES (?, ?)",
            arrayOf(key, path),
        )
    }

    suspend fun loadAttachmentFile(key: String): String? = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(
            "SELECT local_path FROM attachment_files WHERE resource_key = ?", arrayOf(key),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun saveAttachmentFile(key: String, path: String) = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO attachment_files(resource_key, local_path) VALUES (?, ?)",
            arrayOf(key, path),
        )
    }

    suspend fun saveOutgoingUpload(
        roomId: String, senderId: String, clientMsgId: String, deviceId: String, source: MediaUploadSource,
    ) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            ensureActive()
            if (dao.loadConversationTime(roomId) == null || dao.findMessageById(clientMsgId) != null ||
                dao.findClientMessage(roomId, senderId, clientMsgId) != null
            ) throw IOException("会话或上传消息标识已变化")
            val now = isoTimestamp(System.currentTimeMillis())
            dao.saveMessages(listOf(MessageEntity(
                id = clientMsgId, clientMsgId = clientMsgId, conversationId = roomId, senderId = senderId,
                content = MessageHistoryProtocol.encodeStoredBody(source.placeholder()), imageLocalPath = null,
                messageType = "temporary", status = "pending", createdAt = now, editedAt = null, deletedAt = null,
            )))
            val file = source.file
            val video = source.video
            if (video == null) {
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO file_upload_sources(conversation_id, client_msg_id, device_id, source) VALUES (?, ?, ?, ?)",
                    arrayOf(roomId, clientMsgId, deviceId, MessageHistoryProtocol.encodeTransfer(FileUploadSource.serializer(), file)),
                )
            } else {
                database.openHelper.writableDatabase.execSQL(
                    """INSERT INTO video_upload_sources(
                        conversation_id, client_msg_id, device_id, file_path, file_name, file_size, digest, part_digests,
                        duration, thumb_width, thumb_height, poster, cancel_requested
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                    arrayOf(roomId, clientMsgId, deviceId, file.filePath, file.fileName, file.fileSize,
                        file.digest, encodeUploadPartDigests(file.partDigests), video.duration,
                        video.thumbWidth, video.thumbHeight, video.poster),
                )
            }
            dao.advanceConversationTime(roomId, now)
            ensureActive()
        }
    }

    suspend fun loadUploadSource(
        roomId: String, clientMsgId: String, deviceId: String, video: Boolean,
    ): MediaUploadSource? = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        if (!video) {
            db.query(
                "SELECT source, cancel_requested FROM file_upload_sources WHERE conversation_id = ? AND client_msg_id = ? AND device_id = ?",
                arrayOf(roomId, clientMsgId, deviceId),
            ).use {
                if (!it.moveToFirst()) null else MediaUploadSource(
                    MessageHistoryProtocol.decodeUploadSource(it.getString(0)).copy(cancelRequested = it.getInt(1) != 0),
                )
            }
        } else {
            db.query(
                """SELECT file_path, file_name, file_size, digest, part_digests,
                    duration, thumb_width, thumb_height, poster, cancel_requested FROM video_upload_sources
                    WHERE conversation_id = ? AND client_msg_id = ? AND device_id = ?""",
                arrayOf(roomId, clientMsgId, deviceId),
            ).use {
                if (!it.moveToFirst()) null else MediaUploadSource(
                    FileUploadSource(
                        it.getString(0), it.getString(1), it.getLong(2), it.getString(3),
                        decodeUploadPartDigests(it.getString(4)), it.getInt(9) != 0,
                    ),
                    VideoUploadMetadata(it.getLong(5), it.getInt(6), it.getInt(7), it.getBlob(8)),
                )
            }
        }
    }

    suspend fun markUploadCancelled(roomId: String, clientMsgId: String, deviceId: String, video: Boolean) =
        withContext(Dispatchers.IO) {
            val table = if (video) "video_upload_sources" else "file_upload_sources"
            database.openHelper.writableDatabase.execSQL(
                "UPDATE $table SET cancel_requested = 1 WHERE conversation_id = ? AND client_msg_id = ? AND device_id = ?",
                arrayOf(roomId, clientMsgId, deviceId),
            )
        }

    suspend fun loadVideoPosition(key: String): Double = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(
            "SELECT position FROM video_playback WHERE resource_key = ?", arrayOf(key),
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    suspend fun saveVideoPosition(key: String, position: Double, duration: Double) = withContext(Dispatchers.IO) {
        require(position.isFinite() && duration.isFinite() && duration > 0 && position in 0.0..duration)
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO video_playback(resource_key, position, duration, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(key, position, duration, isoTimestamp(System.currentTimeMillis())),
        )
    }

    /**
     * 从 Room 获取单独缓存的公开头像资料。
     * @param server 规范化服务器地址。
     * @param uid 用户 ID。
     * @return PC contacts 中的缓存资料或 null；只在 IO 线程读库，服务器由连接归属校验。
     */
    suspend fun loadAvatarProfile(server: String, uid: String): AvatarProfile? =
        withContext(Dispatchers.IO) {
            dao.loadContact(uid)?.let { AvatarProfile(it.id, it.nickname, it.avatar.orEmpty()) }
        }

    /**
     * 保存头像公开资料，不将消息发送者自动添加到通讯录。
     * @param server 当前服务器地址。
     * @param profile 已核验身份的资料。
     * 无返回值；仅在 IO 线程更新 PC contacts 资料缓存，不授予好友关系。
     */
    suspend fun saveAvatarProfile(server: String, profile: AvatarProfile) {
        withContext(Dispatchers.IO) {
            saveContact(ContactEntity(
                profile.uid, profile.name, profile.avatar.takeIf(String::isNotEmpty),
                dao.loadContact(profile.uid)?.signature, isoTimestamp(System.currentTimeMillis()),
            ))
        }
    }

    suspend fun loadMessageName(uid: String, roomId: String): String? = withContext(Dispatchers.IO) {
        val contact = dao.loadContact(uid) ?: return@withContext null
        val remark = dao.loadContactName(uid, "")
        val group = dao.loadContactName(uid, roomId)
        if (remark == null || group == null) return@withContext null
        val now = System.currentTimeMillis()
        if (listOf(remark.updatedAt, group.updatedAt).any {
            val updated = parseTimestamp(it)
            updated <= 0 || now < updated || now - updated >= 60_000
        }) return@withContext null
        resolveMessageDisplayName(remark.remark, group.groupNickname, contact.nickname)
    }

    suspend fun saveTargetProfile(
        profile: AvatarProfile, signature: String?, roomId: String?, remark: String?, groupNickname: String?,
    ) = withContext(Dispatchers.IO) {
        val timestamp = isoTimestamp(System.currentTimeMillis())
        database.runInTransaction {
            saveContact(ContactEntity(profile.uid, profile.name, profile.avatar.takeIf(String::isNotEmpty), signature, timestamp))
            saveContactName(ContactNameEntity(profile.uid, "", remark, null, timestamp))
            if (roomId != null) saveContactName(ContactNameEntity(profile.uid, roomId, null, groupNickname, timestamp))
        }
    }

    /**
     * 读取 PC 端会话表及其摘要、偏好和本地消息。
     *
     * @param ownerId 当前账号 UID，用于还原消息发送方向。
     * @param darkMode DataStore 中保存的深色主题偏好。
     * @param notifications DataStore 中保存的消息通知偏好。
     * @param server 当前服务器地址，用于恢复对应的未读快照边界。
     * @return 按 PC 端置顶和更新时间规则排序的会话数据；无存储副作用。
     */
    suspend fun loadData(ownerId: String, darkMode: Boolean, notifications: Boolean, server: String = ""): ChatData =
        withContext(Dispatchers.IO) {
            // 先打开内容库，确保需要重建时已清除对应的端侧展示状态。
            database.openHelper.writableDatabase
            val ui = localState.load()
            ChatData(
                contacts = loadContacts(ui),
                conversations = loadConversations(ownerId, server, ui),
                requests = ui.requests.map { it.toModel() },
                profile = loadProfile(ownerId, darkMode, notifications, ui),
            )
        }

    /**
     * 将当前业务快照通过 Room 事务写入内容库。
     *
     * @param data 当前账号的联系人、会话、好友申请和个人资料快照。
     * @param remoteSnapshots 原始列表/详情响应，不使用已扣除本地阅读的界面未读数。
     * 无返回值；事务失败时由 Room 回滚，不写入主题和通知偏好。
     */
    suspend fun save(data: ChatData, remoteSnapshots: List<Conversation> = emptyList()) {
        withContext(Dispatchers.IO) {
            ensureActive()
            val snapshots = remoteSnapshots.associateBy { it.contactId }
            database.runInTransaction {
                ensureActive()
                dao.saveProfile(data.toProfileEntity())
                val contacts = data.contacts.filterNot { it.group }
                contacts.forEach { saveContact(it.toEntity()) }
                if (data.conversations.isEmpty()) {
                    dao.deleteAllConversations()
                } else {
                    dao.deleteMissingConversations(data.conversations.map { it.contactId })
                    data.conversations.forEachIndexed { index, conversation ->
                        saveConversation(data, conversation, index)
                        val owner = data.owner
                        if (owner != null && conversation.roomType != null && !conversation.groupRemoved) {
                            snapshots[conversation.contactId]?.let { saveReadSnapshot(owner.serverUrl, it) }
                            recalculateUnread(owner.serverUrl, owner.userId, conversation.contactId)
                        }
                    }
                }
                ensureActive()
            }
            localState.save(data)
        }
    }

    suspend fun loadUnreadCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        dao.loadConversationRows().associate { it.id to it.unreadCount }
    }

    suspend fun recordConversationRead(server: String, ownerId: String, roomId: String, messageId: String): Int =
        withContext(Dispatchers.IO) {
            ensureActive()
            if (!roomId.isServerMessageId() || !messageId.isServerMessageId()) throw IOException("已读消息标识无效")
            var unread = 0
            database.runInTransaction {
                ensureActive()
                if (dao.loadRoomType(roomId) == null) throw IOException("会话已不存在")
                val message = dao.findMessage(roomId, messageId)
                if (message == null || message.status != "sent" || message.deletedAt != null) {
                    throw IOException("只能确认已展示的服务端消息")
                }
                val state = dao.loadConversationReadState(server, roomId)
                    ?: throw IOException("会话未读状态尚未就绪，请重新进入会话")
                dao.saveConversationReadState(state.copy(observedId = greaterId(state.observedId, messageId)))
                unread = recalculateUnread(server, ownerId, roomId)
                ensureActive()
            }
            unread
        }

    suspend fun loadPendingConversationReads(server: String, after: String = ""): List<PendingConversationRead> =
        withContext(Dispatchers.IO) {
            dao.loadPendingConversationReads(server, after).map { PendingConversationRead(it.conversationId, it.observedId) }
        }

    suspend fun acknowledgeConversationRead(
        server: String, ownerId: String, read: PendingConversationRead, acknowledgedId: String,
    ): Int? = withContext(Dispatchers.IO) {
        ensureActive()
        if (!read.roomId.isServerMessageId() || !read.lastReadMsgId.isServerMessageId() ||
            !acknowledgedId.isServerMessageId() || acknowledgedId.toLong() < read.lastReadMsgId.toLong()
        ) {
            throw IOException("已读确认位置无效")
        }
        var unread: Int? = null
        database.runInTransaction {
            ensureActive()
            dao.loadConversationReadState(server, read.roomId)?.let { state ->
                dao.saveConversationReadState(state.copy(acknowledgedId = greaterId(state.acknowledgedId, acknowledgedId)))
                unread = recalculateUnread(server, ownerId, read.roomId)
            }
            ensureActive()
        }
        unread
    }

    private fun saveReadSnapshot(server: String, room: Conversation) {
        val latest = room.lastMsgId ?: return
        val read = room.lastReadMsgId ?: return
        if (latest != "0" && !latest.isServerMessageId() || read != "0" && !read.isServerMessageId() || room.unread < 0) {
            throw IOException("会话未读快照无效")
        }
        val previous = dao.loadConversationReadState(server, room.contactId)
        val replace = previous == null ||
            latest.toLong() >= previous.snapshotId.toLong() && read.toLong() >= previous.snapshotReadId.toLong()
        dao.saveConversationReadState(ConversationReadStateEntity(
            server = server, conversationId = room.contactId,
            observedId = previous?.observedId ?: "0",
            acknowledgedId = greaterId(previous?.acknowledgedId ?: "0", read),
            snapshotId = if (replace) latest else previous!!.snapshotId,
            snapshotReadId = if (replace) read else previous!!.snapshotReadId,
            snapshotUnread = if (replace) room.unread else previous!!.snapshotUnread,
        ))
    }

/** 保持服务端快照不变，只扣除本地已读并加入更新的收件消息。 */
    private fun recalculateUnread(server: String, ownerId: String, roomId: String): Int {
        val state = dao.loadConversationReadState(server, roomId) ?: return dao.loadUnreadCount(roomId) ?: 0
        val read = greaterId(state.observedId, state.acknowledgedId)
        var unread = 0L
        if (dao.loadRoomType(roomId) != RoomType.FILE_ASSISTANT.wireValue) {
            if (state.snapshotId.toLong() > read.toLong()) {
                unread = (state.snapshotUnread.toLong() -
                    dao.countIncomingRange(roomId, ownerId, state.snapshotReadId, read) -
                    dao.countDeletedIncomingRange(roomId, ownerId, state.snapshotReadId, state.snapshotId)).coerceAtLeast(0)
            }
            unread += dao.countIncomingRange(roomId, ownerId, greaterId(read, state.snapshotId), Long.MAX_VALUE.toString())
        }
        return unread.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().also { dao.saveUnreadCount(roomId, it) }
    }

    private fun greaterId(left: String, right: String): String = if (left.toLong() >= right.toLong()) left else right

    /**
     * 读取该会话已确认的同步区间。
     * @param roomId 真实房间号。
     * @return 持久进度或 null；只通过 Room DAO 读取，不修改数据库。
     */
    suspend fun loadMessageHistoryState(roomId: String): MessageHistoryState? = withContext(Dispatchers.IO) {
        dao.loadMessageSyncState(roomId)?.let { state ->
            MessageHistoryState(
                roomId, state.latestId, state.olderCursor,
                if (state.oldestCreatedAt != null && state.oldestId != null) {
                    MessageHistoryPosition(state.oldestCreatedAt, state.oldestId)
                } else null,
                state.exhausted == 1,
            )
        }
    }

    /**
     * 原子合并历史页与连续区间，按既有阅读水位校准未读，不推进已读或改写摘要。
     * @param roomId 本次请求的真实房间号。
     * @param messages 已构造成对应正文对象的服务端页；未知类型不展示但仍由同步器计算边界。
     * @param state 完成对账时的新进度，中间页为 null。
     * 无返回值；失败回滚消息与进度，取消的任务不开始新的写入事务。
     */
    suspend fun saveMessageHistoryPage(
        roomId: String,
        messages: List<HistoryMessage>,
        state: MessageHistoryState?,
        server: String? = null,
        ownerId: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            ensureActive()
            database.runInTransaction {
                ensureActive()
                if (dao.loadConversationTime(roomId) == null) throw IOException("会话已不存在，请返回列表重试")
                if (state != null && state.roomId != roomId || messages.any { it.roomId != roomId }) {
                    throw IOException("历史消息与会话不匹配")
                }
                // 仅将已识别类型的正文对象编码入库；未知类型仍由同步器计算边界，无额外写入。
                messages.forEach { message ->
                    val type = message.type
                    if (type == MessageType.UNKNOWN) return@forEach
                    // 客户端标识仅确认本地占位；不同服务端 ID 的历史消息必须分别保留。
                    saveRemoteMessage(message, MessageEntity(
                        id = message.id, clientMsgId = message.clientMsgId, conversationId = roomId,
                        senderId = message.senderId,
                        content = MessageHistoryProtocol.encodeStoredBody(message.body), imageLocalPath = null,
                        messageType = MessageHistoryProtocol.storedMessageType(type, message.body),
                        status = "sent", createdAt = message.createdAt,
                        editedAt = null, deletedAt = null,
                    ), ownerId)
                }
                if (state != null) dao.saveMessageSyncState(
                    MessageSyncStateEntity(
                        roomId, state.latestId, state.olderCursor,
                        state.oldest?.createdAt, state.oldest?.id, if (state.exhausted) 1 else 0,
                    ),
                )
                if (server != null && ownerId != null) recalculateUnread(server, ownerId, roomId)
                ensureActive()
            }
        }
    }

    /** 同一客户端标识只允许创建一次或从失败重试，不覆盖已确认的服务端消息。 */
    suspend fun saveOutgoingText(roomId: String, senderId: String, clientMsgId: String, content: String): Boolean =
        saveOutgoingMessage(roomId, senderId, clientMsgId, TextMessageBody(content))

    suspend fun saveOutgoingMessage(roomId: String, senderId: String, clientMsgId: String, body: MessageBody): Boolean =
        withContext(Dispatchers.IO) {
            ensureActive()
            val type = when (body) {
                is TextMessageBody -> "text"
                is ImageMessageBody -> "image"
                else -> throw IOException("不支持的待发送消息类型")
            }
            val content = MessageHistoryProtocol.encodeStoredBody(body)
            var queued = false
            database.runInTransaction {
                ensureActive()
                if (dao.loadConversationTime(roomId) == null) throw IOException("会话已不存在，请返回列表重试")
                val previous = dao.findClientMessage(roomId, senderId, clientMsgId)
                if (previous == null) {
                    if (dao.findMessageById(clientMsgId) != null) throw IOException("本地消息标识已被占用")
                    dao.saveMessages(listOf(MessageEntity(
                        id = clientMsgId, clientMsgId = clientMsgId, conversationId = roomId,
                        senderId = senderId, content = content, imageLocalPath = null,
                        messageType = type, status = "pending", createdAt = isoTimestamp(System.currentTimeMillis()),
                        editedAt = null, deletedAt = null,
                    )))
                    queued = true
                } else if (previous.id == clientMsgId && previous.deletedAt == null &&
                    previous.status == "failed" && previous.messageType == type && previous.content == content
                ) {
                    dao.saveMessages(listOf(previous.copy(status = "pending")))
                    queued = true
                }
                if (queued) dao.advanceConversationTime(roomId, previous?.createdAt ?: isoTimestamp(System.currentTimeMillis()))
                ensureActive()
            }
            queued
        }

    suspend fun failPendingText(roomId: String, senderId: String, clientMsgId: String): Boolean =
        withContext(Dispatchers.IO) { dao.failPendingMessage(roomId, senderId, clientMsgId) > 0 }

    suspend fun recoverPendingMessages(senderId: String) {
        withContext(Dispatchers.IO) { dao.recoverPendingMessages(senderId) }
    }

    /**
     * 读取去重后的缓存消息与是否还有服务端历史。
     * @param ownerId 当前账号 UID，用于还原收发方向。
     * @param roomId 真实房间号。
     * @return 按时间、ID 升序的缓存和分页能力；无写入副作用。
     */
    suspend fun loadMessageHistory(ownerId: String, roomId: String): MessageHistoryResult =
        withContext(Dispatchers.IO) {
            MessageHistoryResult(
                dao.loadMessages(roomId).map { it.toModel(ownerId) },
                dao.loadMessageSyncState(roomId)?.exhausted != 1,
                dao.loadUnreadCount(roomId),
            )
        }

    /**
     * 读取会话主表、远端摘要和免打扰偏好，并恢复 Android 会话模型。
     *
     * @param ownerId 当前账号 UID，用于判断消息发送方向。
     * @return 已排序的会话列表；无存储副作用。
     */
    private fun loadConversations(ownerId: String, server: String, ui: AndroidLocalState): List<Conversation> =
        dao.loadConversationRows().map { row ->
            val activeTime = parseTimestamp(row.activeAt ?: row.updatedAt)
            val readState = dao.loadConversationReadState(server, row.id)
            Conversation(
                contactId = row.id,
                messages = dao.loadMessages(row.id).map { it.toModel(ownerId) },
                unread = row.unreadCount,
                pinned = row.isTop == 1,
                muted = row.notDisturb == 1,
                time = formatConversationTime(activeTime),
                roomType = row.type?.let(RoomType::fromWireValue),
                title = row.title,
                avatar = row.avatar.orEmpty(),
                avatarUids = ui.avatarUids[row.id].orEmpty(),
                participantId = row.participantId,
                preview = row.summaryText,
                activeTime = activeTime,
                lastMsgId = readState?.snapshotId,
                lastReadMsgId = readState?.snapshotReadId,
            )
        }

    /**
     * 读取联系人表并恢复联系人模型。
     *
     * @return 联系人和群组列表；无存储副作用。
     */
    private fun loadContacts(
        ui: AndroidLocalState,
    ): List<Contact> = dao.loadContacts().filter { it.id in ui.contactIds }.map {
        Contact(
            id = it.id,
            name = it.nickname,
            role = it.signature.orEmpty(),
            avatar = it.avatar.orEmpty(),
            starred = it.id in ui.starredIds,
            updatedAt = it.updatedAt,
        )
    }

    /**
     * 读取个人资料，主题和通知开关继续由 DataStore 传入。
     *
     * @param ownerId 当前账号 UID；无账号快照时为空。
     * @param darkMode 当前 DataStore 中的深色主题偏好。
     * @param notifications 当前 DataStore 中的通知偏好。
     * @return 个人资料；没有数据库记录时返回默认资料。
     */
    private fun loadProfile(ownerId: String, darkMode: Boolean, notifications: Boolean, ui: AndroidLocalState): Profile {
        val profile = dao.loadProfile(ownerId.ifBlank { LOCAL_PROFILE_ID })
        return profile?.let {
            Profile(
                name = it.nickname,
                bio = ui.bio,
                darkMode = darkMode,
                notifications = notifications,
                userId = ownerId,
                avatar = ui.avatar,
            )
        } ?: Profile(
            userId = ownerId,
            darkMode = darkMode,
            notifications = notifications,
        )
    }

    /**
     * 写入单条会话及其摘要、偏好和消息。
     *
     * @param data 当前业务快照，用于确定消息发送者。
     * @param conversation 待保存的会话。
     * @param index 新建本地会话在快照中的位置，用于初始化排序。
     * 无返回值；调用方已处于 Room 事务中。远端历史由分页事务独立保存，快照不覆盖它。
     */
    private fun saveConversation(data: ChatData, conversation: Conversation, index: Int) {
        val current = dao.loadConversationTime(conversation.contactId)
        val timestamp = conversation.activeTime.takeIf { it > 0 }
            ?.let(::isoTimestamp)
            ?: current?.updatedAt
            ?: isoTimestamp(System.currentTimeMillis() - index)
        dao.saveConversation(
            ConversationEntity(
                id = conversation.contactId,
                title = conversation.title,
                participantId = conversation.participantId,
                avatar = conversation.avatar.takeIf { it.isNotBlank() },
                type = conversation.roomType?.wireValue,
                isTop = if (conversation.pinned) 1 else 0,
                unreadCount = conversation.unread.coerceAtLeast(0),
                createdAt = current?.createdAt ?: timestamp,
                updatedAt = timestamp,
            ),
        )
        dao.saveConversationPreference(
            ConversationPreferenceEntity(
                conversationId = conversation.contactId,
                notDisturb = if (conversation.muted) 1 else 0,
            ),
        )
        if (conversation.roomType == null) {
            dao.deleteConversationSummary(conversation.contactId)
        } else {
            val last = conversation.lastMsgId?.let { dao.findMessage(conversation.contactId, it) }
            val deleted = last?.deletedAt != null ||
                conversation.lastMsgId?.let { id ->
                    dao.loadConversationDeletion(conversation.contactId)?.lastDeleteId?.let {
                        id.isServerMessageId() && it.isServerMessageId() && id.toLong() <= it.toLong()
                    }
                } == true
            if (deleted) dao.deleteConversationSummary(conversation.contactId) else dao.saveConversationSummary(
                ConversationRemoteSummaryEntity(
                    conversationId = conversation.contactId,
                    text = conversation.preview,
                    activeAt = timestamp,
                ),
            )
        }
        if (conversation.roomType == null) {
            dao.deleteMessages(conversation.contactId)
            if (conversation.messages.isNotEmpty()) {
                dao.saveMessages(
                    conversation.messages.map { message ->
                        message.toEntity(data, conversation)
                    },
                )
            }
        }
    }

    /**
     * 将联系人模型转换为通讯录表实体。
     *
     * @return 联系人表实体；无存储副作用。
     */
    private fun Contact.toEntity(): ContactEntity = ContactEntity(
        id = id,
        nickname = name,
        avatar = avatar.takeIf { it.isNotBlank() },
        signature = role.takeIf { it.isNotBlank() },
        updatedAt = updatedAt.ifBlank { isoTimestamp(System.currentTimeMillis()) },
    )

    /**
     * 将业务资料转换为个人资料表实体。
     *
     * 当前快照的账号归属决定资料主键。
     * @return 个人资料表实体；主题和通知偏好不会写入实体。
     */
    private fun ChatData.toProfileEntity(): ProfileEntity = ProfileEntity(
        id = owner?.userId
            ?: profile.userId.takeIf { it.isNotBlank() }
            ?: LOCAL_PROFILE_ID,
        nickname = profile.name,
        updatedAt = isoTimestamp(System.currentTimeMillis()),
    )

    /**
     * 将消息模型转换为 PC 端 messages 表实体。
     *
     * @param data 当前业务快照，用于确定本地发送者 UID。
     * @param conversation 消息所属会话。
     * @return 消息表实体；无存储副作用。
     */
    private fun Message.toEntity(data: ChatData, conversation: Conversation): MessageEntity {
        if (sentAt <= 0) throw IOException("本地消息缺少发送时间")
        val senderId = when {
            outgoing && data.profile.userId.isNotBlank() -> data.profile.userId
            outgoing -> LOCAL_SENDER_ID
            !conversation.participantId.isNullOrBlank() -> conversation.participantId.orEmpty()
            else -> REMOTE_SENDER_ID
        }
        return MessageEntity(
            id = id,
            clientMsgId = null,
            conversationId = conversation.contactId,
            senderId = senderId,
            content = text,
            imageLocalPath = null,
            messageType = "text",
            status = "sent",
            createdAt = isoTimestamp(sentAt),
            editedAt = null,
            deletedAt = null,
        )
    }

    /**
     * 将消息表实体恢复为 Android 消息模型。
     *
     * @param ownerId 当前账号 UID。
     * @return 带真实发送者和对应正文对象的 Android 模型，非文本类型保留原有占位展示；无存储副作用。
     */
    private fun MessageEntity.toModel(ownerId: String): Message {
        val type = MessageType.fromStorageValue(messageType)
        val body = MessageHistoryProtocol.decodeStoredBody(type, content)
        return Message(
            id = id,
            text = if (body is TextMessageBody) body.content else type.label,
            outgoing = senderId == ownerId || senderId == LOCAL_SENDER_ID,
            time = formatMessageTime(createdAt),
            senderId = senderId,
            body = body,
            clientMsgId = clientMsgId,
            status = status,
            sentAt = parseTimestamp(createdAt),
            replyId = dao.loadMessageReply(id)?.replyId,
        )
    }

    private fun saveContact(contact: ContactEntity) {
        if (dao.insertContact(contact) == -1L) dao.updateContact(
            contact.id, contact.nickname, contact.avatar, contact.signature, contact.updatedAt,
        )
    }

    private fun saveContactName(name: ContactNameEntity) {
        if (dao.insertContactName(name) == -1L) dao.updateContactName(
            name.contactId, name.roomId, name.remark, name.groupNickname, name.updatedAt,
        )
    }

    private fun saveRemoteMessage(message: HistoryMessage, incoming: MessageEntity, ownerId: String?) {
        val existing = dao.findMessageById(message.id)
        if (existing != null && (existing.conversationId != incoming.conversationId || existing.senderId != incoming.senderId)) {
            throw IOException("不能更改已有消息所属会话或发送者")
        }
        val cleared = dao.loadConversationDeletion(message.roomId)?.lastDeleteId?.let {
            it.isServerMessageId() && message.id.isServerMessageId() && message.id.toLong() <= it.toLong()
        } == true
        if (cleared || existing?.deletedAt != null || existing?.messageType == "local-notice") return
        if (existing?.messageType == "recalled") {
            if (message.type != MessageType.RECALL) return
            val previous = MessageHistoryProtocol.decodeStoredBody(MessageType.RECALL, existing.content) as RecallMessageBody
            if (previous.recallUid != null && (message.body as? RecallMessageBody)?.recallUid == null) return
        }
        if (existing?.messageType == "upload-cancelled" && incoming.messageType !in setOf("upload-cancelled", "recalled")) return
        if (message.type == MessageType.TEMPORARY && existing != null && existing.messageType != "temporary") return
        val local = message.clientMsgId?.let { dao.findClientMessage(message.roomId, message.senderId, it) }
            ?.takeIf { it.id == it.clientMsgId && it.status in setOf("pending", "failed") }
        if (local?.deletedAt != null) return
        val reply = message.replyId?.also {
            if (!it.isServerMessageId()) throw IOException("引用消息标识无效")
        }
        val imagePath = if (message.body is ImageMessageBody && existing?.messageType == "image") {
            val previous = MessageHistoryProtocol.decodeStoredBody(MessageType.IMAGE, existing.content) as ImageMessageBody
            val next = MessageHistoryProtocol.decodeStoredBody(MessageType.IMAGE, incoming.content) as ImageMessageBody
            existing.imageLocalPath.takeIf { previous.content != null && previous.content == next.content }
        } else null
        val originalText = existing?.takeIf { it.messageType == "text" }?.content
            ?: local?.takeIf { it.messageType == "text" }?.content
// 只有 pending 或 failed 状态的本地占位消息才能通过 clientMsgId 确认。
        message.clientMsgId?.let {
            dao.deleteProvisionalMessage(message.roomId, message.senderId, it, message.id)
        }
        dao.saveMessages(listOf(incoming.copy(imageLocalPath = imagePath)))
        if (reply != null && message.type != MessageType.RECALL) {
            dao.saveMessageReply(MessageReplyEntity(message.id, reply))
        } else dao.deleteMessageReply(message.id)
        val recall = message.body as? RecallMessageBody
        if (recall != null && ownerId == message.senderId && recall.recallUid?.toString() == ownerId) {
            if (originalText != null) dao.saveRecallText(MessageRecallTextEntity(message.id, originalText))
        } else dao.deleteRecallText(message.id)
        dao.deleteAutoRetry(message.id)
        dao.advanceConversationTime(message.roomId, message.createdAt)
    }

    /**
     * 将数据库时间转换为会话列表使用的本地时间戳。
     *
     * @param value ISO 时间文本。
     * @return Unix 毫秒时间戳；不是当前 UTC 存储格式时抛出 IOException。
     */
    private fun parseTimestamp(value: String): Long {
        val format = SimpleDateFormat(ISO_FORMAT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val position = java.text.ParsePosition(0)
        val date = format.parse(value, position)
        if (date == null || position.index != value.length || format.format(date) != value) {
            throw IOException("本地消息时间格式无效")
        }
        return date.time
    }

    /**
     * 将 Unix 毫秒时间戳编码为 PC 端使用的 UTC ISO 文本。
     *
     * @param timestamp Unix 毫秒时间戳。
     * @return UTC ISO 时间文本；无副作用。
     */
    private fun isoTimestamp(timestamp: Long): String =
        SimpleDateFormat(ISO_FORMAT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    /**
     * 将消息创建时间格式化为 Android 气泡使用的时间文本。
     *
     * @param value 数据库中的消息创建时间。
     * @return 聊天展示时区的日期时间，避免不同日期的相同时分合并；无副作用。
     */
    private fun formatMessageTime(value: String): String {
        val timestamp = parseTimestamp(value)
        return formatChatTime(timestamp, "yyyy-MM-dd HH:mm")
    }

    private companion object {
        const val ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        const val LOCAL_PROFILE_ID = "local"
        const val LOCAL_SENDER_ID = "__local__"
        const val REMOTE_SENDER_ID = "__remote__"
    }
}
