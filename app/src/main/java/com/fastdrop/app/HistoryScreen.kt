package com.fastdrop.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.util.Calendar
import java.util.Date
import kotlin.concurrent.thread

/** A bold screen header: back button and a HERO title, with an optional trailing view. Shared by History and Trusted devices. */
internal fun bigHeader(host: MainActivity, title: String, trailing: View? = null): View {
    val c: android.content.Context = host
    return LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Theme.dp(c, if (Ui.tv(c)) 72 else 64)
        setPadding(Theme.dp(c, 4), 0, Ui.gutter(c), 0)
        addView(Ui.iconButton(c, R.drawable.ic_back, c.getString(R.string.cd_back)) { host.pop() },
            LinearLayout.LayoutParams(-2, -2))
        addView(Ui.text(c, title, Type.HERO).apply {
            maxLines = 1
            setPadding(Theme.dp(c, 4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Theme.dp(c, 12) })
    }
}

/** A confirm sheet whose main button is the red destructive one (docs 2.3). Initial focus is on Cancel. */
internal fun confirmDanger(host: MainActivity, title: String, body: String, positive: String, negative: String, onOk: () -> Unit) {
    val c: android.content.Context = host
    var h: Sheets.Handle? = null
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    col.addView(Ui.text(c, title, Type.TITLE))
    col.addView(Ui.text(c, body, Type.BODY, Theme.INK_2).apply { setPadding(0, Theme.dp(c, 8), 0, Theme.dp(c, 20)) })
    val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    val cancel = Ui.secondaryButton(c, negative) { h?.dismiss() }
    row.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Theme.dp(c, 10) })
    row.addView(Ui.jobButton(c, positive, Job.DANGER) { h?.dismiss(); onOk() }, LinearLayout.LayoutParams(0, -2, 1f))
    col.addView(row)
    h = Sheets.show(host.root, col, true)
    cancel.post { cancel.requestFocus() }
}

/**
 * History (docs 4.7): every transfer, both directions, grouped by day, with All / Sent / Received / Text
 * filters. Tap a row for all its actions. Phone: neutral tabs on top. TV: a filter rail on the left.
 * Colour is limited to the small direction/type plates and job-coloured primary buttons.
 *
 * Open it with `HistoryScreen.show(host)`.
 */
class HistoryScreen(host: MainActivity) : Screen(host) {

    companion object {
        /** Opens History on top of the current screen (ignored if it's already open). */
        fun show(host: MainActivity) { host.push(HistoryScreen(host)) }

        private const val F_ALL = 0
        private const val F_SENT = 1
        private const val F_RECEIVED = 2
        private const val F_TEXT = 3
    }

    private var filter = F_ALL
    private var sig: String? = null
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private var railBox: LinearLayout? = null
    private var tabs: Ui.Tabs? = null
    private var clearBtn: View? = null
    private var actionSheet: Sheets.Handle? = null
    private val listener: () -> Unit = { Hub.main.post { if (::listBox.isInitialized) render() } }

    private fun dp(v: Int) = Theme.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    private val filterLabels = listOf(R.string.hist_tab_all, R.string.hist_tab_sent, R.string.hist_tab_received, R.string.hist_tab_text)

    // ---------------------------------------------------------------- build

