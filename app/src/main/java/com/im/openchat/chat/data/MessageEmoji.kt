package com.im.openchat.chat.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 读取已解码的表情正文，与 PC 一样隔离非法地址，不阻断历史同步。 */
internal fun EmojiMessageBody.toMessageEmoji(): MessageEmoji {
    val id = extra?.emojiId?.takeIf { it > 0 }
    val url = content?.takeIf { it.length <= 2048 && it.none { char -> char <= ' ' } }
        ?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
        ?.newBuilder()?.fragment(null)?.build()?.toString()
    val description = extra?.description?.takeIf {
        it.isNotBlank() && it.length <= 255 && it.none { char -> char < ' ' || char == '\u007f' }
    } ?: "表情包"
    return MessageEmoji(id, url.takeIf { id != null }, description)
}
