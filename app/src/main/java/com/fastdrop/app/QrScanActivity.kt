package com.fastdrop.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-screen QR scanner on Camera1 + TextureView (no AndroidX). Returns the scanned text in
 * [EXTRA_TEXT]. Only FastDrop codes (fastdrop://join...) are accepted. "Scan from photo" works
 * even without camera or permission.
 */
class QrScanActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        private const val REQ_CAMERA = 41
        private const val REQ_PHOTO = 42

        fun intent(ctx: Context, title: String? = null): Intent =
            Intent(ctx, QrScanActivity::class.java).apply {
                if (title != null) putExtra(EXTRA_TITLE, title)
            }

        fun hasCamera(ctx: Context): Boolean {
            val pm = ctx.packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        }
    }

    private class Preview(val w: Int, val h: Int, val rot: Int, val back: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val camExec = Executors.newSingleThreadExecutor()
    private val decodeExec = Executors.newSingleThreadExecutor()
    private val camGen = AtomicInteger(0)
    private val busy = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private val camLock = Any()

    private var cam: Camera? = null
    @Volatile private var preview: Preview? = null
    private var failFrames = 0

    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var overlay: ScanOverlay
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var hint: TextView
    private lateinit var torchBtn: PillButton
    private lateinit var photoBtn: PillButton
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelBtn: PillButton

    private var surfaceReady = false
    private var resumed = false
    private var torchOn = false
    private var askedPermission = false
    private var cameraFailed = false
    private var lastBadAt = 0L

    private val defaultHint = "Point the camera at the code on the other device"
    private val resetHint = Runnable { setHint(defaultHint, false) }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        Theme.resolve(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupEdgeToEdge()
        buildUi()
    }

    private var lastRotation = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // A 180-degree flip (landscape <-> reverse landscape) delivers no configuration change.
            @Suppress("DEPRECATION")
            val rot = try { windowManager.defaultDisplay.rotation } catch (_: Throwable) { return }
            if (rot == lastRotation) return
            lastRotation = rot
            applyDisplayOrientation()
            root.post { applyTransform() }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        try {
            @Suppress("DEPRECATION")
            lastRotation = windowManager.defaultDisplay.rotation
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)
        } catch (_: Throwable) {}
        overlay.start()
        updateState()
    }

    override fun onPause() {
        resumed = false
        try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        overlay.stop()
        releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        camExec.shutdown()          // queued releases still run
        decodeExec.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest handles rotation itself: re-apply camera orientation and the crop transform.
        applyDisplayOrientation()
        root.post { applyTransform() }
    }

    private fun setupEdgeToEdge() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        } catch (_: Exception) {
        }
    }

    // ---- UI ----

    private fun dp(v: Int) = Theme.dp(this, v)

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                updateState()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceReady = false
                releaseCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        texture.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) focusNow()
            true
        }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))

        overlay = ScanOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // top bar: close + title
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val close = PillButton(this, "Close", iconName = "ic_close").apply {
            setOnClickListener { finish() }
        }
        topBar.addView(close, LinearLayout.LayoutParams(-2, dp(44)))
        val title = TextView(this).apply {
            text = intent?.getStringExtra(EXTRA_TITLE) ?: "Scan QR code"
            setTextColor(Theme.TEXT)
            typeface = Theme.BOLD
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom bar: hint + buttons
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        hint = TextView(this).apply {
            text = defaultHint
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            typeface = Theme.MEDIUM
        }
        bottomBar.addView(hint, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        torchBtn = PillButton(this, "Torch", iconName = "ic_torch").apply {
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        photoBtn = PillButton(this, "Scan from photo", iconName = "ic_gallery").apply {
            setOnClickListener { pickPhoto() }
        }
        row.addView(torchBtn, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(12) })
        row.addView(photoBtn, LinearLayout.LayoutParams(-2, dp(48)))
        bottomBar.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // message panel (no camera / permission denied / camera error)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(Theme.SURFACE)
                setStroke(dp(1), Theme.STROKE)
                cornerRadius = dp(Theme.R_SHEET.toInt()).toFloat()
            }
        }
        panelTitle = TextView(this).apply {
            setTextColor(Theme.TEXT)
            typeface = Theme.BOLD
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        panelBody = TextView(this).apply {
            setTextColor(Theme.MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        panelBtn = PillButton(this, "Allow camera").apply { primary = true }
        panel.addView(panelTitle, LinearLayout.LayoutParams(-1, -2))
        panel.addView(panelBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(panelBtn, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(18) })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(28); rightMargin = dp(28)
        })

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        root.setOnApplyWindowInsetsListener { _, ins ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = ins.systemWindowInsetLeft; t = ins.systemWindowInsetTop
                    r = ins.systemWindowInsetRight; b = ins.systemWindowInsetBottom
                }
            }
            topBar.setPadding(dp(16) + l, dp(8) + t, dp(16) + r, dp(8))
            bottomBar.setPadding(dp(20) + l, dp(8), dp(20) + r, dp(20) + b)
            ins
        }
        setContentView(root)
        root.requestApplyInsets()
        photoBtn.requestFocus()
    }

    private fun setHint(msg: String, warn: Boolean) {
        hint.text = msg
        hint.setTextColor(if (warn) Theme.WARNING else 0xCCFFFFFF.toInt())
    }

    private fun showPanel(title: String, body: String, btn: String?, action: (() -> Unit)?) {
        panelTitle.text = title
        panelBody.text = body
        if (btn != null && action != null) {
            panelBtn.text = btn
            panelBtn.visibility = View.VISIBLE
            panelBtn.setOnClickListener { action() }
        } else {
            panelBtn.visibility = View.GONE
        }
        panel.visibility = View.VISIBLE
        overlay.visibility = View.INVISIBLE
        texture.visibility = View.INVISIBLE
        root.setBackgroundColor(Theme.BG)
        hint.visibility = View.GONE
        torchBtn.visibility = View.GONE
        (if (btn != null) panelBtn else photoBtn).requestFocus()
    }

    private fun hidePanel() {
        if (panel.visibility == View.GONE) return
        panel.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        texture.visibility = View.VISIBLE
        root.setBackgroundColor(Color.BLACK)
        hint.visibility = View.VISIBLE
    }

    // ---- permission + camera state machine ----

    private fun hasCameraPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Decides what to show and (re)starts the camera when everything is ready. */
    private fun updateState() {
        if (!resumed || done.get()) return
        if (!hasCamera(this)) {
            showPanel("No camera found", "You can still scan a screenshot or photo of the code.", null, null)
            return
        }
        if (!hasCameraPermission()) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            } else {
                showPermissionDenied()
            }
            return
        }
        if (cameraFailed) {
            showPanel("Camera isn't available", "Another app may be using it. Try again, or scan from a photo.",
                "Try again") { cameraFailed = false; updateState() }
            return
        }
        hidePanel()
        if (surfaceReady) startCamera()
    }

    private fun showPermissionDenied() {
        val canAsk = Build.VERSION.SDK_INT >= 23 && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (canAsk) {
            showPanel("Camera access is off", "FastDrop only uses the camera to read the QR code. Nothing is recorded.",
                "Allow camera") { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
        } else {
            showPanel("Camera access is off", "Turn on Camera for FastDrop in Settings, or scan from a photo instead.",
                "Open settings") { openAppSettings() }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) updateState()
        else showPermissionDenied()
    }

    // ---- camera ----

    private fun currentRotationDegrees(): Int {
        val rot = try {
            if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
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
        val deg = currentRotationDegrees()
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + deg) % 360) % 360
        } else {
            (info.orientation - deg + 360) % 360
        }
    }

    private fun startCamera() {
        synchronized(camLock) { if (cam != null) return }
        val st = texture.surfaceTexture ?: return
        val gen = camGen.incrementAndGet()
        val vw = maxOf(texture.width, root.width)
        val vh = maxOf(texture.height, root.height)
        val rotDeg = currentRotationDegrees()
        camExec.execute { openCamera(gen, st, vw, vh, rotDeg) }
    }

    /** Runs on the camera thread. */
    private fun openCamera(gen: Int, st: SurfaceTexture, vw: Int, vh: Int, @Suppress("UNUSED_PARAMETER") rotDeg: Int) {
        if (gen != camGen.get()) return
        var c: Camera? = null
        try {
            var id = 0
            val info = Camera.CameraInfo()
            val count = Camera.getNumberOfCameras()
            if (count <= 0) throw IllegalStateException("no camera")
            for (i in 0 until count) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break }
            }
            Camera.getCameraInfo(id, info)
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
            c.setPreviewCallbackWithBuffer { data, _ -> onFrame(data, cb) }

            if (gen != camGen.get()) {       // paused while opening
                c.release()
                return
            }
            preview = Preview(size.width, size.height, orient, info.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
            c.startPreview()
            val stale = synchronized(camLock) {
                if (gen != camGen.get()) true else { cam = c; false }
            }
            if (stale) {            // released while opening: don't publish, close it now
                try { c.setPreviewCallback(null) } catch (_: Throwable) {}
                try { c.stopPreview() } catch (_: Throwable) {}
                try { c.release() } catch (_: Throwable) {}
                return
            }
            main.post {
                if (gen != camGen.get()) return@post
                torchBtn.visibility = if (hasTorch) View.VISIBLE else View.GONE
                torchBtn.active = torchOn
                applyTransform()
            }
        } catch (_: Throwable) {
            try { c?.release() } catch (_: Throwable) {}
            synchronized(camLock) { cam = null }
            main.post {
                if (gen == camGen.get()) {
                    cameraFailed = true
                    updateState()
                }
            }
        }
    }

    private fun releaseCamera() {
        camGen.incrementAndGet()
        val c = synchronized(camLock) { cam.also { cam = null } }
        preview = null
        busy.set(false)
        if (c != null) {
            camExec.execute {
                try { c.setPreviewCallback(null) } catch (_: Throwable) {}
                try { c.stopPreview() } catch (_: Throwable) {}
                try { c.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun applyDisplayOrientation() {
        val c = synchronized(camLock) { cam } ?: return
        val old = preview ?: return
        try {
            val info = Camera.CameraInfo()
            var id = 0
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break }
            }
            Camera.getCameraInfo(id, info)
            val orient = displayOrientation(info)
            c.setDisplayOrientation(orient)
            preview = Preview(old.w, old.h, orient, old.back)
        } catch (_: Throwable) {
        }
    }

    /** Center-crop the preview into the view (the default stretches it). */
    private fun applyTransform() {
        val pv = preview ?: return
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val sideways = pv.rot % 180 != 0
        val pw = (if (sideways) pv.h else pv.w).toFloat()
        val ph = (if (sideways) pv.w else pv.h).toFloat()
        val s = maxOf(vw / pw, vh / ph)
        val m = Matrix()
        m.setScale(pw * s / vw, ph * s / vh, vw / 2f, vh / 2f)
        texture.setTransform(m)
    }

    private fun focusNow() {
        val c = synchronized(camLock) { cam } ?: return
        try {
            if (c.parameters.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) c.autoFocus(null)
        } catch (_: Throwable) {
        }
    }

    private fun toggleTorch() {
        val c = synchronized(camLock) { cam } ?: return
        try {
            val p = c.parameters
            torchOn = !torchOn
            p.flashMode = if (torchOn) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
            torchBtn.active = torchOn
        } catch (_: Throwable) {
            torchOn = false
            torchBtn.active = false
        }
    }

    // ---- decoding ----

    /** Preview callback. Drops frames while a decode is running. */
    private fun onFrame(data: ByteArray?, c: Camera) {
        fun recycle() { if (data != null) try { c.addCallbackBuffer(data) } catch (_: Throwable) {} }
        val pv = preview
        if (data == null || pv == null || done.get()) { recycle(); return }
        if (!busy.compareAndSet(false, true)) { recycle(); return }
        val win = overlay.win
        val tryFull = (++failFrames % 6 == 0)
        try {
            decodeExec.execute {
                try {
                    var text: String? = null
                    val crop = if (win != null && pv.back) cropFor(win, pv) else null
                    if (crop != null) {
                        text = Qr.decodeYuv(data, pv.w, pv.h, crop[0], crop[1], crop[2], crop[3])
                    }
                    if (text == null && (crop == null || tryFull)) {
                        text = Qr.decodeYuv(data, pv.w, pv.h, tryInverted = tryFull)
                    }
                    if (text != null) main.post { onScanned(text) }
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

    /** Maps the on-screen scan window to a padded rectangle in the sensor frame: [left, top, w, h]. */
    private fun cropFor(win: ScanOverlay.Win, pv: Preview): IntArray? {
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

    /** Main thread: a decoded text. Accept only FastDrop codes; hint otherwise. */
    private fun onScanned(text: String) {
        if (done.get()) return
        if (JoinInfo.parse(text) == null) {
            val now = System.currentTimeMillis()
            if (now - lastBadAt > 2500) {
                lastBadAt = now
                setHint("That's not a FastDrop code", true)
                main.removeCallbacks(resetHint)
                main.postDelayed(resetHint, 2500)
            }
            return
        }
        if (!done.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        val cc = synchronized(camLock) { cam }
        try { cc?.setPreviewCallback(null) } catch (_: Throwable) {}
        overlay.showSuccess()
        setHint("Connected", false)
        root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        )
        main.postDelayed({ if (!isFinishing) finish() }, if (Theme.animationsOn(this)) 420L else 0L)
    }

    // ---- scan from photo ----

    private fun pickPhoto() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, REQ_PHOTO)
        } catch (_: ActivityNotFoundException) {
            setHint("No photo picker on this device", true)
            hint.visibility = View.VISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        hint.visibility = View.VISIBLE
        setHint("Reading photo…", false)
        Thread {
            val text = readPhoto(uri)
            main.post {
                if (isFinishing || isDestroyed) return@post
                when {
                    text == null -> {
                        setHint("No QR code found in that photo", true)
                        main.removeCallbacks(resetHint)
                        main.postDelayed(resetHint, 3000)
                    }
                    JoinInfo.parse(text) == null -> {
                        setHint("That's not a FastDrop code", true)
                        main.removeCallbacks(resetHint)
                        main.postDelayed(resetHint, 3000)
                    }
                    else -> onScanned(text)
                }
            }
        }.start()
    }

    private fun readPhoto(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val big = maxOf(bounds.outWidth, bounds.outHeight)
            while (big / sample > 3000) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            try { Qr.decode(bmp) } finally { bmp.recycle() }
        } catch (_: Throwable) {
            null
        }
    }

    // ---- small pill button with TV focus state ----

    private class PillButton(ctx: Context, label: String, private val iconName: String? = null) : TextView(ctx) {
        private val bg = GradientDrawable()
        var primary = false
            set(v) { field = v; restyle() }
        var active = false
            set(v) { field = v; restyle() }
        private val d = ctx.resources.displayMetrics.density

        init {
            text = label
            setTextColor(Color.WHITE)
            typeface = Theme.MEDIUM
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            compoundDrawablePadding = (8 * d).toInt()
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            isFocusable = true
            isClickable = true
            bg.cornerRadius = 26 * d
            restyle()
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
            val icon = iconDrawable(ctx)
            if (icon != null) setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            setOnFocusChangeListener { _, _ -> restyle(); animateScale(if (isFocused) 1.06f else 1f) }
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> animateScale(0.96f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(if (isFocused) 1.06f else 1f)
                }
                false
            }
        }

        private fun iconDrawable(ctx: Context): Drawable? {
            val name = iconName ?: return null
            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
            if (id == 0) return null
            return try {
                val dr = ctx.getDrawable(id)?.mutate() ?: return null
                dr.setTint(Color.WHITE)
                dr.setBounds(0, 0, (20 * d).toInt(), (20 * d).toInt())
                dr
            } catch (_: Exception) {
                null
            }
        }

        private fun restyle() {
            val fill = when {
                active -> Theme.ACCENT
                primary -> Theme.ACCENT
                else -> 0x33FFFFFF
            }
            bg.setColor(fill)
            if (isFocused) bg.setStroke((2 * d).toInt(), Color.WHITE)
            else bg.setStroke((1 * d).toInt(), Theme.STROKE_STRONG)
        }

        private fun animateScale(s: Float) {
            animate().scaleX(s).scaleY(s).setDuration(Theme.DUR_FAST).start()
        }
    }

    // ---- animated overlay: dim surround, brackets, sweeping line ----

    private class ScanOverlay(ctx: Context) : View(ctx) {
        class Win(val rect: RectF, val vw: Int, val vh: Int)

        @Volatile var win: Win? = null

        private val d = ctx.resources.displayMetrics.density
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1 * d; color = 0x33FFFFFF
        }
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4 * d
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val rect = RectF()
        private val dim = Path()
        private val clip = Path()
        private val bracket = Path()
        private val corner = 22 * d
        private val arm = 30 * d

        private var sweep = 0.5f
        private var pulse = 0f
        private var success = 0f      // 0..1 morph to green
        private var anim: ValueAnimator? = null
        private var pulseAnim: ValueAnimator? = null
        private var successAnim: ValueAnimator? = null
        private var succeeded = false

        fun start() {
            if (!Theme.animationsOn(context) || succeeded) { invalidate(); return }
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 2000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = Theme.EASE_IN_OUT
                    addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            if (pulseAnim == null) {
                pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1600
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = LinearInterpolator()
                    addUpdateListener { pulse = it.animatedValue as Float }
                    start()
                }
            }
        }

        fun stop() {
            anim?.cancel(); anim = null
            pulseAnim?.cancel(); pulseAnim = null
        }

        fun showSuccess() {
            succeeded = true
            stop()
            if (!Theme.animationsOn(context)) { success = 1f; invalidate(); return }
            successAnim?.cancel()
            successAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = Theme.EASE_ENTER
                addUpdateListener { success = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            stop()
            successAnim?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val landscape = w > h
            val side = minOf(w, h) * (if (landscape) 0.55f else 0.68f)
            val cx = w / 2f
            val cy = h * (if (landscape) 0.5f else 0.42f)
            val r = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            win = Win(r, w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val wn = win ?: return
            val s = if (success > 0f) 1f + 0.04f * Math.sin(success * Math.PI).toFloat() else 1f
            rect.set(wn.rect)
            if (s != 1f) {
                val ex = rect.width() * (s - 1f) / 2f
                rect.inset(-ex, -ex)
            }
            val r = 24 * d

            // dimmed surround with a rounded hole
            dim.reset()
            dim.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            dim.addRoundRect(rect, r, r, Path.Direction.CW)
            dim.fillType = Path.FillType.EVEN_ODD
            canvas.drawPath(dim, dimPaint)
            canvas.drawRoundRect(rect, r, r, edgePaint)

            val green = Theme.SUCCESS
            val white = Color.WHITE
            val col = blend(white, green, success)

            if (success > 0f) {
                tintPaint.color = Theme.alpha(green, 0.16f * success)
                canvas.drawRoundRect(rect, r, r, tintPaint)
            }

            // corner brackets, gently breathing while scanning
            bracketPaint.color = col
            bracketPaint.alpha = if (succeeded) 255 else (170 + 85 * pulse).toInt()
            drawBracket(canvas, rect.left, rect.top, 1, 1)
            drawBracket(canvas, rect.right, rect.top, -1, 1)
            drawBracket(canvas, rect.left, rect.bottom, 1, -1)
            drawBracket(canvas, rect.right, rect.bottom, -1, -1)
        }

        /** One L-shaped corner with a rounded elbow. sx/sy point into the window. */
        private fun drawBracket(c: Canvas, x: Float, y: Float, sx: Int, sy: Int) {
            bracket.reset()
            bracket.moveTo(x, y + sy * arm)
            bracket.lineTo(x, y + sy * corner)
            bracket.quadTo(x, y, x + sx * corner, y)
            bracket.lineTo(x + sx * arm, y)
            c.drawPath(bracket, bracketPaint)
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
        }
    }
}
