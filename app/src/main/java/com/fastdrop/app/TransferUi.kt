package com.fastdrop.app

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The two sheets that sit on top of every screen: the transfer progress sheet (connecting,
 * running, done, failed) and the incoming-offer sheet (Accept / Decline with a countdown).
 * MainActivity feeds it Hub changes through [refresh] and [showOffer].
 */
class TransferUi(private val host: MainActivity) {

    private enum class Mode { NONE, PENDING, RUN, DONE, FAIL }

    private val ctx: Context get() = host
    private fun dp(v: Int) = Theme.dp(host, v)

    // ---------------------------------------------------------------- progress sheet state

    private val pendingKey = Any()
    private var handle: Sheets.Handle? = null
    private var panel: Panel? = null
    private var shownKey: Any? = null
    /** The Transfer (or pending marker) the user hid; the sheet stays closed for it. */
    private var dismissedFor: Any? = null
    /** A transfer the user hid while it was running; its result is shown when it ends. */
    private var hiddenWhileRunning: Transfer? = null

    val wantsTick: Boolean get() = handle != null && panel?.mode == Mode.RUN

    fun refresh() {
        var t = Hub.transfer
        val pending = Hub.pendingSendTo
        // A transfer hidden while running reports its result once it ends.
        val hidden = hiddenWhileRunning
        if (hidden != null && !hidden.running) {
            hiddenWhileRunning = null
            if (dismissedFor === hidden && Hub.transfer === hidden) dismissedFor = null
        }
        // A new send starts: forget the old finished transfer the user already dismissed.
        if (pending != null && t != null && !t.running && dismissedFor === t) {
            if (Hub.transfer === t) Hub.transfer = null
            dismissedFor = null
            t = null
        }
        if (pending == null && dismissedFor === pendingKey) dismissedFor = null
        // Hidden while connecting: stay hidden once the transfer itself starts.
        if (dismissedFor === pendingKey && t != null && t.running) { dismissedFor = t; hiddenWhileRunning = t }

        val mode: Mode
        val key: Any?
        when {
            t != null && t.running -> { mode = Mode.RUN; key = t }
            pending != null -> { mode = Mode.PENDING; key = pendingKey }
            t != null -> { mode = if (t.state == State.DONE) Mode.DONE else Mode.FAIL; key = t }
            else -> { mode = Mode.NONE; key = null }
        }
        if (mode == Mode.NONE || dismissedFor === key) {
            closeProgress()
            return
        }
        // A different transfer replaces the old sheet.
        if (handle != null && shownKey !== key && !(shownKey === pendingKey && mode == Mode.RUN)) closeProgress()
        if (handle == null) openProgress()
        shownKey = key
        panel?.update(mode, t, pending)
    }

    private fun openProgress() {
        val p = Panel()
        panel = p
        var self: Sheets.Handle? = null
        val h = Sheets.show(host.root, p.frame, true) {
            if (handle === self) {
                handle = null; panel = null
                markDismissed(shownKey)
            }
        }
        self = h
        handle = h
    }

    /** Closes the sheet from code (not a user dismissal). */
    private fun closeProgress() {
        val h = handle ?: return
        handle = null; panel = null; shownKey = null
        h.dismiss()
    }

    /** Show the sheet again after the user hid it. */
    fun reopen() {
        dismissedFor = null
        refresh()
    }

    private fun finishWith(t: Transfer?) {
        if (t != null && Hub.transfer === t && !t.running) Hub.transfer = null
        dismissedFor = t ?: pendingKey
        closeProgress()
        Hub.changed()
    }

    private fun hideSheet() {
        markDismissed(shownKey)
        closeProgress()
    }

    /** The user closed the sheet: a finished transfer is forgotten, a running one reports later. */
    private fun markDismissed(key: Any?) {
        dismissedFor = key
        val t = key as? Transfer ?: return
        if (t.running) hiddenWhileRunning = t
        else if (Hub.transfer === t) { Hub.transfer = null; dismissedFor = null; Hub.changed() }
    }

    // ---------------------------------------------------------------- progress panel

