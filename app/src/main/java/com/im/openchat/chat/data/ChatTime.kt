package com.im.openchat.chat.data

import com.im.openchat.common.config.AppConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 仅转换聊天展示时间；协议和 Room 中的时间戳仍保持 UTC。 */
internal fun formatChatTime(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone(AppConfig.CHAT_TIME_ZONE_ID)
    }.format(Date(timestamp))
