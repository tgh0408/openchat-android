package com.im.openchat.chat.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun VideoPlaybackLayout(
    overlay: Boolean,
    keepControlsVisible: Boolean,
    modifier: Modifier = Modifier,
    videoOnly: Boolean = false,
    compactRestore: (@Composable () -> Unit)? = null,
    header: @Composable () -> Unit,
    controls: @Composable (onInteraction: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var controlsVisible by remember(overlay) { mutableStateOf(true) }
    val showControls: () -> Unit = {
        controlsVisible = true
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, overlay) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) showControls()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(overlay, keepControlsVisible) {
        if (!overlay || keepControlsVisible) controlsVisible = true
    }
    Box(modifier.background(Color.Black)) {
        if (videoOnly || compactRestore != null) {
            Box(Modifier.fillMaxSize()) { content() }
            if (!videoOnly && compactRestore != null) {
                Box(Modifier.align(Alignment.TopEnd)) { compactRestore() }
            }
        } else if (overlay) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = if (controlsVisible) "隐藏播放控件"
                            else "显示播放控件",
                        onClick = { controlsVisible = keepControlsVisible || !controlsVisible },
                    )
                    .semantics { contentDescription = "视频画面" },
            ) { content() }
            if (controlsVisible) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    header()
                    Spacer(Modifier.weight(1f))
                    controls(showControls)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                controls(showControls)
            }
        }
    }
}

@Composable
internal fun VideoPlaybackImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: run {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window
    }
    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
}
