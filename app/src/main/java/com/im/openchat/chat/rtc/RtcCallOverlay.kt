package com.im.openchat.chat.rtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import com.im.openchat.common.data.AvatarRepository

/** An ephemeral handoff, never enough to recreate a call after process death. Main thread only. */
internal object RtcCallOverlay {
    var current by mutableStateOf<RtcOverlaySession?>(null)
        private set

    fun start(
        context: Context, controller: RtcVideoController, avatars: AvatarRepository?,
        automatic: Boolean = false,
        onReady: (RtcOverlaySession) -> Unit,
    ) {
        val target = controller.target ?: return
        if (!controller.canStartBackgroundMedia || !controller.isForeground) return
        current?.let { existing ->
            if (!automatic && existing.controller === controller && existing.target === target && existing.interactive) {
                existing.automatic = false
                if (existing.starting) existing.onReady = onReady else onReady(existing)
            }
            return
        }
        val session = RtcOverlaySession(context.applicationContext, controller, target, avatars, onReady, automatic)
        current = session
        session.awaitService()
        try {
            ContextCompat.startForegroundService(context, session.intent(RtcOverlayService.START))
        } catch (_: SecurityException) {
            session.fail(
                if (controller.audioCall) "请检查麦克风权限" else "请检查相机和麦克风权限",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            )
        } catch (_: Exception) {
            session.fail("无法启动悬浮通话", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
    }

    fun clear(session: RtcOverlaySession) {
        if (current === session) current = null
    }

    /** 按控制器及可选通话归属释放服务，旧页面卸载不能关闭新通话的服务。 */
    fun release(controller: RtcVideoController, target: RtcTarget? = null) {
        current?.takeIf { it.controller === controller && (target == null || it.target === target) }?.close()
    }

    fun isStarting(controller: RtcVideoController): Boolean =
        current?.let { it.controller === controller && it.interactive && it.starting } == true

    fun prepareVideo(context: Context, controller: RtcVideoController, avatars: AvatarRepository?) {
        if (controller.audioCall || !controller.canStartBackgroundMedia || !controller.isForeground) return
        if (listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }) return
        start(context, controller, avatars, automatic = true) {}
    }

    fun onBackground(controller: RtcVideoController) {
        current?.takeIf { it.controller === controller }?.onBackground()
    }
}

internal class RtcOverlaySession(
    val context: Context,
    val controller: RtcVideoController,
    val target: RtcTarget,
    val avatars: AvatarRepository?,
    var onReady: ((RtcOverlaySession) -> Unit)?,
    var automatic: Boolean = false,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val startTimeout = Runnable { fail("悬浮通话服务启动超时") }
    var starting = true
        private set
    var service: RtcOverlayService? = null
    var waitingPermission = false
    var restoreRequested by mutableStateOf(false)
    val valid: Boolean get() = RtcCallOverlay.current === this &&
        controller.target === target && controller.windowVisible && controller.authorized
    val interactive: Boolean get() = valid && !controller.closing

    fun intent(action: String) = Intent(context, RtcOverlayService::class.java)
        .setAction(action).putExtra(RtcOverlayService.REQUEST, target.requestId)

    fun awaitService() {
        handler.postDelayed(startTimeout, 5_000L)
    }

    fun serviceReady() {
        if (!interactive || !starting) return
        starting = false
        handler.removeCallbacks(startTimeout)
        val ready = onReady
        onReady = null
        ready?.invoke(this)
        if (!controller.isForeground) onBackground()
    }

    fun onBackground() {
        if (!interactive || waitingPermission || controller.awaitingPermission || controller.cameraPermissionPending) return
        // Freeze the current main-video choice before the Activity stops producing frames.
        controller.minimize(systemWindow = controller.systemFloating)
        if (service != null && Settings.canDrawOverlays(context)) show()
    }

    fun show() {
        if (!interactive) return
        waitingPermission = false
        val shown = try { service?.showWindow(this) == true } catch (_: Exception) { false }
        if (!shown) fail("无法显示悬浮窗", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }

    fun restore() {
        if (!interactive || !controller.isForeground) return
        restoreRequested = false
        controller.restore()
        if (controller.audioCall) close() else service?.hideWindow(this)
    }

    fun fail(message: String, settingsAction: String? = null) {
        val openSettings = !automatic && interactive && controller.isForeground && settingsAction != null
        if (valid) {
            if (settingsAction == null || automatic) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (controller.isForeground) controller.restore()
        }
        close()
        if (openSettings && !launchRtcSettings(context, requireNotNull(settingsAction))) {
            Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_LONG).show()
        }
    }

    fun close() {
        val owned = RtcCallOverlay.current === this
        starting = false
        handler.removeCallbacks(startTimeout)
        RtcCallOverlay.clear(this)
        onReady = null
        val attached = service
        service = null
        attached?.detach(this)
        if (owned) controller.setBackgroundMedia(target, false)
    }
}

internal fun launchRtcSettings(
    context: Context,
    action: String,
    launch: (Intent) -> Unit = { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
): Boolean {
    // Some devices omit the special-access Activity; fall back to this application's details.
    for (destination in listOf(action, Settings.ACTION_APPLICATION_DETAILS_SETTINGS).distinct()) {
        try {
            launch(Intent(destination, Uri.parse("package:${context.packageName}")))
            return true
        } catch (_: Exception) {
            // Try the next supported system screen without reopening a denied screen on return.
        }
    }
    return false
}

@Composable
internal fun rememberRtcOverlayAction(
    controller: RtcVideoController, avatars: AvatarRepository?,
): () -> Unit {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var permissionSession by remember(controller) { mutableStateOf<RtcOverlaySession?>(null) }
    var permissionReturned by remember(controller) { mutableStateOf(false) }
    var notificationSession by remember(controller) { mutableStateOf<RtcOverlaySession?>(null) }
    var notificationReturned by remember(controller) { mutableStateOf(false) }
    var readySession by remember(controller) { mutableStateOf<RtcOverlaySession?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionReturned = true
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification denial does not revoke camera/microphone or special overlay access.
        notificationReturned = true
    }
    val openOverlay: (RtcOverlaySession) -> Unit = { ready ->
        if (ready.interactive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (Settings.canDrawOverlays(context)) ready.show()
            else {
                ready.waitingPermission = true
                permissionSession = ready
                permissionReturned = false
                if (!launchRtcSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) { launcher.launch(it) }) {
                    permissionSession = null
                    ready.fail("无法打开系统设置，继续在应用内通话")
                }
            }
        } else if (!ready.controller.isForeground) ready.onBackground()
    }
    val session = RtcCallOverlay.current?.takeIf { it.controller === controller }
    val owner = controller.target
    DisposableEffect(controller, lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) {
                RtcCallOverlay.prepareVideo(context, controller, avatars)
                resumed = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (owner != null) RtcCallOverlay.release(controller, owner)
        }
    }
    LaunchedEffect(resumed, controller.target, controller.awaitingPermission, controller.answering,
        controller.closing, controller.cameraEnabled) {
        if (resumed) RtcCallOverlay.prepareVideo(context, controller, avatars)
    }
    LaunchedEffect(resumed, readySession) {
        if (!resumed) return@LaunchedEffect
        val ready = readySession ?: return@LaunchedEffect
        readySession = null
        if (!ready.interactive) return@LaunchedEffect
        if (!Settings.canDrawOverlays(context)) {
            openOverlay(ready)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationSession = ready
            notificationReturned = false
            try {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
                notificationSession = null
                openOverlay(ready)
            }
        } else openOverlay(ready)
    }
    LaunchedEffect(resumed, permissionReturned, notificationReturned, session?.restoreRequested) {
        if (!resumed) return@LaunchedEffect
        if (session?.restoreRequested == true) {
            session.restore()
            return@LaunchedEffect
        }
        if (notificationReturned) {
            notificationReturned = false
            val pendingNotification = notificationSession
            notificationSession = null
            if (pendingNotification?.interactive == true) openOverlay(pendingNotification)
        }
        val pending = permissionSession
        if (permissionReturned) {
            permissionReturned = false
            permissionSession = null
            if (pending?.interactive == true && pending.waitingPermission) {
                if (Settings.canDrawOverlays(context)) pending.show()
                else {
                    pending.waitingPermission = false
                    pending.restore()
                    Toast.makeText(context, "未开启悬浮窗权限，继续在应用内通话", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    return {
        if (resumed && !controller.closing && !controller.awaitingPermission && !controller.cameraPermissionPending) {
            if (controller.incoming && !controller.joined) {
                // Ringing never opens camera/microphone or requests their permissions.
                controller.minimize()
                Toast.makeText(context, "接听后可在其他应用上方悬浮通话", Toast.LENGTH_SHORT).show()
            } else if ((if (controller.audioCall) {
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                } else {
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                }).any {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
            ) {
                if (!launchRtcSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS) { launcher.launch(it) }) {
                    Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_LONG).show()
                }
            } else if (permissionSession == null && notificationSession == null) {
                RtcCallOverlay.start(context, controller, avatars) { ready ->
                    readySession = ready
                }
            }
        }
    }
}
