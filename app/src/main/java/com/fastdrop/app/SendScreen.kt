package com.fastdrop.app

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
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

    /**
     * A bottom sheet with one text field on a SURFACE_2 well. Enter on the keyboard confirms too.
     * [text] puts the 4 dp pink block on the well's leading edge (text and link entry).
     */
    fun inputSheet(
        host: MainActivity, title: String, hint: String, initial: String, inputType: Int,
        okLabel: String, multiline: Boolean = false, text: Boolean = false, onOk: (String) -> Unit,
    ): Sheets.Handle {
        val ctx: Context = host
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, title, Type.TITLE), lp(ctx, bottomDp = 14))
        val et = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            this.inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
            if (multiline) { minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START } else setSingleLine()
            setTextColor(Theme.INK)
            setHintTextColor(Theme.INK_3)
            textSize = if (Ui.tv(ctx)) Type.BODY.tvSp else Type.BODY.sp
            typeface = Theme.REGULAR
            setPadding(dp(ctx, if (text) 16 else 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            if (!multiline) imeOptions = EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 6).toFloat()
                setColor(Theme.SURFACE_2)
            }
        }
        val well = FrameLayout(ctx)
        well.addView(et, FrameLayout.LayoutParams(MATCH, WRAP))
        if (text) well.addView(View(ctx).apply { setBackgroundColor(Theme.C_TEXT) },
            FrameLayout.LayoutParams(dp(ctx, 4), MATCH))
        col.addView(well, lp(ctx, bottomDp = 16))
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
        buttons.addView(Ui.secondaryButton(ctx, ctx.getString(R.string.action_cancel)) { hideKeyboard(); handle?.dismiss() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(ctx, 12) })
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

/** Intercepts the number keys 1-9 on the device list (remote or keyboard). */
private class KeyRoot(ctx: Context, val onDigit: (Int) -> Boolean) : FrameLayout(ctx) {
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val k = e.keyCode
            val n = when {
                k in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
                k in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
                else -> 0
            }
            if (n > 0 && onDigit(n)) return true
        }
        return super.dispatchKeyEvent(e)
    }
}

/**
 * "Choose devices" (docs 4.3). A summary of what is being sent with type plates and Edit, then the
 * devices as numbered ports (same Wi-Fi, Wi-Fi Direct, QR), then every other way to send or connect.
 * Long-press a port (or tap Select) for several devices. TV: ports on the right, controls on the left.
 */
class SendScreen(host: MainActivity) : Screen(host) {
    private companion object {
        const val K_KEY = 0x7f0fff31
        const val K_SIG = 0x7f0fff32
    }

    private class Entry(val key: String, val sig: String, val build: () -> View)

    private var shown = false
    private var sheet: Sheets.Handle? = null

    private lateinit var scroll: ScrollView
    private lateinit var headerBox: LinearLayout
    private lateinit var summaryBox: LinearLayout
    private lateinit var locBanner: View
    private lateinit var permBanner: View
    private lateinit var list: LinearLayout
    private lateinit var qrPanel: LinearLayout
    private lateinit var qrHolder: FrameLayout
    private lateinit var qrView: QrView
    private lateinit var qrStatus: TextView
    private lateinit var dock: LinearLayout
    private lateinit var multiBtn: TextView
    private lateinit var bottomSpacer: View
    private lateinit var leftCol: LinearLayout
    private var scanSpinner: View? = null
    private var firstAction: View? = null

    private var qrOpen = false
    private var scanning = false
    private var lastScanEmpty = false
    private var multiMode = false
    /** Devices ticked for a multi-device send (long-press a device to toggle). */
    private val multi = LinkedHashSet<String>()
    private var ordered: List<Device> = emptyList()
    private var lastSelRef: List<SendItem>? = null
    private var lastSelText: String? = null
    private var lastHeader = ""
    private var lastQr: String? = null

