package com.fastdrop.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Receive" (docs/DESIGN.md 4.4 and 5.5): makes this device visible. Same-Wi-Fi phones already see
 * it without any code; the QR (and the Wi-Fi Direct / hotspot behind it) is for devices that are
 * not on the same network. A neutral status row with an ultramarine type plate says whether we are
 * visible (red plate when hosting fails), then the QR on a white plate, the network details, the
 * auto-accept mode with a link to Settings, and a docked "Scan sender's QR" on camera phones.
 * On TV (the "Show my QR" flow) it is two panes: status + steps on the left, a 300 dp QR on the right.
 */
class ReceiveScreen(host: MainActivity) : Screen(host) {
    private var shown = false
    private var qrMode = 0                 // 0 = FastDrop app, 1 = other devices (plain Wi-Fi QR)
    private var showPass = false
    private var lastQr: String? = null
    private var lastDetailsSig = ""
    private var lastStatusSig = ""

    private lateinit var statusBox: LinearLayout
    private lateinit var permBanner: View
    private lateinit var seg: View
    private lateinit var qrView: QrView
    private lateinit var qrCaption: TextView
    private lateinit var webRow: View
    private lateinit var detailsBox: LinearLayout
    private lateinit var ssidRow: View
    private lateinit var passRow: View
    private lateinit var ipRow: View
    private lateinit var revealBtn: ImageView
    private lateinit var acceptRow: View

