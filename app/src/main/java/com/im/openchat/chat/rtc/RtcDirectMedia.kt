package com.im.openchat.chat.rtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioFocusHandler
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 在独立串行线程上维护单聊音视频 P2P，借用上层视频采集，HTTP 授权和 UI 回调仍回主线程。
 * @param onFallback 仅服务端确认 SFU 且直连资源释放后执行；失败保留 SFU 意图重试。
 */
internal class RtcDirectMedia(
    private val context: Context,
    private val repository: RtcRepository,
    private val call: RtcCall,
    private val ownUid: String,
    private val onConnected: (Boolean) -> Unit,
    private val onFallback: suspend () -> Unit,
    private val onError: () -> Unit,
    private val video: VideoTrack? = null,
    private val eglContext: EglBase.Context? = null,
    private val onRemoteVideo: (RtcDirectVideoSource?) -> Unit = {},
) {
    private val callbacks = CoroutineScope(SupervisorJob() + dispatcher)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false
    @Volatile private var switched = false
    private var fallbackRequested = false
    private var sfuConfirmed = false
    private var connected = false
    private var everConnected = false
    private var disconnectedAt = 0L
    private var revision = 0L
    @Volatile private var generation = 0
    private var microphoneEnabled = true
    private var speakerEnabled = true
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private var source: AudioSource? = null
    private var local: AudioTrack? = null
    private var peer: PeerConnection? = null
    private var videoTransceiver: RtpTransceiver? = null
    private var remoteVideo: VideoTrack? = null
    private var remoteVideoId: String? = null
    private var remoteVideoSource: RtcDirectVideoSource? = null
    private val offerId = rtcRequestId()
    private var pending: PendingSignal? = null
    private val quality = RtcMediaQuality()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusHandler(context).apply {
        audioStreamType = AudioManager.STREAM_VOICE_CALL
        audioAttributeUsageType = AudioAttributes.USAGE_VOICE_COMMUNICATION
    }
    private var routeActive = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var cleanup: Job? = null

    /** 串行重读媒体状态；丢失的请求响应不丢弃同一协商及其超时起点。 */
    suspend fun start() = withContext(dispatcher) {
        val started = SystemClock.elapsedRealtime()
        try {
            while (!closed && !switched) {
                try {
                    synchronize(started)
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    fallbackRequested = true
                    reportError()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: LinkageError) {
                    fallbackRequested = true
                    reportError()
                } catch (_: Exception) {
                    if (peer != null && pending == null &&
                        peer?.signalingState() != PeerConnection.SignalingState.STABLE
                    ) fallbackRequested = true
                    reportError()
                }
                if (!closed && !switched) withTimeoutOrNull(2_000L.milliseconds) { wake.receive() }
            }
        } finally {
            withContext(NonCancellable + dispatcher) {
                closePeer()
                releaseAudioRoute()
                callbacks.cancel()
            }
        }
    }

    /** 唤醒同一串行媒体查询，用于 SSE、恢复前台和手动重试。 */
    fun refresh() {
        if (!closed && !switched) wake.trySend(Unit)
    }

    /** 立即作废回调，排队释放 native 资源；返回清理任务供下一通话等待。 */
    fun close(): Job {
        cleanup?.let { return it }
        closed = true
        wake.trySend(Unit)
        return CoroutineScope(SupervisorJob() + dispatcher).let { cleanupScope ->
            cleanupScope.launch {
                try {
                    closePeer()
                    releaseAudioRoute()
                    callbacks.cancel()
                } finally {
                    cleanupScope.cancel()
                }
            }.also { cleanup = it }
        }
    }

    /** 记住静音状态，之后创建或替换的音轨也遵守同一状态。 */
    fun setMicrophoneEnabled(enabled: Boolean) {
        callbacks.launch {
            if (closed) return@launch
            microphoneEnabled = enabled
            local?.setEnabled(enabled)
            peer?.setAudioRecording(enabled)
        }
    }

    /** 控制当前连接整体播放，包括后续才到达的远端音轨。 */
    fun setSpeakerEnabled(enabled: Boolean) {
        callbacks.launch {
            if (closed) return@launch
            speakerEnabled = enabled
            peer?.setAudioPlayout(enabled)
        }
    }

    private suspend fun synchronize(started: Long) {
        // Once confirmed, even a failed SFU attempt cannot reopen a P2P factory or apply a stale SDP.
        if (sfuConfirmed) {
            switchToSfu()
            return
        }
        var view = withContext(Dispatchers.Main.immediate) {
            ensureOpen()
            repository.media(call)
        }
        if (closed || view.version < revision) return
        revision = view.version
        if (view.transport == RtcTransport.SFU) {
            switchToSfu()
            return
        }
        sampleQuality()
        val now = SystemClock.elapsedRealtime()
        if (fallbackRequested || !everConnected && now - started > 18_000L ||
            disconnectedAt != 0L && now - disconnectedAt > 6_000L
        ) {
            fallbackRequested = true
            view = signal("FALLBACK")
            if (!closed && view.transport == RtcTransport.SFU) switchToSfu()
            return
        }
        if (closed) return
        val current = peer ?: try {
            createPeer(view)
        } catch (exception: Exception) {
            fallbackRequested = true
            throw exception
        }
        pending?.let {
            view = signal(it.kind, it.offerId, it.sdp)
            if (closed) return
            pending = null
            revision = maxOf(revision, view.version)
            if (view.transport == RtcTransport.SFU) {
                switchToSfu()
                return
            }
        }
        val answer = view.answerSdp
        val offer = view.offerSdp
        if (ownUid == call.callerUid) {
            if (view.offerId != null && view.offerId != offerId) {
                fallbackRequested = true
                refresh()
                return
            }
            if (view.offerId == null && current.localDescription == null) {
                val description = createDescription { current.createOffer(it, MediaConstraints()) }
                setDescription { current.setLocalDescription(it, description) }
                gather(current)
                if (closed) return
                pending = PendingSignal("OFFER", null, requireNotNull(current.localDescription).description)
            } else if (answer != null && current.remoteDescription == null) {
                setDescription { current.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
                if (closed) return
                if (call.mediaMode == RtcMediaMode.VIDEO &&
                    videoTransceiver?.let {
                        !it.isStopped && it.currentDirection == RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    } != true
                ) {
                    fallbackRequested = true
                    refresh()
                    return
                }
            }
        } else if (offer != null && view.offerId != null && current.remoteDescription == null) {
            if (answer != null) {
                fallbackRequested = true
                refresh()
                return
            }
            setDescription { current.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, offer)) }
            if (closed) return
            // 被叫绑定 Offer 已有的媒体段，避免 Answer 中出现不能发送的新通道。
            // Android getTransceivers() disposes earlier wrappers, unlike the browser API.
            val offeredTransceivers = current.transceivers
            val transceiver = offeredTransceivers.firstOrNull {
                !it.isStopped && it.mid != null && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            } ?: error("对方缺少音频协商通道")
            check(transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            check(transceiver.sender.setTrack(createLocalTrack(), false))
            if (call.mediaMode == RtcMediaMode.VIDEO) {
                val offeredVideo = offeredTransceivers.firstOrNull {
                    !it.isStopped && it.mid != null && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                } ?: error("对方缺少视频协商通道")
                check(offeredVideo.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
                videoTransceiver = offeredVideo
                check(offeredVideo.sender.setTrack(requireNotNull(video), false))
                applyVideoQuality()
            }
            val description = createDescription { current.createAnswer(it, MediaConstraints()) }
            setDescription { current.setLocalDescription(it, description) }
            gather(current)
            if (closed) return
            pending = PendingSignal("ANSWER", view.offerId, requireNotNull(current.localDescription).description)
        }
        updateRemoteVideo(generation)
        pending?.let {
            val result = signal(it.kind, it.offerId, it.sdp)
            if (closed) return
            pending = null
            revision = maxOf(revision, result.version)
            if (result.transport == RtcTransport.SFU) switchToSfu()
        }
    }

    private suspend fun signal(kind: String, answerTo: String? = null, sdp: String? = null): RtcMediaView =
        withContext(Dispatchers.Main.immediate) {
            ensureOpen()
            repository.signalMedia(call, kind, offerId, answerTo, sdp).also {
                check(it.version >= revision) { "媒体响应版本已过期" }
            }
        }

    private fun createPeer(view: RtcMediaView): PeerConnection {
        if (call.mediaMode == RtcMediaMode.VIDEO) {
            requireNotNull(video) { "本机视频尚未准备" }
            requireNotNull(eglContext) { "视频渲染环境尚未准备" }
        }
        configureAudioRoute()
        // 使用 SDK 的初始化入口，保持 prefixed native 库及全局初始化一致。
        LiveKit.init(context)
        if (factory == null) {
            if (module == null) module = JavaAudioDeviceModule.builder(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(requireNotNull(module))
                .apply {
                    if (call.mediaMode == RtcMediaMode.VIDEO) {
                        setVideoEncoderFactory(DefaultVideoEncoderFactory(requireNotNull(eglContext), true, false))
                        setVideoDecoderFactory(DefaultVideoDecoderFactory(requireNotNull(eglContext)))
                    }
                }.createPeerConnectionFactory()
        }
        val configuration = PeerConnection.RTCConfiguration(
            view.stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val owner = ++generation
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                callbacks.launch {
                    if (closed || generation != owner) return@launch
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            connected = true
                            everConnected = true
                            disconnectedAt = 0L
                            applyVideoQuality()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            connected = false
                            if (disconnectedAt == 0L) disconnectedAt = SystemClock.elapsedRealtime()
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            connected = false
                            fallbackRequested = true
                        }
                        else -> return@launch
                    }
                    val available = connected
                    withContext(Dispatchers.Main.immediate) {
                        if (!closed && generation == owner) onConnected(available)
                    }
                    refresh()
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onTrack(transceiver: RtpTransceiver) {
                callbacks.launch { updateRemoteVideo(owner) }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                callbacks.launch { updateRemoteVideo(owner) }
            }
            override fun onRemoveTrack(receiver: RtpReceiver) {
                callbacks.launch { updateRemoteVideo(owner) }
            }
        }
        return requireNotNull(factory).createPeerConnection(configuration, observer)
            .let { requireNotNull(it) }.also {
                peer = it
                it.setAudioPlayout(speakerEnabled)
                it.setAudioRecording(microphoneEnabled)
                if (ownUid == call.callerUid) {
                    checkNotNull(it.addTransceiver(createLocalTrack()))
                    if (call.mediaMode == RtcMediaMode.VIDEO) {
                        videoTransceiver = checkNotNull(it.addTransceiver(requireNotNull(video)))
                        applyVideoQuality()
                    }
                }
            }
    }

    private fun createLocalTrack(): AudioTrack {
        local?.let { return it }
        val currentFactory = requireNotNull(factory)
        val audioSource = currentFactory.createAudioSource(MediaConstraints())
        source = audioSource
        return currentFactory.createAudioTrack("openchat-audio", audioSource).also {
            local = it
            it.setEnabled(microphoneEnabled)
        }
    }

    private suspend fun sampleQuality() {
        val current = peer ?: return
        if (!connected || fallbackRequested) return
        // 统计不可用不视为劣化；保持 native 拥塞控制继续运行。
        val report = try {
            withTimeoutOrNull(1_500L.milliseconds) {
                suspendCancellableCoroutine<RTCStatsReport> { continuation ->
                    current.getStats { if (continuation.isActive) continuation.resume(it) }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) { null }
        if (closed || report == null || peer !== current) return
        if (quality.shouldFallback(
                report.statsMap.values.map { RtcMediaStat(it.id, it.type, it.members) },
                videoEnabled = video?.enabled() == true,
            )
        ) {
            fallbackRequested = true
        }
        applyVideoQuality()
    }

    private fun applyVideoQuality() {
        val sender = videoTransceiver?.sender ?: return
        if (closed || fallbackRequested) return
        // Parameter support varies by device; rejection is not evidence of a bad network.
        runCatching {
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) return@runCatching
            val level = RTC_VIDEO_QUALITY_LEVELS[quality.videoLevel]
            parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            parameters.encodings.forEach {
                it.maxBitrateBps = level.bitrate
                it.maxFramerate = level.frameRate
                it.scaleResolutionDownBy = level.scale
            }
            sender.setParameters(parameters)
        }
    }

    private suspend fun updateRemoteVideo(owner: Int) {
        if (closed || generation != owner || sfuConfirmed || call.mediaMode != RtcMediaMode.VIDEO) return
        // Callback wrappers may be temporary. Keep only the peer-owned negotiation wrapper.
        val transceiver = videoTransceiver ?: return
        if (transceiver.currentDirection == null) return
        val track = transceiver.receiver.track() as? VideoTrack ?: return
        if (transceiver.isStopped || track.state() == MediaStreamTrack.State.ENDED) {
            if (remoteVideoSource == null) return
            detachRemoteVideo()
            withContext(Dispatchers.Main.immediate) {
                if (!closed && generation == owner) onRemoteVideo(null)
            }
            return
        }
        val trackId = track.id()
        if (remoteVideoId == trackId) return
        detachRemoteVideo()
        val source = RtcDirectVideoSource()
        remoteVideo = track
        remoteVideoId = trackId
        remoteVideoSource = source
        track.addSink(source)
        withContext(Dispatchers.Main.immediate) {
            if (!closed && generation == owner) onRemoteVideo(source)
        }
    }

    private fun detachRemoteVideo() {
        remoteVideoSource?.let { source ->
            source.close()
            runCatching { remoteVideo?.removeSink(source) }
        }
        remoteVideoSource = null
        remoteVideo = null
        remoteVideoId = null
    }

    private fun closePeer() {
        generation++
        detachRemoteVideo()
        videoTransceiver = null
        runCatching { local?.setEnabled(false) }
        runCatching { peer?.setAudioPlayout(false) }
        runCatching { peer?.setAudioRecording(false) }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        runCatching { local?.dispose() }
        local = null
        runCatching { source?.dispose() }
        source = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { module?.release() }
        module = null
        connected = false
        pending = null
    }

    private suspend fun switchToSfu() {
        if (closed || switched) return
        sfuConfirmed = true
        fallbackRequested = true
        closePeer()
        releaseAudioRoute()
        withContext(Dispatchers.Main.immediate) {
            if (!closed) {
                onRemoteVideo(null)
                onConnected(false)
                onFallback()
            }
        }
        if (!closed) switched = true
    }

    private suspend fun gather(current: PeerConnection) {
        withTimeoutOrNull(3_000L.milliseconds) {
            while (!closed && current.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) delay(
                50L.milliseconds
            )
        }
    }

    private suspend fun createDescription(action: (SdpObserver) -> Unit): SessionDescription =
        withTimeout(5_000L.milliseconds) {
            ensureOpen()
            suspendCancellableCoroutine { continuation ->
                action(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (continuation.isActive) continuation.resume(description)
                    }
                    override fun onSetSuccess() = Unit
                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SDP 创建失败"))
                    }
                    override fun onSetFailure(error: String) = onCreateFailure(error)
                })
            }
        }

    private suspend fun setDescription(action: (SdpObserver) -> Unit) {
        withTimeout(5_000L.milliseconds) {
            ensureOpen()
            suspendCancellableCoroutine { continuation ->
                action(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = Unit
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SDP 设置失败"))
                    }
                    override fun onSetFailure(error: String) = onCreateFailure(error)
                })
            }
        }
    }

    private fun configureAudioRoute() {
        if (routeActive) return
        previousMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        routeActive = true
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        focus.start()
    }

    private fun releaseAudioRoute() {
        if (!routeActive) return
        routeActive = false
        runCatching { focus.stop() }
        runCatching { audioManager?.mode = previousMode }
    }

    private fun ensureOpen() {
        if (closed) throw CancellationException("通话已关闭")
    }

    private suspend fun reportError() = withContext(Dispatchers.Main.immediate) {
        if (!closed) onError()
    }

    private data class PendingSignal(val kind: String, val offerId: String?, val sdp: String)

    private companion object {
        // 整个进程复用同一 native 调用线程，创建和销毁 factory 不跨线程。
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenChat-P2P").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
