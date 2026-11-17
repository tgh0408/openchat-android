package com.im.openchat.me.update

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.im.openchat.common.data.openChatDataStore
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.common.network.ResumableDownloads
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

private const val MAX_APK_SIZE = 2L * 1024 * 1024 * 1024 - 1024 * 1024

/** 已验证的安卓发布快照，下载与安装始终绑定同一个不可变发布 ID。 */
internal data class AndroidRelease(
    val id: String,
    val version: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
    val notes: List<String>,
)

/** 复用公开 API 与断点下载，仅在应用缓存目录内准备 APK，不操作聊天数据库。 */
internal class AndroidUpdateRepository(context: Context, serverUrl: String) {
    private val app = context.applicationContext
    private val api = ApiClient(app, serverUrl)
    private val directory = File(app.cacheDir, "updates/${digest(serverUrl.trimEnd('/').toByteArray())}")
    private val preferences = app.openChatDataStore
    private val ignoredVersionKey = stringPreferencesKey(
        "ignored_android_update_${digest(serverUrl.trimEnd('/').toByteArray())}",
    )

    companion object {
        // 同进程的旧页面取消尚在关闭文件时，新页面必须等待，避免两个写入者共用断点文件。
        private val downloadMutex = Mutex()
    }

    /** 读取本服务器忽略的版本；沿用唯一 DataStore，不受退出账号影响。 */
    suspend fun ignoredVersion(): String? = preferences.data.first()[ignoredVersionKey]

    /** 仅保存明确忽略的发布版本，失败向界面报告，不清理其他偏好或认证数据。 */
    suspend fun ignoreVersion(version: String) {
        preferences.edit { it[ignoredVersionKey] = version }
    }

    /** 读取真实安装版本和服务端安卓最新版；无更新返回 null，不携带聊天 JWT。 */
    suspend fun check(): Pair<String, AndroidRelease?> {
        val installed = withContext(Dispatchers.IO) { installedPackage().versionName.orEmpty() }
        val root = try {
            successfulResponse(api.get("capi/update/public/latest/android").body)
        } catch (exception: ApiHttpException) {
            throw IOException(errorMessage(exception.response.body, "检查更新失败，请重试"), exception)
        }
        if (root.isNull("data")) return installed to null
        val release = parseRelease(root.getJSONObject("data"))
        return installed to release.takeIf { compareVersions(it.version, installed) > 0 }
    }

    /** 下载固定发布快照；主动重下在原下载锁内清除包和断点，其余情况继续复用。 */
    suspend fun download(
        release: AndroidRelease,
        restart: Boolean = false,
        onProgress: (Long, Long) -> Unit,
    ): File =
        downloadMutex.withLock {
            val target = target(release)
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新目录")
                val files = directory.listFiles() ?: throw IOException("无法读取更新目录，请重试")
                // 主动重下也清除当前 APK 及断点附属文件；不遍历子目录或触碰聊天缓存。
                files.forEach { file ->
                    currentCoroutineContext().ensureActive()
                    val current = file.name == target.name || file.name.startsWith("${target.name}.")
                    if (file.isFile && (restart || !current)) {
                        if (!file.delete()) throw IOException("无法清理安装包，请重试")
                    }
                }
            }
            ResumableDownloads.download(
                release.downloadUrl, target, release.size,
                // 下载组件在 IO 工作线程调用校验；不在主线程计算安装包摘要。
                valid = { file -> matchesDigest(file, release) },
                onProgress = onProgress,
            )
            verify(release)
            target
        }

    /** 安装前再次读取私有 APK，核对摘要、真实包信息及签名；所有磁盘与包解析均在 IO 执行。 */
    suspend fun verify(release: AndroidRelease): File = withContext(Dispatchers.IO) {
        val file = target(release)
        if (!matchesDigest(file, release)) throw IOException("安装包校验失败，请重新下载")
        currentCoroutineContext().ensureActive()
        val flags = signingFlags()
        @Suppress("DEPRECATION")
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: run {
                // 摘要已匹配发布文件；不带签名读取仅用于诊断，绝不用于放行安装。
                @Suppress("DEPRECATION")
                val metadata = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (metadata != null) {
                    throw IOException("安装包未签名或签名无效，请联系管理员重新发布")
                }
                throw IOException("安装包无法解析或不兼容当前系统，请联系管理员重新发布")
            }
        val installed = installedPackage()
        if (archive.packageName != app.packageName) throw IOException("安装包不属于 OpenChat")
        if (compareVersions(archive.versionName.orEmpty(), release.version) != 0) {
            throw IOException("APK 内部版本与发布版本不一致")
        }
        if (versionCode(archive) <= versionCode(installed)) {
            throw IOException("APK versionCode 未递增，不能更新当前应用")
        }
        val archiveSignatures = signatures(archive)
        val installedSignatures = signatures(installed)
        if (archiveSignatures.isEmpty()) {
            throw IOException("安装包未签名或签名无效，请联系管理员重新发布")
        }
        if (archiveSignatures != installedSignatures || installedSignatures.isEmpty()) {
            throw IOException("安装包签名与当前应用不一致")
        }
        currentCoroutineContext().ensureActive()
        file
    }

    /** 返回已校验文件的系统安装 Intent，只授予临时读取权限，不自动确认安装。 */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("OpenChat APK", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 查询本应用的安装来源授权，无磁盘写入；Android 8 以下交由系统安装器判断。 */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    /** 由发布 ID 和摘要生成固定文件名，不使用服务端文件名拼接本地路径。 */
    private fun target(release: AndroidRelease): File = File(directory, "${release.id}-${release.sha256}.apk")

    /** 读取本应用包信息及当前签名，不查询其他应用。 */
    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo =
        app.packageManager.getPackageInfo(app.packageName, signingFlags())

    /** 按设备 API 选择签名读取标记，旧系统使用其支持的签名字段。 */
    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    /** 读取完整版本码，避免 Android 9 起的 longVersionCode 被截断。 */
    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** 比较当前签名集合；本发布流程要求沿用签名密钥，不接受未经配置的签名轮换。 */
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners
        else info.signatures
        return values.orEmpty().map { signature -> digest(signature.toByteArray()) }.toSet()
    }
}