    private fun dp(v: Int) = ScreenKit.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun lp(w: Int = ScreenKit.MATCH, h: Int = ScreenKit.WRAP, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private val sendingText: Boolean get() = !Hub.selectionText.isNullOrEmpty()

    override fun build(): View {
        lastSelRef = null; lastSelText = null; lastHeader = ""; lastQr = null
        firstAction = null
        val wide = Ui.wide(ctx)
        val root = KeyRoot(ctx) { n -> handleDigit(n) }
        root.setBackgroundColor(Theme.BG)

        headerBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        summaryBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        locBanner = banner(s(R.string.send_loc_off), s(R.string.send_loc_off_meta)) {
            try { host.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
        }
        permBanner = banner(s(R.string.send_perm), s(R.string.send_perm_meta)) {
            if (Links.approximateOnly(ctx)) { openAppSettings(); return@banner }
            host.ensureLinkPermissions(explicit = true) { ok -> if (ok && shown) Links.startNearby(ctx); refresh() }
        }
        val other = buildOtherWays()
        buildQrPanel()

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bottomSpacer = View(ctx)
        if (wide) {
            // Two panes (docs 5.1): controls on the left, ports on the right, a 1 px rule between.
            content.addView(headerBox, lp())
            val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            leftCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            leftCol.addView(summaryBox, lp())
            leftCol.addView(other, lp(top = 8))
            right.addView(locBanner, lp()); right.addView(permBanner, lp())
            right.addView(list, lp())
            cols.addView(leftCol, lp(dp(if (Ui.tv(ctx)) 300 else 360), ScreenKit.WRAP))
            cols.addView(View(ctx).apply { setBackgroundColor(Theme.LINE) }, LinearLayout.LayoutParams(Theme.rule(ctx), -1).apply {
                marginStart = dp(16); marginEnd = dp(16)
            })
            cols.addView(right, lp(0, ScreenKit.WRAP, 1f))
            content.addView(cols, lp())
        } else {
            leftCol = content
            content.addView(headerBox, lp())
            content.addView(summaryBox, lp())
            content.addView(locBanner, lp()); content.addView(permBanner, lp())
            content.addView(list, lp())
            content.addView(other, lp(top = 8))
        }
        content.addView(bottomSpacer, lp(h = dp(24)))

        scroll = Ui.scrollPage(ctx, content)
        // The dock pads itself by the nav bar; the page keeps only the top inset.
        scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, 0)
        Ui.applyInsets(scroll, true, false)

        dock = Ui.dock(ctx).apply { visibility = View.GONE }
        multiBtn = Ui.jobButton(ctx, ctx.resources.getQuantityString(R.plurals.send_to_n, 2, 2), Job.SEND, R.drawable.ic_send) { sendMulti() }
        dock.addView(Ui.secondaryButton(ctx, s(R.string.send_clear)) { multi.clear(); refresh() },
            LinearLayout.LayoutParams(ScreenKit.WRAP, ScreenKit.WRAP).apply { marginEnd = dp(12) })
        dock.addView(multiBtn, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(dock, LinearLayout.LayoutParams(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (qrOpen) qrPanel.visibility = View.VISIBLE
        refresh()
        return root
    }

    // ---------------------------------------------------------------- pieces

    private fun renderHeader() {
        val n = multi.size
        val sig = "$multiMode|$n|${Hub.devices.isEmpty()}"
        if (sig == lastHeader) return
        lastHeader = sig
        headerBox.removeAllViews()
        val title = if (multiMode) (if (n == 0) s(R.string.send_choose_several) else s(R.string.send_n_selected, n)) else s(R.string.send_title)
        val trailing: View? = when {
            multiMode -> Ui.quietButton(ctx, s(R.string.action_cancel)) { exitMulti() }
            Hub.devices.isNotEmpty() -> Ui.quietButton(ctx, s(R.string.send_select)) { enterMulti() }
            else -> null
        }
        val back = { if (multiMode) exitMulti() else host.pop() }
        headerBox.addView(Ui.header(ctx, title, back, trailing), lp())
    }

    private fun banner(title: String, sub: String, onClick: () -> Unit): View {
        val box = FrameLayout(ctx).apply { setBackgroundColor(Theme.SURFACE_2); visibility = View.GONE }
        box.addView(Ui.row(ctx, R.drawable.ic_alert, title, sub, Ui.chevron(ctx), onClick = onClick), FrameLayout.LayoutParams(-1, -2))
        return box
    }

    /** Every other way to send or connect: always visible, one tap away. */
    private fun buildOtherWays(): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tp = Theme.plateFor("text")
        val web = Theme.plateFor("browser")
        val big = if (Ui.tv(ctx)) 48 else 40
        fun add(v: View) {
            box.addView(v, lp())
            if (firstAction == null) firstAction = v
        }
        box.addView(Ui.section(ctx, s(R.string.send_section_text)), lp())
        add(Ui.row(ctx, null, s(R.string.send_text), s(R.string.send_text_meta), Ui.chevron(ctx, Job.TEXT),
            lead = Ui.plate(ctx, R.drawable.ic_edit, tp.fill, tp.on, big, Job.TEXT), job = Job.TEXT) { typeText(Hub.selectionText.orEmpty()) })
        add(Ui.row(ctx, null, s(R.string.send_clipboard), s(R.string.send_clipboard_meta), Ui.chevron(ctx, Job.TEXT),
            lead = Ui.plate(ctx, R.drawable.ic_copy, tp.fill, tp.on, big, Job.TEXT), job = Job.TEXT) { host.sendClipboard() })

        box.addView(Ui.section(ctx, s(R.string.send_section_cant)), lp())
        if (QrScanActivity.hasCamera(ctx)) {
            add(Ui.row(ctx, R.drawable.ic_scan, s(R.string.send_scan), null, Ui.chevron(ctx)) { host.scanQr(true) })
        }
        add(Ui.row(ctx, R.drawable.ic_qr, s(R.string.send_show_qr), s(R.string.send_show_qr_meta), Ui.chevron(ctx)) { toggleQr() })
        qrHolder = FrameLayout(ctx)
        box.addView(qrHolder, lp())

        val spin = ScanTicks(ctx).apply { visibility = View.INVISIBLE }
        scanSpinner = spin
        add(Ui.row(ctx, R.drawable.ic_refresh, s(R.string.send_search), s(R.string.send_search_meta), spin) { scanNetwork() })
        add(Ui.row(ctx, R.drawable.ic_edit, s(R.string.send_enter_ip), s(R.string.send_enter_ip_meta), Ui.chevron(ctx)) { enterIp() })
        add(Ui.row(ctx, null, s(R.string.send_share_any), s(R.string.send_share_any_meta), Ui.chevron(ctx, Job.WEB),
            lead = Ui.plate(ctx, R.drawable.ic_globe, web.fill, web.on, big, Job.WEB), job = Job.WEB) { host.showWebShare(false) })
        return box
    }

    private fun buildQrPanel() {
        qrPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.gutter(ctx), dp(8), Ui.gutter(ctx), dp(16))
        }
        qrView = QrView(ctx)
        val size = dp(if (Ui.wide(ctx)) 240 else 250)
        qrPanel.addView(qrView, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
        })
        qrStatus = Ui.text(ctx, "", Type.ROW).apply { gravity = Gravity.CENTER }
        qrPanel.addView(qrStatus, lp())
        qrPanel.addView(Ui.text(ctx, s(R.string.send_qr_hint), Type.META).apply { gravity = Gravity.CENTER },
            lp(top = 4))
        qrHolder.addView(qrPanel, FrameLayout.LayoutParams(-1, -2))
    }

