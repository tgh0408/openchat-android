package com.im.openchat.chat.rtc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.VideoTrack
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.EglBase
import kotlin.time.Duration.Companion.milliseconds

internal class RtcTarget(
    val roomId: String, val peerUid: String, val name: String, val avatar: String,
    val mediaMode: RtcMediaMode = RtcMediaMode.VIDEO,
    val incoming: Boolean = false,
    val recovered: Boolean = false,
    val replaceCallIds: List<String> = emptyList(),
    val requestId: String = rtcRequestId(),
    val groupName: String? = null,
    val inviteeUids: List<String> = emptyList(),
    val memberNames: Map<String, String> = emptyMap(),
    val existingGroup: Boolean = false,
)

internal fun rtcRequestId(): String = UUID.randomUUID().toString().replace("-", "")

internal const val RTC_CLOSE_DELAY_MS = 1_500L

/** One call per authorization; no credentials, SDP or media are persisted. */
internal class RtcVideoController(
    private val context: Context,
    private val repository: RtcRepository,
    private val monotonicNow: () -> Long = { SystemClock.elapsedRealtime() },
    private val onTerminal: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var mediaJob: Job? = null
    private var mediaCleanup: Job? = null
    private var directMedia by mutableStateOf<RtcDirectMedia?>(null)
    private var videoEgl: EglBase? = null
    private var sfuConnecting = false
    private var sfuConnected = false
    private var mediaAttempt = 0L
    private var videoSeatHeld = false
    private var videoLeaseJob: Job? = null
    private var groupWaitingJob: Job? = null
    var groupWaitingExpired by mutableStateOf(false)
        private set
    var groupEnteredUids by mutableStateOf<Set<String>>(emptySet())
        private set
    private var eventJob: Job? = null
    private var endingDeadline: Job? = null
    private var disposed = false
    private var createAttempted = false
    private var retryRequested = false
    private var videoPublished = false
    private var audioPublished = false
    private var audio: LocalAudioTrack? = null
    private var stopRequest: Pair<Long, String>? = null
    private var expireRequest: Pair<Long, String>? = null
    private var timerStartedAt: Long? = null
    private var acceptedTimerStarted = false
    private var detachedCallId: String? = null
    private var foreground = false
    private var backgroundOwner: RtcTarget? = null
    private var discoveryJob: Job? = null
    private val invitations = linkedMapOf<String, RtcInvitation>()
    private val shownCalls = linkedSetOf<String>()
    private var acceptRequested = false
    private var acceptAttempted = false
    private var endRequested = false
    private var acceptRequest: Pair<Long, String>? = null
    private var closeDeadline: Job? = null
    private val mediaReleaseListeners = mutableSetOf<(Boolean) -> Unit>()
    private val cameraStates = mutableMapOf<String, RtcCameraState>()
    private var recoveryJob: Job? = null
    private var recoveryChecked = false
    private var dialJob: Job? = null
    private var dialSubmitted = false
    private var dialGroupAcceptRequest: Pair<Long, String>? = null
    private var dialSelectMembers: (() -> Unit)? = null
    private var dialGroupCall by mutableStateOf<RtcCall?>(null)
    val joiningExistingGroup: Boolean get() = dialGroupCall != null
    var dialTarget by mutableStateOf<RtcTarget?>(null)
        private set
    var dialCallIds by mutableStateOf<List<String>?>(null)
        private set
    var dialBusy by mutableStateOf(false)
        private set
    var dialError by mutableStateOf<String?>(null)
        private set
    var dialAwaitingPermission by mutableStateOf(false)
        private set
    private var recoveries by mutableStateOf<List<RtcOngoingCall>>(emptyList())
    var recoveryBusy by mutableStateOf(false)
        private set
    var recoveryError by mutableStateOf<String?>(null)
        private set
    val recovery: RtcOngoingCall? get() = if (active) null else recoveries.firstOrNull()

    /** 当前授权首次进入前台查询，失败保留重试入口；查询本身不接管通话。 */
    fun refreshRecovery() {
        if (disposed || !foreground || active || dialTarget != null || dialBusy || recoveryJob?.isActive == true) return
        recoveryBusy = true
        recoveryError = null
        recoveryJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val pending = repository.ongoing()
                ensureActive()
                if (!disposed && foreground && !active) {
                    recoveries = pending
                    recoveryChecked = true
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (!disposed) recoveryError = "未结束通话查询失败，请重试"
            } finally {
                recoveryBusy = false
                recoveryJob = null
                discover()
            }
        }.also { it.start() }
    }

    /** 仅收起查询失败提示；下次前台或 SSE 恢复仍可补查。 */
    fun dismissRecoveryError() {
        if (recovery == null && !recoveryBusy) recoveryError = null
    }

    /** 主动挂断直接提交终止意图；仅重连需要预读状态后进入权限和媒体流程。 */
    fun resolveRecovery(reconnect: Boolean) {
        val pending = recovery ?: return
        if (disposed || !foreground || recoveryBusy || active || dialTarget != null || dialBusy) return
        recoveryBusy = true
        recoveryError = null
        discoveryJob?.cancel()
        discoveryJob = null
        recoveryJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val latest = if (reconnect) repository.get(pending.call) else pending.call
                ensureActive()
                if (!latest.state.terminal) {
                    if (reconnect) {
                        val restored = repository.reconnect(latest)
                        ensureActive()
                        if (disposed || !foreground || active) return@launch
                        val peer = if (restored.callerUid == repository.ownUid) requireNotNull(restored.calleeUid) else restored.callerUid
                        reset(RtcTarget(restored.roomId, peer, pending.peerName, "",
                            mediaMode = restored.mediaMode,
                            incoming = restored.calleeUid == repository.ownUid, recovered = true))
                        call = restored
                        acceptAttempted = true
                        detachedFromChat = true
                        acceptedTimerStarted = true
                        elapsedSeconds = restored.acceptedAt?.let {
                            ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)
                        } ?: 0
                        timerStartedAt = monotonicNow() - elapsedSeconds * 1000
                        awaitingPermission = true
                    } else {
                        val ended = repository.action(latest, "hangup", rtcRequestId())
                        if (!ended.state.terminal) throw IOException("服务端尚未确认挂断")
                    }
                }
                ensureActive()
                recoveries = recoveries.filterNot { it.call.callId == pending.call.callId }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RtcBusinessException) {
                if (!disposed) {
                    if (exception.errCode in setOf(46004, 46005, 46007))
                        recoveries = recoveries.filterNot { it.call.callId == pending.call.callId }
                    recoveryError = exception.message
                    Log.w("OpenChatRtc", "${RtcAuditEvent.RTC_RECOVERY_REJECTED.name} callId=${pending.call.callId} code=${exception.errCode}")
                }
            } catch (_: Exception) {
                if (!disposed) recoveryError = "通话恢复操作失败，请重试"
            } finally {
                recoveryBusy = false
                recoveryJob = null
                discover()
            }
        }.also { it.start() }
    }

    var target by mutableStateOf<RtcTarget?>(null)
        private set
    var call by mutableStateOf<RtcCall?>(null)
        private set
    var room by mutableStateOf<Room?>(null)
        private set
    var localVideo by mutableStateOf<LocalVideoTrack?>(null)
        private set
    var remoteVideo by mutableStateOf<VideoTrack?>(null)
        private set
    var groupVideos by mutableStateOf<Map<String, VideoTrack>>(emptyMap())
        private set
    var groupSpeakingUids by mutableStateOf<Set<String>>(emptySet())
        private set
    var cameraPermissionPending by mutableStateOf(false)
        private set
    var cameraError by mutableStateOf<String?>(null)
        private set
    var directRemoteVideo by mutableStateOf<RtcDirectVideoSource?>(null)
        private set
    var awaitingPermission by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var ending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var microphoneEnabled by mutableStateOf(true)
        private set
    var cameraEnabled by mutableStateOf(true)
        private set
    var remoteCameraEnabled by mutableStateOf<Boolean?>(null)
        private set
    var speakerEnabled by mutableStateOf(true)
        private set
    var frontCamera by mutableStateOf(true)
        private set
    var mediaConnected by mutableStateOf(false)
        private set
    var elapsedSeconds by mutableLongStateOf(0)
        private set
    var answering by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set
    var windowClosed by mutableStateOf(false)
        private set
    var floating by mutableStateOf(false)
        private set
    var systemFloating by mutableStateOf(false)
        private set
    var detachedFromChat by mutableStateOf(false)
        private set
    var remoteVideoLayoutEstablished by mutableStateOf(false)
        private set
    // Null follows the initial local-preview/remote-first-frame layout until the user chooses.
    var localVideoMain by mutableStateOf<Boolean?>(null)
        private set
    val active: Boolean get() = target != null
    val windowVisible: Boolean get() = active && !windowClosed
    val incoming: Boolean get() = target?.incoming == true
    /** 群邀请是否可接听只取本人的参与状态。 */
    val canAnswer: Boolean get() = incoming && call?.invited(repository.ownUid) == true &&
        !closing && !ending && !finished && !answering && !awaitingPermission
    /** 页面与媒体共同使用本人身份和本人的接听结果。 */
    val ownUid: String get() = repository.ownUid
    /** 本人的接听状态独立于其他群成员。 */
    val joined: Boolean get() = call?.acceptedBy(repository.ownUid) == true
    /** 群类型由发起或来电流程核验的真实房间确定。 */
    val groupCall: Boolean get() = target?.groupName != null
    val controlsEnabled: Boolean get() =
        (localVideo != null || directMedia != null || audio != null) &&
            !closing && !ending && !finished
    val audioControlsEnabled: Boolean get() =
        !disposed && !awaitingPermission && !closing && !ending && !finished &&
            (controlsEnabled || (audioCall && active && !incoming))
    /** 群通话的视频协议能力不等于正在使用相机。 */
    val audioCall: Boolean get() = target?.mediaMode == RtcMediaMode.AUDIO || groupCall && !cameraEnabled
    val canRetry: Boolean get() = error != null && !closing && !finished && !awaitingPermission
    val isForeground: Boolean get() = foreground
    val authorized: Boolean get() = !disposed && repository.isAuthorized()
    val canContinueInBackground: Boolean get() = target != null && backgroundOwner === target
    val canStartBackgroundMedia: Boolean get() = authorized && windowVisible &&
        !closing && !ending && !finished && !awaitingPermission &&
        (!incoming || acceptRequested || acceptAttempted)

    // Only the service which holds this exact in-memory call may extend its media lifetime.
    fun setBackgroundMedia(request: RtcTarget, enabled: Boolean) {
        if (enabled) {
            if (!authorized || closing || awaitingPermission || target !== request ||
                (!foreground && !RtcCallOverlay.isStarting(this))) return
            backgroundOwner = request
        } else if (target === request) {
            backgroundOwner = null
            if (!foreground && !disposed && !awaitingPermission) end()
        }
    }

    fun establishRemoteVideoLayout(request: RtcTarget) {
        if (disposed || target !== request || closing || audioCall) return
        remoteVideoLayoutEstablished = true
    }

    fun selectMainVideo(request: RtcTarget, local: Boolean) {
        if (disposed || target !== request || !windowVisible || closing || audioCall) return
        localVideoMain = local
    }

    fun minimize(systemWindow: Boolean = false) {
        if (!windowVisible || closing || awaitingPermission || cameraPermissionPending) return
        if (systemWindow && !canContinueInBackground) return
        target?.let {
            selectMainVideo(it, localVideoMain ?: !(remoteVideoLayoutEstablished || remoteCameraEnabled == false))
        }
        floating = true
        systemFloating = systemWindow
        detachedFromChat = true
    }

    fun restore() {
        if (!windowVisible || closing) return
        floating = false
        systemFloating = false
    }

    fun addMediaReleaseListener(listener: (Boolean) -> Unit) { mediaReleaseListeners += listener }
    fun removeMediaReleaseListener(listener: (Boolean) -> Unit) { mediaReleaseListeners -= listener }

    private fun startClosing() {
        if (closing) return
        val request = target ?: return
        tick()
        // Capture textures and detach their renderers before releasing the SDK's EGL resources.
        mediaReleaseListeners.toList().forEach { runCatching { it(true) } }
        closing = true
        groupSpeakingUids = emptySet()
        closeDeadline = scope.launch {
            delay(RTC_CLOSE_DELAY_MS.milliseconds)
            if (target !== request) return@launch
            windowClosed = true
            floating = false
            systemFloating = false
            clearClosedTarget()
        }
    }

    /** 收尾完成后释放旧通话的悬浮服务，再发现排队的新邀请。 */
    private fun clearClosedTarget() {
        if (!windowClosed || !finished) return
        RtcCallOverlay.release(this)
        backgroundOwner = null
        target = null
        wake()
    }

    /** 群入口先查原通话，只有确认不存在时才交给页面选择受邀成员。 */
    fun beginGroup(room: String, name: String, avatar: String, onSelectMembers: () -> Unit) {
        if (disposed || !foreground || dialTarget != null || dialBusy || recoveryBusy || recovery != null ||
            name.isBlank() || room.isBlank()) return
        if (active && call == null && !finished) return
        discoveryJob?.cancel()
        discoveryJob = null
        // 查询阶段没有选定对方；该请求只能加入已查到的通话，不能用于创建。
        dialTarget = RtcTarget(room, "", name, avatar, groupName = name)
        dialSelectMembers = onSelectMembers
        checkDial()
    }

    /** 单聊语音、视频及历史回拨共用呼出检查，同一时刻只处理一次确认。 */
    fun begin(
        room: String, peer: String, name: String, avatar: String,
        mediaMode: RtcMediaMode = RtcMediaMode.VIDEO,
        groupName: String? = null,
        inviteeUids: List<String> = emptyList(),
        memberNames: Map<String, String> = emptyMap(),
    ) {
        if (disposed || dialTarget != null || dialBusy || recoveryBusy || recovery != null || peer == repository.ownUid) return
        // 创建结果未知时仍由原呼叫的重试/取消流程负责，不能同时提交另一通。
        if (active && call == null && !finished) return
        discoveryJob?.cancel()
        discoveryJob = null
        if (groupName != null && (groupName.isBlank() || inviteeUids.isEmpty() ||
                repository.ownUid in inviteeUids || inviteeUids.distinct().size != inviteeUids.size)) return
        dialTarget = RtcTarget(room, peer, name, avatar, mediaMode = mediaMode,
            groupName = groupName, inviteeUids = inviteeUids.toList(), memberNames = memberNames.toMap())
        checkDial()
    }

    /** 先查服务端活动记录；查询失败不按“没有通话”继续呼出。 */
    fun checkDial() {
        val request = dialTarget ?: return
        if (disposed || dialBusy || dialSubmitted) return
        dialBusy = true
        dialError = null
        dialCallIds = null
        dialGroupCall = null
        dialGroupAcceptRequest = null
        dialJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val groupCheck = request.groupName?.let { repository.groupDial(request.roomId, it) }
                val ids = groupCheck?.activeCallIds ?: repository.activeCallIds()
                ensureActive()
                if (dialTarget !== request) return@launch
                dialCallIds = ids
                val existing = groupCheck?.existing
                if (existing != null) {
                    dialSelectMembers = null
                    dialGroupCall = existing
                    continueGroupDial(request, existing, ids)
                } else if (request.groupName != null && request.inviteeUids.isEmpty()) {
                    val selectMembers = dialSelectMembers
                    if (selectMembers != null && foreground) {
                        dialSelectMembers = null
                        dialTarget = null
                        dialCallIds = null
                        selectMembers()
                    } else {
                        dialError = "原群通话已结束或邀请已失效，请重新发起"
                    }
                } else if (ids.isEmpty()) continueDial(request, ids)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (dialTarget === request) dialError = "未挂断通话查询失败，请重试"
            } finally {
                if (dialTarget === request || dialTarget == null) dialBusy = false
            }
        }.also { it.start() }
    }

    /** 只确认本次快照，服务端仍会拒绝期间新增且未确认的呼叫。 */
    fun confirmDial() {
        val request = dialTarget ?: return
        val ids = dialCallIds ?: return
        if (disposed || dialBusy || dialAwaitingPermission) return
        dialGroupCall?.let {
            if (dialError != null && !dialSubmitted) {
                checkDial()
                return
            }
            continueGroupDial(request, it, ids)
            return
        }
        continueDial(request, ids)
    }

    /** 群成员主动加入仅在仍已接听时检查旧设备归属，退出后允许重新绑定当前设备。 */
    private fun continueGroupDial(request: RtcTarget, existing: RtcCall, ids: List<String>) {
        if (dialTarget !== request || !foreground) return
        dialError = null
        if (existing.participants[repository.ownUid] == RtcParticipantState.ACCEPTED &&
            existing.deviceOwnership != RtcDeviceOwnership.CURRENT_DEVICE) {
            dialError = "该群通话已在其他设备接听或设备归属未确认"
            return
        }
        if (active && call?.callId == existing.callId && !closing && !ending && !finished) {
            cancelDial()
            restore()
            if (canAnswer) answer()
            return
        }
        if (active || ids.any { it != existing.callId }) {
            dialError = "该群已有通话，请先结束其他通话后再加入"
            return
        }
        dialAwaitingPermission = true
    }

    /** 空闲时进入原拨号流程；已有窗口时先独立申请新通话需要的权限。 */
    private fun continueDial(request: RtcTarget, ids: List<String>) {
        if (request.groupName != null && request.inviteeUids.isEmpty()) {
            checkDial()
            return
        }
        dialError = null
        if (!active) {
            dialTarget = null
            dialCallIds = null
            dialSubmitted = false
            reset(RtcTarget(request.roomId, request.peerUid, request.name, request.avatar,
                mediaMode = request.mediaMode, replaceCallIds = ids, requestId = request.requestId,
                groupName = request.groupName, inviteeUids = request.inviteeUids, memberNames = request.memberNames))
            awaitingPermission = true
        } else {
            // 旧媒体继续使用，授权拒绝或取消均不会打断当前通话。
            dialAwaitingPermission = true
        }
    }

    /** 已有本机通话时，创建成功后才释放旧媒体；未知结果重试沿用原幂等 ID。 */
    fun dialPermissionResult(request: RtcTarget, granted: Boolean) {
        if (disposed || dialTarget !== request || !dialAwaitingPermission) return
        RtcLogger.record(RtcAuditEvent.RTC_PERMISSION_RESULT, RtcLogStage.MEDIA_PERMISSION,
            target = request, granted = granted)
        dialAwaitingPermission = false
        if (!granted) {
            dialError = if (joiningExistingGroup) "需要麦克风权限才能加入群通话"
                else if (request.groupName != null || request.mediaMode == RtcMediaMode.AUDIO) "需要麦克风权限才能呼出"
                else "需要相机和麦克风权限才能呼出"
            return
        }
        dialGroupCall?.let {
            joinExistingGroup(request, it)
            return
        }
        val ids = dialCallIds ?: return
        dialBusy = true
        dialError = null
        dialJob = scope.launch(start = CoroutineStart.LAZY) {
            var stage = RtcLogStage.VERIFY_ROOM
            try {
                repository.verify(request)
                dialSubmitted = true
                stage = RtcLogStage.CREATE_CALL
                val created = repository.create(
                    request.roomId, request.peerUid, request.requestId, request.mediaMode, ids,
                    request.groupName, request.inviteeUids,
                )
                ensureActive()
                if (dialTarget !== request || !foreground) {
                    cleanupDetached(created)
                    return@launch
                }
                worker?.cancel()
                if (active) finish(cleanup = false)
                RtcCallOverlay.release(this@RtcVideoController)
                reset(request)
                publish(created)
                dialTarget = null
                dialCallIds = null
                dialSubmitted = false
                worker = scope.launch { runCall(request) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                RtcLogger.record(RtcAuditEvent.RTC_OPERATION_FAILED, stage,
                    target = request, exception = exception)
                if (dialTarget === request) dialError = "呼出未确认，请重试或取消；原通话状态以服务端为准"
            } finally {
                dialBusy = false
                if (dialTarget == null) {
                    dialSubmitted = false
                    discover()
                }
            }
        }.also { it.start() }
    }

    /** 授权后重新复核并接听原 callId；明确加入成功前不占用媒体或修改原邀请。 */
    private fun joinExistingGroup(request: RtcTarget, candidate: RtcCall) {
        dialBusy = true
        dialError = null
        dialJob = scope.launch(start = CoroutineStart.LAZY) {
            var acceptSubmitted = dialSubmitted
            var adopted = false
            var stage = RtcLogStage.QUERY_CALL
            try {
                val check = repository.groupDial(request.roomId, requireNotNull(request.groupName))
                var latest = check.existing?.takeIf { it.callId == candidate.callId }
                    ?: throw IOException("原群通话已结束或邀请已失效，请重新发起")
                if (!RtcProtocol.newer(candidate, latest)) throw IOException("原群通话快照不匹配")
                if (check.activeCallIds.any { it != latest.callId } || active)
                    throw IOException("请先结束其他通话后再加入")
                latest = repository.get(latest).also {
                    if (!RtcProtocol.newer(latest, it)) throw IOException("群通话状态已变化，请重试")
                }
                ensureActive()
                if (dialTarget !== request || !foreground) return@launch
                if (latest.state.terminal) throw IOException("原群通话已结束")
                if (latest.participants[repository.ownUid] != RtcParticipantState.ACCEPTED) {
                    val id = dialGroupAcceptRequest?.takeIf { it.first == latest.version }?.second
                        ?: rtcRequestId().also { dialGroupAcceptRequest = latest.version to it }
                    stage = RtcLogStage.ACCEPT_CALL
                    dialSubmitted = true
                    acceptSubmitted = true
                    latest = repository.action(latest, "accept", id).also {
                        if (!RtcProtocol.newer(latest, it)) throw IOException("群通话接听快照不匹配")
                    }
                }
                if (latest.finishedFor(repository.ownUid) ||
                    latest.participants[repository.ownUid] != RtcParticipantState.ACCEPTED ||
                    latest.deviceOwnership != RtcDeviceOwnership.CURRENT_DEVICE)
                    throw IOException("群通话未确认由当前设备加入")
                ensureActive()
                if (dialTarget !== request || !foreground || active) {
                    return@launch
                }
                // 群目标只用发起人作展示关联，没有唯一被叫，不伪造被叫 UID。
                val peer = latest.callerUid
                val joinedTarget = RtcTarget(latest.roomId, peer, request.name, request.avatar,
                    mediaMode = latest.mediaMode, incoming = latest.callerUid != repository.ownUid,
                    groupName = latest.groupName, inviteeUids = latest.participants.keys.filter { it != repository.ownUid },
                    memberNames = request.memberNames, existingGroup = true)
                reset(joinedTarget)
                acceptAttempted = true
                publish(latest)
                if (latest.accepted) {
                    acceptedTimerStarted = true
                    elapsedSeconds = latest.acceptedAt?.let {
                        ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)
                    } ?: 0
                    timerStartedAt = monotonicNow() - elapsedSeconds * 1000
                }
                invitations.remove(latest.callId)
                dialTarget = null
                dialCallIds = null
                dialGroupCall = null
                dialGroupAcceptRequest = null
                dialSubmitted = false
                adopted = true
                worker = scope.launch { runCall(joinedTarget) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                RtcLogger.record(RtcAuditEvent.RTC_OPERATION_FAILED, stage,
                    call = candidate, target = request, exception = exception)
                if (dialTarget === request) dialError = "加入原群通话未确认，请重试或取消"
            } finally {
                if (acceptSubmitted && !adopted && dialTarget !== request)
                    cleanupDetached(candidate, allowAccepted = true, onlyAccepted = true)
                dialBusy = false
                if (dialTarget == null) {
                    dialSubmitted = false
                    discover()
                }
            }
        }.also { it.start() }
    }

    /** 离页或取消只撤销待确认操作；已提交的迟到成功沿原授权结束新呼叫。 */
    fun cancelDial() {
        if (dialSubmitted && !dialBusy) dialGroupCall?.let {
            cleanupDetached(it, allowAccepted = true, onlyAccepted = true)
        }
        dialTarget = null
        dialCallIds = null
        dialAwaitingPermission = false
        dialError = null
        dialGroupCall = null
        dialGroupAcceptRequest = null
        dialSelectMembers = null
        if (!dialSubmitted || !dialBusy) {
            dialJob?.cancel()
            dialJob = null
            dialBusy = false
            dialSubmitted = false
        }
        discover()
    }

    /** 初始化本次呼叫并清除上一通话的媒体和摄像头通知版本。 */
    private fun reset(request: RtcTarget) {
        closeDeadline?.cancel()
        closeDeadline = null
        closing = false
        windowClosed = false
        floating = false
        systemFloating = false
        backgroundOwner = null
        detachedFromChat = false
        remoteVideoLayoutEstablished = false
        localVideoMain = null
        worker?.cancel()
        worker = null
        target = request
        call = null
        awaitingPermission = false
        finished = false
        ending = false
        error = null
        createAttempted = false
        retryRequested = false
        microphoneEnabled = true
        cameraEnabled = request.groupName == null
        cameraPermissionPending = false
        cameraError = null
        groupVideos = emptyMap()
        groupSpeakingUids = emptySet()
        groupWaitingJob?.cancel()
        groupWaitingJob = null
        groupWaitingExpired = false
        groupEnteredUids = emptySet()
        cameraStates.clear()
        remoteCameraEnabled = null
        speakerEnabled = true
        frontCamera = true
        elapsedSeconds = 0
        timerStartedAt = if (request.incoming) null else monotonicNow()
        acceptedTimerStarted = false
        stopRequest = null
        expireRequest = null
        acceptRequest = null
        acceptRequested = false
        acceptAttempted = false
        endRequested = false
        answering = false
        endingDeadline?.cancel()
    }

    /** 前台恢复先发现原单聊，后台不主动接管待确认的通话。 */
    fun setForeground(value: Boolean) {
        if (disposed) return
        foreground = value
        if (value) {
            wake()
        } else {
            cancelDial()
            recoveryJob?.cancel()
            discoveryJob?.cancel()
            discoveryJob = null
            RtcCallOverlay.onBackground(this)
            if (!awaitingPermission && !cameraPermissionPending &&
                !canContinueInBackground && !RtcCallOverlay.isStarting(this)) end()
        }
    }

    /** 空闲且前台时复核排队邀请；旧窗口结束或呼出确认取消后继续处理。 */
    private fun discover() {
        if (disposed || !foreground || active || dialTarget != null || dialBusy || recoveryBusy || recovery != null) return
        if (discoveryJob?.isActive == true || invitations.isEmpty()) return
        discoveryJob = scope.launch(start = CoroutineStart.LAZY) {
            while (invitations.isNotEmpty() && !active) {
                val invitation = invitations.values.first()
                // Retry a transient GET failure without depending on another CREATED push.
                for (attempt in 0 until 3) {
                    try {
                        val snapshot = repository.incoming(invitation)
                        if (!snapshot.invited(repository.ownUid) || snapshot.deadline <= System.currentTimeMillis() ||
                            snapshot.deviceOwnership != RtcDeviceOwnership.UNASSIGNED) {
                            break
                        }
                        val latest = repository.get(snapshot)
                        ensureActive()
                        if (foreground && !active && RtcProtocol.newer(snapshot, latest) &&
                            latest.invited(repository.ownUid) && latest.deadline > System.currentTimeMillis() &&
                            latest.deviceOwnership == RtcDeviceOwnership.UNASSIGNED &&
                            invitation.callId !in shownCalls
                        ) {
                            reset(
                                RtcTarget(
                                    latest.roomId, latest.callerUid, latest.callerUid, "",
                                    mediaMode = latest.mediaMode, incoming = true,
                                    groupName = latest.groupName,
                                ),
                            )
                            call = latest
                            shownCalls += latest.callId
                            if (shownCalls.size > 64) shownCalls.remove(shownCalls.first())
                            invitations.remove(latest.callId)
                            val request = requireNotNull(target)
                            worker = scope.launch { runCall(request) }
                        }
                        break
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        if (attempt < 2) delay(2000.milliseconds)
                    }
                }
                invitations.remove(invitation.callId)
            }
        }.also { it.start() }
    }

    fun answer() {
        if (!foreground || !canAnswer || (call?.deadline ?: 0) <= System.currentTimeMillis()) return
        answering = true
        awaitingPermission = true
        error = null
    }

    /** 媒体权限只在用户主动发起、接听或确认恢复后申请。 */
    fun permissionResult(request: RtcTarget, granted: Boolean) {
        if (disposed || target !== request || !awaitingPermission) return
        RtcLogger.record(RtcAuditEvent.RTC_PERMISSION_RESULT, RtcLogStage.MEDIA_PERMISSION,
            call, request, repository.ownUid, granted = granted)
        awaitingPermission = false
        if (!granted) {
            if (request.recovered) {
                recoveryChecked = false
                error = if (audioCall) "需要麦克风权限才能重连" else "需要相机和麦克风权限才能重连"
                finish(cleanup = false)
                return
            }
            if (incoming) {
                answering = false
                error = if (audioCall) "需要麦克风权限才能接听" else "需要相机和麦克风权限才能接听"
                end()
                return
            }
            error = if (audioCall) {
                "需要麦克风权限，请在系统设置中允许后重新发起"
            } else {
                "需要相机和麦克风权限，请在系统设置中允许后重新发起"
            }
            finish()
            return
        }
        if (request.recovered) {
            worker = scope.launch { runCall(request) }
        } else if (incoming) {
            acceptRequested = true
            wake()
        } else {
            worker = scope.launch { runCall(request) }
        }
    }

    /** 串行推进当前通话与摄像头快照，SSE 可提前唤醒，失败按既有周期补查。 */
    private suspend fun runCall(request: RtcTarget) {
        var stage = RtcLogStage.VERIFY_ROOM
        try {
            if (!request.incoming && !ending) {
                if (!request.recovered && !request.existingGroup) repository.verify(request)
                if (ending) { finish(); return }
                if (request.mediaMode == RtcMediaMode.VIDEO && !groupCall &&
                    call?.state?.terminal != true) prepareMedia { stage = it }
            }
            while (currentCoroutineContext().isActive && target === request && !finished) {
                try {
                    if (call == null) {
                        if (ending && !createAttempted) { finish(); return }
                        // Ambiguous create failures reuse the original id, including cancellation recovery.
                        if (createAttempted && !retryRequested && !ending) {
                            wake.receive()
                            continue
                        }
                        retryRequested = false
                        createAttempted = true
                        stage = RtcLogStage.CREATE_CALL
                        publish(repository.create(
                            request.roomId, request.peerUid, request.requestId, request.mediaMode,
                            request.replaceCallIds,
                            request.groupName, request.inviteeUids,
                        ))
                    } else if (!ending || call?.group == true) {
                        stage = RtcLogStage.QUERY_CALL
                        publish(repository.get(requireNotNull(call)))
                    }
                    val latest = call ?: continue
                    error = null
                    if (latest.finishedFor(repository.ownUid)) { finish(cleanup = false); return }
                    if (!ending && latest.group && latest.invited(repository.ownUid) &&
                        latest.deadline <= System.currentTimeMillis()) {
                        finish(cleanup = false)
                        return
                    }
                    if (latest.deviceOwnership == RtcDeviceOwnership.OTHER_DEVICE ||
                        latest.deviceOwnership == RtcDeviceOwnership.UNKNOWN ||
                        (latest.acceptedBy(repository.ownUid) && latest.deviceOwnership != RtcDeviceOwnership.CURRENT_DEVICE)) {
                        dismissOtherDevice(latest.callId)
                        return
                    }
                    if (ending) {
                        val id = stopRequest?.takeIf { it.first == latest.version }?.second
                            ?: rtcRequestId().also { stopRequest = latest.version to it }
                        val action = if (latest.group) RtcProtocol.endAction(latest, repository.ownUid) else "hangup"
                        stage = RtcLogStage.END_CALL
                        publish(repository.action(latest, action, id))
                        if (call?.finishedFor(repository.ownUid) == true) { finish(cleanup = false); return }
                    } else if (!request.incoming && latest.state == RtcState.RINGING &&
                        System.currentTimeMillis() >= latest.deadline
                    ) {
                        val id = expireRequest?.takeIf { it.first == latest.version }?.second
                            ?: rtcRequestId().also { expireRequest = latest.version to it }
                        stage = RtcLogStage.EXPIRE_CALL
                        publish(repository.action(latest, "expire", id))
                        if (call?.state?.terminal == true) { finish(); return }
                    } else if (request.incoming && latest.invited(repository.ownUid) && acceptRequested) {
                        val id = acceptRequest?.takeIf { it.first == latest.version }?.second
                            ?: rtcRequestId().also { acceptRequest = latest.version to it }
                        acceptAttempted = true
                        stage = RtcLogStage.ACCEPT_CALL
                        publish(repository.action(latest, "accept", id))
                        if (joined) wake()
                    } else if (latest.acceptedBy(repository.ownUid) && mediaJob == null &&
                        directMedia == null && !sfuConnecting && !sfuConnected && !mediaConnected) {
                        if (request.mediaMode == RtcMediaMode.VIDEO && !latest.group && room == null) {
                            try { prepareMedia { stage = it } } catch (exception: Exception) {
                                end()
                                throw exception
                            }
                        }
                        answering = false
                        connectMedia(latest)
                    }
                    if (!ending && !finished && latest.acceptedBy(repository.ownUid) &&
                        latest.mediaMode == RtcMediaMode.VIDEO && (directMedia != null || sfuConnected)
                    ) {
                        stage = RtcLogStage.SYNC_VIDEO_SEAT
                        if (latest.group) syncVideoSeat(latest)
                        stage = RtcLogStage.SYNC_CAMERA
                        syncCameraState(latest, request)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: RtcBusinessException) {
                    logRtc(RtcAuditEvent.RTC_OPERATION_REJECTED, stage, exception)
                    if (exception.errCode in setOf(46001, 46007, 46008)) {
                        call?.let { dismissOtherDevice(it.callId) }
                        return
                    }
                    error = exception.message
                } catch (exception: Exception) {
                    logRtc(RtcAuditEvent.RTC_OPERATION_FAILED, stage, exception)
                    error = if (ending) "结束通话尚未确认，正在重试" else if (call == null) {
                        "呼叫结果尚未确认，请重试查询或取消"
                    } else "通话状态同步失败，正在重试"
                }
                withTimeoutOrNull(2000.milliseconds) { wake.receive() }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logRtc(RtcAuditEvent.RTC_OPERATION_FAILED, stage, exception)
            error = if (audioCall) "无法发起语音通话，请检查会话及麦克风后重试"
                else "无法发起视频通话，请检查会话及相机、麦克风后重试"
            finish()
        }
    }

    private suspend fun prepareMedia(onStage: (RtcLogStage) -> Unit = {}) {
        // A previous room may still be releasing the camera/audio route after its window closed.
        onStage(RtcLogStage.WAIT_MEDIA_CLEANUP)
        mediaCleanup?.join()
        currentCoroutineContext().ensureActive()
        if (ending || finished) return
        onStage(RtcLogStage.INIT_SDK)
        LiveKit.init(context.applicationContext)
        val egl = if (!audioCall) EglBase.create().also { videoEgl = it } else null
        onStage(RtcLogStage.CREATE_ROOM)
        val current = LiveKit.create(context.applicationContext, overrides = LiveKitOverrides(eglBase = egl))
        room = current
        localVideo = if (target?.mediaMode == RtcMediaMode.VIDEO && (!groupCall || cameraEnabled)) {
            onStage(RtcLogStage.CREATE_VIDEO)
            current.localParticipant.createVideoTrack(options = LocalVideoTrackOptions(
                position = if (frontCamera) CameraPosition.FRONT else CameraPosition.BACK,
            ))
        } else null
        onStage(RtcLogStage.CREATE_AUDIO)
        audio = current.localParticipant.createAudioTrack().apply { enabled = false }
        localVideo?.let {
            it.enabled = cameraEnabled
            if (cameraEnabled) {
                onStage(RtcLogStage.START_CAMERA)
                it.startCapture()
            }
        }
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            current.events.collect { event ->
                if (disposed || room !== current || ending || finished) return@collect
                if (!sfuConnecting && !sfuConnected) return@collect
                if (groupCall && event is RoomEvent.ActiveSpeakersChanged) {
                    refreshGroupSpeaking(current)
                    return@collect
                }
                refreshRemote(current)
                when (event) {
                    is RoomEvent.Reconnecting -> {
                        groupSpeakingUids = emptySet()
                        logRtc(RtcAuditEvent.RTC_MEDIA_RECONNECTING, RtcLogStage.CONNECT_SFU)
                        mediaConnected = false
                        error = "媒体连接中断，正在重连"
                    }
                    is RoomEvent.Reconnected -> {
                        logRtc(RtcAuditEvent.RTC_MEDIA_RECONNECTED, RtcLogStage.CONNECT_SFU)
                        error = null
                    }
                    is RoomEvent.Disconnected -> {
                        groupSpeakingUids = emptySet()
                        RtcLogger.record(RtcAuditEvent.RTC_MEDIA_DISCONNECTED, RtcLogStage.CONNECT_SFU,
                            call, target, repository.ownUid, attempt = mediaAttempt,
                            camera = cameraEnabled, microphone = microphoneEnabled,
                            exception = event.error, disconnectReason = event.reason, roomState = current.state)
                        if (!sfuConnected) return@collect
                        mediaConnected = false
                        error = "媒体连接已断开，通话正在结束"
                        end()
                    }
                    is RoomEvent.FailedToConnect -> {
                        logRtc(RtcAuditEvent.RTC_MEDIA_FAILED, RtcLogStage.CONNECT_SFU, event.error)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun logRtc(event: RtcAuditEvent, stage: RtcLogStage, exception: Throwable? = null) {
        RtcLogger.record(event, stage, call, target, repository.ownUid,
            attempt = mediaAttempt, camera = cameraEnabled, microphone = microphoneEnabled,
            exception = exception, roomState = room?.state)
    }

    /** 群通话直接复用服务端 SFU，单聊继续沿用 P2P 优先及单向回退。 */
    private fun connectMedia(latest: RtcCall) {
        if (latest.deviceOwnership != RtcDeviceOwnership.CURRENT_DEVICE ||
            mediaJob != null || sfuConnecting || sfuConnected) return
        if (latest.group) {
            mediaJob = scope.launch(start = CoroutineStart.LAZY) {
                val operation = currentCoroutineContext()[Job]
                try {
                    mediaCleanup?.join()
                    ensureActive()
                    connectSfuMedia(latest)
                } catch (_: TimeoutCancellationException) {
                    if (!ending && !finished) {
                        if (cameraEnabled) stopGroupCamera("摄像头连接超时，正在恢复语音通话")
                        error = "群通话连接超时，正在重试"
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    if (!ending && !finished) {
                        if (cameraEnabled) stopGroupCamera("摄像头开启失败，正在恢复语音通话")
                        error = "群通话连接失败，正在重试"
                    }
                } finally {
                    if (mediaJob === operation) mediaJob = null
                }
            }.also { it.start() }
            return
        }
        mediaJob = scope.launch {
            mediaCleanup?.join()
            ensureActive()
            if (ending || finished) return@launch
            val request = target
            lateinit var direct: RtcDirectMedia
            direct = RtcDirectMedia(
                context = context.applicationContext,
                repository = repository,
                call = latest,
                ownUid = repository.ownUid,
                onConnected = { connected ->
                    if (target === request && !ending && !finished && directMedia === direct) {
                        mediaConnected = connected
                        if (connected) {
                            error = null
                        }
                    }
                },
                onFallback = {
                    if (target === request && !ending && !finished && directMedia === direct) {
                        connectSfuMedia(latest)
                    }
                },
                onError = {
                    if (target === request && !ending && !finished) {
                        error = "通话网络连接不稳定，正在重试"
                    }
                },
                video = localVideo?.rtcTrack,
                eglContext = videoEgl?.eglBaseContext,
                onRemoteVideo = { source ->
                    if (target === request && !ending && !finished && directMedia === direct) {
                        directRemoteVideo = source
                    }
                },
            )
            directMedia = direct
            direct.setMicrophoneEnabled(microphoneEnabled)
            direct.setSpeakerEnabled(speakerEnabled)
            try {
                direct.start()
            } finally {
                withContext(NonCancellable) { direct.close().join() }
                if (directMedia === direct) directMedia = null
            }
        }
    }

    /** 连接既有 SFU；群通话默认只发布音频，相机明确开启且获得权限后才发布视频。 */
    private suspend fun connectSfuMedia(latest: RtcCall) {
        var completed = false
        val attempt = ++mediaAttempt
        val startedAt = monotonicNow()
        val request = target
        var stage = RtcLogStage.WAIT_MEDIA_CLEANUP
        fun record(event: RtcAuditEvent, exception: Throwable? = null) {
            RtcLogger.record(event, stage, latest, request, repository.ownUid,
                attempt, monotonicNow() - startedAt, cameraEnabled, microphoneEnabled,
                exception = exception, roomState = room?.state)
        }
        fun enter(next: RtcLogStage) {
            stage = next
            record(RtcAuditEvent.RTC_MEDIA_STAGE)
        }
        try {
            withTimeout(15_000L.milliseconds) {
                if (ending || finished || disposed) return@withTimeout
                if (!authorized) throw CancellationException("通话授权已失效")
                if (room == null) prepareMedia(::enter)
                val current = room ?: return@withTimeout
                val microphone = audio ?: throw IOException("本机麦克风轨道创建失败")
                enter(RtcLogStage.GET_CREDENTIAL)
                val credential = repository.credential(latest)
                ensureActive()
                if (ending || finished || room !== current) return@withTimeout
                if (latest.group) {
                    videoSeatHeld = credential.videoAllowed && latest.participants.size > 2
                    if (!cameraEnabled && videoSeatHeld) {
                        enter(RtcLogStage.RELEASE_VIDEO_SEAT)
                        repository.videoEnabled(latest, false)
                        videoSeatHeld = false
                    }
                    if (cameraEnabled && !credential.videoAllowed) {
                        record(RtcAuditEvent.RTC_VIDEO_SEAT_DENIED)
                        stopGroupCamera("当前视频人数已满，继续语音通话")
                    }
                }
                sfuConnecting = true
                enter(RtcLogStage.CONNECT_SFU)
                current.connect(credential.serverUrl, credential.token)
                ensureActive()
                if (ending || finished || room !== current) return@withTimeout
                enter(RtcLogStage.VERIFY_IDENTITY)
                if (current.name != credential.roomName ||
                    current.localParticipant.identity?.value != repository.ownUid
                ) throw IOException("媒体房间身份不匹配")
                if (latest.mediaMode == RtcMediaMode.VIDEO && (!latest.group || cameraEnabled)) {
                    enter(RtcLogStage.PUBLISH_VIDEO)
                    val video = localVideo ?: throw IOException("本机摄像头轨道创建失败")
                    video.enabled = cameraEnabled
                    if (!current.localParticipant.publishVideoTrack(video)) throw IOException("视频发布失败")
                    ensureActive()
                    if (ending || finished || room !== current) return@withTimeout
                    videoPublished = true
                }
                enter(RtcLogStage.PUBLISH_AUDIO)
                microphone.enabled = microphoneEnabled
                if (!current.localParticipant.publishAudioTrack(microphone)) throw IOException("音频发布失败")
                ensureActive()
                if (ending || finished || room !== current) return@withTimeout
                audioPublished = true
                record(RtcAuditEvent.RTC_MEDIA_PUBLISHED)
                enter(RtcLogStage.WAIT_SFU_CONNECTED)
                awaitRtcSfuConnected(
                    state = { current.state },
                    isCurrent = { target === request && room === current && !ending && !finished && !disposed },
                )
                sfuConnected = true
                completed = true
                if (latest.group && videoSeatHeld && cameraEnabled) startVideoLease(latest)
                enter(RtcLogStage.REFRESH_MEMBERS)
                refreshRemote(current)
                if (!ending && !finished && room === current) {
                    stage = RtcLogStage.READY
                    record(RtcAuditEvent.RTC_MEDIA_READY)
                }
                error = null
                wake()
            }
        } catch (exception: TimeoutCancellationException) {
            record(RtcAuditEvent.RTC_MEDIA_TIMEOUT, exception)
            throw exception
        } catch (exception: CancellationException) {
            record(RtcAuditEvent.RTC_MEDIA_CANCELED)
            throw exception
        } catch (exception: Exception) {
            // Log before cleanup so the original failing stage and exception remain available.
            record(RtcAuditEvent.RTC_MEDIA_FAILED, exception)
            throw exception
        } finally {
            sfuConnecting = false
            if (!completed && !ending && !finished && !disposed) {
                mediaReleaseListeners.toList().forEach { runCatching { it(false) } }
                val current = room
                val video = localVideo
                val microphone = audio
                val egl = videoEgl
                room = null
                localVideo = null
                remoteVideo = null
                groupVideos = emptyMap()
                groupSpeakingUids = emptySet()
                audio = null
                videoEgl = null
                sfuConnected = false
                mediaConnected = false
                videoPublished = false
                audioPublished = false
                eventJob?.cancel()
                eventJob = null
                runCatching { video?.enabled = false }
                runCatching { microphone?.enabled = false }
                withContext(NonCancellable + Dispatchers.IO) {
                    val owned = current?.localParticipant?.trackPublications?.values?.mapNotNull { it.track }.orEmpty()
                    if (owned.none { it === video }) {
                        runCatching { video?.stop() }
                        runCatching { video?.dispose() }
                    }
                    if (owned.none { it === microphone }) {
                        runCatching { microphone?.stop() }
                        runCatching { microphone?.dispose() }
                    }
                    runCatching { current?.release() }
                    runCatching { egl?.release() }
                }
                if (latest.group && videoSeatHeld) {
                    videoSeatHeld = false
                    withContext(NonCancellable) {
                        withTimeoutOrNull(3_000L.milliseconds) {
                            runCatching { repository.videoEnabled(latest, false) }.onFailure {
                                if (it !is CancellationException || it is TimeoutCancellationException) {
                                    RtcLogger.record(RtcAuditEvent.RTC_OPERATION_FAILED, RtcLogStage.RELEASE_VIDEO_SEAT,
                                        latest, request, repository.ownUid, attempt = attempt, exception = it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 刷新真实轨道和音量；群聊仅播放已接听成员，拒绝和离开的成员立即静音。 */
    private fun refreshRemote(current: Room) {
        if (!sfuConnecting && !sfuConnected) return
        val peer = target?.peerUid ?: return
        val allowed = if (groupCall) call?.participants?.keys.orEmpty() - repository.ownUid else setOf(peer)
        if (current.remoteParticipants.values.any { it.identity?.value !in allowed }) {
            logRtc(RtcAuditEvent.RTC_OPERATION_FAILED, RtcLogStage.REFRESH_MEMBERS)
            error = "通话成员已变化，已停止本机音视频"
            end()
            return
        }
        val audible = if (groupCall) call?.participants?.filterValues {
            it == RtcParticipantState.ACCEPTED
        }?.keys.orEmpty() - repository.ownUid else allowed
        val participant = current.remoteParticipants.values.firstOrNull { it.identity?.value == peer }
        remoteVideo = participant?.trackPublications?.values
            ?.firstOrNull { it.track is VideoTrack }?.track as? VideoTrack
        if (groupCall) {
            // 接听快照不代表已进入媒体房间；曾入房成员在媒体重连时不重新进入邀请倒计时。
            if (sfuConnected && current.state == Room.State.CONNECTED) {
                groupEnteredUids = groupEnteredUids + current.remoteParticipants.values.mapNotNull {
                    it.identity?.value?.takeIf { uid -> uid in audible }
                } + repository.ownUid
            }
            groupVideos = current.remoteParticipants.values.mapNotNull { member ->
                val uid = member.identity?.value ?: return@mapNotNull null
                val track = member.trackPublications.values
                    .firstOrNull { it.track is VideoTrack && !it.muted }?.track as? VideoTrack
                if (uid !in audible || track == null || cameraStates[uid]?.enabled == false) null else uid to track
            }.toMap()
            refreshGroupSpeaking(current)
        }
        current.remoteParticipants.values.forEach { member ->
            member.trackPublications.values.forEach { publication ->
                (publication.track as? RemoteAudioTrack)?.setVolume(
                    if (member.identity?.value in audible && speakerEnabled) 1.0 else 0.0,
                )
            }
        }
        mediaConnected = current.state == Room.State.CONNECTED &&
            (groupCall || participant?.trackPublications?.values?.any { it.track != null } == true)
    }

    private fun refreshGroupSpeaking(current: Room) {
        if (!groupCall || room !== current || !sfuConnected || current.state != Room.State.CONNECTED ||
            closing || ending || finished || disposed
        ) {
            groupSpeakingUids = emptySet()
            return
        }
        val participants = call?.participants.orEmpty()
        groupSpeakingUids = buildSet {
            current.remoteParticipants.values.forEach { member ->
                val uid = member.identity?.value ?: return@forEach
                if (uid in groupEnteredUids && participants[uid] == RtcParticipantState.ACCEPTED &&
                    member.isSpeaking && member.trackPublications.values.any {
                        it.track is RemoteAudioTrack && !it.muted
                    }
                ) add(uid)
            }
            val local = current.localParticipant
            if (repository.ownUid in groupEnteredUids &&
                participants[repository.ownUid] == RtcParticipantState.ACCEPTED &&
                microphoneEnabled && audioPublished && audio?.enabled == true && local.isSpeaking &&
                local.trackPublications.values.any { it.track === audio && !it.muted }
            ) add(repository.ownUid)
        }
    }

    fun tick() {
        val startedAt = timerStartedAt ?: return
        if (!disposed && active && !closing && !finished) {
            elapsedSeconds = ((monotonicNow() - startedAt) / 1000).coerceAtLeast(0)
        }
    }

    /** 沿既有 SSE 唤醒状态同步；空闲时补查本次授权尚未完成的恢复列表。 */
    fun wake() {
        directMedia?.refresh()
        if (active && !finished) wake.trySend(Unit)
        else {
            if (!recoveryChecked) refreshRecovery()
            discover()
        }
    }

    /** 接收唯一 SSE 的通话通知，摄像头独立版本可立即更新画面，其余状态仍查询确认。 */
    fun event(body: String) {
        if (disposed) return
        RtcProtocol.answeredDevice(body, repository.ownUid)?.let { device ->
            if (repository.answeredElsewhere(device)) {
                invitations.remove(device.callId)
                shownCalls += device.callId
                if (shownCalls.size > 64) shownCalls.remove(shownCalls.first())
                dismissOtherDevice(device.callId)
            }
            wake()
            return
        }
        if (!active && recoveries.any { RtcProtocol.terminalEvent(body, it.call, repository.ownUid) }) {
            refreshRecovery()
            return
        }
        call?.let {
            if (!ending && !finished && !closing) {
                RtcProtocol.cameraEvent(body, it, repository.ownUid)?.let { (uid, state) ->
                    applyCameraStates(mapOf(uid to state))
                    return
                }
            }
            if (RtcProtocol.matchesEvent(body, it, repository.ownUid)) {
                wake()
                return
            }
        }
        val invitation = RtcProtocol.invitation(body, repository.ownUid) ?: return
        if (invitation.callId in shownCalls) return
        invitations[invitation.callId] = invitation
        if (invitations.size > 16) invitations.remove(invitations.keys.first())
        // CREATED 可能先于旧通话终态到达；保留邀请并唤醒旧呼叫复核。
        if (active) wake() else discover()
    }

    fun retry() {
        if (!canRetry) return
        retryRequested = true
        wake()
    }

    /** 立即停止本机媒体，恢复通话尚未启动轮询时也由原授权完成挂断。 */
    fun end() {
        if (!active || finished || ending) return
        endRequested = true
        startClosing()
        ending = true
        endingDeadline = scope.launch {
            delay(15_000.milliseconds)
            if (ending && !finished) {
                worker?.cancel()
                error = "本机音视频已关闭，服务端结束状态未确认"
                finish()
            }
        }
        if (awaitingPermission && (!incoming || target?.recovered == true)) {
            awaitingPermission = false
            finish()
        } else {
            awaitingPermission = false
            // Media stops immediately, even if the create/action response is still in flight.
            releaseMedia()
            val request = target
            if (call != null && request != null) {
                // 已知 callId 时取消在途查询/接听，立即提交挂断；服务端处理取消后仍提交的竞争。
                worker?.cancel()
                worker = scope.launch { runCall(request) }
            } else {
                // 创建结果未返回时保留原幂等请求，取得 callId 后再结束，不能另建呼叫。
                wake()
            }
        }
    }

    fun dismiss() {
        if (!finished) end() else startClosing()
    }

    fun toggleMicrophone() {
        if (!audioControlsEnabled) return
        microphoneEnabled = !microphoneEnabled
        audio?.enabled = microphoneEnabled
        directMedia?.setMicrophoneEnabled(microphoneEnabled)
        (room?.localParticipant?.trackPublications?.values?.firstOrNull { it.track === audio }
            as? LocalTrackPublication)?.muted = !microphoneEnabled
        room?.let(::refreshGroupSpeaking)
    }

    fun toggleSpeaker() {
        if (!audioControlsEnabled) return
        speakerEnabled = !speakerEnabled
        directMedia?.setSpeakerEnabled(speakerEnabled)
        room?.let(::refreshRemote)
    }

    /** 切换本机采集后唤醒串行状态上报；通知失败不能自动重新开启摄像头。 */
    fun toggleCamera() {
        if (groupCall) {
            if (!foreground || !joined || !sfuConnected || !controlsEnabled || cameraPermissionPending ||
                target?.mediaMode != RtcMediaMode.VIDEO) return
            if (cameraEnabled) {
                stopGroupCamera()
                wake()
            } else {
                cameraError = null
                cameraPermissionPending = true
            }
            return
        }
        if (!controlsEnabled) return
        try {
            val video = localVideo ?: return
            val enabled = !cameraEnabled
            if (enabled) video.startCapture() else video.stopCapture()
            video.enabled = enabled
            (room?.localParticipant?.trackPublications?.values?.firstOrNull { it.track === video }
                as? LocalTrackPublication)?.muted = !enabled
            cameraEnabled = enabled
            wake()
        } catch (_: Exception) {
            error = "摄像头切换失败，请重试"
        }
    }

    /** 相机授权只交付给发起申请的群通话；拒绝后继续原语音，不重新接听。 */
    fun cameraPermissionResult(request: RtcTarget, granted: Boolean) {
        if (disposed || target !== request || !cameraPermissionPending || closing || !joined) return
        RtcLogger.record(RtcAuditEvent.RTC_PERMISSION_RESULT, RtcLogStage.CAMERA_PERMISSION,
            call, request, repository.ownUid, granted = granted)
        cameraPermissionPending = false
        if (!granted) {
            cameraError = "未开启相机权限，继续语音通话"
            return
        }
        if (!foreground) return
        cameraEnabled = true
        try {
            // 已有麦克风服务需在前台升级类型，再建立相机采集。
            RtcCallOverlay.current?.takeIf { it.controller === this }?.service?.refreshMediaType()
        } catch (exception: Exception) {
            logRtc(RtcAuditEvent.RTC_OPERATION_FAILED, RtcLogStage.CAMERA_SERVICE, exception)
            cameraEnabled = false
            cameraError = "无法启用相机服务，继续语音通话"
            return
        }
        // 重新取得包含视频权限的 Token；保留原 callId、本人接听状态与计时。
        releaseMedia()
        wake()
    }

    /** 立即停止群相机采集和发送；席位释放由同一状态循环串行完成。 */
    private fun stopGroupCamera(message: String? = null) {
        videoLeaseJob?.cancel()
        cameraEnabled = false
        cameraError = message
        runCatching { localVideo?.enabled = false }
        runCatching { localVideo?.stopCapture() }
        (room?.localParticipant?.trackPublications?.values?.firstOrNull { it.track === localVideo }
            as? LocalTrackPublication)?.muted = true
        runCatching {
            RtcCallOverlay.current?.takeIf { it.controller === this }?.service?.refreshMediaType()
        }
    }

    /** 关闭后在既有状态循环释放席位，后续重新开启等待旧媒体清理完成。 */
    private suspend fun syncVideoSeat(latest: RtcCall) {
        if (!videoSeatHeld) return
        if (!cameraEnabled) {
            videoLeaseJob?.join()
            videoLeaseJob = null
            repository.videoEnabled(latest, false)
            videoSeatHeld = false
        }
    }

    /** 续租不依赖通话查询成功；无法确认席位时停止本机视频，继续原群语音。 */
    private fun startVideoLease(latest: RtcCall) {
        videoLeaseJob?.cancel()
        val owner = target
        videoLeaseJob = scope.launch {
            while (target === owner && !closing && videoSeatHeld && cameraEnabled) {
                delay(30_000L.milliseconds)
                if (target !== owner || closing || !videoSeatHeld || !cameraEnabled) return@launch
                try {
                    val allowed = withTimeout(5_000L.milliseconds) { repository.videoEnabled(latest, true) }
                    if (target !== owner || closing || !cameraEnabled) return@launch
                    if (!allowed) {
                        logRtc(RtcAuditEvent.RTC_VIDEO_SEAT_DENIED, RtcLogStage.RENEW_VIDEO_SEAT)
                        videoSeatHeld = false
                        stopGroupCamera("当前视频席位不可用，继续语音通话")
                        wake()
                    }
                } catch (exception: TimeoutCancellationException) {
                    logRtc(RtcAuditEvent.RTC_OPERATION_FAILED, RtcLogStage.RENEW_VIDEO_SEAT, exception)
                    stopGroupCamera("视频席位续期失败，继续语音通话")
                    wake()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    logRtc(RtcAuditEvent.RTC_OPERATION_FAILED, RtcLogStage.RENEW_VIDEO_SEAT, exception)
                    stopGroupCamera("视频席位续期失败，继续语音通话")
                    wake()
                }
            }
        }
    }

    /** 合入较新成员版本并更新摄像头遮罩；重复及乱序通知不倒退，不解绑轨道。 */
    private fun applyCameraStates(states: Map<String, RtcCameraState>) {
        if (disposed || ending || finished || closing) return
        states.forEach { (uid, state) ->
            // 保留每位成员的独立版本，自己的推送不能覆盖本机用户刚选择的开关。
            if (state.version > (cameraStates[uid]?.version ?: 0)) cameraStates[uid] = state
        }
        remoteCameraEnabled = cameraStates[target?.peerUid]?.enabled
        room?.let(::refreshRemote)
    }

    /** 随既有轮询补查和提交一次本人实际状态，迟到结果不得作用于下一通话。 */
    private suspend fun syncCameraState(latest: RtcCall, request: RtcTarget) {
        val snapshot = repository.media(latest)
        if (target !== request || disposed || ending || finished || closing) return
        applyCameraStates(snapshot.cameraStates)
        val own = snapshot.cameraStates[repository.ownUid]
        if (own?.enabled == cameraEnabled) return
        val updated = repository.camera(latest, cameraEnabled, own?.version ?: 0)
        if (target !== request || disposed || ending || finished || closing) return
        applyCameraStates(updated.cameraStates)
    }

    fun switchCamera() {
        if (!controlsEnabled || !cameraEnabled) return
        try {
            localVideo?.switchCamera()
            frontCamera = !frontCamera
        } catch (_: Exception) {
            error = "无法切换摄像头"
        }
    }

    /** 只接受当前通话的单调快照，计时须有本设备归属。 */
    private fun publish(next: RtcCall) {
        if (!RtcProtocol.newer(call, next)) return
        call = next
        if (next.group) {
            if (groupWaitingJob == null && !closing && !finished) {
                val request = target
                val remaining = (next.createdAt + 60_000L - System.currentTimeMillis()).coerceIn(0L, 60_000L)
                groupWaitingExpired = remaining == 0L
                groupWaitingJob = scope.launch {
                    delay(remaining.milliseconds)
                    if (target === request && !closing && !finished) groupWaitingExpired = true
                }
            }
            room?.let(::refreshRemote)
        }
        // Reset once on confirmed acceptance, independently of media connection or reconnection.
        if (next.acceptedBy(repository.ownUid) && next.deviceOwnership == RtcDeviceOwnership.CURRENT_DEVICE &&
            !acceptedTimerStarted && (!incoming || acceptAttempted) &&
            !disposed && !closing && !ending && !finished
        ) {
            acceptedTimerStarted = true
            timerStartedAt = monotonicNow()
            elapsedSeconds = 0
        }
    }

    /** 立即收起当前设备界面和媒体，不发挂断、不调用脱离窗口的结束补偿。 */
    private fun dismissOtherDevice(callId: String) {
        if (call?.callId != callId || finished) return
        Log.i("OpenChatRtc", "${RtcAuditEvent.RTC_POPUP_DISMISSED_ELSEWHERE.name} callId=$callId")
        finished = true
        ending = false
        answering = false
        awaitingPermission = false
        cameraPermissionPending = false
        acceptRequested = false
        endRequested = false
        closing = true
        windowClosed = true
        floating = false
        systemFloating = false
        closeDeadline?.cancel()
        endingDeadline?.cancel()
        worker?.cancel()
        groupWaitingJob?.cancel()
        releaseMedia()
        onTerminal()
        clearClosedTarget()
    }

    private fun finish(cleanup: Boolean = true) {
        if (finished) return
        startClosing()
        finished = true
        ending = false
        awaitingPermission = false
        answering = false
        endingDeadline?.cancel()
        endingDeadline = null
        groupWaitingJob?.cancel()
        releaseMedia()
        if (cleanup) call?.takeUnless { it.finishedFor(repository.ownUid) }?.let { cleanupDetached(it) }
        onTerminal()
        clearClosedTarget()
    }

    private fun releaseMedia() {
        cameraPermissionPending = false
        mediaReleaseListeners.toList().forEach { runCatching { it(false) } }
        val directCleanup = directMedia?.close()
        directMedia = null
        directRemoteVideo = null
        val pendingMedia = mediaJob
        val pendingLease = videoLeaseJob
        videoLeaseJob?.cancel()
        videoLeaseJob = null
        val seatCall = call?.takeIf { it.group && it.mediaMode == RtcMediaMode.VIDEO && videoSeatHeld }
        videoSeatHeld = false
        mediaJob?.cancel()
        mediaJob = null
        eventJob?.cancel()
        eventJob = null
        val current = room
        val video = localVideo
        val microphone = audio
        val egl = videoEgl
        room = null
        localVideo = null
        remoteVideo = null
        groupVideos = emptyMap()
        groupSpeakingUids = emptySet()
        audio = null
        videoEgl = null
        sfuConnecting = false
        sfuConnected = false
        mediaConnected = false
        runCatching { video?.enabled = false }
        runCatching { microphone?.enabled = false }
        current?.remoteParticipants?.values?.forEach { participant ->
            participant.trackPublications.values.forEach {
                runCatching { (it.track as? RemoteAudioTrack)?.setVolume(0.0) }
            }
        }
        val publishedVideo = videoPublished
        val publishedAudio = audioPublished
        videoPublished = false
        audioPublished = false
        if (current == null && video == null && microphone == null && directCleanup == null &&
            egl == null && seatCall == null) return
        val previousCleanup = mediaCleanup
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mediaCleanup = cleanup.launch {
            try {
                previousCleanup?.join()
                directCleanup?.join()
                runCatching { video?.stopCapture() }
                pendingMedia?.join()
                pendingLease?.join()
                // 清理完成前不允许下一次领取，避免旧释放请求移除新视频席位。
                if (seatCall != null) withContext(Dispatchers.Main.immediate) {
                    withTimeoutOrNull(3_000L.milliseconds) { runCatching { repository.videoEnabled(seatCall, false) } }
                }
                // Include publications committed just before cancellation resumed the publishing coroutine.
                val owned = current?.localParticipant?.trackPublications?.values?.mapNotNull { it.track }.orEmpty()
                if (!publishedVideo && owned.none { it === video }) {
                    runCatching { video?.stop() }
                    runCatching { video?.dispose() }
                }
                if (!publishedAudio && owned.none { it === microphone }) {
                    runCatching { microphone?.stop() }
                    runCatching { microphone?.dispose() }
                }
                runCatching { current?.release() }
                runCatching { egl?.release() }
            } finally {
                cleanup.cancel()
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (dialSubmitted && !dialBusy) dialGroupCall?.let {
            cleanupDetached(it, allowAccepted = true, onlyAccepted = true)
        }
        dialTarget = null
        dialCallIds = null
        dialAwaitingPermission = false
        dialError = null
        dialGroupCall = null
        dialGroupAcceptRequest = null
        dialSelectMembers = null
        RtcCallOverlay.release(this)
        backgroundOwner = null
        val pending = call?.takeUnless { it.state.terminal || finished }
        scope.cancel()
        releaseMedia()
        mediaReleaseListeners.clear()
        target = null
        wake.close()
        invitations.clear()
        pending?.let { cleanupDetached(it) }
    }

    private fun cleanupDetached(
        pending: RtcCall,
        allowAccepted: Boolean = pending.callerUid == repository.ownUid || acceptAttempted || endRequested,
        onlyAccepted: Boolean = false,
    ) {
        if (detachedCallId != pending.callId) {
            detachedCallId = pending.callId
            val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            cleanup.launch {
                try {
                    withTimeoutOrNull(12_000.milliseconds) {
                        repository.finishDetached(pending, allowAccepted, onlyAccepted)
                    }
                } catch (_: Exception) {
                    // Authorization may already be revoked; never reuse the next account or restore capture.
                } finally {
                    cleanup.cancel()
                }
            }
        }
    }
}

internal fun rtcStatus(
    call: RtcCall?, ending: Boolean, finished: Boolean, connected: Boolean,
    incoming: Boolean = false, mediaMode: RtcMediaMode = RtcMediaMode.VIDEO,
): String = when {
    ending -> "正在结束通话"
    call?.state == RtcState.REJECTED -> if (incoming) "已拒绝" else "对方已拒绝"
    call?.state == RtcState.EXPIRED -> if (incoming) "来电已超时" else "对方无响应"
    call?.state == RtcState.CANCELED -> if (incoming) "对方已取消" else "已取消"
    call?.state == RtcState.ENDED -> "通话已结束"
    finished -> "通话已关闭"
    connected -> if (mediaMode == RtcMediaMode.AUDIO) "语音通话" else "视频通话"
    call?.accepted == true -> "正在连接"
    call == null -> if (mediaMode == RtcMediaMode.AUDIO) "正在发起语音通话" else "正在发起视频通话"
    incoming -> if (mediaMode == RtcMediaMode.AUDIO) "邀请你语音通话" else "邀请你视频通话"
    else -> "等待对方接受邀请"
}
