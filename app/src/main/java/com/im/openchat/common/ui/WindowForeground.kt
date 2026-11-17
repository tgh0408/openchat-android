package com.im.openchat.common.ui

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

/** 只有生命周期处于 RESUMED 且窗口获得焦点时，才能记录新的已读位置。 */
@Composable
internal fun rememberWindowForeground(): Boolean {
    val view = LocalView.current
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
    var foreground by remember(view, lifecycle) {
        mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true && view.hasWindowFocus())
    }
    DisposableEffect(view, lifecycle) {
        fun update() {
            foreground = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true && view.hasWindowFocus()
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { update() }
        val tree = view.viewTreeObserver
        lifecycle?.addObserver(observer)
        tree.addOnWindowFocusChangeListener(focus)
        update()
        onDispose {
            lifecycle?.removeObserver(observer)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(focus)
        }
    }
    return foreground
}
