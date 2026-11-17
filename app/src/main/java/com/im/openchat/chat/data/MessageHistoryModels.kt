package com.im.openchat.chat.data

import kotlinx.serialization.Serializable

/** 对齐服务端 MessageTypeEnum；未知类型只参与历史同步边界计算。 */
@Serializable(with = MessageTypeSerializer::class)
enum class MessageType(val wireValue: Int, val storageValue: String, val label: String) {
    TEXT(1, "text", ""),
    RECALL(2, "recalled", "该消息已撤回"),
    TEMPORARY(3, "temporary", "[上传消息]"),
    IMAGE(4, "image", "[图片]"),
    VIDEO(5, "video", "[视频]"),
    FILE(6, "file", "[文件]"),
    VOICE(7, "voice", "[音频]"),
    SOUND(8, "audio", "[语音]"),
    EMOJI(9, "emoji", "[表情]"),
    SYSTEM(10, "system", "[系统消息]"),
    RTC_CALL(11, "rtc-call", "[通话记录]"),
    UNKNOWN(-1, "unknown", "[暂不支持的消息]");

    companion object {
        /**
         * 显式映射服务端消息类型。
         * @param value 服务端整数协议值。
         * @return 对应类型或 UNKNOWN；无副作用，未知类型仍参与游标推进。
         */
        fun fromWireValue(value: Int): MessageType = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN

        /**
         * 还原数据库保存的消息类型。
         * @param value 消息表中的具名类型。
         * @return 对应类型或 UNKNOWN；无副作用。
         */
        fun fromStorageValue(value: String): MessageType =
            if (value == "upload-cancelled") TEMPORARY else entries.firstOrNull { it.storageValue == value } ?: UNKNOWN
    }
}

/** 服务端历史消息；标识保持字符串精度，未知类型只参与同步边界计算。 */
internal data class HistoryMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val clientMsgId: String?,
    val createdAt: String,
    val type: MessageType,
    val body: MessageBody,
    val replyId: String? = null,
)

/** 一页向前分页的历史，响应列表不保证 ID 倒序；cursor 只使用服务端实际返回值。 */
internal data class MessageHistoryPage(
    val cursor: String?,
    val isLast: Boolean,
    val messages: List<HistoryMessage>,
)

/** 与 PC 相同的本地时间和 ID 双字段排序边界。 */
internal data class MessageHistoryPosition(val createdAt: String, val id: String)

/** 已确认连续历史区间的持久进度，不把 SSE 消息或孤立的新页作为已补齐的边界。 */
internal data class MessageHistoryState(
    val roomId: String,
    val latestId: String?,
    val olderCursor: String?,
    val oldest: MessageHistoryPosition?,
    val exhausted: Boolean,
)

/** 单次同步后供聊天页展示的本地消息与远端分页能力。 */
internal data class MessageHistoryResult(val messages: List<Message>, val hasMore: Boolean, val unread: Int? = null)
