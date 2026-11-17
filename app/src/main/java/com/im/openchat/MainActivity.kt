package com.im.openchat

import android.app.PictureInPictureUiState
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.im.openchat.chat.rtc.RtcCallOverlay
import com.im.openchat.chat.rtc.RtcOverlayService
import com.im.openchat.chat.ui.LocalVideoPictureInPicture
import com.im.openchat.chat.ui.VideoPictureInPicture
import com.im.openchat.common.ui.OpenChatApp

class MainActivity : ComponentActivity() {
    private lateinit var videoPictureInPicture: VideoPictureInPicture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreRtcOverlay(intent)
        enableEdgeToEdge()
        videoPictureInPicture = VideoPictureInPicture(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            videoPictureInPicture.onModeChanged(true)
        }
        setContent {
            CompositionLocalProvider(LocalVideoPictureInPicture provides videoPictureInPicture) {
                Box(Modifier.fillMaxSize()) {
                    OpenChatApp()
                    if (videoPictureInPicture.videoOnly && !videoPictureInPicture.hasSession) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restoreRtcOverlay(intent)
    }

    private fun restoreRtcOverlay(intent: Intent?) {
        if (intent?.action != RtcOverlayService.RESTORE) return
        val session = RtcCallOverlay.current ?: return
        if (session.interactive && intent.getStringExtra(RtcOverlayService.REQUEST) == session.target.requestId) {
            session.restoreRequested = true
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        videoPictureInPicture.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::videoPictureInPicture.isInitialized) videoPictureInPicture.onModeChanged(isInPictureInPictureMode)
    }

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip &&
            ::videoPictureInPicture.isInitialized
        ) videoPictureInPicture.onTransitionStarted()
    }

    override fun onResume() {
        super.onResume()
        if (::videoPictureInPicture.isInitialized) videoPictureInPicture.onResume()
    }

    override fun onStop() {
        if (::videoPictureInPicture.isInitialized) videoPictureInPicture.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (::videoPictureInPicture.isInitialized) videoPictureInPicture.dispose()
        super.onDestroy()
    }
}
