package com.im.openchat.chat.rtc

import com.im.openchat.login.data.InvalidSessionException
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class RtcState(val wire: Int) {
    RINGING(0),
    ACCEPTED(1),
    CONNECTED(2),
    REJECTED(3),
    CANCELED(4),
    ENDED(5),
    EXPIRED(6);

    val terminal: Boolean
        get() = wire >= 3
}

internal enum class RtcMediaMode(val wire: Int) {
    AUDIO(0),
    VIDEO(1),
}

/** 服务端成员状态使用显式 value，不使用枚举序号。 */
internal enum class RtcParticipantState(val wire: Int) {
    INVITED(1), ACCEPTED(2), REJECTED(3), EXPIRED(4), LEFT(5);

    /** 同时接受服务端枚举名称及显式数字编码，无副作用。 */
    fun matches(value: String?): Boolean = value == name || value == wire.toString()
}

internal enum class RtcTransport {
    P2P,
    SFU,
}

/** 设备归属必须由当前认证请求的服务端快照确认。 */
internal enum class RtcDeviceOwnership { UNASSIGNED, CURRENT_DEVICE, OTHER_DEVICE, UNKNOWN }

/** 保留设备竞争业务码，不能把接听失败当成登录失效。 */
internal class RtcBusinessException(val errCode: Int?, message: String) : IOException(message)

/** 只供当前账号比较设备归属，不记录完整设备标识。 */
internal class RtcAnsweredDevice(val callId: String, val terminal: String, val deviceId: String)

internal data class RtcCall(
    val callId: String,
    val roomId: String,
    val callerUid: String,
    val calleeUid: String?,
    val state: RtcState,
    val version: Long,
    val deadline: Long,
    val acceptedAt: Long?,
    val endedByUid: String?,
    val mediaMode: RtcMediaMode,
    val deviceOwnership: RtcDeviceOwnership = RtcDeviceOwnership.UNKNOWN,
    val participants: Map<String, RtcParticipantState> = emptyMap(),
    val groupName: String? = null,
    val createdAt: Long = 0,
    val participantVersions: Map<String, Long> = emptyMap(),
) {
    val accepted: Boolean
        get() = state == RtcState.ACCEPTED || state == RtcState.CONNECTED

    /** 空被叫表示群通话，房间权限仍由服务端和真实群资料校验。 */
    val group: Boolean get() = calleeUid == null

    /** 群写操作只携带本人成员版本，首次主动加入使用初始版本 1。 */
    fun actionVersion(uid: String): Long = if (group) {
        if (uid in participants) requireNotNull(participantVersions[uid]) else 1L
    } else version

    /** 仅群聊按本人的参与状态判断接听，其他成员接听不代替本人同意。 */
    fun acceptedBy(uid: String): Boolean =
        accepted && (!group || participants[uid] == RtcParticipantState.ACCEPTED)

    /** 本人邀请仍有效时才允许展示和接听；群通话可能已被其他成员接通。 */
    fun invited(uid: String): Boolean = !state.terminal &&
        participants[uid] == RtcParticipantState.INVITED &&
        (group || state == RtcState.RINGING)

    /** 群成员拒绝、超时或离开均结束本机参与，不要求整场通话结束。 */
    fun finishedFor(uid: String): Boolean = state.terminal || group &&
        participants[uid] in setOf(RtcParticipantState.REJECTED, RtcParticipantState.EXPIRED, RtcParticipantState.LEFT)
}

/** 登录后待用户确认的单聊，不保存媒体凭证。 */
internal data class RtcOngoingCall(val call: RtcCall, val peerName: String)

internal data class RtcGroupDialCheck(val activeCallIds: List<String>, val existing: RtcCall?)

@Serializable
private data class OngoingCallPayload(val call: CallPayload, val peerName: String)

internal data class RtcMediaView(
    val callId: String,
    val transport: RtcTransport,
    val version: Long,
    val offerId: String?,
    val offerSdp: String?,
    val answerSdp: String?,
    val stunUrls: List<String>,
    val cameraStates: Map<String, RtcCameraState>,
)

