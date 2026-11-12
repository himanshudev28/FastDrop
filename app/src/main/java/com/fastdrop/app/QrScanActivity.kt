package com.fastdrop.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner on Camera1 + TextureView (no AndroidX). Returns the scanned text in
 * [EXTRA_TEXT]. Only FastDrop codes (fastdrop://join...) are accepted. "From photo" works even
 * without camera or permission, and "Enter code" accepts a pasted or typed code through the same
 * filter. Camera is in [ScanCamera], decoding in [ScanDecoder], the overlay in [ScanOverlay].
 * Layout follows docs/DESIGN.md 4.9: black screen, white text, outlined white buttons.
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

    private val main = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private lateinit var camera: ScanCamera
    private lateinit var decoder: ScanDecoder

    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var overlay: ScanOverlay
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var hintRow: LinearLayout
    private lateinit var hintPlate: FrameLayout
    private lateinit var hint: TextView
    private lateinit var torchBtn: ScanButton
    private lateinit var photoBtn: ScanButton
    private lateinit var codeBtn: ScanButton
    private lateinit var buttonRow: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelBtn: ScanButton
    private lateinit var entry: LinearLayout
    private lateinit var entryField: EditText

    private var surfaceReady = false
    private var resumed = false
    private var askedPermission = false
    private var cameraFailed = false
    private var lastBadAt = 0L
    private var lastRotation = -1

    private val defaultHint get() = getString(R.string.scan_hint)
    private val resetHint = Runnable { setHint(defaultHint, Hint.NORMAL) }

    private enum class Hint { NORMAL, ERROR, OK }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        Theme.resolve(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camera = ScanCamera(this, main)
        decoder = ScanDecoder(main) { onScanned(it) }
        camera.onFrame = { data, c -> decoder.submit(data, c, camera.preview, overlay.win, done) }
        setupEdgeToEdge()
        buildUi()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // A 180-degree flip (landscape <-> reverse landscape) delivers no configuration change.
            @Suppress("DEPRECATION")
            val rot = try { windowManager.defaultDisplay.rotation } catch (_: Throwable) { return }
            if (rot == lastRotation) return
            lastRotation = rot
            camera.applyDisplayOrientation()
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
        updateState()
    }

    override fun onPause() {
        resumed = false
        try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        camera.shutdown()
        decoder.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest handles rotation itself: re-apply camera orientation and the crop transform.
        camera.applyDisplayOrientation()
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
    private val tvMode get() = Ui.tv(this)
    private val sideGutter get() = dp(if (tvMode) Theme.TV_GUTTER else Theme.GUTTER)

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
            if (e.action == MotionEvent.ACTION_UP) camera.focusNow()
            true
        }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))

        overlay = ScanOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // top bar: close on the left, title on the right
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = ScanButton(this, null, R.drawable.ic_close, getString(R.string.cd_scan_close)).apply {
            setOnClickListener { finish() }
        }
        topBar.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(Ui.text(this, intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.scan_title),
            Type.TITLE, Color.WHITE).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom bar: hint + buttons
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        hintRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        hintPlate = FrameLayout(this).apply { visibility = View.GONE }
        hintRow.addView(hintPlate, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        hint = Ui.text(this, defaultHint, Type.BODY, Color.WHITE).apply { gravity = Gravity.CENTER }
        hintRow.addView(hint, LinearLayout.LayoutParams(-2, -2))
        bottomBar.addView(hintRow, LinearLayout.LayoutParams(-1, -2))

        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        torchBtn = ScanButton(this, getString(R.string.scan_torch), R.drawable.ic_torch).apply {
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        photoBtn = ScanButton(this, getString(R.string.scan_photo), R.drawable.ic_gallery).apply {
            setOnClickListener { pickPhoto() }
        }
        codeBtn = ScanButton(this, getString(R.string.scan_enter_code), R.drawable.ic_edit).apply {
            setOnClickListener { toggleEntry() }
        }
        val gap = dp(8)
        buttonRow.addView(torchBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
        buttonRow.addView(photoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
        buttonRow.addView(codeBtn, LinearLayout.LayoutParams(-2, -2))
        bottomBar.addView(buttonRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // message panel (no camera / permission denied / camera error): centred, on black
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        panelTitle = Ui.text(this, "", Type.TITLE, Color.WHITE).apply { gravity = Gravity.CENTER }
        panelBody = Ui.text(this, "", Type.BODY, 0xFFC9C6BE.toInt()).apply { gravity = Gravity.CENTER }
        panelBtn = ScanButton(this, getString(R.string.scan_allow_camera), null)
        panel.addView(panelTitle, LinearLayout.LayoutParams(-1, -2))
        panel.addView(panelBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(panelBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(32); rightMargin = dp(32)
        })

        // manual entry: a themed sheet near the top so the keyboard never covers it
        entry = Ui.panel(this, 0f).apply {
            visibility = View.GONE
            setPadding(sideGutter, dp(16), sideGutter, dp(16))
        }
        entry.addView(Ui.text(this, getString(R.string.scan_entry_title), Type.ROW))
        entryField = EditText(this).apply {
            hint = getString(R.string.scan_entry_hint)
            setHintTextColor(Theme.INK_3)
            setTextColor(Theme.INK)
            typeface = Theme.MONO
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Theme.SURFACE_2)
                cornerRadius = dp(Theme.R_CTRL.toInt()).toFloat()
            }
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { submitEntry(); true } else false }
        }
        entry.addView(entryField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val entryButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        entryButtons.addView(Ui.secondaryButton(this, getString(R.string.scan_paste)) { pasteCode() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        entryButtons.addView(Ui.jobButton(this, getString(R.string.scan_use_code), Job.RECEIVE) { submitEntry() },
            LinearLayout.LayoutParams(0, -2, 1f))
        entry.addView(entryButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(entry, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        root.setOnApplyWindowInsetsListener { _, ins ->
            val i = Ui.insetsOf(ins)
            val g = sideGutter
            topBar.setPadding(dp(4) + i[0], dp(8) + i[1], g + i[2], dp(8))
            bottomBar.setPadding(g + i[0], dp(8), g + i[2], dp(20) + i[3] + if (tvMode) dp(Theme.TV_SAFE_V) else 0)
            (entry.layoutParams as FrameLayout.LayoutParams).topMargin = i[1] + dp(64)
            entry.requestLayout()
            ins
        }
        setContentView(root)
        root.requestApplyInsets()
        photoBtn.requestFocus()
    }

    private fun setHint(msg: String, kind: Hint) {
        hint.text = msg
        hintPlate.removeAllViews()
        when (kind) {
            Hint.NORMAL -> hintPlate.visibility = View.GONE
            Hint.ERROR -> {
                hintPlate.addView(Ui.plate(this, R.drawable.ic_alert, Job.DANGER.fill, Job.DANGER.on, 24),
                    FrameLayout.LayoutParams(dp(24), dp(24)))
                hintPlate.visibility = View.VISIBLE
            }
            Hint.OK -> {
                hintPlate.addView(Ui.plate(this, R.drawable.ic_check, Job.OK.fill, Job.OK.on, 24),
                    FrameLayout.LayoutParams(dp(24), dp(24)))
                hintPlate.visibility = View.VISIBLE
            }
        }
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
        hintRow.visibility = View.GONE
        torchBtn.visibility = View.GONE
        (if (btn != null) panelBtn else photoBtn).requestFocus()
    }

    private fun hidePanel() {
        if (panel.visibility == View.GONE) return
        panel.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        texture.visibility = View.VISIBLE
        hintRow.visibility = View.VISIBLE
    }

    private fun toggleEntry() {
        val show = entry.visibility != View.VISIBLE
        entry.visibility = if (show) View.VISIBLE else View.GONE
        if (show) entryField.requestFocus() else hideKeyboard()
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(entryField.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun pasteCode() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!t.isNullOrBlank()) { entryField.setText(t.trim()); entryField.setSelection(entryField.text.length) }
        } catch (_: Exception) {}
    }

    private fun submitEntry() {
        val t = entryField.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        if (JoinInfo.parse(t) == null) {
            setHint(getString(R.string.scan_not_fastdrop), Hint.ERROR)
            main.removeCallbacks(resetHint)
            main.postDelayed(resetHint, 2500)
            return
        }
        entry.visibility = View.GONE
        onScanned(t)
    }

    // ---- permission + camera state machine ----

    private fun hasCameraPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Decides what to show and (re)starts the camera when everything is ready. */
    private fun updateState() {
        if (!resumed || done.get()) return
        if (!hasCamera(this)) {
            showPanel(getString(R.string.scan_no_camera), getString(R.string.scan_no_camera_meta), null, null)
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
            showPanel(getString(R.string.scan_camera_busy), getString(R.string.scan_camera_busy_meta),
                getString(R.string.action_retry)) { cameraFailed = false; updateState() }
            return
        }
        hidePanel()
        if (surfaceReady) startCamera()
    }

    private fun showPermissionDenied() {
        val canAsk = Build.VERSION.SDK_INT >= 23 && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (canAsk) {
            showPanel(getString(R.string.scan_allow_title), getString(R.string.scan_allow_meta),
                getString(R.string.scan_allow_camera)) { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
        } else {
            showPanel(getString(R.string.scan_allow_title), getString(R.string.scan_settings_meta),
                getString(R.string.scan_open_settings)) { openAppSettings() }
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

    // ---- camera glue ----

    private fun startCamera() {
        val st = texture.surfaceTexture ?: return
        val vw = maxOf(texture.width, root.width)
        val vh = maxOf(texture.height, root.height)
        camera.start(st, vw, vh, { hasTorch ->
            torchBtn.visibility = if (hasTorch) View.VISIBLE else View.GONE
            torchBtn.isActivated = camera.torchOn
            applyTransform()
        }, {
            cameraFailed = true
            updateState()
        })
    }

    private fun releaseCamera() {
        camera.release()
        decoder.reset()
    }

    private fun applyTransform() {
        camera.transform(texture.width.toFloat(), texture.height.toFloat())?.let { texture.setTransform(it) }
    }

    private fun toggleTorch() {
        torchBtn.isActivated = camera.toggleTorch()
    }

    // ---- results ----

    /** Main thread: a decoded text. Accept only FastDrop codes; hint otherwise. */
    private fun onScanned(text: String) {
        if (done.get()) return
        val info = JoinInfo.parse(text)
        if (info == null) {
            val now = System.currentTimeMillis()
            if (now - lastBadAt > 2500) {
                lastBadAt = now
                setHint(getString(R.string.scan_not_fastdrop), Hint.ERROR)
                main.removeCallbacks(resetHint)
                main.postDelayed(resetHint, 2500)
            }
            return
        }
        if (!done.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        camera.stopFrames()
        overlay.showSuccess()
        setHint(getString(R.string.scan_found, info.name), Hint.OK)
        root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        )
        main.postDelayed({ if (!isFinishing) finish() }, if (Theme.animationsOn(this)) 400L else 0L)
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
            setHint(getString(R.string.scan_no_picker), Hint.ERROR)
            hintRow.visibility = View.VISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        hintRow.visibility = View.VISIBLE
        setHint(getString(R.string.scan_reading_photo), Hint.NORMAL)
        Thread {
            val text = ScanDecoder.readPhoto(contentResolver, uri)
            main.post {
                if (isFinishing || isDestroyed) return@post
                when {
                    text == null -> {
                        setHint(getString(R.string.scan_photo_none), Hint.ERROR)
                        main.removeCallbacks(resetHint)
                        main.postDelayed(resetHint, 3000)
                    }
                    JoinInfo.parse(text) == null -> {
                        setHint(getString(R.string.scan_not_fastdrop), Hint.ERROR)
                        main.removeCallbacks(resetHint)
                        main.postDelayed(resetHint, 3000)
                    }
                    else -> onScanned(text)
                }
            }
        }.start()
    }

    // ---- outlined white button on black (the scanner is always black, in both themes) ----

    private class ScanButton(ctx: Context, label: String?, icon: Int?, desc: String? = null) : TextView(ctx) {
        init {
            val d = ctx.resources.displayMetrics.density
            val tv = Ui.tv(ctx)
            val white = 0xFFFFFFFF.toInt()
            val ink = Job.WEB.on
            text = label ?: ""
            textSize = if (tv) Type.BUTTON.tvSp else Type.BUTTON.sp
            typeface = Theme.MEDIUM
            includeFontPadding = false
            isAllCaps = false
            gravity = Gravity.CENTER
            contentDescription = desc
            val colors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(android.R.attr.state_activated), intArrayOf()),
                intArrayOf(ink, ink, ink, white))
            setTextColor(colors)
            val hasText = !label.isNullOrEmpty()
            val ph = if (hasText) 16 else 12
            setPadding((ph * d).toInt(), (10 * d).toInt(), (ph * d).toInt(), (10 * d).toInt())
            minHeight = (if (tv) 56 else 48).let { (it * d).toInt() }
            minWidth = (48 * d).toInt()
            if (icon != null) {
                val dr = ctx.getDrawable(icon)?.mutate()
                if (dr != null) {
                    dr.setTintList(colors)
                    val s = (20 * d).toInt()
                    dr.setBounds(0, 0, s, s)
                    setCompoundDrawablesRelative(dr, null, null, null)
                    compoundDrawablePadding = if (hasText) (8 * d).toInt() else 0
                }
            }
            val r = Theme.R_CTRL * d
            fun rect(fill: Int, stroke: Int = 0) = GradientDrawable().apply {
                setColor(fill); cornerRadius = r
                if (stroke != 0) setStroke(Theme.rule(ctx), stroke)
            }
            val torchOn = rect(Job.WEB.fill)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rect(white))
                addState(intArrayOf(android.R.attr.state_focused), rect(white))
                addState(intArrayOf(android.R.attr.state_activated), torchOn)
                addState(intArrayOf(), rect(0, white))
            }
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, has ->
                if (!tv) return@setOnFocusChangeListener
                val s = if (has) 1.06f else 1f
                if (Anim.on(ctx)) v.animate().scaleX(s).scaleY(s).setDuration(100).setInterpolator(Theme.EASE_ENTER).start()
                else { v.scaleX = s; v.scaleY = s }
            }
        }
    }
}
