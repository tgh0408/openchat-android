package com.im.openchat.chat.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import java.util.UUID

class ChatCameraFileProvider : FileProvider()

internal class CapturedPhoto(
    val file: File,
    val uri: Uri,
    private val revoke: () -> Unit,
) {
    fun revokeCameraAccess() {
        runCatching { revoke() }
    }

    fun discard() {
        revokeCameraAccess()
        file.delete()
    }
}

internal fun createCapturedPhoto(context: Context): CapturedPhoto {
    val directory = File(context.cacheDir, "camera-captures")
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("无法创建拍照文件，请检查可用空间")
    }
    val file = File(directory, "IMG_${UUID.randomUUID()}.jpg")
    try {
        if (!file.createNewFile()) throw IOException("无法创建拍照文件")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.chat.camera", file)
        return CapturedPhoto(file, uri) {
            context.revokeUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    } catch (exception: Exception) {
        file.delete()
        throw exception
    }
}

internal class ChatTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            clipData = ClipData.newRawUri("photo", input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
}

internal class PhotoCaptureState {
    private enum class Phase { IDLE, PERMISSION, CAMERA, READY, CLOSED }
    private var phase by mutableStateOf(Phase.IDLE)
    private var photo: CapturedPhoto? = null
    val pending: Boolean get() = phase != Phase.IDLE && phase != Phase.CLOSED
    val awaitingPermission: Boolean get() = phase == Phase.PERMISSION
    val ready: Boolean get() = phase == Phase.READY

    fun begin(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.PERMISSION
        return true
    }

    fun attach(output: CapturedPhoto): Boolean {
        if (!awaitingPermission) {
            output.discard()
            return false
        }
        photo = output
        phase = Phase.CAMERA
        return true
    }

    // A successful result without a nonempty output must never create a message.
    fun result(success: Boolean): Boolean {
        if (phase != Phase.CAMERA) return false
        photo?.revokeCameraAccess()
        if (!success || (photo?.file?.length() ?: 0L) <= 0L) {
            cancel()
            return success
        }
        phase = Phase.READY
        return false
    }

    fun takeReady(resumed: Boolean): CapturedPhoto? {
        if (!resumed || !ready) return null
        val result = photo
        photo = null
        phase = Phase.IDLE
        return result
    }

    fun cancel() {
        photo?.discard()
        photo = null
        if (phase != Phase.CLOSED) phase = Phase.IDLE
    }

    fun close() {
        cancel()
        phase = Phase.CLOSED
    }
}

internal class ChatPhotoCapture(val state: PhotoCaptureState, val launch: () -> Unit)

@Composable
internal fun rememberChatPhotoCapture(
    enabled: Boolean,
    onPhoto: (CapturedPhoto) -> Boolean,
    onError: (String) -> Unit,
): ChatPhotoCapture {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { PhotoCaptureState() }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPhoto by rememberUpdatedState(onPhoto)
    val currentError by rememberUpdatedState(onError)
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val camera = rememberLauncherForActivityResult(remember { ChatTakePicture() }) { success ->
        if (state.result(success)) currentError("未获取到照片，请重新拍摄")
    }
    fun launchCamera() {
        if (!state.awaitingPermission) return
        if (!currentEnabled) {
            state.cancel()
            return
        }
        try {
            val output = createCapturedPhoto(context.applicationContext)
            if (state.attach(output)) camera.launch(output.uri)
        } catch (_: Exception) {
            state.cancel()
            currentError("无法打开相机，请检查相机权限及相机应用后重试")
        }
    }
    val permission = rememberLauncherForActivityResult(remember { ActivityResultContracts.RequestPermission() }) { granted ->
        if (state.awaitingPermission) {
            if (granted) launchCamera() else {
                state.cancel()
                currentError("未获得相机权限，请在系统设置中允许相机权限后重试")
            }
        }
    }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.close()
        }
    }
    // Activity results may arrive at STARTED, before the application's pause cancellation ends.
    LaunchedEffect(state.ready, resumed, enabled) {
        val photo = state.takeReady(resumed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            ?: return@LaunchedEffect
        var accepted = false
        try {
            if (currentEnabled) accepted = currentPhoto(photo)
        } finally {
            if (!accepted) photo.discard()
        }
    }
    return ChatPhotoCapture(state) {
        if (currentEnabled && resumed && state.begin()) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) launchCamera() else {
                try {
                    permission.launch(Manifest.permission.CAMERA)
                } catch (_: Exception) {
                    state.cancel()
                    currentError("无法申请相机权限，请重试")
                }
            }
        }
    }
}
