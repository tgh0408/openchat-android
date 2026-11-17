package com.im.openchat.chat.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.MediaInformationSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

/**
 * 不使用服务器或套接字：Media3 请求本地 VOD 分段，FFmpeg 只编码这些分段。
 * 私有源文件及其 Room 资源、播放键均不会改变。
 */
internal class VideoTranscoder private constructor(
    private val source: File,
    private val directory: File,
    val plan: VideoTranscodePlan,
    private val validate: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private val running = MutableStateFlow(true)
    private val cache = LinkedHashMap<Int, ByteArray>(MAX_CACHE_SEGMENTS, 0.75f, true)
    private val pending = mutableMapOf<Int, Deferred<ByteArray>>()
    private var cacheBytes = 0
    private var encoding: Job? = null
    @Volatile private var closed = false
    private val authority = UUID.randomUUID().toString()
    val playlistUri: Uri = "openchat-video://$authority/index.m3u8".toUri()
    private val playlist = plan.playlist().toByteArray(Charsets.UTF_8)

    /** 仅由 Media3 加载器调用，不会在主线程执行。 */
    fun read(uri: Uri): ByteArray {
        if (closed || uri.scheme != playlistUri.scheme || uri.authority != authority ||
            uri.query != null || uri.fragment != null
        ) throw IOException("VIDEO_TRANSCODE_SOURCE_CLOSED")
        if (uri.path == "/index.m3u8") return playlist
        val match = SEGMENT_PATH.matchEntire(uri.path.orEmpty())
            ?: throw IOException("VIDEO_TRANSCODE_INVALID_SEGMENT")
        val index = match.groupValues[1].toIntOrNull()
            ?.takeIf { it in 0 until plan.segmentCount }
            ?: throw IOException("VIDEO_TRANSCODE_INVALID_SEGMENT")
        val task = synchronized(monitor) {
            if (closed) throw IOException("VIDEO_TRANSCODE_SOURCE_CLOSED")
            cache[index]?.let { return it }
            pending.getOrPut(index) { scope.async { produce(index) } }
        }
        return try {
            runBlocking {
                withTimeout(120_000.milliseconds) { task.await() }
            }
        } catch (_: TimeoutCancellationException) {
            task.cancel()
            Log.w(TAG, "code=VIDEO_TRANSCODE_WAIT_TIMEOUT, segment=$index")
            throw IOException("视频分段等待超时，请重试")
        } catch (_: CancellationException) {
            task.cancel()
            throw InterruptedIOException("VIDEO_TRANSCODE_CANCELLED")
        } catch (_: InterruptedException) {
            task.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("VIDEO_TRANSCODE_CANCELLED")
        } catch (exception: IOException) {
            Log.w(TAG, "code=VIDEO_TRANSCODE_SEGMENT_FAILED, segment=$index")
            throw exception
        } catch (exception: RuntimeException) {
            Log.w(TAG, "code=VIDEO_TRANSCODE_RUNTIME_ERROR, segment=$index")
            throw IOException("视频分段读取失败，请重试", exception)
        } finally {
            synchronized(monitor) {
                if (pending[index] === task) pending.remove(index)
            }
        }
    }

    /** Media3 打开目标分段前，先取消旧播放位置的任务。 */
    fun seekTo(positionMs: Long) {
        val target = plan.indexAt(positionMs)
        synchronized(monitor) {
            pending.filterKeys { it != target }.forEach { (index, task) ->
                task.cancel()
                if (pending[index] === task) pending.remove(index)
            }
        }
    }

    fun cancelRead(uri: Uri) {
        val match = SEGMENT_PATH.matchEntire(uri.path.orEmpty()) ?: return
        val index = match.groupValues[1].toIntOrNull() ?: return
        synchronized(monitor) {
            pending[index]?.cancel()
        }
    }

    fun setPaused(paused: Boolean) {
        running.value = !paused
        if (paused) synchronized(monitor) {
                // 保留请求，避免暂停被当作播放错误。
                // 只取消编码器任务，恢复播放时重新生成分段。
            encoding?.cancel(Paused())
        }
    }

    fun cancel() {
        synchronized(monitor) {
            closed = true
            cache.clear()
            cacheBytes = 0
        }
        scope.coroutineContext[Job]?.cancel()
    }

    suspend fun close() {
        cancel()
        scope.coroutineContext[Job]?.join()
        withContext(Dispatchers.IO) {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private suspend fun produce(index: Int): ByteArray {
        while (true) {
            running.first { it }
            currentCoroutineContext().ensureActive()
            val result = coroutineScope {
                val worker = async {
                    nativeGate.withPermit {
                        validateSource()
                        if (!running.value) throw Paused()
                        val temporary = File.createTempFile("segment-", ".ts.part", directory)
                        try {
                            val session = awaitNative<FFmpegSession>(validate = ::validateSource) { complete ->
                                FFmpegKit.executeWithArgumentsAsync(
                                    plan.arguments(source.path, temporary.path, index), { complete(it) },
                                )
                            }
                            if (!ReturnCode.isSuccess(session.returnCode)) {
                                Log.w(TAG, "code=VIDEO_TRANSCODE_RETURN_CODE, segment=$index")
                                throw IOException("视频分段转码失败，请重试")
                            }
                            validateSource()
                            if (temporary.length() !in 188L..MAX_SEGMENT_BYTES.toLong() ||
                                temporary.length() % TS_PACKET_SIZE != 0L
                            ) {
                                Log.w(TAG, "code=VIDEO_TRANSCODE_INVALID_SIZE, segment=$index")
                                throw IOException("视频分段输出无效")
                            }
                            val bytes = temporary.readBytes()
                            validateTs(bytes, index)
                            validateSegment(temporary, index)
                            bytes
                        } finally {
                            temporary.delete()
                        }
                    }
                }
                synchronized(monitor) {
                    encoding = worker
                    if (!running.value) worker.cancel(Paused())
                }
                try {
                    worker.await()
                } catch (exception: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // 暂停时只取消 native 任务；继续播放时由加载器恢复此请求。
                    if (exception is Paused) null else throw exception
                } finally {
                    synchronized(monitor) { if (encoding === worker) encoding = null }
                }
            } ?: continue
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (closed) throw CancellationException("Transcoder closed")
                cache.put(index, result)?.let { cacheBytes -= it.size }
                cacheBytes += result.size
                while (cacheBytes > MAX_CACHE_BYTES || cache.size > MAX_CACHE_SEGMENTS) {
                    val oldest = cache.entries.iterator().next()
                    cacheBytes -= oldest.value.size
                    cache.remove(oldest.key)
                }
            }
            return result
        }
    }

    private suspend fun validateSource() = withContext(Dispatchers.Main.immediate) {
        if (closed) throw CancellationException("Transcoder closed")
        validate()
    }

    private class Paused : CancellationException("Transcoding paused")

    companion object {
        private const val TAG = "OpenChatVideo"
        private val nativeGate = Semaphore(1)
        private val SEGMENT_PATH = Regex("/segment/(0|[1-9][0-9]*)\\.ts")
        private const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
        // 覆盖播放器提前请求的 60 秒范围及最近播放的分段，以支持短距离回退查找。
        private const val MAX_CACHE_SEGMENTS = 40
        private const val MAX_CACHE_BYTES = 64 * 1024 * 1024
        private const val TS_PACKET_SIZE = 188L
        private const val TS_SYNC_BYTE: Byte = 0x47
        private val configure by lazy {
            FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
            FFmpegKitConfig.setSessionHistorySize(4)
        }

        /** 探测本机视频，并沿已校验的服务器摘要和 UID 创建独立转码会话目录。 */
        suspend fun create(context: Context, source: File, validate: () -> Unit): VideoTranscoder {
            var created: VideoTranscoder? = null
            try {
                return withContext(Dispatchers.IO) {
                    try {
                        configure
                        val information = nativeGate.withPermit {
                            val session = awaitNative<MediaInformationSession>(validate = {
                                withContext(Dispatchers.Main.immediate) { validate() }
                            }) { complete ->
                                val probe = MediaInformationSession.create(
                                    arrayOf(
                                        "-v", "error", "-hide_banner", "-protocol_whitelist", "file",
                                        "-format_whitelist", "mov,matroska,webm,avi,asf,flv,mpeg,mpegvideo",
                                        "-analyzeduration", "5000000", "-probesize", "8388608",
                                        "-print_format", "json", "-show_entries", "format=duration:stream=index,codec_type",
                                        "-i", source.path,
                                    ), { complete(it) }, { },
                                )
                                FFmpegKitConfig.asyncGetMediaInformationExecute(probe, 5_000)
                                probe
                            }
                            if (!ReturnCode.isSuccess(session.returnCode) || session.mediaInformation == null) {
                                throw IOException("无法读取本地视频信息")
                            }
                            session.mediaInformation
                        }
                        val seconds = information.duration?.toDoubleOrNull()
                        if (seconds == null || !seconds.isFinite() || seconds <= 0 || seconds > 86_400) {
                            throw IOException("视频时长无效")
                        }
                        val video = information.streams.firstOrNull { it.type == "video" }?.index
                            ?: throw IOException("未能识别视频轨道")
                        val audio = information.streams.firstOrNull { it.type == "audio" }?.index
                        val plan = VideoTranscodePlan((seconds * 1000).roundToLong().coerceAtLeast(1), video, audio)
                        val accountDirectory = source.parentFile
                            ?: throw IOException("VIDEO_TRANSCODE_INVALID_PARTITION")
                        val uid = accountDirectory.name.takeIf {
                            it.matches(Regex("[1-9][0-9]{0,18}")) && it.toLongOrNull() != null
                        } ?: throw IOException("VIDEO_TRANSCODE_INVALID_PARTITION")
                        val serverHash = accountDirectory.parentFile?.name
                            ?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }
                            ?: throw IOException("VIDEO_TRANSCODE_INVALID_PARTITION")
                        val partition = "$serverHash/$uid"
                        val directory = File(context.cacheDir, "video-transcode/$partition/${UUID.randomUUID()}")
                        if (!directory.mkdirs()) throw IOException("无法创建转码缓存")
                        VideoTranscoder(source, directory, plan, validate).also { created = it }
                    } catch (_: LinkageError) {
                        throw IOException("当前设备缺少可用的视频转码组件")
                    } catch (exception: RuntimeException) {
                        if (exception is CancellationException) throw exception
                        throw IOException("无法初始化视频转码组件，请重试", exception)
                    }
                }
            } catch (exception: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    created?.close()
                }
                throw exception
            }
        }

        private suspend fun <T : Session> awaitNative(
            validate: suspend () -> Unit,
            start: ((T) -> Unit) -> T,
        ): T {
            validate()
            val completed = CompletableDeferred<T>()
            var native: T? = null
            try {
                native = start { completed.complete(it) }
                return withTimeout(60_000.milliseconds) {
                    while (!completed.isCompleted) {
                        validate()
                        delay(200.milliseconds)
                    }
                    completed.await()
                }
            } catch (_: TimeoutCancellationException) {
                throw IOException("视频转码超时，请重试")
            } catch (exception: RuntimeException) {
                if (exception is CancellationException) throw exception
                throw IOException("无法启动视频转码，请重试", exception)
            } finally {
                val started = native
                if (!completed.isCompleted && started != null) {
                    FFmpegKit.cancel(started.sessionId)
        // 删除文件或释放闸门前，必须确认 native 代码已停止写入。
                    withContext(NonCancellable) { completed.await() }
                }
            }
        }

        private fun validateTs(bytes: ByteArray, index: Int) {
            val packetSize = TS_PACKET_SIZE.toInt()
            val invalidHeader = if (bytes.isEmpty() || bytes.size % packetSize != 0) {
                true
            } else {
                var invalid = false
                var offset = 0
                while (offset < bytes.size) {
                    if (bytes[offset] != TS_SYNC_BYTE) {
                        invalid = true
                        break
                    }
                    offset += packetSize
                }
                invalid
            }
            if (invalidHeader) {
                Log.w(TAG, "code=VIDEO_TRANSCODE_INVALID_TS, segment=$index")
                throw IOException("VIDEO_TRANSCODE_INVALID_TS")
            }
        }

        private fun validateSegment(file: File, index: Int) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                var videoTrack = -1
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        videoTrack = track
                        break
                    }
                }
                if (videoTrack < 0) {
                    Log.w(TAG, "code=VIDEO_TRANSCODE_NO_VIDEO_TRACK, segment=$index")
                    throw IOException("VIDEO_TRANSCODE_NO_VIDEO_TRACK")
                }
                extractor.selectTrack(videoTrack)
                if (extractor.sampleTrackIndex < 0) {
                    Log.w(TAG, "code=VIDEO_TRANSCODE_NO_VIDEO_SAMPLE, segment=$index")
                    throw IOException("VIDEO_TRANSCODE_NO_VIDEO_SAMPLE")
                }
            } catch (exception: IOException) {
                throw exception
            } catch (_: RuntimeException) {
                Log.w(TAG, "code=VIDEO_TRANSCODE_INVALID_CONTAINER, segment=$index")
                throw IOException("VIDEO_TRANSCODE_INVALID_CONTAINER")
            } finally {
                extractor.release()
            }
        }
    }
}
