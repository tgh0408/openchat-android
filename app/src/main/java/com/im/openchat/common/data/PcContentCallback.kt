package com.im.openchat.common.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** 新建及重建内容库时安装 PC 表定义，不读取或转换历史结构。 */
internal class PcContentCallback(private val localState: AndroidLocalStateStore) : RoomDatabase.Callback() {
    /** 新建库时清除对应展示状态并补齐 Room 无法表达的 CHECK 和索引，无返回值。 */
    override fun onCreate(db: SupportSQLiteDatabase) {
        localState.clear()
        createSchema(db)
    }

    /** 重建内容库时清除对应的端侧展示状态，直接安装当前结构，无返回值。 */
    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        localState.clear()
        createSchema(db)
    }

    /** 打开内容库时核对服务器归属并设置锁等待时间，无返回值。 */
    override fun onOpen(db: SupportSQLiteDatabase) {
        localState.bindServer(db)
        db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
        PcContentSchema.ensureAndroidIndexes(db)
    }

    /** 将 Room 创建的空表替换为当前 PC SQL 基线，无返回值，不导入已有数据。 */
    private fun createSchema(db: SupportSQLiteDatabase) {
        listOf(
            "message_replies", "message_recall_text", "message_auto_retry",
            "message_sync_state", "messages", "conversation_read_state", "conversation_deletions",
            "conversation_remote_summary", "conversation_preferences", "contact_name_cache",
            "contacts", "profile", "conversations",
        ).forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
        PcContentSchema.create(db)
    }
}
