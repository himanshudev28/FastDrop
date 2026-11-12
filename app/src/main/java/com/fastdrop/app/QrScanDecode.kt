package com.fastdrop.app

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.hardware.Camera
import android.net.Uri
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera1 preview geometry: frame size, display rotation and whether it is the back camera. */
internal class ScanPreview(val w: Int, val h: Int, val rot: Int, val back: Boolean)

/**
 * Frame decoding for the scanner. Behaviour is unchanged from the original: one decode at a time
 * (extra frames are dropped), the scan window is tried first, and every sixth failed frame also
 * tries the whole frame, inverted.
 */
internal class ScanDecoder(private val main: Handler, private val onText: (String) -> Unit) {
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var failFrames = 0

    fun reset() = busy.set(false)

    fun shutdown() { exec.shutdownNow() }

    /** Preview callback entry. Always hands the buffer back to the camera. */
    fun submit(data: ByteArray?, c: Camera, pv: ScanPreview?, win: ScanOverlay.Win?, done: AtomicBoolean) {
        fun recycle() { if (data != null) try { c.addCallbackBuffer(data) } catch (_: Throwable) {} }
        if (data == null || pv == null || done.get()) { recycle(); return }
        if (!busy.compareAndSet(false, true)) { recycle(); return }
        val tryFull = (++failFrames % 6 == 0)
        try {
            exec.execute {
                try {
                    var text: String? = null
                    val crop = if (win != null && pv.back) cropFor(win, pv) else null
                    if (crop != null) {
                        text = Qr.decodeYuv(data, pv.w, pv.h, crop[0], crop[1], crop[2], crop[3])
                    }
                    if (text == null && (crop == null || tryFull)) {
                        text = Qr.decodeYuv(data, pv.w, pv.h, tryInverted = tryFull)
                    }
                    if (text != null) main.post { onText(text) }
                } catch (_: Throwable) {
                } finally {
                    busy.set(false)
                    recycle()
                }
            }
        } catch (_: Exception) {   // executor shut down
            busy.set(false)
            recycle()
        }
    }

    companion object {
        /** Maps the on-screen scan window to a padded rectangle in the sensor frame: [left, top, w, h]. */
        fun cropFor(win: ScanOverlay.Win, pv: ScanPreview): IntArray? {
            val sideways = pv.rot % 180 != 0
            val pw = (if (sideways) pv.h else pv.w).toFloat()    // preview size as displayed
            val ph = (if (sideways) pv.w else pv.h).toFloat()
            val s = maxOf(win.vw / pw, win.vh / ph)
            val offX = (win.vw - pw * s) / 2f
            val offY = (win.vh - ph * s) / 2f
            val pad = win.rect.width() * 0.08f
            val dl = ((win.rect.left - pad - offX) / s).coerceIn(0f, pw)
            val dt = ((win.rect.top - pad - offY) / s).coerceIn(0f, ph)
            val dr = ((win.rect.right + pad - offX) / s).coerceIn(0f, pw)
            val db = ((win.rect.bottom + pad - offY) / s).coerceIn(0f, ph)
            val w = pv.w.toFloat()
            val h = pv.h.toFloat()
            // display -> sensor coordinates
            val l: Float; val t: Float; val r: Float; val b: Float
            when (pv.rot) {
                90 -> { l = dt; r = db; t = h - dr; b = h - dl }
                270 -> { l = w - db; r = w - dt; t = dl; b = dr }
                180 -> { l = w - dr; r = w - dl; t = h - db; b = h - dt }
                else -> { l = dl; r = dr; t = dt; b = db }
            }
            val cw = (r - l).toInt()
            val ch = (b - t).toInt()
            if (cw < 40 || ch < 40) return null
            return intArrayOf(l.toInt(), t.toInt(), cw, ch)
        }

        /** Decodes a QR code from a photo (screenshot, gallery). Null when none is found. */
        fun readPhoto(cr: ContentResolver, uri: Uri): String? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                val big = maxOf(bounds.outWidth, bounds.outHeight)
                while (big / sample > 3000) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: return null
                try { Qr.decode(bmp) } finally { bmp.recycle() }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
