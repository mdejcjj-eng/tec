package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ImageCodec {

    fun encodeBitmapToBase64(
        bitmap: Bitmap,
        maxDimension: Int = 540,
        maxBytes: Int = 220_000,
        initialQuality: Int = 62
    ): String {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ""
        var current: Bitmap? = null
        return try {
            current = scaleBitmapDown(bitmap, maxDimension)
            var bytes = ByteArray(0)
            var dim = maxDimension
            for (attempt in 0..3) {
                val active = current ?: break
                var quality = if (attempt == 0) initialQuality else 42
                do {
                    val out = ByteArrayOutputStream()
                    active.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    bytes = out.toByteArray()
                    quality -= 10
                } while (bytes.size > maxBytes && quality >= 20)
                if (bytes.size <= maxBytes || dim <= 180) break
                dim = (dim * 0.72f).toInt().coerceAtLeast(180)
                val next = scaleBitmapDown(active, dim)
                if (active !== bitmap && active !== next && !active.isRecycled) {
                    active.recycle()
                }
                current = next
            }
            if (bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        } finally {
            val active = current
            if (active != null && active !== bitmap && !active.isRecycled) {
                active.recycle()
            }
        }
    }

    fun decodeBase64ToBitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return try {
            val raw = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.takeIf { !it.isRecycled }
        } catch (_: Throwable) {
            null
        }
    }

    fun encodeUriToBase64(
        context: Context,
        uri: Uri,
        maxDimension: Int = 960,
        maxBytes: Int = 260_000
    ): String? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.isMutableRequired = false
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val largest = max(srcW, srcH)
                    if (largest > maxDimension && largest > 0) {
                        val ratio = maxDimension.toFloat() / largest.toFloat()
                        decoder.setTargetSize(
                            (srcW * ratio).toInt().coerceAtLeast(1),
                            (srcH * ratio).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                var sampleSize = 1
                val largest = max(bounds.outWidth, bounds.outHeight)
                if (largest > maxDimension && maxDimension > 0) {
                    while ((largest / (sampleSize * 2)) >= maxDimension) {
                        sampleSize *= 2
                    }
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                } ?: @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            val loaded = bitmap ?: return null
            encodeBitmapToBase64(
                bitmap = loaded,
                maxDimension = maxDimension,
                maxBytes = maxBytes,
                initialQuality = 76
            ).takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } finally {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun scaleBitmapDown(source: Bitmap, maxDimension: Int): Bitmap {
        val width = source.width
        val height = source.height
        val largest = max(width, height)
        if (largest <= maxDimension || largest <= 0) return source
        val ratio = maxDimension.toFloat() / largest.toFloat()
        val targetW = (width * ratio).toInt().coerceAtLeast(1)
        val targetH = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }
}
