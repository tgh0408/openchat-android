package com.im.openchat.chat.data

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** 对齐服务端 YesOrNoEnum，仅接受显式的二值协议。 */
private enum class ConversationFlag(val wireValue: Int) {
    NO(0), YES(1);

    companion object {
        /**
         * 解析服务端二值标记。
         * @param value 原始 JSON 值。
         * @return 对应布尔值；非法值抛出异常，无副作用。
         */
        fun parse(value: Any?): Boolean = when (value) {
            NO.wireValue -> false
            YES.wireValue -> true
            else -> throw IOException("会话状态无效")
        }
    }
}

/**
 * 获取真实会话快照，头像资料交由可见项单独懒加载，不阻塞列表展示。
 * @param api 当前授权共用的客户端；仓库实例仅用于一个授权生命周期。
 */
class ConversationRepository(private val api: ApiClient) {
    /**
     * 单次拉取全部可见会话，不分页、不读取聊天记录、不推进已读。
     * @return 完整解析的会话列表及群头像成员 ID；失败抛出 IOException，取消传播。
     * 只请求会话摘要，不获取头像资料或图片，不写入存储。
     */
    suspend fun list(): List<Conversation> {
        val response = post("/capi/chat/contact/list")
        return withContext(Dispatchers.Default) {
            parseConversations(response)
        }
    }

    /** 点击会话时获取当前摘要和未读快照，不在本地推断已读或改写消息。 */
    suspend fun info(roomId: String): Conversation {
        if (readId(roomId) != roomId) throw IOException("会话标识无效")
        val response = post("/capi/chat/contact/info/$roomId")
        return withContext(Dispatchers.Default) {
            parseConversationInfo(response, roomId)
        }
    }

    /**
     * 使用共享客户端请求 JSON，统一识别 HTTP 及业务层鉴权失效。
     * @param path 服务端接口路径。
     * @param body 可选 JSON 请求体；列表接口不要求参数。
     * @return 已解析的成功响应；发起网络请求，不修改令牌和存储。
     */
    private suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject {
        val response = try {
            api.postJson(path, body.toString())
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode in setOf(401, 403)) {
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("会话服务请求失败（HTTP ${exception.response.statusCode}）", exception)
        }
        return withContext(Dispatchers.Default) { successfulResponse(response.body) }
    }
}

/**
 * 校验统一业务响应并区分令牌拒绝和一般业务失败。
 * @param body 服务端 JSON 正文。
 * @return 成功响应的根对象；无副作用，非法数据或失败抛出 IOException。
 */
private fun successfulResponse(body: String): JSONObject {
    val root = try {
        JSONObject(body)
    } catch (exception: Exception) {
        throw IOException("会话服务返回的数据无效", exception)
    }
    if (root.optInt("code") in setOf(401, 403)) throw InvalidSessionException("登录已过期，请重新登录")
    if (root.opt("success") != true || root.opt("code") != 200) {
        throw IOException(root.optString("errMsg", "").takeIf { it.isNotBlank() } ?: "获取会话失败，请重试")
    }
    return root
}

/**
 * 解析真实房间列表，ID 保留字符串精度，摘要不伪装为消息。
 * @param root 已验证业务成功的响应根对象。
 * @return 已校验且无重复房间号的快照；无副作用，任一关键字段错误则拒绝整个快照。
 */
private fun parseConversations(root: JSONObject): List<Conversation> {
    try {
        val array = root.optJSONArray("data") ?: throw IOException("会话列表无效")
        val seen = mutableSetOf<String>()
        // 每项同时校验房间类型、二值状态、时间及未读数；无存储或状态副作用。
        return (0 until array.length()).map { index ->
            parseConversation(array.getJSONObject(index)).also {
                if (!seen.add(it.contactId)) throw IOException("会话列表包含重复房间")
            }
        }
    } catch (exception: IOException) {
        throw exception
    } catch (exception: Exception) {
        throw IOException("会话列表服务返回的数据无效", exception)
    }
}

/** 详情与列表共用字段校验，额外拒绝其他房间的响应。 */
internal fun parseConversationInfo(root: JSONObject, roomId: String): Conversation {
    try {
        val item = root.optJSONObject("data") ?: throw IOException("会话详情无效")
        return parseConversation(item).also {
            if (it.contactId != roomId) throw IOException("会话详情房间不匹配")
        }
    } catch (exception: IOException) {
        throw exception
    } catch (exception: Exception) {
        throw IOException("会话详情服务返回的数据无效", exception)
    }
}

