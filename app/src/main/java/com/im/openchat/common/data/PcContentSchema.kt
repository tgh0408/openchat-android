package com.im.openchat.common.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * PC 端基线：openchat-web/electron/services/database/database-user.ts
 * 的 initializeContentSchema（2026-11-02）。未接入的 PC 功能表保持为空。
 * Room 要求其管理实体的主键使用 NOT NULL。
 */
internal object PcContentSchema {
    val tables = linkedMapOf(
        "profile" to """
            CREATE TABLE IF NOT EXISTS profile (
                id TEXT NOT NULL PRIMARY KEY,
                nickname TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """,
        "conversations" to """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                participant_id TEXT,
                avatar TEXT,
                type INTEGER,
                is_top INTEGER NOT NULL DEFAULT 0,
                unread_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """,
        "conversation_preferences" to """
            CREATE TABLE IF NOT EXISTS conversation_preferences (
                conversation_id TEXT NOT NULL PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
                not_disturb INTEGER NOT NULL CHECK(not_disturb IN (0, 1))
            )
        """,
        "conversation_remote_summary" to """
            CREATE TABLE IF NOT EXISTS conversation_remote_summary (
                conversation_id TEXT NOT NULL PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
                text TEXT,
                active_at TEXT NOT NULL
            )
        """,
        "conversation_deletions" to """
            CREATE TABLE IF NOT EXISTS conversation_deletions (
                conversation_id TEXT NOT NULL PRIMARY KEY,
                last_delete_id TEXT NOT NULL,
                revision TEXT NOT NULL,
                pending INTEGER NOT NULL CHECK(pending IN (0, 1))
            )
        """,
        "conversation_file_cleanup" to """
            CREATE TABLE IF NOT EXISTS conversation_file_cleanup (local_path TEXT PRIMARY KEY)
        """,
        "conversation_read_state" to """
            CREATE TABLE IF NOT EXISTS conversation_read_state (
                server TEXT NOT NULL,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                observed_id TEXT NOT NULL DEFAULT '0',
                acknowledged_id TEXT NOT NULL DEFAULT '0',
                snapshot_id TEXT NOT NULL DEFAULT '0',
                snapshot_read_id TEXT NOT NULL DEFAULT '0',
                snapshot_unread INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(server, conversation_id)
            )
        """,
        "messages" to """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT NOT NULL PRIMARY KEY,
                client_msg_id TEXT,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                sender_id TEXT NOT NULL,
                content TEXT NOT NULL,
                image_local_path TEXT,
                message_type TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                edited_at TEXT,
                deleted_at TEXT
            )
        """,
        "message_recall_text" to """
            CREATE TABLE IF NOT EXISTS message_recall_text (
                message_id TEXT NOT NULL PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
                content TEXT NOT NULL
            )
        """,
        "message_auto_retry" to """
            CREATE TABLE IF NOT EXISTS message_auto_retry (
                message_id TEXT NOT NULL PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE ON UPDATE CASCADE,
                reason TEXT NOT NULL CHECK(reason IN ('offline', 'timeout'))
            )
        """,
        "message_replies" to """
            CREATE TABLE IF NOT EXISTS message_replies (
                message_id TEXT NOT NULL PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE ON UPDATE CASCADE,
                reply_id TEXT NOT NULL
            )
        """,
        "message_sync_state" to """
            CREATE TABLE IF NOT EXISTS message_sync_state (
                conversation_id TEXT NOT NULL PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
                latest_id TEXT,
                older_cursor TEXT,
                oldest_created_at TEXT,
                oldest_id TEXT,
                exhausted INTEGER NOT NULL CHECK(exhausted IN (0, 1))
            )
        """,
        "files" to """
            CREATE TABLE IF NOT EXISTS files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                size INTEGER NOT NULL CHECK(size >= 0),
                path TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL
            )
        """,
        "contacts" to """
            CREATE TABLE IF NOT EXISTS contacts (
                id TEXT NOT NULL PRIMARY KEY,
                nickname TEXT NOT NULL,
                avatar TEXT,
                signature TEXT,
                updated_at TEXT NOT NULL
            )
        """,
        "contact_name_cache" to """
            CREATE TABLE IF NOT EXISTS contact_name_cache (
                contact_id TEXT NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
                room_id TEXT NOT NULL,
                remark TEXT,
                group_nickname TEXT,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(contact_id, room_id)
            )
        """,
        "emojis" to """
            CREATE TABLE IF NOT EXISTS emojis (img_id TEXT PRIMARY KEY, url TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        "emoji_hashes" to """
            CREATE TABLE IF NOT EXISTS emoji_hashes (
                img_id TEXT PRIMARY KEY REFERENCES emojis(img_id) ON DELETE CASCADE,
                sha256 TEXT NOT NULL
            )
        """,
        "emoji_order" to """
            CREATE TABLE IF NOT EXISTS emoji_order (
                img_id TEXT PRIMARY KEY REFERENCES emojis(img_id) ON DELETE CASCADE,
                sort TEXT NOT NULL
            )
        """,
        "video_files" to """
            CREATE TABLE IF NOT EXISTS video_files (resource_key TEXT PRIMARY KEY, local_path TEXT NOT NULL)
        """,
        "file_upload_sources" to """
            CREATE TABLE IF NOT EXISTS file_upload_sources (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                client_msg_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                source TEXT NOT NULL,
                cancel_requested INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(conversation_id, client_msg_id)
            )
        """,
        "attachment_files" to """
            CREATE TABLE IF NOT EXISTS attachment_files (resource_key TEXT PRIMARY KEY, local_path TEXT NOT NULL)
        """,
        "video_playback" to """
            CREATE TABLE IF NOT EXISTS video_playback (
                resource_key TEXT PRIMARY KEY,
                position REAL NOT NULL CHECK(position >= 0),
                duration REAL NOT NULL CHECK(duration > 0 AND position <= duration),
                updated_at TEXT NOT NULL
            )
        """,
        "video_upload_sources" to """
            CREATE TABLE IF NOT EXISTS video_upload_sources (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                client_msg_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_name TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                digest TEXT NOT NULL,
                part_digests TEXT NOT NULL,
                duration INTEGER NOT NULL,
                thumb_width INTEGER NOT NULL,
                thumb_height INTEGER NOT NULL,
                poster BLOB NOT NULL CHECK(length(poster) BETWEEN 1 AND 2097152),
                cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0, 1)),
                PRIMARY KEY(conversation_id, client_msg_id)
            )
        """,
    )

    val indexes = listOf(
        "CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at DESC, id DESC)",
        """CREATE INDEX IF NOT EXISTS idx_conversation_read_pending
            ON conversation_read_state(server, conversation_id)
            WHERE CAST(observed_id AS INTEGER) > CAST(acknowledged_id AS INTEGER)""",
        """CREATE INDEX IF NOT EXISTS idx_messages_conversation_created
            ON messages(conversation_id, created_at DESC, id DESC)""",
        "CREATE INDEX IF NOT EXISTS idx_messages_client ON messages(conversation_id, sender_id, client_msg_id)",
        """CREATE INDEX IF NOT EXISTS idx_messages_read_position
            ON messages(conversation_id, CAST(id AS INTEGER)) WHERE status = 'sent' AND deleted_at IS NULL""",
        """CREATE INDEX IF NOT EXISTS idx_messages_deleted_position
            ON messages(conversation_id, CAST(id AS INTEGER)) WHERE status = 'sent' AND deleted_at IS NOT NULL""",
        "CREATE INDEX IF NOT EXISTS emoji_hashes_sha256 ON emoji_hashes(sha256)",
    )

    fun create(db: SupportSQLiteDatabase) {
        tables.values.forEach(db::execSQL)
        indexes.forEach(db::execSQL)
        ensureAndroidIndexes(db)
    }

    /** The pending partial index cannot cover foreign-key lookups across all servers/rows. */
    fun ensureAndroidIndexes(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_conversation_read_conversation
                ON conversation_read_state(conversation_id)""",
        )
    }
}
