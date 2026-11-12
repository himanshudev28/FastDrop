package com.fastdrop.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * Chooses what to send (docs 4.2): Apps, Photos, Videos, Music, Files and Text tabs, each in its
 * category colour. The selection survives tab changes. "Send" stores it in Hub.selection (or
 * Hub.selectionText for the Text tab) and finishes with RESULT_OK.
 *
 * Split across files: [PickUi] (shared helpers, check mark, selection bar), [PickerTabBar],
 * [PickerViews] (cells, rows, pane states, adapters) and [PickerTextPane].
 */
class PickerActivity : Activity() {

    companion object {
        const val TAB_APPS = "apps"
        const val TAB_PHOTOS = "photos"
        const val TAB_VIDEOS = "videos"
        const val TAB_MUSIC = "music"
        const val TAB_FILES = "files"
        const val TAB_TEXT = "text"
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

    /** Text is phone-only: typing with a D-pad is painful (docs 3.3). */
    private val defs: List<PickTab> by lazy {
        val l = arrayListOf(
            PickTab(TAB_APPS, getString(R.string.pick_tab_apps), Job.OK),
            PickTab(TAB_PHOTOS, getString(R.string.pick_tab_photos), Job.WEB),
            PickTab(TAB_VIDEOS, getString(R.string.pick_tab_videos), Job.RECEIVE),
            PickTab(TAB_MUSIC, getString(R.string.pick_tab_music), Job.TEXT),
            PickTab(TAB_FILES, getString(R.string.pick_tab_files), null),
        )
        if (!Ui.tv(this)) l.add(PickTab(TAB_TEXT, getString(R.string.pick_tab_text), Job.TEXT))
        l
    }

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
    private lateinit var tabBar: PickerTabBar
    private lateinit var selectAllBtn: TextView
    private lateinit var bar: PickUi.FloatingBar
    private lateinit var bannerHolder: FrameLayout
    private lateinit var paneViews: Array<View?>
    private val panes = HashMap<String, PickPane>()
    private var textPane: PickerTextPane? = null
    private var typed = ""
    private var cur = -1
    private var bottomInset = 0
    private var filesList: LinearLayout? = null
    private var filesStatus: TextView? = null
    private var filesBusy = 0
    private var pendingAllFiles = false
    private var finishing = false
    private var confirm: Sheets.Handle? = null

    private fun dp(v: Int) = Theme.dp(this, v)
    private val inset: () -> Int = { bottomInset }
    private val side get() = if (Ui.tv(this)) dp(16) else Ui.gutter(this)

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.resolve(this)
        PickUi.edgeToEdge(this)
        PickUi.registerBack(this) { cancel() }
        loadExisting()
        typed = Hub.selectionText.orEmpty()
        paneViews = arrayOfNulls(defs.size)
        setContentView(buildUi())
        Theme.applyWindow(this)
        val want = intent?.getStringExtra(EXTRA_TAB) ?: if (typed.isNotEmpty()) TAB_TEXT else TAB_PHOTOS
        val start = defs.indexOfFirst { it.id == want }.let { if (it < 0) 1 else it }
        selectTab(start, false, force = true)
        updateBar(false)
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
        if (confirm?.showing == true) { confirm?.dismiss(); return }
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

    private fun buildUi(): View {
        val tv = Ui.tv(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Theme.BG)
            setOnApplyWindowInsetsListener { _, ins ->
                val b = Ui.insetsOf(ins, ime = true)[3]
                if (b != bottomInset) {
                    bottomInset = b
                    for (p in panes.values) p.applyPadding()
                    textPane?.applyPadding()
                }
                ins
            }
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(column, top = true, bottom = false, extraDp = if (tv) Theme.TV_SAFE_V else 0)

        selectAllBtn = Ui.quietButton(this, getString(R.string.pick_select_all)) { selectAll() }
        column.addView(Ui.header(this, getString(R.string.pick_title), { cancel() }, selectAllBtn),
            LinearLayout.LayoutParams(-1, -2))

        tabBar = PickerTabBar(this, defs, vertical = tv) { selectTab(it, true) }
        bannerHolder = FrameLayout(this).apply { visibility = View.GONE }
        content = FrameLayout(this)

        if (tv) {
            // Two panes (docs 5.1): a 240 dp tab rail on the left, the content on the right.
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(tabBar, LinearLayout.LayoutParams(dp(240), -2).apply { leftMargin = dp(Theme.TV_GUTTER - 4) })
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(bannerHolder, LinearLayout.LayoutParams(-1, -2))
            right.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            body.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(16) })
            column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            column.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = Ui.gutter(this@PickerActivity) - dp(4); rightMargin = Ui.gutter(this@PickerActivity) - dp(4)
            })
            column.addView(Ui.rule(this), LinearLayout.LayoutParams(-1, Theme.rule(this)).apply { topMargin = dp(4) })
            column.addView(bannerHolder, LinearLayout.LayoutParams(-1, -2))
            column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        bar = PickUi.FloatingBar(this, getString(R.string.pick_send)) { next() }
        bar.button.id = View.generateViewId()
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return root
    }

    // ---------- tabs ----------

    private fun textIdx() = defs.indexOfFirst { it.id == TAB_TEXT }

    private fun selectTab(idx: Int, user: Boolean, force: Boolean = false) {
        if (idx == cur) return
        // Text is sent on its own: switching to it with files selected asks first.
        if (idx == textIdx() && picks.isNotEmpty() && !force) { askClearForText(idx); return }
        val old = if (cur >= 0) paneViews[cur] else null
        val v = ensurePane(idx)
        cur = idx
        tabBar.select(idx)
        if (user && Anim.on(this) && old != null) Anim.crossfade(old, v) else {
            old?.visibility = View.GONE
            v.visibility = View.VISIBLE
            v.alpha = 1f
        }
        refreshBanner()
        refreshSelectAll()
        updateBar(user)
        if (defs[idx].id == TAB_TEXT) textPane?.focus()
    }

    private fun askClearForText(textTab: Int) {
        val n = picks.values.sumOf { it.count }
        val c = Ui.column(this)
        c.addView(Ui.text(this, getString(R.string.pick_clear_title), Type.TITLE))
        c.addView(Ui.text(this, resources.getQuantityString(R.plurals.pick_clear_msg, n, n), Type.BODY).apply {
            setPadding(0, dp(8), 0, dp(20))
        })
        var h: Sheets.Handle? = null
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val keep = Ui.secondaryButton(this, getString(R.string.pick_keep_files)) { h?.dismiss() }
        val clear = Ui.jobButton(this, getString(R.string.pick_clear), Job.DANGER) {
            h?.dismiss()
            picks.clear()
            for (p in panes.values) p.adapter.notifyDataSetChanged()
            onPicksChanged()
            selectTab(textTab, true)
        }
        row.addView(keep, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        row.addView(clear, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(row, LinearLayout.LayoutParams(-1, -2))
        h = Sheets.show(root, c)
        confirm = h
        keep.post { keep.requestFocus() }
    }

    private fun ensurePane(idx: Int): View {
        paneViews[idx]?.let { return it }
        val v: View = when (defs[idx].id) {
            TAB_APPS -> buildAppsPane()
            TAB_PHOTOS -> buildMediaPane(MediaKind.IMAGE)
            TAB_VIDEOS -> buildMediaPane(MediaKind.VIDEO)
            TAB_MUSIC -> buildMediaPane(MediaKind.AUDIO)
            TAB_TEXT -> buildTextPane()
            else -> buildFilesPane()
        }
        v.visibility = View.GONE
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        paneViews[idx] = v
        return v
    }

    private fun paneFor(kind: MediaKind): PickPane? = panes[kindTab(kind)]

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

    // ---------- text tab ----------

    private fun buildTextPane(): View {
        val p = PickerTextPane(this, typed, inset, { clipboardText() }, { toast(it, 2) }) { t ->
            typed = t
            updateBar(false)
        }
        textPane = p
        return p.view
    }

    private fun clipboardText(): String? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** D-pad: left / right off a list's edge reaches the Send bar, so a long list never strands it. */
    private fun linkBar(v: View) {
        if (Ui.tv(this)) return
        v.nextFocusLeftId = bar.button.id
        v.nextFocusRightId = bar.button.id
    }

    // ---------- media tabs (photos / videos / music) ----------

    private fun isPicked(m: MediaItem) = picks.containsKey("m:${m.uri}")

    private fun buildMediaPane(kind: MediaKind): View {
        val tab = kindTab(kind)
        val pane: PickPane
        if (kind == MediaKind.AUDIO) {
            val list = ListView(this)
            PickerViews.styleList(this, list)
            linkBar(list)
            val px = dp(96)
            val ad = RowsAdapter(this, { mediaItems[kind]?.size ?: 0 }) { h, i ->
                val m = mediaItems[kind]!![i]
                Thumbs.media(h.iv, m, px)
                h.title.text = m.name.substringBeforeLast('.')
                val parts = ArrayList<String>()
                if (m.durationMs > 0) parts += PickUi.fmtDuration(m.durationMs)
                if (!m.bucket.isNullOrBlank() && m.bucket != "<unknown>") parts += m.bucket
                parts += Io.bytes(m.size)
                h.sub.text = parts.joinToString(" · ")
                h.chip.visibility = View.GONE
                h.check.set(isPicked(m), false)
            }
            list.adapter = ad
            list.setOnItemClickListener { _, v, pos, _ ->
                val m = mediaItems[kind]?.getOrNull(pos) ?: return@setOnItemClickListener
                toggle(pickOf(m))
                (v.tag as? PickerViews.RowHolder)?.check?.set(isPicked(m), true)
                Ui.haptic(v)
            }
            pane = PickPane(this, list, ad, null, inset)
        } else {
            val tv = Ui.tv(this)
            val cols = when { tv -> 6; Ui.wide(this) -> 5; else -> 3 }
            val gap = dp(if (tv) 8 else 2)
            val avail = resources.displayMetrics.widthPixels -
                if (tv) dp(Theme.TV_GUTTER + 240 + 16 + 16 + 16) else 2 * Ui.gutter(this)
            val px = ((avail - (cols - 1) * gap) / cols).coerceAtLeast(dp(80))
            val grid = GridView(this).apply {
                numColumns = cols
                horizontalSpacing = gap
                verticalSpacing = gap
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                selector = PickUi.selector(this@PickerActivity, 0)
                setDrawSelectorOnTop(true)
                cacheColorHint = Color.TRANSPARENT
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
            }
            linkBar(grid)
            val ad = GridAdapter(this, { mediaItems[kind] }, kind == MediaKind.VIDEO, px, ::isPicked)
            grid.adapter = ad
            grid.setOnItemClickListener { _, v, pos, _ ->
                val m = mediaItems[kind]?.getOrNull(pos) ?: return@setOnItemClickListener
                toggle(pickOf(m))
                (v.tag as? Cell)?.let { PickerViews.bindCell(it, isPicked(m), true) }
                Ui.haptic(v)
            }
            pane = PickPane(this, grid, ad, null, inset)
        }
        panes[tab] = pane
        loadMedia(kind, pane)
        return pane.view
    }

    private fun pickOf(m: MediaItem) = Pick("m:${m.uri}", KIND_MEDIA, m.name, 1, m.size, m.name, m.uri,
        { listOf(Media.toSendItem(m)) })

    private fun loadMedia(kind: MediaKind, pane: PickPane) {
        val acc = Media.access(this, kind)
        lastAccess[kind] = acc
        val icon = when (kind) { MediaKind.IMAGE -> R.drawable.ic_photo; MediaKind.VIDEO -> R.drawable.ic_video; MediaKind.AUDIO -> R.drawable.ic_music }
        if (acc == Media.Access.NONE) {
            mediaItems[kind] = emptyList()
            pane.adapter.notifyDataSetChanged()
            val settings = kind in denied && Build.VERSION.SDK_INT >= 23 &&
                Media.permissions(kind).firstOrNull()?.let { !shouldShowRequestPermissionRationale(it) } == true
            val (title, msg) = when (kind) {
                MediaKind.IMAGE -> getString(R.string.pick_perm_photos) to getString(R.string.pick_perm_photos_why)
                MediaKind.VIDEO -> getString(R.string.pick_perm_videos) to getString(R.string.pick_perm_videos_why)
                MediaKind.AUDIO -> getString(R.string.pick_perm_music) to getString(R.string.pick_perm_music_why)
            }
            pane.showMessage(icon, title, msg,
                getString(if (settings) R.string.pick_open_settings else R.string.pick_allow_access), onAction = { requestMedia(kind) })
            refreshBanner(); refreshSelectAll()
            return
        }
        val first = mediaItems[kind] == null
        if (first) pane.showLoading(getString(when (kind) {
            MediaKind.IMAGE -> R.string.pick_loading_photos
            MediaKind.VIDEO -> R.string.pick_loading_videos
            MediaKind.AUDIO -> R.string.pick_loading_music
        }))
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
                    val (t, m) = when (kind) {
                        MediaKind.IMAGE -> getString(R.string.pick_empty_photos) to getString(R.string.pick_empty_photos_meta)
                        MediaKind.VIDEO -> getString(R.string.pick_empty_videos) to getString(R.string.pick_empty_videos_meta)
                        MediaKind.AUDIO -> getString(R.string.pick_empty_music) to getString(R.string.pick_empty_music_meta)
                    }
                    pane.showMessage(icon, t, m)
                } else pane.showContent()
                refreshBanner(); refreshSelectAll()
            }
        }
    }

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
            toast(getString(R.string.pick_open_settings_manual), 2)
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

    /** Android 14+: shown when the user only shared some photos or videos. Neutral full-bleed row. */
    private fun refreshBanner() {
        bannerHolder.removeAllViews()
        val kind = tabKind(defs.getOrNull(cur)?.id ?: "")
        val partial = Build.VERSION.SDK_INT >= 34 && kind != null && kind != MediaKind.AUDIO &&
            lastAccess[kind] == Media.Access.PARTIAL
        if (!partial || kind == null) { bannerHolder.visibility = View.GONE; return }
        val photos = kind == MediaKind.IMAGE
        val row = Ui.row(this, R.drawable.ic_alert,
            getString(if (photos) R.string.pick_partial_photos else R.string.pick_partial_videos),
            getString(if (photos) R.string.pick_partial_photos_meta else R.string.pick_partial_videos_meta),
            Ui.chevron(this)) { requestMedia(kind) }
        bannerHolder.setBackgroundColor(Theme.SURFACE_2)
        bannerHolder.addView(row, FrameLayout.LayoutParams(-1, -2))
        bannerHolder.visibility = View.VISIBLE
    }

    // ---------- apps tab ----------

    private fun buildAppsPane(): View {
        val list = ListView(this)
        PickerViews.styleList(this, list)
        linkBar(list)
        val ad = RowsAdapter(this, { apps.size }) { h, i ->
            val a = apps[i]
            Thumbs.app(h.iv, a.pkg)
            h.title.text = a.label
            val v = if (a.version.isBlank()) "" else "v${a.version.removePrefix("v")} · "
            h.sub.text = v + Io.bytes(a.size)
            h.chip.visibility = if (a.apks.size > 1) View.VISIBLE else View.GONE
            h.check.set(picks.containsKey("a:${a.pkg}"), false)
        }
        list.adapter = ad
        list.setOnItemClickListener { _, v, pos, _ ->
            val a = apps.getOrNull(pos) ?: return@setOnItemClickListener
            toggle(pickOf(a))
            (v.tag as? PickerViews.RowHolder)?.check?.set(picks.containsKey("a:${a.pkg}"), true)
            Ui.haptic(v)
        }
        val toggleRow = Ui.toggleRow(this, getString(R.string.pick_system_apps), getString(R.string.pick_system_apps_meta),
            Hub.showSystemApps) { on ->
            Hub.showSystemApps = on
            loadApps()
        }
        val pane = PickPane(this, list, ad, toggleRow, inset)
        panes[TAB_APPS] = pane
        loadApps()
        return pane.view
    }

    private fun pickOf(a: AppEntry) = Pick("a:${a.pkg}", KIND_APP, a.label, 1, a.size, "x.apk", null,
        { Apps.toSendItems(a) })

    private fun loadApps() {
        val pane = panes[TAB_APPS] ?: return
        if (!appsLoaded) pane.showLoading(getString(R.string.pick_loading_apps))
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
                if (list.isEmpty()) pane.showMessage(R.drawable.ic_apps, getString(R.string.pick_empty_apps),
                    getString(R.string.pick_empty_apps_meta))
                else pane.showContent()
                refreshSelectAll()
            }
        }
    }

    // ---------- files tab ----------

    private fun buildFilesPane(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun action(icon: Int, kind: String, title: String, sub: String, onClick: () -> Unit) {
            val p = Theme.plateFor(kind)
            page.addView(Ui.row(this, null, title, sub, Ui.chevron(this),
                lead = Ui.plate(this, icon, p.fill, p.on, if (Ui.tv(this)) 48 else 40), onClick = onClick),
                LinearLayout.LayoutParams(-1, -2))
        }
        action(R.drawable.ic_file, "files", getString(R.string.pick_files), getString(R.string.pick_files_meta), ::pickFiles)
        page.addView(Ui.rule(this))
        action(R.drawable.ic_folder, "files", getString(R.string.pick_folder), getString(R.string.pick_folder_meta), ::pickFolder)
        page.addView(Ui.rule(this))
        action(R.drawable.ic_storage, "files", getString(R.string.pick_browse), getString(R.string.pick_browse_meta), ::browseStorage)
        page.addView(Ui.section(this, getString(R.string.pick_picked_files)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val status = Ui.text(this, getString(R.string.pick_reading_files), Type.META).apply {
            visibility = View.GONE
            setPadding(Ui.gutter(this@PickerActivity), 0, Ui.gutter(this@PickerActivity), dp(8))
        }
        filesStatus = status
        page.addView(status, LinearLayout.LayoutParams(-1, -2))
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filesList = l
        page.addView(l, LinearLayout.LayoutParams(-1, -2))
        val sv = ScrollView(this).apply {
            isFocusable = false   // a focus stop with no visible focus is a trap
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(96) + bottomInset)
            addView(page, FrameLayout.LayoutParams(-1, -2))
        }
        filesScroll = sv
        rebuildPicked()
        return sv
    }

    private var filesScroll: ScrollView? = null

    private fun rebuildPicked() {
        val l = filesList ?: return
        filesScroll?.setPadding(0, 0, 0, dp(96) + bottomInset)
        l.removeAllViews()
        val mine = picks.values.filter { it.kind == KIND_FILE }
        if (mine.isEmpty()) {
            l.addView(Ui.text(this, getString(R.string.pick_nothing_picked), Type.META).apply {
                setPadding(Ui.gutter(this@PickerActivity), dp(4), Ui.gutter(this@PickerActivity), dp(16))
            })
            return
        }
        val px = dp(96)
        for (p in mine) {
            val h = PickerViews.newRow(this)
            Thumbs.file(h.iv, p.thumbName, p.thumbUri, px)
            h.title.text = p.title
            h.sub.text = if (p.count > 1) resources.getQuantityString(R.plurals.pick_files_size, p.count, p.count, Io.bytes(p.size))
            else Io.bytes(p.size)
            h.chip.visibility = View.GONE
            h.check.set(true, false)
            Ui.focusFill(h.root, null, 1.02f, 0f, null)
            h.root.setOnClickListener {
                Ui.haptic(it)
                toggle(p)
            }
            l.addView(h.root, LinearLayout.LayoutParams(-1, -2))
            l.addView(Ui.rule(this))
        }
    }

    private fun setFilesBusy(delta: Int) {
        filesBusy = (filesBusy + delta).coerceAtLeast(0)
        filesStatus?.visibility = if (filesBusy > 0) View.VISIBLE else View.GONE
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
        c.addView(Ui.text(this, getString(R.string.pick_allfiles_title), Type.TITLE))
        c.addView(Ui.text(this, getString(R.string.pick_allfiles_msg), Type.BODY).apply { setPadding(0, dp(8), 0, dp(20)) })
        var handle: Sheets.Handle? = null
        val allow = Ui.jobButton(this, getString(R.string.pick_allfiles_allow), Job.SEND, null) {
            handle?.dismiss()
            requestAllFilesAccess()
        }
        c.addView(allow, LinearLayout.LayoutParams(-1, -2))
        c.addView(Ui.secondaryButton(this, getString(R.string.pick_allfiles_media)) {
            handle?.dismiss()
            requestReadPermissions()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
                    toast(getString(R.string.pick_no_files), 2)
                } else {
                    for (p in list) picks[p.key] = p
                    val n = list.sumOf { it.count }
                    toast(resources.getQuantityString(R.plurals.pick_added, n, n), 1)
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

    private fun onTextTab() = defs.getOrNull(cur)?.id == TAB_TEXT

    private fun updateBar(animate: Boolean) {
        if (!::bar.isInitialized) return
        if (onTextTab()) {
            val t = typed.trim()
            bar.updateLine(getString(R.string.pick_text_bar), resources.getQuantityString(R.plurals.pick_text_chars, t.length, t.length),
                t.isNotEmpty(), animate)
            return
        }
        var n = 0
        var size = 0L
        for (p in picks.values) { n += p.count; size += p.size }
        bar.updateLine(if (n > 0) getString(R.string.pick_n_selected, n) else "", if (n > 0) Io.bytes(size) else "", n > 0, animate)
    }

    /** What "Select all" covers on the current tab. */
    private fun visiblePicks(): List<Pick> {
        val id = defs.getOrNull(cur)?.id ?: return emptyList()
        return when (id) {
            TAB_APPS -> apps.map { pickOf(it) }
            TAB_FILES, TAB_TEXT -> emptyList()
            else -> (tabKind(id)?.let { mediaItems[it] } ?: emptyList()).map { pickOf(it) }
        }
    }

    private fun refreshSelectAll() {
        if (!::selectAllBtn.isInitialized) return
        val id = defs.getOrNull(cur)?.id
        if (id == null || id == TAB_FILES || id == TAB_TEXT) { selectAllBtn.visibility = View.GONE; return }
        val kind = tabKind(id)
        val list = kind?.let { mediaItems[it] }
        val count = if (id == TAB_APPS) apps.size else list?.size ?: 0
        if (count == 0) { selectAllBtn.visibility = View.GONE; return }
        selectAllBtn.visibility = View.VISIBLE
        val prefix = when (id) { TAB_APPS -> "a:"; else -> "m:" }
        var all = true
        if (id == TAB_APPS) { for (a in apps) if (!picks.containsKey(prefix + a.pkg)) { all = false; break } }
        else for (m in list!!) if (!picks.containsKey(prefix + m.uri)) { all = false; break }
        selectAllBtn.text = getString(if (all) R.string.pick_clear_all else R.string.pick_select_all)
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
        if (finishing) return
        if (onTextTab()) {
            val t = typed.trim()
            if (t.isEmpty()) return
            if (t.length > ProtoV2.MAX_TEXT) { toast(getString(R.string.pick_text_too_long), 2); return }
            // Files and text are exclusive: clear files first, then set the text.
            Hub.selection = emptyList()
            Hub.selectionText = t
            finishing = true
            setResult(RESULT_OK)
            finish()
            return
        }
        if (picks.isEmpty()) return
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
                    toast(getString(R.string.pick_cant_read), 2)
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
