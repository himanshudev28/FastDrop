package com.fastdrop.app

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * "Share with any device" (docs/DESIGN.md 4.10 and 5.5): runs the built-in web server so an iPhone,
 * a PC or a phone without FastDrop can download the selected files (and the FastDrop app itself)
 * and upload files back. The web identity is yellow, used as a plate and as the one filled button.
 * Step 1 joins our Wi-Fi (only when we host one), step 2 opens the page. Live counters and the
 * connected browsers follow, then Stop. On TV: status, counters and Stop on the left, the two QR
 * plates side by side on the right.
 */
class WebShareScreen(host: MainActivity, private val includeApp: Boolean) : Screen(host) {
    private companion object {
        // Start and stop run one after another so they can never overlap.
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "web-ctl").apply { isDaemon = true } }
    }

    private var shown = false
    private var starting = false
    private var failed = false
    private var lastWifiQr: String? = null
    private var lastUrlQr: String? = null
    private var lastEventSig = ""
    private var lastDl = 0L
    private var lastUl = 0L
    private var lastUrlsSig = ""
    private var lastStatusSig = ""

    private lateinit var statusBox: LinearLayout
    private lateinit var step1: View
    private lateinit var step2: View
    private lateinit var wifiQrView: QrView
    private lateinit var ssidRow: View
    private lateinit var passRow: View
    private lateinit var urlQrView: QrView
    private lateinit var urlText: TextView
    private lateinit var moreUrls: TextView
    private lateinit var copyBtn: TextView
    private lateinit var dlNum: TextView
    private lateinit var ulNum: TextView
    private lateinit var outNum: TextView
    private lateinit var inNum: TextView
    private lateinit var events: LinearLayout
    private lateinit var stopHeader: View
    private var liveBox: View? = null
    private var leftBox: LinearLayout? = null
    private var rightBox: LinearLayout? = null
    private var lastLeft: View? = null
    private var lastRight: View? = null

    private val stopCheck = Runnable { if (host.current !== this) stopAll() }

    private fun dp(v: Int) = Theme.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun tvMode() = Ui.tv(ctx)
    private fun ensureId(v: View): Int { if (v.id == View.NO_ID) v.id = View.generateViewId(); return v.id }
    private fun lp(top: Int = 0, w: Int = -1, h: Int = -2, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    override val wantsTick: Boolean get() = true

    override fun build(): View {
        lastWifiQr = null; lastUrlQr = null; lastEventSig = ""; lastDl = -1; lastUl = -1; lastUrlsSig = ""; lastStatusSig = ""
        val c = ctx
        val wide = Ui.wide(c)
        if (Ui.tv(c)) return buildTv()

        statusBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        step1 = buildStep1(wide)
        step2 = buildStep2(wide)
        val live = buildLive()

        stopHeader = Ui.quietButton(c, s(R.string.web_stop), danger = true) { stopAndLeave() }
        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.header(c, s(R.string.web_title), { stopAndLeave() }, stopHeader), lp())

        if (wide) {
            val cols = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            left.addView(statusBox, lp())
            left.addView(live, lp(top = 8))
            val g = Ui.gutter(c)
            left.addView(Ui.secondaryButton(c, s(R.string.web_stop_sharing), R.drawable.ic_close) { stopAndLeave() },
                lp(top = 16).apply { leftMargin = g; rightMargin = g })
            val qrs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            qrs.addView(step1, lp(w = 0, h = -2, weight = 1f))
            qrs.addView(step2, lp(w = 0, h = -2, weight = 1f))
            right.addView(qrs, lp())
            cols.addView(left, lp(w = 0, weight = 1f))
            cols.addView(right, lp(w = 0, weight = 1.3f))
            content.addView(cols, lp())
        } else {
            content.addView(statusBox, lp())
            content.addView(step1, lp())
            content.addView(step2, lp())
            content.addView(live, lp())
        }
        val root = Ui.scrollPage(c, content)
        refresh()
        return root
    }


    // ---------------------------------------------------------------- TV two-pane (docs 5.5)
    // Budget (dp, 540 - 2 x 27 safe = 486): right pane worst case = rule 1 + step label 102 + QR 220 + 8 margins
    // + (step 1: two rows 2 x 72 = 144 -> 475) or (step 2: url 40 + more 20 + copy 64 + gaps 12 -> 471).
    // Left pane = header 64 + status <= 136 + live (section 56 + stats 92 + event line 36 + 8) + Stop 72 = 464.
    private fun buildTv(): View {
        val c = ctx
        val g = Ui.gutter(c)
        statusBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        step1 = buildStep1(true)
        step2 = buildStep2(true)
        val live = buildLive()
        liveBox = live
        stopHeader = Ui.quietButton(c, s(R.string.web_stop), danger = true) { stopAndLeave() }

        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.header(c, s(R.string.web_title), { stopAndLeave() }, null), lp())
        left.addView(statusBox, lp())
        left.addView(live, lp(top = 8))
        left.addView(Ui.secondaryButton(c, s(R.string.web_stop_sharing), R.drawable.ic_close) { stopAndLeave() },
            lp(top = 16).apply { leftMargin = g; rightMargin = g })

        val right = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(step1, LinearLayout.LayoutParams(dp(220), -2))
        right.addView(step2, LinearLayout.LayoutParams(dp(220), -2).apply { marginStart = dp(16) })

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(Theme.TV_SAFE_V), 0, dp(Theme.TV_SAFE_V))
        }
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(right, LinearLayout.LayoutParams(-2, -1).apply { marginStart = dp(24); marginEnd = g })
        leftBox = left; rightBox = right
        lastLeft = null; lastRight = null
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, nf ->
            when {
                inside(left, nf) -> lastLeft = nf
                inside(right, nf) -> lastRight = nf
            }
            if (nf != null) linkPanes()
        }
        refresh()
        linkPanes()
        return root
    }

    private fun inside(box: View?, v: View?): Boolean {
        var p: android.view.ViewParent? = v?.parent
        while (p != null) { if (p === box) return true; p = p.parent }
        return false
    }

    /** Explicit pane links like HomeScreen: right from the left pane, left from the right pane, and between the two QR columns. */
    private fun linkPanes() {
        val l = leftBox ?: return
        val r = rightBox ?: return
        val lf = ArrayList<View>(); l.addFocusables(lf, View.FOCUS_RIGHT)
        val rf = ArrayList<View>(); r.addFocusables(rf, View.FOCUS_LEFT)
        val toRight = lastRight?.takeIf { it.isAttachedToWindow && it.isFocusable && it.visibility == View.VISIBLE } ?: rf.firstOrNull()
        val toLeft = lastLeft?.takeIf { it.isAttachedToWindow && it.isFocusable } ?: lf.firstOrNull()
        val rid = toRight?.let { ensureId(it) } ?: View.NO_ID
        val lid = toLeft?.let { ensureId(it) } ?: View.NO_ID
        for (v in lf) v.nextFocusRightId = rid
        for (v in rf) v.nextFocusLeftId = lid
        // Step 1 (left column of the right pane) and step 2 (right column) link to each other.
        val s1 = ArrayList<View>(); (step1 as? ViewGroup)?.addFocusables(s1, View.FOCUS_RIGHT)
        if (s1.isNotEmpty() && step1.visibility == View.VISIBLE) {
            val to2 = ensureId(copyBtn)
            val to1 = ensureId(s1.first())
            for (v in s1) v.nextFocusRightId = to2
            copyBtn.nextFocusLeftId = to1
        }
        // Vertical moves stay inside a pane: the ends point at themselves.
        for (list in listOf(lf, rf)) {
            if (list.isEmpty()) continue
            val loc = IntArray(2)
            val ys = list.map { v -> v.getLocationOnScreen(loc); loc[1] }
            val minY = ys.min(); val maxY = ys.max()
            list.forEachIndexed { i, v ->
                v.nextFocusUpId = if (ys[i] <= minY + dp(8)) ensureId(v) else View.NO_ID
                v.nextFocusDownId = if (ys[i] >= maxY - dp(8)) ensureId(v) else View.NO_ID
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    private fun numberPlate(n: Int): View = TextView(ctx).apply {
        text = n.toString()
        textSize = 13f
        typeface = Theme.MONO
        setTextColor(Job.WEB.on)
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply { setColor(Job.WEB.fill); cornerRadius = dp(Theme.R_CTRL.toInt()).toFloat() }
    }

    private fun stepHeader(n: Int, title: String, sub: String): View {
        if (tvMode()) return stepHeaderTv(n, title, sub)
        val g = Ui.gutter(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(g, dp(4), g, dp(8))
        }
        row.addView(numberPlate(n), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, title, Type.TITLE))
        col.addView(Ui.text(ctx, sub, Type.META).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** TV: number plate + title on one line (up to 2), the note under it across the full 220 dp. */
    private fun stepHeaderTv(n: Int, title: String, sub: String): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(numberPlate(n), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        top.addView(Ui.text(ctx, title, Type.ROW).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top, lp())
        col.addView(Ui.text(ctx, sub, Type.META).apply { maxLines = 2 }, lp(top = 2))
        return col
    }

    private fun qrBox(card: LinearLayout): QrView {
        val q = QrView(ctx)
        val tv = tvMode()
        val size = dp(if (tv) 220 else 200)
        card.addView(q, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(if (tv) 4 else 8); bottomMargin = dp(if (tv) 4 else 8)
        })
        return q
    }

    private fun buildStep1(wide: Boolean): View {
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(Ui.rule(ctx))
        card.addView(stepHeader(1, s(R.string.web_step1), s(if (tvMode()) R.string.web_step1_meta_tv else R.string.web_step1_meta)))
        wifiQrView = qrBox(card)
        val copySsid = Ui.iconButton(ctx, R.drawable.ic_copy, s(R.string.cd_copy_network)) {
            Links.hostInfo().ssid?.let { host.copyText(s(R.string.web_network), it) }
        }
        ssidRow = Ui.row(ctx, if (tvMode()) null else R.drawable.ic_wifi_direct, s(R.string.web_network), " ", copySsid)
        val copyPass = Ui.iconButton(ctx, R.drawable.ic_copy, s(R.string.cd_copy_password)) {
            Links.hostInfo().pass?.let { host.copyText(s(R.string.web_password), it) }
        }
        passRow = Ui.row(ctx, if (tvMode()) null else R.drawable.ic_shield, s(R.string.web_password), " ", copyPass)
        for (r in listOf(ssidRow, passRow)) {
            subOf(r)?.typeface = Theme.MONO
            if (tvMode()) {
                // The 220 dp column cannot afford the 48 dp gutter, the icon or a 64 dp minimum.
                r.setPadding(dp(4), dp(4), dp(0), dp(4)); (r as LinearLayout).minimumHeight = dp(56)
            }
            card.addView(r, lp())
        }
        card.visibility = View.GONE
        return card
    }

    /** The subtitle of a [Ui.row]; on TV the row has no icon, so the text column is child 0. */
    private fun subOf(row: View): TextView? {
        if (!tvMode()) return ScreenKit.subtitleOf(row)
        val col = (row as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return null
        return if (col.childCount > 1) col.getChildAt(1) as? TextView else null
    }

    private fun buildStep2(wide: Boolean): View {
        val tv = tvMode()
        val g = if (tv) 0 else Ui.gutter(ctx)
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(Ui.rule(ctx))
        card.addView(stepHeader(2, s(R.string.web_step2), s(R.string.web_step2_meta)))
        urlQrView = qrBox(card)
        urlText = Ui.text(ctx, s(R.string.web_starting), Type.ROW).apply {
            typeface = Theme.MONO
            gravity = Gravity.CENTER
            if (tv) { textSize = 14f; maxLines = 2 } else setTextIsSelectable(true)   // selectable text is a focus stop on TV
        }
        card.addView(urlText, lp(top = 4).apply { leftMargin = g; rightMargin = g })
        moreUrls = Ui.mono(ctx, "").apply { gravity = Gravity.CENTER; visibility = View.GONE; if (tv) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END } }
        card.addView(moreUrls, lp(top = 4).apply { leftMargin = g; rightMargin = g })
        copyBtn = Ui.jobButton(ctx, s(R.string.web_copy_link), Job.WEB, R.drawable.ic_copy) {
            WebShare.url()?.let { host.copyText(s(R.string.web_link), it) }
        }
        card.addView(copyBtn, lp(top = if (tv) 8 else 12).apply { leftMargin = g; rightMargin = g; bottomMargin = dp(8) })
        return card
    }

    private fun statCell(label: String): Pair<View, TextView> {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        val n = Ui.text(ctx, "0", Type.FIGURE).apply {
            gravity = Gravity.CENTER; maxLines = 1
            textSize = if (Ui.tv(ctx)) 20f else 20f
        }
        col.addView(n, LinearLayout.LayoutParams(-1, -2))
        col.addView(Ui.text(ctx, label, Type.META).apply {
            gravity = Gravity.CENTER
            // TV: the column is narrow, so a label wraps to 2 lines instead of ellipsizing.
            if (Ui.tv(ctx)) maxLines = 2 else { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        return col to n
    }

    private fun buildLive(): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.section(ctx, s(R.string.web_live)))
        val a = statCell(s(R.string.web_downloads)); val b = statCell(s(R.string.web_uploads))
        val c = statCell(s(R.string.web_sent)); val d = statCell(s(R.string.web_received))
        dlNum = a.second; ulNum = b.second; outNum = c.second; inNum = d.second
        val g = Ui.gutter(ctx)
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(g, 0, g, 0)
            // 1 px LINE cells, like a spec sheet
            background = GradientDrawable().apply { setColor(0); setStroke(Theme.rule(ctx), Theme.LINE) }
        }
        val cells = listOf(a, b, c, d)
        cells.forEachIndexed { i, cell ->
            if (i > 0) grid.addView(View(ctx).apply { setBackgroundColor(Theme.LINE) }, LinearLayout.LayoutParams(Theme.rule(ctx), -1))
            grid.addView(cell.first, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(grid, lp().apply { leftMargin = g; rightMargin = if (tvMode()) dp(8) else g; bottomMargin = dp(8) })
        events = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(events, lp())
        return box
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startWeb() {
        starting = true; failed = false
        val items = Hub.selection
        val app = includeApp
        val c = ctx
        refresh()
        exec.execute {
            val ok = try { WebShare.start(c, items, app) } catch (_: Exception) { false }
            Hub.main.post {
                starting = false
                failed = !ok
                if (!shown && host.current !== this) stopAll()
                refresh()
            }
        }
    }

    private fun startHosting() {
        if (shown) Links.startHosting(ctx, "web")
        refresh()
    }

    private fun stopAll() {
        exec.execute { try { WebShare.stop() } catch (_: Exception) { } }
        Links.stopHosting("web")
    }

    private fun stopAndLeave() {
        Hub.main.removeCallbacks(stopCheck)
        stopAll()
        host.pop()
    }

    override fun onShow() {
        shown = true
        Hub.main.removeCallbacks(stopCheck)
        if (!WebShare.running && !starting) startWeb()
        if (Links.hasPermissions(ctx)) startHosting() else host.ensureLinkPermissions { startHosting() }
        refresh()
    }

    override fun onHide() {
        shown = false
        // Popped or covered by another screen: stop. Only the activity going to the background: keep sharing.
        Hub.main.removeCallbacks(stopCheck)
        Hub.main.postDelayed(stopCheck, 350)
    }

    override val keepsScreenOn: Boolean get() = shown

    override fun firstFocus(): View? = if (::copyBtn.isInitialized) copyBtn else null

    // ---------------------------------------------------------------- refresh

    override fun refresh() {
        if (!::events.isInitialized) return
        refreshStatus()
        refreshStep1()
        refreshStep2(WebShare.running)
        refreshStats()
        if (tvMode()) linkPanes()
    }

    private fun refreshStatus() {
        val c = ctx
        val running = WebShare.running
        val files = Hub.selection.size
        val what = when {
            files == 0 && includeApp -> s(R.string.web_what_app)
            files == 0 -> s(R.string.web_what_none)
            includeApp -> s(R.string.web_what_files_app, files)
            else -> s(R.string.web_what_files, files)
        }
        val failedNow = failed && !running
        val title = when {
            starting -> s(R.string.web_starting)
            failedNow -> s(R.string.web_failed)
            else -> what
        }
        val meta = when {
            starting -> s(R.string.web_starting_meta)
            failedNow -> s(R.string.web_failed_meta, WebShare.PORT)
            files == 0 && !includeApp -> s(R.string.web_meta_upload_only)
            else -> s(R.string.web_meta)
        }
        val sig = "$title|$meta|$starting|$failedNow"
        if (sig == lastStatusSig) return
        lastStatusSig = sig

        statusBox.removeAllViews()
        // TV has no room for the counters next to the failure state's Try again button.
        liveBox?.visibility = if (Ui.tv(c) && failedNow) View.GONE else View.VISIBLE
        val g = Ui.gutter(c)
        val size = if (Ui.tv(c)) 56 else 48
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (Ui.tv(c)) setPadding(g, dp(4), g, dp(8)) else setPadding(g, dp(12), g, dp(16))
        }
        val plate = when {
            failedNow -> Ui.plate(c, R.drawable.ic_alert, Job.DANGER.fill, Job.DANGER.on, size)
            starting -> Ui.plate(c, R.drawable.ic_globe, Theme.SURFACE_2, Theme.INK, size)
            else -> Ui.plate(c, R.drawable.ic_globe, Job.WEB.fill, Job.WEB.on, size)
        }
        row.addView(plate, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(16) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(c, title, Type.HERO).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val metaRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!starting) metaRow.addView(Ui.led(c, if (failedNow) Led.DANGER else Led.OK),
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        metaRow.addView(Ui.text(c, meta, Type.META).apply { if (Ui.tv(c)) maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(metaRow, lp(top = 4))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        statusBox.addView(row, lp())
        if (failedNow) {
            statusBox.addView(Ui.jobButton(c, s(R.string.web_try_again), Job.WEB, R.drawable.ic_refresh) { startWeb() },
                lp().apply { leftMargin = g; rightMargin = g; bottomMargin = dp(16) })
        }
    }

    private fun refreshStep1() {
        val info = try { Links.hostInfo() } catch (_: Exception) { return }
        val wifi = info.wifiQr()
        step1.visibility = if (wifi != null) View.VISIBLE else View.GONE
        if (wifi != lastWifiQr) { lastWifiQr = wifi; wifiQrView.setText(wifi) }
        subOf(ssidRow)?.text = info.ssid ?: " "
        subOf(passRow)?.text = info.pass ?: " "
        passRow.visibility = if (info.pass.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshStep2(running: Boolean) {
        val urls = if (running) WebShare.urls() else emptyList()
        val sig = urls.joinToString(",")
        if (sig != lastUrlsSig) {
            lastUrlsSig = sig
            val first = urls.firstOrNull()
            if (first != lastUrlQr) { lastUrlQr = first; urlQrView.setText(first) }
            urlText.text = first ?: if (starting) s(R.string.web_starting) else s(R.string.web_no_address)
            val rest = urls.drop(1).take(3).map { it.removePrefix("http://") }
            moreUrls.text = if (rest.isEmpty()) "" else s(R.string.web_also, rest.joinToString("  ·  "))
            moreUrls.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
            copyBtn.isEnabled = first != null
        }
    }

    private fun refreshStats() {
        val dl = WebShare.downloads.toLong()
        val ul = WebShare.uploads.toLong()
        if (dl != lastDl) { dlNum.text = dl.toString(); lastDl = dl }
        if (ul != lastUl) { ulNum.text = ul.toString(); lastUl = ul }
        outNum.text = Io.bytes(WebShare.bytesOut)
        inNum.text = Io.bytes(WebShare.bytesIn)

        val evs = WebShare.events.take(if (tvMode()) 1 else 6)
        val sig = evs.joinToString("|") { it.at.toString() + it.text }
        val now = System.currentTimeMillis()
        // Rebuild when the list changes, or every 5 s so "5 s ago" stays fresh.
        val stamp = sig + "#" + (evs.firstOrNull()?.let { (now - it.at) / 5000 } ?: 0)
        if (stamp == lastEventSig) return
        lastEventSig = stamp
        events.removeAllViews()
        if (evs.isEmpty()) {
            val g = Ui.gutter(ctx)
            events.addView(Ui.text(ctx, s(R.string.web_waiting), Type.META).apply { setPadding(g, dp(8), g, dp(8)) })
            return
        }
        if (tvMode()) {
            // One compact line: the latest connection, so the left pane stays inside the safe area.
            val g = Ui.gutter(ctx)
            val e = evs[0]
            events.addView(Ui.text(ctx, e.text + "  ·  " + ago(now - e.at), Type.META).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(g, dp(8), g, dp(8))
            })
            return
        }
        for (e in evs) {
            val icon = when (e.kind) { 1 -> R.drawable.ic_send; 2 -> R.drawable.ic_receive; else -> R.drawable.ic_globe }
            events.addView(Ui.row(ctx, icon, e.text, ago(now - e.at)), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun ago(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 5 -> s(R.string.web_just_now)
            sec < 60 -> s(R.string.web_s_ago, sec)
            sec < 3600 -> s(R.string.web_min_ago, sec / 60)
            else -> s(R.string.web_h_ago, sec / 3600)
        }
    }
}
