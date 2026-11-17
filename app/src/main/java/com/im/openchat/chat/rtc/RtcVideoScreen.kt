package com.im.openchat.chat.rtc

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.im.openchat.R
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.common.ui.Avatar
import com.im.openchat.common.ui.ToolButton
import com.im.openchat.common.ui.rememberAvatarProfiles
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import livekit.org.webrtc.RendererCommon

/** 展示当前授权的通话窗口，群通话的相机权限与首次麦克风授权分别交付。 */
@Composable
internal fun RtcVideoHost(controller: RtcVideoController, avatars: AvatarRepository?) {
    RtcRecoveryPrompt(controller)
    RtcDialPrompt(controller)
    val minimize = key(controller, controller.target) { rememberRtcOverlayAction(controller, avatars) }
    val target = controller.target ?: return
    if (!controller.windowVisible) return
    key(target) {
        if (controller.awaitingPermission) {
            RtcPermissionRequest(target) { controller.permissionResult(target, it) }
        }
        if (controller.cameraPermissionPending) {
            key("group_camera") {
                RtcPermissionRequest(target, cameraOnly = true) {
                    controller.cameraPermissionResult(target, it)
                }
            }
        }
        LaunchedEffect(target) {
            while (true) { controller.tick(); delay(1000) }
        }
        if (controller.systemFloating) {
            // The service owns a separate window and composition, also while this Activity is stopped.
        } else if (controller.floating) {
            RtcFloatingWindow(controller, target, avatars)
        } else Dialog(
            onDismissRequest = {
                if (target.mediaMode == RtcMediaMode.VIDEO) {
                    if (!controller.closing && !controller.awaitingPermission) {
                        controller.selectMainVideo(
                            target,
                            controller.localVideoMain ?:
                                !(controller.remoteVideoLayoutEstablished || controller.remoteCameraEnabled == false),
                        )
                        minimize()
                    }
                } else controller.dismiss()
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                dismissOnClickOutside = false,
            ),
        ) {
            val view = LocalView.current
            DisposableEffect(view) {
                val window = (view.parent as? DialogWindowProvider)?.window
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window?.let {
                    WindowCompat.getInsetsController(it, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            RtcVideoScreen(controller, target, avatars, onMinimize = minimize)
        }
    }
}

/** 呼出确认使用应用内 Compose 弹窗；查询、确认期间保持旧通话。 */
@Composable
private fun RtcDialPrompt(controller: RtcVideoController) {
    val request = controller.dialTarget ?: return
    if (controller.dialAwaitingPermission) {
        key(request) {
            RtcPermissionRequest(request) { controller.dialPermissionResult(request, it) }
        }
        return
    }
    val busy = controller.dialBusy
    val error = controller.dialError
    val checked = controller.dialCallIds != null
    AlertDialog(
        onDismissRequest = { if (!busy) controller.cancelDial() },
        modifier = Modifier.testTag("rtc_redial_prompt"),
        title = { Text(if (controller.joiningExistingGroup) "加入群通话" else "发起通话") },
        text = {
            Text(when {
                error != null -> error
                busy -> "正在确认通话状态，请稍候"
                else -> "当前已有通话正在进行，是否强制呼出？"
            })
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (checked) controller.confirmDial() else controller.checkDial()
            }) { Text(if (error != null) "重试" else "是") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = controller::cancelDial) {
                Text(if (error != null) "取消" else "否")
            }
        },
    )
}

/** 首次呼出、接听及群相机共用权限流程，群接听只申请麦克风，恢复前台后交付结果。 */
@Composable
private fun RtcPermissionRequest(
    target: RtcTarget, cameraOnly: Boolean = false, onResult: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentResult by rememberUpdatedState(onResult)
    var granted by remember { mutableStateOf(false) }
    var launched by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val required = remember(target, cameraOnly) {
        when {
            cameraOnly -> arrayOf(Manifest.permission.CAMERA)
            target.groupName != null || target.mediaMode == RtcMediaMode.AUDIO ->
                arrayOf(Manifest.permission.RECORD_AUDIO)
            else -> arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = required.all { name ->
            ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) currentResult(false)
    }
    LaunchedEffect(resumed) {
        if (!resumed || launched) return@LaunchedEffect
        launched = true
        if (required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            granted = true
        } else {
            try { permission.launch(required) } catch (_: Exception) { currentResult(false) }
        }
    }
    LaunchedEffect(granted, resumed) {
        if (granted && resumed) currentResult(true)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 单聊恢复确认独立于通话窗口，确认前不申请摄像头或麦克风权限。 */
@Composable
private fun RtcRecoveryPrompt(controller: RtcVideoController) {
    val pending = controller.recovery
    val error = controller.recoveryError
    if (controller.active || controller.dialTarget != null || (pending == null && error == null)) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("通话恢复") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pending?.let {
                    val mode = if (it.call.mediaMode == RtcMediaMode.AUDIO) "语音" else "视频"
                    Text("与 ${it.peerName} 的${mode}通话尚在进行，是否重连？")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !controller.recoveryBusy, onClick = {
                if (pending == null) controller.refreshRecovery() else controller.resolveRecovery(true)
            }) { Text(if (pending == null) "重试" else "重连") }
        },
        dismissButton = {
            TextButton(enabled = !controller.recoveryBusy, onClick = {
                if (pending == null) controller.dismissRecoveryError() else controller.resolveRecovery(false)
            }) { Text(if (pending == null) "稍后" else "挂断") }
        },
    )
}

@Composable
private fun RtcFloatingWindow(controller: RtcVideoController, target: RtcTarget, avatars: AvatarRepository?) {
    BackHandler { controller.restore() }
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
        val density = LocalDensity.current
        val width = minOf(105.dp, maxWidth)
        val height = minOf(161.dp, maxHeight)
        val limitX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
        val limitY = with(density) { (maxHeight - height).toPx().coerceAtLeast(0f) }
        var x by remember(target) { mutableFloatStateOf(Float.NaN) }
        var y by remember(target) { mutableFloatStateOf(0f) }
        Box(
            Modifier.offset {
                IntOffset((if (x.isNaN()) limitX else x.coerceIn(0f, limitX)).roundToInt(),
                    y.coerceIn(0f, limitY).roundToInt())
            }.size(width, height).clip(RoundedCornerShape(6.dp))
                .testTag("rtc_floating_window")
                .pointerInput(limitX, limitY, controller.closing) {
                    if (!controller.closing) detectDragGestures { change, amount ->
                        change.consume()
                        x = ((if (x.isNaN()) limitX else x) + amount.x).coerceIn(0f, limitX)
                        y = (y + amount.y).coerceIn(0f, limitY)
                    }
                },
        ) {
            RtcVideoScreen(controller, target, avatars, compact = true)
        }
    }
}

private class RtcScreenMedia {
    var room: Room? = null
    var local: RtcVideoSource? = null
    var remote: RtcVideoSource? = null
    var mirror = false
    var invited = false
    var accepted = false
    var remoteCameraEnabled: Boolean? = null
    var localCameraEnabled = true
}

/** 展示视频、语音及悬浮通话；远端关闭摄像头时用明确提示替换旧画面。 */
@Composable
internal fun RtcVideoScreen(
    controller: RtcVideoController, target: RtcTarget, avatars: AvatarRepository?,
    compact: Boolean = false,
    onMinimize: () -> Unit = { controller.minimize() },
    onRestore: () -> Unit = { controller.restore() },
) {
    if (target.groupName != null) {
        RtcGroupScreen(controller, target, avatars, compact, onMinimize, onRestore)
        return
    }
    val audioCall = target.mediaMode == RtcMediaMode.AUDIO
    val profiles by rememberAvatarProfiles(
        if (!compact && target.incoming) listOf(target.peerUid) else emptyList(), avatars, null,
    )
    val peer = profiles.firstOrNull()
    val displayName = if (target.incoming) peer?.name?.takeIf { it.isNotBlank() } ?: target.name else target.name
    val localTrack = controller.localVideo
    val remoteTrack = controller.remoteVideo
    val sdkLocal = remember(localTrack) { localTrack?.let(::RtcSdkVideoSource) }
    val sdkRemote = remember(remoteTrack) { remoteTrack?.let(::RtcSdkVideoSource) }
    // Keep the visible track identities during the close delay; their renderers become still images.
    val media = remember(target) { RtcScreenMedia() }
    if (!controller.closing) {
        media.room = controller.room
        media.local = sdkLocal
        media.remote = controller.directRemoteVideo ?: sdkRemote
        media.mirror = controller.frontCamera
        media.invited = controller.incoming && controller.call?.accepted != true
        media.accepted = controller.call?.accepted == true
        media.remoteCameraEnabled = controller.remoteCameraEnabled
        media.localCameraEnabled = controller.cameraEnabled
    }
    val room = media.room
    val local = media.local
    val remote = media.remote
    val remoteCameraOff = media.remoteCameraEnabled == false
    val hasRemote = controller.remoteVideoLayoutEstablished || remoteCameraOff
    val localMain = controller.localVideoMain ?: !hasRemote
    // 首帧或关闭通知建立双画面后，暂停输出及重新等待首帧都不撤销小窗。
    SideEffect { if (hasRemote) controller.establishRemoteVideoLayout(target) }
    val status = rtcStatus(controller.call, controller.ending, controller.finished,
        controller.mediaConnected, controller.incoming, target.mediaMode)
    val surface = Modifier.fillMaxSize().background(Color(0xFF1C1D22))
        .testTag(if (compact) "rtc_floating_video" else if (audioCall) "rtc_audio_screen" else "rtc_video_screen")
    BoxWithConstraints(if (compact) surface
        .semantics { contentDescription = "还原通话窗口" }
        .clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = !controller.closing, onClick = onRestore,
        ) else surface
    ) {
        val controlsMaxHeight = maxHeight * 0.55f
        if (room != null && !audioCall) {
            if (local != null) RtcVideo(controller, room, local, media.mirror,
                Modifier.fillMaxSize().alpha(if (localMain) 1f else 0f), onFrame = {})
            if (!media.localCameraEnabled && localMain) {
                Box(Modifier.fillMaxSize().background(Color(0xFF1C1D22)),
                    contentAlignment = Alignment.Center) {
                    if (!compact) Text("摄像头已关闭", color = Color.White, fontSize = 16.sp)
                }
            }
            if (remote != null) RtcVideo(controller, room, remote, false,
                Modifier.fillMaxSize().alpha(if (!localMain) 1f else 0f),
                onFrame = {
                    if (controller.room === room &&
                        (controller.directRemoteVideo === remote ||
                            (remote === sdkRemote && remoteTrack != null && controller.remoteVideo === remoteTrack))
                    ) {
                        controller.establishRemoteVideoLayout(target)
                    }
                })
            if (remoteCameraOff && !localMain) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF1C1D22))
                        .testTag("rtc_remote_camera_off"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("对方摄像头已关闭", Modifier.padding(12.dp),
                        color = Color.White, fontSize = if (compact) 12.sp else 16.sp,
                        textAlign = TextAlign.Center)
                }
            }
            if (hasRemote && !compact) {
                val density = LocalDensity.current
                val width = minOf(100.dp, maxWidth * 0.28f)
                val height = width * 1.5f
                val limitX = with(density) { (maxWidth - width - 12.dp).toPx().coerceAtLeast(0f) }
                val limitY = with(density) { (maxHeight - height - 260.dp).toPx().coerceAtLeast(0f) }
                var x by remember(target) { mutableFloatStateOf(Float.NaN) }
                var y by remember(target) { mutableFloatStateOf(0f) }
                Box(
                    Modifier.zIndex(1f).padding(top = 64.dp)
                        .offset { IntOffset((if (x.isNaN()) limitX else x.coerceIn(0f, limitX)).roundToInt(),
                            y.coerceIn(0f, limitY).roundToInt()) }
                        .size(width, height).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B2D33))
                        .testTag("rtc_video_pip")
                        .semantics { contentDescription = "切换主画面" }
                        .pointerInput(limitX, limitY, controller.closing) {
                            if (!controller.closing) detectDragGestures { change, amount ->
                                change.consume()
                                x = ((if (x.isNaN()) limitX else x) + amount.x).coerceIn(0f, limitX)
                                y = (y + amount.y).coerceIn(0f, limitY)
                            }
                        }.clickable(enabled = !controller.closing) {
                            controller.selectMainVideo(target, !localMain)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val track = if (localMain) remote else local
                    if (track != null) RtcVideo(controller, room, track, !localMain && media.mirror,
                        Modifier.fillMaxSize(), onFrame = {})
                    val cameraOff = if (localMain) remoteCameraOff else !media.localCameraEnabled
                    if (cameraOff || track == null) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF2B2D33)),
                            contentAlignment = Alignment.Center) {
                            if (cameraOff) Text(if (localMain) "对方摄像头已关闭" else "摄像头已关闭",
                                Modifier.padding(8.dp), color = Color.White,
                                fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (compact && audioCall) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Avatar(
                    uid = target.peerUid, name = displayName, avatar = peer?.avatar ?: target.avatar,
                    roomId = target.roomId.takeUnless { target.incoming }, repository = avatars, size = 48.dp,
                )
                Spacer(Modifier.height(6.dp))
                val seconds = controller.elapsedSeconds
                Text(
                    "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}",
                    color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (!compact) Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        ToolButton(ImageVector.vectorResource(R.drawable.ic_video_minimize), "悬浮通话",
                            enabled = !controller.closing && !controller.awaitingPermission,
                            onClick = {
                                controller.selectMainVideo(target, localMain)
                                onMinimize()
                            })
                    }
                }
                if (!audioCall) {
                    val seconds = controller.elapsedSeconds
                    val minutes = (seconds / 60).toString().padStart(2, '0')
                    val remainder = (seconds % 60).toString().padStart(2, '0')
                    Text(
                        "$minutes:$remainder",
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("rtc_duration"),
                        color = Color.White, fontSize = 16.sp, lineHeight = 24.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 0.sp,
                        textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    )
                } else Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!hasRemote || audioCall) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.testTag("rtc_peer_avatar")) {
                        Avatar(uid = target.peerUid, name = displayName, avatar = peer?.avatar ?: target.avatar,
                            roomId = target.roomId.takeUnless { target.incoming }, repository = avatars, size = 72.dp)
                    }
                    if (audioCall) {
                        Spacer(Modifier.height(10.dp))
                        val seconds = controller.elapsedSeconds
                        Text(
                            "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}",
                            modifier = Modifier.testTag("rtc_duration"),
                            color = Color.White, fontSize = 16.sp, lineHeight = 24.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 0.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                    } else Spacer(Modifier.height(14.dp))
                    Text(displayName, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(if (controller.awaitingPermission) {
                        if (audioCall) "等待麦克风授权" else "等待相机和麦克风授权"
                    }
                        else if (controller.answering) "正在接听" else status,
                        color = Color(0xFFE4E7ED), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                controller.error?.let {
                    Text(it, color = Color(0xFFFFC1BD), fontSize = 13.sp, textAlign = TextAlign.Center)
                    if (controller.canRetry) TextButton(onClick = { controller.retry() }) { Text("重试", color = Color.White) }
                    Spacer(Modifier.height(8.dp))
                }
                if (!controller.finished || controller.closing) {
                    val invited = media.invited
                    if (!invited) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RtcControl("麦克风", R.drawable.ic_chat_mic, controller.microphoneEnabled,
                                controller.audioControlsEnabled, Modifier.weight(1f)) { controller.toggleMicrophone() }
                            RtcControl("扬声器", R.drawable.ic_rtc_speaker, controller.speakerEnabled,
                                controller.audioControlsEnabled, Modifier.weight(1f)) { controller.toggleSpeaker() }
                            if (!audioCall) {
                                RtcControl("摄像头", R.drawable.ic_chat_video_call, controller.cameraEnabled,
                                    controller.controlsEnabled, Modifier.weight(1f)) { controller.toggleCamera() }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!invited) Spacer(Modifier.weight(1f))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { controller.end() }, enabled = !controller.closing,
                                modifier = Modifier.size(60.dp).testTag("rtc_hangup"),
                            ) {
                                Image(painterResource(R.drawable.ic_rtc_hangup),
                                    if (invited) "拒绝通话" else if (media.accepted) "挂断通话"
                                    else "取消通话", Modifier.fillMaxSize())
                            }
                            if (invited) Text("拒绝", color = Color.White, fontSize = 14.sp)
                        }
                        if (invited) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                FilledIconButton(
                                    onClick = { controller.answer() }, enabled = controller.canAnswer,
                                    modifier = Modifier.size(60.dp).testTag("rtc_answer"),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color(0xFF198754), contentColor = Color.White),
                                ) {
                                    Icon(Icons.Default.Phone, if (audioCall) "接听语音通话" else "接听视频通话", Modifier.size(28.dp))
                                }
                                Text("接听", color = Color.White, fontSize = 14.sp)
                            }
                        } else if (!audioCall) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides Color.White) {
                                ToolButton(Icons.Default.Refresh, "切换摄像头",
                                    enabled = controller.controlsEnabled && controller.cameraEnabled,
                                    onClick = { controller.switchCamera() })
                            }
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 群邀请以语音样式展示发起人与本次受邀者，接听后按真实参与状态展示成员音视频。
 * @param controller 本次授权的通话状态，按钮仅提交本人接听、离开和设备控制。
 * @param target 保留群名与发起人，资料和头像复用当前授权仓库；不读取全部群成员。
 * @param compact 悬浮时仅展示当前画面或发起人头像，点击复用还原入口。
 */