/** 当前成员实际摄像头状态；版本独立于通话和媒体协商。 */
@Serializable
internal data class RtcCameraState(
    val enabled: Boolean,
    @Serializable(with = WireLongSerializer::class)
    val version: Long,
)

/** 与服务端 RtcEventType 使用相同的字符串值。 */
private enum class RtcCameraEventType { RTC_CAMERA_UPDATED }

// Credentials deliberately have no generated toString and never enter saved state.
internal class RtcCredential(
    val serverUrl: String,
    val roomName: String,
    val token: String,
    val videoAllowed: Boolean,
)

internal data class RtcInvitation(
    val callId: String,
    val roomId: String,
    val callerUid: String,
)

@Serializable
private data class CreateCall(
    val roomId: String,
    val mediaMode: Int,
    val clientRequestId: String,
    val replaceCallIds: List<String> = emptyList(),
    val participantUids: List<String>? = null,
)

@Serializable
private data class CallAction(
    val clientRequestId: String,
    val expectedVersion: Long,
)

@Serializable
private data class MediaSignal(
    val kind: String,
    val clientRequestId: String,
    val offerId: String? = null,
    val sdp: String? = null,
)

/** 通过原媒体接口提交本人状态，不允许调用方指定操作者。 */
@Serializable
private data class CameraSignal(
    val kind: String,
    val clientRequestId: String,
    val enabled: Boolean,
    val expectedCameraVersion: Long,
)

/** 摄像头席位由当前认证用户领取或释放。 */
@Serializable
private data class VideoAction(val enabled: Boolean)

@Serializable
private data class RtcResponse<T>(
    @Serializable(with = WireStringSerializer::class)
    val code: String? = null,
    val success: Boolean? = null,
    val data: T? = null,
    @Serializable(with = WireStringSerializer::class)
    val errCode: String? = null,
    val errMsg: String? = null,
)

@Serializable
private data class CallParticipant(
    @Serializable(with = WireStringSerializer::class)
    val uid: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val state: String? = null,
    @Serializable(with = WireLongSerializer::class)
    val version: Long? = null,
)

@Serializable
private data class CallPayload(
    @Serializable(with = WireStringSerializer::class)
    val callId: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val roomId: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val callerUid: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val calleeUid: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val mediaMode: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val state: String? = null,
    @Serializable(with = WireLongSerializer::class)
    val version: Long? = null,
    val participants: List<CallParticipant>? = null,
    @Serializable(with = WireStringSerializer::class)
    val createdAt: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val expiresAt: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val acceptedAt: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val endedByUid: String? = null,
    val deviceOwnership: String? = null,
)

@Serializable
private data class AnsweredDevicePayload(val acceptedTerminal: String, val acceptedDeviceId: String)

@Serializable
private data class CredentialPayload(
    @Serializable(with = WireStringSerializer::class)
    val serverUrl: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val roomName: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val token: String? = null,
    val videoAllowed: Boolean? = null,
    @Serializable(with = WireStringSerializer::class)
    val expiresAt: String? = null,
)

@Serializable
private data class MediaPayload(
    @Serializable(with = WireStringSerializer::class)
    val callId: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val transport: String? = null,
    @Serializable(with = WireLongSerializer::class)
    val version: Long? = null,
    @Serializable(with = WireStringSerializer::class)
    val offerId: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val offerSdp: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val answerSdp: String? = null,
    @Serializable(with = WireStringListSerializer::class)
    val stunUrls: List<String>? = null,
    val cameraStates: Map<String, RtcCameraState>? = null,
)

@Serializable
private data class RtcEvent(
    @Serializable(with = WireStringSerializer::class)
    val eventType: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val toUid: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val fromUid: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val callId: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val roomId: String? = null,
    @Serializable(with = WireStringSerializer::class)
    val signalSeq: String? = null,
    val payload: JsonElement? = null,
)

/**
 * The PC protocol has historically emitted scalar values as either JSON strings or numbers.
 * Compatibility is kept inside serializers so domain code only sees typed DTOs.
 */
private object WireStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RtcWireString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected a scalar string")
        return primitive.content
    }
}

private object WireLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RtcWireLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected a scalar long")
        return primitive.content.toLongOrNull()
            ?: throw SerializationException("Expected a long")
    }
}

