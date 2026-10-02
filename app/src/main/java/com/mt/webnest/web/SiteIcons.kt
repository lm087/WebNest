package com.mt.webnest.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object SiteIcons {
    const val MAX_BYTES = 4 * 1024 * 1024

    fun normalize(bytes: ByteArray): ByteArray? {
        if (bytes.size > MAX_BYTES) return null
        val bitmap =
            if (
                bytes.size >= 6 &&
                    bytes[0] == 0.toByte() &&
                    bytes[1] == 0.toByte() &&
                    bytes[2] == 1.toByte() &&
                    bytes[3] == 0.toByte()
            )
                decodeIco(bytes)
            else decodeBitmap(bytes) ?: decodeSvg(bytes)
        bitmap ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun decodeSvg(bytes: ByteArray): Bitmap? = runCatching {
        SVG.setInternalEntitiesEnabled(false)
        val svg = SVG.getFromInputStream(bytes.inputStream())
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0)
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            svg.renderToCanvas(Canvas(bitmap))
            bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }
        .getOrNull()

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = 1
                while (
                    maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256
                ) inSampleSize *= 2
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun decodeIco(bytes: ByteArray): Bitmap? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getShort(4).toInt() and 0xffff
        val entries =
            (0 until minOf(count, 64))
                .mapNotNull { index ->
                    val offset = 6 + index * 16
                    if (offset + 16 > bytes.size) return@mapNotNull null
                    val size = buffer.getInt(offset + 8)
                    val start = buffer.getInt(offset + 12)
                    if (start < 6 + count * 16 || size < 8 || start.toLong() + size > bytes.size)
                        return@mapNotNull null
                    val width = (bytes[offset].toInt() and 255).let { if (it == 0) 256 else it }
                    val height =
                        (bytes[offset + 1].toInt() and 255).let { if (it == 0) 256 else it }
                    Triple(bytes.copyOfRange(start, start + size), width, height)
                }
                .sortedByDescending { it.second * it.third }
        return entries.firstNotNullOfOrNull { (data, width, height) ->
            if (data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()) decodeBitmap(data)
            else runCatching { decodeDib(data, width, height) }.getOrNull()
        }
    }

    private fun decodeDib(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (data.size < 40) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val header = buffer.getInt(0)
        if (
            header < 40 ||
                header > data.size ||
                buffer.getInt(4) != width ||
                abs(buffer.getInt(8)) < height
        )
            return null
        if (buffer.getShort(12).toInt() != 1 || buffer.getInt(16) != 0) return null
        val bits = buffer.getShort(14).toInt() and 0xffff
        if (bits !in listOf(1, 4, 8, 24, 32)) return null
        val paletteCount =
            if (bits <= 8) buffer.getInt(32).let { if (it == 0) 1 shl bits else it } else 0
        if (paletteCount < 0 || paletteCount > 256) return null
        val pixelStart = header + paletteCount * 4
        val stride = ((width * bits + 31) / 32) * 4
        val maskStart = pixelStart + stride * height
        if (pixelStart > data.size || maskStart > data.size) return null
        val maskStride = ((width + 31) / 32) * 4
        val hasMask = maskStart + maskStride * height <= data.size
        val topDown = buffer.getInt(8) < 0
        val pixels = IntArray(width * height)
        var hasAlpha = false
        fun byte(index: Int) = data[index].toInt() and 255
        for (y in 0 until height) for (x in 0 until width) {
            val row = pixelStart + (if (topDown) y else height - 1 - y) * stride
            val color =
                if (bits >= 24) {
                    val at = row + x * (bits / 8)
                    val alpha = if (bits == 32) byte(at + 3) else 255
                    if (bits == 32 && alpha != 0) hasAlpha = true
                    (alpha shl 24) or (byte(at + 2) shl 16) or (byte(at + 1) shl 8) or byte(at)
                } else {
                    val index =
                        when (bits) {
                            8 -> byte(row + x)
                            4 -> (byte(row + x / 2) shr (if (x % 2 == 0) 4 else 0)) and 15
                            else -> (byte(row + x / 8) shr (7 - x % 8)) and 1
                        }
                    if (index >= paletteCount) return null
                    val at = header + index * 4
                    (255 shl 24) or (byte(at + 2) shl 16) or (byte(at + 1) shl 8) or byte(at)
                }
            pixels[y * width + x] = color
        }
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (bits == 32 && !hasAlpha) pixels[index] = pixels[index] or (255 shl 24)
            if (hasMask) {
                val row = maskStart + (if (topDown) y else height - 1 - y) * maskStride
                if ((byte(row + x / 8) shr (7 - x % 8)) and 1 != 0)
                    pixels[index] = pixels[index] and 0x00ffffff
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
