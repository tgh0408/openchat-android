package com.im.openchat.chat.rtc

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.im.openchat.MainActivity
import com.im.openchat.R
import com.im.openchat.common.ui.theme.OpenChatTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Holds the current authorized call across Activity stops. No incoming-call discovery or restart. */
class RtcOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: RtcOverlaySession? = null
    private var observer: Job? = null
    private var permissionObserver: Job? = null
    private var callNotification: Notification? = null
    private var window: ComposeView? = null
    private var windowOwner: RtcOverlayViewOwner? = null
    private var layout: WindowManager.LayoutParams? = null
    private var callbackContext: Context? = null
    private val windowCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { refitWindow() }
        override fun onLowMemory() = Unit
    }
    private val windowContext by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displays = requireNotNull(getSystemService(DisplayManager::class.java))
            val display = requireNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
            createDisplayContext(display).createWindowContext(overlayType(), null).also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    it.registerComponentCallbacks(windowCallbacks)
                    callbackContext = it
                }
            }
        } else this
    }
    private val manager get() = requireNotNull(windowContext.getSystemService(WindowManager::class.java))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val candidate = RtcCallOverlay.current
        if (candidate == null || intent == null ||
            intent.getStringExtra(REQUEST) != candidate.target.requestId
        ) {
            if (session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!candidate.valid) {
            if (!candidate.controller.authorized) candidate.controller.dispose() else candidate.close()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == END) {
            candidate.controller.end()
            return START_NOT_STICKY
        }
        if (intent.action != START || session === candidate) return START_NOT_STICKY
        if (!candidate.interactive || (!candidate.controller.isForeground && !candidate.starting)) {
            candidate.close()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // START was requested while the Activity was visible; delivery may follow ON_STOP.
        session?.close()
        session = candidate
        candidate.service = this
        try {
            val notifications = requireNotNull(getSystemService(NotificationManager::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notifications.createNotificationChannel(NotificationChannel(
                    CHANNEL, if (candidate.controller.audioCall) "悬浮语音通话" else "悬浮视频通话",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setSound(null, null) })
            }
            val restore = PendingIntent.getActivity(this, 0, restoreIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val end = PendingIntent.getService(this, 1,
                candidate.intent(END).setData(android.net.Uri.parse("openchat-rtc:${candidate.target.requestId}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_chat_video_call)
                .setContentTitle(when {
                    candidate.controller.groupCall -> "群通话进行中"
                    candidate.controller.audioCall -> "语音通话进行中"
                    else -> "视频通话进行中"
                })
                .setContentText("点击返回通话")
                .setContentIntent(restore)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(R.drawable.ic_rtc_hangup, "挂断", end)
                .build()
            callNotification = notification
            refreshMediaType()
            candidate.controller.setBackgroundMedia(candidate.target, true)
            if (!candidate.controller.canContinueInBackground) {
                candidate.close()
                return START_NOT_STICKY
            }
            observer = scope.launch {
                // The captured authorization reads Compose state, but this observer needs no Activity frames.
                snapshotFlow { candidate.valid }.first { !it }
                if (!candidate.controller.authorized) candidate.controller.dispose() else candidate.close()
            }
            permissionObserver = scope.launch {
                while (candidate.valid) {
                    delay(500)
                    if (window != null && !Settings.canDrawOverlays(this@RtcOverlayService)) {
                        candidate.fail("悬浮窗权限已关闭", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        break
                    }
                }
            }
            candidate.serviceReady()
        } catch (_: SecurityException) {
            candidate.fail(
                if (candidate.controller.audioCall) "请检查麦克风权限" else "请检查相机和麦克风权限",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            )
        } catch (_: Exception) {
            candidate.fail("系统未允许悬浮通话", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
        return START_NOT_STICKY
    }

    /** 群成员主动开启相机后更新前台服务类型；权限不足由调用方保留语音并提示。 */
    internal fun refreshMediaType() {
        val current = session ?: return
        val notification = callNotification ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION, notification,
                if (current.controller.audioCall) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else startForeground(NOTIFICATION, notification)
    }

    internal fun showWindow(owner: RtcOverlaySession): Boolean {
        if (session !== owner || !owner.interactive || !Settings.canDrawOverlays(this)) return false
        if (window != null) return true
        val viewOwner = RtcOverlayViewOwner()
        val params = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "OpenChat video call"
        }
        layout = params
        fitWindow(initial = true)
        val view = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OpenChatTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                        .testTag("rtc_system_floating_window")
                        .pointerInput(owner.controller.closing) {
                            if (!owner.controller.closing) detectDragGestures { change, amount ->
                                change.consume()
                                moveWindow(amount.x, amount.y)
                            }
                        }) {
                        RtcVideoScreen(owner.controller, owner.target, owner.avatars, compact = true,
                            onRestore = { restore(owner) })
                    }
                }
            }
        }
        window = view
        windowOwner = viewOwner
        return try {
            manager.addView(view, params)
            owner.controller.minimize(systemWindow = true)
            true
        } catch (_: Exception) {
            removeWindow()
            false
        }
    }

    private fun restore(owner: RtcOverlaySession) {
        if (!owner.interactive) return
        if (owner.controller.isForeground) owner.restore()
        else {
            owner.restoreRequested = true
            try { startActivity(restoreIntent()) } catch (_: Exception) {
                owner.restoreRequested = false
                android.widget.Toast.makeText(this, "请从通知或桌面返回 OpenChat", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .setAction(RESTORE)
        .setData(android.net.Uri.parse("openchat-rtc-restore:${session?.target?.requestId}"))
        .putExtra(REQUEST, session?.target?.requestId)

    private fun moveWindow(dx: Float, dy: Float) {
        val params = layout ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        refitWindow()
    }

    private fun fitWindow(initial: Boolean) {
        val params = layout ?: return
        val density = windowContext.resources.displayMetrics.density
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            android.graphics.Rect(0, 0,
                metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { manager.defaultDisplay.getSize(it) }
            android.graphics.Rect(0, 0, size.x, size.y)
        }
        val dimensions = rtcOverlayBounds(bounds.width(), bounds.height(), density, params.x, params.y, initial)
        params.width = dimensions.width
        params.height = dimensions.height
        params.x = dimensions.x
        params.y = dimensions.y
    }

    private fun updateWindow() {
        val view = window ?: return
        try { manager.updateViewLayout(view, layout) } catch (_: Exception) {
            session?.fail("悬浮窗已不可用")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refitWindow()
    }

    private fun refitWindow() {
        try {
            fitWindow(initial = false)
            updateWindow()
        } catch (_: Exception) {
            session?.fail("无法更新悬浮窗位置")
        }
    }

    internal fun detach(owner: RtcOverlaySession) {
        if (session !== owner) return
        session = null
        observer?.cancel()
        permissionObserver?.cancel()
        removeWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    internal fun hideWindow(owner: RtcOverlaySession) {
        if (session === owner) removeWindow()
    }

    private fun removeWindow() {
        val old = window
        window = null
        old?.disposeComposition()
        windowOwner?.destroy()
        windowOwner = null
        if (old != null) runCatching { manager.removeViewImmediate(old) }
        layout = null
    }

    override fun onDestroy() {
        session?.close()
        removeWindow()
        callbackContext?.unregisterComponentCallbacks(windowCallbacks)
        callbackContext = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val START = "com.im.openchat.rtc.START_OVERLAY"
        internal const val END = "com.im.openchat.rtc.END_OVERLAY"
        internal const val RESTORE = "com.im.openchat.rtc.RESTORE_OVERLAY"
        internal const val REQUEST = "rtc_request_id"
        private const val CHANNEL = "rtc_video_overlay"
        private const val NOTIFICATION = 4107

        @Suppress("DEPRECATION")
        private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else WindowManager.LayoutParams.TYPE_PHONE
    }
}

private class RtcOverlayViewOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

internal data class RtcOverlayBounds(val width: Int, val height: Int, val x: Int, val y: Int)

internal fun rtcOverlayBounds(
    availableWidth: Int, availableHeight: Int, density: Float, x: Int, y: Int, initial: Boolean,
): RtcOverlayBounds {
    val width = (105 * density).roundToInt().coerceIn(1, availableWidth.coerceAtLeast(1))
    val height = (161 * density).roundToInt().coerceIn(1, availableHeight.coerceAtLeast(1))
    val limitX = (availableWidth - width).coerceAtLeast(0)
    val limitY = (availableHeight - height).coerceAtLeast(0)
    val inset = (8 * density).roundToInt()
    return RtcOverlayBounds(width, height,
        (if (initial) limitX - inset else x).coerceIn(0, limitX),
        (if (initial) inset else y).coerceIn(0, limitY))
}
