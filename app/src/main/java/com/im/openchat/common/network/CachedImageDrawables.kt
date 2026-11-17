package com.im.openchat.common.network

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import pl.droidsonroids.gif.GifDrawable
import java.io.File
import java.io.IOException

/** 原始文件始终保留动图数据；解码失败交给缓存层删除并重试。 */
internal fun decodeImageDrawable(resources: Resources, file: File, maxBytes: Long, dimension: Int): Drawable? {
    if (file.length() !in 1L..maxBytes) return null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width <= 0 || height <= 0 || width.toLong() * height > 100_000_000L) {
                    throw IOException("表情包尺寸过大")
                }
                val ratio = minOf(1f, dimension.toFloat() / maxOf(width, height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
                decoder.setOnPartialImageListener { false }
            }
        } else {
            decodeLegacyDrawable(resources, file, dimension)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (exception: LinkageError) {
        // A missing native decoder is not a corrupt cached image.
        throw IOException("图片解码器不可用", exception)
    }
}

private fun decodeLegacyDrawable(resources: Resources, file: File, dimension: Int): Drawable? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    val pixels = options.outWidth.toLong() * options.outHeight
    if (options.outWidth <= 0 || options.outHeight <= 0 || pixels > 100_000_000L) return null
    if (options.outMimeType == "image/gif") {
        // Keep the legacy pixel limit; playback starts only when the item is visible.
        if (pixels > 4_000_000L) return null
        return GifDrawable(file).apply { stop() }
    }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > dimension) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.path, options)?.let {
        it.density = Bitmap.DENSITY_NONE
        BitmapDrawable(resources, it)
    }
}