    private inner class Panel {
        val frame = FrameLayout(ctx)
        private val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        private val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        private val ring = ProgressRing(ctx)
        private val title = Ui.title(ctx, "", 22f).apply { gravity = Gravity.CENTER }
        private val status = Ui.text(ctx, "", 15f, Theme.MUTED, Theme.MEDIUM).apply { gravity = Gravity.CENTER }
        private val detail = Ui.text(ctx, "", 13f, Theme.MUTED).apply { gravity = Gravity.CENTER }
        private val names = Ui.text(ctx, "", 13f, Theme.FAINT).apply {
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        private val confetti = Confetti(ctx)
        // "Slow link" hint: the radio, not FastDrop, is usually the limit (2.4 GHz ≈ 15 MB/s max).
        private val tip = Ui.text(ctx, "", 13f, Theme.WARNING).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        private var tipChecked = false

        var mode = Mode.NONE
        private var boundTransfer: Any? = null
        private var outgoing = true

        // speed smoothing
        private var speedFor: Transfer? = null
        private var lastDone = 0L
        private var lastTime = 0L
        private var ema = 0.0

        // result extras for the DONE state
        private var openItem: Received? = null
        private var installGroup: List<Received>? = null

        init {
            ring.colors = Theme.SEND
            col.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            col.addView(ring, LinearLayout.LayoutParams(dp(176), dp(176)).apply { topMargin = dp(16) })
            col.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            col.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            col.addView(tip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            col.addView(names, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            col.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            frame.addView(col, FrameLayout.LayoutParams(-1, -2))
            frame.addView(confetti, FrameLayout.LayoutParams(-1, -1))
        }

        private fun setIf(tv: TextView, s: CharSequence) { if (tv.text.toString() != s.toString()) tv.text = s }

        fun update(newMode: Mode, t: Transfer?, pending: String?) {
            val key: Any = t ?: pendingKey
            val modeChanged = newMode != mode
            val keyChanged = key !== boundTransfer && !(boundTransfer === pendingKey && newMode == Mode.RUN)
            if (keyChanged) {
                boundTransfer = key
                outgoing = t?.outgoing ?: true
                ring.colors = if (outgoing) Theme.SEND else Theme.RECEIVE
                ring.reset()
                bindChips(t)
                bindNames(t)
                speedFor = null; ema = 0.0
                openItem = null; installGroup = null
                tipChecked = false; tip.visibility = View.GONE
            } else if (boundTransfer === pendingKey && t != null) {
                // Connecting turned into a real transfer: keep the ring, refresh chips and names.
                boundTransfer = t
                outgoing = t.outgoing
                ring.colors = if (outgoing) Theme.SEND else Theme.RECEIVE
                bindChips(t)
                bindNames(t)
            }
            if (modeChanged && (mode == Mode.DONE || mode == Mode.FAIL)) ring.reset()
            mode = newMode

            when (newMode) {
                Mode.PENDING -> {
                    if (modeChanged || keyChanged) { ring.indeterminate = true; ring.centerText = null; ring.subText = null; buildActions(newMode, t) }
                    setIf(title, "Connecting to ${pending ?: "device"}…")
                    setIf(status, Hub.pendingStatus ?: "Waiting for them to accept…")
                    status.setTextColor(Theme.MUTED)
                    val sel = Hub.selection
                    setIf(detail, if (sel.isEmpty()) "" else "${sel.size} item${if (sel.size == 1) "" else "s"} · ${Io.bytes(sel.sumOf { it.size })}")
                }
                Mode.RUN -> {
                    if (t == null) return
                    if (modeChanged || keyChanged) { ring.indeterminate = false; buildActions(newMode, t) }
                    val done = t.done.get()
                    val p = if (t.total > 0) (done.toDouble() / t.total).toFloat().coerceIn(0f, 1f) else 0f
                    ring.setProgress(p, true)
                    ring.centerText = "${(p * 100).toInt()}%"
                    val speed = smooth(t, done)
                    val eta = if (speed > 1 && t.total > 0) Io.duration(((t.total - done) / speed).toLong()) else null
                    setIf(title, if (t.outgoing) "Sending to ${t.peer}" else "Receiving from ${t.peer}")
                    setIf(status, if (speed > 1) Io.speed(speed) + (if (eta != null) " · $eta left" else "") else "Starting…")
                    status.setTextColor(Theme.MUTED)
                    setIf(detail, "${Io.bytes(done)} of ${Io.bytes(t.total)} · ${t.files} file${if (t.files == 1) "" else "s"}")
                    // Decide once, 5 s in, so the hint doesn't flicker.
                    if (!tipChecked && SystemClock.elapsedRealtime() - t.startedAt > 5_000 && speed > 1) {
                        tipChecked = true
                        val hint = if (speed < 20.0 * 1024 * 1024) slowLinkTip(t) else null
                        if (hint != null) {
                            tip.text = hint
                            tip.visibility = View.VISIBLE
                            Anim.enter(tip)
                        }
                    }
                }
                Mode.DONE -> {
                    if (t == null) return
                    if (modeChanged) {
                        ring.indeterminate = false
                        ring.setProgress(1f, false)
                        ring.centerText = null
                        ring.showSuccess()
                        Ui.haptic(frame)
                        frame.postDelayed({ if (frame.isAttachedToWindow) confetti.burst() }, 250)
                        if (!t.outgoing) findReceived(t)
                        buildActions(newMode, t)
                    }
                    val ms = (t.finishedAt - t.startedAt).coerceAtLeast(1)
                    val avg = t.total * 1000.0 / ms
                    setIf(title, if (t.outgoing) "Sent!" else "Received!")
                    setIf(status, "${if (t.outgoing) "Sent" else "Received"} ${Io.bytes(t.total)} in ${Io.duration(ms / 1000)} · ${Io.speed(avg)}")
                    status.setTextColor(Theme.SUCCESS)
                    setIf(detail, (if (t.outgoing) "To " else "From ") + t.peer)
                }
                Mode.FAIL -> {
                    if (t == null) return
                    if (modeChanged) {
                        ring.indeterminate = false
                        ring.centerText = null
                        ring.showError()
                        buildActions(newMode, t)
                    }
                    setIf(title, if (t.state == State.CANCELLED) "Cancelled" else "Transfer failed")
                    setIf(status, t.message.ifEmpty { "Something went wrong" })
                    status.setTextColor(Theme.DANGER)
                    setIf(detail, "")
                }
                Mode.NONE -> {}
            }
            detail.visibility = if (detail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        /** Why a same-network transfer is slow, if we can tell: 2.4 GHz Wi‑Fi, or our own hotspot. */
        private fun slowLinkTip(t: Transfer): String? {
            if (t.via != Device.VIA_LAN) return null       // Wi‑Fi Direct already prefers 5 GHz
            val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return null
            return try {
                if (wm.isWifiEnabled) {
                    @Suppress("DEPRECATION")
                    val f = wm.connectionInfo?.frequency ?: 0
                    if (f in 1..2999) "Slow 2.4 GHz Wi‑Fi. Switch both devices to a 5 GHz Wi‑Fi for 3–5× the speed." else null
                } else {
                    "Using your hotspot? Set its band to 5 GHz in hotspot settings for 3–5× the speed."
                }
            } catch (_: Exception) { null }
        }

        /** Exponential moving average; resets with each transfer. */
        private fun smooth(t: Transfer, done: Long): Double {
            val now = SystemClock.elapsedRealtime()
            if (t !== speedFor) {
                speedFor = t; lastDone = done; lastTime = now; ema = 0.0
            } else if (now - lastTime >= 250) {
                val inst = (done - lastDone) * 1000.0 / (now - lastTime)
                ema = if (ema == 0.0) inst else ema * 0.7 + inst * 0.3
                lastDone = done; lastTime = now
            }
            return ema
        }

        private fun bindChips(t: Transfer?) {
            chips.removeAllViews()
            val out = t?.outgoing ?: true
            chips.addView(Ui.chip(ctx, if (out) "Sending" else "Receiving",
                if (out) Theme.SEND_A else Theme.RECV_A, if (out) R.drawable.ic_send else R.drawable.ic_receive))
            val via = t?.via ?: Hub.devices.values.firstOrNull { it.name == Hub.pendingSendTo }?.via
            if (via != null) {
                chips.addView(Ui.chip(ctx, "via ${Links.linkLabel(via)}", Theme.ACCENT_2,
                    if (via == Device.VIA_P2P) R.drawable.ic_wifi_direct else R.drawable.ic_wifi),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }
        }

        private fun bindNames(t: Transfer?) {
            val list = t?.names?.takeIf { it.isNotEmpty() } ?: Hub.selection.map { it.name }
            val shown = list.take(3).map { it.substringAfterLast('/') }
            val more = (if (t != null && t.files > shown.size) t.files else list.size) - shown.size
            val txt = shown.joinToString("\n") + if (more > 0) "\n+$more more" else ""
            names.text = txt
            names.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        }

        /** For incoming transfers: which saved files can be opened or installed. */
        private fun findReceived(t: Transfer) {
            val wallStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - t.startedAt) - 3000
            val got = Hub.received.filter { it.at >= wallStart && it.from == t.peer }
            val groups = AppInstaller.groups(got)
            if (groups.isNotEmpty()) installGroup = groups[0]
            else if (got.size == 1) openItem = got[0]
        }

        private fun buildActions(m: Mode, t: Transfer?) {
            actions.removeAllViews()
            val colors = if (outgoing) Theme.SEND else Theme.RECEIVE
            fun add(v: View, first: Boolean) {
                actions.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) marginStart = dp(10) })
            }
            var focus: View? = null
            when (m) {
                Mode.PENDING, Mode.RUN -> {
                    add(Ui.secondaryButton(ctx, "Hide") { hideSheet() }, true)
                    val cancel = Ui.secondaryButton(ctx, "Cancel") {
                        Hub.cancel()
                        if (m == Mode.PENDING) hideSheet()
                    }
                    add(cancel, false)
                    focus = cancel
                }
                Mode.DONE -> {
                    val ig = installGroup
                    val oi = openItem
                    var first = true
                    if (ig != null) {
                        val b = Ui.primaryButton(ctx, "Install", Theme.RECEIVE, R.drawable.ic_install) {
                            host.installApp(ig)
                        }
                        add(b, first); first = false; focus = b
                    } else if (oi != null) {
                        val b = Ui.primaryButton(ctx, "Open", colors, null) { host.openReceived(oi) }
                        add(b, first); first = false; focus = b
                    }
                    if (focus == null) {
                        val b = Ui.primaryButton(ctx, "Done", colors, null) { finishWith(t) }
                        add(b, first); focus = b
                    } else {
                        add(Ui.secondaryButton(ctx, "Done") { finishWith(t) }, first)
                    }
                }
                Mode.FAIL -> {
                    val dev = if (t != null && t.outgoing)
                        Hub.devices.values.firstOrNull { it.name == t.peer } else null
                    val done = Ui.secondaryButton(ctx, "Done") { finishWith(t) }
                    add(done, true)
                    focus = done
                    if (dev != null && Hub.selection.isNotEmpty()) {
                        val retry = Ui.primaryButton(ctx, "Retry", Theme.SEND, R.drawable.ic_refresh) {
                            finishWith(t)
                            host.sendTo(dev)
                        }
                        add(retry, false)
                        focus = retry
                    }
                }
                Mode.NONE -> {}
            }
            val f = focus
            if (f != null && Ui.tv(ctx)) f.post { f.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- offer sheet

    private var offerHandle: Sheets.Handle? = null
    private var offerRef: IncomingOffer? = null
    private var countdown: ValueAnimator? = null

    /** Builds the Accept / Decline sheet for an incoming offer. */
    fun showOffer(offer: IncomingOffer) {
        if (offerRef === offer && offerHandle?.showing == true) return
        closeOffer()
        offerRef = offer

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // avatar inside a countdown ring
        val ring = ProgressRing(ctx).apply { colors = Theme.RECEIVE }
        ring.setProgress(1f, false)
        val avatarBox = FrameLayout(ctx)
        avatarBox.addView(ring, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        avatarBox.addView(Ui.avatar(ctx, offer.from, offer.fromType, 72, Theme.RECEIVE),
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        col.addView(avatarBox, LinearLayout.LayoutParams(dp(112), dp(112)).apply { topMargin = dp(4) })

        col.addView(Ui.title(ctx, "${offer.from} wants to send you", 22f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val n = offer.files.size
        col.addView(Ui.text(ctx, "$n file${if (n == 1) "" else "s"} · ${Io.bytes(offer.total)}", 15f, Theme.MUTED, Theme.MEDIUM)
            .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // type breakdown (top three kinds)
        var photos = 0; var videos = 0; var music = 0; var apps = 0; var other = 0
        for (f in offer.files) when (Thumbs.iconFor(f.name)) {
            R.drawable.ic_photo -> photos++
            R.drawable.ic_video -> videos++
            R.drawable.ic_music -> music++
            R.drawable.ic_apps -> apps++
            else -> other++
        }
        class Kind(val count: Int, val word: String, val color: Int, val icon: Int)
        val kinds = listOf(
            Kind(photos, "photo", Theme.CAT_PHOTOS, R.drawable.ic_photo),
            Kind(videos, "video", Theme.CAT_VIDEOS, R.drawable.ic_video),
            Kind(music, "song", Theme.CAT_MUSIC, R.drawable.ic_music),
            Kind(apps, "app", Theme.CAT_APPS, R.drawable.ic_apps),
            Kind(other, "file", Theme.CAT_FILES, R.drawable.ic_file),
        ).filter { it.count > 0 }.sortedByDescending { it.count }.take(3)
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        kinds.forEachIndexed { i, k ->
            chipRow.addView(Ui.chip(ctx, "${k.count} ${k.word}${if (k.count == 1) "" else "s"}", k.color, k.icon),
                LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
        }
        if (kinds.isNotEmpty()) col.addView(chipRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        val shown = offer.files.take(4).joinToString("\n") { it.name.substringAfterLast('/') }
        val more = if (n > 4) "\nand ${n - 4} more" else ""
        col.addView(Ui.text(ctx, shown + more, 13f, Theme.FAINT).apply {
            gravity = Gravity.CENTER; maxLines = 5; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val timer = Ui.text(ctx, "Expires in 60 s", 12f, Theme.FAINT).apply { gravity = Gravity.CENTER }
        col.addView(timer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val decline = Ui.secondaryButton(ctx, "Decline") { answerOffer(offer, false) }
        val accept = Ui.primaryButton(ctx, "Accept", Theme.RECEIVE, R.drawable.ic_check) { answerOffer(offer, true) }
        row.addView(decline, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        row.addView(accept, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        var self: Sheets.Handle? = null
        val h = Sheets.show(host.root, col, false) {
            if (offerHandle === self) {
                offerHandle = null
                stopCountdown()
                if (offerRef === offer) offerRef = null
                if (!offer.answered) offer.respond(false)
            }
        }
        self = h
        offerHandle = h
        offer.onExpired = { if (offerRef === offer) closeOffer() }
        accept.post { accept.requestFocus() }

        // Countdown ring: 60 s, matches the receiver's wait.
        val total = 60_000L
        var lastSec = -1
        countdown = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                ring.setProgress(f, false)
                val sec = (f * total / 1000f).toInt() + 1
                if (sec != lastSec) { lastSec = sec; timer.text = "Expires in $sec s" }
            }
            if (!Anim.on(ctx)) {
                // Animator scale 0 would finish instantly; keep the ring static instead.
                ring.setProgress(1f, false)
            } else start()
        }
    }

    private fun answerOffer(offer: IncomingOffer, accept: Boolean) {
        val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (accept && android.os.Build.VERSION.SDK_INT in 23..28 &&
            host.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Android 6-9 saves into Download/FastDrop, which needs this permission.
            host.ensurePermissions(arrayOf(perm)) { ok ->
                if (!ok) host.toast("Allow storage access to receive files", 2)
                offer.respond(ok)
                if (offerRef === offer) closeOffer()
            }
            return
        }
        offer.respond(accept)
        if (offerRef === offer) closeOffer()
    }

    private fun stopCountdown() {
        countdown?.cancel()
        countdown = null
    }

    private fun closeOffer() {
        stopCountdown()
        val h = offerHandle
        offerHandle = null
        offerRef = null
        h?.dismiss()
    }

    // ---------------------------------------------------------------- back + teardown

    /** Back closes the offer (declining it) or hides the progress sheet. */
    fun onBack(): Boolean {
        val o = offerRef
        if (offerHandle?.showing == true && o != null) {
            o.respond(false)
            closeOffer()
            return true
        }
        if (handle?.showing == true) {
            hideSheet()
            return true
        }
        return false
    }

    fun destroy() {
        stopCountdown()
        offerRef?.let { if (!it.answered) it.respond(false) }
    }
}
