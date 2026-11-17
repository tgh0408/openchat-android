package com.im.openchat.chat.rtc

/** WebRTC 标准统计的只读投影，不保留 SDP 或网络地址。 */
internal data class RtcMediaStat(val id: String, val type: String, val members: Map<String, Any?>)

internal data class RtcVideoQualityLevel(val bitrate: Int, val frameRate: Int, val scale: Double)

internal val RTC_VIDEO_QUALITY_LEVELS = listOf(
    RtcVideoQualityLevel(120_000, 10, 4.0),
    RtcVideoQualityLevel(350_000, 15, 2.0),
    RtcVideoQualityLevel(900_000, 24, 1.5),
    RtcVideoQualityLevel(1_800_000, 30, 1.0),
)

/** 对齐 PC：视频快降慢升，最低档持续严重劣化才中转；语音不经过视频降档。 */
internal class RtcMediaQuality {
    private val previous = mutableMapOf<String, Pair<Double, Double>>()
    private var severe = 0
    private var poor = 0
    private var good = 0
    var videoLevel = 2
        private set

    /** 按选中候选对及增量丢包计算回退；缺少统计不冒充坏网络。 */
    fun shouldFallback(stats: Collection<RtcMediaStat>, videoEnabled: Boolean = false): Boolean {
        val pairId = stats.firstNotNullOfOrNull {
            if (it.type == "transport") it.members["selectedCandidatePairId"] as? String else null
        }
        var rtt = 0.0
        var loss = 0.0
        var jitter = 0.0
        var bitrate: Double? = null
        var measured = false
        val seen = mutableSetOf<String>()
        stats.forEach { stat ->
            fun number(key: String) = (stat.members[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
            when (stat.type) {
                "candidate-pair" -> if (stat.id == pairId) {
                    number("currentRoundTripTime")?.let { rtt = maxOf(rtt, it); measured = true }
                    bitrate = number("availableOutgoingBitrate")
                }
                "remote-inbound-rtp" -> {
                    number("roundTripTime")?.let { rtt = maxOf(rtt, it); measured = true }
                    number("fractionLost")?.let { loss = maxOf(loss, it) }
                }
                "inbound-rtp" -> {
                    number("jitter")?.let { jitter = maxOf(jitter, it) }
                    val received = number("packetsReceived") ?: return@forEach
                    val lost = number("packetsLost") ?: return@forEach
                    seen += stat.id
                    val old = previous.put(stat.id, received to lost) ?: return@forEach
                    if (received < old.first) return@forEach
                    val receivedDelta = received - old.first
                    val lostDelta = (lost - old.second).coerceAtLeast(0.0)
                    if (receivedDelta + lostDelta > 0) {
                        loss = maxOf(loss, lostDelta / (receivedDelta + lostDelta))
                        measured = true
                    }
                }
            }
        }
        previous.keys.retainAll(seen)
        if (!measured) return false
        val lowBandwidth = videoEnabled &&
            bitrate?.let { it < RTC_VIDEO_QUALITY_LEVELS[videoLevel].bitrate * 0.8 } == true
        val bad = rtt > 0.5 || loss > 0.05 || jitter > 0.06 || lowBandwidth
        val unusable = rtt > 1.2 || loss > 0.2 || jitter > 0.15 || bitrate?.let { it < 80_000 } == true
        poor = if (bad) poor + 1 else 0
        severe = if (unusable && (!videoEnabled || videoLevel == 0)) severe + 1 else 0
        val availableBitrate = bitrate
        val healthy = rtt < 0.25 && loss < 0.02 && jitter < 0.03 &&
            (availableBitrate == null ||
                availableBitrate > RTC_VIDEO_QUALITY_LEVELS[minOf(3, videoLevel + 1)].bitrate * 1.3)
        good = if (healthy) good + 1 else 0
        if (videoEnabled && poor >= 2 && videoLevel > 0) {
            videoLevel--
            poor = 0
            good = 0
        } else if (videoEnabled && good >= 10 && videoLevel < 3) {
            videoLevel++
            good = 0
        }
        return severe >= 3
    }
}
