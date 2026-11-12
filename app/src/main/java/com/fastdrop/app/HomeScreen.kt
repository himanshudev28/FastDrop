package com.fastdrop.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Home: who we are, big Send / Receive cards, quick pickers, devices found on the same Wi-Fi
 * (automatic, no QR) and recently received files (with Install for apps).
 */
class HomeScreen(host: MainActivity) : Screen(host) {

    private var sendCard: View? = null
    private lateinit var bannerBox: LinearLayout
    private lateinit var deviceBox: LinearLayout
    private lateinit var nearbyBox: LinearLayout
    private lateinit var recentBox: LinearLayout

    private var sigBanner: String? = null
    private var sigDevice: String? = null
    private var sigNearby: String? = null
    private var sigRecent: String? = null
    private val seenNearby = HashSet<String>()
    private val seenRecent = HashSet<String>()
    private var built = false

    private var netCache: String? = null
    private var netAt = 0L
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun dp(v: Int) = Theme.dp(ctx, v)

    private fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
        }

    // ---------------------------------------------------------------- build

    override fun build(): View {
        val c = ctx
        val wide = Ui.wide(c)
        built = false
        sigBanner = null; sigDevice = null; sigNearby = null; sigRecent = null
        seenNearby.clear(); seenRecent.clear()

        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val blocks = ArrayList<View>()
        fun add(parent: LinearLayout, v: View, bottom: Int = 16) {
            parent.addView(v, lp(bottom = bottom))
            blocks.add(v)
        }

        add(content, topBar(), 4)
        bannerBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        content.addView(bannerBox, lp())
        deviceBox = Ui.card(c, 6)
        nearbyBox = Ui.card(c, 6)
        recentBox = Ui.card(c, 6)

        val left: LinearLayout
        val right: LinearLayout
        if (wide) {
            val cols = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            cols.addView(left, lp(0, -2, 1f, end = 12))
            cols.addView(right, lp(0, -2, 1f, start = 12))
            content.addView(cols, lp())
        } else {
            left = content
            right = content
        }

        add(left, deviceBox)
        add(left, actionCards())
        add(left, Ui.label(c, "Quick send"), 8)
        add(left, quickTiles())
        add(left, linkRows())

        add(right, Ui.label(c, "Nearby on this Wi-Fi"), 8)
        add(right, nearbyBox)
        add(right, Ui.label(c, "Recent"), 8)
        add(right, recentBox, 8)

        val page = Ui.scrollPage(c, content)
        Anim.stagger(blocks, 40, 45)
        refresh()
        built = true
        return page
    }

    private fun topBar(): View {
        val c = ctx
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val logo = android.widget.FrameLayout(c).apply {
            background = Ui.gradient(Theme.BRAND, Theme.dp(c, 11).toFloat())
        }
        logo.addView(Ui.icon(c, R.drawable.ic_bolt, android.graphics.Color.WHITE, 20),
            android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        bar.addView(logo, lp(dp(38), dp(38), end = 12))
        bar.addView(Ui.title(c, "FastDrop", 24f), lp(0, -2, 1f))
        bar.addView(Ui.iconButton(c, R.drawable.ic_settings, "Settings") { host.showSettings() })
        return bar
    }

    private fun actionCards(): View {
        val c = ctx
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val send = Ui.actionCard(c, "Send", "Photos, apps, files", R.drawable.ic_send, Theme.SEND) { host.startSend() }
        val recv = Ui.actionCard(c, "Receive", "Show QR, be visible", R.drawable.ic_receive, Theme.RECEIVE) {
            host.push(ReceiveScreen(host))
        }
        sendCard = send
        row.addView(send, lp(0, -2, 1f, end = 6))
        row.addView(recv, lp(0, -2, 1f, start = 6))
        return row
    }

    private fun quickTiles(): View {
        val c = ctx
        class T(val label: String, val icon: Int, val tint: Int, val tab: String)
        val items = listOf(
            T("Apps", R.drawable.ic_apps, Theme.CAT_APPS, PickerActivity.TAB_APPS),
            T("Photos", R.drawable.ic_photo, Theme.CAT_PHOTOS, PickerActivity.TAB_PHOTOS),
            T("Videos", R.drawable.ic_video, Theme.CAT_VIDEOS, PickerActivity.TAB_VIDEOS),
            T("Music", R.drawable.ic_music, Theme.CAT_MUSIC, PickerActivity.TAB_MUSIC),
            T("Files", R.drawable.ic_file, Theme.CAT_FILES, PickerActivity.TAB_FILES),
        )
        // Fill the width when it fits, otherwise scroll sideways.
        val gutter = if (Ui.tv(c)) Theme.TV_GUTTER else Theme.GUTTER
        val avail = c.resources.configuration.screenWidthDp / (if (Ui.wide(c)) 2 else 1) - gutter * 2
        val fits = avail >= items.size * 76 + (items.size - 1) * 8
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, t ->
            val tile = Ui.tile(c, t.label, t.icon, t.tint) { host.openPicker(t.tab) }
            row.addView(tile, if (fits) lp(0, -2, 1f, start = if (i > 0) 8 else 0) else lp(dp(76), -2, 0f, start = if (i > 0) 8 else 0))
        }
        val hsv = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            setPadding(0, dp(6), 0, dp(8))
            addView(row, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        return hsv
    }

    private fun chevron(): View = Ui.icon(ctx, R.drawable.ic_chevron, Theme.MUTED, 18)

    private fun linkRows(): View {
        val c = ctx
        val card = Ui.card(c, 6)
        var first = true
        fun addRow(v: View) {
            if (!first) card.addView(Ui.divider(c))
            first = false
            card.addView(v, lp())
        }
        if (QrScanActivity.hasCamera(c)) {
            addRow(Ui.row(c, R.drawable.ic_scan, Theme.ACCENT, "Scan QR code",
                "Join a device that shows its code", chevron()) { host.scanQr(true) })
        }
        addRow(Ui.row(c, R.drawable.ic_globe, Theme.WEB_A, "Share with any device",
            "iPhone, PC — no app needed", chevron()) { host.showWebShare(false) })
        return card
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onShow() {
        // Ask the network who is around right now instead of waiting for the next announce.
        try { Discovery.nudge() } catch (_: Exception) { }
        watchNetwork()
        netAt = 0L
        refresh()
    }

    override fun onHide() {
        unwatchNetwork()
    }

    /** Wi-Fi connects / disconnects must update the network line and the Nearby empty state. */
    private fun watchNetwork() {
        if (netCb != null) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private fun poke() {
                    Hub.main.postDelayed({ netAt = 0L; Hub.changed() }, 400)
                }
                override fun onAvailable(network: Network) = poke()
                override fun onLost(network: Network) = poke()
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = poke()
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCb = cb
        } catch (_: Exception) { }
    }

    private fun unwatchNetwork() {
        val cb = netCb ?: return
        netCb = null
        try {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
    }

    override fun firstFocus(): View? = sendCard

    override fun refresh() {
        if (!::bannerBox.isInitialized) return
        renderBanner()
        renderDevice()
        renderNearby()
        renderRecent()
    }

    // ---------------------------------------------------------------- helpers

    /** Puts [lead] in front of a row's text (rows built without an icon). */
    private fun withLead(row: LinearLayout, lead: View, sizeDp: Int): LinearLayout {
        row.addView(lead, 0, lp(dp(sizeDp), dp(sizeDp), end = 14))
        return row
    }

    private fun netLine(): String {
        val now = SystemClock.elapsedRealtime()
        val cached = netCache
        if (cached != null && now - netAt < 3000) return cached
        val line = try {
            val a = Net.localAddrs().firstOrNull()
            if (a == null) {
                if (Links.p2pSupported(ctx)) "No Wi-Fi — use Wi-Fi Direct" else "Not connected to Wi-Fi"
            } else {
                val n = a.iface.name.lowercase()
                val kind = when {
                    n.startsWith("wlan") -> "Wi-Fi"
                    n.startsWith("eth") -> "Ethernet"
                    n.startsWith("p2p") -> "Wi-Fi Direct"
                    else -> "Network"
                }
                "$kind · ${a.ip.hostAddress ?: ""}"
            }
        } catch (_: Exception) { "Network unavailable" }
        netCache = line; netAt = now
        return line
    }

    // ---------------------------------------------------------------- active transfer banner

    private fun renderBanner() {
        val t = Hub.transfer
        val pending = Hub.pendingSendTo
        val running = t?.running == true
        val sig = when {
            running -> "run:${System.identityHashCode(t)}"
            pending != null -> "pend:$pending"
            else -> ""
        }
        if (sig == sigBanner) return
        sigBanner = sig
        bannerBox.removeAllViews()
        if (sig.isEmpty()) return
        val c = ctx
        val title = when {
            running && t != null -> (if (t.outgoing) "Sending to " else "Receiving from ") + t.peer
            else -> "Connecting to $pending…"
        }
        val card = Ui.card(c, 4)
        card.addView(Ui.row(c, R.drawable.ic_speed, Theme.ACCENT_2, title, "Tap to see progress",
            Ui.chip(c, "View", Theme.ACCENT_2)) { host.showTransfer() }, lp())
        bannerBox.addView(card, lp(bottom = 16))
        if (built) Anim.enter(card)
    }

    // ---------------------------------------------------------------- this device

    private fun renderDevice() {
        val err = Server.error
        val status = err ?: ("Ready to receive" + if (Hub.autoAccept) " · auto-accept on" else "")
        val net = netLine()
        val sig = "${Hub.myName}|${Hub.myType}|$status|$net"
        if (sig == sigDevice) return
        sigDevice = sig

        val c = ctx
        val chip = Ui.chip(c, if (err == null) "Ready" else "Offline", if (err == null) Theme.SUCCESS else Theme.WARNING)
        val row = Ui.row(c, null, Theme.ACCENT, Hub.myName, "$status\n$net", chip) { host.showSettings() }
        withLead(row, Ui.avatar(c, Hub.myName, Hub.myType, 46, Theme.BRAND), 46)
        deviceBox.removeAllViews()
        deviceBox.addView(row, lp())
    }

    // ---------------------------------------------------------------- nearby (same Wi-Fi, automatic)

    private fun renderNearby() {
        val list = Hub.devices.values.filter { it.via == Device.VIA_LAN }.sortedBy { it.name.lowercase() }.take(8)
        val sel = Hub.selection
        val wifiOn = try { Links.wifiEnabled(ctx) } catch (_: Exception) { true }
        val online = try { Net.localAddrs().isNotEmpty() } catch (_: Exception) { true }
        val sig = sel.size.toString() + "|$wifiOn|$online|" + list.joinToString("|") { "${it.id},${it.name},${it.type}" }
        if (sig == sigNearby) return
        sigNearby = sig

        val c = ctx
        val focusId = nearbyBox.findFocus()?.tag
        nearbyBox.removeAllViews()
        if (list.isEmpty()) {
            val line = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            // Our own hotspot or Ethernet is a network too, even with Wi‑Fi switched off.
            val searching = online
            val dot = Ui.dot(c, if (searching) Theme.ACCENT_2 else Theme.WARNING, 10)
            line.addView(dot, lp(dp(10), dp(10), end = 14))
            val msg = when {
                online -> "Looking for devices… Open FastDrop on the other device. " +
                    "Devices on the same Wi-Fi or hotspot appear here by themselves."
                !wifiOn -> "Wi-Fi is off. Turn it on to find devices, or use Receive for Wi-Fi Direct."
                else -> "Not connected to Wi-Fi. Join the same Wi-Fi as the other device and it appears here."
            }
            line.addView(Ui.text(c, msg, 14f, Theme.MUTED), lp(0, -2, 1f))
            nearbyBox.addView(line, lp())
            if (searching) Anim.pulse(dot)
            return
        }
        val sub = if (sel.isEmpty()) "Tap to choose files and send"
        else "Tap to send ${sel.size} item${if (sel.size == 1) "" else "s"}"
        var newIdx = 0
        list.forEachIndexed { i, d ->
            if (i > 0) nearbyBox.addView(Ui.divider(c))
            val row = Ui.row(c, null, Theme.ACCENT, d.name, sub,
                Ui.icon(c, R.drawable.ic_send, Theme.ACCENT_2, 20)) { host.sendTo(d) }
            withLead(row, Ui.avatar(c, d.name, d.type, 42, if (d.type == "tv") Theme.WEB else Theme.SEND), 42)
            row.tag = d.id
            nearbyBox.addView(row, lp())
            if (seenNearby.add(d.id) && built) Anim.enter(row, (newIdx++ * 45).toLong())
            if (d.id == focusId) row.requestFocus()
        }
    }

    // ---------------------------------------------------------------- recent

    private class Entry(val key: String, val group: List<Received>?, val file: Received?)

    private fun thumb(): ImageView {
        val c = ctx
        val r = Theme.dp(c, 12).toFloat()
        return ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Theme.SURFACE_3); cornerRadius = r }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
        }
    }

    private fun ago(at: Long): String =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    private fun renderRecent() {
        val all = Hub.received.take(30)
        val groups = AppInstaller.groups(all)
        val groupOf = HashMap<Received, List<Received>>()
        for (g in groups) for (r in g) groupOf[r] = g
        val entries = ArrayList<Entry>()
        val emitted = HashSet<List<Received>>()
        for (r in all) {
            val g = groupOf[r]
            if (g != null) {
                if (emitted.add(g)) entries.add(Entry("g${System.identityHashCode(g[0])}", g, null))
            } else entries.add(Entry("f${System.identityHashCode(r)}", null, r))
            if (entries.size >= 8) break
        }
        val minute = System.currentTimeMillis() / 60000
        val sig = minute.toString() + entries.joinToString("|") {
            it.key + (it.file?.uri ?: it.group?.firstOrNull()?.uri)
        }
        if (sig == sigRecent) return
        sigRecent = sig

        val c = ctx
        val focusKey = recentBox.findFocus()?.tag
        recentBox.removeAllViews()
        if (entries.isEmpty()) {
            recentBox.addView(Ui.text(c, "Nothing received yet. Files land in Download/FastDrop.", 14f, Theme.MUTED)
                .apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }, lp())
            return
        }
        var newIdx = 0
        entries.forEachIndexed { i, e ->
            if (i > 0) recentBox.addView(Ui.divider(c))
            val row: LinearLayout
            if (e.group != null) {
                val first = e.group[0]
                val tag = e.group.firstNotNullOfOrNull { it.app }
                val label = tag?.label ?: first.name.substringAfterLast('/').removeSuffix(".apk")
                val size = e.group.sumOf { it.size.coerceAtLeast(0L) }
                val ver = tag?.version?.takeIf { it.isNotEmpty() }?.let { "v$it · " } ?: ""
                row = Ui.row(c, null, Theme.CAT_APPS, label,
                    "$ver${Io.bytes(size)} · from ${first.from} · ${ago(first.at)}",
                    Ui.chip(c, "Install", Theme.CAT_APPS, R.drawable.ic_install)) { host.installApp(e.group) }
                val iv = thumb()
                val pkg = tag?.pkg
                if (pkg != null) Thumbs.app(iv, pkg) else Thumbs.file(iv, "app.apk", null, dp(48))
                withLead(row, iv, 44)
            } else {
                val r = e.file!!
                row = Ui.row(c, null, Theme.ACCENT, r.name.substringAfterLast('/'),
                    "${Io.bytes(r.size)} · from ${r.from} · ${ago(r.at)}", null) { host.openReceived(r) }
                val iv = thumb()
                Thumbs.file(iv, r.name, r.uri, dp(96))
                withLead(row, iv, 44)
            }
            row.tag = e.key
            recentBox.addView(row, lp())
            if (seenRecent.add(e.key) && built) Anim.enter(row, (newIdx++ * 45).toLong())
            if (e.key == focusKey) row.requestFocus()
        }
    }
}