    // ---------------------------------------------------------------- actions

    private fun edit() {
        if (Hub.selectionText != null) typeText(Hub.selectionText.orEmpty())
        else host.openPicker(PickerActivity.TAB_PHOTOS) { refresh() }
    }

    private fun typeText(initial: String = "") {
        sheet = ScreenKit.inputSheet(host, s(R.string.send_text), s(R.string.send_text_hint), initial,
            InputType.TYPE_CLASS_TEXT, s(R.string.send_next), multiline = true, text = true) { host.setSendText(it) }
    }

    private fun enterMulti(first: String? = null) {
        multiMode = true
        first?.let { if (!multi.remove(it)) multi.add(it) }
        refresh()
    }

    private fun exitMulti() {
        multiMode = false
        multi.clear()
        refresh()
    }

    private fun toggleMulti(id: String) {
        if (!multi.remove(id)) multi.add(id)
        multiMode = true
        refresh()
    }

    private fun sendMulti() {
        val devs = multi.mapNotNull { Hub.devices[it] }
        multi.clear()
        multiMode = false
        refresh()
        host.sendToMany(devs)
    }

    private fun send(d: Device) {
        if (multiMode) { toggleMulti(d.id); return }
        if (!host.hasPayload()) {
            host.openPicker(PickerActivity.TAB_PHOTOS) {
                if (host.hasPayload()) host.sendTo(Hub.devices[d.id] ?: d)
            }
            return
        }
        host.sendTo(Hub.devices[d.id] ?: d)
    }