/** 严格校验统一响应；保留服务端业务错误，无网络或存储副作用。 */
private fun successfulResponse(body: String): JSONObject {
    val root = JSONObject(body)
    if (root.opt("success") != true || root.opt("code") != 200) {
        throw IOException(errorMessage(body, "检查更新失败，请重试"))
    }
    if (!root.has("data")) throw IOException("更新服务响应不完整")
    return root
}

/** 读取有界业务错误，畸形 HTTP 响应使用本地提示，不暴露原始正文。 */
private fun errorMessage(body: String, fallback: String): String =
    runCatching { JSONObject(body).optString("errMsg").trim().take(300).ifBlank { fallback } }
        .getOrDefault(fallback)

/** 将三段发布协议转换为不可变快照，拒绝非法 ID、摘要、大小及非安全下载地址。 */
private fun parseRelease(data: JSONObject): AndroidRelease {
    val id = data.get("releaseId").toString()
    val size = data.get("fileSize").toString().toLongOrNull()
    val version = data.getString("version")
    val sha256 = data.getString("sha256").lowercase()
    val url = data.getString("downloadUrl").toHttpUrlOrNull()
    val name = data.getString("clientName")
    if (!Regex("[1-9][0-9]*").matches(id) || id.toLongOrNull() == null ||
        size == null || size !in 1..MAX_APK_SIZE ||
        !Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version) ||
        !Regex("[0-9a-f]{64}").matches(sha256) || !name.endsWith(".apk", ignoreCase = true) ||
        url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
        !(url.isHttps || (url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1")))
    ) throw IOException("安卓更新信息无效")
    val notes = data.getJSONArray("updateInfo")
    if (notes.length() > 20000) throw IOException("更新说明过长")
    val lines = (0 until notes.length()).map { index -> notes.getString(index) }
    if (lines.sumOf { it.length } > 20000) throw IOException("更新说明过长")
    return AndroidRelease(id, version, size, sha256, url.toString(), lines)
}

/** 比较纯数字版本；当前已安装的两段版本补零，拒绝预发布或无法识别的格式。 */
internal fun compareVersions(left: String, right: String): Int {
    /** 解析有界数字段；无副作用，不把未知版本当作零。 */
    fun parts(value: String): List<Long> {
        if (!Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?").matches(value)) throw IOException("应用版本格式无效")
        return value.split('.').map { it.toLongOrNull() ?: throw IOException("应用版本格式无效") }
            .let { if (it.size == 2) it + 0L else it }
    }
    val a = parts(left)
    val b = parts(right)
    for (index in 0..2) {
        val compared = a[index].compareTo(b[index])
        if (compared != 0) return compared
    }
    return 0
}

/** 有界流式计算摘要并核对大小，不将完整 APK 加载进内存；仅由 IO 线程调用。 */
private fun matchesDigest(file: File, release: AndroidRelease): Boolean {
    if (!file.isFile || file.length() != release.size) return false
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > release.size) return false
            hash.update(buffer, 0, read)
        }
        if (count != release.size) return false
    }
    return hex(hash.digest()) == release.sha256
}

/** 返回小写 SHA-256，用于隔离服务器目录和比较签名，无存储副作用。 */
private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** 将摘要字节转为固定两位十六进制，无副作用。 */
private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
