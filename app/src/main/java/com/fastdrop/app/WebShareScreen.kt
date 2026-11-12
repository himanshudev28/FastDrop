package com.fastdrop.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * "Share with any device": runs the built-in web server so an iPhone, a PC or a phone without
 * FastDrop can download the selected files (and the FastDrop app itself) and upload files back.
 * Step 1 joins our Wi-Fi (only when we host one), step 2 opens the page.
 */
class WebShareScreen(host: MainActivity, private val includeApp: Boolean) : Screen(host) {
    private companion object {
        // Start and stop run one after another so they can never overlap.
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "web-ctl").apply { isDaemon = true } }
    }

    private var shown = false
    private var entered = false
    private var starting = false
    private var failed = false
    private var lastWifiQr: String? = null
    private var lastUrlQr: String? = null
    private var lastEventSig = ""
    private var lastDl = 0L
    private var lastUl = 0L
    private var lastUrlsSig = ""

    private lateinit var introTitle: TextView
    private lateinit var introSub: TextView
    private lateinit var errorCard: View
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
    private var sections: List<View> = emptyList()

    private val stopCheck = Runnable { if (host.current !== this) stopAll() }

    private fun lp(w: Int = ScreenKit.MATCH, h: Int = ScreenKit.WRAP, bottomDp: Int = 14, weight: Float = 0f) =
        ScreenKit.lp(ctx, w, h, bottomDp, weight)

    override val wantsTick: Boolean get() = true

    override fun build(): View {
        entered = false; lastWifiQr = null; lastUrlQr = null; lastEventSig = ""; lastDl = 0; lastUl = 0; lastUrlsSig = ""
        val wide = Ui.wide(ctx)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Theme.BG)

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.header(ctx, "Share with any device", { stopAndLeave() }), lp(bottomDp = 6))

        val intro = buildIntro()
        errorCard = buildError()
        step1 = buildStep1()
        step2 = buildStep2()
        val stats = buildStats()
        val stop = Ui.secondaryButton(ctx, "Stop sharing", R.drawable.ic_close) { stopAndLeave() }

        if (wide) {
            val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = ScreenKit.dp(ctx, 24)
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            left.addView(intro, lp()); left.addView(errorCard, lp()); left.addView(step1, lp()); left.addView(step2, lp())
            right.addView(stats, lp()); right.addView(stop, lp(bottomDp = 0))
            cols.addView(left, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f).apply { marginEnd = gap / 2 })
            cols.addView(right, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f).apply { marginStart = gap / 2 })
            content.addView(cols, lp())
            sections = listOf(intro, step1, step2, stats)
        } else {
            content.addView(intro, lp()); content.addView(errorCard, lp())
            content.addView(step1, lp()); content.addView(step2, lp())
            content.addView(stats, lp()); content.addView(stop, lp())
            sections = listOf(intro, step1, step2, stats, stop)
        }
        root.addView(Ui.scrollPage(ctx, content, gutter = true), FrameLayout.LayoutParams(-1, -1))
        refresh()
        return root
    }

    // ---------------------------------------------------------------- pieces

    private fun buildIntro(): View {
        val card = Ui.column(ctx)
        introTitle = Ui.text(ctx, "Starting…", 18f, Theme.TEXT, Theme.BOLD)
        introSub = Ui.text(ctx, "", 13f, Theme.MUTED).apply { setPadding(0, ScreenKit.dp(ctx, 4), 0, 0) }
        card.addView(introTitle); card.addView(introSub)
        return card
    }

    private fun buildError(): View {
        val card = Ui.column(ctx)
        card.addView(Ui.text(ctx, "Couldn't start sharing", 17f, Theme.DANGER, Theme.BOLD))
        card.addView(Ui.text(ctx, "Port ${WebShare.PORT} is busy. Close other sharing apps and try again.", 13f, Theme.MUTED)
            .apply { setPadding(0, ScreenKit.dp(ctx, 4), 0, ScreenKit.dp(ctx, 12)) })
        card.addView(Ui.jobButton(ctx, "Try again", Job.WEB, R.drawable.ic_refresh) { startWeb() })
        card.visibility = View.GONE
        return card
    }

    private fun badge(n: Int): View = TextView(ctx).apply {
        text = n.toString()
        textSize = 14f
        typeface = Theme.BOLD
        setTextColor(Theme.ON_DARK)
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply { setColor(Theme.C_WEB); cornerRadius = Theme.dp(ctx, 6).toFloat() }
    }

    private fun stepHeader(n: Int, title: String, sub: String): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val s = ScreenKit.dp(ctx, 30)
        row.addView(badge(n), LinearLayout.LayoutParams(s, s).apply { marginEnd = ScreenKit.dp(ctx, 12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, title, 17f, Theme.TEXT, Theme.BOLD))
        col.addView(Ui.text(ctx, sub, 13f, Theme.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))
        return row
    }

    private fun qrBox(card: LinearLayout): QrView {
        val q = QrView(ctx)
        val size = ScreenKit.dp(ctx, 220)
        card.addView(q, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ScreenKit.dp(ctx, 16); bottomMargin = ScreenKit.dp(ctx, 8)
        })
        return q
    }

    private fun buildStep1(): View {
        val card = Ui.column(ctx)
        card.addView(stepHeader(1, "Join this Wi‑Fi", "Scan with the other device's camera. Skip if it is already on your Wi‑Fi."))
        wifiQrView = qrBox(card)
        val copySsid = Ui.iconButton(ctx, R.drawable.ic_copy, "Copy network name") {
            Links.hostInfo().ssid?.let { host.copyText("Network", it) }
        }
        ssidRow = Ui.row(ctx, R.drawable.ic_wifi_direct, "Network", " ", copySsid, null)
        val copyPass = Ui.iconButton(ctx, R.drawable.ic_copy, "Copy password") {
            Links.hostInfo().pass?.let { host.copyText("Password", it) }
        }
        passRow = Ui.row(ctx, R.drawable.ic_shield, "Password", " ", copyPass, null)
        card.addView(ssidRow, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        card.addView(passRow, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        card.visibility = View.GONE
        return card
    }

    private fun buildStep2(): View {
        val card = Ui.column(ctx)
        card.addView(stepHeader(2, "Open in browser", "Scan this, or type the address."))
        urlQrView = qrBox(card)
        urlText = Ui.text(ctx, "Starting…", 16f, Theme.TEXT, Theme.BOLD).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }
        card.addView(urlText, lp(bottomDp = 4))
        moreUrls = Ui.text(ctx, "", 12f, Theme.MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        card.addView(moreUrls, lp(bottomDp = 10))
        copyBtn = Ui.secondaryButton(ctx, "Copy link", R.drawable.ic_copy) {
            WebShare.url()?.let { host.copyText("Link", it) }
        }
        card.addView(copyBtn, lp(bottomDp = 0))
        return card
    }

    private fun statCell(label: String): Pair<View, TextView> {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val n = Ui.text(ctx, "0", 22f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.CENTER }
        col.addView(n)
        col.addView(Ui.text(ctx, label, 12f, Theme.MUTED).apply { gravity = Gravity.CENTER })
        return col to n
    }

    private fun buildStats(): View {
        val card = Ui.column(ctx)
        card.addView(Ui.section(ctx, "Live"), lp(bottomDp = 10))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val a = statCell("Downloads"); val b = statCell("Uploads"); val c = statCell("Sent"); val d = statCell("Received")
        dlNum = a.second; ulNum = b.second; outNum = c.second; inNum = d.second
        for (cell in listOf(a, b, c, d)) row.addView(cell.first, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))
        card.addView(row, lp(bottomDp = 12))
        events = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(events, lp(bottomDp = 0))
        return card
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
        if (!entered) {
            entered = true
        }
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
        val running = WebShare.running
        val files = Hub.selection.size
        val what = when {
            files == 0 && includeApp -> "Sharing the FastDrop app"
            files == 0 -> "Nothing selected"
            includeApp -> "Sharing ${if (files == 1) "1 file" else "$files files"} + the FastDrop app"
            else -> "Sharing ${if (files == 1) "1 file" else "$files files"}"
        }
        ScreenKit.fadeText(introTitle, if (starting) "Starting…" else if (running) what else if (failed) "Not sharing" else what)
        ScreenKit.fadeText(introSub,
            if (files == 0 && !includeApp) "People can still send files to this device from their browser."
            else "No app needed on the other device. It stays offline.")
        ScreenKit.setShown(errorCard, failed && !running)

        refreshStep1()
        refreshStep2(running)
        refreshStats()
    }

    private fun refreshStep1() {
        val info = try { Links.hostInfo() } catch (_: Exception) { return }
        val wifi = info.wifiQr()
        ScreenKit.setShown(step1, wifi != null)
        if (wifi != lastWifiQr) { lastWifiQr = wifi; wifiQrView.setText(wifi) }
        ScreenKit.subtitleOf(ssidRow)?.text = info.ssid ?: " "
        ScreenKit.subtitleOf(passRow)?.text = info.pass ?: " "
        passRow.visibility = if (info.pass.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshStep2(running: Boolean) {
        val urls = if (running) WebShare.urls() else emptyList()
        val sig = urls.joinToString(",")
        if (sig != lastUrlsSig) {
            lastUrlsSig = sig
            val first = urls.firstOrNull()
            if (first != lastUrlQr) { lastUrlQr = first; urlQrView.setText(first) }
            urlText.text = first ?: if (starting) "Starting…" else "Connect to Wi‑Fi to get an address"
            val rest = urls.drop(1).take(3).map { it.removePrefix("http://") }
            moreUrls.text = if (rest.isEmpty()) "" else "Also: " + rest.joinToString("  ·  ")
            moreUrls.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
            copyBtn.isEnabled = first != null
            copyBtn.alpha = if (first != null) 1f else 0.5f
        }
    }

    private fun refreshStats() {
        val dl = WebShare.downloads.toLong()
        val ul = WebShare.uploads.toLong()
        if (dl != lastDl) { dlNum.text = dl.toString(); lastDl = dl }
        if (ul != lastUl) { ulNum.text = ul.toString(); lastUl = ul }
        outNum.text = Io.bytes(WebShare.bytesOut)
        inNum.text = Io.bytes(WebShare.bytesIn)

        val evs = WebShare.events.take(6)
        val sig = evs.joinToString("|") { it.at.toString() + it.text }
        val now = System.currentTimeMillis()
        // Rebuild when the list changes, or each tick so "5 s ago" stays fresh.
        val stamp = sig + "#" + (evs.firstOrNull()?.let { (now - it.at) / 5000 } ?: 0)
        if (stamp == lastEventSig) return
        val grew = sig != lastEventSig.substringBefore('#')
        lastEventSig = stamp
        events.removeAllViews()
        if (evs.isEmpty()) {
            events.addView(Ui.text(ctx, "Waiting for someone to connect…", 13f, Theme.MUTED))
            return
        }
        for ((i, e) in evs.withIndex()) {
            val icon = when (e.kind) { 1 -> R.drawable.ic_send; 2 -> R.drawable.ic_receive; else -> R.drawable.ic_globe }
            val color = when (e.kind) { 1 -> Theme.SEND_B; 2 -> Theme.RECV_A; else -> Theme.MUTED }
            val r = Ui.row(ctx, icon, e.text, ago(now - e.at), null, null)
            events.addView(r, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
            if (i == 0 && grew) Anim.enter(r)
        }
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }
}
