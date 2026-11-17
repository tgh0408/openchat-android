package com.im.openchat.chat.rtc

import android.util.Log
import com.im.openchat.common.network.ApiHttpException
import io.livekit.android.events.DisconnectReason
import io.livekit.android.room.Room
import java.util.Collections
import java.util.IdentityHashMap

internal enum class RtcLogStage {
    VERIFY_ROOM, CREATE_CALL, QUERY_CALL, ACCEPT_CALL, END_CALL, EXPIRE_CALL,
    WAIT_MEDIA_CLEANUP, INIT_SDK, CREATE_ROOM, CREATE_VIDEO, CREATE_AUDIO, START_CAMERA,
    GET_CREDENTIAL, RELEASE_VIDEO_SEAT, CONNECT_SFU, VERIFY_IDENTITY,
    PUBLISH_VIDEO, PUBLISH_AUDIO, WAIT_SFU_CONNECTED, REFRESH_MEMBERS, READY,
    SYNC_VIDEO_SEAT, SYNC_CAMERA, RENEW_VIDEO_SEAT, CAMERA_SERVICE,
    MEDIA_PERMISSION, CAMERA_PERMISSION,
}

/** Only validated identifiers, enums and scalar state enter Logcat; SDK messages may contain credentials. */
internal object RtcLogger {
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")

    fun record(
        event: RtcAuditEvent,
        stage: RtcLogStage,
        call: RtcCall? = null,
        target: RtcTarget? = null,
        ownUid: String? = null,
        attempt: Long = 0,
        elapsedMs: Long = 0,
        camera: Boolean = false,
        microphone: Boolean = false,
        granted: Boolean? = null,
        exception: Throwable? = null,
        disconnectReason: DisconnectReason? = null,
        roomState: Room.State? = null,
    ) {
        val priority = if (exception != null || event in setOf(
                RtcAuditEvent.RTC_MEDIA_DISCONNECTED, RtcAuditEvent.RTC_VIDEO_SEAT_DENIED,
                RtcAuditEvent.RTC_OPERATION_FAILED,
            )) Log.WARN else Log.INFO
        val context = buildString {
            append("stage=").append(stage.name)
            append(" callId=").append(safeId(call?.callId))
            append(" roomId=").append(safeId(call?.roomId ?: target?.roomId))
            append(" attempt=").append(attempt)
            append(" elapsedMs=").append(elapsedMs.coerceAtLeast(0))
            append(" group=").append(call?.group ?: (target?.groupName != null))
            append(" state=").append(call?.state?.name ?: "none")
            append(" roomState=").append(roomState?.name ?: "none")
            append(" version=").append(call?.version ?: 0)
            append(" ownership=").append(call?.deviceOwnership?.name ?: "none")
            append(" selfState=").append(ownUid?.let { call?.participants?.get(it)?.name } ?: "none")
            append(" participants=").append(call?.participants?.size ?: target?.inviteeUids?.size ?: 0)
            append(" camera=").append(camera)
            append(" microphone=").append(microphone)
            if (granted != null) append(" granted=").append(granted)
            if (disconnectReason != null) append(" disconnectReason=").append(disconnectReason.name)
        }
        Log.println(priority, "OpenChatRtc", "${event.name} $context")
        exception?.let {
            exceptionLines(it).forEach { line ->
                Log.println(priority, "OpenChatRtc", "${event.name} $context $line")
            }
        }
    }

    private fun safeId(value: String?): String =
        if (value == null) "none" else if (identifier.matches(value)) value else "invalid"

    /** Preserve cause/suppressed relationships and source locations without message/toString or payloads. */
    internal fun exceptionLines(root: Throwable): List<String> {
        val lines = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Pair<String, Throwable>>()
        pending.addLast("root" to root)
        while (pending.isNotEmpty() && seen.size < 6) {
            val (relation, error) = pending.removeFirst()
            if (!seen.add(error)) {
                lines += "$relation <cycle>"
                continue
            }
            val code = when (error) {
                is RtcBusinessException -> " code=${error.errCode}"
                is ApiHttpException -> " httpStatus=${error.response.statusCode}"
                else -> ""
            }
            lines += "$relation type=${error.javaClass.name}$code message=<omitted>"
            error.stackTrace.take(16).forEach { lines += "at $it" }
            if (error.stackTrace.size > 16) lines += "<stack truncated>"
            error.cause?.let { pending.addLast("$relation.cause" to it) }
            error.suppressed.take(2).forEachIndexed { index, suppressed ->
                pending.addLast("$relation.suppressed[$index]" to suppressed)
            }
            if (error.suppressed.size > 2) lines += "<suppressed truncated>"
        }
        if (pending.isNotEmpty()) lines += "<exception chain truncated>"
        return lines
    }
}
