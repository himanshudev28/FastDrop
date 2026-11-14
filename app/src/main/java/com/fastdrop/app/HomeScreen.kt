package com.fastdrop.app

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout

/**
 * Home (docs/DESIGN.md 4.1), device-first: who we are and whether we are reachable, the devices
 * nearby as numbered ports, two other ways, the last three transfers, and a docked
 * [Receive][Send] bar that shows the colour code on the first screen.
 */
class HomeScreen(host: MainActivity) : Screen(host) {

    private var sendBtn: View? = null
    private lateinit var heroBox: LinearLayout
    private lateinit var nearbyBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var strip: TransferStrip

    private var sigHero: String? = null
    private var sigNearby: String? = null
    private var sigRecent: String? = null
    private var sigStrip: String? = null
    private val seenNearby = HashSet<String>()
    private var built = false
    private var firstPort: View? = null

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var netName: String = ""
    private var netAt = 0L

    private fun dp(v: Int) = Theme.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    private fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    override val wantsTick: Boolean get() = Hub.transfer?.running == true

    // ---------------------------------------------------------------- build

    override fun build(): View {
        val c = ctx
        if (Ui.tv(c)) return buildTv()
        val wide = Ui.wide(c)
        built = false
        sigHero = null; sigNearby = null; sigRecent = null; sigStrip = null
        seenNearby.clear()

        heroBox = Ui.column(c)
        nearbyBox = Ui.column(c)
        recentBox = Ui.column(c)

        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        content.addView(header(), lp())
        content.addView(heroBox, lp())

        val nearby = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val scan = if (QrScanActivity.hasCamera(c)) Ui.quietButton(c, s(R.string.home_scan)) { host.scanQr(true) } else null
        nearby.addView(Ui.section(c, s(R.string.home_nearby), scan), lp())
        nearby.addView(nearbyBox, lp())

        // Quick send: solid category tiles, each opens the picker on its tab.
        val quick = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        quick.addView(Ui.section(c, s(R.string.home_quick_send)), lp())
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val g = Ui.gutter(c); setPadding(g, 0, g, 0) }
        val tiles = quickTiles()
        val rowL = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEachIndexed { i, t ->
            rowL.addView(tile(t.job, t.icon, s(t.label), t.go),
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        grid.addView(rowL, lp())
        quick.addView(grid, lp())

        val other = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        other.addView(Ui.section(c, s(R.string.home_other_ways)), lp())
        if (QrScanActivity.hasCamera(c)) {
            val ink = Theme.plateFor("files")
            other.addView(Ui.row(c, null, s(R.string.home_scan_code), s(R.string.home_scan_code_sub), Ui.chevron(c),
                lead = Ui.plate(c, R.drawable.ic_scan, ink.fill, ink.on, 40)) { host.scanQr(true) }, lp())
        }
        val web = Theme.plateFor("browser")
        other.addView(Ui.row(c, null, s(R.string.home_share_any), s(R.string.home_share_any_sub), Ui.chevron(c, Job.WEB),
            lead = Ui.plate(c, R.drawable.ic_globe, web.fill, web.on, 40, Job.WEB), job = Job.WEB) { host.showWebShare(false) }, lp())

        val recent = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.quietButton(c, s(R.string.home_clear)) { confirmClear() })
            addView(Ui.quietButton(c, s(R.string.home_see_all)) { host.openHistory() })
        }
        recent.addView(Ui.section(c, s(R.string.home_recent), actions), lp())
        recent.addView(recentBox, lp())

        if (wide) {
            val cols = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            left.addView(quick, lp(top = 8)); left.addView(other, lp(top = 24))
            right.addView(nearby, lp(top = 8)); right.addView(recent, lp(top = 24))
            cols.addView(left, lp(0, -2, 1f)); cols.addView(right, lp(0, -2, 1f))
            content.addView(cols, lp())
        } else {
            content.addView(quick, lp())
            content.addView(nearby, lp(top = 24))
            content.addView(other, lp(top = 24))
            content.addView(recent, lp(top = 24))
        }

        val page = Ui.scrollPage(c, content)

        strip = TransferStrip(c) { host.showTransfer() }.apply { visibility = View.GONE }