private fun parseConversation(item: JSONObject): Conversation {
    val id = readId(item.opt("roomId")) ?: throw IOException("会话标识无效")
    val type = (item.opt("type") as? Int)?.let(RoomType::fromWireValue)
        ?: throw IOException("会话类型无效")
    val participantId = if (item.isNull("participantId")) null else {
        readId(item.opt("participantId"))?.takeIf { type == RoomType.FRIEND }
            ?: throw IOException("会话对方标识无效")
    }
    val unread = item.opt("unreadCount") as? Int ?: throw IOException("会话未读数无效")
    val activeTime = if (item.isNull("activeTime")) 0L else {
        readNonNegativeLong(item.opt("activeTime")) ?: throw IOException("会话时间无效")
    }
    if (unread < 0 || activeTime > 253402300799999L) throw IOException("会话时间或未读数无效")
    val avatars = item.optJSONArray("avatarUidList")
    if (!item.isNull("avatarUidList") && avatars == null || (avatars?.length() ?: 0) > 9) {
        throw IOException("群头像成员列表无效")
    }
    val uids = (0 until (avatars?.length() ?: 0)).map {
        readId(avatars!!.get(it)) ?: throw IOException("群头像成员标识无效")
    }
    val removed = item.opt("groupRemoved")
    if (removed != null && removed != JSONObject.NULL && removed !is Boolean ||
        removed == true && type != RoomType.GROUP
    ) throw IOException("会话退出状态无效")
    val name = if (item.isNull("name")) "" else item.getString("name")
    val title = name.ifBlank {
        when (type) {
            RoomType.FILE_ASSISTANT -> "文件助手"
            RoomType.FRIEND -> "用户 ${participantId ?: id}"
            RoomType.GROUP -> if (removed == true) "群聊" else ""
        }
    }
    return Conversation(
        contactId = id, roomType = type, title = title, participantId = participantId,
        pinned = ConversationFlag.parse(item.opt("isTop")),
        muted = if (item.isNull("notDisturb")) false else ConversationFlag.parse(item.opt("notDisturb")),
        unread = unread, activeTime = activeTime, time = formatConversationTime(activeTime),
        preview = if (item.isNull("text")) null else item.getString("text"),
        lastMsgId = readBoundary(item, "lastMsgId"),
        lastReadMsgId = readBoundary(item, "lastReadMsgId"),
        groupRemoved = removed == true, avatarUids = uids,
    )
}

/**
 * 读取正整数房间或用户 ID，拒绝浮点数和超出服务端 Long 范围的值。
 * @param value 原始 JSON 数值或字符串。
 * @return 无精度损失的标识，非法时返回 null；无副作用。
 */
private fun readId(value: Any?): String? {
    val text = when (value) {
        is String -> value
        is Int, is Long -> value.toString()
        else -> return null
    }
    return text.takeIf { Regex("[1-9][0-9]{0,18}").matches(it) && it.toLongOrNull() != null }
}

/**
 * 读取非负整数，不接受浮点数或隐式转换。
 * @param value 原始 JSON 值。
 * @return 非负 Long，非法时返回 null；无副作用。
 */
private fun readNonNegativeLong(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is String -> value.toLongOrNull()
    else -> null
}?.takeIf { it >= 0 }

/**
 * 读取允许零值的消息快照边界，缺省时不猜测。
 * @param item 当前房间 JSON。
 * @param key 已读或最新消息边界字段。
 * @return 标识字符串或 null；格式错误抛出异常，无副作用。
 */
private fun readBoundary(item: JSONObject, key: String): String? {
    if (item.isNull(key)) return null
    val value = item.opt(key)
    if (value == "0" || value == 0 || value == 0L) return "0"
    return readId(value) ?: throw IOException("会话消息边界无效")
}

/**
 * 按聊天展示时区的日期展示会话活跃时间，原始时间戳仍保留用于排序。
 * @param timestamp 服务端 Unix 毫秒时间戳。
 * @return 当天的时分或其他日期的年月日，零值为空；无副作用。
 */
fun formatConversationTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val date = formatChatTime(timestamp, "yyyy-MM-dd")
    return if (date == formatChatTime(System.currentTimeMillis(), "yyyy-MM-dd")) {
        formatChatTime(timestamp, "HH:mm")
    } else date
}
