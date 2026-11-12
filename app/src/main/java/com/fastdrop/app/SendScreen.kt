package com.fastdrop.app

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Small helpers shared by the Send, Receive and Web share screens. */
internal object ScreenKit {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int) = Theme.dp(ctx, v)

    /** Linear layout params with a bottom margin in dp. */
    fun lp(ctx: Context, w: Int = MATCH, h: Int = WRAP, bottomDp: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { bottomMargin = dp(ctx, bottomDp) }

    /** Show or hide a view with a small entrance when it appears. */
    fun setShown(v: View, show: Boolean) {
        val now = v.visibility == View.VISIBLE
        if (now == show) return
        if (show) {
            v.visibility = View.VISIBLE
            Anim.enter(v)
        } else {
            v.animate().cancel()
            v.visibility = View.GONE
            v.alpha = 1f
            v.translationY = 0f
        }
    }

    /** Changes text with a quick fade, only when it really differs. */
    fun fadeText(tv: TextView, s: CharSequence) {
        if (tv.text.toString() == s.toString()) return
        if (!Anim.on(tv.context) || !tv.isAttachedToWindow) { tv.text = s; return }
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(Theme.DUR_FAST / 2).withEndAction {
            tv.text = s
            tv.animate().alpha(1f).setDuration(Theme.DUR_FAST).start()
        }.start()
    }

    /** Status line for our own hosted link (text, colour). */
    fun hostStatus(ctx: Context): Pair<String, Int> = when (Links.hostState) {
        Links.HostState.STARTING ->
            (if (Links.p2pSupported(ctx)) "Setting up Wi‑Fi Direct…" else "Setting up hotspot…") to Theme.WARNING
        Links.HostState.READY -> when (Links.hostMode) {
            JoinInfo.MODE_P2P -> "Ready · Wi‑Fi Direct" to Theme.SUCCESS
            JoinInfo.MODE_HOTSPOT -> "Ready · Hotspot" to Theme.SUCCESS
            else -> "Ready · same Wi‑Fi" to Theme.SUCCESS
        }
        Links.HostState.FAILED -> "Same Wi‑Fi only" to Theme.WARNING
        Links.HostState.OFF -> "Ready · same Wi‑Fi" to Theme.SUCCESS
    }

    /** The subtitle TextView of a Ui.row built with an icon (badge, then text column). */
    fun subtitleOf(row: View): TextView? {
        val g = row as? ViewGroup ?: return null
        val col = (if (g.childCount > 1) g.getChildAt(1) else g.getChildAt(0)) as? ViewGroup ?: return null
        return if (col.childCount > 1) col.getChildAt(1) as? TextView else null
    }

    fun titleOf(row: View): TextView? {
        val g = row as? ViewGroup ?: return null
        val col = (if (g.childCount > 1) g.getChildAt(1) else g.getChildAt(0)) as? ViewGroup ?: return null
        return col.getChildAt(0) as? TextView
    }

    fun firstFocusable(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.isFocusable && c.isEnabled) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    /** A bottom sheet with one text field. Enter on the keyboard confirms too. */
    fun inputSheet(
        host: MainActivity, title: String, hint: String, initial: String, inputType: Int,
        okLabel: String, multiline: Boolean = false, onOk: (String) -> Unit,
    ): Sheets.Handle {
        val ctx: Context = host
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.title(ctx, title, 22f), lp(ctx, bottomDp = 14))
        val et = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            this.inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
            if (multiline) { minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START } else setSingleLine()
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.FAINT)
            textSize = 17f
            typeface = Theme.MEDIUM
            val p = dp(ctx, 14)
            setPadding(p, p, p, p)
            if (!multiline) imeOptions = EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(Theme.SURFACE_2)
                setStroke(dp(ctx, 1), Theme.STROKE_STRONG)
            }
        }
        col.addView(et, lp(ctx, bottomDp = 16))
        var handle: Sheets.Handle? = null
        fun hideKeyboard() {
            try {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(et.windowToken, 0)
            } catch (_: Exception) { }
        }
        fun submit() {
            val v = et.text.toString().trim()
            if (v.isEmpty()) { Anim.shake(et); return }
            hideKeyboard()
            handle?.dismiss()
            onOk(v)
        }
        et.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Ui.secondaryButton(ctx, "Cancel") { hideKeyboard(); handle?.dismiss() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(ctx, 10) })
        buttons.addView(Ui.jobButton(ctx, okLabel, Job.SEND) { submit() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(buttons, lp(ctx))
        val h = Sheets.show(host.root, col, true) { hideKeyboard() }
        handle = h
        et.postDelayed({
            try {
                et.requestFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, 0)
            } catch (_: Exception) { }
        }, 320)
        return h
    }
}