    /** v1 devices (FastDrop 2.0) cannot receive text. Unknown (0) is left enabled. */
    private fun blocked(d: Device) = sendingText && d.ver == 1

    private fun handleDigit(n: Int): Boolean {
        if (sheet?.showing == true) return false
        val d = ordered.getOrNull(n - 1) ?: return false
        if (blocked(d)) { host.toast(s(R.string.send_needs_new), 2); return true }
        send(d)
        return true
    }

    private fun toggleQr() {
        qrOpen = !qrOpen
        if (qrOpen) {
            val go = { if (shown && qrOpen) Links.startHosting(ctx, "send") }
            if (Links.hasPermissions(ctx)) go() else host.ensureLinkPermissions(explicit = true) { go() }
            ScreenKit.setShown(qrPanel, true)
            qrPanel.post { qrPanel.requestRectangleOnScreen(android.graphics.Rect(0, 0, qrPanel.width, qrPanel.height)) }
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
        lastScanEmpty = false
        scanSpinner?.visibility = View.VISIBLE
        host.toast(s(R.string.send_searching))
        refresh()
        Discovery.scan { n ->
            scanning = false
            lastScanEmpty = n == 0
            scanSpinner?.visibility = View.INVISIBLE
            host.toast(if (n == 0) s(R.string.send_found_none) else ctx.resources.getQuantityString(R.plurals.send_found_n, n, n), if (n == 0) 0 else 1)
            refresh()
        }
    }

    private fun enterIp() {
        val ip = try { Net.primaryIp() } catch (_: Exception) { null }
        val prefill = ip?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.plus(".") ?: ""
        sheet = ScreenKit.inputSheet(host, s(R.string.send_enter_ip), s(R.string.send_ip_hint), prefill,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, s(R.string.send_connect)) { addr ->
            host.toast(s(R.string.send_looking_at, addr))
            thread(name = "hello", isDaemon = true) {
                val dev = try { Discovery.hello(addr.trim()) } catch (_: Exception) { null }
                Hub.main.post {
                    if (dev != null) host.toast(s(R.string.send_found_one, dev.name), 1) else host.toast(s(R.string.send_no_fastdrop, addr), 2)
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
    }

    override fun onHide() {
        shown = false
        Links.stopNearby()
        Links.stopHosting("send")
        sheet?.dismiss(); sheet = null
    }

    override fun onBack(): Boolean {
        if (sheet?.showing == true) { sheet?.dismiss(); sheet = null; return true }
        if (multiMode) { exitMulti(); return true }
        if (qrOpen) { toggleQr(); return true }
        return false
    }

    override val keepsScreenOn: Boolean get() = shown && qrOpen

    override fun firstFocus(): View? =
        ScreenKit.firstFocusable(list) ?: firstAction

    // ---------------------------------------------------------------- refresh

    override fun refresh() {
        if (!::list.isInitialized) return
        val devs = Hub.devices.values.toList().sortedWith(
            compareBy<Device>({ rank(it.via) }, { it.name.lowercase() }, { it.id }))
        ordered = devs
        multi.retainAll(devs.map { it.id }.toSet())
        if (devs.isEmpty()) multiMode = false
        renderHeader()
        refreshSelection()
        refreshList(devs)
        multiBtn.text = ctx.resources.getQuantityString(R.plurals.send_to_n, multi.size.coerceAtLeast(1), multi.size)
        multiBtn.isEnabled = multi.isNotEmpty()
        dock.visibility = if (multiMode) View.VISIBLE else View.GONE
        locBanner.visibility = if (Links.locationOffBlocksDiscovery(ctx)) View.VISIBLE else View.GONE
        permBanner.visibility = if (Links.p2pSupported(ctx) && Links.requiredPermissions().isNotEmpty() && !Links.hasPermissions(ctx)) View.VISIBLE else View.GONE
        if (Links.approximateOnly(ctx)) Ui.setRowText(permBanner, s(R.string.send_precise), s(R.string.send_precise_meta))
        // Leave room for the nav bar when there is no dock to do it.
        val ins = if (dock.visibility == View.VISIBLE) 0 else Ui.currentInsets(scroll)[3]
        bottomSpacer.layoutParams = (bottomSpacer.layoutParams as LinearLayout.LayoutParams).apply { height = dp(24) + ins }
        refreshQr()
        wirePanes()
    }

    private fun openAppSettings() {
        try {
            host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${host.packageName}")))
        } catch (_: Exception) { host.toast(s(R.string.send_open_settings_manual), 2) }
    }

    private fun rank(via: String) = when (via) { Device.VIA_LAN -> 0; Device.VIA_P2P -> 1; else -> 2 }

    private fun refreshSelection() {
        val sel = Hub.selection
        val txt = Hub.selectionText
        if (sel === lastSelRef && txt == lastSelText) return
        lastSelRef = sel; lastSelText = txt
        summaryBox.removeAllViews()
        val g = Ui.gutter(ctx)

        fun head(title: String, action: String) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(g, dp(4), dp(4), 0)
            }
            row.addView(Ui.text(ctx, title, Type.TITLE).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f))
            row.addView(Ui.secondaryButton(ctx, action, R.drawable.ic_edit, true) { edit() },
                LinearLayout.LayoutParams(ScreenKit.WRAP, ScreenKit.WRAP).apply { marginStart = dp(12); marginEnd = dp(12) })
            summaryBox.addView(row, lp())
        }

        if (txt != null) {
            head(ctx.resources.getQuantityString(R.plurals.send_text_chars, txt.length, txt.length), s(R.string.send_edit))
            val well = FrameLayout(ctx).apply { setBackgroundColor(Theme.SURFACE_2) }
            well.addView(Ui.text(ctx, txt, Type.BODY).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(16), dp(12), dp(12), dp(12))
            }, FrameLayout.LayoutParams(-1, -2))
            well.addView(View(ctx).apply { setBackgroundColor(Theme.C_TEXT) }, FrameLayout.LayoutParams(dp(4), -1))
            summaryBox.addView(well, LinearLayout.LayoutParams(-1, -2).apply { setMargins(g, dp(12), g, dp(12)) })
            return
        }
        if (sel.isEmpty()) {
            head(s(R.string.send_nothing), s(R.string.send_choose))
            summaryBox.addView(Ui.text(ctx, s(R.string.send_nothing_meta), Type.META).apply { setPadding(g, dp(4), g, dp(12)) }, lp())
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
        head(ctx.resources.getQuantityString(R.plurals.items_size, count, count, Io.bytes(total)), s(R.string.send_edit))
        // One small plate per kind: what is in the selection at a glance.
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun chip(kind: String, glyph: Int, n: Int, plural: Int) {
            if (n <= 0) return
            val p = Theme.plateFor(kind)
            chips.addView(Ui.plate(ctx, glyph, p.fill, p.on, 28), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(6) })
            chips.addView(Ui.text(ctx, ctx.resources.getQuantityString(plural, n, n), Type.META),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
        }
        chip("apps", R.drawable.ic_apps, apps.size, R.plurals.kind_app)
        chip("photos", R.drawable.ic_photo, photos, R.plurals.kind_photo)
        chip("videos", R.drawable.ic_video, videos, R.plurals.kind_video)
        chip("music", R.drawable.ic_music, music, R.plurals.kind_song)
        chip("files", R.drawable.ic_file, other, R.plurals.kind_file)
        val scrollChips = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(g, dp(12), g, dp(12))
            clipToPadding = false
            addView(chips)
        }
        summaryBox.addView(scrollChips, lp())
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
        var n = 0
        fun section(key: String, title: String, group: List<Device>) {
            if (group.isEmpty()) return
            entries.add(Entry("hdr:$key", title) { Ui.section(ctx, title) })
            if (key == "lan" && Ui.tv(ctx) && hasNumberKeys()) {
                entries.add(Entry("hint", "hint") {
                    Ui.text(ctx, s(R.string.send_press_keys), Type.META).apply { setPadding(Ui.gutter(ctx), 0, Ui.gutter(ctx), dp(8)) }
                })
            }
            for (d in group) {
                n++
                val num = n
                val sig = "${d.name}|${d.ip}|${d.type}|${d.via}|${d.ver}|${d.id in multi}|$multiMode|$sendingText|$num"
                entries.add(Entry("dev:${d.id}", sig) { deviceRow(d, num) })
            }
        }
        section("lan", s(R.string.send_section_lan), devs.filter { it.via == Device.VIA_LAN })
        section("p2p", s(R.string.send_section_p2p), devs.filter { it.via == Device.VIA_P2P })
        section("qr", s(R.string.send_section_qr), devs.filter { it.via == Device.VIA_QR })
        if (entries.isEmpty()) {
            // Any local network counts (Wi-Fi, our own hotspot, Ethernet), not only Wi-Fi being on.
            val online = Links.wifiEnabled(ctx) || try { Net.localAddrs().isNotEmpty() } catch (_: Exception) { true }
            val sig = "empty|$online|$scanning|$lastScanEmpty"
            entries.add(Entry("hdr:near", s(R.string.send_section_lan)) { Ui.section(ctx, s(R.string.send_section_lan)) })
            entries.add(Entry("empty", sig) { emptyState(online) })
        }
        reconcile(entries)
    }

    private fun hasNumberKeys() = try {
        android.view.KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_1)
    } catch (_: Throwable) { false }

    /** Updates the list in place so unchanged rows (and TV focus) are left alone. */
    private fun reconcile(desired: List<Entry>) {
        fun keyOf(v: View) = v.getTag(K_KEY) as? String
        val want = HashSet<String>().also { st -> desired.forEach { st.add(it.key) } }
        val existing = ArrayList<View>()
        for (i in 0 until list.childCount) existing.add(list.getChildAt(i))
        val byKey = HashMap<String, View>()
        for (v in existing) keyOf(v)?.let { byKey[it] = v }

        var focusKey: String? = null
        for (v in existing) {
            val k = keyOf(v)
            if (k != null && k in want) continue
            list.removeView(v)
        }
        // Rebuild changed rows and fix the order; unchanged rows keep their view (and focus).
        val existingOrder = existing.mapNotNull { keyOf(it) }.filter { it in want }
        val desiredOrder = desired.map { it.key }.filter { byKey.containsKey(it) }
        val reordered = existingOrder != desiredOrder
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

    /** TV: horizontal moves go between panes (right = first port, left = first control). */
    private fun wirePanes() {
        if (!Ui.wide(ctx)) return
        val ports = ArrayList<View>()
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v.isFocusable && v.isEnabled) ports.add(v)
        }
        val firstPort = ports.firstOrNull()
        val firstLeft = ScreenKit.firstFocusable(leftCol)
        if (firstPort != null) {
            if (firstPort.id == View.NO_ID) firstPort.id = View.generateViewId()
            fun walk(g: ViewGroup) {
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.isFocusable) c.nextFocusRightId = firstPort.id
                    if (c is ViewGroup) walk(c)
                }
            }
            walk(leftCol)
        }
        if (firstLeft != null) {
            if (firstLeft.id == View.NO_ID) firstLeft.id = View.generateViewId()
            for (p in ports) p.nextFocusLeftId = firstLeft.id
        }
    }

    private fun kindLabel(type: String) = when (type) {
        "tv" -> s(R.string.type_tv)
        "phone" -> s(R.string.type_phone)
        else -> s(R.string.type_computer)
    }

    private fun deviceRow(d: Device, number: Int): View {
        val picked = d.id in multi
        val off = blocked(d)
        val tags = ArrayList<String>()
        if (try { Trust.keyFor(d.id) != null } catch (_: Exception) { false }) tags.add(s(R.string.tag_trusted))
        tags.add(s(when (d.via) { Device.VIA_LAN -> R.string.link_wifi; Device.VIA_P2P -> R.string.link_direct; else -> R.string.link_qr }))
        val meta = when {
            off -> s(R.string.send_needs_new)
            d.via == Device.VIA_LAN -> s(R.string.meta_type_ip, kindLabel(d.type), d.ip)
            d.via == Device.VIA_P2P -> s(R.string.send_direct_meta)
            else -> if (d.join?.hasNetwork == true) s(R.string.send_qr_joins) else s(R.string.meta_type_ip, kindLabel(d.type), d.ip)
        }
        val trailing = if (multiMode && !off) checkBox(picked) else null
        val row = Ui.portRow(ctx, number, d, meta, if (off) emptyList() else tags, trailing) { send(d) }
        row.isActivated = picked
        row.tag = d.id
        row.contentDescription = "${d.name}, ${kindLabel(d.type)}, ${tags.joinToString(", ")}" + if (off) ", ${s(R.string.send_needs_new)}" else ""
        if (off) {
            row.alpha = 0.4f
            row.isEnabled = false
            row.isFocusable = false
            row.isClickable = false
        } else {
            // Long-press (or hold OK on a remote) to pick several devices.
            row.setOnLongClickListener { Ui.haptic(it, Haptic.TICK); if (!multiMode) enterMulti(d.id) else toggleMulti(d.id); true }
        }
        return row
    }

    /** 22 dp square: outline when off, ink check when on (the row behind is orange while checked). */
    private fun checkBox(on: Boolean): View {
        val size = dp(22)
        val f = FrameLayout(ctx)
        f.layoutParams = LinearLayout.LayoutParams(size, size)
        f.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setStroke(Theme.rule(ctx) * 2, if (on) Theme.ON_DARK else Theme.LINE_STRONG)
        }
        if (on) f.addView(Ui.icon(ctx, R.drawable.ic_check, Theme.ON_DARK, 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        return f
    }

    private fun emptyState(online: Boolean): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(64)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.gutter(ctx), dp(8), Ui.gutter(ctx), dp(8))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title: String
        val meta: String
        if (!online) {
            head.addView(Ui.led(ctx, Led.OFF), LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
            title = s(R.string.send_no_network)
            meta = s(R.string.send_no_network_meta)
        } else {
            head.addView(ScanTicks(ctx), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
            title = if (lastScanEmpty && !scanning) s(R.string.send_found_none) else s(R.string.send_looking)
            meta = if (lastScanEmpty && !scanning) s(R.string.send_none_meta) else s(R.string.send_looking_meta)
        }
        head.addView(Ui.text(ctx, title, Type.ROW))
        box.addView(head)
        box.addView(Ui.text(ctx, meta, Type.META).apply { setPadding(0, dp(2), 0, 0) })
        return box
    }
}
