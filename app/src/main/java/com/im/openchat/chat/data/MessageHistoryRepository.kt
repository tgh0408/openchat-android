package com.im.openchat.chat.data

import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.ChatStore
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 对齐 PC 的最新缺口补齐和向前游标分页；一个实例仅服务于当前授权。
 * @param api 当前授权复用的 API 客户端。
 * @param store 统一的 Room 存储入口。
 * @param owner 当前服务器与账号归属。
 */
internal class MessageHistoryRepository(
    private val api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
) {
    private val mutex = Mutex()
    private val olderCursors = mutableMapOf<String, MutableSet<String>>()
    private val synchronizedRooms = mutableSetOf<String>()

    /**
     * 串行补齐最新记录或扩展一页旧记录，失败保留原持久游标，取消继续传播。
     * @param roomId 当前真实房间号。
     * @param older 是否继续向前分页；没有完成最新对账时先补齐最新缺口。
     * @param validate 校验当前授权、页面和成员状态；失效时抛出取消异常，不接受迟到结果。
     * @return Room 中去重且按时间升序的消息及是否还有远端历史；发起网络请求并原子写入页面。
     */
    suspend fun sync(roomId: String, older: Boolean, validate: () -> Unit): MessageHistoryResult =
        mutex.withLock {
            validate()
            try {
                if (!older || roomId !in synchronizedRooms) {
                    syncLatest(roomId, validate)
                    synchronizedRooms.add(roomId)
                }
                if (older) syncOlder(roomId, validate)
                validate()
                store.loadMessageHistory(owner, roomId)
            } catch (exception: Exception) {
                synchronizedRooms.remove(roomId)
                throw exception
            }
        }

    /**
     * 从首页补到上次确认的最新 ID；中间页只保存消息，不提前推进连续区间。
     * @param roomId 真实房间号。
     * @param validate 每次请求与写入前校验当前页面及授权，无返回值。
     * 无返回值；网络或存储失败中止，已保存的中间消息允许下次重复合并。
     */
    private suspend fun syncLatest(roomId: String, validate: () -> Unit) {
        val previous = store.loadMessageHistoryState(owner, roomId)
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var latestId: String? = null
        var oldest = previous?.oldest
        while (true) {
            validate()
            val page = requestPage(roomId, cursor)
            validate()
            // 服务端按发送时间排列响应，最新同步边界取最大消息 ID，不依赖列表首项。
            if (latestId == null) latestId = page.messages.maxByOrNull { it.id.toLong() }?.id
            oldest = pageFloor(page, oldest)
            val joined = previous?.latestId != null && page.messages.any { it.id == previous.latestId }
            val complete = previous?.latestId == null || joined || page.isLast
            val state = if (complete) MessageHistoryState(
                roomId = roomId,
                latestId = latestId ?: previous?.latestId,
                olderCursor = if (joined) previous?.olderCursor else page.cursor,
                oldest = oldest,
                exhausted = page.isLast || joined && previous?.exhausted == true,
            ) else null
            if (!complete && (page.cursor == null || page.cursor == cursor || page.cursor in cursors)) {
                throw IOException("聊天记录服务返回重复或空的分页游标")
            }
            store.saveMessageHistoryPage(owner, roomId, page.messages, state)
            validate()
            if (complete) return
            cursor = page.cursor
            cursors.add(checkNotNull(cursor))
        }
    }

    /**
     * 扩展已确认区间的旧历史，消息与新游标在同一事务保存。
     * @param roomId 真实房间号。
     * @param validate 当前页面及授权校验，无返回值。
     * 无返回值；已读完时不请求，重复或缺失游标报错而不推进进度。
     */
    private suspend fun syncOlder(roomId: String, validate: () -> Unit) {
        validate()
        val previous = store.loadMessageHistoryState(owner, roomId) ?: return
        if (previous.exhausted) return
        val cursor = previous.olderCursor ?: throw IOException("历史同步游标缺失，请重新进入会话")
        val page = requestPage(roomId, cursor)
        validate()
        val seen = olderCursors.getOrPut(roomId) { mutableSetOf() }
        if (!page.isLast && (page.cursor == null || page.cursor == cursor || page.cursor in seen)) {
            throw IOException("聊天记录服务返回重复或空的分页游标")
        }
        store.saveMessageHistoryPage(
            owner, roomId, page.messages,
            previous.copy(olderCursor = page.cursor, oldest = pageFloor(page, previous.oldest), exhausted = page.isLast),
        )
        seen.add(cursor)
    }

    /**
     * 通过共用接口按 ID 游标向前请求历史，首请求传空字符串，固定每页 50 条。
     * @param roomId 服务端 Long 范围内的房间号。
     * @param cursor 服务端返回的游标，首页为 null。
     * @return 对象反序列化并校验后的页；网络请求可取消，HTTP 和业务鉴权拒绝上抛为登录失效。
     */
    private suspend fun requestPage(roomId: String, cursor: String?): MessageHistoryPage {
        if (readHistoryId(roomId) != roomId || cursor != null && readHistoryId(cursor) != cursor) {
            throw IOException("聊天记录分页参数无效")
        }
        val response = try {
            api.postJson(
                "/capi/chat/msg/page",
                MessageHistoryProtocol.encodeRequest(
                    MessageHistoryRequest(roomId.toLong(), PAGE_SIZE, cursor.orEmpty()),
                ),
            )
        } catch (exception: ApiHttpException) {
            if (exception.response.statusCode == 401 || exception.response.statusCode == 403) {
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("聊天记录请求失败（HTTP ${exception.response.statusCode}）", exception)
        }
        // 在工作线程反序列化为后端响应对象并校验分页，不写入存储；取消继续传播。
        return withContext(Dispatchers.Default) {
            try {
                validateHistoryPage(MessageHistoryProtocol.decodePage(response.body), roomId, cursor)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                throw IOException("聊天记录服务返回的数据无效", exception)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

/**
 * 核对类型化历史页、房间身份和 ID 游标范围，不直接读取 JSON 或要求列表按 ID 排序。
 * @param page 已反序列化的服务端分页对象。
 * @param roomId 本次请求的房间号。
 * @param requestCursor 本次请求游标，首次为空。
 * @return 可原子持久化的完整消息页；无副作用。
 */
private fun validateHistoryPage(
    page: MessageHistoryPageResponse,
    roomId: String,
    requestCursor: String?,
): MessageHistoryPage {
    val cursor = page.cursor?.takeUnless(String::isEmpty)
    if (cursor != null && readHistoryId(cursor) != cursor) throw IOException("聊天记录游标无效")
    if (!page.isLast && (cursor == null || cursor == requestCursor)) throw IOException("聊天记录游标未推进")
    if (page.list.size > 50 || !page.isLast && page.list.isEmpty()) throw IOException("聊天记录分页大小无效")
    val upperBound = requestCursor?.toLong()
    val seenIds = mutableSetOf<String>()
    // 校验整页之后才交给存储，未知类型也保留 ID 和时间以推进同步边界。
    val messages = page.list.map { item ->
        val message = item.toHistoryMessage(roomId)
        if (!seenIds.add(message.id)) throw IOException("聊天记录包含重复消息")
        if (upperBound != null && item.message.id >= upperBound) throw IOException("聊天记录消息超出分页范围")
        message
    }
    // 游标来自数据库 ID 倒序页的最小 ID，响应经过发送时间排序后不一定仍在末项。
    val oldestId = messages.minByOrNull { it.id.toLong() }?.id
    if (cursor != null && oldestId != null && cursor != oldestId) {
        throw IOException("聊天记录游标与页边界不匹配")
    }
    return MessageHistoryPage(cursor, page.isLast, messages)
}

/** 历史与实时推送使用相同的身份、时间、文本和客户端标识校验。 */
internal fun ChatMessageResponse.toHistoryMessage(expectedRoomId: String = message.roomId.toString()): HistoryMessage {
    if (message.id <= 0) throw IOException("聊天记录消息标识无效")
    if (fromUser.uid <= 0) throw IOException("聊天记录发送者无效")
    if (message.roomId <= 0 || message.roomId.toString() != expectedRoomId) {
        throw IOException("聊天记录所属房间不匹配")
    }
    val timestamp = message.sendTime
    if (timestamp !in 0L..253402300799999L) throw IOException("聊天记录发送时间无效")
    val body = message.body
    if (body is TextMessageBody && (body.content.length > 65536 || '\u0000' in body.content)) {
        throw IOException("聊天记录文本内容无效")
    }
    val clientId = message.clientMsgId
    if (clientId != null && (clientId.isBlank() || clientId.length > 64 || '\u0000' in clientId)) {
        throw IOException("聊天记录客户端标识无效")
    }
    if (message.replyId != null && message.replyId <= 0) throw IOException("引用消息标识无效")
    if (message.type == MessageType.SYSTEM && clientId != null) throw IOException("系统提示客户端标识无效")
    return HistoryMessage(
        message.id.toString(), expectedRoomId, fromUser.uid.toString(), clientId,
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp)),
        message.type, body, message.replyId?.toString(),
    )
}

/**
 * 校验请求参数中的服务端 Long 标识，不接受浮点数、零或超出范围的值。
 * @param value 房间号或分页游标字符串。
 * @return 无精度损失的正整数字符串，非法时为空；无副作用。
 */
private fun readHistoryId(value: String): String? =
    value.takeIf { Regex("[1-9][0-9]{0,18}").matches(it) && it.toLongOrNull() != null }

/**
 * 计算包含未展示类型的最早分页边界，与 PC 的本地时间、ID 排序一致。
 * @param page 已校验的服务端页。
 * @param previous 上次确认的最早位置。
 * @return 新的最早位置或 null；无副作用。
 */
private fun pageFloor(page: MessageHistoryPage, previous: MessageHistoryPosition?): MessageHistoryPosition? {
    var oldest = previous
    for (message in page.messages) {
        val current = oldest
        if (current == null || message.createdAt < current.createdAt ||
            message.createdAt == current.createdAt && message.id < current.id
        ) oldest = MessageHistoryPosition(message.createdAt, message.id)
    }
    return oldest
}
