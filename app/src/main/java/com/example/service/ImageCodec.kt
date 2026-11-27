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
        val scaled = scaleBitmapDown(bitmap, maxDimension)
        return try {
            var quality = initialQuality
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 10
            } while (bytes.size > maxBytes && quality >= 20)
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            if (scaled !== bitmap) {
                scaled.recycle()
            }
        }
    }

    fun decodeBase64ToBitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return try {
            val raw = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeUriToBase64(
        context: Context,
        uri: Uri,
        maxDimension: Int = 960,
        maxBytes: Int = 260_000
    ): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            val encoded = encodeBitmapToBase64(
                bitmap = bitmap,
                maxDimension = maxDimension,
                maxBytes = maxBytes,
                initialQuality = 76
            )
            bitmap.recycle()
            encoded
        } catch (_: Exception) {
            null
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
