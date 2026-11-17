package com.im.openchat.chat.data

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class GroupDetailsRepository(
    val ownerUid: Long,
    private val validate: (String) -> Unit,
    private val onUnauthorized: () -> Unit,
    private val transport: suspend (String, String) -> String,
) {
    constructor(
        api: ApiClient,
        ownerUid: Long,
        validate: (String) -> Unit,
        onUnauthorized: () -> Unit,
    ) : this(ownerUid, validate, onUnauthorized, { path, body -> api.postJson(path, body).body })

/** PC 从群目录读取群资料；只发布本次请求的房间资料。 */
    suspend fun details(room: String): GroupDetails {
        val id = GroupDetailsProtocol.roomId(room)
        return request(room, "/capi/group/page/group") { body ->
            GroupDetailsProtocol.groups(body).firstOrNull { it.roomId == id }
                ?: throw IOException("你已不在该群中，或群聊已解散")
        }
    }

    suspend fun members(room: String): List<GroupMember> {
        GroupDetailsProtocol.roomId(room)
        return request(room, "/capi/group/page/groupMember/$room", decode = GroupDetailsProtocol::members)
    }

    suspend fun update(room: String, name: String? = null, notice: String? = null): GroupDetails {
        val id = GroupDetailsProtocol.roomId(room)
        return request(room, "/capi/group/update", GroupDetailsProtocol.updateRequest(id, name, notice)) {
            GroupDetailsProtocol.updatedGroup(it, id)
        }
    }

    suspend fun addMembers(room: String, uids: List<Long>) {
        request(room, "/capi/group/members/add",
            GroupDetailsProtocol.membersRequest(GroupDetailsProtocol.roomId(room), uids, ownerUid),
        ) { GroupDetailsProtocol.success(it, empty = true) }
    }

/** 写入前重新核对当前成员关系；最终以服务端判定为准。 */
    suspend fun removeMembers(room: String, uids: List<Long>) {
        val body = GroupDetailsProtocol.membersRequest(GroupDetailsProtocol.roomId(room), uids, ownerUid)
        val current = members(room)
        val operator = current.firstOrNull { it.uid == ownerUid }
            ?.takeIf { it.canManage } ?: throw IOException("当前已无管理员权限，请刷新群成员")
        val byUid = current.associateBy { it.uid }
        if (uids.any { uid -> byUid[uid]?.let(operator::canRemove) != true }) {
            throw IOException("所选成员不可移出，请刷新群成员后重试")
        }
        request(room, "/capi/group/members/remove", body) {
            GroupDetailsProtocol.success(it, empty = true)
        }
    }

    private val preferences = ConversationPreferencesRepository(validate, onUnauthorized, transport)

    suspend fun setPinned(room: String, enabled: Boolean) = preferences.setPinned(room, enabled)

    suspend fun setMuted(room: String, enabled: Boolean) = preferences.setMuted(room, enabled)

    suspend fun exit(room: String) {
        GroupDetailsProtocol.roomId(room)
        request(room, "/capi/group/exitGroup/$room") { GroupDetailsProtocol.success(it, empty = true) }
    }

/** 每次挂起操作前后都校验状态；取消不能转换成界面错误。 */
    private suspend fun <T> request(
        room: String,
        path: String,
        body: String = "{}",
        decode: (String) -> T,
    ): T {
        validate(room)
        try {
            val response = transport(path, body)
            validate(room)
            val result = withContext(Dispatchers.Default) { decode(response) }
            validate(room)
            return result
        } catch (exception: ApiHttpException) {
            validate(room)
            if (exception.response.statusCode in setOf(401, 403)) {
                onUnauthorized()
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("群聊请求失败（HTTP ${exception.response.statusCode}）", exception)
        } catch (exception: InvalidSessionException) {
            validate(room)
            onUnauthorized()
            throw exception
        }
    }
}
