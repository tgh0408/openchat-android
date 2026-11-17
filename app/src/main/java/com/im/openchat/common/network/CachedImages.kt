package com.im.openchat.common.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.AtomicFile
import android.util.LruCache
import com.im.openchat.common.data.accountStoragePartition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 图片文件与位图共用的本地优先缓存；网络客户端不携带业务认证头，也不跟随重定向。 */
internal object CachedImages {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val transfers = Semaphore(3)
    // 固定条带锁合并同资源请求，避免长列表为每张图片永久持有一把锁。
    private val locks = Array(64) { Mutex() }
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        /**
         * 按解码字节限制内存缓存。
         * @param key 资源及解码尺寸摘要。
         * @param value 已缩采样位图。
         * @return 位图字节数；无副作用。
         */
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** OSS 上传成功后保存当前分区的原图，并按需复用 512px 缩略图。 */
    suspend fun storeUploaded(
        context: Context,
        namespace: String,
        key: String,
        source: File,
        thumbnail: Bitmap? = null,
    ): Unit =
        withContext(Dispatchers.IO) {
            val pathKey = digest("$namespace\n$key")
            locks[(pathKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                val directory = cacheDirectory(context, namespace)
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("无法创建图片缓存目录")
                }
                val target = AtomicFile(File(directory, "$pathKey.img"))
                val output = target.startWrite()
                try {
                    source.inputStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    ensureActive()
                    target.finishWrite(output)
                } catch (exception: Exception) {
                    target.failWrite(output)
                    throw exception
                }
                thumbnail?.let { memory.put("$pathKey:512", it) }
            }
        }

    /** Memory only: a recreated image row can draw its first frame without waiting for IO. */
    fun peek(namespace: String, key: String, dimension: Int): Bitmap? =
        memory["${digest("$namespace\n$key")}:$dimension"]

    /**
     * 先读内存和私有文件，缓存未命中后才获取远端地址并下载。
     * @param context 应用上下文，用于定位不参与备份的缓存目录。
     * @param namespace 公开头像或按服务器、账号隔离的消息分区。
     * @param key 稳定资源标识，不含短期签名。
     * @param maxBytes 原始图片的体积上限。
     * @param dimension 解码最长边上限。
     * @param remote 缓存未命中时获取下载地址的回调；只读状态可在回调中拒绝远端访问。
     * @return 缩采样位图；成功下载后原子保存，失败抛出 IOException，取消传播。
     */
    suspend fun load(
        context: Context,
        namespace: String,
        key: String,
        maxBytes: Long,
        dimension: Int,
        remote: suspend () -> String,
    ): Bitmap {
        val pathKey = digest("$namespace\n$key")
        val memoryKey = "$pathKey:$dimension"
        return loadCached(
            context, namespace, key, maxBytes, remote,
            cached = { memory[memoryKey] },
            decode = { file -> decode(file, maxBytes, dimension)?.also { memory.put(memoryKey, it) } },
        )
    }

    /** 动图仅共享原始文件，每个可见项单独解码，避免多个视图争用 Drawable 回调和播放状态。 */
    suspend fun loadDrawable(
        context: Context,
        namespace: String,
        key: String,
        maxBytes: Long,
        dimension: Int,
        remote: suspend () -> String,
    ): Drawable = loadCached(
        context, namespace, key, maxBytes, remote,
        cached = { null },
        decode = { file -> decodeImageDrawable(context.resources, file, maxBytes, dimension) },
    )

    /** 在对应资源分区串行读取或下载图片，避免重复写入同一缓存。 */
    private suspend fun <T : Any> loadCached(
        context: Context,
        namespace: String,
        key: String,
        maxBytes: Long,
        remote: suspend () -> String,
        cached: () -> T?,
        decode: (File) -> T?,
    ): T = withContext(Dispatchers.IO) {
        val pathKey = digest("$namespace\n$key")
        cached()?.let { return@withContext it }
        // 同资源先串行检查缓存，后到的可见项不重复下载。
        locks[(pathKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cached()?.let { return@withLock it }
            val directory = cacheDirectory(context, namespace)
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("无法创建图片缓存目录")
            }
            val file = File(directory, "$pathKey.img")
            val atomic = AtomicFile(file)
            if (file.exists()) {
                val decoded = decode(file)
                if (decoded != null) {
                    return@withLock decoded
                }
                atomic.delete()
            }
            // 有界并发下载，排队和读取过程中均响应界面取消。
            transfers.withPermit {
                ensureActive()
                download(remote(), atomic, maxBytes)
            }
            ensureActive()
            val decoded = decode(file)
            if (decoded == null) {
                atomic.delete()
                throw IOException("图片格式无效或尺寸过大")
            }
            decoded
        }
    }

