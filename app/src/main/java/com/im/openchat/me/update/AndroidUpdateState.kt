package com.im.openchat.me.update

import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY }

/** 本次应用页面的更新状态；所有状态修改在主线程，旧任务结果按代次丢弃。 */
internal class AndroidUpdateState(
    val repository: AndroidUpdateRepository,
    private val scope: CoroutineScope,
) {
    var visible by mutableStateOf(false)
        private set
    var stage by mutableStateOf(UpdateStage.IDLE)
        private set
    var release by mutableStateOf<AndroidRelease?>(null)
        private set
    var currentVersion by mutableStateOf("")
        private set
    var loaded by mutableLongStateOf(0L)
        private set
    var message by mutableStateOf("")
        private set
    var savingIgnore by mutableStateOf(false)
        private set
    private var ignoredVersion by mutableStateOf<String?>(null)
    private var operation: Job? = null
    private var ignoreOperation: Job? = null
    private var generation = 0L
    private var lastAutomaticCheck: Long? = null

    /** 只向会话列表展示未被忽略的较新版本，无存储或网络副作用。 */
    val showBanner: Boolean
        get() = release?.let { it.version != ignoredVersion } == true

    /** 自动检查仅更新顶部提示；手动检查可查看被忽略版本，已有下载保持原快照。 */
    fun check(manual: Boolean) {
        if (manual) visible = true
        if (operation?.isActive == true || savingIgnore) return
        if (manual && release != null) return
        if (!manual) {
            if (visible || loaded > 0 || stage == UpdateStage.READY) return
            val now = SystemClock.elapsedRealtime()
            if (lastAutomaticCheck?.let { now - it < 5 * 60 * 1000L } == true) return
            lastAutomaticCheck = now
        }
        val request = ++generation
        stage = UpdateStage.CHECKING
        message = ""
        // 在页面作用域发起公开查询，取消或切换服务器后不回写旧结果。
        operation = scope.launch {
            try {
                val ignored = repository.ignoredVersion()
                val (installed, latest) = repository.check()
                if (request != generation) return@launch
                ignoredVersion = ignored
                currentVersion = installed
                release = latest
                stage = if (latest == null) UpdateStage.IDLE else UpdateStage.AVAILABLE
                message = if (latest == null) "当前已是最新版本" else ""
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (request == generation) {
                    stage = if (release == null) UpdateStage.IDLE else UpdateStage.AVAILABLE
                    message = failureMessage(exception, "检查更新失败，请重试")
                }
            }
        }
    }

    /** 打开现有发布的更新内容，不重新请求或开始下载。 */
    fun showDetails() {
        visible = true
    }

    /** 从顶部入口直接开始下载；已下载或正在处理时打开原进度和安装入口。 */
    fun updateNow() {
        if (savingIgnore) return
        visible = true
        if (stage == UpdateStage.AVAILABLE) download()
    }

    /** 忽略当前快照，DataStore 写入成功后才隐藏；失败保留提示及错误反馈。 */
    fun ignoreCurrentVersion() {
        val snapshot = release ?: return
        if (savingIgnore) return
        dismiss()
        generation += 1
        operation?.cancel()
        stage = UpdateStage.AVAILABLE
        savingIgnore = true
        val request = generation
        // 仅本次服务器的原快照可写入忽略偏好，迟到结果不改变后续状态。
        ignoreOperation = scope.launch {
            try {
                repository.ignoreVersion(snapshot.version)
                if (request != generation) return@launch
                ignoredVersion = snapshot.version
                release = null
                loaded = 0L
                stage = UpdateStage.IDLE
                message = ""
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (request == generation) {
                    message = "保存忽略版本失败，请重试"
                    visible = true
                }
            } finally {
                if (request == generation) savingIgnore = false
            }
        }
    }

    /** 用户确认后下载固定发布；失败和暂停保留快照，允许使用同一断点重新开始。 */
    fun download() = startDownload(restart = false)

    /** 放弃当前完整包和断点，从零下载同一发布快照。 */
    fun redownload() = startDownload(restart = true)

    private fun startDownload(restart: Boolean) {
        val snapshot = release ?: return
        if (savingIgnore || (stage != UpdateStage.AVAILABLE && !(restart && stage == UpdateStage.READY))) return
        val request = ++generation
        operation?.cancel()
        stage = UpdateStage.DOWNLOADING
        if (restart) loaded = 0L
        message = ""
        // 下载组件负责工作线程 IO，进度回调切回主线程后再核对操作代次。
        operation = scope.launch {
            try {
                repository.download(snapshot, restart = restart) { count, _ ->
                    scope.launch {
                        if (request == generation && stage == UpdateStage.DOWNLOADING) loaded = count
                    }
                }
                if (request != generation) return@launch
                stage = UpdateStage.READY
                message = "下载完成"
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (request == generation) {
                    stage = UpdateStage.AVAILABLE
                    message = failureMessage(exception, "下载失败，请重试")
                }
            }
        }
    }

    /** 进入后台或用户暂停时取消在途传输，不删除断点，不将取消显示为下载失败。 */
    fun pause() {
        if (stage != UpdateStage.DOWNLOADING && stage != UpdateStage.VERIFYING) return
        generation += 1
        operation?.cancel()
        stage = UpdateStage.AVAILABLE
        message = "已暂停，可继续下载"
    }

    /** 关闭提示并暂停传输；保留发布快照供手动入口继续，无安装副作用。 */
    fun dismiss() {
        pause()
        if (stage == UpdateStage.CHECKING) {
            generation += 1
            operation?.cancel()
            stage = if (release == null) UpdateStage.IDLE else UpdateStage.AVAILABLE
        }
        visible = false
    }

    /** 用户重新检查时放弃内存快照；已缓存文件仍由下一次下载核对和清理。 */
    fun refresh() {
        if (savingIgnore) return
        generation += 1
        operation?.cancel()
        operation = null
        release = null
        loaded = 0L
        stage = UpdateStage.IDLE
        check(manual = true)
    }

    /** 再次校验文件后交给界面启动系统安装器，启动成功只代表交接，不能视为安装成功。 */
    fun install(launch: (Intent) -> Unit) {
        val snapshot = release ?: return
        if (stage != UpdateStage.READY) return
        val request = ++generation
        stage = UpdateStage.VERIFYING
        message = ""
        // 校验在 IO 线程运行，系统 Intent 在主线程交接；失败保持重试入口。
        operation = scope.launch {
            try {
                val file = repository.verify(snapshot)
                if (request != generation) return@launch
                stage = UpdateStage.READY
                launch(repository.installIntent(file))
                message = "请在系统安装页面确认；取消后可再次安装"
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (request == generation) {
                    stage = UpdateStage.AVAILABLE
                    message = failureMessage(exception, "无法打开安装器，请重试")
                }
            }
        }
    }

    /** 系统权限或安装页面返回后仅显示反馈，不猜测安装结果。 */
    fun notify(message: String) {
        this.message = message
    }

    /** 服务器切换或页面销毁时使全部在途结果失效，取消查询、下载和忽略写入。 */
    fun close() {
        generation += 1
        operation?.cancel()
        ignoreOperation?.cancel()
    }
}

/** 只展示本模块的中文业务异常，避免把解析错误、路径或 URL 泄露到界面。 */
private fun failureMessage(exception: Exception, fallback: String): String {
    val message = exception.message.orEmpty()
    return if (exception is IOException && message.any { it in '\u4e00'..'\u9fff' }) message.take(300)
    else fallback
}
