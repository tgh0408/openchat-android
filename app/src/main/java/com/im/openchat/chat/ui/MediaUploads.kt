package com.im.openchat.chat.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.im.openchat.chat.data.MediaUploadRepository
import com.im.openchat.chat.data.ImageUploadDraft
import com.im.openchat.chat.data.ImageUploadOperations
import com.im.openchat.chat.data.GallerySelection
import com.im.openchat.chat.data.MAX_GALLERY_SELECTION
import com.im.openchat.chat.data.Message
import com.im.openchat.chat.data.FileMessageBody
import com.im.openchat.chat.data.MessageFileRepository
import com.im.openchat.chat.data.SelectedFileKind
import com.im.openchat.chat.data.selectedFileKind
import com.im.openchat.chat.data.RecallMessageBody
import com.im.openchat.chat.data.TemporaryMessageBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

internal val LocalMediaUploads = staticCompositionLocalOf<MediaUploads?> { null }

internal data class UploadUiState(val active: Boolean = false, val bytes: Long = 0, val total: Long = 0, val label: String = "已暂停")

internal sealed interface UploadSelection {
    data class Device(val uri: Uri, val kind: SelectedFileKind? = null) : UploadSelection
    data class ChatFile(val message: Message, val files: MessageFileRepository) : UploadSelection
}

