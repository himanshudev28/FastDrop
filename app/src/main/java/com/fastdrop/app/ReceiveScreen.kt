package com.fastdrop.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Receive": makes this device visible. Same-Wi-Fi phones already see it without any code; the QR
 * (and the Wi-Fi Direct / hotspot behind it) is for devices that are not on the same network.
 */
class ReceiveScreen(host: MainActivity) : Screen(host) {
    private var shown = false
    private var entered = false
    private var qrMode = 0                 // 0 = FastDrop app, 1 = other devices (plain Wi-Fi QR)
    private var showPass = false
    private var lastQr: String? = null
    private var lastBlipSig = ""
    private var lastDetailsSig = ""

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var permBanner: View
    private lateinit var qrView: QrView
    private lateinit var qrCaption: TextView
    private lateinit var qrAction: TextView
    private lateinit var detailsCard: LinearLayout
    private lateinit var ssidRow: View
    private lateinit var passRow: View
    private lateinit var ipRow: View
    private lateinit var revealBtn: android.widget.ImageView
    private var sections: List<View> = emptyList()
    private var firstBtn: View? = null

    private fun lp(w: Int = ScreenKit.MATCH, h: Int = ScreenKit.WRAP, bottomDp: Int = 14, weight: Float = 0f) =
        ScreenKit.lp(ctx, w, h, bottomDp, weight)

