package com.im.openchat.chat.rtc

import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException

internal class RtcRepository(
    val ownUid: String,
    private val validate: () -> Unit,
    private val validateRoom: suspend (String, String) -> Unit,
    private val onUnauthorized: () -> Unit,
    private val matchesDevice: ((String, String) -> Boolean)? = null,
    private val incomingGroupName: (suspend (RtcCall) -> String?)? = null,
    private val validateGroup: (suspend (String, List<String>) -> Unit)? = null,
    private val rtcServerUrl: String? = null,
    private val transport: suspend (String, String, String?) -> String,
) {
    /** 绑定原令牌及登录时获取的媒体地址，后续请求仍按当前通话授权校验。 */
    constructor(
        api: ApiClient,
        accessToken: String,
        ownUid: String,
        validate: () -> Unit,
        validateRoom: suspend (String, String) -> Unit,
        onUnauthorized: () -> Unit,
        rtcServerUrl: String,
        incomingGroupName: (suspend (RtcCall) -> String?)? = null,
        validateGroup: (suspend (String, List<String>) -> Unit)? = null,
    ) : this(ownUid, validate, validateRoom, onUnauthorized, api::matchesDevice, incomingGroupName, validateGroup, rtcServerUrl, { method, path, body ->
        api.rtcRequest(method, path, body, accessToken).body
    })

    /** 未提供设备比较器时保留只读复核，不推断接听所有权。 */
    fun answeredElsewhere(device: RtcAnsweredDevice): Boolean =
        matchesDevice?.invoke(device.terminal, device.deviceId) == false

    internal fun isAuthorized(): Boolean = try {
        validate()
        true
    } catch (_: Exception) {
        false
    }

    suspend fun verify(room: String, peer: String) {
        validate()
        try {
            validateRoom(room, peer)
        } catch (exception: InvalidSessionException) {
            validate()
            onUnauthorized()
            throw exception
        }
        validate()
    }

    suspend fun verify(request: RtcTarget) {
        if (request.groupName == null) return verify(request.roomId, request.peerUid)
        validate()
        if (request.inviteeUids.isEmpty() || ownUid in request.inviteeUids)
            throw IOException("请选择有效的群成员")
        try {
            (validateGroup ?: throw IOException("群通话校验不可用"))(request.roomId, request.inviteeUids)
        } catch (exception: InvalidSessionException) {
            validate()
            onUnauthorized()
            throw exception
        }
        validate()
    }

    /** 单聊及显式群邀请共用创建接口，固定请求 ID 供不明响应重试。 */
    suspend fun create(
        room: String, peer: String, requestId: String,
        mediaMode: RtcMediaMode = RtcMediaMode.VIDEO,
        replaceCallIds: List<String> = emptyList(),
        groupName: String? = null,
        inviteeUids: List<String> = emptyList(),
    ): RtcCall {
        if (groupName != null && (groupName.isBlank() || inviteeUids.isEmpty() || ownUid in inviteeUids))
            throw IOException("请选择有效的群成员")
        val body = request("POST", "/capi/rtc/calls",
            RtcProtocol.create(room, requestId, mediaMode, replaceCallIds,
                inviteeUids.takeIf { groupName != null }))
        return (if (groupName != null) {
            RtcProtocol.outgoingGroup(body, room, ownUid, inviteeUids, groupName)
        } else RtcProtocol.call(body, room, ownUid, peer)).also {
            if (groupName == null && it.mediaMode != mediaMode) throw IOException("创建结果的通话媒体类型不匹配")
        }
    }

    suspend fun get(call: RtcCall): RtcCall =
        parse(request("GET", RtcProtocol.path(call.callId)), call)

    /** 登录验证后只读查询本人未结束的单聊，不自动接听或申请媒体权限。 */
    suspend fun ongoing(): List<RtcOngoingCall> =
        RtcProtocol.ongoing(request("GET", "/capi/rtc/calls/ongoing"), ownUid)

    /** 只读查询当前账号所有未挂断记录，不能用单聊恢复列表代替。 */
    suspend fun activeCallIds(): List<String> =
        RtcProtocol.activeCallIds(request("GET", "/capi/rtc/calls/active"), ownUid)

    /** 分别查询群房间活动通话与本人冲突记录，未受邀或退出后也能发现同群通话。 */
    suspend fun groupDial(roomId: String, groupName: String): RtcGroupDialCheck {
        require(roomId.toLongOrNull()?.let { it > 0 } == true)
        val call = RtcProtocol.activeGroup(
            request("GET", "/capi/rtc/rooms/$roomId/active"), roomId, groupName)
        val existing = call?.let { verifyExistingGroup(it) }
        return RtcGroupDialCheck(activeCallIds(), existing)
    }

    /** 加入旧通话只核对真实群及本人权限，不把本次勾选当成原邀请名单。 */
    suspend fun verifyExistingGroup(call: RtcCall): RtcCall {
        if (!call.group) throw IOException("当前通话不是群通话")
        val name = resolveGroupName(call)?.takeIf { it.isNotBlank() }
            ?: throw IOException("当前房间不是可加入的群通话")
        return call.copy(groupName = name)
    }

    /** 用户确认后恢复原呼叫；服务端负责单聊权限及不可逆的 SFU 切换。 */
    suspend fun reconnect(call: RtcCall): RtcCall =
        parse(request("POST", "${RtcProtocol.path(call.callId)}/reconnect"), call).also {
            if (!it.accepted || it.deviceOwnership != RtcDeviceOwnership.CURRENT_DEVICE)
                throw IOException("通话无法在当前设备恢复")
        }

    /** 从受邀名单校验本人，再通过真实房间入口区分群聊与单聊并读取群名称。 */
    suspend fun incoming(invitation: RtcInvitation): RtcCall {
        val call = RtcProtocol.incoming(request("GET", RtcProtocol.path(invitation.callId)), invitation, ownUid)
        val groupName = resolveGroupName(call)
        if (call.group != (groupName != null)) throw IOException("通话与房间类型不匹配")
        if (groupName == null) {
            if (call.calleeUid != ownUid || call.participants.size != 2)
                throw IOException("单聊邀请成员不匹配")
            if (incomingGroupName == null) verify(call.roomId, call.callerUid)
        }
        return call.copy(groupName = groupName)
    }

    private suspend fun resolveGroupName(call: RtcCall): String? =
        try {
            validate()
            incomingGroupName?.invoke(call).also { validate() }
        } catch (exception: InvalidSessionException) {
            validate()
            onUnauthorized()
            throw exception
        }

    /** 提交既有呼叫操作；群成员离开不结束其他成员通话。 */
    suspend fun action(call: RtcCall, action: String, requestId: String): RtcCall {
        require(action in setOf("accept", "reject", "cancel", "hangup", "expire", "leave"))
        return parse(request("POST", "${RtcProtocol.path(call.callId)}/$action",
            RtcProtocol.action(requestId, call.actionVersion(ownUid))), call)
    }

    /** 服务端确认 SFU 后申请凭据，连接地址使用当前授权在登录时取得的缓存。 */
    suspend fun credential(call: RtcCall): RtcCredential =
        RtcProtocol.credential(request("POST", "${RtcProtocol.path(call.callId)}/token")).also {
            if (it.roomName != "openchat-call-${call.callId}") throw IOException("媒体房间不属于当前呼叫")
            if (!call.group && call.mediaMode == RtcMediaMode.VIDEO && !it.videoAllowed) {
                throw IOException("服务端未授予视频发布权限")
            }
        }.let { credential ->
            // 登录配置不可用时，仍可使用本次通话凭据中的已校验地址。
            credential.copy(serverUrl = rtcServerUrl?.takeIf { it.isNotBlank() } ?: credential.serverUrl)
        }

    /** 复用共享席位接口，领取、续租和释放只作用于当前用户。 */
    suspend fun videoEnabled(call: RtcCall, enabled: Boolean): Boolean =
        RtcProtocol.videoEnabled(request("POST", "${RtcProtocol.path(call.callId)}/video",
            RtcProtocol.videoAction(enabled)))

    /** 读取当前呼叫的 P2P/SFU 媒体协商快照。 */
    suspend fun media(call: RtcCall): RtcMediaView =
        RtcProtocol.media(request("GET", "${RtcProtocol.path(call.callId)}/media"), call)

    /** 上报当前授权用户的摄像头实际状态，冲突及网络错误交给通话同步入口处理。 */
    suspend fun camera(call: RtcCall, enabled: Boolean, expectedVersion: Long): RtcMediaView =
        RtcProtocol.media(request("POST", "${RtcProtocol.path(call.callId)}/media",
            RtcProtocol.cameraSignal(enabled, expectedVersion)), call)

    /** 提交一次幂等的 P2P Offer、Answer 或 SFU 回退。 */
    suspend fun signalMedia(
        call: RtcCall, kind: String, requestId: String,
        offerId: String? = null, sdp: String? = null,
    ): RtcMediaView = RtcProtocol.media(
        request(
            "POST", "${RtcProtocol.path(call.callId)}/media",
            RtcProtocol.mediaSignal(kind, requestId, offerId, sdp),
        ),
        call,
    )

    /** 窗口外补偿先复核设备归属，接听尝试不等于已取得归属，不能误挂断其他设备。 */
    suspend fun finishDetached(call: RtcCall, allowAccepted: Boolean = true, onlyAccepted: Boolean = false) {
        var latest = call
        repeat(if (call.group) 1 else 2) {
            latest = parse(transport("GET", RtcProtocol.path(call.callId), null), call)
            if (latest.finishedFor(ownUid)) return
            if (call.group) {
                val expected = when {
                    call.acceptedBy(ownUid) -> call.actionVersion(ownUid)
                    ownUid !in call.participants -> 1L
                    latest.acceptedBy(ownUid) -> call.actionVersion(ownUid) + 1
                    else -> call.actionVersion(ownUid)
                }
                // 迟到窗口补偿只结束原参与，不能把旧退出应用到同设备后一次重入。
                if (latest.actionVersion(ownUid) != expected) return
            }
            if (onlyAccepted && !latest.acceptedBy(ownUid)) return
            if (latest.deviceOwnership == RtcDeviceOwnership.OTHER_DEVICE ||
                latest.deviceOwnership == RtcDeviceOwnership.UNKNOWN) return
            if (!allowAccepted && latest.acceptedBy(ownUid)) return
            if (latest.acceptedBy(ownUid) && latest.deviceOwnership != RtcDeviceOwnership.CURRENT_DEVICE) return
            val action = if (allowAccepted && !latest.group) "hangup" else RtcProtocol.endAction(latest, ownUid)
            try {
                latest = parse(transport("POST", "${RtcProtocol.path(call.callId)}/$action",
                    RtcProtocol.action(rtcRequestId(), latest.actionVersion(ownUid))), latest)
                if (latest.finishedFor(ownUid)) return
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: RtcBusinessException) {
                if (exception.errCode in setOf(46001, 46007, 46008)) return
                throw exception
            } catch (_: IOException) {
                // 单聊保持既有有界补交；群操作不刷新成员版本再次退出。
            }
        }
    }

    /** 统一保持受邀名单与群房间上下文，拒绝其他账号或通话的响应。 */
    private fun parse(body: String, call: RtcCall): RtcCall = RtcProtocol.snapshot(body, call, ownUid)

    /** 保留业务错误码，只有明确认证失效才撤销当前授权。 */
    private suspend fun request(method: String, path: String, body: String? = null): String {
        validate()
        try {
            return transport(method, path, body).also {
                validate()
                // Parse the envelope here too, so business authentication failures revoke the session.
                RtcProtocol.assertSuccess(it)
            }
        } catch (exception: ApiHttpException) {
            validate()
            if (exception.response.statusCode in setOf(401, 403)) {
                onUnauthorized()
                throw InvalidSessionException("登录已过期，请重新登录", exception)
            }
            throw IOException("通话请求失败，请重试", exception)
        } catch (exception: InvalidSessionException) {
            validate()
            onUnauthorized()
            throw exception
        }
    }
}
