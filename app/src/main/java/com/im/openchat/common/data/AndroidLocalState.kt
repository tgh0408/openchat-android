package com.im.openchat.common.data

import android.content.Context
import java.io.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.db.SupportSQLiteDatabase
import com.im.openchat.address.data.Contact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅设备使用的展示状态不属于 PC 内容数据库。 */
@Serializable
internal data class AndroidLocalState(
    val contactIds: Set<String> = emptySet(),
    val starredIds: Set<String> = emptySet(),
    val requests: List<LocalContactRequest> = emptyList(),
    val bio: String = "",
    val avatar: String = "",
    val avatarUids: Map<String, List<String>> = emptyMap(),
)

@Serializable
internal data class LocalContactRequest(val id: String, val nickname: String, val avatar: String, val signature: String) {
    fun toModel(): Contact = Contact(id = id, name = nickname, avatar = avatar, role = signature)
}

internal class AndroidLocalStateStore(
    context: Context, databaseName: String, private val server: String = "",
) {
    private val store = context.applicationContext.openChatDataStore
    private val key = stringPreferencesKey("android_ui_$databaseName")
    private val serverKey = stringPreferencesKey("content_server_$databaseName")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): AndroidLocalState =
        store.data.first()[key]?.let { json.decodeFromString<AndroidLocalState>(it) } ?: AndroidLocalState()

    suspend fun save(data: ChatData) {
        val state = AndroidLocalState(
            contactIds = data.contacts.filterNot { it.group }.map { it.id }.toSet(),
            starredIds = data.contacts.filter { it.starred && !it.group }.map { it.id }.toSet(),
            requests = data.requests.map { LocalContactRequest(it.id, it.name, it.avatar, it.role) },
            bio = data.profile.bio,
            avatar = data.profile.avatar,
            avatarUids = data.conversations.filter { it.avatarUids.isNotEmpty() }
                .associate { it.contactId to it.avatarUids },
        )
        store.edit { it[key] = json.encodeToString(state) }
    }

    /** 内容库重建时丢弃对应的本机展示状态和服务器绑定，不影响全局配置或认证，无返回值。 */
    fun clear() {
        runBlocking {
            store.edit {
                it.remove(key)
                it.remove(serverKey)
            }
        }
    }

    fun bindServer(db: SupportSQLiteDatabase) {
        if (server.isEmpty()) return
        val hasRemote = hasRemoteConversations(db)
        runBlocking {
            store.edit { preferences ->
                val previous = preferences[serverKey]
                if (previous != null && previous != server ||
                    previous == null && hasRemote
                ) throw IOException("内容库服务器归属不匹配或未知；禁止混用其他服务器的数据")
                preferences[serverKey] = server
            }
        }
    }

    private fun hasRemoteConversations(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM conversations WHERE type IS NOT NULL LIMIT 1").use { it.moveToFirst() }
}