    override fun build(): View {
        entered = false; lastQr = null; lastBlipSig = ""; lastDetailsSig = ""; showPass = false
        val wide = Ui.wide(ctx)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Theme.BG)

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.header(ctx, "Receive", { host.pop() }), lp(bottomDp = 6))

        val hero = buildHero()
        val note = buildSameWifiNote()
        permBanner = buildPermBanner()
        val qrCard = buildQrCard()
        buildDetails()
        val buttons = buildButtons()

        if (wide) {
            val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = ScreenKit.dp(ctx, 24)
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            left.addView(hero, lp()); left.addView(note, lp()); left.addView(buttons, lp())
            right.addView(permBanner, lp()); right.addView(qrCard, lp()); right.addView(detailsCard, lp())
            cols.addView(left, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f).apply { marginEnd = gap / 2 })
            cols.addView(right, LinearLayout.LayoutParams(0, ScreenKit.WRAP, 1f).apply { marginStart = gap / 2 })
            content.addView(cols, lp())
            sections = listOf(hero, note, buttons, qrCard, detailsCard)
        } else {
            content.addView(hero, lp())
            content.addView(permBanner, lp())
            content.addView(qrCard, lp())
            content.addView(note, lp())
            content.addView(detailsCard, lp())
            content.addView(buttons, lp())
            sections = listOf(hero, qrCard, note, detailsCard, buttons)
        }
        root.addView(Ui.scrollPage(ctx, content, gutter = true), FrameLayout.LayoutParams(-1, -1))
        refresh()
        return root
    }

    // ---------------------------------------------------------------- pieces

    private fun buildHero(): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val pl = Theme.plateFor(Hub.myType)
        box.addView(Ui.plate(ctx, Ui.glyphFor(Hub.myType), pl.fill, pl.on, 56), lp(w = ScreenKit.dp(ctx, 56), h = ScreenKit.dp(ctx, 56), bottomDp = 12))

        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        statusDot = Ui.led(ctx, Led.OFF)
        line.addView(statusDot, LinearLayout.LayoutParams(ScreenKit.dp(ctx, 10), ScreenKit.dp(ctx, 10)).apply {
            marginEnd = ScreenKit.dp(ctx, 10)
        })
        statusText = Ui.text(ctx, "Getting ready…", 18f, Theme.TEXT, Theme.BOLD)
        line.addView(statusText)
        box.addView(line, lp(bottomDp = 4))
        statusSub = Ui.text(ctx, "", 13f, Theme.MUTED).apply { gravity = Gravity.CENTER }
        box.addView(statusSub, lp(bottomDp = 0))
        return box
    }

    private fun buildSameWifiNote(): View {
        val card = Ui.column(ctx)
        card.addView(Ui.row(ctx, R.drawable.ic_wifi, "On the same Wi‑Fi?",
            "Nearby phones see you automatically. No code needed.", null, null),
            LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        return card
    }

    private fun buildPermBanner(): View {
        val card = Ui.column(ctx)
        card.addView(Ui.row(ctx, R.drawable.ic_wifi_direct, "Allow Nearby devices",
            "Needed to link phones without a router.", null) {
            if (Links.approximateOnly(ctx)) { openAppSettings(); return@row }
            host.ensureLinkPermissions(explicit = true) { ok -> if (ok) startHosting() else refresh() }
        }, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        card.visibility = View.GONE
        return card
    }

    private fun buildQrCard(): View {
        val card = Ui.column(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val seg = Ui.segmented(ctx, listOf("FastDrop app", "Other devices"), 0) { i ->
            qrMode = i
            refresh()
        }
        card.addView(seg, lp(bottomDp = 18))
        qrView = QrView(ctx)
        val size = ScreenKit.dp(ctx, 250)
        card.addView(qrView, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ScreenKit.dp(ctx, 14)
        })
        qrCaption = Ui.text(ctx, "", 14f, Theme.MUTED).apply { gravity = Gravity.CENTER }
        card.addView(qrCaption, lp(bottomDp = 0))
        qrAction = Ui.secondaryButton(ctx, "Share with any device", R.drawable.ic_globe) { host.showWebShare(true) }
            .apply { visibility = View.GONE }
        card.addView(qrAction, lp(bottomDp = 0).apply { topMargin = ScreenKit.dp(ctx, 14) })
        return card
    }

    private fun buildDetails() {
        detailsCard = Ui.column(ctx)
        detailsCard.addView(Ui.section(ctx, "Network details").apply {
            setPadding(ScreenKit.dp(ctx, 14), ScreenKit.dp(ctx, 10), 0, ScreenKit.dp(ctx, 2))
        })
        val copySsid = Ui.iconButton(ctx, R.drawable.ic_copy, "Copy network name") {
            Links.hostInfo().ssid?.let { host.copyText("Network", it) }
        }
        ssidRow = Ui.row(ctx, R.drawable.ic_wifi_direct, "Network", " ", copySsid, null)
        revealBtn = Ui.iconButton(ctx, R.drawable.ic_eye, "Show password") {
            showPass = !showPass
            refreshDetails(force = true)
        }
        val copyPass = Ui.iconButton(ctx, R.drawable.ic_copy, "Copy password") {
            Links.hostInfo().pass?.let { host.copyText("Password", it) }
        }
        val passBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sz = revealBtn.layoutParams.width
        passBtns.addView(revealBtn, LinearLayout.LayoutParams(sz, sz).apply { marginEnd = ScreenKit.dp(ctx, 8) })
        passBtns.addView(copyPass, LinearLayout.LayoutParams(sz, sz))
        passRow = Ui.row(ctx, R.drawable.ic_shield, "Password", " ", passBtns, null)
        val copyIp = Ui.iconButton(ctx, R.drawable.ic_copy, "Copy address") {
            Net.primaryIp()?.let { host.copyText("Address", it) }
        }
        ipRow = Ui.row(ctx, R.drawable.ic_link, "Your address", " ", copyIp, null)
        for (r in listOf(ssidRow, passRow, ipRow)) {
            detailsCard.addView(r, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        }
    }

    private fun buildButtons(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (QrScanActivity.hasCamera(ctx)) {
            val b = Ui.jobButton(ctx, "Scan sender's QR", Job.RECEIVE, R.drawable.ic_scan) { host.scanQr(false) }
            col.addView(b, lp(bottomDp = 12))
            firstBtn = b
        }
        val card = Ui.column(ctx)
        card.addView(Ui.toggleRow(ctx, "Auto-accept", "Save incoming files without asking", Hub.autoAccept) {
            Hub.autoAccept = it
        }, LinearLayout.LayoutParams(ScreenKit.MATCH, ScreenKit.WRAP))
        col.addView(card, lp(bottomDp = 0))
        return col
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
        if (!entered) {
            entered = true
        }
    }

    override fun onHide() {
        shown = false
        Links.stopHosting("receive")
    }

    override val keepsScreenOn: Boolean get() = shown

    override fun firstFocus(): View? = firstBtn ?: ScreenKit.firstFocusable(detailsCard)

    // ---------------------------------------------------------------- refresh

    override fun refresh() {
        if (!::statusDot.isInitialized) return
        val st = ScreenKit.hostStatus(ctx)
        ScreenKit.fadeText(statusText, st.first)
        statusDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(st.second)
        }
        val sub = when {
            Links.hostState == Links.HostState.READY && Links.hostMode == null && Links.hostError != null -> Links.hostError!!
            Links.hostState == Links.HostState.FAILED -> Links.hostError ?: "Wi‑Fi Direct is not available"
            else -> "Visible as ${Hub.myName}"
        }
        ScreenKit.fadeText(statusSub, sub)

        ScreenKit.setShown(permBanner, Links.p2pSupported(ctx) && Links.requiredPermissions().isNotEmpty() && !Links.hasPermissions(ctx))
        if (Links.approximateOnly(ctx)) Ui.setRowText(permBanner, "Choose Precise location", "Wi-Fi Direct needs Precise location. Tap to open settings.")
        refreshQr()
        refreshDetails(force = false)
    }

    private fun openAppSettings() {
        try {
            host.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${host.packageName}")))
        } catch (_: Exception) { host.toast("Open Android Settings and allow it for FastDrop", 2) }
    }

    private fun refreshQr() {
        val info = try { Links.hostInfo() } catch (_: Exception) { return }
        if (qrMode == 0) {
            setQr(info.toUri() + "&r=r")
            ScreenKit.fadeText(qrCaption, if (info.hasNetwork)
                "Other phone: Send → Scan QR. No router needed."
            else "Other phone: Send → Scan QR. Works on the same Wi‑Fi.")
            ScreenKit.setShown(qrAction, false)
        } else {
            val wifi = info.wifiQr()
            setQr(wifi)
            ScreenKit.fadeText(qrCaption, if (wifi != null)
                "Scan with any camera to join the Wi‑Fi, then open the web page from Share with any device."
            else "No separate network right now. iPhone or PC on the same Wi‑Fi can use Share with any device.")
            ScreenKit.setShown(qrAction, true)
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
        revealBtn.contentDescription = if (showPass) "Hide password" else "Show password"
        ipRow.visibility = if (ip != null) View.VISIBLE else View.GONE
        ScreenKit.subtitleOf(ipRow)?.text = ip ?: " "
        detailsCard.visibility = if (net || ip != null) View.VISIBLE else View.GONE
    }
}
