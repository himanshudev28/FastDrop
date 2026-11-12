package com.fastdrop.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/** Small shared helpers for the two picker screens (PickerActivity and BrowserActivity). */
internal object PickUi {

    /** Transparent system bars, content drawn under them; screens pad with Ui.applyInsets. */
    @Suppress("DEPRECATION")
    fun edgeToEdge(a: Activity) {
        val w = a.window
        w.setBackgroundDrawable(ColorDrawable(Theme.BG))
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Back that works with the predictive-back callback (33+) and the old callback below. */
    fun registerBack(a: Activity, onBack: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, OnBackInvokedCallback { onBack() })
        }
    }

    /** Focus / selector highlight for list and grid rows (white ring). */
    fun selector(ctx: Context, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(0x14FFFFFF)
        cornerRadius = Theme.dp(ctx, radiusDp).toFloat()
        setStroke(Theme.dp(ctx, 2), 0xE6FFFFFF.toInt())
    }

    fun rounded(ctx: Context, color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = Theme.dp(ctx, radiusDp).toFloat()
            if (stroke != 0) setStroke(Theme.dp(ctx, strokeDp), stroke)
        }

    fun roundOutline(radiusPx: Float): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    fun fmtDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Round selection mark. Pops in with a spring when switched on. */
    class Check(ctx: Context, sizeDp: Int, private val onPhoto: Boolean) : FrameLayout(ctx) {
        private val mark = ImageView(ctx)
        private var on: Boolean? = null

        init {
            val s = Theme.dp(ctx, sizeDp)
            mark.setImageResource(R.drawable.ic_check)
            mark.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            mark.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = s / 5
            mark.setPadding(p, p, p, p)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(mark, LayoutParams(s, s, Gravity.CENTER))
            set(false, false)
        }

        fun set(v: Boolean, animate: Boolean) {
            if (on == v) return
            on = v
            val ctx = context
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (v) {
                    setColor(Theme.ACCENT)
                    setStroke(Theme.dp(ctx, 2), Color.WHITE)
                } else {
                    setColor(if (onPhoto) 0x59000000 else 0x00000000)
                    setStroke(Theme.dp(ctx, 2), if (onPhoto) 0xCCFFFFFF.toInt() else Theme.STROKE_STRONG)
                }
            }
            mark.animate().cancel()
            if (!v) {
                mark.alpha = 0f; mark.scaleX = 0.3f; mark.scaleY = 0.3f
            } else if (animate && Anim.on(ctx)) {
                mark.alpha = 0f; mark.scaleX = 0.3f; mark.scaleY = 0.3f
                mark.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(Theme.DUR).setInterpolator(Theme.SPRING).start()
            } else {
                mark.alpha = 1f; mark.scaleX = 1f; mark.scaleY = 1f
            }
        }
    }

    /** Floating bottom bar: "12 selected / 340 MB" plus a gradient action button. Slides in and out. */
    class FloatingBar(ctx: Context, private val label: String, onNext: () -> Unit) : FrameLayout(ctx) {
        private val countText = Ui.text(ctx, "", 16f, Theme.TEXT, Theme.BOLD)
        private val subText = Ui.text(ctx, "", 12.5f, Theme.MUTED, Theme.MEDIUM)
        val button: TextView = Ui.primaryButton(ctx, label, Theme.BRAND, null, onNext)
        private var shown = false

        init {
            visibility = GONE
            clipChildren = false
            clipToPadding = false
            val g = Theme.dp(ctx, if (Ui.tv(ctx)) Theme.TV_GUTTER else Theme.GUTTER)
            setPadding(g, Theme.dp(ctx, 8), g, Theme.dp(ctx, 12))
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Theme.dp(ctx, 20), Theme.dp(ctx, 10), Theme.dp(ctx, 10), Theme.dp(ctx, 10))
                background = rounded(ctx, Theme.alpha(Theme.SURFACE_2, 0.97f), 30, Theme.STROKE_STRONG)
                elevation = Theme.dp(ctx, 12).toFloat()
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(countText)
            col.addView(subText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Theme.dp(ctx, 3) })
            bar.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            button.minHeight = Theme.dp(ctx, 48)
            bar.addView(button, LinearLayout.LayoutParams(-2, -2))
            addView(bar, LayoutParams(-1, -2))
            Ui.applyInsets(this, top = false, bottom = true)
        }

        fun update(count: Int, sub: String, noun: String = "selected", anim: Boolean = true) {
            if (count > 0) {
                countText.text = "$count $noun"
                subText.text = sub
                subText.visibility = if (sub.isEmpty()) GONE else VISIBLE
                show(anim)
            } else hide(anim)
        }

        fun setBusy(busy: Boolean) {
            button.isEnabled = !busy
            button.text = if (busy) "Preparing…" else label
            button.alpha = if (busy) 0.6f else 1f
        }

        private fun show(anim: Boolean) {
            if (shown) return
            shown = true
            animate().cancel()
            visibility = VISIBLE
            if (anim && Anim.on(context)) {
                translationY = Theme.dp(context, 120).toFloat()
                alpha = 0f
                animate().translationY(0f).alpha(1f).setStartDelay(0)
                    .setDuration(Theme.DUR_SLOW).setInterpolator(Theme.EASE_OUT).start()
            } else {
                translationY = 0f; alpha = 1f
            }
        }

        private fun hide(anim: Boolean) {
            if (!shown) return
            shown = false
            animate().cancel()
            if (anim && Anim.on(context)) {
                animate().translationY(Theme.dp(context, 120).toFloat()).alpha(0f).setStartDelay(0)
                    .setDuration(Theme.DUR).setInterpolator(Theme.EASE_IN)
                    .withEndAction { if (!shown) visibility = GONE }.start()
            } else visibility = GONE
        }
    }
}