    override fun build(): View {
        val c = ctx
        sig = null
        val clear = Ui.quietButton(c, s(R.string.hist_clear_all), danger = true) { confirmClear() }
        clearBtn = clear
        listBox = Ui.column(c)
        scroll = ScrollView(c).apply {
            isFocusable = false   // a focus stop with no visible focus is a trap
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
            addView(listBox, FrameLayout.LayoutParams(-1, -2))
        }

        val page: View
        if (Ui.tv(c)) {
            // Left rail (400 dp incl. margin) of filters, 1 px rule, the list on the right.
            val rail = Ui.column(c)
            railBox = rail
            tabs = null
            val left = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(72)
                    setPadding(dp(4), 0, 0, 0)
                    addView(Ui.iconButton(c, R.drawable.ic_back, s(R.string.cd_back)) { host.pop() })
                    addView(Ui.text(c, s(R.string.hist_title), Type.HERO).apply { setPadding(dp(4), 0, 0, 0) })
                })
                addView(rail, LinearLayout.LayoutParams(-1, -2))
                addView(clear, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(36); topMargin = dp(8) })
            }
            val right = FrameLayout(c).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
            page = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(Theme.TV_SAFE_V), 0, dp(Theme.TV_SAFE_V))
                addView(left, LinearLayout.LayoutParams(dp(400), -1))
                addView(View(c).apply { setBackgroundColor(Theme.LINE) }, LinearLayout.LayoutParams(Theme.rule(c), -1))
                addView(right, LinearLayout.LayoutParams(0, -1, 1f))
            }
        } else {
            railBox = null
            val t = Ui.tabs(c, filterLabels.map { s(it) to null }, filter) { filter = it; render() }
            tabs = t
            page = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(bigHeader(host, s(R.string.hist_title), clear))
                addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                Ui.applyInsets(this, true, false)
            }
            Ui.applyInsets(scroll, false, true)
        }
        render()
        return page
    }

    // ---------------------------------------------------------------- data

    private fun matches(e: HistoryEntry, f: Int) = when (f) {
        F_SENT -> e.outgoing
        F_RECEIVED -> !e.outgoing
        F_TEXT -> e.text != null
        else -> true
    }

    private fun dayKey(at: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun dayLabel(at: Long): String {
        val now = System.currentTimeMillis()
        return when (dayKey(at)) {
            dayKey(now) -> s(R.string.hist_today)
            dayKey(now - 24L * 3600 * 1000) -> s(R.string.hist_yesterday)
            else -> DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        if (!::listBox.isInitialized) return
        val all = HistoryStore.all()
        val newSig = filter.toString() + "|" + all.size + "|" + all.joinToString(",") { it.id }
        if (newSig == sig) return
        sig = newSig
        val c = ctx
        val list = all.filter { matches(it, filter) }
        val focusKey = listBox.findFocus()?.tag
        listBox.removeAllViews()
        clearBtn?.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        tabs?.select(filter)

        var firstRow: View? = null
        if (list.isEmpty()) {
            listBox.addView(emptyState(all.isEmpty()))
        } else {
            var lastDay = Int.MIN_VALUE
            for (e in list) {
                val k = dayKey(e.at)
                if (k != lastDay) {
                    lastDay = k
                    listBox.addView(Ui.section(c, dayLabel(e.at)))
                }
                val row = rowFor(e)
                row.tag = e.id
                listBox.addView(row, LinearLayout.LayoutParams(-1, -2))
                if (firstRow == null) firstRow = row
                if (e.id == focusKey) row.requestFocus()
            }
        }
        if (Ui.tv(c)) renderRail(firstRow, all)
    }

    /** TV filter rail: the selected filter is an INK-filled row (activated). */
    private fun renderRail(firstRow: View?, all: List<HistoryEntry>) {
        val rail = railBox ?: return
        val c = ctx
        rail.removeAllViews()
        var selectedId = View.NO_ID
        if (firstRow != null && firstRow.id == View.NO_ID) firstRow.id = View.generateViewId()
        filterLabels.indices.forEach { i ->
            val r = Ui.row(c, null, s(filterLabels[i]), null, Ui.tag(c, all.count { matches(it, i) }.toString())) {
                filter = i; render()
                railBox?.getChildAt(i)?.requestFocus()
            }
            r.id = View.generateViewId()
            r.isActivated = i == filter
            if (i == filter) selectedId = r.id
            if (firstRow != null) r.nextFocusRightId = firstRow.id
            rail.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        if (firstRow != null && selectedId != View.NO_ID) {
            for (i in 0 until listBox.childCount) listBox.getChildAt(i).nextFocusLeftId = selectedId
        }
    }

    private fun emptyState(noHistoryAtAll: Boolean): View {
        val c = ctx
        val msg = when {
            noHistoryAtAll || filter == F_ALL -> R.string.hist_empty_all
            filter == F_SENT -> R.string.hist_empty_sent
            filter == F_RECEIVED -> R.string.hist_empty_received
            else -> R.string.hist_empty_text
        }
        val p = Theme.plateFor("files")
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val g = Ui.gutter(c)
            setPadding(g, dp(48), g, dp(24))
            addView(Ui.plate(c, R.drawable.ic_history, p.fill, p.on, 56), LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(Ui.text(c, s(msg), Type.TITLE).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
            addView(Ui.text(c, s(R.string.hist_empty_hint), Type.BODY, Theme.INK_2).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
                LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------------------------------------------------------------- rows

    private fun titleOf(e: HistoryEntry): String {
        val f = e.files.firstOrNull()
        return when {
            e.text != null -> HistoryStore.textPreview(e.text)
            f?.appLabel != null && f.appLabel.isNotEmpty() -> f.appLabel
            e.count > 1 -> ctx.resources.getQuantityString(R.plurals.hist_items, e.count, e.count)
            f != null -> f.name.substringAfterLast('/')
            else -> e.peer
        }
    }

    private fun metaOf(e: HistoryEntry): String {
        val time = DateFormat.getTimeFormat(ctx).format(Date(e.at))
        if (e.text != null) {
            val kind = if (ProtoV2.isHttpUrl(e.text)) s(R.string.hist_link) else s(R.string.hist_text)
            return "$time · " + (if (e.outgoing) s(R.string.hist_meta_to, kind, e.peer) else s(R.string.hist_meta_from, kind, e.peer))
        }
        if (!e.ok) return "$time · " + s(R.string.hist_meta_failed, e.peer)
        val size = Io.bytes(e.total)
        val base = if (e.outgoing) s(R.string.hist_meta_to, size, e.peer) else s(R.string.hist_meta_from, size, e.peer)
        val withSpeed = if (e.outgoing && e.avgSpeed > 0) s(R.string.hist_meta_speed, base, Io.speed(e.avgSpeed)) else base
        return "$time · $withSpeed"
    }

    /** A rounded 40 dp thumbnail loaded by [Thumbs] off the main thread (it shows the type plate while loading). */
    private fun thumbOf(e: HistoryEntry): View? {
        if (e.text != null || !e.ok) return null
        val f = e.files.firstOrNull { it.uri != null } ?: return null
        val mime = Storage.mime(f.name)
        val media = mime.startsWith("image/") || mime.startsWith("video/")
        if (!f.isApp && !media) return null
        val c = ctx
        val size = Theme.dp(c, if (Ui.tv(c)) 48 else 40)
        val r = Ui.dpf(c, Theme.R_CTRL)
        val iv = android.widget.ImageView(c).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Theme.SURFACE_2)
            layoutParams = LinearLayout.LayoutParams(size, size)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
            isDuplicateParentStateEnabled = true
        }
        if (f.appPkg != null) Thumbs.app(iv, f.appPkg)
        else Thumbs.file(iv, f.name, try { Uri.parse(f.uri) } catch (_: Exception) { null }, size * 2)
        return iv
    }

    private fun plateOf(e: HistoryEntry): View {
        val c = ctx
        val size = if (Ui.tv(c)) 48 else 40
        thumbOf(e)?.let { return it }
        return when {
            !e.ok -> Ui.plate(c, R.drawable.ic_alert, Theme.C_DANGER, Theme.ON_LIGHT, size)
            e.text != null -> {
                val p = Theme.plateFor("text")
                Ui.plate(c, if (ProtoV2.isHttpUrl(e.text)) R.drawable.ic_link else R.drawable.ic_copy, p.fill, p.on, size)
            }
            e.outgoing -> Ui.plate(c, R.drawable.ic_send, Theme.C_SEND, Theme.ON_DARK, size)
            else -> Ui.plate(c, R.drawable.ic_receive, Theme.C_RECEIVE, Theme.ON_LIGHT, size)
        }
    }

    private fun isInstallable(e: HistoryEntry) =
        !e.outgoing && e.ok && e.text == null && e.files.any { it.isApp || AppInstaller.isApk(it.name) }

    private fun rowFor(e: HistoryEntry): View {
        val c = ctx
        // One quick action on the row itself; everything else is in the action sheet.
        val quick: View? = when {
            isInstallable(e) -> Ui.secondaryButton(c, s(R.string.hist_action_install), null, true) { install(e) }
            e.text != null -> Ui.secondaryButton(c, s(R.string.hist_action_copy), null, true) { host.copyText("Text", e.text) }
            else -> null
        }
        return Ui.row(c, null, titleOf(e), metaOf(e), quick ?: Ui.chevron(c), lead = plateOf(e)) { showActions(e) }
    }

    // ---------------------------------------------------------------- action sheet

    private fun showActions(e: HistoryEntry) {
        val c = ctx
        actionSheet?.dismiss()
        var h: Sheets.Handle? = null
        fun act(block: () -> Unit): () -> Unit = { h?.dismiss(); block() }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        col.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(plateOf(e), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(c, titleOf(e), Type.TITLE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(Ui.text(c, metaOf(e), Type.META).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (e.text != null) {
            col.addView(Ui.text(c, e.text, Type.BODY).apply {
                maxLines = 4; ellipsize = TextUtils.TruncateAt.END
                setBackgroundColor(Theme.SURFACE_2)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        col.addView(View(c), LinearLayout.LayoutParams(-1, dp(14)))

        var primary: View? = null
        fun add(v: View) {
            col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            if (primary == null) primary = v
        }

        val url = e.text != null && ProtoV2.isHttpUrl(e.text)
        val hasFiles = e.text == null && e.files.any { it.uri != null }
        if (e.text != null) {
            val copy = act { host.copyText("Text", e.text) }
            if (url) {
                add(Ui.jobButton(c, s(R.string.hist_action_open_link), Job.TEXT, R.drawable.ic_link, act { host.openUrl(e.text) }))
                add(Ui.secondaryButton(c, s(R.string.hist_action_copy), R.drawable.ic_copy, onClick = copy))
            } else add(Ui.jobButton(c, s(R.string.hist_action_copy), Job.TEXT, R.drawable.ic_copy, copy))
            add(Ui.secondaryButton(c, s(R.string.hist_action_share), R.drawable.ic_share, onClick = act { shareText(e.text) }))
            if (!e.textTruncated) add(Ui.secondaryButton(c, s(R.string.hist_action_send_text_again), R.drawable.ic_send, onClick = act { sendTextAgain(e) }))
        } else {
            val install = isInstallable(e)
            if (install) add(Ui.jobButton(c, s(R.string.hist_action_install), Job.OK, R.drawable.ic_install, act { install(e) }))
            if (hasFiles && e.ok) {
                val onOpen = act { open(e) }
                add(if (install) Ui.secondaryButton(c, s(R.string.hist_action_open), R.drawable.ic_file, onClick = onOpen)
                    else Ui.jobButton(c, s(R.string.hist_action_open), if (e.outgoing) Job.SEND else Job.RECEIVE, R.drawable.ic_file, onOpen))
            }
            if (hasFiles) add(Ui.secondaryButton(c, s(R.string.hist_action_share), R.drawable.ic_share, onClick = act { shareFiles(e) }))
            if (e.files.any { it.uri != null }) {
                add(Ui.secondaryButton(c, s(R.string.hist_action_send_again), R.drawable.ic_send, onClick = act { sendAgain(e) }))
            }
        }
        col.addView(Ui.quietButton(c, s(R.string.hist_action_remove), danger = true,
            onClick = act { HistoryStore.remove(e.id); host.toast(s(R.string.hist_removed), 1) }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        h = Sheets.show(host.root, col, true)
        actionSheet = h
        primary?.post { primary?.requestFocus() }
    }

    // ---------------------------------------------------------------- actions

    private fun receivedOf(e: HistoryEntry, f: HFile): Received? {
        val u = f.uri ?: return null
        val uri = try { Uri.parse(u) } catch (_: Exception) { return null }
        val tag = f.appPkg?.let { AppTag(f.appLabel ?: "", it, f.appVer ?: "") }
        return Received(f.name, f.size, uri, e.peer, tag, e.at)
    }

    private fun exists(r: Received): Boolean {
        val u = r.uri ?: return false
        return u.scheme != "file" || File(u.path ?: return false).exists()
    }

    private fun open(e: HistoryEntry) {
        val f = e.files.firstOrNull { it.uri != null } ?: return host.toast(s(R.string.hist_missing), 2)
        val r = receivedOf(e, f)
        if (r == null || !exists(r)) return host.toast(s(R.string.hist_missing), 2)
        host.openReceived(r)
    }

    private fun install(e: HistoryEntry) {
        val group = e.files.filter { it.isApp || AppInstaller.isApk(it.name) }.mapNotNull { receivedOf(e, it) }
        if (group.isEmpty() || group.none { exists(it) }) return host.toast(s(R.string.hist_missing), 2)
        host.installApp(group)
    }

    private fun shareText(text: String) {
        try {
            host.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        } catch (_: Exception) { host.toast(s(R.string.hist_cant_share), 2) }
    }

    private fun shareFiles(e: HistoryEntry) {
        val uris = HistoryStore.uris(e).filter { it.scheme != "file" || File(it.path ?: "").exists() }
        if (uris.isEmpty()) return host.toast(s(R.string.hist_missing), 2)
        try {
            if (uris.any { it.scheme == "file" } && Build.VERSION.SDK_INT in 24..28) {
                try { StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build()) } catch (_: Exception) { }
            }
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            send.type = if (uris.size == 1) Storage.mime(e.files.firstOrNull { it.uri != null }?.name ?: "") else "*/*"
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri(null, uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            send.clipData = clip
            host.startActivity(Intent.createChooser(send, null))
        } catch (_: Exception) { host.toast(s(R.string.hist_cant_share), 2) }
    }

    /** Rebuilding the items touches the content resolver, so it runs off the main thread. */
    private fun sendAgain(e: HistoryEntry) {
        val app = host.applicationContext
        thread(name = "history-send-again") {
            val items = try { HistoryStore.sendAgain(app, e) } catch (_: Exception) { null }
            Hub.main.post {
                if (host.isDestroyed) return@post
                if (items.isNullOrEmpty()) {
                    host.toast(s(R.string.hist_unavailable), 2)
                } else {
                    Hub.selection = items
                    Hub.selectionText = null
                    host.startSend()
                }
            }
        }
    }

    private fun sendTextAgain(e: HistoryEntry) {
        val t = HistoryStore.sendAgainText(e) ?: return host.toast(s(R.string.hist_unavailable), 2)
        Hub.selection = emptyList()
        Hub.selectionText = t
        host.startSend()
    }

    private fun confirmClear() {
        confirmDanger(host, s(R.string.hist_clear_title), s(R.string.hist_clear_body), s(R.string.hist_clear_confirm),
            s(R.string.action_cancel)) { HistoryStore.clear() }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onShow() { HistoryStore.addListener(listener); render() }
    override fun onHide() { HistoryStore.removeListener(listener); actionSheet?.dismiss() }
    override fun refresh() = render()
    override fun firstFocus(): View? {
        if (Ui.tv(ctx)) return railBox?.getChildAt(filter)
        for (i in 0 until listBox.childCount) listBox.getChildAt(i).let { if (it.tag != null) return it }
        return clearBtn
    }
}
