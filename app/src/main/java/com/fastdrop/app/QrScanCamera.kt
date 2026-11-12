package com.fastdrop.app

import android.app.Activity
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Build
import android.os.Handler
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Camera1 session for the scanner: open on a worker thread, back camera, barcode scene and
 * continuous focus where available, torch, display rotation and the centre-crop transform for a
 * TextureView. Logic is unchanged from the original QrScanActivity; only moved here.
 * The onOpened / onFailed callbacks run on the main thread.
 */
internal class ScanCamera(private val act: Activity, private val main: Handler) {
    private val exec = Executors.newSingleThreadExecutor()
    private val gen = AtomicInteger(0)
    private val lock = Any()
    private var cam: Camera? = null

    @Volatile var preview: ScanPreview? = null
        private set
    var torchOn = false
        private set

    /** Called from the camera thread with every preview frame. */
    var onFrame: ((ByteArray?, Camera) -> Unit)? = null

    val current: Camera? get() = synchronized(lock) { cam }

    // ---- rotation ----

    private fun rotationDegrees(): Int {
        val rot = try {
            if (Build.VERSION.SDK_INT >= 30) act.display?.rotation ?: Surface.ROTATION_0
            else @Suppress("DEPRECATION") act.windowManager.defaultDisplay.rotation
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun displayOrientation(info: Camera.CameraInfo): Int {
        val deg = rotationDegrees()
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + deg) % 360) % 360
        } else {
            (info.orientation - deg + 360) % 360
        }
    }

    private fun backCameraId(info: Camera.CameraInfo): Int {
        var id = 0
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break }
        }
        Camera.getCameraInfo(id, info)
        return id
    }

    // ---- open / close ----

    fun start(st: SurfaceTexture, vw: Int, vh: Int, onOpened: (hasTorch: Boolean) -> Unit, onFailed: () -> Unit) {
        synchronized(lock) { if (cam != null) return }
        val g = gen.incrementAndGet()
        exec.execute { open(g, st, vw, vh, onOpened, onFailed) }
    }

    private fun open(g: Int, st: SurfaceTexture, vw: Int, vh: Int, onOpened: (Boolean) -> Unit, onFailed: () -> Unit) {
        if (g != gen.get()) return
        var c: Camera? = null
        try {
            val info = Camera.CameraInfo()
            if (Camera.getNumberOfCameras() <= 0) throw IllegalStateException("no camera")
            val id = backCameraId(info)
            c = Camera.open(id)
            val p = c.parameters

            // pick a preview size close to the screen's aspect and not too heavy to decode
            val target = maxOf(vw, vh).toFloat() / maxOf(1, minOf(vw, vh))
            val sizes = p.supportedPreviewSizes ?: emptyList()
            val ok = sizes.filter { it.width * it.height <= 1280 * 960 }.ifEmpty { sizes.sortedBy { it.width * it.height }.take(1) }
            if (ok.isNotEmpty()) {
                val minDiff = ok.minOf { Math.abs(it.width.toFloat() / it.height - target) }
                val best = ok.filter { Math.abs(it.width.toFloat() / it.height - target) <= minDiff + 0.02f }
                    .maxByOrNull { it.width * it.height }!!
                p.setPreviewSize(best.width, best.height)
            }
            val modes = p.supportedFocusModes ?: emptyList()
            when {
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                Camera.Parameters.FOCUS_MODE_AUTO in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            if (Camera.Parameters.SCENE_MODE_BARCODE in (p.supportedSceneModes ?: emptyList())) {
                p.sceneMode = Camera.Parameters.SCENE_MODE_BARCODE
            }
            val hasTorch = Camera.Parameters.FLASH_MODE_TORCH in (p.supportedFlashModes ?: emptyList())
            if (hasTorch && torchOn) p.flashMode = Camera.Parameters.FLASH_MODE_TORCH
            c.parameters = p

            val orient = displayOrientation(info)
            c.setDisplayOrientation(orient)
            val size = c.parameters.previewSize
            c.setPreviewTexture(st)
            val cb = c
            val bufSize = size.width * size.height * 3 / 2   // NV21 (the default preview format)
            for (i in 0 until 3) c.addCallbackBuffer(ByteArray(bufSize))
            c.setPreviewCallbackWithBuffer { data, _ -> onFrame?.invoke(data, cb) }

            if (g != gen.get()) {       // paused while opening
                c.release()
                return
            }
            preview = ScanPreview(size.width, size.height, orient, info.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
            c.startPreview()
            val stale = synchronized(lock) {
                if (g != gen.get()) true else { cam = c; false }
            }
            if (stale) {            // released while opening: don't publish, close it now
                try { c.setPreviewCallback(null) } catch (_: Throwable) {}
                try { c.stopPreview() } catch (_: Throwable) {}
                try { c.release() } catch (_: Throwable) {}
                return
            }
            main.post { if (g == gen.get()) onOpened(hasTorch) }
        } catch (_: Throwable) {
            try { c?.release() } catch (_: Throwable) {}
            synchronized(lock) { cam = null }
            main.post { if (g == gen.get()) onFailed() }
        }
    }

    fun release() {
        gen.incrementAndGet()
        val c = synchronized(lock) { cam.also { cam = null } }
        preview = null
        if (c != null) {
            exec.execute {
                try { c.setPreviewCallback(null) } catch (_: Throwable) {}
                try { c.stopPreview() } catch (_: Throwable) {}
                try { c.release() } catch (_: Throwable) {}
            }
        }
    }

    /** Queued releases still run. */
    fun shutdown() { exec.shutdown() }

    /** Stops frame delivery (after a successful scan). */
    fun stopFrames() {
        try { current?.setPreviewCallback(null) } catch (_: Throwable) {}
    }

    fun applyDisplayOrientation() {
        val c = current ?: return
        val old = preview ?: return
        try {
            val info = Camera.CameraInfo()
            backCameraId(info)
            val orient = displayOrientation(info)
            c.setDisplayOrientation(orient)
            preview = ScanPreview(old.w, old.h, orient, old.back)
        } catch (_: Throwable) {
        }
    }

    /** Centre-crop matrix for a TextureView of [vw] x [vh] (the default stretches the preview). */
    fun transform(vw: Float, vh: Float): Matrix? {
        val pv = preview ?: return null
        if (vw <= 0f || vh <= 0f) return null
        val sideways = pv.rot % 180 != 0
        val pw = (if (sideways) pv.h else pv.w).toFloat()
        val ph = (if (sideways) pv.w else pv.h).toFloat()
        val s = maxOf(vw / pw, vh / ph)
        return Matrix().apply { setScale(pw * s / vw, ph * s / vh, vw / 2f, vh / 2f) }
    }

    fun focusNow() {
        val c = current ?: return
        try {
            if (c.parameters.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) c.autoFocus(null)
        } catch (_: Throwable) {
        }
    }

    /** Toggles the torch; returns the new state (false if it could not be switched). */
    fun toggleTorch(): Boolean {
        val c = current ?: return false
        return try {
            val p = c.parameters
            torchOn = !torchOn
            p.flashMode = if (torchOn) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
            torchOn
        } catch (_: Throwable) {
            torchOn = false
            false
        }
    }
}