internal class MediaUploads(
    val repository: MediaUploadRepository,
    private val scope: CoroutineScope,
    private val images: ImageUploadOperations? = null,
    private val onError: suspend (String) -> Unit,
) {
    val states = mutableStateMapOf<String, UploadUiState>()
    val pendingImages = mutableStateListOf<PendingImageUpload>()
    var preparingRoom by mutableStateOf<String?>(null)
        private set
    var imageRoom by mutableStateOf<String?>(null)
        private set
    var imageBusy by mutableStateOf(false)
        private set
    var imageError by mutableStateOf<String?>(null)
        private set
    var albumVideoRoom by mutableStateOf<String?>(null)
        private set
    var fileSelectionRoom by mutableStateOf<String?>(null)
        private set
    val fileSelectionPreparing: Boolean
        get() = fileSelectionRoom != null && imageBusy && imageBatch?.imageId == null
    val fileSelectionError: String?
        get() = imageError.takeIf { fileSelectionRoom != null && imageBatch?.imageId == null }
    val preparing: Boolean get() = preparingRoom != null || imageRoom != null || imageBusy
    private val tasks = mutableMapOf<String, Job>()
    private var preparation: Job? = null
    private var imageTask: Job? = null
    private class AlbumBatch(
        val roomId: String,
        val items: List<UploadSelection>,
        val automatic: Boolean,
        private val onFinished: () -> Unit,
    ) {
        var index = 0
        var draft: ImageUploadDraft? = null
        var imageId: String? = null
        var resolvedName: String? = null
        private var released = false
        fun release() {
            if (!released) {
                released = true
                onFinished()
            }
        }
    }
    private var imageBatch: AlbumBatch? = null

    private fun key(roomId: String, clientMsgId: String) = "$roomId:$clientMsgId"
    fun state(roomId: String, message: Message) = states[key(roomId, message.clientMsgId.orEmpty())]

    fun selectImage(roomId: String, uri: Uri, onFinished: () -> Unit = {}): Boolean =
        selectMedia(roomId, listOf(GallerySelection(uri, false)), onFinished)

    fun selectImages(roomId: String, uris: List<Uri>) {
        selectMedia(roomId, uris.map { GallerySelection(it, false) })
    }

    fun selectMedia(
        roomId: String,
        selections: List<GallerySelection>,
        onFinished: () -> Unit = {},
    ): Boolean {
        if (!scope.isActive || preparing || selections.isEmpty()) return false
        val items = selections.distinctBy { it.uri }
        if (items.size > MAX_GALLERY_SELECTION) {
            scope.launch { onError("一次最多选择 $MAX_GALLERY_SELECTION 项") }
            return false
        }
        if (images == null && items.any { !it.isVideo }) return false
        val batch = AlbumBatch(roomId, items.map {
            UploadSelection.Device(it.uri, if (it.isVideo) SelectedFileKind.VIDEO else SelectedFileKind.IMAGE)
        }, false, onFinished)
        imageBatch = batch
        startImageBatch(batch)
        return true
    }

    fun selectFiles(roomId: String, selections: List<UploadSelection>): Boolean {
        if (!scope.isActive || preparing || selections.isEmpty() || selections.size > MAX_GALLERY_SELECTION) return false
        val items = selections.distinctBy {
            when (it) {
                is UploadSelection.Device -> "uri:${it.uri}"
                is UploadSelection.ChatFile -> "message:${it.message.id}"
            }
        }.map {
            // File-picker inputs always resolve their actual display name, including MediaStore items.
            if (it is UploadSelection.Device) it.copy(kind = null) else it
        }
        val batch = AlbumBatch(roomId, items, true) {}
        imageBatch = batch
        fileSelectionRoom = roomId
        startImageBatch(batch)
        return true
    }

    fun retryImage(id: String? = null) {
        val batch = imageBatch ?: return
        if (id != null && batch.imageId != id) return
        if (!imageBusy && imageError != null) startImageBatch(batch)
    }

    private fun updateImage(id: String?, update: (PendingImageUpload) -> PendingImageUpload) {
        val index = pendingImages.indexOfFirst { it.id == id }
        if (index >= 0) pendingImages[index] = update(pendingImages[index])
    }

    private fun startImageBatch(batch: AlbumBatch) {
        if (imageBusy) return
        imageRoom = batch.roomId
        imageBusy = true
        imageError = null
        updateImage(batch.imageId) { it.copy(error = null) }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            val currentTask = currentCoroutineContext()[Job]
            fun current() = imageBatch === batch && imageTask === currentTask
            try {
                while (batch.index < batch.items.size) {
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@launch
                    val item = batch.items[batch.index]
                    val explicitKind = (item as? UploadSelection.Device)?.kind
                    val name = if (explicitKind != null) null else when (item) {
                        is UploadSelection.Device -> repository.selectedName(batch.roomId, item.uri)
                        is UploadSelection.ChatFile -> (item.message.body as? FileMessageBody)?.extra?.fileName
                            ?: throw IOException("聊天文件不可用")
                    }
                    if (batch.automatic) {
                        if (batch.resolvedName != null && batch.resolvedName != name) {
                            throw IOException("文件名称已变化，请取消后重新选择")
                        }
                        batch.resolvedName = name
                    }
                    val kind = explicitKind ?: selectedFileKind(name.orEmpty())
                    if (kind != SelectedFileKind.IMAGE) {
                        if (kind == SelectedFileKind.VIDEO) albumVideoRoom = batch.roomId
                        try {
                            val onStaged: () -> Unit = {
                                if (batch.automatic) {
                                    batch.index++
                                    batch.resolvedName = null
                                }
                            }
                            val id = when (item) {
                                is UploadSelection.Device -> repository.stage(
                                    batch.roomId, item.uri, kind == SelectedFileKind.VIDEO, name, onStaged,
                                )
                                is UploadSelection.ChatFile -> repository.stageChatFile(
                                    batch.roomId, item.message, item.files, onStaged, kind == SelectedFileKind.VIDEO,
                                )
                            }
                            currentCoroutineContext().ensureActive()
                            if (!current()) return@launch
                            send(batch.roomId, id)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            if (!current()) return@launch
                            if (batch.automatic) throw exception
                            // A persisted video belongs to the existing resumable upload workflow.
                            onError((exception as? IOException)?.message ?: "视频准备失败，请重新选择")
                        } finally {
                            if (current()) albumVideoRoom = null
                        }
                        if (!batch.automatic) batch.index++
                        continue
                    }
                    val imageRepository = images ?: throw IOException("图片发送尚未就绪")
                    if (batch.imageId == null) {
                        val pending = PendingImageUpload(batch.roomId)
                        batch.imageId = pending.id
                        pendingImages += pending
                    }
                    val draft = batch.draft ?: try {
                        when (item) {
                            is UploadSelection.Device -> imageRepository.prepare(batch.roomId, item.uri)
                            is UploadSelection.ChatFile -> {
                                val original = item.files.download(batch.roomId, item.message) { _, _ -> }
                                val body = item.message.body as FileMessageBody
                                imageRepository.prepareCached(
                                    batch.roomId, original, body.extra?.fileName.orEmpty(), body.extra?.size ?: 0L,
                                )
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        if (!current()) return@launch
                        if (batch.automatic) throw exception
                        pendingImages.removeAll { it.id == batch.imageId }
                        batch.imageId = null
                        batch.index++
                        onError((exception as? IOException)?.message ?: "无法读取所选图片，请重新选择")
                        continue
                    }
                    if (!current()) {
                        imageRepository.discard(draft)
                        return@launch
                    }
                    batch.draft = draft
                    updateImage(batch.imageId) { it.copy(draft = draft) }
                    if (name != null && draft.name != name) throw IOException("文件名称已变化，请取消后重新选择")
                    if (item is UploadSelection.ChatFile) item.files.status(batch.roomId, item.message)
                    imageRepository.send(draft)
                    // Once queued, never retry this entry as a new upload.
                    updateImage(batch.imageId) { it.copy(queued = true) }
                    batch.imageId = null
                    batch.draft = null
                    batch.index++
                    batch.resolvedName = null
                    imageRepository.discard(draft)
                }
                if (current()) {
                    imageBatch = null
                    imageRoom = null
                    fileSelectionRoom = null
                }
            } catch (exception: CancellationException) {
                if (current()) cancelImage()
                throw exception
            } catch (exception: Exception) {
                if (current()) {
                    val fallback = if (batch.automatic) "附件发送失败，请重试" else "图片发送失败，请重试"
                    val message = (exception as? IOException)?.message ?: fallback
                    imageError = message
                    updateImage(batch.imageId) { it.copy(error = message) }
                }
            } finally {
                if (imageBatch !== batch) batch.release()
                if (imageTask === currentCoroutineContext()[Job]) {
                    imageTask = null
                    imageBusy = false
                }
            }
        }
        imageTask = task
        task.start()
    }

    fun cancelImage(id: String? = null) {
        if (id != null && imageBatch?.imageId != id) return
        val task = imageTask
        val batch = imageBatch
        val draft = batch?.draft
        imageTask = null
        imageBatch = null
        imageRoom = null
        imageBusy = false
        imageError = null
        albumVideoRoom = null
        fileSelectionRoom = null
        pendingImages.removeAll { !it.queued }
        task?.cancel()
        if (batch != null) scope.launch(NonCancellable) {
            task?.join()
            if (draft != null) images?.discard(draft)
            batch.release()
        }
    }

    fun select(roomId: String, uri: Uri, video: Boolean) {
        if (preparing) return
        preparingRoom = roomId
        preparation = scope.launch {
            try {
                val id = repository.stage(roomId, uri, video)
                send(roomId, id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                onError(if (exception is IOException) exception.message ?: "文件准备失败" else "无法读取所选文件，请重新选择")
            } finally {
                preparingRoom = null
            }
        }
    }

    fun send(roomId: String, clientMsgId: String) {
        val key = key(roomId, clientMsgId)
        if (tasks[key]?.isActive == true) return
        states[key] = UploadUiState(active = true, label = "等待上传")
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                repository.send(roomId, clientMsgId) { bytes, total, label ->
                    states[key] = UploadUiState(true, bytes, total, label)
                }
                states.remove(key)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                states[key] = (states[key] ?: UploadUiState()).copy(active = false, label = "发送失败")
                onError(exception.message ?: "发送失败，已完成分片保留")
            } finally {
                if (tasks[key] === currentCoroutineContext()[Job]) {
                    states[key]?.let { if (it.active) states[key] = it.copy(active = false, label = "已暂停") }
                    tasks.remove(key)
                }
            }
        }
        tasks[key] = task
        task.start()
    }

    fun pause(roomId: String, message: Message) {
        tasks[key(roomId, message.clientMsgId.orEmpty())]?.cancel()
    }

    fun cancel(roomId: String, message: Message) {
        val key = key(roomId, message.clientMsgId.orEmpty())
        val previous = tasks[key]
        previous?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                states[key] = UploadUiState(active = true, label = "取消中")
                repository.cancel(roomId, message)
                states.remove(key)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                states[key] = UploadUiState(label = "取消失败")
                onError(exception.message ?: "取消尚未确认，请重试")
            } finally {
                if (tasks[key] === currentCoroutineContext()[Job]) {
                    states[key]?.let { if (it.active) states[key] = it.copy(active = false, label = "取消待确认") }
                    tasks.remove(key)
                }
            }
        }
        tasks[key] = task
        task.start()
    }

    fun pauseAll(includeImages: Boolean = true, clearImageDisplay: Boolean = false) {
        if (includeImages) cancelImage()
        if (clearImageDisplay) pendingImages.clear()
        preparation?.cancel()
        tasks.values.toList().forEach { it.cancel() }
    }

    fun reconcile(roomId: String, messages: List<Message>) {
        pendingImages.removeAll { it.roomId == roomId && it.isPublished(messages) }
        tasks.toMap().forEach { (key, task) ->
            if (!key.startsWith("$roomId:")) return@forEach
            val id = key.substringAfter(':')
            val current = messages.firstOrNull { it.clientMsgId == id && it.outgoing }
            if (current == null || current.body is RecallMessageBody ||
                ((current.body as? TemporaryMessageBody)?.extra?.cancelled == true &&
                    states[key]?.label != "取消中")
            ) task.cancel()
        }
    }
}
