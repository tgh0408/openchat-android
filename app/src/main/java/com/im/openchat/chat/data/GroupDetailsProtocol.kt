package com.im.openchat.chat.data

import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
internal data class GroupDetails(
    val groupId: Long,
    val roomId: Long,
    val name: String,
    val memberCount: Int? = null,
    val notice: GroupNotice? = null,
    val qrCode: List<GroupQrCode>? = null,
)

@Serializable
internal data class GroupNotice(val description: String, val createTime: String, val uid: Long)

@Serializable
internal data class GroupQrCode(
    val url: String,
    val createTime: String,
    val expireTime: String? = null,
    val keyword: String? = null,
)

@Serializable
internal data class GroupMember(
    val uid: Long,
    val username: String,
    val nickname: String? = null,
    val role: Int? = null,
) {
    val label: String get() = nickname?.takeIf(String::isNotBlank)
        ?: username.ifBlank { "用户 $uid" }
    val canManage: Boolean get() = role == 1 || role == 2

/** 对齐 PC 的 canRemoveGroupMember；未知角色不能获得移除成员权限。 */
    fun canRemove(target: GroupMember): Boolean = uid != target.uid && when (role) {
        1 -> target.role == 2 || target.role == 3
        2 -> target.role == 3
        else -> false
    }
}

@Serializable
private data class GroupResponse(
    val code: Int,
    val success: Boolean = false,
    val errMsg: String? = null,
    val data: JsonElement = JsonNull,
)

@Serializable
private data class GroupUpdateRequest(
    val roomId: Long,
    val groupName: String? = null,
    val groupDescription: String? = null,
)

@Serializable
private data class GroupMembersRequest(val roomId: Long, val uids: List<Long>)

@Serializable
private data class GroupPreferenceRequest(val roomId: Long, val status: Int)

/** GroupResp、GroupMemberInfo 与 PC 群请求共用此类型化边界。 */
internal object GroupDetailsProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val recordDate = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})")

    fun roomId(value: String): Long = value.toLongOrNull()
        ?.takeIf { it > 0 && it.toString() == value }
        ?: throw IOException("群聊标识无效")

    fun groups(body: String): List<GroupDetails> = decode<List<GroupDetails>>(body).also { groups ->
        checkData(groups.size <= 100_000 && groups.distinctBy { it.roomId }.size == groups.size)
        groups.forEach {
            validateGroup(it)
            checkData(it.memberCount != null)
        }
    }

    fun updatedGroup(body: String, room: Long): GroupDetails = decode<GroupDetails>(body).also {
        validateGroup(it)
        checkData(it.roomId == room)
    }

    fun members(body: String): List<GroupMember> = decode<List<GroupMember>>(body).also { members ->
        checkData(members.size <= 100_000 && members.distinctBy { it.uid }.size == members.size)
        members.forEach {
            checkData(it.uid > 0 && (it.role == null || it.role >= 1))
            text(it.username, 64)
            text(it.nickname, 64)
        }
    }

    fun updateRequest(room: Long, name: String? = null, notice: String? = null): String {
        checkData(room > 0 && (name != null || notice != null))
        name?.let { text(it, 256); checkData(it.isNotBlank()) }
        notice?.let { text(it, 300); checkData(it.isNotBlank()) }
        return json.encodeToString(GroupUpdateRequest(room, name?.trim(), notice?.trim()))
    }

    fun membersRequest(room: Long, uids: List<Long>, owner: Long): String {
        checkData(room > 0 && uids.size in 1..100 && uids.all { it > 0 && it != owner })
        checkData(uids.distinct().size == uids.size)
        return json.encodeToString(GroupMembersRequest(room, uids))
    }

    fun preferenceRequest(room: Long, enabled: Boolean): String {
        checkData(room > 0)
        return json.encodeToString(GroupPreferenceRequest(room, if (enabled) 1 else 0))
    }

    fun success(body: String, empty: Boolean = false, serviceLabel: String = "群聊") {
        val data = successfulData(body, serviceLabel)
        if (empty) checkData(data == JsonNull)
    }

    private fun validateGroup(group: GroupDetails) {
        checkData(group.groupId > 0 && group.roomId > 0)
        checkData(group.memberCount == null || group.memberCount in 0..100_000)
        text(group.name, 256)
        group.notice?.let {
            text(it.description, 300)
            checkData(it.description.isNotBlank() && it.uid > 0)
            recordTime(it.createTime)
        }
        checkData(group.qrCode.orEmpty().size <= 100)
        group.qrCode.orEmpty().forEach {
            text(it.url, 2048)
            val url = it.url.toHttpUrlOrNull()
            checkData(url != null && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            recordTime(it.createTime)
            it.expireTime?.let(::recordTime)
            text(it.keyword, 256)
        }
    }

/** GroupResp 使用带明确时区偏移的 ISO 毫秒时间，不推断设备时区。 */
    fun recordTime(value: String): Long {
        checkData(value.length <= 64 && recordDate.matches(value))
        val position = ParsePosition(0)
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value, position)
        checkData(date != null && position.index == value.length)
        return requireNotNull(date).time
    }

    private fun text(value: String?, max: Int) {
        checkData(value == null || value.length <= max && '\u0000' !in value)
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromJsonElement<T>(successfulData(body))
    } catch (exception: SerializationException) {
        throw IOException("群聊服务返回的数据无效", exception)
    }

    private fun successfulData(body: String, serviceLabel: String = "群聊"): JsonElement {
        val response = try {
            json.decodeFromString<GroupResponse>(body)
        } catch (exception: SerializationException) {
            throw IOException("${serviceLabel}服务返回的数据无效", exception)
        }
        if (response.code in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
        if (!response.success || response.code != 200) {
            throw IOException(response.errMsg?.take(256)?.takeIf(String::isNotBlank) ?: "${serviceLabel}请求失败，请重试")
        }
        return response.data
    }

    private fun checkData(valid: Boolean) {
        if (!valid) throw IOException("群聊数据或请求参数无效")
    }
}