    /** 账号图片按用途、服务器和 UID 分层；公开资源继续使用独立的公共命名空间。 */
    private fun cacheDirectory(context: Context, namespace: String): File {
        val parts = namespace.split('\n')
        val partition = when (parts.first()) {
            "messages", "emojis", "group-qr" -> {
                require(parts.size == 3) { "图片缓存分区无效" }
                "${accountStoragePartition(parts[1], parts[2])}/${parts[0]}"
            }
            else -> digest(namespace)
        }
        return File(context.applicationContext.noBackupFilesDir, "images/$partition")
    }

    /**
     * 使用无认证客户端流式下载，限制体积并原子提交文件。
     * @param address 公开头像或服务端签发的短期下载地址，不持久化地址。
     * @param target 私有摘要文件。
     * @param maxBytes 下载上限。
     * 无返回值；失败回滚临时文件，协程取消时取消 Call。
     */
    private suspend fun download(address: String, target: AtomicFile, maxBytes: Long) {
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val url = address.toHttpUrlOrNull()
                if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
                    finished.complete(Unit)
                    continuation.resumeWithException(IOException("图片下载地址无效"))
                    return@suspendCancellableCoroutine
                }
                val call = client.newCall(Request.Builder().url(url).build())
                // 可见项离开界面时取消请求，工作线程丢弃未完成写入。
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    /**
                     * 将下载失败交给当前加载效果，无返回值，不记录 URL 或签名。
                     * @param call 当前失败请求。
                     * @param e 网络异常。
                     */
                    override fun onFailure(call: Call, e: IOException) {
                        finished.complete(Unit)
                        if (continuation.isActive) continuation.resumeWithException(IOException("图片下载失败，请重试", e))
                    }

                    /**
                     * 有界消费并关闭响应，成功后提交原始图片文件。
                     * @param call 当前下载请求。
                     * @param response 无业务认证的资源响应。
                     * 无返回值；取消或失败回滚，不输出资源地址。
                     */
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            // 消费响应并始终关闭网络资源，取消后不开始写文件。
                            response.use { result ->
                                if (!continuation.isActive) return
                                if (!result.isSuccessful) throw IOException("图片下载失败（HTTP ${result.code}）")
                                val body = result.body
                                if (body.contentLength() > maxBytes) throw IOException("图片文件过大")
                                val output = target.startWrite()
                                try {
                                    var count = 0L
                                    val buffer = ByteArray(16 * 1024)
                                    // 分块写入原始文件，不将最大 50 MiB 的资源整体读入内存。
                                    body.byteStream().use { input ->
                                        while (true) {
                                            if (!continuation.isActive) throw IOException("图片加载已取消")
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            count += read
                                            if (count > maxBytes) throw IOException("图片文件过大")
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    if (count == 0L || !continuation.isActive) throw IOException("图片为空或加载已取消")
                                    target.finishWrite(output)
                                } catch (exception: Exception) {
                                    target.failWrite(output)
                                    throw exception
                                }
                            }
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (exception: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("图片读取失败，请重试", exception))
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                })
            }
        } finally {
            // 取消仍等待网络工作线程完成临时文件回滚，再释放资源锁，避免新请求与旧写入交错。
            withContext(NonCancellable) { finished.await() }
        }
    }

    /**
     * 检查体积、尺寸并缩采样解码，拒绝非位图和过大像素尺寸。
     * @param file 私有缓存文件。
     * @param maxBytes 文件体积上限。
     * @param dimension 解码最长边。
     * @return 位图或 null；在工作线程读取文件及分配位图，无网络副作用。
     */
    private fun decode(file: File, maxBytes: Long, dimension: Int): Bitmap? {
        if (file.length() !in 1L..maxBytes) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 ||
            options.outWidth.toLong() * options.outHeight > 100_000_000L
        ) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > dimension) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options)
    }

    /**
     * 将账号分区或资源键映射为固定文件名，避免使用外部文件名和路径。
     * @param value 待摘要的稳定标识。
     * @return SHA-256 十六进制摘要；无副作用。
     */
    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
