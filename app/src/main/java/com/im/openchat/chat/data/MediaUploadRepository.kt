package com.im.openchat.chat.data

import android.content.Context
import android.net.Uri
import com.im.openchat.common.data.ChatDataOwner
import com.im.openchat.common.data.accountStoragePartition
import com.im.openchat.common.data.ChatStore
import com.im.openchat.common.network.ApiClient
import com.im.openchat.common.network.ApiHttpException
import com.im.openchat.common.network.SignedUploads
import com.im.openchat.common.network.CachedVideoFiles
import com.im.openchat.login.data.InvalidSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.RandomAccessFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class MediaUploadRepository(
    context: Context,
    private val api: ApiClient,
    private val store: ChatStore,
    private val owner: ChatDataOwner,
    private val validate: (String) -> Unit,
    private val latest: (String, String) -> Message?,
    private val publish: suspend (String) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    private val sources = MediaUploadSources(context, owner.serverUrl, owner.userId)
    private val cacheRoot = context.applicationContext.noBackupFilesDir
    private val partition = accountStoragePartition(owner.serverUrl, owner.userId)
    private val posters = linkedMapOf<String, String>()

    suspend fun selectedName(roomId: String, uri: Uri): String {
        validate(roomId)
        return sources.documentName(uri).also { validate(roomId) }
    }

    suspend fun stage(
        roomId: String, uri: Uri, video: Boolean, expectedName: String? = null, onStaged: () -> Unit = {},
    ): String {
        validate(roomId)
        val source = sources.prepare(uri, video, expectedName)
        return stageSource(roomId, source, onStaged)
    }

    suspend fun stageChatFile(
        roomId: String, message: Message, files: MessageFileRepository, onStaged: () -> Unit,
        video: Boolean = false,
    ): String {
        validate(roomId)
        val body = message.body as? FileMessageBody ?: throw IOException("文件不可用")
        val file = files.download(roomId, message) { _, _ -> }
        validate(roomId)
        val source = sources.prepareCachedFile(
            file, body.extra?.fileName.orEmpty(), body.extra?.size ?: 0L, video,
        )
        return stageSource(roomId, source, onStaged = onStaged, validateSource = {
            // Recheck the source message after copying, before creating a new upload.
            files.status(roomId, message)
        })
    }

    private suspend fun stageSource(
        roomId: String, source: MediaUploadSource, onStaged: () -> Unit = {},
        validateSource: suspend () -> Unit = {},
    ): String {
        var persisted = false
        try {
            validateSource()
            validate(roomId)
            val clientId = UUID.randomUUID().toString()
            // Cancellation after the transaction must not delete a source already referenced by it.
            withContext(kotlinx.coroutines.NonCancellable) {
                store.saveOutgoingUpload(owner, roomId, clientId, sources.installationId(), source)
                persisted = true
                onStaged()
            }
            currentCoroutineContext().ensureActive()
            validate(roomId)
            publish(roomId)
            return clientId
        } finally {
            if (!persisted) withContext(kotlinx.coroutines.NonCancellable) { sources.discard(source) }
        }
    }

    suspend fun available(roomId: String, message: Message): Boolean {
        validate(roomId)
        val available = source(roomId, message) != null
        validate(roomId)
        return available
    }

    suspend fun cancellationRequested(roomId: String, message: Message): Boolean {
        validate(roomId)
        val requested = source(roomId, message)?.file?.cancelRequested ?: true
        validate(roomId)
        return requested
    }

    suspend fun send(roomId: String, clientMsgId: String, progress: (Long, Long, String) -> Unit) {
        transferLock.withLock {
            currentCoroutineContext().ensureActive()
            validate(roomId)
            val message = latest(roomId, clientMsgId) ?: throw IOException("上传消息已不存在")
            val source = source(roomId, message) ?: throw IOException("仅首次发送的本机可以恢复上传")
            if (source.file.cancelRequested) throw IOException("取消尚未确认，请先重试取消发送")
            fun current() {
                validate(roomId)
                val current = latest(roomId, clientMsgId) ?: throw CancellationException("上传消息已移除")
                val extra = (current.body as? TemporaryMessageBody)?.extra
                if (extra == null || extra.cancelled || extra.targetType != source.targetType ||
                    extra.fileName != source.file.fileName || extra.size != source.file.fileSize
                ) throw CancellationException("上传消息已变化")
            }
            current()
            progress(0, source.file.fileSize, "校验中")
            sources.verify(source)
            current()
            val created = request(
                "POST", "/capi/chat/msg/temporary",
                CreateUploadRequest.serializer(), source.createRequest(roomId, clientMsgId),
                ChatMessageResponse.serializer(),
            ).toHistoryMessage(roomId)
            if (created.senderId != owner.userId || created.clientMsgId != clientMsgId ||
                created.type !in setOf(MessageType.TEMPORARY, source.targetType)
            ) throw IOException("上传占位身份不匹配")
            val createdExtra = (created.body as? TemporaryMessageBody)?.extra
            if (createdExtra != null && (createdExtra.cancelled || createdExtra.targetType != source.targetType ||
                createdExtra.fileName != source.file.fileName || createdExtra.size != source.file.fileSize)
            ) throw IOException("上传占位信息不匹配")
            current()
            store.saveMessageHistoryPage(owner, roomId, listOf(created), null)
            publish(roomId)
            if (created.type == source.targetType) {
                cacheUploaded(roomId, source, created.body)
                return@withLock
            }
            if (createdExtra == null) throw IOException("上传占位信息缺失")
            current()
            val poster = source.video?.let video@{ video ->
                posters[clientMsgId]?.let { return@video it }
                val urls = request(
                    "POST", "/capi/oss/upload/url", PosterUploadRequest.serializer(),
                    PosterUploadRequest(roomId, clientMsgId), PosterUploadUrls.serializer(),
                )
                SignedUploads.address(urls.downloadUrl, false)
                current()
                SignedUploads.put(urls.uploadUrl, video.poster, "image/jpeg", requireEtag = false)
                current()
                posters[clientMsgId] = urls.downloadUrl
                if (posters.size > 128) posters.remove(posters.keys.first())
                urls.downloadUrl
            }
            val state = request(
                "POST", "/capi/oss/multipart/initiate", MultipartInitiateRequest.serializer(),
                MultipartInitiateRequest(
                    roomId, clientMsgId, source.targetType, source.file.fileName,
                    source.file.fileSize.toString(), source.file.digest,
                ), MultipartState.serializer(),
            )
            state.validate(source.file.fileSize)
            SignedUploads.address(state.downloadUrl, false)
            current()
            val done = state.parts.map { it.partNumber }.toSet()
            var uploaded = if (state.completed) source.file.fileSize else state.parts.sumOf { it.size }
            progress(uploaded, source.file.fileSize, "上传中")
            if (!state.completed) {
                val file = withContext(Dispatchers.IO) { sources.checkedFile(source) }
                val stream = withContext(Dispatchers.IO) { RandomAccessFile(file, "r") }
                try {
                    for (number in 1..source.file.partDigests.size) {
                        currentCoroutineContext().ensureActive()
                        current()
                        val offset = (number.toLong() - 1) * UPLOAD_PART_BYTES
                        val size = minOf(UPLOAD_PART_BYTES.toLong(), source.file.fileSize - offset).toInt()
                        val buffer = withContext(Dispatchers.IO) {
                            ByteArray(size).also {
                                stream.seek(offset)
                                stream.readFully(it)
                                if (MediaUploadSources.hex(MessageDigest.getInstance("SHA-256").digest(it)) !=
                                    source.file.partDigests[number - 1]
                                ) throw IOException("原文件已变化，不能继续发送")
                            }
                        }
                        if (number in done) continue
                        for (attempt in 0..2) {
                            current()
                            try {
                                val signed = request(
                                    "POST", "/capi/oss/multipart/part-url", MultipartPartRequest.serializer(),
                                    MultipartPartRequest(state.sessionKey, state.uploadId, number), UploadUrl.serializer(),
                                )
                                val url = SignedUploads.address(signed.uploadUrl, true)
                                if (url.queryParameter("uploadId") != state.uploadId ||
                                    url.queryParameter("partNumber") != number.toString()
                                ) throw IOException("分片上传签名不匹配")
                                current()
                                val type = if (source.video == null) "application/octet-stream" else
                                    MediaUploadSources.VIDEO_TYPES[source.file.fileName.substringAfterLast('.').lowercase(java.util.Locale.ROOT)]
                                        ?: throw IOException("视频格式不可用")
                                SignedUploads.put(signed.uploadUrl, buffer, type)
                                current()
                                uploaded += size
                                progress(uploaded, source.file.fileSize, "上传中")
                                break
                            } catch (exception: IOException) {
                                currentCoroutineContext().ensureActive()
                                current()
                                if (exception is InvalidSessionException || attempt == 2) throw exception
                                delay(1000L shl attempt)
                            }
                        }
                    }
            } finally {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { stream.close() }
                }
                current()
                val complete = request(
                    "POST", "/capi/oss/multipart/complete", MultipartIdentity.serializer(),
                    MultipartIdentity(state.sessionKey, state.uploadId), MultipartState.serializer(),
                )
                complete.validate(source.file.fileSize)
                if (!complete.completed || complete.sessionKey != state.sessionKey || complete.uploadId != state.uploadId ||
                    complete.downloadUrl != state.downloadUrl
                ) throw IOException("文件合并未确认，请继续发送")
            }
            current()
            progress(source.file.fileSize, source.file.fileSize, "确认中")
            val body = CompletedUploadBody(
                state.downloadUrl, state.downloadUrl, source.file.fileName, source.file.fileSize.toString(),
                poster, source.video?.poster?.size?.toString(), source.video?.thumbWidth,
                source.video?.thumbHeight, source.video?.duration?.toString(),
            )
            complete(CompleteUploadRequest(
                roomId, created.id, clientMsgId,
                body = body.takeIf { source.video != null }, fileBody = body.takeIf { source.video == null },
            ))
            // HTTP acceptance alone never changes the placeholder to a final media message.
            for (attempt in 0..9) {
                validate(roomId)
                val local = latest(roomId, clientMsgId)
                if (local?.body is FileMessageBody || local?.body is VideoMessageBody) {
                    cacheUploaded(roomId, source, local.body)
                    return@withLock
                }
                val messages = request(
                    "POST", "/capi/chat/msg/temporary/state", UploadStateRequest.serializer(),
                    UploadStateRequest(roomId, listOf(created.id)), ListSerializer(ChatMessageResponse.serializer()),
                ).map { it.toHistoryMessage(roomId) }
                if (messages.size != 1 || messages.single().id != created.id ||
                    messages.single().senderId != owner.userId || messages.single().clientMsgId != clientMsgId
                ) throw IOException("上传确认身份不匹配")
                validate(roomId)
                store.saveMessageHistoryPage(owner, roomId, messages, null)
                publish(roomId)
                when (messages.single().type) {
                    source.targetType -> {
                        cacheUploaded(roomId, source, messages.single().body)
                        return@withLock
                    }
                    MessageType.TEMPORARY -> if ((messages.single().body as? TemporaryMessageBody)?.extra?.cancelled == true) {
                        throw IOException("消息已取消发送")
                    }
                    else -> throw IOException("上传消息已变化")
                }
                delay(1000)
            }
            throw IOException("上传已完成，消息尚未确认，请继续发送以核对状态")
        }
    }

    suspend fun cancel(roomId: String, message: Message) {
        validate(roomId)
        val source = source(roomId, message) ?: throw IOException("本机没有该上传来源")
        val id = message.clientMsgId ?: throw IOException("上传标识无效")
        store.markUploadCancelled(owner, roomId, id, sources.installationId(), source.video != null)
        transferLock.withLock {
            validate(roomId)
            request("POST", "/capi/chat/msg/temporary", CreateUploadRequest.serializer(),
                source.createRequest(roomId, id), ChatMessageResponse.serializer())
            validate(roomId)
            val cancelled = request(
                "POST", "/capi/chat/msg/temporary/cancel", UploadIdentityRequest.serializer(),
                UploadIdentityRequest(roomId, id), ChatMessageResponse.serializer(),
            ).toHistoryMessage(roomId)
            val extra = (cancelled.body as? TemporaryMessageBody)?.extra
            if (cancelled.senderId != owner.userId || cancelled.clientMsgId != id || extra?.cancelled != true) {
                throw IOException("取消发送尚未确认，请重试")
            }
            validate(roomId)
            store.saveMessageHistoryPage(owner, roomId, listOf(cancelled), null)
            publish(roomId)
            withContext(kotlinx.coroutines.NonCancellable) { sources.discard(source) }
            posters.remove(id)
        }
    }

    private suspend fun cacheUploaded(roomId: String, source: MediaUploadSource, body: MessageBody) {
        val metadataMatches = when (body) {
            is FileMessageBody -> source.video == null && body.extra?.let {
                it.fileName == source.file.fileName && it.size == source.file.fileSize
            } == true
            is VideoMessageBody -> source.video?.let { video ->
                body.extra?.let {
                    it.fileName == source.file.fileName && it.size == source.file.fileSize &&
                        it.duration == video.duration && it.thumbWidth == video.thumbWidth &&
                        it.thumbHeight == video.thumbHeight && it.thumbSize == video.poster.size.toLong()
                } == true
            } == true
            else -> false
        }
        if (!metadataMatches) throw IOException("上传完成资源与本机来源不匹配")
        val url = when (body) {
            is FileMessageBody -> body.content
            is VideoMessageBody -> body.content
            else -> null
        } ?: throw IOException("上传完成资源信息缺失")
        SignedUploads.address(url, false)
        val key = CachedVideoFiles.key(url)
        val video = source.video
        val extension = if (video == null) "bin" else source.file.fileName.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
        val relative = "$partition/$key.$extension"
        val target = File(cacheRoot, "${if (video == null) "attachment" else "video"}/$relative")
        validate(roomId)
        withContext(Dispatchers.IO) {
            if (target.parentFile?.let { it.isDirectory || it.mkdirs() } != true) throw IOException("无法保存上传缓存")
            if (!target.isFile || target.length() != source.file.fileSize) {
                val file = sources.checkedFile(source)
                currentCoroutineContext().ensureActive()
                if (!file.renameTo(target)) throw IOException("无法保存上传原文件缓存")
            }
        }
        validate(roomId)
        if (video == null) store.saveAttachmentFile(owner, key, relative) else {
            store.saveVideoFile(owner, key, relative)
            val posterUrl = (body as? VideoMessageBody)?.extra?.thumbUrl ?: return
            SignedUploads.address(posterUrl, false)
            val posterKey = CachedVideoFiles.key(posterUrl)
            val posterRelative = "$partition/$posterKey.jpg"
            val targetPoster = android.util.AtomicFile(File(cacheRoot, "video/$posterRelative"))
            withContext(Dispatchers.IO) {
                val output = targetPoster.startWrite()
                try {
                    output.write(video.poster)
                    targetPoster.finishWrite(output)
                } catch (exception: Exception) {
                    targetPoster.failWrite(output)
                    throw exception
                }
            }
            validate(roomId)
            store.saveVideoFile(owner, posterKey, posterRelative)
        }
    }

    private suspend fun source(roomId: String, message: Message): MediaUploadSource? {
        val extra = (message.body as? TemporaryMessageBody)?.extra ?: return null
        if (message.senderId != owner.userId || extra.cancelled ||
            extra.targetType !in setOf(MessageType.FILE, MessageType.VIDEO)
        ) return null
        val id = message.clientMsgId ?: return null
        val source = store.loadUploadSource(owner, roomId, id, sources.installationId(), extra.targetType == MessageType.VIDEO)
            ?: return null
        if (source.file.fileName != extra.fileName || source.file.fileSize != extra.size ||
            source.file.fileSize !in 1..(if (source.video == null) 6L else 2L) * 1024 * 1024 * 1024 ||
            (source.video != null && (source.video.duration != extra.duration ||
                source.video.thumbWidth != extra.thumbWidth || source.video.thumbHeight != extra.thumbHeight)) ||
            !Regex("[a-f0-9]{64}").matches(source.file.digest) ||
            source.file.partDigests.size.toLong() != (source.file.fileSize + UPLOAD_PART_BYTES - 1) / UPLOAD_PART_BYTES ||
            source.file.partDigests.any { !Regex("[a-f0-9]{64}").matches(it) }
        ) throw IOException("本机上传来源与占位不匹配")
        return source
    }

    private suspend fun complete(request: CompleteUploadRequest) {
        val response = apiRequest("PUT", "/capi/chat/msg/temporary",
            MessageHistoryProtocol.encodeTransfer(CompleteUploadRequest.serializer(), request))
        try {
            MessageHistoryProtocol.decodeSendResult(response)
        } catch (exception: InvalidSessionException) {
            onUnauthorized()
            throw exception
        }
    }

    private suspend fun <T, R> request(
        method: String, path: String, serializer: KSerializer<T>, value: T, response: KSerializer<R>,
    ): R {
        val result = apiRequest(method, path, MessageHistoryProtocol.encodeTransfer(serializer, value))
        return try {
            MessageHistoryProtocol.decodeTransfer(response, result)
        } catch (exception: InvalidSessionException) {
            onUnauthorized()
            throw exception
        }
    }

    private suspend fun apiRequest(method: String, path: String, body: String): String = try {
        api.request(method, path, body = body.toRequestBody("application/json; charset=utf-8".toMediaType())).body
    } catch (exception: ApiHttpException) {
        if (exception.response.statusCode in setOf(401, 403)) {
            onUnauthorized()
            throw InvalidSessionException("登录已过期，请重新登录", exception)
        }
        throw IOException("文件传输请求失败，请重试")
    }

    private companion object {
        val transferLock = Mutex()
    }
}