    private fun dp(v: Int) = Theme.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun lp(top: Int = 0, w: Int = -1, h: Int = -2, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    override fun build(): View {
        lastQr = null; lastDetailsSig = ""; lastStatusSig = ""; showPass = false
        val c = ctx
        val wide = Ui.wide(c)

        statusBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        permBanner = buildPermBanner()
        val qrBlock = buildQrBlock()
        buildDetails()
        val accept = buildAcceptBlock()
        val tvSteps = buildSteps()

        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.header(c, s(R.string.rcv_title), { host.pop() }), lp())
        if (wide) {
            val cols = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            left.addView(statusBox, lp())
            left.addView(permBanner, lp())
            left.addView(tvSteps, lp(top = 8))
            left.addView(accept, lp(top = 16))
            right.addView(qrBlock, lp())
            right.addView(detailsBox, lp(top = 8))
            cols.addView(left, lp(w = 0, weight = 5f))
            cols.addView(right, lp(w = 0, weight = 6f))
            content.addView(cols, lp())
        } else {
            content.addView(statusBox, lp())
            content.addView(permBanner, lp())
            content.addView(qrBlock, lp())
            content.addView(detailsBox, lp(top = 8))
            content.addView(accept, lp(top = 16))
        }

        val page = Ui.scrollPage(c, content)
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val camera = QrScanActivity.hasCamera(c)
        if (camera) {
            // The dock applies the bottom inset itself.
            page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, 0)
            Ui.applyInsets(page, true, false)
        }
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        if (camera) {
            val dock = Ui.dock(c)
            dock.addView(Ui.jobButton(c, s(R.string.rcv_scan), Job.RECEIVE, R.drawable.ic_scan) { host.scanQr(false) },
                LinearLayout.LayoutParams(-1, -2))
            root.addView(dock, LinearLayout.LayoutParams(-1, -2))
        }
        refresh()
        return root
    }

    // ---------------------------------------------------------------- pieces

    private fun buildPermBanner(): View {
        val row = Ui.row(ctx, R.drawable.ic_wifi_direct, s(R.string.rcv_perm_title), s(R.string.rcv_perm_meta), Ui.chevron(ctx)) {
            if (Links.approximateOnly(ctx)) { openAppSettings(); return@row }
            host.ensureLinkPermissions(explicit = true) { ok -> if (ok) startHosting() else refresh() }
        }
        row.setBackgroundColor(Theme.SURFACE_2)
        Ui.focusFill(row, null, 1.03f, 0f, row.background)
        row.visibility = View.GONE
        return row
    }

    private fun buildQrBlock(): View {
        val c = ctx
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val g = Ui.gutter(c)
        seg = Ui.segmented(c, listOf(s(R.string.rcv_seg_app), s(R.string.rcv_seg_other)), 0) { i ->
            qrMode = i
            refresh()
        }
        box.addView(seg, lp(top = 4).apply { leftMargin = g; rightMargin = g })
        qrView = QrView(c)
        val size = dp(if (Ui.tv(c)) 300 else 240)
        box.addView(qrView, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16); bottomMargin = dp(12)
        })
        qrCaption = Ui.text(c, "", Type.BODY).apply { gravity = Gravity.CENTER }
        box.addView(qrCaption, lp().apply { leftMargin = g; rightMargin = g })
        val web = Theme.plateFor("browser")
        webRow = Ui.row(c, null, s(R.string.rcv_share_any), s(R.string.rcv_share_any_sub), Ui.chevron(c, Job.WEB),
            lead = Ui.plate(c, R.drawable.ic_globe, web.fill, web.on, if (Ui.tv(c)) 48 else 40, Job.WEB), job = Job.WEB) {
            host.showWebShare(true)
        }
        webRow.visibility = View.GONE
        box.addView(webRow, lp(top = 8))
        return box
    }

    private fun buildDetails() {
        val c = ctx
        detailsBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        detailsBox.addView(Ui.section(c, s(R.string.rcv_details)))
        val copySsid = Ui.iconButton(c, R.drawable.ic_copy, s(R.string.cd_copy_network)) {
            Links.hostInfo().ssid?.let { host.copyText(s(R.string.rcv_network), it) }
        }
        ssidRow = Ui.row(c, R.drawable.ic_wifi_direct, s(R.string.rcv_network), " ", copySsid)
        revealBtn = Ui.iconButton(c, R.drawable.ic_eye, s(R.string.cd_show_password)) {
            showPass = !showPass
            refreshDetails(force = true)
        }
        val copyPass = Ui.iconButton(c, R.drawable.ic_copy, s(R.string.cd_copy_password)) {
            Links.hostInfo().pass?.let { host.copyText(s(R.string.rcv_password), it) }
        }
        val passBtns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        passBtns.addView(revealBtn, LinearLayout.LayoutParams(revealBtn.layoutParams.width, revealBtn.layoutParams.height))
        passBtns.addView(copyPass, LinearLayout.LayoutParams(copyPass.layoutParams.width, copyPass.layoutParams.height))
        passRow = Ui.row(c, R.drawable.ic_shield, s(R.string.rcv_password), " ", passBtns)
        val copyIp = Ui.iconButton(c, R.drawable.ic_copy, s(R.string.cd_copy_address)) {
            Net.primaryIp()?.let { host.copyText(s(R.string.rcv_address), it) }
        }
        ipRow = Ui.row(c, R.drawable.ic_link, s(R.string.rcv_address), " ", copyIp)
        for (r in listOf(ssidRow, passRow, ipRow)) {
            ScreenKit.subtitleOf(r)?.typeface = Theme.MONO          // values read as technical strings
            detailsBox.addView(r, lp())
        }
    }

    /** Accept mode (with a link to Settings) and the same-Wi-Fi reassurance. */
    private fun buildAcceptBlock(): View {
        val c = ctx
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.section(c, s(R.string.rcv_accept_section)))
        acceptRow = Ui.row(c, R.drawable.ic_shield, s(R.string.rcv_accept_title), acceptLabel(), Ui.chevron(c)) { host.showSettings() }
        box.addView(acceptRow, lp())
        val g = Ui.gutter(c)
        box.addView(Ui.text(c, s(R.string.rcv_same_wifi), Type.META).apply { setPadding(g, dp(8), g, dp(8)) }, lp())
        return box
    }

    /** Numbered "how to" steps for the TV flow (the TV shows the code, the phone scans it). */
    private fun buildSteps(): View {
        val c = ctx
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (!Ui.tv(c)) { box.visibility = View.GONE; return box }
        val g = Ui.gutter(c)
        val steps = listOf(R.string.rcv_step1, R.string.rcv_step2, R.string.rcv_step3)
        steps.forEachIndexed { i, id ->
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(g, dp(8), g, dp(8))
            }
            val job = Job.RECEIVE
            val num = TextView(c).apply {
                text = (i + 1).toString()
                textSize = 16f
                typeface = Theme.MONO
                setTextColor(job.on)
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply { setColor(job.fill); cornerRadius = dp(Theme.R_CTRL.toInt()).toFloat() }
            }
            row.addView(num, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) })
            row.addView(Ui.text(c, s(id), Type.BODY), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, lp())
        }
        return box
    }

    private fun acceptLabel(): String = when (Hub.autoMode) {
        Hub.AUTO_ASK -> s(R.string.rcv_accept_ask)
        Hub.AUTO_EVERYONE -> s(R.string.rcv_accept_everyone)
        else -> s(R.string.rcv_accept_trusted)
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startHosting() {
        if (shown) Links.startHosting(ctx, "receive")
        refresh()
    }

    override fun onShow() {
        shown = true
        if (Links.hasPermissions(ctx)) startHosting() else host.ensureLinkPermissions { startHosting() }
        refresh()
    }

    override fun onHide() {
        shown = false
        Links.stopHosting("receive")
    }

    override val keepsScreenOn: Boolean get() = shown

    override fun firstFocus(): View? =
        (seg as? android.view.ViewGroup)?.let { ScreenKit.firstFocusable(it) } ?: ScreenKit.firstFocusable(detailsBox)

    // ---------------------------------------------------------------- refresh

    override fun refresh() {
        if (!::statusBox.isInitialized) return
        refreshStatus()
        permBanner.visibility =
            if (Links.p2pSupported(ctx) && Links.requiredPermissions().isNotEmpty() && !Links.hasPermissions(ctx)) View.VISIBLE else View.GONE
        if (Links.approximateOnly(ctx)) Ui.setRowText(permBanner, s(R.string.rcv_precise_title), s(R.string.rcv_precise_meta))
        Ui.setRowText(acceptRow, s(R.string.rcv_accept_title), acceptLabel())
        refreshQr()
        refreshDetails(force = false)
    }

    private fun refreshStatus() {
        val c = ctx
        val name = Hub.myName
        val starting = Links.hostState == Links.HostState.STARTING
        val failed = Links.hostState == Links.HostState.FAILED
        val mode = Links.hostMode
        val softErr = Links.hostState == Links.HostState.READY && mode == null && Links.hostError != null
        val title: String
        val meta: String
        when {
            starting -> {
                title = s(if (Links.p2pSupported(c)) R.string.rcv_starting_p2p else R.string.rcv_starting_hotspot)
                meta = s(R.string.rcv_starting_meta)
            }
            failed -> {
                title = s(R.string.rcv_failed)
                meta = Links.hostError ?: s(R.string.rcv_failed_meta)
            }
            else -> {
                title = s(R.string.rcv_visible, name)
                meta = when {
                    softErr -> Links.hostError!!
                    mode == JoinInfo.MODE_P2P -> s(R.string.rcv_state_p2p)
                    mode == JoinInfo.MODE_HOTSPOT -> s(R.string.rcv_state_hotspot)
                    else -> s(R.string.rcv_state_wifi)
                }
            }
        }
        val tag = when {
            starting || failed -> null
            mode == JoinInfo.MODE_P2P -> s(R.string.rcv_tag_p2p)
            mode == JoinInfo.MODE_HOTSPOT -> s(R.string.rcv_tag_hotspot)
            else -> s(R.string.rcv_tag_wifi)
        }
        val sig = "$title|$meta|$tag|$starting|$failed"
        if (sig == lastStatusSig) return
        lastStatusSig = sig

        statusBox.removeAllViews()
        val tvMode = Ui.tv(c)
        val g = Ui.gutter(c)
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(g, dp(12), g, dp(16))
        }
        val size = if (tvMode) 56 else 48
        val plate = when {
            failed -> Ui.plate(c, R.drawable.ic_alert, Job.DANGER.fill, Job.DANGER.on, size)
            starting -> Ui.plate(c, R.drawable.ic_wifi_direct, Theme.SURFACE_2, Theme.INK, size)
            else -> Ui.plate(c, R.drawable.ic_receive, Job.RECEIVE.fill, Job.RECEIVE.on, size)
        }
        row.addView(plate, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(16) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(c, title, Type.HERO).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val metaRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!starting) {
            metaRow.addView(Ui.led(c, if (failed) Led.DANGER else Led.RECEIVE),
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        }
        metaRow.addView(Ui.text(c, meta, Type.META), LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(metaRow, lp(top = 4))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (tag != null) row.addView(Ui.tag(c, tag), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        statusBox.addView(row, lp())
        if (failed) {
            val retry = Ui.secondaryButton(c, s(R.string.action_retry), R.drawable.ic_refresh) { startHosting() }
            statusBox.addView(retry, lp(bottom = 0).apply { leftMargin = g; rightMargin = g; bottomMargin = dp(12) })
        }
        statusBox.addView(Ui.rule(c))
    }

    private fun lp(bottom: Int, top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun openAppSettings() {
        try {
            host.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${host.packageName}")))
        } catch (_: Exception) { host.toast(s(R.string.rcv_open_android_settings), 2) }
    }

    private fun refreshQr() {
        val info = try { Links.hostInfo() } catch (_: Exception) { return }
        if (qrMode == 0) {
            setQr(info.toUri() + "&r=r")
            qrCaption.text = s(if (info.hasNetwork) R.string.rcv_caption_app else R.string.rcv_caption_app_lan)
            webRow.visibility = View.GONE
        } else {
            val wifi = info.wifiQr()
            setQr(wifi)
            qrCaption.text = s(if (wifi != null) R.string.rcv_caption_other else R.string.rcv_caption_other_none)
            webRow.visibility = View.VISIBLE
        }
    }

    private fun setQr(text: String?) {
        if (text == lastQr) return
        lastQr = text
        qrView.setText(text)
    }

    private fun refreshDetails(force: Boolean) {
        val info = try { Links.hostInfo() } catch (_: Exception) { return }
        val ip = try { Net.primaryIp() } catch (_: Exception) { null }
        val sig = "${info.ssid}|${info.pass}|${info.hostIp}|$ip|$showPass"
        if (!force && sig == lastDetailsSig) return
        lastDetailsSig = sig
        val net = info.hasNetwork
        ssidRow.visibility = if (net) View.VISIBLE else View.GONE
        passRow.visibility = if (net && !info.pass.isNullOrEmpty()) View.VISIBLE else View.GONE
        ScreenKit.subtitleOf(ssidRow)?.text = info.ssid ?: " "
        val pass = info.pass ?: ""
        ScreenKit.subtitleOf(passRow)?.text = if (showPass) pass else "•".repeat(minOf(pass.length, 12).coerceAtLeast(1))
        revealBtn.setImageResource(if (showPass) R.drawable.ic_close else R.drawable.ic_eye)
        revealBtn.contentDescription = s(if (showPass) R.string.cd_hide_password else R.string.cd_show_password)
        ipRow.visibility = if (ip != null) View.VISIBLE else View.GONE
        ScreenKit.subtitleOf(ipRow)?.text = ip ?: " "
        detailsBox.visibility = if (net || ip != null) View.VISIBLE else View.GONE
    }
}