@Composable
private fun RtcGroupScreen(
    controller: RtcVideoController, target: RtcTarget, avatars: AvatarRepository?,
    compact: Boolean, onMinimize: () -> Unit, onRestore: () -> Unit,
) {
    val call = controller.call
    val profiles by rememberAvatarProfiles(listOf(target.peerUid), avatars)
    val caller = profiles.firstOrNull()
    val callerName = caller?.name?.takeIf { it.isNotBlank() } ?: target.name
    // 定格关闭保留原渲染节点，让既有视频释放监听器保留最后一帧。
    var joined by remember(target) { mutableStateOf(false) }
    var displayRoom by remember(target) { mutableStateOf<Room?>(null) }
    var videos by remember(target) { mutableStateOf<Map<String, VideoTrack>>(emptyMap()) }
    if (!controller.closing) {
        joined = controller.joined
        displayRoom = controller.room
        videos = controller.groupVideos + if (controller.cameraEnabled && controller.localVideo != null) {
            mapOf(controller.ownUid to requireNotNull(controller.localVideo))
        } else emptyMap()
    }
    val invitation = target.incoming && !joined
    val seconds = controller.elapsedSeconds
    val duration = "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    val background = Modifier.fillMaxSize().background(Color(0xFF1C1D22))
    if (compact) {
        Box(background.testTag("rtc_group_floating")
            .semantics { contentDescription = "还原群通话窗口" }
            .clickable(enabled = !controller.closing, onClick = onRestore),
            contentAlignment = Alignment.Center) {
            val video = videos.values.firstOrNull()
            val source = remember(video) { video?.let(::RtcSdkVideoSource) }
            val room = displayRoom
            if (source != null && room != null) {
                RtcVideo(controller, room, source, video === controller.localVideo && controller.frontCamera,
                    Modifier.fillMaxSize(), onFrame = {})
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(target.peerUid, callerName, 48.dp, avatars, avatar = caller?.avatar ?: target.avatar)
                Spacer(Modifier.height(8.dp))
                Text(duration, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
        return
    }
    Column(background.safeDrawingPadding().testTag("rtc_group_screen")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                ToolButton(ImageVector.vectorResource(R.drawable.ic_video_minimize), "悬浮通话",
                    enabled = !controller.closing && !controller.awaitingPermission &&
                        !controller.cameraPermissionPending, onClick = onMinimize)
            }
            Text(if (!invitation) target.groupName.orEmpty() else "群语音邀请",
                Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White,
                fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(48.dp))
        }
        if (invitation) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.testTag("rtc_group_caller_avatar")) {
                    Avatar(target.peerUid, callerName, 72.dp, avatars, avatar = caller?.avatar ?: target.avatar)
                }
                Spacer(Modifier.height(20.dp))
                Text("$callerName 邀请你加入 ${target.groupName} 群语音",
                    Modifier.fillMaxWidth().testTag("rtc_group_invitation_title"),
                    color = Color.White, fontSize = 18.sp, lineHeight = 28.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                LazyRow(
                    Modifier.fillMaxWidth().height(56.dp).testTag("rtc_group_invitees"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 发起人已在上方展示；下方仅保留本次受邀名单并排除本人。
                    items(call?.participants?.keys.orEmpty().filter { it != controller.ownUid && it != call?.callerUid },
                        key = { it }) { uid ->
                        Box(Modifier.testTag("rtc_group_invitee_$uid")) {
                            Avatar(uid, uid, 48.dp, avatars)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                val status = when {
                    controller.closing -> "通话已关闭"
                    controller.awaitingPermission -> "等待麦克风授权"
                    controller.answering -> "正在接听"
                    else -> null
                }
                if (status != null) Text(status, color = Color(0xFFE4E7ED),
                    fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            Text(duration, Modifier.fillMaxWidth().testTag("rtc_duration"),
                color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center)
            RtcGroupMemberGrid(controller, target, avatars, displayRoom, videos,
                Modifier.weight(1f).fillMaxWidth())
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
            .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            (controller.cameraError ?: controller.error)?.let {
                Text(it, color = Color(0xFFFFC1BD), fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }
            if (!invitation) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RtcControl("麦克风", R.drawable.ic_chat_mic, controller.microphoneEnabled,
                        controller.audioControlsEnabled, Modifier.weight(1f)) { controller.toggleMicrophone() }
                    RtcControl("扬声器", R.drawable.ic_rtc_speaker, controller.speakerEnabled,
                        controller.audioControlsEnabled, Modifier.weight(1f)) { controller.toggleSpeaker() }
                    RtcControl("摄像头", R.drawable.ic_chat_video_call, controller.cameraEnabled,
                        controller.controlsEnabled && controller.mediaConnected && !controller.cameraPermissionPending &&
                            target.mediaMode == RtcMediaMode.VIDEO,
                        Modifier.weight(1f)) { controller.toggleCamera() }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (controller.awaitingPermission && !invitation) {
                Text("等待麦克风授权", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (controller.canRetry) {
                TextButton(onClick = controller::retry) { Text("重试", color = Color.White) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                if (!invitation) Spacer(Modifier.size(48.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { controller.end() }, enabled = !controller.closing,
                        modifier = Modifier.size(60.dp).testTag("rtc_hangup")) {
                        Image(painterResource(R.drawable.ic_rtc_hangup),
                            if (!invitation) "挂断群通话" else "拒绝群通话", Modifier.fillMaxSize())
                    }
                    Text(if (!invitation) "挂断" else "拒绝", color = Color.White, fontSize = 14.sp)
                }
                if (invitation) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(onClick = { controller.answer() }, enabled = controller.canAnswer,
                            modifier = Modifier.size(60.dp).testTag("rtc_answer"),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFF198754), contentColor = Color.White)) {
                            Icon(Icons.Default.Phone, "接听群语音", Modifier.size(28.dp))
                        }
                        Text("接听", color = Color.White, fontSize = 14.sp)
                    }
                } else CompositionLocalProvider(LocalContentColor provides Color.White) {
                    ToolButton(Icons.Default.Refresh, "切换摄像头",
                        enabled = controller.controlsEnabled && controller.cameraEnabled,
                        onClick = { controller.switchCamera() })
                }
            }
        }
    }
}

@Composable
private fun RtcGroupMemberGrid(
    controller: RtcVideoController, target: RtcTarget, avatars: AvatarRepository?,
    room: Room?, videos: Map<String, VideoTrack>, modifier: Modifier,
) {
    val members = rtcGroupMemberTiles(target, controller.call, controller.ownUid,
        controller.groupEnteredUids, controller.groupWaitingExpired)
    key(target) {
        RtcGroupMemberLayout(
            members = members,
            videoUids = if (room != null) videos.keys else emptySet(),
            enabled = !controller.closing,
            modifier = modifier,
            avatarContent = { member, side ->
                RtcGroupMemberAvatar(member, side, controller.ownUid, target, avatars,
                    speaking = !controller.closing && member.uid in controller.groupSpeakingUids)
            },
            videoContent = { uid ->
                val track = videos[uid]
                val source = remember(track) { track?.let(::RtcSdkVideoSource) }
                if (source != null && room != null) {
                    RtcVideo(controller, room, source,
                        uid == controller.ownUid && controller.frontCamera,
                        Modifier.fillMaxSize().background(Color.Black), onFrame = {})
                }
            },
        )
    }
}

@Composable
internal fun RtcGroupMemberAvatar(
    member: RtcGroupMemberTile, side: Dp, ownUid: String,
    target: RtcTarget, avatars: AvatarRepository?, speaking: Boolean,
) {
    val uid = member.uid
    val profiles by rememberAvatarProfiles(listOf(uid), avatars, target.roomId)
    val profile = profiles.firstOrNull()
    val name = if (uid == ownUid) "我" else target.memberNames[uid] ?: profile?.name ?: uid
    Box(Modifier.fillMaxSize().testTag("rtc_group_member_$uid")
        .semantics { stateDescription = if (member.waiting) "等待进入" else "已进入" },
        contentAlignment = Alignment.Center) {
        Avatar(uid, name, side, avatars, target.roomId, profile?.avatar.orEmpty(), shape = RectangleShape)
        if (member.waiting) RtcGroupWaiting(Modifier.fillMaxSize().testTag("rtc_group_waiting_$uid"))
        Text(name, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 20.dp, vertical = 3.dp),
            color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (speaking && !member.waiting) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chat_mic), "$name 正在说话",
                Modifier.align(Alignment.BottomStart).padding(start = 3.dp, bottom = 3.dp)
                    .size(16.dp).testTag("rtc_group_speaking_$uid"),
                tint = Color(0xFF4ADE80))
        }
    }
}

@Composable
private fun RtcGroupWaiting(modifier: Modifier) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            phase = (phase + 1) % 3
        }
    }
    Box(modifier.background(Color(0xFF666666).copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { dot ->
                Box(Modifier.size(5.dp).background(
                    Color.White.copy(alpha = if (dot == phase) 1f else 0.35f), CircleShape))
            }
        }
    }
}

