package com.im.openchat.address.data

import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
private data class AddressResponse(
    val code: Int,
    val success: Boolean = false,
    val errMsg: String? = null,
    val data: JsonElement = JsonNull,
)

@Serializable
private data class FriendPageRequest(val cursor: String, val pageSize: Int = 100)
@Serializable
private data class ApplicationPageRequest(val pageNo: Int, val pageSize: Int = 10)
@Serializable
private data class ProfileRequest(val targetUid: Long, val roomId: Long)
@Serializable
private data class SearchRequest(val searchText: String)
@Serializable
private data class UserProfileRequest(val searchText: String, val code: Int = 3)
@Serializable
private data class ApprovalRequest(val applyId: Long, val friendId: Long)
@Serializable
private data class ReplyRequest(val applyId: Long, val msg: String)
@Serializable
private data class ApplyRequest(
    val targetUid: Long,
    val message: String,
    val remark: String,
    val tags: List<String> = emptyList(),
    val permission: String = "all",
    val hideMine: Boolean = false,
    val hideTheirs: Boolean = false,
)
@Serializable
private data class AddressNotification(
    val eventType: String,
    val targetUid: Long? = null,
    val roomId: Long? = null,
    val removedUids: List<Long>? = null,
)

/** 当前 PC 通讯录协议的唯一编解码入口，不读取历史格式。 */
internal object AddressProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val letter = Regex("[A-Z]|#")
    private const val FRIEND_APPLICATION_TYPE = 1

    /** 编码不透明好友游标，不解析或补造分页边界。 */
    fun friendsRequest(cursor: String): String = json.encodeToString(FriendPageRequest(cursor))
    /** 编码固定十条的申请页码，非法参数不发请求。 */
    fun applicationsRequest(page: Int): String {
        checkData(page in 1..1_000_000)
        return json.encodeToString(ApplicationPageRequest(page))
    }
    /** 绑定真实 UID 和房间查询好友资料。 */
    fun profileRequest(uid: Long, roomId: Long): String {
        checkData(uid > 0 && roomId > 0)
        return json.encodeToString(ProfileRequest(uid, roomId))
    }
    /** 编码公开用户搜索词，不提交账号归属或猜测搜索类型。 */
    fun searchRequest(query: String): String {
        checkData(query.trim().length in 1..128)
        return json.encodeToString(SearchRequest(query.trim()))
    }
    /** UID 显式使用服务端 IdTypeEnum.ID，避免纯数字 UID 被识别为手机号。 */
    fun userProfileRequest(uid: Long): String {
        checkData(uid > 0)
        return json.encodeToString(UserProfileRequest(uid.toString()))
    }
    /** 编码收到的申请审批，禁止审批本人或已处理记录。 */
    fun approveRequest(application: FriendApplication, owner: Long): String {
        checkData(application.canApprove && application.applyId > 0 &&
            application.uid == application.peerUid && application.peerUid > 0 && application.peerUid != owner)
        return json.encodeToString(ApprovalRequest(application.applyId, application.peerUid))
    }
    /** 回复沿用原申请，不生成反向申请。 */
    fun replyRequest(application: FriendApplication, text: String): String {
        checkData(application.applyId > 0 && application.status == ApplicationStatus.PENDING && text.trim().length in 1..200)
        return json.encodeToString(ReplyRequest(application.applyId, text.trim()))
    }
    /** 编码添加申请，预留权限字段保持 PC 默认值，不宣称生效。 */
    fun applyRequest(uid: Long, owner: Long, message: String, remark: String): String {
        checkData(uid > 0 && uid != owner && message.trim().length in 1..200 && remark.length <= 64)
        return json.encodeToString(ApplyRequest(uid, message.trim(), remark.trim()))
    }

    /** 校验分页布尔值、Long 标识、组名及有界文本。 */
    fun friends(body: String): DirectoryFriendPage = decode<DirectoryFriendPage>(body).also { page ->
        checkData(page.list.size <= 100 && (page.isLast || !page.cursor.isNullOrBlank()))
        checkData((page.cursor?.length ?: 0) <= 16384)
        page.list.forEach {
            checkData(it.uid > 0 && (it.roomId == null || it.roomId > 0))
            text(it.name, 256); text(it.displayName, 256); text(it.remark, 256)
            checkData(letter.matches(it.indexLetter))
        }
        checkData(page.list.distinctBy { it.uid }.size == page.list.size)
    }

    /** 群列表只解码当前页面使用的摘要，头像列表最大九人。 */
    fun groups(body: String): List<DirectoryGroup> = decode<List<DirectoryGroup>>(body).also { groups ->
        checkData(groups.size <= 100_000)
        groups.forEach {
            checkData(it.groupId > 0 && it.roomId > 0 && it.memberCount in 0..100_000)
            text(it.name, 256)
            checkData(it.avatarUidList.orEmpty().size <= 9 && it.avatarUidList.orEmpty().all { uid -> uid > 0 })
        }
        checkData(groups.distinctBy { it.roomId }.size == groups.size)
    }

    /** 核对方向、本人身份及对方身份，拒绝不属于当前账号的申请。 */
    fun applications(body: String, owner: Long): FriendApplicationPage = decode<FriendApplicationPage>(body).also { page ->
        checkData(page.list.size <= 10 && (page.isLast || page.list.isNotEmpty()))
        page.list.forEach {
            checkData(it.type == FRIEND_APPLICATION_TYPE && it.status in ApplicationStatus.PENDING..ApplicationStatus.REJECTED)
            checkData(it.applyId > 0 && it.uid > 0 && it.peerUid > 0 && it.peerUid != owner)
            checkData(if (it.direction == ApplicationDirection.RECEIVED) it.uid == it.peerUid else it.uid == owner)
            text(it.name, 256)
            checkData(it.msg.isNotEmpty() && it.msg.any { message -> message.uid == it.uid })
            it.msg.forEach { message ->
                checkData(message.uid == owner || message.uid == it.peerUid)
                text(message.msg, 2048)
            }
        }
        checkData(page.list.distinctBy { it.applyId }.size == page.list.size)
    }

    /** 失败不当作零未读，保留所有服务端确认的未读状态。 */
    fun unread(body: String): List<ApplicationUnread> = decode<List<ApplicationUnread>>(body).also { list ->
        checkData(list.size <= 1000)
        list.forEach { checkData(it.uid > 0 && it.applyStatus in ApplicationStatus.PENDING..ApplicationStatus.REJECTED) }
    }

    /** 搜索和好友详情共用公开字段校验，详情额外核对目标与来源房间。 */
    fun profile(body: String, uid: Long? = null, room: Long? = null): DirectoryProfile =
        decode<DirectoryProfile>(body).also {
            checkData(it.uid > 0 && (uid == null || it.uid == uid) && (room == null || it.roomId == room))
            checkData(it.roomId == null || it.roomId > 0)
            text(it.name, 256); checkData(it.name.isNotBlank())
            text(it.remark, 256); text(it.avatar, 2048); text(it.personalitySignature, 512)
        }

    /** 明确 ADDED 才算直接添加，空成功仅表示提交成功。 */
    fun applicationResult(body: String, expectedUid: Long): ApplicationResult? {
        val data = successfulData(body)
        if (data == JsonNull) return null
        return decodeData<ApplicationResult>(data).also {
            checkData(it.status == "ADDED" && it.uid == expectedUid && it.roomId > 0 && letter.matches(it.indexLetter))
        }
    }

    /** 确认统一成功响应；回复必须返回空 data。 */
    fun success(body: String, requireEmpty: Boolean = false) {
        val data = successfulData(body)
        if (requireEmpty) checkData(data == JsonNull)
    }

    /** 通知只触发重读，不将事件数据直接作为关系或授权。 */
    fun isRefreshNotification(body: String, owner: Long): Boolean = try {
        val event = json.decodeFromString<AddressNotification>(body)
        event.eventType == "FRIEND_APPLY" && event.targetUid == owner ||
            event.eventType == "GROUP_MEMBERS_REMOVED" && (event.roomId ?: 0) > 0 &&
            !event.removedUids.isNullOrEmpty() && event.removedUids.size <= 100 &&
            event.removedUids.all { it > 0 }
    } catch (_: SerializationException) { false }

    /** 校验统一状态后类型化解码，不让界面或仓库逐字段解析 JSON。 */
    private inline fun <reified T> decode(body: String): T = decodeData(successfulData(body))

    /** 将序列化失败转换为业务可展示错误，不输出原始响应。 */
    private inline fun <reified T> decodeData(data: JsonElement): T = try {
        json.decodeFromJsonElement<T>(data)
    } catch (exception: SerializationException) {
        throw IOException("通讯录服务返回的数据无效", exception)
    }

    /** 解析业务状态，认证拒绝交给现有授权生命周期处理。 */
    private fun successfulData(body: String): JsonElement {
        val root = try {
            json.decodeFromString<AddressResponse>(body)
        } catch (exception: SerializationException) {
            throw IOException("通讯录服务返回的数据无效", exception)
        }
        if (root.code in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
        if (!root.success || root.code != 200) throw IOException(
            root.errMsg?.take(256)?.takeIf(String::isNotBlank) ?: "通讯录请求失败，请重试",
        )
        return root.data
    }

    /** 检查文本边界和控制字符，不修改服务端排序字段。 */
    private fun text(value: String?, max: Int) {
        checkData(value == null || value.length <= max && '\u0000' !in value)
    }
    /** 将不合法协议值统一转为可恢复读取失败。 */
    private fun checkData(valid: Boolean) {
        if (!valid) throw IOException("通讯录数据或请求参数无效")
    }
}