        val dock = Ui.dock(c)
        val receive = Ui.jobButton(c, s(R.string.home_receive), Job.RECEIVE) { host.push(ReceiveScreen(host)) }
        val send = Ui.jobButton(c, s(R.string.home_send), Job.SEND) { host.startSend() }
        sendBtn = send
        dock.addView(receive, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        dock.addView(send, LinearLayout.LayoutParams(0, -2, 2f))

        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(strip, LinearLayout.LayoutParams(-1, -2))
        root.addView(dock, LinearLayout.LayoutParams(-1, -2))
        page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, 0)
        Ui.applyInsets(page, true, false)
        refresh()
        built = true
        return root
    }


    private class T(val label: Int, val icon: Int, val job: Job?, val go: () -> Unit)

    private fun quickTiles(): List<T> {
        val c = ctx
        return listOf(
            T(R.string.tile_apps, R.drawable.ic_apps, Job.OK) { host.openPicker(PickerActivity.TAB_APPS) },
            T(R.string.tile_photos, R.drawable.ic_photo, Job.WEB) { host.openPicker(PickerActivity.TAB_PHOTOS) },
            T(R.string.tile_videos, R.drawable.ic_video, Job.RECEIVE) { host.openPicker(PickerActivity.TAB_VIDEOS) },
            T(R.string.tile_music, R.drawable.ic_music, Job.TEXT) { host.openPicker(PickerActivity.TAB_MUSIC) },
            T(R.string.tile_files, R.drawable.ic_file, null) { host.openPicker(PickerActivity.TAB_FILES) },
            T(R.string.tile_text, R.drawable.ic_edit, Job.TEXT) {
                // The picker's Text tab is phone-only; TV gets the themed input sheet.
                if (Ui.tv(c)) host.inputDialog(s(R.string.tile_text_title), "", android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) { host.setSendText(it) }
                else host.openPicker(PickerActivity.TAB_TEXT)
            },
        )
    }

    // ---------------------------------------------------------------- TV two-pane (docs 5.1, 5.4, 5.5)

    private var leftBox: LinearLayout? = null
    private var rightBox: LinearLayout? = null
    private var lastLeft: View? = null
    private var lastRight: View? = null
    private val portViews = ArrayList<View>()
    private var tvHint: View? = null

    private fun ensureId(v: View): Int { if (v.id == View.NO_ID) v.id = View.generateViewId(); return v.id }

    /** Explicit pane links: right from the left pane lands on the last-focused right item (or the first port), left comes back. */
    private fun linkPanes() {
        val l = leftBox ?: return
        val r = rightBox ?: return
        val lf = ArrayList<View>(); l.addFocusables(lf, View.FOCUS_RIGHT)
        val rf = ArrayList<View>(); r.addFocusables(rf, View.FOCUS_LEFT)
        val toRight = (lastRight?.takeIf { it.isAttachedToWindow && it.isFocusable } ?: rf.firstOrNull())
        val toLeft = (lastLeft?.takeIf { it.isAttachedToWindow && it.isFocusable } ?: sendBtn?.takeIf { it.isAttachedToWindow } ?: lf.firstOrNull())
        val rid = toRight?.let { ensureId(it) } ?: View.NO_ID
        val lid = toLeft?.let { ensureId(it) } ?: View.NO_ID
        for (v in lf) v.nextFocusRightId = rid
        for (v in rf) v.nextFocusLeftId = lid
        // Vertical moves stay inside a pane: the ends of each pane point at themselves.
        val loc = IntArray(2)
        for (list in listOf(lf, rf)) {
            if (list.isEmpty()) continue
            val ys = list.map { loc.also { _ -> it.getLocationOnScreen(loc) }[1] }
            val minY = ys.min(); val maxY = ys.max()
            list.forEachIndexed { i, v ->
                v.nextFocusUpId = if (ys[i] <= minY + dp(8)) ensureId(v) else View.NO_ID
                v.nextFocusDownId = if (ys[i] >= maxY - dp(8)) ensureId(v) else View.NO_ID
            }
        }
    }

    private fun inside(box: View?, v: View?): Boolean {
        var p: android.view.ViewParent? = v?.parent
        while (p != null) { if (p === box) return true; p = p.parent }
        return false
    }

    private fun buildTv(): View {
        val c = ctx
        built = false
        sigHero = null; sigNearby = null; sigRecent = null; sigStrip = null
        seenNearby.clear()
        heroBox = Ui.column(c)
        nearbyBox = Ui.column(c)
        recentBox = Ui.column(c)
        val g = Ui.gutter(c)

        // Left pane: who we are, then every action.
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(header(), lp())
        left.addView(heroBox, lp())
        val send = Ui.jobButton(c, s(R.string.home_send_files), Job.SEND, R.drawable.ic_send) { host.startSend() }
        sendBtn = send
        left.addView(send, lp(top = 4).apply { marginStart = g; marginEnd = g })

        left.addView(Ui.section(c, s(R.string.home_quick_send)), lp(top = 8))
        val tiles = quickTiles()
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(g, 0, g, dp(8)) }
        for (r in 0 until 2) {
            val rowL = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val t = tiles[r * 3 + k]
                rowL.addView(tile(t.job, t.icon, s(t.label), t.go),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (k > 0) marginStart = dp(8) })
            }
            grid.addView(rowL, lp(top = if (r > 0) 8 else 0))
        }
        left.addView(grid, lp(top = 4))

        left.addView(Ui.section(c, s(R.string.home_other_ways)), lp(top = 8))
        left.addView(Ui.row(c, null, s(R.string.send_show_qr), s(R.string.home_receive_hint), Ui.chevron(c, Job.RECEIVE),
            lead = Ui.plate(c, R.drawable.ic_scan, Job.RECEIVE.fill, Job.RECEIVE.on, 40, Job.RECEIVE),
            job = Job.RECEIVE) { host.push(ReceiveScreen(host)) }, lp())
        if (QrScanActivity.hasCamera(c)) {
            val ink = Theme.plateFor("files")
            left.addView(Ui.row(c, null, s(R.string.home_scan_code), s(R.string.home_scan_code_sub), Ui.chevron(c),
                lead = Ui.plate(c, R.drawable.ic_scan, ink.fill, ink.on, 40)) { host.scanQr(true) }, lp())
        }
        val web = Theme.plateFor("browser")
        left.addView(Ui.row(c, null, s(R.string.home_share_any), s(R.string.home_share_any_sub), Ui.chevron(c, Job.WEB),
            lead = Ui.plate(c, R.drawable.ic_globe, web.fill, web.on, 40, Job.WEB), job = Job.WEB) { host.showWebShare(false) }, lp())

        // Right pane: the transfer strip, the ports, then Recent.
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val nearby = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        nearby.addView(Ui.section(c, s(R.string.home_nearby)), lp())
        if (hasNumberKeys()) {
            tvHint = Ui.text(c, s(R.string.send_press_keys), Type.META).apply { setPadding(g, 0, g, dp(8)) }
            nearby.addView(tvHint, lp())
        }
        nearby.addView(nearbyBox, lp())
        right.addView(nearby, lp())
        val recent = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.quietButton(c, s(R.string.home_clear)) { confirmClear() })
            addView(Ui.quietButton(c, s(R.string.home_see_all)) { host.openHistory() })
        }
        recent.addView(Ui.section(c, s(R.string.home_recent), actions), lp())
        recent.addView(recentBox, lp())
        right.addView(recent, lp(top = 16))

        strip = TransferStrip(c) { host.showTransfer() }.apply { visibility = View.GONE }

        val ls = Ui.scrollPage(c, left)
        right.addView(strip, 0, lp())
        val rs = Ui.scrollPage(c, right)
        val cols = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(ls, LinearLayout.LayoutParams(dp(300 + 2 * Theme.TV_GUTTER), -1))
        cols.addView(View(c).apply { setBackgroundColor(Theme.LINE) },
            LinearLayout.LayoutParams(Theme.rule(c), -1).apply { topMargin = dp(Theme.TV_SAFE_V); bottomMargin = dp(Theme.TV_SAFE_V) })
        cols.addView(rs, LinearLayout.LayoutParams(0, -1, 1f))

        val root = KeyRoot(c) { n -> handleDigit(n) }
        root.addView(cols, FrameLayout.LayoutParams(-1, -1))
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
        built = true
        return root
    }

    private fun hasNumberKeys() = try {
        android.view.KeyCharacterMap.deviceHasKey(android.view.KeyEvent.KEYCODE_1)
    } catch (_: Throwable) { false }

    /** Number keys 1-9 send to that port (remote or keyboard). */
    private fun handleDigit(n: Int): Boolean {
        val v = portViews.getOrNull(n - 1) ?: return false
        v.performClick()
        return true
    }

    /** Clears the Recent list (history and the in-memory received list). Never deletes files. */
    private fun confirmClear() {
        host.confirm(s(R.string.clear_title), s(R.string.clear_body), s(R.string.home_clear), s(R.string.action_cancel)) { ok ->
            if (ok) {
                HistoryStore.clear()
                Hub.received.clear()
                Hub.changed()
            }
        }
    }

    /** A Quick send tile: solid category colour (INK for Files), icon on top, label below. */
    private fun tile(job: Job?, icon: Int, label: String, onClick: () -> Unit): View {
        val c = ctx
        val fill = job?.fill ?: Theme.INK
        val on = job?.on ?: Theme.BG
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(68)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(8))
        }
        v.addView(Ui.icon(c, icon, on, 24), LinearLayout.LayoutParams(dp(24), dp(24)))
        v.addView(Ui.text(c, label, 12f, on, Theme.MEDIUM, 16f).apply {
            maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        Ui.focusFill(v, job, 1.06f, Theme.R_CTRL,
            android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = Ui.dpf(c, Theme.R_CTRL) },
            pressFill = job?.shade ?: Theme.INK_2, ring = true)
        v.setOnClickListener { Ui.haptic(it); onClick() }
        return v
    }

    private fun header(): View {
        val c = ctx
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(Ui.gutter(c), 0, dp(4), 0)
        }
        bar.addView(Ui.text(c, s(R.string.home_title), Type.TITLE), lp(0, -2, 1f))
        // History (stage 2 adds the screen; Home reads HistoryStore for Recent meanwhile).
        bar.addView(Ui.iconButton(c, R.drawable.ic_settings, s(R.string.cd_settings)) { host.showSettings() })
        return bar
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onShow() {
        try { Discovery.nudge() } catch (_: Exception) { }
        watchNetwork()
        netAt = 0L
        refresh()
    }

    override fun onHide() { unwatchNetwork() }

    /** Wi-Fi connects / disconnects must update the status line and the Nearby empty state. */
    private fun watchNetwork() {
        if (netCb != null) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private fun poke() { Hub.main.postDelayed({ netAt = 0L; Hub.changed() }, 400) }
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
        try { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (_: Exception) { }
    }

    override fun firstFocus(): View? = firstPort ?: sendBtn

    override fun refresh() {
        if (!::heroBox.isInitialized) return
        renderStrip()
        renderHero()
        renderNearby()
        renderRecent()
    }

    // ---------------------------------------------------------------- network

    /** The Wi-Fi name when Android lets us read it, else a plain "Wi-Fi". */
    @Suppress("DEPRECATION")
    private fun wifiName(): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.trim('"') ?: ""
            if (ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == WifiManager.UNKNOWN_SSID) s(R.string.home_wifi_name_unknown) else ssid
        } catch (_: Exception) { s(R.string.home_wifi_name_unknown) }
    }

    private fun online() = try { Net.localAddrs().isNotEmpty() } catch (_: Exception) { true }
    private fun wifiOn() = try { Links.wifiEnabled(ctx) } catch (_: Exception) { true }

    /** Network name shown on the status line and in "Looking on ...". */
    private fun networkLabel(): String {
        val now = SystemClock.elapsedRealtime()
        if (netName.isNotEmpty() && now - netAt < 3000) return netName
        netAt = now
        netName = try {
            val a = Net.localAddrs().firstOrNull()
            when {
                a == null -> ""
                a.iface.name.lowercase().startsWith("wlan") -> wifiName()
                a.iface.name.lowercase().startsWith("eth") -> s(R.string.link_ethernet)
                a.iface.name.lowercase().startsWith("p2p") -> s(R.string.link_wifi_direct)
                else -> s(R.string.home_wifi_name_unknown)
            }
        } catch (_: Exception) { "" }
        return netName
    }

    // ---------------------------------------------------------------- transfer strip

    private fun renderStrip() {
        val t = Hub.transfer
        val pending = Hub.pendingSendTo
        val running = t?.running == true
        val title: String
        val meta: String
        val job: Job
        var frac = 0f
        when {
            running && t != null -> {
                job = if (t.outgoing) Job.SEND else Job.RECEIVE
                title = s(if (t.outgoing) R.string.title_sending else R.string.title_receiving, t.peer)
                val done = t.done.get()
                frac = if (t.total > 0) (done.toDouble() / t.total).toFloat() else 0f
                val el = (SystemClock.elapsedRealtime() - t.startedAt).coerceAtLeast(1)
                val sp = t.sessionDone * 1000.0 / el
                val pct = (frac * 100).toInt()
                meta = if (sp > 1 && t.total > 0) s(R.string.strip_meta, pct, Io.duration(((t.total - done) / sp).toLong()))
                else s(R.string.strip_meta_noeta, pct)
            }
            pending != null -> {
                job = Job.SEND
                title = s(R.string.strip_connecting, pending)
                meta = ""
            }
            else -> {
                if (strip.visibility != View.GONE) strip.visibility = View.GONE
                sigStrip = null
                return
            }
        }
        if (strip.visibility != View.VISIBLE) strip.visibility = View.VISIBLE
        strip.update(job, title, meta, frac)
    }

    // ---------------------------------------------------------------- this device

    private fun renderHero() {
        val err = Server.error
        val on = online()
        val wifi = wifiOn()
        val net = networkLabel()
        val ip = try { Net.primaryIp() ?: "" } catch (_: Exception) { "" }
        val sig = "${Hub.myName}|$err|$on|$wifi|$net|$ip|${Hub.autoMode}|${Trust.hasAny()}"
        if (sig == sigHero) return
        sigHero = sig
        val c = ctx

        heroBox.removeAllViews()
        val g = Ui.gutter(c)
        // One compact status line: LED, device name and network. The LED carries the state.
        val line = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            setPadding(g, dp(4), g, dp(4))
            isFocusable = true
            setOnClickListener { Ui.haptic(it); host.showSettings() }
            Ui.focusFill(this, null, 1.02f, 0f, null)
        }
        val led: Led
        val label: View
        when {
            err != null -> {
                led = Led.DANGER
                label = LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.icon(c, R.drawable.ic_alert, Theme.TXT_DANGER, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
                    addView(Ui.text(c, s(R.string.home_cant_receive, err), Type.META, Theme.TXT_DANGER))
                }
            }
            !on -> {
                led = Led.OFF
                label = Ui.text(c, "${Hub.myName} · " + s(if (!wifi) R.string.home_wifi_off else R.string.home_no_network), Type.BODY)
            }
            else -> {
                led = Led.OK
                label = LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.text(c, "${Hub.myName} · $net ", Type.BODY).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-2, -2, 0f))
                    addView(Ui.mono(c, ip), LinearLayout.LayoutParams(-2, -2))
                }
            }
        }
        line.addView(Ui.led(c, led), LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
        // The line fills INK when focused / selected: its text must flip to BG with it, or the name
        // and IP vanish (black on black, white on white in dark mode).
        followFill(label)
        line.addView(label, LinearLayout.LayoutParams(-2, -2))
        line.contentDescription = "${Hub.myName}. ${net} $ip"
        heroBox.addView(line, lp())
        if (err == null) {
            val summary = when (Hub.autoMode) {
                Hub.AUTO_EVERYONE -> R.string.home_accept_everyone
                Hub.AUTO_ASK -> R.string.home_accept_ask
                else -> R.string.home_accept_trusted
            }
            heroBox.addView(Ui.text(c, s(summary), Type.META).apply { setPadding(g + dp(18), 0, g, dp(12)) }, lp())
        } else heroBox.addView(Ui.spacer(c, 12))
    }

    /** Text under [v] takes BG while their row is focused or selected (its fill is INK). */
    private fun followFill(v: View) {
        v.isDuplicateParentStateEnabled = true
        when (v) {
            is TextView -> v.setTextColor(Ui.states(v.currentTextColor, Theme.BG, Ui.tv(ctx)))
            is ViewGroup -> {
                // A group only forwards its state to children that were flagged when added: re-add them.
                val kids = (0 until v.childCount).map { v.getChildAt(it) }
                v.removeAllViews()
                for (k in kids) { followFill(k); v.addView(k, k.layoutParams) }
            }
        }
    }

    // ---------------------------------------------------------------- nearby ports

    private fun kindLabel(type: String) = when (type) {
        "tv" -> s(R.string.type_tv)
        "phone" -> s(R.string.type_phone)
        else -> s(R.string.type_computer)
    }

    private fun viaRank(d: Device) = when (d.via) { Device.VIA_LAN -> 0; Device.VIA_P2P -> 1; else -> 2 }

    private fun renderNearby() {
        val list = Hub.devices.values.sortedWith(compareBy({ viaRank(it) }, { it.name.lowercase() })).take(9)
        val wifiOn = wifiOn()
        val online = online()
        val sig = "$wifiOn|$online|" + list.joinToString("|") { "${it.id},${it.name},${it.type},${it.via},${it.ip}" }
        if (sig == sigNearby) return
        sigNearby = sig

        val c = ctx
        val focusId = nearbyBox.findFocus()?.tag
        nearbyBox.removeAllViews()
        firstPort = null
        portViews.clear()
        val g = Ui.gutter(c)
        if (list.isEmpty()) {
            val line = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(64)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(g, dp(8), g, dp(8))
            }
            when {
                online -> {
                    val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    r.addView(ScanTicks(c), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
                    r.addView(Ui.text(c, s(R.string.home_looking, networkLabel().ifEmpty { s(R.string.home_wifi_name_unknown) }), Type.ROW))
                    line.addView(r)
                    line.addView(Ui.text(c, s(R.string.home_looking_meta), Type.META).apply { setPadding(0, dp(2), 0, 0) })
                }
                !wifiOn -> {
                    line.addView(Ui.text(c, s(R.string.home_wifi_off), Type.ROW))
                    line.addView(Ui.text(c, s(R.string.home_wifi_off_meta), Type.META).apply { setPadding(0, dp(2), 0, dp(8)) })
                    line.addView(Ui.secondaryButton(c, s(R.string.home_wifi_settings), null, true) {
                        try { host.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { }
                    })
                }
                else -> {
                    line.addView(Ui.text(c, s(R.string.home_no_network), Type.ROW))
                    line.addView(Ui.text(c, s(R.string.home_no_network_meta), Type.META).apply { setPadding(0, dp(2), 0, 0) })
                }
            }
            nearbyBox.addView(line, lp())
            return
        }
        var newIdx = 0
        list.forEachIndexed { i, d ->
            val tags = ArrayList<String>()
            tags.add(s(when (d.via) { Device.VIA_LAN -> R.string.link_wifi; Device.VIA_P2P -> R.string.link_direct; else -> R.string.link_qr }))
            if (try { Trust.keyFor(d.id) != null } catch (_: Exception) { false }) tags.add(0, s(R.string.tag_trusted))
            val meta = if (d.via == Device.VIA_P2P) s(R.string.meta_type_ip, kindLabel(d.type), Links.linkLabel(d.via))
            else s(R.string.meta_type_ip, kindLabel(d.type), d.ip)
            val row = Ui.portRow(c, i + 1, d, meta, tags) {
                if (Hub.transfer?.running == true || Hub.pendingSendTo != null) host.toast(s(R.string.busy_wait))
                else host.sendTo(d)
            }
            row.tag = d.id
            row.contentDescription = "${d.name}, ${kindLabel(d.type)}, ${tags.joinToString(", ")}"
            nearbyBox.addView(row, lp())
            portViews.add(row)
            if (firstPort == null) firstPort = row
            if (seenNearby.add(d.id) && built) Anim.enter(row, (newIdx++ * 45).toLong())
            if (d.id == focusId) row.requestFocus()
        }
        linkPanes()
    }

    // ---------------------------------------------------------------- recent (HistoryStore)

    private fun ago(at: Long): String =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    private fun thumb(): ImageView {
        val c = ctx
        val r = Ui.dpf(c, Theme.R_CTRL)
        return ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Theme.SURFACE_2)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, r) }
            }
            clipToOutline = true
            isDuplicateParentStateEnabled = true
        }
    }

    private fun renderRecent() {
        val all = HistoryStore.all().take(3)
        val minute = System.currentTimeMillis() / 60000
        val sig = minute.toString() + all.joinToString("|") { it.id }
        if (sig == sigRecent) return
        sigRecent = sig

        val c = ctx
        val g = Ui.gutter(c)
        val focusKey = recentBox.findFocus()?.tag
        recentBox.removeAllViews()
        if (all.isEmpty()) {
            recentBox.addView(Ui.text(c, s(R.string.home_recent_empty), Type.META).apply { setPadding(g, dp(8), g, dp(16)) }, lp())
            return
        }
        for (e in all) {
            val row = recentRow(e)
            row.tag = e.id
            recentBox.addView(row, lp())
            if (e.id == focusKey) row.requestFocus()
        }
    }

    private fun recentRow(e: HistoryEntry): View {
        val c = ctx
        val file = e.files.firstOrNull()
        val failed = e.state != HistoryCodec.DONE
        val sizeText = Io.bytes(e.total)
        // text / link
        if (e.text != null) {
            val p = Theme.plateFor("text")
            val txt = e.text
            val url = ProtoV2.isHttpUrl(txt)
            val meta = if (e.outgoing) s(R.string.recent_to, if (url) "Link" else "Text", e.peer)
            else s(if (url) R.string.recent_link else R.string.recent_text, e.peer)
            val trailing = Ui.secondaryButton(c, s(R.string.action_copy), null, true) { host.copyText(s(R.string.main_label_text), txt) }
            return Ui.row(c, null, HistoryStore.textPreview(txt), meta, trailing,
                lead = Ui.plate(c, R.drawable.ic_link, p.fill, p.on, 40), onClick = { if (url) host.openUrl(txt) else host.copyText(s(R.string.main_label_text), txt) })
        }
        // failed or cancelled
        if (failed) {
            val lead = Ui.plate(c, R.drawable.ic_alert, Theme.C_DANGER, Theme.ON_LIGHT, 40)
            return Ui.row(c, null, titleOf(e), s(R.string.recent_failed, ago(e.at)), null, lead = lead)
        }
        // received file, group or APK
        val meta = if (e.outgoing) s(R.string.recent_to, "$sizeText · ${ago(e.at)}", e.peer)
        else s(R.string.recent_from, "$sizeText · ${ago(e.at)}", e.peer)
        val iv = thumb()
        if (file?.appPkg != null) Thumbs.app(iv, file.appPkg) else if (file != null) Thumbs.file(iv, file.name, file.uri?.let { Uri.parse(it) }, dp(96))
        val open = { if (e.outgoing) Unit else openEntry(e) }
        val trailing = if (file?.isApp == true)
            Ui.secondaryButton(c, s(R.string.action_install), null, true) { open() } else null
        return Ui.row(c, null, titleOf(e), meta, trailing, lead = iv, onClick = if (e.outgoing) null else open)
    }

    private fun titleOf(e: HistoryEntry): String {
        val f = e.files.firstOrNull()
        return when {
            f?.appLabel != null && f.appLabel.isNotEmpty() -> f.appLabel
            e.count > 1 -> c().resources.getQuantityString(R.plurals.kind_file, e.count, e.count)
            f != null -> f.name.substringAfterLast('/')
            else -> e.peer
        }
    }

    private fun c(): Context = ctx

    /** Opens a received file or installs a received app, found again by its saved URI. */
    private fun openEntry(e: HistoryEntry) {
        val f = e.files.firstOrNull()
        val uri = f?.uri
        val r = Hub.received.firstOrNull { it.uri?.toString() == uri }
        if (r == null) host.toast(s(R.string.recent_missing), 2) else host.openReceived(r)
    }
}
