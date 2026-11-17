package com.im.openchat.common.data

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** 公开资料与私有名称分开保存；资料缓存不代表好友关系。 */
internal data class AvatarProfile(val uid: String, val name: String, val avatar: String)

internal fun resolveMessageDisplayName(remark: String?, groupNickname: String?, name: String): String =
    sequenceOf(remark, groupNickname, name).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull().orEmpty()

/** 对齐服务端 IdTypeEnum.ID，不能使用枚举 ordinal 作为协议值。 */
private enum class AvatarSearchType(val wireValue: Int) { ID(3) }

/**
 * 按用户单独懒加载头像资料，优先使用当前账号的 Room 缓存。
 * @param api 当前授权复用的 API 客户端。
 * @param store 统一 Room 存储入口。
 * @param owner 服务器、账号归属。
 * @param validate 校验授权及可选房间，第二参数表示是否访问远端；无返回值，失效时抛异常。
 * @param onUnauthorized 服务端拒绝认证时撤销本次授权，无返回值。
 */
internal class AvatarRepository(
    private val api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
    private val validate: (String?, Boolean) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    private val profiles = ConcurrentHashMap<String, AvatarProfile>()
// 对齐账号私有的 contact_name_cache，不能跨房间合并名称。
    private val messageNames = ConcurrentHashMap<Pair<String, String?>, String>()
    private val messageNameUpdatedAt = ConcurrentHashMap<Pair<String, String?>, Long>()
    private val locks = Array(32) { Mutex() }
    private val limit = Semaphore(4)

    /** Memory-only lookup for the first frame; never bypass the current room/authorization check. */
    fun peek(uid: String, roomId: String? = null): AvatarProfile? {
        validate(roomId, false)
        return profiles[uid]
    }

    /**
     * 在头像进入组合树时查询缓存或公开资料，同一 UID 跨页面和房间复用成功结果。
     * @param uid 真实用户 ID，群聊使用每条消息的发送者 ID。
     * @param roomId 可选的真实房间权限上下文，不参与公开头像缓存的主键。
     * @return 公开昵称和头像地址，普通失败为 null；成功落库，不修改会话或通讯录。
     * 缓存按服务器和账号隔离；取消传播，鉴权拒绝通知应用撤销授权。
     */
    suspend fun load(uid: String, roomId: String? = null): AvatarProfile? =
        loadProfile(uid, roomId, requireMessageName = false)

    /** 按当前阅读者和房间解析消息名称，好友备注优先于群昵称和公开用户名。 */
    suspend fun loadMessageName(uid: String, roomId: String): String? {
        loadProfile(uid, roomId, requireMessageName = true) ?: return null
        return messageNames[uid to roomId]
    }

    private suspend fun loadProfile(uid: String, roomId: String?, requireMessageName: Boolean): AvatarProfile? {
        if (!Regex("[1-9][0-9]{0,18}").matches(uid) || uid.toLongOrNull() == null) return null
        val nameKey = uid to roomId
        fun hasFreshName(): Boolean {
            val time = messageNameUpdatedAt[nameKey] ?: return false
            val now = System.currentTimeMillis()
            return now >= time && now - time < 60_000 && messageNames.containsKey(nameKey)
        }
        try {
            validate(roomId, false)
            if (!requireMessageName || hasFreshName()) profiles[uid]?.let { return it }
            // 同资源使用条带锁，后到的消息头像优先复用前一次已落库结果。
            return locks[(uid.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                validate(roomId, false)
                if (!requireMessageName || hasFreshName()) {
                    profiles[uid]?.let { return@withLock it }
                }
                if (!requireMessageName) {
                    val local = store.loadAvatarProfile(owner, uid)
                    validate(roomId, false)
                    if (local != null) {
                        profiles[uid] = local
                        return@withLock local
                    }
                } else if (roomId != null) {
                    val localName = store.loadMessageName(owner, uid, roomId)
                    val local = store.loadAvatarProfile(owner, uid)
                    validate(roomId, false)
                    if (localName != null && local != null) {
                        profiles[uid] = local
                        messageNames[nameKey] = localName
                        messageNameUpdatedAt[nameKey] = System.currentTimeMillis()
                        return@withLock local
                    }
                }
                // 公开资料并发受限，不让大量可见消息同时占用业务连接。
                limit.withPermit {
                    validate(roomId, true)
                    val response = try {
                        api.postJson(
                            "/capi/user/target/info",
                            JSONObject().put("code", AvatarSearchType.ID.wireValue)
                                .put("searchText", uid).put("roomId", roomId ?: JSONObject.NULL).toString(),
                        )
                    } catch (exception: ApiHttpException) {
                        if (exception.response.statusCode in setOf(401, 403)) onUnauthorized()
                        throw IOException("头像资料获取失败", exception)
                    }
                    validate(roomId, true)
                    val root = JSONObject(response.body)
                    if (root.optInt("code") in setOf(401, 403)) {
                        onUnauthorized()
                        throw CancellationException("头像请求授权已失效")
                    }
                    if (root.opt("success") != true || root.opt("code") != 200) throw IOException("头像资料获取失败")
                    val data = root.getJSONObject("data")
                    val responseRoom = if (data.isNull("roomId")) null else data.opt("roomId")?.toString()
                    if (data.opt("uid")?.toString() != uid || responseRoom != roomId) {
                        throw IOException("头像资料所属用户或房间不匹配")
                    }
                    val address = data.optString("avatar", "").takeIf { value ->
                        value.length <= 2048 && value.none { it <= ' ' }
                    }?.toHttpUrlOrNull()?.takeIf {
                        it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
                    }?.toString().orEmpty()
                    val name = data.optString("name", "").trim()
                    if (name.length > 256 || '\u0000' in name) throw IOException("头像昵称无效")
                    val displayName = resolveMessageDisplayName(
                        if (data.isNull("remark")) null else data.optString("remark"),
                        if (data.isNull("groupNickname")) null else data.optString("groupNickname"),
                        name,
                    )
                    if (displayName.length > 256 || '\u0000' in displayName) throw IOException("消息昵称无效")
                    val profile = AvatarProfile(uid, name, address)
                    store.saveTargetProfile(
                        owner, profile,
                        if (data.isNull("personalitySignature")) null else data.optString("personalitySignature"),
                        roomId,
                        if (data.isNull("remark")) null else data.optString("remark"),
                        if (data.isNull("groupNickname")) null else data.optString("groupNickname"),
                    )
                    validate(roomId, false)
                    profiles[uid] = profile
                    messageNames[nameKey] = displayName
                    messageNameUpdatedAt[nameKey] = System.currentTimeMillis()
                    profile
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return null
        } catch (_: org.json.JSONException) {
            return null
        }
    }
}
