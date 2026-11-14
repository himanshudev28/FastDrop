package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The two sheets that sit on top of every screen: the transfer sheet (a state-coloured panel with the
 * dot-matrix speed readout and a segment meter) and the incoming-offer sheet (an ultramarine, or pink
 * for text, panel with the confirmation code). MainActivity feeds it Hub changes through [refresh]
 * and [showOffer]. See docs/DESIGN.md 4.5 and 4.6.
 */
class TransferUi(private val host: MainActivity) {

    private enum class Mode { NONE, PENDING, RUN, DONE, FAIL }

    private val ctx: Context get() = host
    private fun dp(v: Int) = Theme.dp(host, v)
    private fun str(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun chars(n: Int) = ctx.resources.getQuantityString(R.plurals.text_chars, n, n)

    // ---------------------------------------------------------------- transfer sheet state

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
        val h = Sheets.show(host.root, p.frame, true, padded = false, full = false, dragArea = p.panel) {
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

    // ---------------------------------------------------------------- transfer panel

    private inner class Panel {
        val frame = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val panel = TransferPanel(ctx) { hideSheet() }
        private val tip = Ui.text(ctx, "", Type.META, Theme.INK).apply {
            setBackgroundColor(Theme.SURFACE_2)
            val g = Ui.gutter(ctx)
            setPadding(g, dp(12), g, dp(12))
            visibility = View.GONE
        }
        private val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        /** Tick per listed file row, by file index; shown once that file is finished. */
        private val rowTicks = ArrayList<View>()
        /** Per listed row: skip button (unfinished files, when the peer supports it) and "Skipped" label. */
        private val rowSkips = ArrayList<View>()
        private val rowSkipped = ArrayList<View>()
        /** Pause state and control support the action row was built for. */
        private var actionsPaused = false
        private var actionsCtl = false
        private var tickFor: Transfer? = null
        private val extras = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val g = Ui.gutter(ctx)
            setPadding(g - dp(4), dp(4), g - dp(4), 0)
        }
        private val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val g = Ui.gutter(ctx)
            setPadding(g, dp(12), g, dp(12))
        }
        private var tipChecked = false

        var mode = Mode.NONE
        private var boundTransfer: Any? = null
        private var outgoing = true

        // speed estimate (see smooth())
        private var speedFor: Transfer? = null
        private var lastDone = 0L
        private var lastTime = 0L
        private var ema = 0.0
        private var lastSpeed = 0.0
        private var shownSpeed = 0.0
        private var shownAt = 0L
        private var lastFrac = 0f
        private var lastAnnounce = 0L

        // What was being sent and to whom, remembered while it runs so Retry still works after the
        // receiver is gone (the device list may have dropped it by the time the failure shows).
        private var snapDev: Device? = null
        private var snapItems: List<SendItem> = emptyList()
        private var snapText: String? = null

        private fun snapshot(peer: String?) {
            if (peer != null) Hub.devices.values.firstOrNull { it.name == peer }?.let { snapDev = it }
            // What this send started with, not the live selection (it may be edited or cleared meanwhile).
            if (snapItems.isNotEmpty() || snapText != null) return
            snapItems = Hub.sentItems
            snapText = Hub.sentText
        }

        // result extras for the DONE state
        private var openItem: Received? = null
        private var installGroup: List<Received>? = null

        init {
            frame.addView(panel, LinearLayout.LayoutParams(-1, -2))
            frame.addView(tip, LinearLayout.LayoutParams(-1, -2))
            val scroll = ScrollView(ctx).apply {
            isFocusable = false   // a focus stop with no visible focus is a trap
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(listBox, FrameLayout.LayoutParams(-1, -2))
            }
            frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            frame.addView(Ui.rule(ctx))
            frame.addView(extras, LinearLayout.LayoutParams(-1, -2))
            frame.addView(actions, LinearLayout.LayoutParams(-1, -2))
        }

        private fun setIf(tv: TextView, s: CharSequence) { if (tv.text.toString() != s.toString()) tv.text = s }

        private fun pct(f: Float) = "${(f * 100).toInt().coerceIn(0, 100)} %"

        private fun showSpeed(bps: Double) {
            if (bps > 1) {
                val (s, mb) = DotReadout.format(bps)
                panel.readout.setText(s)
                setIf(panel.unit, str(if (mb) R.string.unit_mbs else R.string.unit_kbs))
                panel.readout.contentDescription = str(if (mb) R.string.cd_speed_mb else R.string.cd_speed_kb, s)
            } else {
                panel.readout.setText("--.-")
                setIf(panel.unit, str(R.string.unit_mbs))
                panel.readout.contentDescription = null
            }
        }

        fun update(newMode: Mode, t: Transfer?, pending: String?) {
            val key: Any = t ?: pendingKey
            val modeChanged = newMode != mode
            val keyChanged = key !== boundTransfer && !(boundTransfer === pendingKey && newMode == Mode.RUN)
            if (keyChanged) {
                boundTransfer = key
                outgoing = t?.outgoing ?: true
                bindNames(t)
                speedFor = null; ema = 0.0; lastSpeed = 0.0; lastFrac = 0f
                if (t == null || t.outgoing) { snapDev = null; snapItems = emptyList(); snapText = null }
                openItem = null; installGroup = null
                tipChecked = false; tip.visibility = View.GONE
            } else if (boundTransfer === pendingKey && t != null) {
                // Connecting turned into a real transfer: refresh the file list.
                boundTransfer = t
                outgoing = t.outgoing
                bindNames(t)
            }
            if (newMode == Mode.PENDING) snapshot(pending)
            else if (newMode == Mode.RUN && t != null && t.outgoing) { snapshot(t.peer); if (t.text != null) snapText = t.text }
            val prev = mode
            mode = newMode
            panel.hide.visibility = View.VISIBLE
            panel.crc.visibility = if (Hub.verifyCrc) View.VISIBLE else View.GONE

            when (newMode) {
                Mode.PENDING -> {
                    if (modeChanged || keyChanged || Sender.queued != actionsQueued) buildActions(newMode, t)
                    panel.setJob(Job.SEND)
                    panel.setBang(false)
                    val who = pending ?: str(R.string.device_generic)
                    val code = Hub.pendingCode
                    val sel = Hub.sentItems
                    val what = when {
                        Hub.sentText != null -> chars(Hub.sentText?.length ?: 0)
                        sel.isEmpty() -> ""
                        else -> ctx.resources.getQuantityString(R.plurals.items_size, sel.size, sel.size, Io.bytes(sel.sumOf { it.size }))
                    }
                    if (code != null) {
                        setIf(panel.title, str(R.string.title_waiting_accept, who))
                        panel.layout(readoutOn = false, meterOn = false, codeOn = true)
                        setIf(panel.codeLabel, str(R.string.code_label))
                        panel.code.setText(code)
                        panel.code.contentDescription = str(R.string.cd_code, code.toCharArray().joinToString(" "))
                        setIf(panel.codeHelp, str(R.string.code_check_sender, who))
                    } else {
                        setIf(panel.title, str(R.string.title_connecting, who))
                        panel.layout(readoutOn = false, meterOn = false, codeOn = false, waitOn = true)
                        setIf(panel.codeHelp, Hub.pendingStatus ?: str(R.string.status_waiting_accept))
                    }
                    setIf(panel.meta, listOfNotNull(Hub.multiInfo, what.ifEmpty { null }, queuedNote()).joinToString(" · "))
                    setIf(panel.note, "")
                    setIf(panel.body, "")
                }
                Mode.RUN -> {
                    if (t == null) return
                    if (modeChanged || keyChanged || Sender.queued != actionsQueued ||
                        t.paused != actionsPaused || t.canControl != actionsCtl) buildActions(newMode, t)
                    val job = if (t.outgoing) Job.SEND else Job.RECEIVE
                    panel.setJob(job)
                    panel.setBang(false)
                    panel.layout(readoutOn = true, meterOn = true, codeOn = false)
                    val done = t.done.get()          // includes bytes the receiver already had (resume)
                    val frac = if (t.total > 0) (done.toDouble() / t.total).toFloat().coerceIn(0f, 1f) else 0f
                    val base = if (t.resumed && t.total > 0) (t.baseline.toDouble() / t.total).toFloat() else -1f
                    val speed = if (t.paused) { speedFor = null; 0.0 } else smooth(t, done)
                    lastSpeed = speed; lastFrac = frac
                    val eta = if (speed > 1 && t.total > 0) Io.duration(((t.total - done).coerceAtLeast(0) / speed).toLong()) else null
                    showSpeed(speed)
                    panel.meter.setProgress(frac, base)
                    setIf(panel.percent, pct(frac))
                    val going = str(if (t.outgoing) R.string.title_sending else R.string.title_receiving, t.peer)
                    val title = if (t.paused) str(R.string.title_paused, going) else going
                    setIf(panel.title, title)
                    announce(t, title, frac, eta)
                    setIf(panel.meta, str(R.string.progress_of, Io.bytes(done.coerceAtMost(t.total)), Io.bytes(t.total)) +
                        (if (eta != null && !t.paused) " · " + str(R.string.eta_left, eta) else ""))
                    val resumed = if (t.resumed) str(R.string.resumed_at, (base * 100).toInt().coerceAtLeast(0)) else null
                    val fileOf = if (t.files > 1 && t.text == null)
                        str(R.string.transfer_file_of, (t.finishedFiles.size + t.skippedFiles.size + 1).coerceAtMost(t.files), t.files) else null
                    setIf(panel.note, listOfNotNull(Hub.multiInfo, fileOf, skippedNote(t), resumed, queuedNote()).joinToString(" · "))
                    showTicks(t)
                    setIf(panel.body, "")
                    // Decide once, 5 s in, so the hint doesn't flicker.
                    if (!tipChecked && SystemClock.elapsedRealtime() - t.startedAt > 5_000 && speed > 1) {
                        tipChecked = true
                        val hint = if (speed < 20.0 * 1024 * 1024) slowLinkTip(t) else null
                        if (hint != null) { tip.text = hint; tip.visibility = View.VISIBLE }
                    }
                }
                Mode.DONE -> {
                    if (t == null) return
                    if (modeChanged) {
                        if (!t.outgoing) findReceived(t)
                        buildActions(newMode, t)
                        Ui.haptic(frame, Haptic.CONFIRM)
                    }
                    panel.setJob(Job.OK)
                    panel.setBang(false)
                    panel.layout(readoutOn = true, meterOn = true, codeOn = false)
                    val ms = (t.finishedAt - t.startedAt).coerceAtLeast(1)
                    val moved = (t.total - t.baseline).coerceAtLeast(0)
                    val avg = moved * 1000.0 / ms
                    showSpeed(avg)
                    panel.meter.setProgress(1f, -1f)
                    setIf(panel.percent, pct(1f))
                    setIf(panel.title, when {
                        t.text != null -> str(R.string.title_sent_text, t.peer)
                        t.outgoing -> str(R.string.title_sent, t.peer)
                        else -> ctx.resources.getQuantityString(R.plurals.title_saved, t.files, t.files)
                    })
                    setIf(panel.meta, str(R.string.done_in, Io.bytes(moved), Io.duration(ms / 1000)))
                    showTicks(t)
                    setIf(panel.note, skippedNote(t) ?: ""); setIf(panel.body, "")
                    panel.hide.visibility = View.GONE
                    tip.visibility = View.GONE
                }
                Mode.FAIL -> {
                    if (t == null) return
                    if (modeChanged) {
                        buildActions(newMode, t)
                        if (t.state != State.CANCELLED) Ui.haptic(frame, Haptic.REJECT)
                    }
                    panel.hide.visibility = View.GONE
                    tip.visibility = View.GONE
                    if (t.state == State.CANCELLED) {
                        // Neutral panel: cancelled is not an error.
                        panel.setJob(null)
                        panel.setBang(false)
                        panel.layout(readoutOn = false, meterOn = false, codeOn = false)
                        setIf(panel.title, str(R.string.title_cancelled))
                        val moved = t.done.get()
                        setIf(panel.meta, str(if (t.outgoing) R.string.cancelled_sent else R.string.cancelled_received,
                            Io.bytes(moved), Io.bytes(t.total)))
                        setIf(panel.note, ""); setIf(panel.body, "")
                    } else {
                        panel.setJob(Job.DANGER)
                        panel.layout(readoutOn = true, meterOn = true, codeOn = false)
                        panel.setBang(true)
                        showSpeed(lastSpeed)
                        val frac = if (t.total > 0) (t.done.get().toDouble() / t.total).toFloat().coerceIn(0f, 1f) else lastFrac
                        panel.meter.setProgress(frac, -1f)
                        setIf(panel.percent, pct(frac))
                        setIf(panel.title, str(if (t.outgoing) R.string.title_failed_send else R.string.title_failed_receive, t.peer))
                        setIf(panel.meta, "")
                        setIf(panel.body, t.message.ifEmpty { str(R.string.error_generic) })
                        // Resume applies to a send that got part of the way.
                        val canResume = t.outgoing && t.text == null && frac > 0.01f
                        setIf(panel.note, if (canResume) str(R.string.retry_continues, (frac * 100).toInt()) else "")
                    }
                    showTicks(t)
                }
                Mode.NONE -> {}
            }
            panel.meta.visibility = if (panel.meta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            panel.note.visibility = if (panel.note.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            panel.body.visibility = if (panel.body.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (prev != mode && (mode == Mode.DONE || mode == Mode.FAIL)) panel.title.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }

        /** TalkBack: the title is a polite live region, refreshed at most every 5 s. */
        private fun announce(t: Transfer, title: String, frac: Float, eta: String?) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnnounce < 5000) return
            lastAnnounce = now
            panel.title.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            panel.title.contentDescription = str(R.string.cd_progress, title, (frac * 100).toInt(), eta ?: "")
        }

        /** Why a same-network transfer is slow, if we can tell: 2.4 GHz Wi-Fi, or our own hotspot. */
        private fun slowLinkTip(t: Transfer): String? {
            if (t.via != Device.VIA_LAN) return null       // Wi-Fi Direct already prefers 5 GHz
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return null
            return try {
                if (wm.isWifiEnabled) {
                    @Suppress("DEPRECATION")
                    val f = wm.connectionInfo?.frequency ?: 0
                    if (f in 1..2999) str(R.string.tip_slow_24) else null
                } else str(R.string.tip_hotspot)
            } catch (_: Exception) { null }
        }

        /**
         * The speed on the readout is an estimate, not a live meter: a time-weighted average over about
         * 5 s (the session average for the first 3 s, when a cold average swings), and the readout and
         * ETA change at most every 2 s. Only reads the progress counter; nothing on the send path.
         */
        private fun smooth(t: Transfer, done: Long): Double {
            val now = SystemClock.elapsedRealtime()
            if (t !== speedFor) {
                speedFor = t; lastDone = done; lastTime = now; ema = 0.0; shownSpeed = 0.0; shownAt = 0L
                return 0.0
            }
            val dt = now - lastTime
            if (dt >= 250) {
                val inst = (done - lastDone) * 1000.0 / dt
                val run = now - t.startedAt
                ema = if (run < 3000 || ema == 0.0) t.sessionDone * 1000.0 / run.coerceAtLeast(1)
                else ema + (1 - Math.exp(-dt / 5000.0)) * (inst - ema)
                lastDone = done; lastTime = now
            }
            if (shownAt == 0L || now - shownAt >= 2000) { shownSpeed = ema; shownAt = now }
            return shownSpeed
        }

        /** "2 queued": sends waiting for this transfer to end. */
        private fun queuedNote(): String? = Sender.queued.takeIf { it > 0 }?.let {
            ctx.resources.getQuantityString(R.plurals.queued_n, it, it)
        }

        /** Queue size the action row was built for (it gains "Clear queue" while sends wait). */
        private var actionsQueued = 0

        /** Ticks the listed files that are finished (all of them once the transfer is done). */
        private fun showTicks(t: Transfer) {
            if (t !== tickFor) return
            val all = t.state == State.DONE
            val ctl = t.canControl
            fun show(v: View?, on: Boolean, off: Int = View.GONE) { val w = if (on) View.VISIBLE else off; if (v != null && v.visibility != w) v.visibility = w }
            for ((i, tick) in rowTicks.withIndex()) {
                val skipped = i in t.skippedFiles
                val finished = i in t.finishedFiles || (all && !skipped)
                show(tick, finished)
                show(rowSkipped.getOrNull(i), skipped)
                show(rowSkips.getOrNull(i), ctl && !finished && !skipped)
            }
        }

        /** "2 skipped" when files were dropped from this transfer. */
        private fun skippedNote(t: Transfer): String? = t.skippedFiles.size.takeIf { it > 0 }?.let {
            ctx.resources.getQuantityString(R.plurals.skipped_n, it, it)
        }

        /** The file rows under the panel: names (sizes when known), first few only. */
        private fun bindNames(t: Transfer?) {
            listBox.removeAllViews()
            rowTicks.clear(); rowSkips.clear(); rowSkipped.clear()
            tickFor = t
            val list = t?.names?.takeIf { it.isNotEmpty() } ?: Hub.sentItems.map { it.name }
            if (list.isEmpty()) return
            val sizes = HashMap<String, Long>()
            // Sizes are known for our own sends only (incoming rows must not borrow an unrelated send's).
            val known = when { t == null -> Hub.sentItems; t.outgoing -> t.items; else -> emptyList() }
            for (s in known) sizes[s.name] = s.size
            val shown = list.take(5)
            val g = Ui.gutter(ctx)
            shown.forEachIndexed { idx, n ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(52)
                    setPadding(g, dp(8), g, dp(8))
                }
                val e = n.substringAfterLast('.', "").lowercase()
                val kind = when (Thumbs.iconFor(n)) {
                    R.drawable.ic_photo -> "photos"; R.drawable.ic_video -> "videos"
                    R.drawable.ic_music -> "music"; R.drawable.ic_apps -> "apps"; else -> "files"
                }
                val p = Theme.plateFor(kind)
                row.addView(Ui.plate(ctx, Thumbs.iconFor(n), p.fill, p.on, 36),
                    LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                row.addView(Ui.text(ctx, n.substringAfterLast('/'), Type.ROW, Theme.INK).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, -2, 1f))
                sizes[n]?.let {
                    row.addView(Ui.mono(ctx, Io.bytes(it)), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                }
                if (t != null && t.text == null) {
                    // One trailing slot: tick (finished), skip button (still to come), or "Skipped".
                    val slot = FrameLayout(ctx)
                    val tick = Ui.icon(ctx, R.drawable.ic_check, Theme.TXT_OK, 18).apply { visibility = View.GONE }
                    slot.addView(tick, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    val name = n.substringAfterLast('/')
                    val skip = Ui.iconButton(ctx, R.drawable.ic_close, str(R.string.cd_skip_file, name)) { t.userSkip(idx) }
                        .apply { visibility = View.GONE }
                    slot.addView(skip, FrameLayout.LayoutParams(skip.layoutParams.width, skip.layoutParams.height, Gravity.CENTER))
                    val gone = Ui.mono(ctx, str(R.string.label_skipped)).apply { visibility = View.GONE }
                    slot.addView(gone, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    row.addView(slot, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    rowTicks.add(tick); rowSkips.add(skip); rowSkipped.add(gone)
                }
                listBox.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val total = t?.files ?: list.size
            if (total > shown.size) {
                listBox.addView(Ui.text(ctx, str(R.string.and_n_more, total - shown.size), Type.META).apply {
                    setPadding(g, dp(6), g, dp(10))
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }

        private fun shareReceived(r: Received) {
            val uri = r.uri ?: return
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(Storage.mime(r.name))
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                host.startActivity(android.content.Intent.createChooser(i, null))
            } catch (_: Exception) { host.toast(str(R.string.err_cant_share), 2) }
        }

        private fun showInFolder() {
            try { host.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { host.toast(str(R.string.err_cant_share), 2) }
        }

        /** Send the received file straight back to the device it came from. */
        private fun sendBack(t: Transfer, r: Received) {
            val uri = r.uri ?: return
            val app = ctx.applicationContext
            kotlin.concurrent.thread(name = "send-back") {
                val item = try { Storage.itemFromUri(app, uri) } catch (_: Exception) { null }
                Hub.main.post {
                    val dev = Hub.devices.values.firstOrNull { it.name == t.peer }
                    if (item == null || dev == null) { host.toast(str(R.string.err_cant_share), 2); return@post }
                    finishWith(t)
                    Hub.selection = listOf(item)
                    host.sendTo(dev)
                }
            }
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
            actionsQueued = Sender.queued
            actionsPaused = t?.paused == true
            actionsCtl = t?.canControl == true
            actions.removeAllViews()
            extras.removeAllViews()
            extras.visibility = View.GONE
            fun add(v: View, first: Boolean) {
                actions.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) marginStart = dp(12) })
            }
            var focus: View? = null
            when (m) {
                Mode.PENDING, Mode.RUN -> {
                    val cancel = Ui.secondaryButton(ctx, str(R.string.action_cancel)) {
                        Hub.cancel()
                        if (m == Mode.PENDING) hideSheet()
                    }
                    add(cancel, true)
                    focus = cancel
                    if (m == Mode.RUN && t != null && t.canControl) {
                        val p = t.paused
                        add(Ui.jobButton(ctx, str(if (p) R.string.action_resume else R.string.action_pause),
                            if (t.outgoing) Job.SEND else Job.RECEIVE, null) { t.userPause(!p) }, false)
                    }
                    if (Sender.queued > 0) add(Ui.quietButton(ctx, str(R.string.action_clear_queue), danger = true) { Sender.clearQueue() }, false)
                }
                Mode.DONE -> {
                    val ig = installGroup
                    val oi = openItem
                    var first = true
                    if (ig != null) {
                        val b = Ui.jobButton(ctx, str(R.string.action_install), Job.OK, R.drawable.ic_install) { host.installApp(ig) }
                        add(b, first); first = false; focus = b
                    } else if (oi != null) {
                        val b = Ui.jobButton(ctx, str(R.string.action_open), Job.OK, null) { host.openReceived(oi) }
                        add(b, first); first = false; focus = b
                    }
                    // Quick follow-ups for what was just received: Share, Show in folder, Send back.
                    if (t != null && !t.outgoing && t.text == null) {
                        val item = oi ?: ig?.firstOrNull()
                        fun q(label: String, go: () -> Unit) =
                            extras.addView(Ui.quietButton(ctx, label) { go() }, LinearLayout.LayoutParams(0, -2, 1f))
                        if (item?.uri != null) q(str(R.string.action_share)) { shareReceived(item) }
                        q(str(R.string.action_show_folder)) { showInFolder() }
                        if (item?.uri != null) q(str(R.string.action_send_back)) { sendBack(t, item) }
                        extras.visibility = View.VISIBLE
                    }
                    if (t != null && t.outgoing && first) {
                        add(Ui.secondaryButton(ctx, str(R.string.action_send_more)) {
                            finishWith(t); host.openPicker(PickerActivity.TAB_PHOTOS)
                        }, true)
                        first = false
                    }
                    val done = Ui.secondaryButton(ctx, str(R.string.action_done)) { finishWith(t) }
                    add(done, first)
                    if (focus == null) focus = done
                }
                Mode.FAIL -> {
                    val cancelled = t?.state == State.CANCELLED
                    val dev = if (t != null && t.outgoing && !cancelled)
                        (t.target?.let { tg -> Hub.devices[tg.id] ?: tg } ?: t.peerId?.let { Hub.devices[it] } ?: Hub.devices.values.firstOrNull { it.name == t.peer } ?: snapDev
                            ?: Hub.sentDevs.firstOrNull { it.id == t.peerId } ?: Hub.sentDevs.firstOrNull { it.name == t.peer }) else null
                    val close = Ui.secondaryButton(ctx, str(if (cancelled) R.string.action_done else R.string.action_close)) { finishWith(t) }
                    add(close, true)
                    focus = close
                    // Retry resends what was sent, not whatever is selected now.
                    val text = t?.text ?: snapText
                    val items = if (text != null) emptyList() else t?.items?.takeIf { it.isNotEmpty() } ?: snapItems.ifEmpty { Hub.sentItems }
                    if (dev != null && (items.isNotEmpty() || text != null)) {
                        val retry = Ui.jobButton(ctx, str(R.string.action_retry), Job.SEND, R.drawable.ic_refresh) {
                            finishWith(t)
                            if (items.isNotEmpty()) Hub.selection = items
                            else if (text != null) { Hub.selection = emptyList(); Hub.selectionText = text }
                            host.sendTo(dev)
                        }
                        add(retry, false)
                        focus = retry
                    }
                }
                Mode.NONE -> {}
            }
            val f = focus
            if (f != null && (Ui.tv(ctx) || !f.isInTouchMode)) f.post { f.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- offer sheet (docs 4.5)

    private var offerHandle: Sheets.Handle? = null
    private var offerRef: IncomingOffer? = null
    private var countdownRunnable: Runnable? = null

    /** 3 dp bar along the panel's top edge, shrinking right to left in 1-second steps. */
    private class CountdownBar(ctx: Context, color: Int) : View(ctx) {
        private val p = Paint().apply { this.color = color }
        var frac = 1f
            set(v) { if (field != v) { field = v; invalidate() } }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Theme.dp(context, 3))
        override fun onDraw(c: Canvas) {
            val w = width * frac
            if (layoutDirection == LAYOUT_DIRECTION_RTL) c.drawRect(width - w, 0f, width.toFloat(), height.toFloat(), p)
            else c.drawRect(0f, 0f, w, height.toFloat(), p)
        }
    }

    /** A ScrollView that never grows taller than [maxPx] (the received text, max 60 % of the screen). */
    private class CappedScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
        init { isFocusable = false }
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    private fun kindsSummary(files: List<RxFile>): String {
        var photos = 0; var videos = 0; var music = 0; var apps = 0; var other = 0
        for (f in files) when (Thumbs.iconFor(f.name)) {
            R.drawable.ic_photo -> photos++
            R.drawable.ic_video -> videos++
            R.drawable.ic_music -> music++
            R.drawable.ic_apps -> apps++
            else -> other++
        }
        val r = ctx.resources
        val parts = ArrayList<Pair<Int, String>>()
        if (photos > 0) parts.add(photos to r.getQuantityString(R.plurals.kind_photo, photos, photos))
        if (videos > 0) parts.add(videos to r.getQuantityString(R.plurals.kind_video, videos, videos))
        if (music > 0) parts.add(music to r.getQuantityString(R.plurals.kind_song, music, music))
        if (apps > 0) parts.add(apps to r.getQuantityString(R.plurals.kind_app, apps, apps))
        if (other > 0) parts.add(other to r.getQuantityString(R.plurals.kind_file, other, other))
        parts.sortByDescending { it.first }
        return when {
            parts.size == 1 -> parts[0].second
            parts.size == 2 -> str(R.string.kinds_and, parts[0].second, parts[1].second)
            else -> r.getQuantityString(R.plurals.kind_file, files.size, files.size)
        }
    }

    /** Builds the Accept / Decline sheet for an incoming offer. */
    fun showOffer(offer: IncomingOffer) {
        if (offerRef === offer && offerHandle?.showing == true) return
        closeOffer()
        offerRef = offer

        val offerText = offer.text
        val isText = offerText != null
        val job = if (isText) Job.TEXT else Job.RECEIVE
        val g = Ui.gutter(ctx)
        val n = offer.files.size

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(job.fill)
        }
        val bar = CountdownBar(ctx, job.on)
        if (!Anim.on(ctx)) bar.visibility = View.GONE      // animations off: the countdown is text only
        top.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(g, dp(16), g, dp(20))
        }
        top.addView(head, LinearLayout.LayoutParams(-1, -2))

        // sender line: inverted plate, name, Trusted tag
        val who = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        who.addView(Ui.plate(ctx, Ui.glyphFor(offer.fromType), job.on, job.fill, 40),
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        who.addView(Ui.text(ctx, offer.from, Type.ROW, job.on).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (offer.trusted) {
            who.addView(Ui.tag(ctx, str(R.string.tag_trusted)).apply {
                setTextColor(job.on)
                background = GradientDrawable().apply {
                    setColor(0); cornerRadius = Ui.dpf(ctx, Theme.R_CTRL); setStroke(Theme.rule(ctx), job.on)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        head.addView(who, LinearLayout.LayoutParams(-1, -2))

        val what = when {
            isText -> str(if (ProtoV2.isHttpUrl(offerText ?: "")) R.string.what_link else R.string.what_text)
            else -> kindsSummary(offer.files)
        }
        val titleText = str(R.string.offer_title, offer.from, what)
        head.addView(Ui.text(ctx, titleText, Type.TITLE, job.on).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val sizeLine = if (isText) chars(offerText?.length ?: 0) else Io.bytes(offer.total)
        head.addView(Ui.text(ctx, sizeLine, Type.META, job.on),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // The confirmation code the sender is showing (untrusted offers only).
        val showCode = !offer.trusted && offer.code.isNotEmpty()
        if (showCode) {
            val codeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            codeRow.addView(Ui.text(ctx, str(R.string.code_label), Type.ROW, job.on),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
            val code = DotReadout(ctx, 5f, 3.8f, "0000").apply {
                // On the pink text panel the readout uses the ink dots; on ultramarine it is white on its shade.
                setJob(job)
                setText(offer.code)
                contentDescription = str(R.string.cd_code, offer.code.toCharArray().joinToString(" "))
            }
            codeRow.addView(code, LinearLayout.LayoutParams(-2, -2))
            head.addView(codeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            head.addView(Ui.text(ctx, str(R.string.code_check_sender, offer.from), Type.META, job.on),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // ---- neutral body
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (isText) {
            body.addView(textWell(offerText ?: "", 6), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16); marginStart = g; marginEnd = g
            })
        } else if (n > 0) {
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(g, dp(12), g, dp(4)) }
            offer.files.take(4).forEach { f ->
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
                r.addView(Ui.text(ctx, f.name.substringAfterLast('/'), Type.META, Theme.INK).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(Ui.mono(ctx, Io.bytes(f.size)), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                list.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
            if (n > 4) list.addView(Ui.text(ctx, str(R.string.and_n_more, n - 4), Type.META).apply { setPadding(0, dp(4), 0, 0) })
            body.addView(list, LinearLayout.LayoutParams(-1, -2))
        }
        if (offer.canTrust && !offer.trusted) {
            body.addView(Ui.checkRow(ctx, Job.RECEIVE, str(R.string.always_accept, offer.from), false) { offer.alwaysAccept = it },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        if (offer.dev.isNotEmpty() && !offer.trusted && Trust.get(offer.dev) != null) {
            body.addView(Ui.text(ctx, str(R.string.offer_already_trusted, offer.from), Type.META).apply { setPadding(g, dp(8), g, 0) },
                LinearLayout.LayoutParams(-1, -2))
        }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(g, dp(8), g, 0) }
        val decline = Ui.secondaryButton(ctx, str(R.string.action_decline)) { answerOffer(offer, false) }
        val accept = Ui.jobButton(ctx, str(R.string.action_accept), Job.RECEIVE, null) { answerOffer(offer, true) }
        buttons.addView(decline, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        buttons.addView(accept, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        val timer = Ui.text(ctx, str(R.string.closes_in, 60), Type.META).apply {
            gravity = Gravity.CENTER
            setPadding(g, dp(12), g, dp(16))
        }
        body.addView(timer, LinearLayout.LayoutParams(-1, -2))
        col.addView(body, LinearLayout.LayoutParams(-1, -2))
        bodyRefs = Triple(col, top, body)

        var self: Sheets.Handle? = null
        val h = Sheets.show(host.root, col, false, padded = false) {
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
        accept.post {
            accept.requestFocus()
            // TalkBack: announce once on open, then once at 10 s left.
            val code = if (showCode) str(R.string.cd_offer_code, offer.code.toCharArray().joinToString(" ")) else ""
            col.announceForAccessibility("$titleText, $sizeLine. $code")
        }

        // Countdown: 60 s, matches the receiver's wait. Steps once a second: no tween, no animator.
        val total = 60
        val start = SystemClock.elapsedRealtime()
        var announced = false
        val r = object : Runnable {
            override fun run() {
                val left = (total - (SystemClock.elapsedRealtime() - start) / 1000).toInt().coerceIn(0, total)
                bar.frac = left / total.toFloat()
                timer.text = str(R.string.closes_in, left)
                if (left == 10 && !announced) { announced = true; timer.announceForAccessibility(str(R.string.closes_in, 10)) }
                if (left > 0) Hub.main.postDelayed(this, 1000)
            }
        }
        countdownRunnable = r
        r.run()
    }

    private var bodyRefs: Triple<LinearLayout, LinearLayout, LinearLayout>? = null

    /** A SURFACE_2 well with a 4 dp pink leading block: text offers and the full received text. */
    private fun textWell(text: String, maxLines: Int): View {
        val well = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.SURFACE_2)
        }
        well.addView(View(ctx).apply { setBackgroundColor(Theme.C_TEXT) }, LinearLayout.LayoutParams(dp(4), -1))
        val shown = text.take(2000) + if (text.length > 2000) "…" else ""
        val span = SpannableString(shown)
        if (ProtoV2.isHttpUrl(text)) span.setSpan(UnderlineSpan(), 0, span.length, 0)
        well.addView(Ui.text(ctx, span, Type.BODY, Theme.INK).apply {
            if (maxLines > 0) { this.maxLines = maxLines; ellipsize = TextUtils.TruncateAt.END }
            setTextIsSelectable(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return well
    }

    /** Text accepted: the sheet changes in place to the full text with Copy / Open link and Done. */
    private fun showReceivedText(offer: IncomingOffer) {
        val refs = bodyRefs ?: return closeOffer()
        val (col, top, body) = refs
        val text = offer.text ?: return closeOffer()
        stopCountdown()
        val g = Ui.gutter(ctx)
        val isUrl = ProtoV2.isHttpUrl(text)
        // Panel: keep the colour, replace the content with a one-line title.
        top.removeAllViews()
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(g, dp(20), g, dp(20))
        }
        head.addView(Ui.text(ctx, str(if (isUrl) R.string.text_sent_link else R.string.text_sent_text, offer.from),
            Type.TITLE, Job.TEXT.on).apply { maxLines = 2 })
        top.addView(head, LinearLayout.LayoutParams(-1, -2))
        body.removeAllViews()
        val max = (ctx.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scroll = CappedScroll(ctx, max).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(textWell(text, 0), FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); marginStart = g; marginEnd = g })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(g, dp(16), g, 0) }
        var primary: View
        if (isUrl) {
            row.addView(Ui.secondaryButton(ctx, str(R.string.action_copy)) { host.copyText(str(R.string.main_label_text), text) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            primary = Ui.jobButton(ctx, str(R.string.action_open_link), Job.TEXT, null) { host.openUrl(text); closeOffer() }
            row.addView(primary, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            primary = Ui.jobButton(ctx, str(R.string.action_copy), Job.TEXT, null) { host.copyText(str(R.string.main_label_text), text) }
            row.addView(primary, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.quietButton(ctx, str(R.string.action_done)) { closeOffer() },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(g, dp(4), g, dp(12)) })
        col.requestLayout()
        primary.post { primary.requestFocus() }
    }

    private fun answerOffer(offer: IncomingOffer, accept: Boolean) {
        val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (accept && android.os.Build.VERSION.SDK_INT in 23..28 && offer.text == null &&
            host.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Android 6-9 saves into Download/FastDrop, which needs this permission.
            host.ensurePermissions(arrayOf(perm)) { ok ->
                if (!ok) host.toast(str(R.string.err_storage_permission), 2)
                offer.respond(ok)
                if (offerRef === offer) closeOffer()
            }
            return
        }
        // "Always accept" is set on the offer (by the checkbox) before the answer goes out.
        offer.respond(accept)
        if (accept && offer.text != null && offerRef === offer) showReceivedText(offer)
        else if (offerRef === offer) closeOffer()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { Hub.main.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun closeOffer() {
        stopCountdown()
        val h = offerHandle
        offerHandle = null
        offerRef = null
        bodyRefs = null
        h?.dismiss()
    }

    // ---------------------------------------------------------------- back + teardown

    /** Back closes the offer (declining it) or hides the progress sheet. */
    fun onBack(): Boolean {
        val o = offerRef
        if (offerHandle?.showing == true && o != null) {
            if (!o.answered) o.respond(false)
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
