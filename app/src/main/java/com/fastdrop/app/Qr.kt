package com.fastdrop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR encode/decode helpers on top of ZXing core. Pure functions, safe on any thread. */
object Qr {

    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** Module matrix with no margin (1 bit = 1 module). Null if the text is empty or too long for a QR. */
    fun matrix(text: String, highEcc: Boolean = false): BitMatrix? {
        if (text.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to (if (highEcc) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M),
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            // Size 0x0 asks the writer for the smallest output: exactly one pixel per module.
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: Exception) {
            null
        }
    }

    /** Plain square QR bitmap with a 4-module quiet zone (for sharing / saving). */
    fun bitmap(text: String, sizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap? {
        val m = matrix(text) ?: return null
        val n = m.width
        val quiet = 4
        val size = maxOf(sizePx, n + quiet * 2)
        val cell = size / (n + quiet * 2)
        val off = (size - cell * n) / 2
        return try {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(light)
            val p = Paint().apply { color = dark; isAntiAlias = false }
            for (y in 0 until n) for (x in 0 until n) {
                if (m.get(x, y)) {
                    val l = (off + x * cell).toFloat()
                    val t = (off + y * cell).toFloat()
                    c.drawRect(l, t, l + cell, t + cell, p)
                }
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** "Scan from photo": finds a QR code in any bitmap. Tries full size, then a smaller copy. */
    fun decode(bitmap: Bitmap): String? {
        if (bitmap.width < 21 || bitmap.height < 21) return null
        var scaled: Bitmap? = null
        try {
            val big = maxOf(bitmap.width, bitmap.height)
            val first = if (big > 2400) scale(bitmap, 2400) else bitmap
            if (first !== bitmap) scaled = first
            decodePixels(first)?.let { return it }
            // A big photo with a small code often reads better once shrunk.
            if (big > 1000) {
                val second = scale(bitmap, 900)
                try {
                    decodePixels(second)?.let { return it }
                } finally {
                    if (second !== bitmap) second.recycle()
                }
            }
            return null
        } catch (_: Throwable) {
            return null
        } finally {
            scaled?.recycle()
        }
    }

    private fun scale(src: Bitmap, maxSide: Int): Bitmap {
        val big = maxOf(src.width, src.height)
        if (big <= maxSide) return src
        val f = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(src, maxOf(1, (src.width * f).toInt()), maxOf(1, (src.height * f).toInt()), true)
    }

    private fun decodePixels(b: Bitmap): String? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return decodeSource(RGBLuminanceSource(w, h, px), tryInverted = true)
    }

    /**
     * Decodes a camera NV21 frame (only the Y plane is read), optionally cropped to a window.
     * Crop values are in the frame's own (sensor) coordinates.
     */
    fun decodeYuv(
        data: ByteArray, width: Int, height: Int,
        left: Int = 0, top: Int = 0, cropW: Int = width, cropH: Int = height,
        tryInverted: Boolean = false,
    ): String? {
        if (width <= 0 || height <= 0 || data.size < width * height) return null
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val cw = cropW.coerceIn(1, width - l)
        val ch = cropH.coerceIn(1, height - t)
        return try {
            decodeSource(PlanarYUVLuminanceSource(data, width, height, l, t, cw, ch, false), tryInverted)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSource(src: LuminanceSource, tryInverted: Boolean): String? {
        val sources = if (tryInverted) listOf(src, src.invert()) else listOf(src)
        for (s in sources) {
            // Hybrid handles uneven light; the global one rescues flat, high-contrast images.
            for (global in booleanArrayOf(false, true)) {
                val reader = QRCodeReader()
                try {
                    val bin = if (global) GlobalHistogramBinarizer(s) else HybridBinarizer(s)
                    return reader.decode(BinaryBitmap(bin), decodeHints).text
                } catch (_: Exception) {
                    // not found / checksum / format: try the next variant
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }
}