private object WireStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(WireStringSerializer)

    override val descriptor: SerialDescriptor
        get() = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        return delegate.deserialize(decoder)
    }
}

internal object RtcProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")
    private val uid = Regex("[1-9][0-9]{0,18}")
    private val sdpPrefix = "v=0\r\n"
    private val stunUrl = Regex("""^stuns?:[^\s/@?#]{1,240}$""")

    fun create(
        room: String,
        requestId: String,
        mediaMode: RtcMediaMode = RtcMediaMode.VIDEO,
        replaceCallIds: List<String> = emptyList(),
        participantUids: List<String>? = null,
    ): String {
        check(validUid(room) && identifier.matches(requestId))
        check(replaceCallIds.size <= 100 && replaceCallIds.distinct().size == replaceCallIds.size)
        check(replaceCallIds.all { identifier.matches(it) })
        if (participantUids != null) {
            check(participantUids.isNotEmpty() && participantUids.distinct().size == participantUids.size)
            check(participantUids.all(::validUid))
        }
        return json.encodeToString(
            CreateCall(
                roomId = room,
                mediaMode = mediaMode.wire,
                clientRequestId = requestId,
                replaceCallIds = replaceCallIds,
                participantUids = participantUids,
            ),
        )
    }

    fun action(requestId: String, version: Long): String {
        check(identifier.matches(requestId) && version > 0)
        return json.encodeToString(CallAction(requestId, version))
    }

    fun mediaSignal(
        kind: String,
        requestId: String,
        offerId: String? = null,
        sdp: String? = null,
    ): String {
        check(kind in setOf("OFFER", "ANSWER", "FALLBACK") && identifier.matches(requestId))
        check(offerId == null || identifier.matches(offerId))
        check(sdp == null || validSdp(sdp))
        check(
            (kind == "FALLBACK" && offerId == null && sdp == null) ||
                (kind == "OFFER" && offerId == null && sdp != null) ||
                (kind == "ANSWER" && offerId != null && sdp != null),
        )
        return json.encodeToString(MediaSignal(kind, requestId, offerId, sdp))
    }

    fun path(callId: String): String {
        check(identifier.matches(callId))
        return "/capi/rtc/calls/$callId"
    }

    /** 解析绑定原房间与双方身份的呼叫快照。 */
    fun call(
        body: String,
        room: String,
        callerUid: String,
        calleeUid: String,
    ): RtcCall {
        return readCall(response(body, CallPayload.serializer()), room, callerUid, calleeUid)
    }

    /** 群发起可能加入已有通话，保留服务端原名单并确认本人已取得设备归属。 */
    fun outgoingGroup(body: String, room: String, ownUid: String, invitees: List<String>, name: String): RtcCall {
        check(name.isNotBlank() && invitees.isNotEmpty() && ownUid !in invitees)
        check(invitees.distinct().size == invitees.size && invitees.all(::validUid))
        val data = response(body, CallPayload.serializer())
        val call = readCall(data, room, data.callerUid ?: invalid(), data.calleeUid, allowGroup = true)
        check(call.group)
        check(!call.state.terminal && call.participants[ownUid] == RtcParticipantState.ACCEPTED &&
            call.deviceOwnership == RtcDeviceOwnership.CURRENT_DEVICE)
        return call.copy(groupName = name)
    }

    /** 来电允许实际受邀名单中的任一被叫，后续再核对真实房间类型。 */
    fun incoming(body: String, invitation: RtcInvitation, ownUid: String): RtcCall {
        val data = response(body, CallPayload.serializer())
        val call = readCall(data, invitation.roomId, invitation.callerUid,
            data.calleeUid, allowGroup = true)
        check(call.callId == invitation.callId && ownUid != call.callerUid && ownUid in call.participants)
        return call
    }

    /** 固定通话身份和媒体类型，群名单允许追加主动加入者，单聊仍严格保持双方。 */
    fun snapshot(body: String, previous: RtcCall, ownUid: String): RtcCall {
        val call = readCall(response(body, CallPayload.serializer()), previous.roomId,
            previous.callerUid, previous.calleeUid, allowGroup = true)
        check(call.callId == previous.callId && call.mediaMode == previous.mediaMode)
        if (previous.group) {
            check(call.participants.keys.containsAll(previous.participants.keys))
        } else {
            check(ownUid in call.participants && call.participants.keys == previous.participants.keys)
        }
        return call.copy(groupName = previous.groupName)
    }

    /** 复用统一快照校验，恢复列表不额外建立另一套呼叫协议。 */
    private fun readCall(
        data: CallPayload, room: String, callerUid: String, calleeUid: String?,
        allowGroup: Boolean = false,
    ): RtcCall {
        val id = data.callId ?: invalid()
        check(identifier.matches(id))
        check(data.roomId == room && validUid(room))
        check(data.callerUid == callerUid && data.calleeUid == calleeUid)
        check(validUid(callerUid))
        if (calleeUid == null) check(allowGroup)
        else check(validUid(calleeUid) && callerUid != calleeUid)
        val mediaMode = when (data.mediaMode) {
            "AUDIO", RtcMediaMode.AUDIO.wire.toString() -> RtcMediaMode.AUDIO
            "VIDEO", RtcMediaMode.VIDEO.wire.toString() -> RtcMediaMode.VIDEO
            else -> invalid()
        }
        val state = RtcState.entries.firstOrNull {
            data.state == it.name || data.state == it.wire.toString()
        } ?: invalid()
        val version = data.version?.takeIf { it > 0 } ?: invalid()
        val entries = data.participants ?: invalid()
        val members = entries.map { it.uid ?: invalid() }
        check(members.isNotEmpty() && members.distinct().size == members.size && members.all(::validUid))
        check(callerUid in members)
        if (calleeUid != null) check(members.size == 2 && members.toSet() == setOf(callerUid, calleeUid))
        // 群版本来自历史成员行，缺失或非法值拒绝，不能用整通版本代替。
        val memberVersions = if (calleeUid == null) entries.associate { member ->
            requireNotNull(member.uid) to (member.version?.takeIf { it > 0 } ?: invalid())
        } else emptyMap()
        val participants = entries.associate { member ->
            requireNotNull(member.uid) to (RtcParticipantState.entries.firstOrNull {
                it.matches(member.state)
            } ?: invalid())
        }
        val created = timestamp(data.createdAt ?: invalid())
        val deadline = data.expiresAt?.let(::timestamp) ?: (created + 60_000)
        val endedBy = data.endedByUid
        check(endedBy == null || endedBy in members)
        return RtcCall(
            id,
            room,
            callerUid,
            calleeUid,
            state,
            version,
            deadline,
            data.acceptedAt?.let(::timestamp),
            endedBy,
            mediaMode,
            when (data.deviceOwnership) {
                null -> RtcDeviceOwnership.UNKNOWN
                else -> RtcDeviceOwnership.entries.firstOrNull { it.name == data.deviceOwnership } ?: invalid()
            },
            participants,
            createdAt = created,
            participantVersions = memberVersions,
        )
    }

    /** 服务端按真实单聊类型筛选，客户端继续校验本人身份、接听状态和唯一呼叫。 */
    fun ongoing(body: String, ownUid: String): List<RtcOngoingCall> {
        check(validUid(ownUid))
        val ids = mutableSetOf<String>()
        return response(body, ListSerializer(OngoingCallPayload.serializer())).map { item ->
            val data = item.call
            val call = readCall(data, data.roomId ?: invalid(),
                data.callerUid ?: invalid(), data.calleeUid ?: invalid())
            check(call.accepted && ownUid in setOf(call.callerUid, call.calleeUid))
            check(data.participants?.any { it.uid == ownUid && it.state in setOf("2", "ACCEPTED") } == true)
            check(item.peerName.isNotBlank() && ids.add(call.callId))
            RtcOngoingCall(call, item.peerName)
        }
    }

    /** 呼出检查包含跨房间、群聊和其他设备记录；仅提取本次可确认替换的 ID。 */
    fun activeCallIds(body: String, ownUid: String): List<String> {
        check(validUid(ownUid))
        val calls = response(body, ListSerializer(CallPayload.serializer()))
        check(calls.size <= 100)
        val ids = mutableSetOf<String>()
        return calls.map { data ->
            val id = data.callId ?: invalid()
            check(identifier.matches(id) && ids.add(id))
            check(validUid(data.roomId ?: invalid()) && validUid(data.callerUid ?: invalid()))
            val state = RtcState.entries.firstOrNull {
                data.state == it.name || data.state == it.wire.toString()
            } ?: invalid()
            check(!state.terminal)
            val members = data.participants ?: invalid()
            check(members.map { it.uid }.distinct().size == members.size)
            check(members.all { validUid(it.uid ?: invalid()) })
            check(members.any {
                it.uid == ownUid && (RtcParticipantState.INVITED.matches(it.state) ||
                    RtcParticipantState.ACCEPTED.matches(it.state))
            })
            id
        }
    }

    /** 仅从本人有效参与记录中选择同房间通话，创建请求的勾选名单不覆盖原名单。 */
    fun groupDial(body: String, ownUid: String, roomId: String, groupName: String): RtcGroupDialCheck {
        check(validUid(roomId) && groupName.isNotBlank())
        val ids = activeCallIds(body, ownUid)
        val matches = response(body, ListSerializer(CallPayload.serializer())).filter { it.roomId == roomId }
        check(matches.size <= 1)
        val existing = matches.singleOrNull()?.let { data ->
            readCall(data, roomId, data.callerUid ?: invalid(), data.calleeUid, allowGroup = true)
                .also { check(it.group) }
                .copy(groupName = groupName)
        }
        return RtcGroupDialCheck(ids, existing)
    }

    /** 按群房间解析活动通话，明确 data:null 表示没有通话，不过滤本人的历史参与状态。 */
    fun activeGroup(body: String, roomId: String, groupName: String): RtcCall? {
        check(validUid(roomId) && groupName.isNotBlank())
        assertSuccess(body)
        val root = json.parseToJsonElement(body) as? JsonObject ?: invalid()
        val data = root["data"] ?: invalid()
        if (data == JsonNull) return null
        val payload = json.decodeFromJsonElement(CallPayload.serializer(), data)
        val call = readCall(payload, roomId, payload.callerUid ?: invalid(),
            payload.calleeUid, allowGroup = true)
        check(call.group && !call.state.terminal)
        return call.copy(groupName = groupName)
    }

    fun invitation(body: String, ownUid: String): RtcInvitation? = try {
        val event = json.decodeFromString<RtcEvent>(body)
        val callId = event.callId.orEmpty()
        val room = event.roomId.orEmpty()
        val caller = event.fromUid.orEmpty()
        if (event.eventType == "RTC_CALL_CREATED" && event.toUid == ownUid &&
            validUid(ownUid) && validUid(room) && validUid(caller) && caller != ownUid &&
            identifier.matches(callId)
        ) {
            RtcInvitation(callId, room, caller)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** 接听通知仅适用于本人，严格验证设备字段，重复通知可安全地再次收起本机界面。 */
    fun answeredDevice(body: String, ownUid: String): RtcAnsweredDevice? = try {
        val event = json.decodeFromString<RtcEvent>(body)
        if (event.eventType != "RTC_CALL_ANSWERED_ELSEWHERE" ||
            event.toUid != ownUid || event.fromUid != ownUid ||
            !identifier.matches(event.callId.orEmpty()) || event.payload == null) null
        else {
            val device = json.decodeFromJsonElement(AnsweredDevicePayload.serializer(), event.payload)
            if (!Regex("[A-Z]{2,16}").matches(device.acceptedTerminal) ||
                !Regex("[a-f0-9]{64}").matches(device.acceptedDeviceId)) null
            else RtcAnsweredDevice(requireNotNull(event.callId), device.acceptedTerminal, device.acceptedDeviceId)
        }
    } catch (_: Exception) {
        null
    }

    /** 按本人角色和参与状态选择既有结束接口，不把群成员离开变成结束整场。 */
    fun endAction(call: RtcCall, ownUid: String): String {
        if (call.group) {
            check(ownUid in call.participants)
            return when {
                ownUid == call.callerUid -> if (call.state == RtcState.RINGING) "cancel" else "hangup"
                call.participants[ownUid] == RtcParticipantState.INVITED -> "reject"
                else -> "leave"
            }
        }
        check(ownUid == call.callerUid || ownUid == call.calleeUid)
        return if (call.state != RtcState.RINGING) {
            "hangup"
        } else if (ownUid == call.callerUid) {
            "cancel"
        } else {
            "reject"
        }
    }

    fun credential(body: String): RtcCredential {
        val data = response(body, CredentialPayload.serializer())
        val server = data.serverUrl ?: invalid()
        check(server.startsWith("wss://") || server.startsWith("ws://"))
        val url = server.replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .toHttpUrlOrNull() ?: invalid()
        check(url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && url.query == null)
        check(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1"))
        val name = data.roomName?.takeIf { it.isNotBlank() && it.length <= 256 } ?: invalid()
        val token = data.token?.takeIf { it.isNotBlank() && it.length <= 16_384 } ?: invalid()
        val videoAllowed = data.videoAllowed ?: invalid()
        check(timestamp(data.expiresAt ?: invalid()) > System.currentTimeMillis())
        return RtcCredential(server, name, token, videoAllowed)
    }

    /** 群摄像头席位接口只接受真实布尔结果，不把席位不足改判成功。 */
    fun videoEnabled(body: String): Boolean = response(body, Boolean.serializer())

    /** 编码当前用户的摄像头席位选择，无额外身份字段。 */
    fun videoAction(enabled: Boolean): String = json.encodeToString(VideoAction(enabled))

    /** 解析当前呼叫媒体快照及成员摄像头状态，拒绝错号、未知成员和非法版本。 */
    fun media(body: String, call: RtcCall): RtcMediaView {
        val data = response(body, MediaPayload.serializer())
        val id = data.callId ?: invalid()
        check(id == call.callId && identifier.matches(id))
        val transport = when (data.transport) {
            "P2P" -> RtcTransport.P2P
            "SFU" -> RtcTransport.SFU
            else -> invalid()
        }
        val version = data.version?.takeIf { it > 0 } ?: invalid()
        val offerId = data.offerId
        val offerSdp = data.offerSdp
        val answerSdp = data.answerSdp
        check(offerId == null || identifier.matches(offerId))
        val stunUrls = data.stunUrls?.also { urls ->
            check(urls.size <= 4)
            urls.forEach { check(stunUrl.matches(it)) }
        } ?: invalid()
        check((offerId == null) == (offerSdp == null))
        check(answerSdp == null || offerSdp != null)
        if (transport == RtcTransport.SFU) {
            check(offerSdp == null && answerSdp == null)
        }
        if (offerSdp != null) check(validSdp(offerSdp))
        if (answerSdp != null) check(validSdp(answerSdp))
        val cameras = data.cameraStates ?: invalid()
        cameras.forEach { (uid, state) ->
            check(uid in if (call.group) call.participants.keys else setOf(call.callerUid, call.calleeUid))
            check(state.version in 1..9_007_199_254_740_991L)
        }
        return RtcMediaView(id, transport, version, offerId, offerSdp, answerSdp, stunUrls, cameras)
    }

    /** 编码实际摄像头开关和已观测版本，仅交由当前授权的媒体接口提交。 */
    fun cameraSignal(enabled: Boolean, expectedVersion: Long): String =
        json.encodeToString(CameraSignal.serializer(),
            CameraSignal(kind = "CAMERA", clientRequestId = rtcRequestId(),
                enabled = enabled, expectedCameraVersion = expectedVersion))

    /** 校验定向摄像头事件；未知成员、错号和畸形数据不能改变当前画面。 */
    fun cameraEvent(body: String, call: RtcCall, ownUid: String): Pair<String, RtcCameraState>? = try {
        val event = json.decodeFromString<RtcEvent>(body)
        val uid = event.fromUid
        if (call.mediaMode != RtcMediaMode.VIDEO ||
            event.eventType != RtcCameraEventType.RTC_CAMERA_UPDATED.name ||
            event.toUid != ownUid || event.callId != call.callId || event.roomId != call.roomId ||
            uid !in (if (call.group) call.participants.keys else setOf(call.callerUid, call.calleeUid)) ||
            event.payload == null
        ) null else {
            val state = json.decodeFromJsonElement(RtcCameraState.serializer(), event.payload)
            if (state.version !in 1..9_007_199_254_740_991L ||
                event.signalSeq != state.version.toString()) null
            else requireNotNull(uid) to state
        }
    } catch (_: Exception) {
        null
    }

    fun matchesEvent(body: String, call: RtcCall, ownUid: String): Boolean = try {
        val event = json.decodeFromString<RtcEvent>(body)
        event.toUid == ownUid && event.callId == call.callId &&
            event.roomId == call.roomId && event.eventType?.startsWith("RTC_") == true
    } catch (_: Exception) {
        false
    }

    /** 终态通知仅唤醒权威查询，旧通知不能直接删除待恢复快照。 */
    fun terminalEvent(body: String, call: RtcCall, ownUid: String): Boolean = try {
        val event = json.decodeFromString<RtcEvent>(body)
        event.toUid == ownUid && event.callId == call.callId && event.roomId == call.roomId &&
            event.eventType in setOf("RTC_CALL_ENDED", "RTC_CALL_REJECTED", "RTC_CALL_CANCELED", "RTC_CALL_EXPIRED")
    } catch (_: Exception) {
        false
    }

    /** 比较同一通话版本，群聊高版本可追加成员，同版本仍要求状态及名单完全一致。 */
    fun newer(previous: RtcCall?, next: RtcCall): Boolean = previous == null ||
        previous.callId == next.callId && previous.roomId == next.roomId &&
        previous.callerUid == next.callerUid && previous.calleeUid == next.calleeUid &&
        previous.mediaMode == next.mediaMode &&
        (if (previous.group) next.participants.keys.containsAll(previous.participants.keys)
            else previous.participants.keys == next.participants.keys) &&
        next.version >= previous.version &&
        (next.version != previous.version ||
            next.state == previous.state && next.participants == previous.participants) &&
        (!previous.state.terminal || next.state == previous.state)

    /** 在解析数据前保留业务失败原因，不能要求失败响应携带成功实体。 */
    fun assertSuccess(body: String) {
        val root = try {
            json.decodeFromString(RtcResponse.serializer(JsonElement.serializer()), body)
        } catch (_: SerializationException) {
            invalid()
        }
        if (root.code in setOf("401", "403"))
            throw InvalidSessionException("登录已过期，请重新登录")
        if (root.code != "200" || root.success != true)
            throw RtcBusinessException(root.errCode?.toIntOrNull(),
                root.errMsg?.takeIf { it.isNotBlank() } ?: "通话请求未成功，请重试")
    }

    /** 统一验证业务信封后解析实体。 */
    private fun <T> response(body: String, serializer: KSerializer<T>): T {
        assertSuccess(body)
        val root = try {
            json.decodeFromString(RtcResponse.serializer(serializer), body)
        } catch (_: SerializationException) {
            invalid()
        }
        if (root.code in setOf("401", "403")) {
            throw InvalidSessionException("登录已过期，请重新登录")
        }
        if (root.code != "200" || root.success != true) {
            throw IOException("通话请求未成功，请重试")
        }
        return root.data ?: invalid()
    }

    internal fun timestamp(value: String): Long {
        check(value.length <= 64)
        val match = Regex(
            """^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$""",
        ).matchEntire(value) ?: invalid()
        val normalized =
            "${match.groupValues[1]}.${match.groupValues[2].padEnd(3, '0').take(3)}${match.groupValues[3]}"
        val position = ParsePosition(0)
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(normalized, position)
        check(date != null && position.index == normalized.length)
        return requireNotNull(date).time
    }

    private fun validUid(value: String) = uid.matches(value) &&
        value.toLongOrNull()?.let { it > 0 } == true

    private fun validSdp(value: String): Boolean = value.length <= 60_000 &&
        value.startsWith(sdpPrefix) && "\r\nm=audio " in value && '\u0000' !in value

    private fun check(value: Boolean) {
        if (!value) invalid()
    }

    private fun invalid(): Nothing {
        throw IOException("通话数据无效或与当前会话不匹配")
    }
}
