package com.im.openchat.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.CachedImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * 对齐 PC 的本地优先消息图片读取，只在缺少文件时申请短期下载地址。
 * @param context 仅保留应用上下文。
 * @param api 当前授权客户端，仅用于申请下载授权，不用它向 OSS 下载。
 * @param owner 固定服务器和账号，用于隔离缓存目录。
 * @param validate 校验授权及房间，第二参数表示远端访问；无返回值，失效时抛异常。
 * @param onUnauthorized 认证拒绝后撤销当前授权，无返回值。
 */
internal class MessageImageRepository(
    context: Context,
    api: ApiClient,
    private val owner: ChatDataOwner,
    private val validate: (String, Boolean) -> Unit,
    onUnauthorized: () -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val authorization = MessageDownloadAuthorization(api, validate, onUnauthorized)

    /** Synchronous memory lookup with the same authorization and resource key as load(). */
    fun peek(roomId: String, message: Message, preview: Boolean = false): Bitmap? {
        validate(roomId, false)
        val url = message.image?.url ?: return null
        return CachedImages.peek(
            "messages\n${owner.serverUrl}\n${owner.userId}",
            "$roomId\n$url",
            if (preview) 2048 else 512,
        )
    }

    /** 表情与 PC 一样读取公开地址，不走普通图片的短期授权接口。 */
    suspend fun loadEmoji(roomId: String, message: Message): Drawable {
        validate(roomId, false)
        val emoji = message.emoji ?: throw IOException("表情包消息无效")
        val url = emoji.url ?: throw IOException("表情包不可用")
        val drawable = CachedImages.loadDrawable(
            applicationContext,
            "emojis\n${owner.serverUrl}\n${owner.userId}",
            "${emoji.id}\n$url",
            20L * 1024 * 1024,
            512,
            remote = {
                withContext(Dispatchers.Main.immediate) {
                    validate(roomId, true)
                    url
                }
            },
        )
        validate(roomId, false)
        return drawable
    }

    /**
     * 为可见消息读取图片，缓存缺失才签名下载；预览复用同一原始文件。
     * @param roomId 真实房间号。
     * @param message 真实缓存消息，需带有效图片元数据。
     * @param preview 是否按预览分辨率解码。
     * @return 缩采样位图；失败抛 IOException，取消传播，图片与认证不写入消息正文。
     */
    suspend fun load(roomId: String, message: Message, preview: Boolean = false): Bitmap {
        validate(roomId, false)
        val image = message.image ?: throw IOException("图片消息无效")
        val url = image.url ?: throw IOException("图片不可用")
        val bitmap = CachedImages.load(
            applicationContext,
            "messages\n${owner.serverUrl}\n${owner.userId}",
            "$roomId\n$url",
            50L * 1024 * 1024,
            if (preview) 2048 else 512,
            // 本地缓存未命中后才验证远端权限并申请签名，签名只保留在请求内存。
            remote = { withContext(Dispatchers.Main.immediate) { authorization.load(roomId, message.id) } },
        )
        validate(roomId, false)
        return bitmap
    }
}

/**
 * 从类型化图片正文读取公开元数据，与 PC 一样隔离无效地址和文件名。
 * @return 图片元数据；地址无效时返回不可用占位，不解析 JSON、下载图片或修改正文。
 */
internal fun ImageMessageBody.toMessageImage(): MessageImage {
    // 仅允许无签名、无凭据的公开 HTTP(S) 标识；返回规范化地址，无网络或存储副作用。
    val url = content?.takeIf { it.length <= 2048 && it.none { char -> char <= ' ' } }
        ?.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        }?.toString()
    // 展示文件名不含路径或控制字符；缺失时返回固定名称，不修改协议对象。
    val name = extra?.fileName?.takeIf {
        it.isNotBlank() && it.length <= 255 && it.none { char -> char < ' ' || char == '\u007f' || char == '/' || char == '\\' }
    } ?: "图片"
    return MessageImage(url, name)
}
