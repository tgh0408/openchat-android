package com.im.openchat.chat.ui

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import kotlin.math.roundToInt

internal val LocalVideoPictureInPicture = staticCompositionLocalOf<VideoPictureInPicture?> { null }

/** 由 Activity 持有、连接当前授权播放会话的桥接器。 */
internal class VideoPictureInPicture(private val activity: ComponentActivity) {
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    var inPictureInPicture by mutableStateOf(false)
        private set
    var transitioning by mutableStateOf(false)
        private set
    var hasSession by mutableStateOf(false)
        private set
    val videoOnly get() = inPictureInPicture || transitioning

    private var owner: Any? = null
    private var validate: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private var onRestore: (() -> Unit)? = null
    private var available = false
    private var wantsToPlay = false
    private var aspectRatio = 16f / 9f
    private var sourceBounds: Rect? = null
    private var leavingPictureInPicture = false

    fun bind(
        owner: Any,
        validate: () -> Unit,
        onClose: () -> Unit,
        onRestore: () -> Unit,
    ) {
        if (!supported) return
        this.owner = owner
        this.validate = validate
        this.onClose = onClose
        this.onRestore = onRestore
        hasSession = true
        available = false
        wantsToPlay = false
        aspectRatio = 16f / 9f
        sourceBounds = null
        publish()
    }

    fun update(owner: Any, available: Boolean, wantsToPlay: Boolean) {
        if (this.owner !== owner || this.available == available && this.wantsToPlay == wantsToPlay) return
        this.available = available
        this.wantsToPlay = wantsToPlay
        publish()
    }

    fun updateVideoBounds(owner: Any, bounds: Rect, ratio: Float) {
        if (this.owner !== owner || videoOnly || bounds.isEmpty) return
        val fittedRatio = pictureInPictureRatio(ratio)
        if (sourceBounds == bounds && aspectRatio == fittedRatio) return
        sourceBounds = Rect(bounds)
        aspectRatio = fittedRatio
        publish()
    }

    fun enter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!supported || !available || !validateSession() ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return false
        if (activity.isInPictureInPictureMode) return true
        return try {
            val entered = activity.enterPictureInPictureMode(parameters())
            transitioning = entered
            entered
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun onUserLeaveHint() {
        // Android 12 及以上使用已发布的自动进入参数处理 Home 手势切换。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && wantsToPlay) enter()
    }

    fun onTransitionStarted() {
        if (hasSession && available) transitioning = true
    }

    fun onModeChanged(active: Boolean) {
        val wasActive = inPictureInPicture
        inPictureInPicture = active
        transitioning = false
        if (active) {
            leavingPictureInPicture = false
            if (!validateSession()) activity.finish()
        } else if (wasActive) {
            leavingPictureInPicture = true
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                leavingPictureInPicture = false
                onClose?.invoke()
                activity.finish()
            } else if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                leavingPictureInPicture = false
                onRestore?.invoke()
            }
        }
    }

    fun onResume() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !activity.isInPictureInPictureMode) {
            transitioning = false
            if (leavingPictureInPicture) {
                leavingPictureInPicture = false
                onRestore?.invoke()
            }
        }
    }

    fun onStop() {
        // 画中画可见时 Activity 处于 STARTED；STOP 表示关闭或失去可见性，而非进入画中画。
        if (inPictureInPicture || transitioning || leavingPictureInPicture) {
            leavingPictureInPicture = false
            onClose?.invoke()
            activity.finish()
        }
    }

    fun unbind(owner: Any) {
        if (this.owner !== owner) return
        clearSession()
        publish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
            // 授权或播放结束后，绝不让账号或聊天界面继续显示在画中画窗口中。
            activity.finish()
        } else {
            transitioning = false
        }
    }

    fun dispose() {
        clearSession()
    }

    private fun clearSession() {
        owner = null
        validate = null
        onClose = null
        onRestore = null
        available = false
        wantsToPlay = false
        hasSession = false
        sourceBounds = null
    }

    private fun validateSession(): Boolean {
        val check = validate ?: return false
        return try {
            check()
            true
        } catch (_: IllegalStateException) {
            onClose?.invoke()
            false
        } catch (_: java.io.IOException) {
            onClose?.invoke()
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun parameters(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            // 向内取整以满足平台下限，比例不得低于 1 / 2.39。
            .setAspectRatio(Rational(
                (pictureInPictureRatio(aspectRatio) * 10_000).roundToInt().coerceIn(4_185, 23_900),
                10_000,
            ))
            .setSourceRectHint(sourceBounds)
            .setActions(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(available && wantsToPlay)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun publish() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!supported || activity.isFinishing || activity.isDestroyed) return
        try {
            activity.setPictureInPictureParams(parameters())
        } catch (_: IllegalStateException) {
            // 平台不支持或未启用画中画时，不能中断本地播放。
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }
}

internal fun pictureInPictureRatio(ratio: Float): Float =
    if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