/**
 * Chooses what to send: Apps, Photos, Videos, Music and Files tabs. The selection survives tab
 * changes. "Next" stores it in Hub.selection and finishes with RESULT_OK.
 */
class PickerActivity : Activity() {

    companion object {
        const val TAB_APPS = "apps"
        const val TAB_PHOTOS = "photos"
        const val TAB_VIDEOS = "videos"
        const val TAB_MUSIC = "music"
        const val TAB_FILES = "files"
        const val EXTRA_TAB = "tab"

        private const val REQ_FILES = 1
        private const val REQ_FOLDER = 2
        private const val REQ_BROWSER = 3
        private const val REQ_BROWSER_PERMS = 10
        private const val REQ_MEDIA = 20       // + MediaKind ordinal
        private const val MAX_PERSISTED = 100

        fun intent(ctx: Context, tab: String): Intent =
            Intent(ctx, PickerActivity::class.java).putExtra(EXTRA_TAB, tab)
    }

    // ---------- model ----------

    private class Pick(
        val key: String, val kind: Int, val title: String, val count: Int, val size: Long,
        val thumbName: String, val thumbUri: Uri?, val build: () -> List<SendItem>,
    )

    private val KIND_APP = 0
    private val KIND_MEDIA = 1
    private val KIND_FILE = 2

    private class TabDef(val id: String, val label: String)

    private val defs = listOf(
        TabDef(TAB_APPS, "Apps"), TabDef(TAB_PHOTOS, "Photos"), TabDef(TAB_VIDEOS, "Videos"),
        TabDef(TAB_MUSIC, "Music"), TabDef(TAB_FILES, "Files"),
    )

    private val picks = LinkedHashMap<String, Pick>()
    private val mediaItems = HashMap<MediaKind, List<MediaItem>>()
    private val loadGen = HashMap<MediaKind, Int>()
    private val lastAccess = HashMap<MediaKind, Media.Access>()
    private val denied = HashSet<MediaKind>()
    private var apps: List<AppEntry> = emptyList()
    private var appsLoaded = false
    private var appsGen = 0

    // ---------- views ----------

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabBar: TabBar
    private lateinit var selectAllBtn: TextView
    private lateinit var bar: PickUi.FloatingBar
    private lateinit var bannerHolder: FrameLayout
    private val paneViews = arrayOfNulls<View>(5)
    private val panes = HashMap<String, Pane>()
    private var cur = -1
    private var bottomInset = 0
    private var filesList: LinearLayout? = null
    private var filesStatus: TextView? = null
    private var filesBusy = 0
    private var pendingAllFiles = false
    private var finishing = false

    private val gut get() = Theme.dp(this, if (Ui.tv(this)) Theme.TV_GUTTER else Theme.GUTTER)
    private fun dp(v: Int) = Theme.dp(this, v)

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PickUi.edgeToEdge(this)
        PickUi.registerBack(this) { cancel() }
        loadExisting()
        setContentView(buildUi())
        val start = defs.indexOfFirst { it.id == intent?.getStringExtra(EXTRA_TAB) }.let { if (it < 0) 1 else it }
        selectTab(start, false)
        updateBar(false)
        if (Anim.on(this)) Anim.stagger(listOf(headerView, tabBar), 0, 60)
        tabBar.post { tabBar.focusTab(cur) }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed permissions in Settings.
        for ((kind, acc) in lastAccess.entries.toList()) {
            if (Media.access(this, kind) != acc) paneFor(kind)?.let { loadMedia(kind, it) }
        }
        if (pendingAllFiles) {
            pendingAllFiles = false
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) openBrowser()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = cancel()

    // TV remote shortcut: jump straight to the action bar from a long list.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.repeatCount == 0 && (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == android.view.KeyEvent.KEYCODE_BUTTON_START)) {
            next()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    // ---------- starting selection ----------

    /** Keeps what was already selected, so "Edit" on the send screen adds to it. */
    private fun loadExisting() {
        val old = Hub.selection
        if (old.isEmpty()) return
        val appGroups = LinkedHashMap<String, MutableList<SendItem>>()
        for (si in old) {
            val tag = si.app
            if (tag != null) { appGroups.getOrPut(tag.pkg) { ArrayList() }.add(si); continue }
            val uri = si.uri
            val isMedia = uri != null && uri.authority == "media"
            val key = when {
                uri != null && isMedia -> "m:$uri"
                uri != null -> "u:$uri"
                si.file != null -> "p:${si.file.absolutePath}"
                else -> continue
            }
            if (picks.containsKey(key)) continue
            picks[key] = Pick(key, if (isMedia) KIND_MEDIA else KIND_FILE, si.name.substringAfterLast('/'), 1, si.size,
                si.name, uri, { listOf(si) })
        }
        for ((pkg, list) in appGroups) {
            val tag = list[0].app!!
            picks["a:$pkg"] = Pick("a:$pkg", KIND_APP, tag.label, 1, list.sumOf { it.size }, "x.apk", null, { list })
        }
    }

    // ---------- UI ----------

    private lateinit var headerView: View