/**
 * "Send to": same-Wi-Fi devices appear on their own (top), Wi-Fi Direct neighbours below, QR devices
 * after. The radar mirrors the list. Extra ways to connect sit underneath.
 */
class SendScreen(host: MainActivity) : Screen(host) {
    private companion object {
        const val K_KEY = 0x7f0fff31
        const val K_SIG = 0x7f0fff32
    }

    private class Entry(val key: String, val sig: String, val build: () -> View)

    private var shown = false
    private var entered = false
    private var sheet: Sheets.Handle? = null

    private lateinit var scroll: ScrollView
    private lateinit var selTitle: TextView
    private lateinit var selSub: TextView
    private lateinit var selAction: TextView
    private lateinit var statusText: TextView
    private lateinit var locBanner: View
    private lateinit var permBanner: View
    private lateinit var list: LinearLayout
    private lateinit var scanRow: View
    private lateinit var qrRow: View
    private lateinit var qrPanel: LinearLayout
    private lateinit var qrView: QrView
    private lateinit var qrStatus: TextView
    private lateinit var qrHint: TextView
    private var scanSpinner: ProgressBar? = null
    private var firstAction: View? = null

    private var sections: List<View> = emptyList()
    private var qrOpen = false
    private var scanning = false
    private var lastSelRef: List<SendItem>? = null
    private var lastSelText: String? = null
    /** Devices ticked for a multi-device send (long-press a device to toggle). */
    private val multi = LinkedHashSet<String>()
    private lateinit var multiBar: View
    private lateinit var multiBtn: TextView
    private var lastBlipSig = ""
    private var lastQr: String? = null
    private val transition: LayoutTransition by lazy {
        LayoutTransition().apply {
            setDuration(Theme.DUR)
            setInterpolator(LayoutTransition.APPEARING, Theme.EASE_OUT)
            setInterpolator(LayoutTransition.DISAPPEARING, Theme.EASE_IN)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, Theme.EASE_IN_OUT)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, Theme.EASE_IN_OUT)
        }
    }
    private var transitionOn = false

    private fun lp(w: Int = ScreenKit.MATCH, h: Int = ScreenKit.WRAP, bottomDp: Int = 14, weight: Float = 0f) =
        ScreenKit.lp(ctx, w, h, bottomDp, weight)

    override fun build(): View {
        entered = false
        lastSelRef = null; lastSelText = null; lastBlipSig = ""; lastQr = null
        val wide = Ui.wide(ctx)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Theme.BG)

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.header(ctx, "Send to", { host.pop() }), lp(bottomDp = 6))

        val selCard = buildSelectionCard()
        val radarBox = buildRadar()
        val devicesCard = buildDevices()
        multiBar = buildMultiBar()
        val actions = buildActions()
        buildQrPanel()
        locBanner = banner(R.drawable.ic_wifi_direct, "Location is off",
            "Turn it on to find Wi‑Fi Direct phones nearby.") {
            try { host.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
        }
        permBanner = banner(R.drawable.ic_wifi_direct, "Find phones without Wi‑Fi",
            "Allow Nearby devices so Wi‑Fi Direct can work.") {
            if (Links.approximateOnly(ctx)) { openAppSettings(); return@banner }
            host.ensureLinkPermissions(explicit = true) { ok -> if (ok && shown) Links.startNearby(ctx); refresh() }
        }
        if (qrOpen) qrPanel.visibility = View.VISIBLE

        if (wide) {
            val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = ScreenKit.dp(ctx, 24)
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            left.addView(selCard, lp()); left.addView(radarBox, lp(bottomDp = 4)); left.addView(statusText, lp())
            right.addView(locBanner, lp()); right.addView(permBanner, lp())
            right.addView(multiBar, lp()); right.addView(devicesCard, lp()); right.addView(actions, lp()); right.addView(qrPanel, lp())
            cols.addView(left, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f).apply { marginEnd = gap / 2 })
            cols.addView(right, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f).apply { marginStart = gap / 2 })
            content.addView(cols, lp())
            sections = listOf(selCard, radarBox, devicesCard, actions)
        } else {
            content.addView(selCard, lp())
            content.addView(radarBox, lp(bottomDp = 4))
            content.addView(statusText, lp())
            content.addView(locBanner, lp()); content.addView(permBanner, lp())
            content.addView(multiBar, lp())
            content.addView(devicesCard, lp())
            content.addView(actions, lp())
            content.addView(qrPanel, lp())
            sections = listOf(selCard, radarBox, devicesCard, actions)
        }
        scroll = Ui.scrollPage(ctx, content, gutter = true)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        refresh()
        return root
    }

    // ---------------------------------------------------------------- pieces

    private fun buildSelectionCard(): View {
        val card = Ui.column(ctx)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        selTitle = Ui.text(ctx, "", 18f, Theme.TEXT, Theme.BOLD)
        selSub = Ui.text(ctx, "", 13f, Theme.MUTED).apply { setPadding(0, ScreenKit.dp(ctx, 3), 0, 0) }
        col.addView(selTitle); col.addView(selSub)
        selAction = Ui.secondaryButton(ctx, "Edit", R.drawable.ic_edit) { edit() }
        row.addView(col, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))
        row.addView(selAction, LinearLayout.LayoutParams(ScreenKit.WRAP, ScreenKit.WRAP).apply { marginStart = ScreenKit.dp(ctx, 12) })
        card.addView(row, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        return card
    }

    private fun buildRadar(): View {
        // Stage 2 rebuilds this screen: the list is the radar. A one-line status stays for now.
        val box = FrameLayout(ctx)
        statusText = Ui.text(ctx, "Looking for devices…", 14f, Theme.MUTED).apply { gravity = Gravity.START }
        box.addView(statusText, FrameLayout.LayoutParams(-2, -2))
        return box
    }

    private fun buildDevices(): View {
        val card = Ui.column(ctx)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        return card
    }

    private fun buildMultiBar(): View {
        val card = Ui.column(ctx)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        multiBtn = Ui.jobButton(ctx, "Send to 2 devices", Job.SEND, R.drawable.ic_send) { sendMulti() }
        row.addView(Ui.secondaryButton(ctx, "Clear") { multi.clear(); refresh() },
            LinearLayout.LayoutParams(ScreenKit.WRAP, ScreenKit.WRAP).apply { marginEnd = ScreenKit.dp(ctx, 10) })
        row.addView(multiBtn, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))
        card.addView(row, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        card.visibility = View.GONE
        return card
    }

    private fun buildActions(): View {
        val card = Ui.column(ctx)
        fun add(v: View, divider: Boolean = true) {
            if (card.childCount > 0 && divider) card.addView(Ui.rule(ctx))
            card.addView(v, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
            if (firstAction == null) firstAction = v
        }
        if (QrScanActivity.hasCamera(ctx)) {
            add(Ui.row(ctx, R.drawable.ic_scan, "Scan QR code",
                "Join the other device's code", null) { host.scanQr(true) })
        }
        add(Ui.row(ctx, R.drawable.ic_edit, "Send text",
            "Type a message or paste a link", null) { typeText() })
        add(Ui.row(ctx, R.drawable.ic_copy, "Send clipboard",
            "Send whatever you last copied", null) { host.sendClipboard() })
        qrRow = Ui.row(ctx, R.drawable.ic_qr, "Show my QR",
            "Let another device scan this one", null) { toggleQr() }
        add(qrRow)

        val spin = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Theme.ACCENT_2)
            visibility = View.INVISIBLE
        }
        val spinBox = FrameLayout(ctx)
        spinBox.addView(spin, FrameLayout.LayoutParams(ScreenKit.dp(ctx, 22), ScreenKit.dp(ctx, 22), Gravity.CENTER))
        scanSpinner = spin
        scanRow = Ui.row(ctx, R.drawable.ic_refresh, "Find on network",
            "Search this Wi‑Fi for FastDrop", spinBox) { scanNetwork() }
        add(scanRow)

        add(Ui.row(ctx, R.drawable.ic_edit, "Enter IP address",
            "Type the other device's address", null) { enterIp() })
        add(Ui.row(ctx, R.drawable.ic_globe, "Share via browser",
            "iPhone, PC — no app needed", null) { host.showWebShare(false) })
        return card
    }

    private fun buildQrPanel() {
        qrPanel = Ui.column(ctx).apply { visibility = View.GONE; gravity = Gravity.CENTER_HORIZONTAL }
        qrPanel.addView(Ui.text(ctx, "Your code", 13f, Theme.MUTED, Theme.MEDIUM), lp(bottomDp = 10))
        qrView = QrView(ctx)
        val size = ScreenKit.dp(ctx, if (Ui.wide(ctx)) 240 else 250)
        qrPanel.addView(qrView, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ScreenKit.dp(ctx, 14)
        })
        qrStatus = Ui.text(ctx, "", 15f, Theme.SUCCESS, Theme.BOLD).apply { gravity = Gravity.CENTER }
        qrPanel.addView(qrStatus, lp(bottomDp = 6))
        qrHint = Ui.text(ctx,
            "On the other device tap Receive, then Scan sender's QR. Or point its camera here.", 13f, Theme.MUTED)
            .apply { gravity = Gravity.CENTER }
        qrPanel.addView(qrHint, lp(bottomDp = 0))
    }

    private fun banner(icon: Int, title: String, sub: String, onClick: () -> Unit): View {
        val card = Ui.column(ctx)
        card.addView(Ui.row(ctx, icon, title, sub, null, onClick = onClick),
            LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        card.visibility = View.GONE
        return card
    }

    // ---------------------------------------------------------------- actions

    private fun edit() {
        val t = Hub.selectionText
        if (t != null) typeText(t) else host.openPicker(PickerActivity.TAB_PHOTOS) { refresh() }
    }

    private fun typeText(initial: String = "") {
        sheet = ScreenKit.inputSheet(host, "Send text", "Message or link", initial,
            InputType.TYPE_CLASS_TEXT, "Next", multiline = true) { host.setSendText(it) }
    }

    private fun toggleMulti(id: String) {
        if (!multi.remove(id)) multi.add(id)
        refresh()
    }

    private fun sendMulti() {
        val devs = multi.mapNotNull { Hub.devices[it] }
        multi.clear()
        refresh()
        host.sendToMany(devs)
    }

    private fun send(d: Device) {
        if (multi.isNotEmpty()) { toggleMulti(d.id); return }
        if (!host.hasPayload()) {
            host.openPicker(PickerActivity.TAB_PHOTOS) {
                if (host.hasPayload()) host.sendTo(Hub.devices[d.id] ?: d)
            }
            return
        }
        host.sendTo(Hub.devices[d.id] ?: d)
    }

    private fun toggleQr() {
        qrOpen = !qrOpen
        if (qrOpen) {
            val go = { if (shown && qrOpen) Links.startHosting(ctx, "send") }
            if (Links.hasPermissions(ctx)) go() else host.ensureLinkPermissions(explicit = true) { go() }
            ScreenKit.setShown(qrPanel, true)
            qrPanel.post { scroll.smoothScrollTo(0, qrPanel.top) }
        } else {
            Links.stopHosting("send")
            ScreenKit.setShown(qrPanel, false)
        }
        host.updateKeepScreenOn()
        refresh()
    }

    private fun scanNetwork() {
        if (scanning) return
        scanning = true
        scanSpinner?.visibility = View.VISIBLE
        host.toast("Searching this Wi‑Fi…")
        Discovery.scan { n ->
            scanning = false
            scanSpinner?.visibility = View.INVISIBLE
            host.toast(if (n == 0) "No devices found" else if (n == 1) "Found 1 device" else "Found $n devices", if (n == 0) 0 else 1)
            refresh()
        }
    }

    private fun enterIp() {
        val ip = try { Net.primaryIp() } catch (_: Exception) { null }
        val prefill = ip?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.plus(".") ?: ""
        sheet = ScreenKit.inputSheet(host, "Enter IP address", "e.g. 192.168.1.23", prefill,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "Connect") { addr ->
            host.toast("Looking for $addr…")
            thread(name = "hello", isDaemon = true) {
                val dev = try { Discovery.hello(addr.trim()) } catch (_: Exception) { null }
                Hub.main.post {
                    if (dev != null) host.toast("Found ${dev.name}", 1) else host.toast("No FastDrop found at $addr", 2)
                    refresh()
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onShow() {
        shown = true
        Discovery.nudge()
        if (Links.p2pSupported(ctx)) {
            if (Links.hasPermissions(ctx)) Links.startNearby(ctx)
            else host.ensureLinkPermissions { if (shown) Links.startNearby(ctx); refresh() }
        }
        if (qrOpen) {
            if (Links.hasPermissions(ctx)) Links.startHosting(ctx, "send")
            else host.ensureLinkPermissions { if (shown && qrOpen) Links.startHosting(ctx, "send") }
        }
        refresh()
        if (!entered) {
            entered = true
        }
    }

    override fun onHide() {
        shown = false
        Links.stopNearby()
        Links.stopHosting("send")
        sheet?.dismiss(); sheet = null
    }

    override fun onBack(): Boolean {
        if (sheet?.showing == true) { sheet?.dismiss(); sheet = null; return true }
        if (qrOpen) { toggleQr(); return true }
        return false
    }

    override val keepsScreenOn: Boolean get() = shown && qrOpen

    override fun firstFocus(): View? =
        ScreenKit.firstFocusable(list) ?: firstAction

    // ---------------------------------------------------------------- refresh

    override fun refresh() {
        if (!::list.isInitialized) return
        refreshSelection()
        val devs = Hub.devices.values.toList().sortedWith(
            compareBy<Device>({ rank(it.via) }, { it.name.lowercase() }, { it.id }))
        multi.retainAll(devs.map { it.id }.toSet())
        refreshRadar(devs)
        refreshList(devs)
        multiBtn.text = "Send to ${multi.size} device${if (multi.size == 1) "" else "s"}"
        ScreenKit.setShown(multiBar, multi.isNotEmpty())
        ScreenKit.setShown(locBanner, Links.locationOffBlocksDiscovery(ctx))
        ScreenKit.setShown(permBanner, Links.p2pSupported(ctx) && Links.requiredPermissions().isNotEmpty() && !Links.hasPermissions(ctx))
        if (Links.approximateOnly(ctx)) Ui.setRowText(permBanner, "Choose Precise location", "Wi‑Fi Direct needs Precise location. Tap to open settings.")
        refreshQr()
    }

    private fun openAppSettings() {
        try {
            host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${host.packageName}")))
        } catch (_: Exception) { host.toast("Open Android Settings and allow it for FastDrop", 2) }
    }

    private fun rank(via: String) = when (via) { Device.VIA_LAN -> 0; Device.VIA_P2P -> 1; else -> 2 }

    private fun tint(via: String) = when (via) { Device.VIA_LAN -> Theme.SUCCESS; Device.VIA_P2P -> Theme.ACCENT_2; else -> Theme.ACCENT }

    private fun refreshSelection() {
        val sel = Hub.selection
        val txt = Hub.selectionText
        if (sel === lastSelRef && txt == lastSelText) return
        lastSelRef = sel; lastSelText = txt
        if (txt != null) {
            selTitle.text = "Text · ${txt.length} character${if (txt.length == 1) "" else "s"}"
            selSub.text = txt.replace('\n', ' ').take(80)
            selAction.text = "Edit"
            return
        }
        if (sel.isEmpty()) {
            selTitle.text = "Nothing selected"
            selSub.text = "Choose what to send first"
            selAction.text = "Choose"
            return
        }
        val apps = HashSet<String>()
        var photos = 0; var videos = 0; var music = 0; var other = 0; var total = 0L
        for (item in sel) {
            total += item.size
            val a = item.app
            if (a != null) { apps.add(a.pkg); continue }
            when (Thumbs.iconFor(item.name)) {
                R.drawable.ic_photo -> photos++
                R.drawable.ic_video -> videos++
                R.drawable.ic_music -> music++
                R.drawable.ic_apps -> apps.add(item.name)
                else -> other++
            }
        }
        val count = apps.size + photos + videos + music + other
        selTitle.text = "${if (count == 1) "1 item" else "$count items"} · ${Io.bytes(total)}"
        val parts = ArrayList<String>()
        if (apps.isNotEmpty()) parts.add(if (apps.size == 1) "1 app" else "${apps.size} apps")
        if (photos > 0) parts.add(if (photos == 1) "1 photo" else "$photos photos")
        if (videos > 0) parts.add(if (videos == 1) "1 video" else "$videos videos")
        if (music > 0) parts.add(if (music == 1) "1 song" else "$music songs")
        if (other > 0) parts.add(if (other == 1) "1 file" else "$other files")
        selSub.text = parts.joinToString(" · ")
        selAction.text = "Edit"
    }

    private fun refreshRadar(devs: List<Device>) {
        val sig = devs.joinToString("|") { it.id + it.name + it.via }
        if (sig != lastBlipSig) {
            lastBlipSig = sig
        }
        val n = devs.size
        ScreenKit.fadeText(statusText, when {
            n == 0 -> "Looking for devices…"
            n == 1 -> "1 device nearby · tap to send"
            else -> "$n devices nearby · tap to send"
        })
    }

    private fun refreshQr() {
        if (!qrOpen) return
        val uri = try { Links.hostInfo().toUri() } catch (_: Exception) { null }
        if (uri != lastQr) { lastQr = uri; qrView.setText(uri?.let { it + "&r=s" }) }
        val st = ScreenKit.hostStatus(ctx)
        ScreenKit.fadeText(qrStatus, st.first)
        qrStatus.setTextColor(st.second)
    }

    private fun refreshList(devs: List<Device>) {
        val entries = ArrayList<Entry>()
        fun section(key: String, title: String, group: List<Device>) {
            if (group.isEmpty()) return
            entries.add(Entry("hdr:$key", title) {
                Ui.section(ctx, title).apply { setPadding(ScreenKit.dp(ctx, 14), ScreenKit.dp(ctx, 10), 0, ScreenKit.dp(ctx, 2)) }
            })
            for (d in group) {
                val sig = "${d.name}|${d.ip}|${d.type}|${d.via}|${d.id in multi}"
                entries.add(Entry("dev:${d.id}", sig) { deviceRow(d) })
            }
        }
        section("lan", "On this Wi‑Fi", devs.filter { it.via == Device.VIA_LAN })
        section("p2p", "Nearby · Wi‑Fi Direct", devs.filter { it.via == Device.VIA_P2P })
        section("qr", "From QR code", devs.filter { it.via == Device.VIA_QR })
        if (entries.isEmpty()) {
            // Any local network counts (Wi‑Fi, our own hotspot, Ethernet), not only Wi‑Fi being on.
            val online = Links.wifiEnabled(ctx) || try { Net.localAddrs().isNotEmpty() } catch (_: Exception) { true }
            entries.add(Entry("empty", "empty|$online") { emptyState(online) })
        }
        reconcile(entries)
    }

    /** Updates the list in place so unchanged rows (and TV focus) are left alone. */
    private fun reconcile(desired: List<Entry>) {
        fun keyOf(v: View) = v.getTag(K_KEY) as? String
        val want = HashSet<String>().also { s -> desired.forEach { s.add(it.key) } }
        val existing = ArrayList<View>()
        for (i in 0 until list.childCount) existing.add(list.getChildAt(i))
        val byKey = HashMap<String, View>()
        for (v in existing) keyOf(v)?.let { byKey[it] = v }

        val changed = desired.any { e -> byKey[e.key]?.let { it.getTag(K_SIG) != e.sig } == true }
        val existingOrder = existing.mapNotNull { keyOf(it) }.filter { it in want }
        val desiredOrder = desired.map { it.key }.filter { byKey.containsKey(it) }
        val reordered = existingOrder != desiredOrder
        val instant = changed || reordered || !Anim.on(ctx) || !list.isAttachedToWindow
        setTransition(!instant)

        var focusKey: String? = null
        for (v in existing) {
            val k = keyOf(v)
            if (k != null && k in want) continue
            list.removeView(v)
        }
        if (instant) {
            // Rebuild changed rows and fix the order without animation.
            for (e in desired) {
                val v = byKey[e.key] ?: continue
                if (v.getTag(K_SIG) != e.sig) {
                    if (v.hasFocus()) focusKey = e.key
                    list.removeView(v)
                    byKey.remove(e.key)
                } else if (reordered) {
                    list.removeView(v)
                }
            }
        }
        for ((i, e) in desired.withIndex()) {
            val cur = if (i < list.childCount) list.getChildAt(i) else null
            if (cur != null && keyOf(cur) == e.key) continue
            val v = byKey[e.key]?.takeIf { it.parent == null } ?: e.build().also {
                it.setTag(K_KEY, e.key); it.setTag(K_SIG, e.sig)
            }
            if (v.parent != null) list.removeView(v)
            list.addView(v, minOf(i, list.childCount), LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        }
        if (focusKey != null) {
            val fk = focusKey
            for (i in 0 until list.childCount) {
                val v = list.getChildAt(i)
                if (keyOf(v) == fk) { v.post { v.requestFocus() }; break }
            }
        }
    }

    private fun setTransition(on: Boolean) {
        if (on == transitionOn) return
        transitionOn = on
        list.layoutTransition = if (on) transition else null
    }

    private fun deviceRow(d: Device): View {
        val icon = when (d.type) { "tv" -> R.drawable.ic_tv; "laptop" -> R.drawable.ic_laptop; else -> R.drawable.ic_phone }
        val color = tint(d.via)
        val chipIcon = when (d.via) { Device.VIA_LAN -> R.drawable.ic_wifi; Device.VIA_P2P -> R.drawable.ic_wifi_direct; else -> R.drawable.ic_qr }
        val picked = d.id in multi
        val chip = Ui.tag(ctx, if (picked) "Selected" else Links.linkLabel(d.via))
        val sub = when (d.via) {
            Device.VIA_LAN -> d.ip
            Device.VIA_P2P -> "Tap to connect over Wi‑Fi Direct"
            else -> if (d.join?.hasNetwork == true) "Joins its Wi‑Fi when you send" else d.ip
        }
        val row = Ui.row(ctx, icon, d.name, sub, chip, onClick = { send(d) })
        // Long-press (or hold OK on a remote) to pick several devices.
        row.setOnLongClickListener { toggleMulti(d.id); true }
        return row
    }

    private fun emptyState(wifiOn: Boolean): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = ScreenKit.dp(ctx, 14)
            setPadding(p, ScreenKit.dp(ctx, 16), p, ScreenKit.dp(ctx, 16))
        }
        val dot: View = if (wifiOn) ScanTicks(ctx) else Ui.led(ctx, Led.OFF)
        box.addView(dot, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ScreenKit.dp(ctx, 14) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, if (wifiOn) "Looking for devices…" else "No Wi‑Fi network", 16f, Theme.TEXT, Theme.MEDIUM))
        col.addView(Ui.text(ctx,
            if (wifiOn) "Open FastDrop on the other device. Devices on the same Wi‑Fi or hotspot appear automatically."
            else "Join a Wi‑Fi or hotspot, or use Show my QR to connect without a router.",
            13f, Theme.MUTED).apply { setPadding(0, ScreenKit.dp(ctx, 3), 0, 0) })
        box.addView(col, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))
        return box
    }
}