@Composable
private fun RtcControl(name: String, icon: Int, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = selected, onCheckedChange = { onClick() }, enabled = enabled,
            modifier = Modifier.size(56.dp).semantics { stateDescription = if (selected) "已开" else "已关" },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color(0xFF474A52), contentColor = Color.White,
                disabledContainerColor = if (selected) Color.White else Color(0xFF474A52),
                disabledContentColor = if (selected) Color(0xFF1A1A1E) else Color.White,
                checkedContainerColor = Color.White, checkedContentColor = Color(0xFF1A1A1E),
            ),
        ) {
            Icon(ImageVector.vectorResource(icon), if (selected) "关闭$name" else "打开$name", Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("$name${if (selected) "已开" else "已关"}", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RtcVideo(
    controller: RtcVideoController, room: Room, track: RtcVideoSource, mirror: Boolean,
    modifier: Modifier, onFrame: () -> Unit,
) {
    val currentFrame by rememberUpdatedState(onFrame)
    key(room, track) {
        var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
        var frozenFrame by remember { mutableStateOf<ImageBitmap?>(null) }
        var released by remember { mutableStateOf(false) }
        val release: (Boolean) -> Unit = { capture ->
            val view = renderer
            if (view != null && !released) {
                if (capture && view.isAvailable && view.width > 0 && view.height > 0) {
                    // Bound the temporary still image; TextureView includes its rendered mirror/scale transform.
                    val scale = minOf(1f, 1280f / maxOf(view.width, view.height))
                    frozenFrame = runCatching {
                        view.getBitmap((view.width * scale).roundToInt().coerceAtLeast(1),
                            (view.height * scale).roundToInt().coerceAtLeast(1))?.asImageBitmap()
                    }.getOrNull()
                }
                released = true
                runCatching { track.removeRenderer(view) }
                runCatching { view.release() }
            }
        }
        val currentRelease by rememberUpdatedState(release)
        DisposableEffect(controller, room, track) {
            val listener: (Boolean) -> Unit = { currentRelease(it) }
            controller.addMediaReleaseListener(listener)
            onDispose {
                controller.removeMediaReleaseListener(listener)
                currentRelease(false)
            }
        }
        if (controller.closing) {
            val frame = frozenFrame
            if (frame != null) Image(frame, null, modifier.testTag("rtc_frozen_frame"),
                contentScale = ContentScale.FillBounds)
            else Box(modifier)
        } else AndroidView(
            modifier = modifier,
            factory = { context ->
                object : TextureViewRenderer(context) {
                    override fun onFirstFrameRendered() {
                        super.onFirstFrameRendered()
                        post { currentFrame() }
                    }
                }.apply {
                    room.initVideoRenderer(this)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(mirror)
                    track.addRenderer(this)
                    renderer = this
                    released = false
                }
            },
            update = { it.setMirror(mirror) },
            onRelease = { currentRelease(false) },
        )
    }
}