    private fun buildUi(): View {
        root = FrameLayout(this).apply {
            setBackgroundColor(Theme.BG)
            setOnApplyWindowInsetsListener { _, ins ->
                val b = Ui.insetsOf(ins)[3]
                if (b != bottomInset) {
                    bottomInset = b
                    for (p in panes.values) p.applyPadding()
                }
                ins
            }
        }
        root.addView(Backdrop(this).apply { colors = Theme.BRAND + Theme.RECV_A }, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(column, top = true, bottom = false)

        selectAllBtn = Ui.secondaryButton(this, "Select all") { selectAll() }.apply {
            minHeight = dp(40)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            textSize = 14f
        }
        headerView = Ui.header(this, "Choose", { cancel() }, selectAllBtn)
        column.addView(headerView, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = gut; rightMargin = gut })

        tabBar = TabBar()
        column.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = gut; rightMargin = gut; topMargin = dp(4); bottomMargin = dp(10)
        })

        bannerHolder = FrameLayout(this).apply { visibility = View.GONE }
        column.addView(bannerHolder, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = gut; rightMargin = gut; bottomMargin = dp(10)
        })

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        bar = PickUi.FloatingBar(this, "Next") { next() }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return root
    }

    // ---------- tabs ----------

    private inner class TabBar : FrameLayout(this@PickerActivity) {
        private val labels = ArrayList<TextView>()
        private val indicator = View(this@PickerActivity)
        private val pad = dp(4)
        private var index = 0

        init {
            background = PickUi.rounded(context, Theme.alpha(Theme.SURFACE_2, 0.9f), 22, Theme.STROKE)
            setPadding(pad, pad, pad, pad)
            indicator.background = Ui.gradient(Theme.BRAND, dp(18).toFloat())
            addView(indicator, LayoutParams(0, -1))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val h = dp(if (Ui.tv(context)) 48 else 40)
            defs.forEachIndexed { i, d ->
                val t = Ui.text(context, d.label, 13.5f, Theme.MUTED, Theme.MEDIUM).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    contentDescription = d.label
                }
                Ui.focusable(t, 1.04f, 18f)
                Ui.pressable(t)
                t.setOnClickListener { Ui.haptic(it); selectTab(i, true) }
                labels.add(t)
                row.addView(t, LinearLayout.LayoutParams(0, h, 1f))
            }
            addView(row, LayoutParams(-1, -2))
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            place(false)
        }

        private fun place(animate: Boolean) {
            val inner = width - 2 * pad
            if (inner <= 0) return
            val tw = inner / defs.size
            val lp = indicator.layoutParams
            if (lp.width != tw) { lp.width = tw; indicator.layoutParams = lp }
            val x = (tw * index).toFloat()
            indicator.animate().cancel()
            if (animate && Anim.on(context)) {
                indicator.animate().translationX(x).setDuration(Theme.DUR)
                    .setInterpolator(Theme.EASE_OUT).start()
            } else indicator.translationX = x
        }

        fun select(i: Int, animate: Boolean) {
            index = i
            labels.forEachIndexed { n, t ->
                t.setTextColor(if (n == i) Color.WHITE else Theme.MUTED)
                t.typeface = if (n == i) Theme.BOLD else Theme.MEDIUM
            }
            place(animate)
        }

        fun focusTab(i: Int) { labels.getOrNull(i)?.requestFocus() }
    }

    private fun selectTab(idx: Int, animate: Boolean) {
        if (idx == cur) return
        val old = if (cur >= 0) paneViews[cur] else null
        val v = ensurePane(idx)
        cur = idx
        tabBar.select(idx, animate)
        if (animate) Anim.crossfade(old, v) else {
            old?.visibility = View.GONE
            v.visibility = View.VISIBLE
            v.alpha = 1f
        }
        refreshBanner()
        refreshSelectAll()
    }

    private fun ensurePane(idx: Int): View {
        paneViews[idx]?.let { return it }
        val v: View = when (defs[idx].id) {
            TAB_APPS -> buildAppsPane()
            TAB_PHOTOS -> buildMediaPane(MediaKind.IMAGE)
            TAB_VIDEOS -> buildMediaPane(MediaKind.VIDEO)
            TAB_MUSIC -> buildMediaPane(MediaKind.AUDIO)
            else -> buildFilesPane()
        }
        v.visibility = View.GONE
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        paneViews[idx] = v
        return v
    }

    private fun paneFor(kind: MediaKind): Pane? = panes[kindTab(kind)]

    private fun kindTab(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE -> TAB_PHOTOS
        MediaKind.VIDEO -> TAB_VIDEOS
        MediaKind.AUDIO -> TAB_MUSIC
    }

    private fun tabKind(tab: String): MediaKind? = when (tab) {
        TAB_PHOTOS -> MediaKind.IMAGE
        TAB_VIDEOS -> MediaKind.VIDEO
        TAB_MUSIC -> MediaKind.AUDIO
        else -> null
    }

    // ---------- pane with loading / empty / permission states ----------

    private inner class Pane(val list: AbsListView, val adapter: BaseAdapter, top: View?) {
        val view: View
        private val stateBox = LinearLayout(this@PickerActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(80))
            visibility = View.GONE
        }
        private var pulse: android.animation.Animator? = null

        init {
            val frame = FrameLayout(this@PickerActivity)
            frame.addView(list, FrameLayout.LayoutParams(-1, -1))
            frame.addView(stateBox, FrameLayout.LayoutParams(-1, -1))
            if (top == null) view = frame
            else view = LinearLayout(this@PickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(top, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = gut; rightMargin = gut; bottomMargin = dp(6)
                })
                addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            applyPadding()
        }

        fun applyPadding() {
            list.setPadding(gut, dp(4), gut, dp(110) + bottomInset)
        }

        fun showContent() {
            pulse?.cancel(); pulse = null
            if (stateBox.visibility == View.VISIBLE) Anim.crossfade(stateBox, list)
        }

        fun showMessage(
            icon: Int, tint: Int, title: String, msg: String,
            action: String? = null, onAction: (() -> Unit)? = null, loading: Boolean = false,
        ) {
            pulse?.cancel(); pulse = null
            stateBox.removeAllViews()
            val badge = FrameLayout(this@PickerActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Theme.alpha(tint, 0.15f))
                }
            }
            badge.addView(Ui.icon(this@PickerActivity, icon, tint, 32),
                FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            stateBox.addView(badge, LinearLayout.LayoutParams(dp(76), dp(76)))
            stateBox.addView(Ui.title(this@PickerActivity, title, 20f).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
            stateBox.addView(Ui.text(this@PickerActivity, msg, 14.5f, Theme.MUTED).apply {
                gravity = Gravity.CENTER
                maxWidth = dp(320)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            if (action != null && onAction != null) {
                stateBox.addView(Ui.primaryButton(this@PickerActivity, action, Theme.BRAND, null, onAction),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
            }
            if (loading) pulse = Anim.pulse(badge)
            if (stateBox.visibility != View.VISIBLE) Anim.crossfade(list, stateBox)
            else if (Anim.on(this@PickerActivity)) Anim.enter(stateBox)
        }

        fun showLoading(msg: String) =
            showMessage(R.drawable.ic_refresh, Theme.ACCENT, "Just a moment", msg, loading = true)
    }

    // ---------- grid rows and cells ----------

    private class SquareFrame(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }

    private class Cell(
        val root: SquareFrame, val box: FrameLayout, val iv: ImageView,
        val dur: TextView, val check: PickUi.Check, val ring: View,
    )

    private fun newCell(): Cell {
        val ctx = this
        val root = SquareFrame(ctx)
        val box = FrameLayout(ctx).apply {
            background = ColorDrawable(Theme.SURFACE_2)
            outlineProvider = PickUi.roundOutline(dp(12).toFloat())
            clipToOutline = true
        }
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val dur = Ui.text(ctx, "", 11.5f, Color.WHITE, Theme.BOLD).apply {
            background = PickUi.rounded(ctx, 0x99000000.toInt(), 8)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            visibility = View.GONE
        }
        box.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(6); bottomMargin = dp(6)
        })
        val ring = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(3), Theme.ACCENT)
            }
            alpha = 0f
        }
        box.addView(ring, FrameLayout.LayoutParams(-1, -1))
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
        val check = PickUi.Check(ctx, 24, true)
        root.addView(check, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(6); rightMargin = dp(6)
        })
        return Cell(root, box, iv, dur, check, ring).also { root.tag = it }
    }

    private fun bindCell(c: Cell, sel: Boolean, animate: Boolean) {
        val s = if (sel) 0.88f else 1f
        val a = if (sel) 1f else 0f
        c.box.animate().cancel(); c.ring.animate().cancel()
        if (animate && Anim.on(this)) {
            c.box.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(Theme.DUR).setInterpolator(Theme.SPRING).start()
            c.ring.animate().alpha(a).setStartDelay(0).setDuration(Theme.DUR_FAST).start()
        } else {
            c.box.scaleX = s; c.box.scaleY = s; c.ring.alpha = a
        }
        c.check.set(sel, animate)
    }

    private inner class GridAdapter(val kind: MediaKind, val px: Int) : BaseAdapter() {
        override fun getCount() = mediaItems[kind]?.size ?: 0
        override fun getItem(position: Int): Any? = mediaItems[kind]?.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = (convertView?.tag as? Cell) ?: newCell()
            val m = mediaItems[kind]!![position]
            Thumbs.media(c.iv, m, px)
            if (kind == MediaKind.VIDEO && m.durationMs > 0) {
                c.dur.text = PickUi.fmtDuration(m.durationMs)
                c.dur.visibility = View.VISIBLE
            } else c.dur.visibility = View.GONE
            c.root.contentDescription = m.name
            bindCell(c, picks.containsKey("m:${m.uri}"), false)
            return c.root
        }
    }

    // ---------- list rows ----------

    private class RowHolder(
        val root: LinearLayout, val iv: ImageView, val title: TextView, val sub: TextView,
        val chip: TextView, val check: PickUi.Check,
    )

    private fun newRow(): RowHolder {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (Ui.tv(ctx)) 72 else 64)
            setPadding(dp(10), dp(8), dp(14), dp(8))
        }
        val s = dp(46)
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ColorDrawable(Theme.SURFACE_3)
            outlineProvider = PickUi.roundOutline(dp(11).toFloat())
            clipToOutline = true
        }
        root.addView(iv, LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(14) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = Ui.text(ctx, "", 15.5f, Theme.TEXT, Theme.MEDIUM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val sub = Ui.text(ctx, "", 12.5f, Theme.MUTED).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title)
        col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val chip = Ui.chip(ctx, "Split", Theme.CAT_APPS).apply { visibility = View.GONE }
        root.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        val check = PickUi.Check(ctx, 26, false)
        root.addView(check, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        return RowHolder(root, iv, title, sub, chip, check).also { root.tag = it }
    }

    private fun setRowSel(h: RowHolder, sel: Boolean) {
        h.root.background = if (sel) PickUi.rounded(this, Theme.alpha(Theme.ACCENT, 0.14f), 16, Theme.alpha(Theme.ACCENT, 0.55f))
        else PickUi.rounded(this, Theme.alpha(Theme.SURFACE, 0.75f), 16, Theme.STROKE)
    }

    private fun bindRowSel(h: RowHolder, sel: Boolean, animate: Boolean) {
        setRowSel(h, sel)
        h.check.set(sel, animate)
    }

    private inner class RowsAdapter(val size: () -> Int, val bind: (RowHolder, Int) -> Unit) : BaseAdapter() {
        override fun getCount() = size()
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? RowHolder) ?: newRow()
            bind(h, position)
            return h.root
        }
    }

    private fun styleList(l: ListView) {
        l.divider = ColorDrawable(Color.TRANSPARENT)
        l.dividerHeight = dp(8)
        l.selector = PickUi.selector(this, 16)
        l.setDrawSelectorOnTop(true)
        l.cacheColorHint = Color.TRANSPARENT
        l.clipToPadding = false
        l.overScrollMode = View.OVER_SCROLL_NEVER
        l.isVerticalScrollBarEnabled = false
    }

    // ---------- media tabs (photos / videos / music) ----------

    private fun buildMediaPane(kind: MediaKind): View {
        val tab = kindTab(kind)
        val pane: Pane
        if (kind == MediaKind.AUDIO) {
            val list = ListView(this)
            styleList(list)
            val px = dp(96)
            val ad = RowsAdapter({ mediaItems[kind]?.size ?: 0 }) { h, i ->
                val m = mediaItems[kind]!![i]
                Thumbs.media(h.iv, m, px)
                h.title.text = m.name.substringBeforeLast('.')
                val parts = ArrayList<String>()
                if (m.durationMs > 0) parts += PickUi.fmtDuration(m.durationMs)
                if (!m.bucket.isNullOrBlank() && m.bucket != "<unknown>") parts += m.bucket
                parts += Io.bytes(m.size)
                h.sub.text = parts.joinToString(" · ")
                h.chip.visibility = View.GONE
                bindRowSel(h, picks.containsKey("m:${m.uri}"), false)
            }
            list.adapter = ad
            list.setOnItemClickListener { _, v, pos, _ ->
                val m = mediaItems[kind]?.getOrNull(pos) ?: return@setOnItemClickListener
                toggle(pickOf(m))
                (v.tag as? RowHolder)?.let { bindRowSel(it, picks.containsKey("m:${m.uri}"), true) }
                Ui.haptic(v)
            }
            pane = Pane(list, ad, null)
        } else {
            val cols = when { Ui.tv(this) -> 6; Ui.wide(this) -> 5; else -> 3 }
            val gap = dp(6)
            val gridW = resources.displayMetrics.widthPixels - 2 * gut
            val px = ((gridW - (cols - 1) * gap) / cols).coerceAtLeast(dp(80))
            val grid = GridView(this).apply {
                numColumns = cols
                horizontalSpacing = gap
                verticalSpacing = gap
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                selector = PickUi.selector(this@PickerActivity, 12)
                setDrawSelectorOnTop(true)
                cacheColorHint = Color.TRANSPARENT
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
            }
            val ad = GridAdapter(kind, px)
            grid.adapter = ad
            grid.setOnItemClickListener { _, v, pos, _ ->
                val m = mediaItems[kind]?.getOrNull(pos) ?: return@setOnItemClickListener
                toggle(pickOf(m))
                (v.tag as? Cell)?.let { bindCell(it, picks.containsKey("m:${m.uri}"), true) }
                Ui.haptic(v)
            }
            pane = Pane(grid, ad, null)
        }
        panes[tab] = pane
        loadMedia(kind, pane)
        return pane.view
    }

    private fun pickOf(m: MediaItem) = Pick("m:${m.uri}", KIND_MEDIA, m.name, 1, m.size, m.name, m.uri,
        { listOf(Media.toSendItem(m)) })

    private fun loadMedia(kind: MediaKind, pane: Pane) {
        val acc = Media.access(this, kind)
        lastAccess[kind] = acc
        if (acc == Media.Access.NONE) {
            mediaItems[kind] = emptyList()
            pane.adapter.notifyDataSetChanged()
            val what = when (kind) { MediaKind.IMAGE -> "photos"; MediaKind.VIDEO -> "videos"; MediaKind.AUDIO -> "music" }
            val icon = when (kind) { MediaKind.IMAGE -> R.drawable.ic_photo; MediaKind.VIDEO -> R.drawable.ic_video; MediaKind.AUDIO -> R.drawable.ic_music }
            val tint = when (kind) { MediaKind.IMAGE -> Theme.CAT_PHOTOS; MediaKind.VIDEO -> Theme.CAT_VIDEOS; MediaKind.AUDIO -> Theme.CAT_MUSIC }
            val settings = kind in denied && Build.VERSION.SDK_INT >= 23 &&
                Media.permissions(kind).firstOrNull()?.let { !shouldShowRequestPermissionRationale(it) } == true
            pane.showMessage(icon, tint, "Allow access to $what",
                "FastDrop only reads $what to show them here. Nothing leaves your device except what you send.",
                if (settings) "Open settings" else "Allow access", onAction = { requestMedia(kind) })
            refreshBanner(); refreshSelectAll()
            return
        }
        val first = mediaItems[kind] == null
        if (first) pane.showLoading("Looking for your ${if (kind == MediaKind.AUDIO) "music" else if (kind == MediaKind.IMAGE) "photos" else "videos"}…")
        val gen = (loadGen[kind] ?: 0) + 1
        loadGen[kind] = gen
        val app = applicationContext
        thread(name = "picker-media") {
            val list = try { Media.query(app, kind) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed || loadGen[kind] != gen) return@runOnUiThread
                mediaItems[kind] = list
                pane.adapter.notifyDataSetChanged()
                if (list.isEmpty()) {
                    val (icon, tint, t, m) = when (kind) {
                        MediaKind.IMAGE -> Quad(R.drawable.ic_photo, Theme.CAT_PHOTOS, "No photos yet", "Photos on this device will show up here.")
                        MediaKind.VIDEO -> Quad(R.drawable.ic_video, Theme.CAT_VIDEOS, "No videos yet", "Videos on this device will show up here.")
                        MediaKind.AUDIO -> Quad(R.drawable.ic_music, Theme.CAT_MUSIC, "No music yet", "Songs on this device will show up here.")
                    }
                    pane.showMessage(icon, tint, t, m)
                } else pane.showContent()
                refreshBanner(); refreshSelectAll()
            }
        }
    }

    private data class Quad(val a: Int, val b: Int, val c: String, val d: String)

    private fun requestMedia(kind: MediaKind) {
        if (Build.VERSION.SDK_INT < 23) return
        val perms = Media.permissions(kind)
        if (perms.isEmpty()) return
        if (kind in denied && !shouldShowRequestPermissionRationale(perms[0])) {
            openAppSettings(); return
        }
        requestPermissions(perms, REQ_MEDIA + kind.ordinal)
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("Open Settings, then Apps, then FastDrop, then Permissions", 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BROWSER_PERMS) { openBrowser(); return }
        val kind = MediaKind.values().getOrNull(requestCode - REQ_MEDIA) ?: return
        if (grantResults.none { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) denied.add(kind)
        else denied.remove(kind)
        paneFor(kind)?.let { loadMedia(kind, it) }
    }

    /** Android 14+: shown when the user only shared some photos or videos. */
    private fun refreshBanner() {
        bannerHolder.removeAllViews()
        val kind = tabKind(defs.getOrNull(cur)?.id ?: "")
        val partial = Build.VERSION.SDK_INT >= 34 && kind != null && kind != MediaKind.AUDIO &&
            lastAccess[kind] == Media.Access.PARTIAL
        if (!partial || kind == null) { bannerHolder.visibility = View.GONE; return }
        val what = if (kind == MediaKind.IMAGE) "photos" else "videos"
        val row = Ui.row(this, R.drawable.ic_gallery, Theme.WARNING, "Showing only the $what you chose",
            "Tap to allow all $what", Ui.icon(this, R.drawable.ic_chevron, Theme.MUTED, 20)) { requestMedia(kind) }
        bannerHolder.background = PickUi.rounded(this, Theme.alpha(Theme.WARNING, 0.10f), 18, Theme.alpha(Theme.WARNING, 0.35f))
        bannerHolder.addView(row, FrameLayout.LayoutParams(-1, -2))
        val first = bannerHolder.visibility != View.VISIBLE
        bannerHolder.visibility = View.VISIBLE
        if (first) Anim.enter(bannerHolder)
    }

    // ---------- apps tab ----------

    private fun buildAppsPane(): View {
        val list = ListView(this)
        styleList(list)
        val ad = RowsAdapter({ apps.size }) { h, i ->
            val a = apps[i]
            Thumbs.app(h.iv, a.pkg)
            h.title.text = a.label
            val v = if (a.version.isBlank()) "" else "v${a.version.removePrefix("v")} · "
            h.sub.text = v + Io.bytes(a.size)
            h.chip.visibility = if (a.apks.size > 1) View.VISIBLE else View.GONE
            bindRowSel(h, picks.containsKey("a:${a.pkg}"), false)
        }
        list.adapter = ad
        list.setOnItemClickListener { _, v, pos, _ ->
            val a = apps.getOrNull(pos) ?: return@setOnItemClickListener
            toggle(pickOf(a))
            (v.tag as? RowHolder)?.let { bindRowSel(it, picks.containsKey("a:${a.pkg}"), true) }
            Ui.haptic(v)
        }
        val toggleRow = Ui.toggleRow(this, "Show system apps", "Include apps that came with the device",
            Hub.showSystemApps) { on ->
            Hub.showSystemApps = on
            loadApps()
        }
        val pane = Pane(list, ad, toggleRow)
        panes[TAB_APPS] = pane
        loadApps()
        return pane.view
    }

    private fun pickOf(a: AppEntry) = Pick("a:${a.pkg}", KIND_APP, a.label, 1, a.size, "x.apk", null,
        { Apps.toSendItems(a) })

    private fun loadApps() {
        val pane = panes[TAB_APPS] ?: return
        if (!appsLoaded) pane.showLoading("Reading your apps…")
        val gen = ++appsGen
        val app = applicationContext
        val sys = Hub.showSystemApps
        thread(name = "picker-apps") {
            val list = try { Apps.list(app, sys) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed || gen != appsGen) return@runOnUiThread
                apps = list
                appsLoaded = true
                pane.adapter.notifyDataSetChanged()
                if (list.isEmpty()) pane.showMessage(R.drawable.ic_apps, Theme.CAT_APPS, "No apps found",
                    "Turn on system apps above to see more.")
                else pane.showContent()
                refreshSelectAll()
            }
        }
    }

    // ---------- files tab ----------

    private fun buildFilesPane(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gut, dp(4), gut, dp(110) + bottomInset)
        }
        val card = Ui.card(this, 6)
        fun action(icon: Int, tint: Int, title: String, sub: String, onClick: () -> Unit) {
            card.addView(Ui.row(this, icon, tint, title, sub, Ui.icon(this, R.drawable.ic_chevron, Theme.MUTED, 20), onClick))
        }
        action(R.drawable.ic_file, Theme.CAT_FILES, "Pick files", "Documents, archives, anything", ::pickFiles)
        card.addView(Ui.divider(this))
        action(R.drawable.ic_folder, Theme.WARNING, "Pick a folder", "Send a whole folder with its files", ::pickFolder)
        card.addView(Ui.divider(this))
        action(R.drawable.ic_storage, Theme.CAT_MUSIC, "Browse storage", "USB drives, SD cards and system folders", ::browseStorage)
        page.addView(card, LinearLayout.LayoutParams(-1, -2))

        page.addView(Ui.label(this, "Picked files"), LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(24); bottomMargin = dp(10); leftMargin = dp(4)
        })
        val status = Ui.text(this, "", 13.5f, Theme.ACCENT_2, Theme.MEDIUM).apply { visibility = View.GONE }
        filesStatus = status
        page.addView(status, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10); leftMargin = dp(4) })
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filesList = l
        page.addView(l, LinearLayout.LayoutParams(-1, -2))
        val sv = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(page, FrameLayout.LayoutParams(-1, -2))
        }
        // The page keeps its own bottom padding; keep it in step with the nav bar.
        sv.setOnApplyWindowInsetsListener { _, ins ->
            page.setPadding(gut, dp(4), gut, dp(110) + Ui.insetsOf(ins)[3])
            ins
        }
        rebuildPicked()
        return sv
    }

    private fun rebuildPicked() {
        val l = filesList ?: return
        l.removeAllViews()
        val mine = picks.values.filter { it.kind == KIND_FILE }
        if (mine.isEmpty()) {
            l.addView(Ui.text(this, "Nothing picked yet. Files you add here stay selected while you switch tabs.",
                14f, Theme.MUTED).apply { setPadding(dp(4), 0, dp(4), 0) })
            return
        }
        val px = dp(96)
        val views = ArrayList<View>()
        for (p in mine) {
            val h = newRow()
            Thumbs.file(h.iv, p.thumbName, p.thumbUri, px)
            h.title.text = p.title
            h.sub.text = if (p.count > 1) "${p.count} files · ${Io.bytes(p.size)}" else Io.bytes(p.size)
            h.chip.visibility = View.GONE
            bindRowSel(h, true, false)
            h.root.isFocusable = true
            Ui.focusable(h.root, 1.02f, 16f)
            h.root.setOnClickListener {
                Ui.haptic(it)
                toggle(p)
            }
            l.addView(h.root, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            views += h.root
        }
        Anim.stagger(views, 0, 30)
    }

    private fun setFilesBusy(delta: Int) {
        filesBusy = (filesBusy + delta).coerceAtLeast(0)
        filesStatus?.let {
            it.text = "Reading files…"
            it.visibility = if (filesBusy > 0) View.VISIBLE else View.GONE
        }
    }

    private fun filesIntent(action: String) = Intent(action).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    @Suppress("DEPRECATION")
    private fun pickFiles() {
        try {
            startActivityForResult(filesIntent(Intent.ACTION_OPEN_DOCUMENT), REQ_FILES)
        } catch (_: Exception) {
            try {
                startActivityForResult(filesIntent(Intent.ACTION_GET_CONTENT), REQ_FILES)
            } catch (_: Exception) {
                browseStorage()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (_: Exception) {
            browseStorage()
        }
    }

    @Suppress("DEPRECATION")
    private fun openBrowser() {
        startActivityForResult(Intent(this, BrowserActivity::class.java), REQ_BROWSER)
    }

    private fun browseStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return openBrowser()
            askAllFiles()
        } else requestReadPermissions()
    }

    /** Explains "All files access" before sending the user to Settings. */
    private fun askAllFiles() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Ui.title(this, "Browse all files", 22f))
        c.addView(Ui.text(this, "Allow \"All files access\" to browse every folder, including USB drives and SD cards. " +
            "Or choose media only to keep it limited.", 14.5f, Theme.MUTED).apply { setPadding(0, dp(10), 0, dp(20)) })
        var handle: Sheets.Handle? = null
        val allow = Ui.primaryButton(this, "Allow all files", Theme.BRAND, null) {
            handle?.dismiss()
            requestAllFilesAccess()
        }
        c.addView(allow, LinearLayout.LayoutParams(-1, -2))
        c.addView(Ui.secondaryButton(this, "Media only") {
            handle?.dismiss()
            requestReadPermissions()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        handle = Sheets.show(root, c)
        allow.post { allow.requestFocus() }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return
        pendingAllFiles = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                pendingAllFiles = false
                requestReadPermissions()
            }
        }
    }

    private fun requestReadPermissions() {
        if (Build.VERSION.SDK_INT < 23) return openBrowser()
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO")
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) return openBrowser()
        requestPermissions(perms, REQ_BROWSER_PERMS)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris.add(it) } }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                for (u in uris) persist(u, data)
                addFromWorker { uris.mapNotNull { u ->
                    Storage.itemFromUri(this, u)?.let { item ->
                        Pick("u:$u", KIND_FILE, item.name.substringAfterLast('/'), 1, item.size, item.name, u, { listOf(item) })
                    }
                } }
            }
            REQ_FOLDER -> data.data?.let { tree ->
                persist(tree, data)
                addFromWorker {
                    val items = Storage.itemsFromTree(this, tree)
                    if (items.isEmpty()) emptyList()
                    else listOf(Pick("t:$tree", KIND_FILE, items[0].name.substringBefore('/'), items.size,
                        items.sumOf { it.size }, "folder", null, { items }))
                }
            }
            REQ_BROWSER -> {
                val paths = data.getStringArrayListExtra("paths") ?: return
                addFromWorker {
                    paths.mapNotNull { p ->
                        val f = File(p)
                        val items = Storage.itemsFromFile(f)
                        if (items.isEmpty()) null
                        else Pick("p:${f.absolutePath}", KIND_FILE, f.name, items.size, items.sumOf { it.size },
                            if (f.isDirectory) "folder" else f.name, if (f.isFile) Uri.fromFile(f) else null, { items })
                    }
                }
            }
        }
    }

    /** Keeps read access after this screen closes (grants on a result die with the activity). */
    private fun persist(uri: Uri, data: Intent) {
        if (uri.scheme != "content") return
        try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
            val old = contentResolver.persistedUriPermissions
            if (old.size >= MAX_PERSISTED) {
                // Drop the oldest grants that are not part of the current selection.
                val inUse = picks.keys
                old.sortedBy { it.persistedTime }.take(old.size - MAX_PERSISTED + 10).forEach { p ->
                    if (inUse.none { k -> k.endsWith(p.uri.toString()) }) {
                        try { contentResolver.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    }
                }
            }
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun addFromWorker(work: () -> List<Pick>) {
        setFilesBusy(1)
        thread(name = "picker-prepare") {
            val list = try { work() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setFilesBusy(-1)
                if (list.isEmpty()) {
                    toast("No files found", 2)
                } else {
                    for (p in list) picks[p.key] = p
                    val n = list.sumOf { it.count }
                    toast(if (n == 1) "Added 1 file" else "Added $n files", 1)
                    onPicksChanged()
                }
            }
        }
    }

    // ---------- selection ----------

    private fun toggle(p: Pick) {
        if (picks.remove(p.key) == null) picks[p.key] = p
        onPicksChanged()
    }

    private fun onPicksChanged() {
        updateBar(true)
        refreshSelectAll()
        rebuildPicked()
    }

    private fun updateBar(animate: Boolean) {
        var n = 0
        var size = 0L
        for (p in picks.values) { n += p.count; size += p.size }
        bar.update(n, if (n > 0) Io.bytes(size) else "", "selected", animate)
    }

    /** What "Select all" covers on the current tab. */
    private fun visiblePicks(): List<Pick> {
        val id = defs.getOrNull(cur)?.id ?: return emptyList()
        return when (id) {
            TAB_APPS -> apps.map { pickOf(it) }
            TAB_FILES -> emptyList()
            else -> (tabKind(id)?.let { mediaItems[it] } ?: emptyList()).map { pickOf(it) }
        }
    }

    private fun refreshSelectAll() {
        if (!::selectAllBtn.isInitialized) return
        val id = defs.getOrNull(cur)?.id
        if (id == null || id == TAB_FILES) { selectAllBtn.visibility = View.GONE; return }
        val kind = tabKind(id)
        val list = kind?.let { mediaItems[it] }
        val count = if (id == TAB_APPS) apps.size else list?.size ?: 0
        if (count == 0) { selectAllBtn.visibility = View.GONE; return }
        selectAllBtn.visibility = View.VISIBLE
        val prefix = when (id) { TAB_APPS -> "a:"; else -> "m:" }
        var all = true
        if (id == TAB_APPS) { for (a in apps) if (!picks.containsKey(prefix + a.pkg)) { all = false; break } }
        else for (m in list!!) if (!picks.containsKey(prefix + m.uri)) { all = false; break }
        selectAllBtn.text = if (all) "Clear all" else "Select all"
    }

    private fun selectAll() {
        val id = defs.getOrNull(cur)?.id ?: return
        val all = visiblePicks()
        if (all.isEmpty()) return
        if (all.all { picks.containsKey(it.key) }) all.forEach { picks.remove(it.key) }
        else all.forEach { if (!picks.containsKey(it.key)) picks[it.key] = it }
        panes[id]?.adapter?.notifyDataSetChanged()
        Ui.haptic(selectAllBtn)
        onPicksChanged()
    }

    private fun next() {
        if (finishing || picks.isEmpty()) return
        finishing = true
        bar.setBusy(true)
        val chosen = picks.values.toList()
        thread(name = "picker-next") {
            val items = ArrayList<SendItem>()
            for (p in chosen) try { items.addAll(p.build()) } catch (_: Exception) { }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (items.isEmpty()) {
                    finishing = false
                    bar.setBusy(false)
                    toast("Couldn't read the selected items", 2)
                } else {
                    Hub.selection = items
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun toast(msg: String, kind: Int = 0) = Ui.snackbar(root, msg, kind)
}
