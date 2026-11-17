package com.im.openchat.chat.data

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PC 偏好接口面向房间，好友和群聊都使用相同接口。 */
internal class ConversationPreferencesRepository(
    private val validate: (String) -> Unit,
    private val onUnauthorized: () -> Unit,
    private val transport: suspend (String, String) -> String,
) {
    constructor(
        api: ApiClient,
        validate: (String) -> Unit,
        onUnauthorized: () -> Unit,
    ) : this(validate, onUnauthorized, { path, body -> api.postJson(path, body).body })

    suspend fun setPinned(room: String, enabled: Boolean) =
        preference(room, "/capi/user/friend/topStatus", enabled)

    suspend fun setMuted(room: String, enabled: Boolean) =
        preference(room, "/capi/chat/contact/notDisturb", enabled)

    private suspend fun preference(room: String, path: String, enabled: Boolean) {
        val id = room.toLongOrNull()?.takeIf { it > 0 && it.toString() == room }
            ?: throw IOException("会话标识无效")
        val body = GroupDetailsProtocol.preferenceRequest(id, enabled)
        validate(room)
        try {
            val response = transport(path, body)
            validate(room)
            withContext(Dispatchers.Default) { GroupDetailsProtocol.success(response, serviceLabel = "会话设置") }
            validate(room)
        } catch (exception: ApiHttpException) {
            validate(room)
            if (exception.response.statusCode in setOf(401, 403)) {
                onUnauthorized()
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("会话设置请求失败（HTTP ${exception.response.statusCode}）", exception)
        } catch (exception: InvalidSessionException) {
            validate(room)
            onUnauthorized()
            throw exception
        }
    }
}
