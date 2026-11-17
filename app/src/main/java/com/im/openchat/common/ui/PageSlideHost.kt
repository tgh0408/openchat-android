package com.im.openchat.common.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal const val PAGE_SLIDE_DURATION_MS = 260
private const val MAX_CAPTURE_PIXELS = 8_388_608L

internal val LocalPageTransitionRunning = staticCompositionLocalOf { false }
internal val LocalPageNavigation = staticCompositionLocalOf<(() -> Unit) -> Unit> { { change -> change() } }
internal val LocalPageBack = staticCompositionLocalOf<(() -> Unit) -> Unit> { { change -> change() } }

/** 只保留无交互的离场图像，不保留带有请求或返回处理器的第二个页面。 */
@Stable
internal class PageSlideState(private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set
    var returning by mutableStateOf(false)
        private set
    var slidesRight by mutableStateOf(false)
        private set
    val progress = Animatable(1f)
    var outgoing by mutableStateOf<ImageBitmap?>(null)
        private set
    var capture: (suspend () -> ImageBitmap?)? = null
    var dismissInput: () -> Unit = {}

    fun navigate(change: () -> Unit) = transition(returning = false, change = change)

    fun back(change: () -> Unit) = transition(returning = true, change = change)

    fun switchTab(currentIndex: Int, targetIndex: Int, change: () -> Unit) {
        if (currentIndex == targetIndex) return
        transition(returning = false, slidesRight = targetIndex < currentIndex, change = change)
    }

    private fun transition(returning: Boolean, slidesRight: Boolean = false, change: () -> Unit) {
        if (running) return
        this.returning = returning
        this.slidesRight = slidesRight
        running = true
        dismissInput()
        scope.launch {
            try {
                // 在修改导航前截图，此时离场页面的绘制树仍然有效。
                val image = try {
                    withTimeoutOrNull(120L.milliseconds) { capture?.invoke() }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                currentCoroutineContext().ensureActive()
                outgoing = image
                progress.snapTo(0f)
                change()
                withFrameNanos { }
                progress.animateTo(1f, tween(PAGE_SLIDE_DURATION_MS, easing = FastOutSlowInEasing))
            } finally {
                outgoing = null
                running = false
            }
        }
    }
}

/** 在授权键作用域内调用，以便撤销授权时立即取消截图和等待中的导航。 */
@Composable
internal fun rememberPageSlideState(): PageSlideState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PageSlideState(scope) }
}

@Composable
internal fun PageSlideHost(state: PageSlideState, content: @Composable () -> Unit) = key(state) {
    val layer = rememberGraphicsLayer()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(state, layer) {
        state.capture = {
            val size = layer.size
            if (!layer.isReleased && size.width > 0 && size.height > 0 &&
                size.width.toLong() * size.height <= MAX_CAPTURE_PIXELS
            ) layer.toImageBitmap() else null
        }
        onDispose { state.capture = null }
    }
    SideEffect { state.dismissInput = { focus.clearFocus(); keyboard?.hide() } }
    CompositionLocalProvider(
        LocalPageTransitionRunning provides state.running,
        LocalPageNavigation provides state::navigate,
        LocalPageBack provides state::back,
    ) {
        Box(
            Modifier.fillMaxSize().testTag("page_slide_host")
                .background(MaterialTheme.colorScheme.background).clipToBounds()
                .onPreviewKeyEvent { state.running }
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (state.running) event.changes.forEach { it.consume() }
                        }
                    }
                }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    val progress = if (state.running) state.progress.value else 1f
                    val direction = if (state.slidesRight) -1f else 1f
                    // 返回时目标页保持静止，当前页面向右滑出并露出目标页。
                    if (state.returning) drawLayer(layer)
                    state.outgoing?.let { image ->
                        translate(left = (if (state.returning) size.width else -size.width * direction) * progress) {
                            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                        }
                    }
                    if (!state.returning) {
                        translate(left = size.width * direction * (1f - progress)) { drawLayer(layer) }
                    }
                },
        ) {
            Box(Modifier.fillMaxSize().then(if (state.running) Modifier.clearAndSetSemantics {} else Modifier)) {
                content()
            }
        }
        BackHandler(enabled = state.running) { }
    }
}
