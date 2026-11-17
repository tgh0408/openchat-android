package com.im.openchat.chat.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

internal data class GalleryAsset(
    val uri: Uri,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val isVideo: Boolean,
    val addedAt: Long,
    val durationMillis: Long = 0,
    val sizeBytes: Long? = null,
)

internal data class GallerySource(val id: String, val name: String, val assets: List<GalleryAsset>)
internal data class GallerySelection(val uri: Uri, val isVideo: Boolean)

internal const val GALLERY_ALL = "all"
internal const val GALLERY_VIDEOS = "videos"
internal const val MAX_GALLERY_SELECTION = 9
private val thumbnailPermits = Semaphore(4)

internal fun gallerySources(assets: List<GalleryAsset>): List<GallerySource> = buildList {
    add(GallerySource(GALLERY_ALL, "图片和视频", assets))
    add(GallerySource(GALLERY_VIDEOS, "所有视频", assets.filter { it.isVideo }))
    assets.groupBy { it.sourceId }.forEach { (id, items) ->
        add(GallerySource(id, items.first().sourceName, items))
    }
}

internal fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun Context.hasGalleryPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasGalleryAccess(): Boolean = galleryPermissions().any { hasGalleryPermission(it) }

internal fun Context.hasLimitedGalleryAccess(): Boolean = Build.VERSION.SDK_INT >= 34 &&
    hasGalleryPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
    (!hasGalleryPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
        !hasGalleryPermission(Manifest.permission.READ_MEDIA_VIDEO))

/** Metadata only; thumbnails are decoded on demand by the visible grid. */
internal suspend fun queryGallery(context: Context): List<GalleryAsset> = withContext(Dispatchers.IO) {
    val result = mutableListOf<GalleryAsset>()
    val partial = Build.VERSION.SDK_INT >= 34 &&
        context.hasGalleryPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context)
        else setOf("external")
    for (volume in volumes) {
        for (video in listOf(false, true)) {
            ensureActive()
            val permission = if (Build.VERSION.SDK_INT >= 33) {
                if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
            } else Manifest.permission.READ_EXTERNAL_STORAGE
            if (!partial && !context.hasGalleryPermission(permission)) continue
            val collection = if (video) MediaStore.Video.Media.getContentUri(volume)
                else MediaStore.Images.Media.getContentUri(volume)
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATE_ADDED)
                add(MediaStore.Images.ImageColumns.BUCKET_ID)
                add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
                add(MediaStore.MediaColumns.SIZE)
                if (video) add(MediaStore.Video.VideoColumns.DURATION)
            }.toTypedArray()
            context.contentResolver.query(
                collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    ensureActive()
                    result += GalleryAsset(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(0)),
                        sourceId = "bucket:$volume:${cursor.getString(3).orEmpty()}",
                        sourceName = cursor.getString(4)?.takeIf { it.isNotBlank() } ?: "其他",
                        name = cursor.getString(1).orEmpty(),
                        isVideo = video,
                        addedAt = cursor.getLong(2),
                        durationMillis = if (video) cursor.getLong(6).coerceAtLeast(0) else 0,
                        sizeBytes = if (cursor.isNull(5)) null else cursor.getLong(5).takeIf { it >= 0 },
                    )
                }
            } ?: throw IOException("无法读取相册，请重试")
        }
    }
    result.sortedWith(compareByDescending<GalleryAsset> { it.addedAt }.thenBy { it.uri.toString() })
}

@Suppress("DEPRECATION")
internal suspend fun galleryThumbnail(context: Context, asset: GalleryAsset): Bitmap? =
    thumbnailPermits.withPermit {
        withContext(Dispatchers.IO) {
            ensureActive()
            // Older platform thumbnail APIs also bound decode size and return video poster frames.
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(asset.uri, Size(256, 256), CancellationSignal())
            } else if (asset.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, ContentUris.parseId(asset.uri),
                    MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver, ContentUris.parseId(asset.uri),
                    MediaStore.Images.Thumbnails.MINI_KIND, null,
                )
            }
            ensureActive()
            bitmap
        }
    }
