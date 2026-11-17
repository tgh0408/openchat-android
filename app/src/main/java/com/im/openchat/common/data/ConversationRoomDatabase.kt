package com.im.openchat.common.data

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomWarnings
import androidx.room.Upsert

/**
 * PC 端会话主表的 Room 实体。
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(
            name = "idx_conversations_updated",
            value = ["updated_at", "id"],
            orders = [Index.Order.DESC, Index.Order.DESC],
        ),
    ],
)
internal data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "participant_id")
    val participantId: String?,
    val avatar: String?,
    val type: Int?,
    @ColumnInfo(name = "is_top", defaultValue = "0")
    val isTop: Int,
    @ColumnInfo(name = "unread_count", defaultValue = "0")
    val unreadCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

/**
 * 本地个人资料表的 Room 实体，主题和通知偏好不在此表保存。
 */
@Entity(tableName = "profile")
internal data class ProfileEntity(
    @PrimaryKey
    val id: String,
    val nickname: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

/**
 * 通讯录表的 Room 实体。
 */
@Entity(tableName = "contacts")
internal data class ContactEntity(
    @PrimaryKey
    val id: String,
    val nickname: String,
    val avatar: String?,
    val signature: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

@Entity(
    tableName = "contact_name_cache",
    primaryKeys = ["contact_id", "room_id"],
    foreignKeys = [ForeignKey(
        entity = ContactEntity::class, parentColumns = ["id"], childColumns = ["contact_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class ContactNameEntity(
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    val remark: String?,
    @ColumnInfo(name = "group_nickname") val groupNickname: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/**
 * 会话免打扰偏好表的 Room 实体。
 */
@Entity(
    tableName = "conversation_preferences",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "not_disturb")
    val notDisturb: Int,
)

/**
 * 远端会话摘要表的 Room 实体。
 */
@Entity(
    tableName = "conversation_remote_summary",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationRemoteSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val text: String?,
    @ColumnInfo(name = "active_at")
    val activeAt: String,
)

/**
 * 会话删除同步状态表的 Room 实体。
 */
@Entity(tableName = "conversation_deletions")
internal data class ConversationDeletionEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "last_delete_id")
    val lastDeleteId: String,
    val revision: String,
    val pending: Int,
)

/**
 * 会话已读同步状态表的 Room 实体。
 */
// PcContentCallback installs the full conversation_id index on both new and existing databases.
// Keep it outside Room metadata so this index-only fix does not change the schema identity.
@SuppressWarnings(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "conversation_read_state",
    primaryKeys = ["server", "conversation_id"],
    indices = [Index(name = "idx_conversation_read_pending", value = ["server", "conversation_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationReadStateEntity(
    val server: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "observed_id", defaultValue = "'0'")
    val observedId: String,
    @ColumnInfo(name = "acknowledged_id", defaultValue = "'0'")
    val acknowledgedId: String,
    @ColumnInfo(name = "snapshot_id", defaultValue = "'0'")
    val snapshotId: String,
    @ColumnInfo(name = "snapshot_read_id", defaultValue = "'0'")
    val snapshotReadId: String,
    @ColumnInfo(name = "snapshot_unread", defaultValue = "0")
    val snapshotUnread: Int,
)

/**
 * PC 端消息表的 Room 实体。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "idx_messages_conversation_created",
            value = ["conversation_id", "created_at", "id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(
            name = "idx_messages_client",
            value = ["conversation_id", "sender_id", "client_msg_id"],
        ),
    ],
)
internal data class MessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "client_msg_id")
    val clientMsgId: String?,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "sender_id")
    val senderId: String,
    val content: String,
    @ColumnInfo(name = "image_local_path")
    val imageLocalPath: String?,
    @ColumnInfo(name = "message_type")
    val messageType: String,
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "edited_at")
    val editedAt: String?,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String?,
)

@Entity(
    tableName = "message_replies",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"],
        onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE,
    )],
)
internal data class MessageReplyEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "reply_id") val replyId: String,
)

@Entity(
    tableName = "message_recall_text",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class MessageRecallTextEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    val content: String,
)

@Entity(
    tableName = "message_auto_retry",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"],
        onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE,
    )],
)
internal data class MessageAutoRetryEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    val reason: String,
)

/**
 * 消息同步状态表的 Room 实体。
 */
@Entity(
    tableName = "message_sync_state",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class MessageSyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "latest_id")
    val latestId: String?,
    @ColumnInfo(name = "older_cursor")
    val olderCursor: String?,
    @ColumnInfo(name = "oldest_created_at")
    val oldestCreatedAt: String?,
    @ColumnInfo(name = "oldest_id")
    val oldestId: String?,
    val exhausted: Int,
)

/**
 * 会话主表和远端摘要、免打扰偏好的联查结果。
 */
internal data class ConversationRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "participant_id")
    val participantId: String?,
    val avatar: String?,
    val type: Int?,
    @ColumnInfo(name = "is_top")
    val isTop: Int,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "not_disturb")
    val notDisturb: Int,
    @ColumnInfo(name = "summary_text")
    val summaryText: String?,
    @ColumnInfo(name = "active_at")
    val activeAt: String?,
)

/**
 * 用于保留已有会话创建时间的轻量查询结果。
 */
internal data class ConversationTimeRow(
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

/**
 * 会话相关表的 Room DAO。
 */
@Dao
internal interface ConversationDao {

    /**
     * 查询会话主表及其摘要和免打扰状态。
     *
     * @return 按置顶和更新时间排序的会话行。
     */
    @Query(
        """
        SELECT c.id, c.title, c.participant_id, c.avatar, c.type,
            c.is_top, c.unread_count, c.created_at, c.updated_at,
            COALESCE(p.not_disturb, 0) AS not_disturb,
            r.text AS summary_text, r.active_at
        FROM conversations c
        LEFT JOIN conversation_preferences p
            ON p.conversation_id = c.id
        LEFT JOIN conversation_remote_summary r
            ON r.conversation_id = c.id
        ORDER BY c.is_top DESC, c.updated_at DESC, c.id DESC
        """,
    )
    fun loadConversationRows(): List<ConversationRow>

    /**
     * 查询指定会话的本地消息。
     *
     * @param conversationId 会话主键。
     * @return 按创建时间升序排列的消息。
     */
    @Query(
        """
        SELECT id, client_msg_id, conversation_id, sender_id, content,
            image_local_path, message_type, status, created_at, edited_at, deleted_at
        FROM messages
        WHERE conversation_id = :conversationId AND deleted_at IS NULL
        ORDER BY created_at ASC, id ASC
        """,
    )
    fun loadMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM conversation_read_state WHERE server = :server AND conversation_id = :roomId")
    fun loadConversationReadState(server: String, roomId: String): ConversationReadStateEntity?

    @Upsert
    fun saveConversationReadState(state: ConversationReadStateEntity)

    @Query(
        """
        SELECT * FROM conversation_read_state
        WHERE server = :server AND conversation_id > :after
            AND CAST(observed_id AS INTEGER) > CAST(acknowledged_id AS INTEGER)
        ORDER BY conversation_id LIMIT 50
        """,
    )
    fun loadPendingConversationReads(server: String, after: String): List<ConversationReadStateEntity>

    @Query("SELECT * FROM messages WHERE conversation_id = :roomId AND id = :messageId")
    fun findMessage(roomId: String, messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun findMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM message_replies WHERE message_id = :messageId")
    fun loadMessageReply(messageId: String): MessageReplyEntity?

    @Upsert
    fun saveMessageReply(reply: MessageReplyEntity)

    @Query("DELETE FROM message_replies WHERE message_id = :messageId")
    fun deleteMessageReply(messageId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun saveRecallText(text: MessageRecallTextEntity)

    @Query("DELETE FROM message_recall_text WHERE message_id = :messageId")
    fun deleteRecallText(messageId: String)

    @Query("DELETE FROM message_auto_retry WHERE message_id = :messageId")
    fun deleteAutoRetry(messageId: String)

    @Query("SELECT * FROM conversation_deletions WHERE conversation_id = :roomId")
    fun loadConversationDeletion(roomId: String): ConversationDeletionEntity?

    @Query("UPDATE conversations SET updated_at = MAX(updated_at, :timestamp) WHERE id = :roomId")
    fun advanceConversationTime(roomId: String, timestamp: String)

    @Query("SELECT unread_count FROM conversations WHERE id = :roomId")
    fun loadUnreadCount(roomId: String): Int?

    @Query("SELECT type FROM conversations WHERE id = :roomId")
    fun loadRoomType(roomId: String): Int?

    @Query("UPDATE conversations SET unread_count = :unread WHERE id = :roomId")
    fun saveUnreadCount(roomId: String, unread: Int)

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversation_id = :roomId AND sender_id != :ownerId AND status = 'sent'
            AND deleted_at IS NULL AND id != '' AND id NOT GLOB '*[^0-9]*'
            AND CAST(id AS INTEGER) > CAST(:after AS INTEGER)
            AND CAST(id AS INTEGER) <= CAST(:through AS INTEGER)
        """,
    )
    fun countIncomingRange(roomId: String, ownerId: String, after: String, through: String): Long

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversation_id = :roomId AND sender_id != :ownerId AND status = 'sent'
            AND deleted_at IS NOT NULL AND id != '' AND id NOT GLOB '*[^0-9]*'
            AND CAST(id AS INTEGER) > CAST(:after AS INTEGER)
            AND CAST(id AS INTEGER) <= CAST(:through AS INTEGER)
        """,
    )
    fun countDeletedIncomingRange(roomId: String, ownerId: String, after: String, through: String): Long

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :roomId AND sender_id = :senderId AND client_msg_id = :clientMsgId
        ORDER BY CASE WHEN status = 'sent' THEN 0 ELSE 1 END, created_at DESC, id DESC
        LIMIT 1
        """,
    )
    fun findClientMessage(roomId: String, senderId: String, clientMsgId: String): MessageEntity?

    @Query(
        """
        DELETE FROM messages
        WHERE conversation_id = :roomId AND sender_id = :senderId
            AND client_msg_id = :clientMsgId AND id != :serverId
            AND id = client_msg_id AND status IN ('pending', 'failed')
        """,
    )
    fun deleteProvisionalMessage(roomId: String, senderId: String, clientMsgId: String, serverId: String)

    @Query(
        """
        UPDATE messages SET status = 'failed'
        WHERE conversation_id = :roomId AND sender_id = :senderId
            AND client_msg_id = :clientMsgId AND id = client_msg_id
            AND status = 'pending' AND deleted_at IS NULL
        """,
    )
    fun failPendingMessage(roomId: String, senderId: String, clientMsgId: String): Int

    @Query("""
        UPDATE messages SET status = 'failed'
        WHERE sender_id = :senderId AND message_type IN ('text', 'image') AND status = 'pending'
            AND id = client_msg_id AND deleted_at IS NULL
    """)
    fun recoverPendingMessages(senderId: String)

    /**
     * 读取已确认的连续历史同步区间。
     * @param conversationId 真实房间号。
     * @return 已保存的最新边界和旧历史游标，无记录时为 null；无副作用。
     */
    @Query("SELECT * FROM message_sync_state WHERE conversation_id = :conversationId")
    fun loadMessageSyncState(conversationId: String): MessageSyncStateEntity?

    /**
     * 读取当前账号数据库中指定服务器和 UID 的公开头像资料。
     * @param server 规范化服务器地址。
     * @param uid 真实用户 ID。
     * @return 缓存记录或 null；无写入副作用。
     */
    @Query("SELECT * FROM contacts WHERE id = :uid")
    fun loadContact(uid: String): ContactEntity?

    /**
     * 保存成功获取的公开资料，保留空头像状态以避免反复请求。
     * @param profile 当前账号与服务器下的用户公开资料。
     * 无返回值；只更新头像资料缓存，不修改联系人和会话。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertContact(contact: ContactEntity): Long

    @Query("""
        UPDATE contacts SET nickname = :nickname, avatar = :avatar, signature = :signature, updated_at = :updatedAt
        WHERE id = :id AND updated_at <= :updatedAt
    """)
    fun updateContact(id: String, nickname: String, avatar: String?, signature: String?, updatedAt: String)

    @Query("SELECT * FROM contact_name_cache WHERE contact_id = :uid AND room_id = :roomId")
    fun loadContactName(uid: String, roomId: String): ContactNameEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertContactName(name: ContactNameEntity): Long

    @Query("""
        UPDATE contact_name_cache SET remark = :remark, group_nickname = :groupNickname, updated_at = :updatedAt
        WHERE contact_id = :uid AND room_id = :roomId AND updated_at <= :updatedAt
    """)
    fun updateContactName(uid: String, roomId: String, remark: String?, groupNickname: String?, updatedAt: String)

    /**
     * 保存已确认的同步进度，调用方与当前页消息置于同一 Room 事务。
     * @param state 已确认的连续区间。
     * 无返回值；写入或更新该会话进度，不改动消息和已读状态。
     */
    @Upsert
    fun saveMessageSyncState(state: MessageSyncStateEntity)

    /**
     * 查询联系人。
     *
     * @return 按更新时间倒序排列的联系人。
     */
    @Query(
        """
        SELECT id, nickname, avatar, signature, updated_at
        FROM contacts
        ORDER BY updated_at DESC, id DESC
        """,
    )
    fun loadContacts(): List<ContactEntity>

    /**
     * 查询指定账号的个人资料。
     *
     * @param profileId 个人资料主键。
     * @return 个人资料，不存在时返回 null。
     */
    @Query("SELECT id, nickname, updated_at FROM profile WHERE id = :profileId")
    fun loadProfile(profileId: String): ProfileEntity?

    /**
     * 查询会话的已有时间字段。
     *
     * @param conversationId 会话主键。
     * @return 已有时间字段，不存在时返回 null。
     */
    @Query("SELECT created_at, updated_at FROM conversations WHERE id = :conversationId")
    fun loadConversationTime(conversationId: String): ConversationTimeRow?

    /**
     * 插入或更新个人资料。
     *
     * @param profile 待保存的资料实体。
     */
    @Upsert
    fun saveProfile(profile: ProfileEntity)

    /**
     * 删除全部会话。
     */
    @Query("DELETE FROM conversations")
    fun deleteAllConversations()

    /**
     * 删除当前快照中不存在的会话。
     *
     * @param conversationIds 当前快照中的会话主键。
     */
    @Query("DELETE FROM conversations WHERE id NOT IN (:conversationIds)")
    fun deleteMissingConversations(conversationIds: List<String>)

    /**
     * 插入或更新会话主表记录。
     *
     * @param conversation 待保存的会话实体。
     * 无返回值；原位更新，避免 REPLACE 删除主表时级联清除消息与历史游标。
     */
    @Upsert
    fun saveConversation(conversation: ConversationEntity)

    /**
     * 插入或更新会话免打扰偏好。
     *
     * @param preference 待保存的偏好实体。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveConversationPreference(preference: ConversationPreferenceEntity)

    /**
     * 删除会话远端摘要。
     *
     * @param conversationId 会话主键。
     */
    @Query("DELETE FROM conversation_remote_summary WHERE conversation_id = :conversationId")
    fun deleteConversationSummary(conversationId: String)

    /**
     * 插入或更新会话远端摘要。
     *
     * @param summary 待保存的摘要实体。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveConversationSummary(summary: ConversationRemoteSummaryEntity)

    /**
     * 删除指定会话的消息。
     *
     * @param conversationId 会话主键。
     */
    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    fun deleteMessages(conversationId: String)

    /**
     * 插入或更新消息。
     *
     * @param messages 待保存的消息实体。
     */
    @Upsert
    fun saveMessages(messages: List<MessageEntity>)
}

/**
 * 当前账号使用的 Room 数据库。
 */
@Database(
    entities = [
        ConversationEntity::class,
        ProfileEntity::class,
        ContactEntity::class,
        ContactNameEntity::class,
        ConversationPreferenceEntity::class,
        ConversationRemoteSummaryEntity::class,
        ConversationDeletionEntity::class,
        ConversationReadStateEntity::class,
        MessageEntity::class,
        MessageSyncStateEntity::class,
        MessageReplyEntity::class,
        MessageRecallTextEntity::class,
        MessageAutoRetryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class OpenChatRoomDatabase : RoomDatabase() {

    /**
     * 获取会话相关 DAO。
     *
     * @return 当前数据库实例对应的 DAO。
     */
    abstract fun conversationDao(): ConversationDao
}
