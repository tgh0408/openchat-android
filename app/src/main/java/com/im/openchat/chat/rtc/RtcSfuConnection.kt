package com.im.openchat.chat.rtc

import io.livekit.android.room.Room
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Publishing can finish before ICE connects. The caller owns the existing connection timeout. */
internal suspend fun awaitRtcSfuConnected(
    state: () -> Room.State,
    isCurrent: () -> Boolean,
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("RTC media session changed")
        when (state()) {
            Room.State.CONNECTED -> return
            Room.State.DISCONNECTED -> throw IOException("SFU disconnected before media became ready")
            Room.State.CONNECTING, Room.State.RECONNECTING -> delay(50)
        }
    }
}
