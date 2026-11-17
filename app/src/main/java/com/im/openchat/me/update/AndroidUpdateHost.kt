package com.im.openchat.me.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 为同一服务器的会话提示和安装弹窗持有唯一状态，服务器变化释放旧任务。 */
@Composable
internal fun rememberAndroidUpdateState(serverUrl: String): AndroidUpdateState = key(serverUrl) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(serverUrl) { AndroidUpdateRepository(context, serverUrl) }
    val state = remember(repository) { AndroidUpdateState(repository, scope) }
    DisposableEffect(state) { onDispose { state.close() } }
    state
}

/** 自动检查仅驱动列表提示，用户点击后展示下载及安装弹窗；离开应用暂停传输。 */
@Composable
internal fun AndroidUpdateHost(state: AndroidUpdateState, manualRequest: Int) {
    val context = LocalContext.current
    val repository = state.repository
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var waitingForPermission by rememberSaveable { mutableStateOf(false) }
    var waitingForInstaller by remember { mutableStateOf(false) }
    // 系统页面返回不等于安装成功；仍可用原已校验快照再次发起安装。
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForInstaller = false
        state.notify("系统安装页面已关闭，未完成安装可重试")
    }
    // 用户允许来源后重新校验文件，再交接系统安装；拒绝保留已下载文件。
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requested = waitingForPermission
        waitingForPermission = false
        if (requested && repository.canInstall()) {
            state.install { intent ->
                installer.launch(intent)
                waitingForInstaller = true
            }
        } else if (requested) {
            state.notify("未允许安装此来源的应用，可再次授权")
        }
    }
    // 手动计数按服务器重置；自动检查不弹窗，也不绕过已忽略版本。
    LaunchedEffect(state, manualRequest) { state.check(manual = manualRequest > 0) }
    // 停止可见时暂停网络和校验；安装器与权限设置由系统继续处理。
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) state.pause()
            if (event == Lifecycle.Event.ON_RESUME) state.check(manual = false)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.pause()
        }
    }
    if (!state.visible || !resumed) return
    val release = state.release
    val busy = state.savingIgnore || state.stage == UpdateStage.CHECKING || state.stage == UpdateStage.VERIFYING
    AlertDialog(
        onDismissRequest = state::dismiss,
        title = { Text(if (release == null) "检查更新" else "OpenChat ${release.version}") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.currentVersion.isNotBlank()) Text("当前版本 ${state.currentVersion}")
                if (release != null) {
                    Text("安装包 ${release.size / (1024 * 1024)} MiB")
                    if (release.notes.isEmpty()) Text("暂无更新内容")
                    release.notes.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
                }
                when (state.stage) {
                    UpdateStage.CHECKING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查更新") }
                    UpdateStage.VERIFYING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在校验安装包") }
                    UpdateStage.DOWNLOADING -> {
                        val total = release?.size ?: 1L
                        val percent = (state.loaded * 100 / total).coerceIn(0, 100)
                        LinearProgressIndicator(
                            progress = { (state.loaded.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("已下载 $percent%")
                    }
                    else -> Unit
                }
                if (state.message.isNotBlank()) Text(state.message)
                if (release != null && !busy && state.stage != UpdateStage.DOWNLOADING) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = state::refresh,
                            enabled = !waitingForPermission && !waitingForInstaller,
                        ) { Text("重新检查") }
                        TextButton(
                            onClick = state::ignoreCurrentVersion,
                            enabled = !waitingForPermission && !waitingForInstaller,
                        ) { Text("忽略当前版本") }
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = state::dismiss) { Text("稍后") }
                if (release != null) {
                    TextButton(
                        onClick = state::redownload,
                        enabled = !busy && !waitingForPermission && !waitingForInstaller &&
                            (state.stage == UpdateStage.AVAILABLE || state.stage == UpdateStage.READY),
                    ) { Text("重新下载") }
                }
                TextButton(
                    enabled = !busy && !waitingForPermission && !waitingForInstaller,
                    // 按真实状态检查、下载、暂停或申请安装授权，不静默安装。
                    onClick = {
                        when (state.stage) {
                            UpdateStage.READY -> {
                                if (repository.canInstall()) {
                                    state.install { intent ->
                                        installer.launch(intent)
                                        waitingForInstaller = true
                                    }
                                } else {
                                    try {
                                        permission.launch(Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:${context.packageName}"),
                                        ))
                                        waitingForPermission = true
                                    } catch (_: Exception) {
                                        waitingForPermission = false
                                        state.notify("无法打开安装来源设置，请在系统设置中允许 OpenChat 安装应用")
                                    }
                                }
                            }
                            UpdateStage.DOWNLOADING -> state.pause()
                            UpdateStage.AVAILABLE -> state.download()
                            UpdateStage.IDLE -> state.check(manual = true)
                            else -> Unit
                        }
                    },
                ) {
                    Text(when (state.stage) {
                        UpdateStage.READY -> "安装更新"
                        UpdateStage.DOWNLOADING -> "暂停"
                        UpdateStage.AVAILABLE -> if (state.loaded > 0) "继续下载" else "下载更新"
                        else -> "检查更新"
                    })
                }
            }
        },
    )
}
