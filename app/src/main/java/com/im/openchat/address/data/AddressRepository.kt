package com.im.openchat.address.data

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 复用现有 API 客户端；每次网络等待前后复核原授权，不持久化好友关系。 */
internal class AddressRepository(
    val ownerUid: Long,
    private val validate: () -> Unit,
    private val onUnauthorized: () -> Unit,
    private val transport: suspend (String, String, Boolean) -> String,
) {
    /** 生产入口复用唯一授权客户端；传输函数同时允许隔离协议与授权回归测试。 */
    constructor(
        api: ApiClient, ownerUid: Long, validate: () -> Unit, onUnauthorized: () -> Unit,
    ) : this(ownerUid, validate, onUnauthorized, { path, body, get ->
        (if (get) api.get(path) else api.postJson(path, body)).body
    })

    /** 全量完成后才发布好友，游标循环或跨页重复 UID 拒绝整个结果。 */
    suspend fun listFriends(): List<DirectoryFriend> {
        val friends = linkedMapOf<Long, DirectoryFriend>()
        val cursors = mutableSetOf("")
        var cursor = ""
        do {
            val page = parse(request("/capi/user/friend/page", AddressProtocol.friendsRequest(cursor)), AddressProtocol::friends)
            for (friend in page.list) {
                if (friends.put(friend.uid, friend) != null) throw IOException("好友列表发生变化，请刷新重试")
            }
            if (page.isLast) return friends.values.toList()
            cursor = page.cursor ?: throw IOException("好友分页游标无效")
            if (!cursors.add(cursor)) throw IOException("好友分页游标重复，请重试")
        } while (true)
    }

    /** 读取当前有效群关系，不从历史会话补造已退出的群。 */
    suspend fun listGroups(): List<DirectoryGroup> =
        parse(request("/capi/group/page/group"), AddressProtocol::groups)

    /** 单页读取申请，不批量标记已读。 */
    suspend fun listApplications(page: Int): FriendApplicationPage =
        parse(request("/capi/user/friend/apply/page", AddressProtocol.applicationsRequest(page))) {
            AddressProtocol.applications(it, ownerUid)
        }

    /** 获取权威未读摘要，读取失败保留此前界面状态。 */
    suspend fun unreadApplications(): List<ApplicationUnread> =
        parse(request("/capi/user/friend/apply/unread", get = true), AddressProtocol::unread)

    /** 点击申请详情才确认该申请已读。 */
    suspend fun readApplication(id: Long) {
        require(id > 0)
        AddressProtocol.success(request("/capi/user/friend/apply/read/$id", get = true))
    }

    /** 用户确认后审批原申请，不自动重试写操作。 */
    suspend fun approve(application: FriendApplication) {
        AddressProtocol.applicationResult(
            request("/capi/user/friend/agreeApply", AddressProtocol.approveRequest(application, ownerUid)),
            application.peerUid,
        )
    }

    /** 回复当前待处理申请，失败时由界面保留草稿。 */
    suspend fun reply(application: FriendApplication, text: String) {
        AddressProtocol.success(
            request("/capi/user/friend/replay", AddressProtocol.replyRequest(application, text)), requireEmpty = true,
        )
    }

    /** 好友详情绑定列表的真实房间，不调用会话读取或已读接口。 */
    suspend fun profile(friend: DirectoryFriend): DirectoryProfile {
        val room = friend.roomId ?: throw IOException("该好友缺少可用房间，请刷新后重试")
        return parse(request("/capi/user/friend/info", AddressProtocol.profileRequest(friend.uid, room))) {
            AddressProtocol.profile(it, friend.uid, room)
        }
    }

    /** 群详情响应的 roomId 是可操作的单聊，不要求等于来源群；本人不走单聊对方校验。 */
    suspend fun userProfile(target: UserProfileTarget): DirectoryProfile {
        target.friend?.let { return profile(it) }
        if (target.sourceRoomId != null && target.uid != ownerUid) {
            return parse(request(
                "/capi/user/friend/info", AddressProtocol.profileRequest(target.uid, target.sourceRoomId),
            )) { AddressProtocol.profile(it, target.uid) }
        }
        return parse(request("/capi/user/target/info", AddressProtocol.userProfileRequest(target.uid))) {
            AddressProtocol.profile(it, target.uid)
        }
    }

    /** 发消息前重新确认好友关系，不能使用来源群或公开资料中的房间作为单聊。 */
    suspend fun userMessageRoom(uid: Long): Long {
        if (uid == ownerUid) throw IOException("不能给自己发消息")
        val friend = listFriends().firstOrNull { it.uid == uid }
            ?: throw IOException("对方尚不是当前好友，请先添加朋友")
        val detail = profile(friend)
        if (detail.blacklisted) throw IOException("请先将对方移出黑名单")
        return detail.roomId ?: throw IOException("该好友缺少可用单聊，请刷新后重试")
    }

    /** 搜索后从真实好友列表区分本人、好友和陌生人，不以资料缓存判断关系。 */
    suspend fun search(query: String): DirectorySearchResult {
        val profile = parse(request("/capi/user/target/info", AddressProtocol.searchRequest(query))) {
            AddressProtocol.profile(it)
        }
        if (profile.uid == ownerUid) return DirectorySearchResult(profile, null, true)
        val friend = listFriends().firstOrNull { it.uid == profile.uid }
        return DirectorySearchResult(profile, friend, false)
    }

    /** 提交真实添加申请，只有确认 ADDED 才返回直接添加结果。 */
    suspend fun apply(uid: Long, message: String, remark: String): ApplicationResult? =
        AddressProtocol.applicationResult(
            request("/capi/user/friend/apply", AddressProtocol.applyRequest(uid, ownerUid, message, remark)), uid,
        )

    /** Revalidate the bound direct room; a source group must never become a write target. */
    suspend fun friendSettingsProfile(uid: Long, roomId: Long): DirectoryProfile {
        if (uid <= 0 || uid == ownerUid || roomId <= 0) throw IOException("好友操作目标无效")
        return parse(request("/capi/user/friend/info", AddressProtocol.profileRequest(uid, roomId))) {
            AddressProtocol.profile(it, uid, roomId)
        }
    }

    suspend fun setBlacklisted(uid: Long, roomId: Long, blacklisted: Boolean) {
        friendSettingsProfile(uid, roomId)
        val path = if (blacklisted) "/capi/user/friend/black/$roomId"
        else "/capi/user/friend/unBlack/$roomId"
        AddressProtocol.success(request(path, body = ""), requireEmpty = true)
    }

    /** PC's default deletion removes only the relationship, not conversation/history. */
    suspend fun deleteFriend(uid: Long, roomId: Long) {
        friendSettingsProfile(uid, roomId)
        AddressProtocol.success(request("/capi/user/friend/del/$roomId", body = ""), requireEmpty = true)
    }

    /** 公共请求入口处理 HTTP 和业务授权拒绝；取消继续传播。 */
    private suspend fun request(path: String, body: String = "{}", get: Boolean = false): String {
        currentCoroutineContext().ensureActive()
        validate()
        try {
            val response = transport(path, body, get)
            currentCoroutineContext().ensureActive()
            validate()
            withContext(Dispatchers.Default) { AddressProtocol.success(response) }
            validate()
            return response
        } catch (exception: ApiHttpException) {
            validate()
            if (exception.response.statusCode in setOf(401, 403)) {
                onUnauthorized()
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("通讯录请求失败（HTTP ${exception.response.statusCode}）", exception)
        } catch (exception: InvalidSessionException) {
            validate()
            onUnauthorized()
            throw exception
        }
    }

    /** 大列表解码移出主线程，回到调用线程再次核对授权后才发布结果。 */
    private suspend fun <T> parse(body: String, decode: (String) -> T): T {
        val result = withContext(Dispatchers.Default) { decode(body) }
        currentCoroutineContext().ensureActive()
        validate()
        return result
    }
}
