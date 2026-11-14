package com.fastdrop.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * Single-activity shell. Holds a back stack of [Screen]s, animates between them, hosts sheets
 * and snackbars on [root], and turns Hub events (changes, offers, messages) into UI.
 */
class MainActivity : Activity(), Hub.Listener {

    private companion object {
        const val REQ_PICK = 21
        const val REQ_SCAN = 22
        const val PERM_BASE = 100
        const val PERM_SPAN = 100
    }

    /** Host for screens, sheets and snackbars. */
    lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var transferUi: TransferUi

    private val stack = ArrayList<Screen>()
    private val views = HashMap<Screen, View>()
    val current: Screen? get() = stack.lastOrNull()

    private var started = false
    private var shownScreen: Screen? = null
    private var ticking = false
    private var keepOn = false

    private var pickThen: (() -> Unit)? = null
    private var scanForSend = true
    private var linkAsked = false
    private val confirmHandles = ArrayList<Sheets.Handle>()
    private val permCallbacks = HashMap<Int, (Boolean) -> Unit>()
    private var nextPermCode = PERM_BASE

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(applicationContext)
        Theme.resolve(this)
        Hub.listener = this
        TransferService.start(this)

        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        setupWindow()
        setContentView(root)
        Theme.applyWindow(this)

        transferUi = TransferUi(this)
        registerBack()
        push(HomeScreen(this))

        if (savedInstanceState == null) {
            val perms = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            // Android 17 local-network permission (no-op unless targetSdk >= 37, see LocalNetwork.kt)
            perms.addAll(LocalNetwork.toRequest(this))
            if (perms.isNotEmpty()) ensurePermissions(perms.toTypedArray()) { Discovery.nudge() }
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        showPendingOffer()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Theme.resolve(this)
        Theme.applyWindow(this)
        root.setBackgroundColor(Theme.BG)
        // Rebuild the visible screen for the new size; its state lives in its fields.
        val s = current ?: return
        // D-pad: remember where focus was (child-index path) and put it back on the rebuilt twin.
        val old = views.remove(s)
        var path: ArrayList<Int>? = null
        if (old != null) {
            val f = old.findFocus()
            if (f != null) {
                val p = ArrayList<Int>()
                var v: View = f
                while (v !== old) {
                    val par = v.parent as? ViewGroup ?: break
                    p.add(0, par.indexOfChild(v)); v = par
                }
                if (v === old) path = p
            }
            container.removeView(old)
        }
        val nv = buildView(s)
        container.addView(nv, FrameLayout.LayoutParams(-1, -1))
        s.refresh()
        path?.let { p ->
            nv.post {
                var v: View = nv
                for (i in p) { v = (v as? ViewGroup)?.getChildAt(i) ?: return@post }
                if (v.isFocusable) v.requestFocus()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        Hub.listener = this
        current?.let { show(it) }
        refreshAll()
        ensureTick()
        showPendingOffer()
    }

    override fun onStop() {
        started = false
        Hub.main.removeCallbacks(tick)
        ticking = false
        current?.let { hide(it) }
        updateKeepScreenOn()
        super.onStop()
    }

    override fun onDestroy() {
        Hub.main.removeCallbacks(tick)
        transferUi.destroy()
        if (Hub.listener === this) Hub.listener = null
        if (!isChangingConfigurations) {
            // The activity is gone for good: nothing could stop a share that keeps running for it.
            thread(name = "stop-share", isDaemon = true) { try { WebShare.stop() } catch (_: Exception) { } }
            Links.stopHosting("web")
        }
        TransferService.stopIfIdle()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- window + back

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val w = window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        try {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = android.window.OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    /** Back order: sheets, then the screen itself, then the back stack, then leave the app. */
    private fun handleBack() {
        // A confirm sheet sits above the progress / offer sheets, so it goes first.
        if (dismissTopSheet()) return
        if (transferUi.onBack()) return
        if (Sheets.dismissTop(root)) return
        val c = current
        if (c != null && c.onBack()) return
        if (stack.size > 1) pop() else finish()
    }

    private fun dismissTopSheet(): Boolean {
        confirmHandles.removeAll { !it.showing }
        val h = confirmHandles.lastOrNull() ?: return false
        h.dismiss()
        confirmHandles.remove(h)
        return true
    }

    // ---------------------------------------------------------------- screen stack

    fun push(s: Screen) {
        val old = current
        if (old === s) return
        // Double taps create a second copy of the same screen; ignore them.
        if (old != null && old::class == s::class) return
        stack.add(s)
        swap(old, s, true)
    }

    fun pop() {
        if (stack.size <= 1) return
        val old = stack.removeAt(stack.size - 1)
        swap(old, current, false)
    }

    private fun buildView(s: Screen): View {
        val v = s.build()
        views[s] = v
        return v
    }

    private fun swap(out: Screen?, into: Screen?, forward: Boolean) {
        // Show the incoming screen before hiding the outgoing one, so a hosted link that both
        // want (Receive -> Web share and back) keeps its owner count above zero and survives.
        val outWasShown = out != null && shownScreen === out
        if (outWasShown) shownScreen = null
        if (out != null) {
            val v = views.remove(out)
            if (v != null) {
                if (v is ViewGroup) v.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                Anim.screenOut(v, forward) { container.removeView(v) }
            }
        }
        if (into != null) {
            val v = buildView(into)
            container.addView(v, FrameLayout.LayoutParams(-1, -1))
            Anim.screenIn(v, forward)
            if (started) show(into)
            if (Ui.tv(this) || !container.isInTouchMode) v.post { into.firstFocus()?.requestFocus() }
        }
        if (outWasShown) out?.onHide()
        updateKeepScreenOn()
        ensureTick()
    }

    private fun show(s: Screen) {
        if (shownScreen === s) return
        shownScreen = s
        s.onShow()
    }

    private fun hide(s: Screen) {
        if (shownScreen !== s) return
        shownScreen = null
        s.onHide()
    }

    // ---------------------------------------------------------------- refresh + tick

    private val tick = object : Runnable {
        override fun run() {
            refreshAll(ticked = true)
            if (needsTick()) Hub.main.postDelayed(this, 250) else ticking = false
        }
    }

    private fun needsTick() = started && (current?.wantsTick == true || transferUi.wantsTick)

    private fun ensureTick() {
        if (ticking || !needsTick()) return
        ticking = true
        Hub.main.postDelayed(tick, 250)
    }

    private fun refreshAll(ticked: Boolean = false) {
        if (!::transferUi.isInitialized) return
        val c = current
        if (c != null && (!ticked || c.wantsTick)) c.refresh()
        transferUi.refresh()
        updateKeepScreenOn()
    }

    /** Keeps the display awake during transfers and while a hosting screen waits for a peer. */
    fun updateKeepScreenOn() {
        val busy = Hub.transfer?.running == true || Hub.pendingSendTo != null ||
            (started && current?.keepsScreenOn == true)
        if (busy != keepOn) {
            keepOn = busy
            if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- Hub.Listener

    override fun onChanged() {
        refreshAll()
        ensureTick()
    }

    override fun onMessage(msg: String) = toast(msg)

    override fun onOffer(offer: IncomingOffer) {
        // Server posts the background notification itself; here we only show the sheet.
        transferUi.showOffer(offer)
    }

    private fun showPendingOffer() {
        val o = Hub.offer ?: return
        if (!o.answered) transferUi.showOffer(o)
    }

    // ---------------------------------------------------------------- public API for screens

    fun toast(msg: String, kind: Int = 0) {
        if (!::root.isInitialized) return
        Ui.snackbar(root, msg, kind)
    }

    fun copyText(label: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            toast(getString(R.string.main_copied, label), 1)
        } catch (_: Exception) {
            toast(getString(R.string.main_cant_copy), 2)
        }
    }

    /** Reopens the transfer sheet after the user hid it. */
    fun showTransfer() = transferUi.reopen()

    fun showSettings() {
        if (current is SettingsScreen) return
        push(SettingsScreen(this))
    }

    /** Opens the History screen. */
    fun openHistory() = HistoryScreen.show(this)

    fun showWebShare(includeApp: Boolean) = push(WebShareScreen(this, includeApp))

    @Suppress("DEPRECATION")
    fun openPicker(tab: String = PickerActivity.TAB_PHOTOS, keep: Boolean = false, then: (() -> Unit)? = null) {
        pickThen = then
        // A fresh pick replaces the selection, and cancelling it leaves nothing old behind.
        if (!keep) { Hub.selection = emptyList(); Hub.selectionText = null }
        try {
            startActivityForResult(PickerActivity.intent(this, tab, keep), REQ_PICK)
        } catch (_: Exception) {
            pickThen = null
            toast(getString(R.string.main_cant_open_picker), 2)
        }
    }

    /** Home "Send": nothing picked yet means pick first, otherwise straight to device choice. */
    fun startSend() {
        if (!hasPayload()) openPicker(PickerActivity.TAB_PHOTOS)
        else if (current !is SendScreen) push(SendScreen(this))
    }

    @Suppress("DEPRECATION")
    fun scanQr(forSend: Boolean) {
        if (!QrScanActivity.hasCamera(this)) { toast(getString(R.string.main_no_camera), 2); return }
        scanForSend = forSend
        try {
            startActivityForResult(
                QrScanActivity.intent(this, if (forSend) "Scan the receiver's code" else "Scan the sender's code"),
                REQ_SCAN)
        } catch (_: Exception) {
            toast(getString(R.string.main_cant_open_camera), 2)
        }
    }

    fun handleJoin(info: JoinInfo, forSend: Boolean) {
        if (forSend) {
            val dev = Links.deviceFromJoin(info)
            Hub.upsertDevice(dev)
            sendTo(dev)
        } else {
            val go = {
                toast(getString(R.string.main_connecting_to, info.name))
                Links.joinAsync(this, info) { ok, msg -> toast(msg, if (ok) 1 else 2) }
            }
            if (info.hasNetwork) ensureLinkPermissions { go() } else go()
        }
    }

    /** Something to send: picked files, or text / a link / the clipboard. */
    fun hasPayload(): Boolean = Hub.selection.isNotEmpty() || !Hub.selectionText.isNullOrEmpty()

    fun sendTo(d: Device) {
        if (!hasPayload()) {
            openPicker(PickerActivity.TAB_PHOTOS) { sendTo(d) }
            return
        }
        if (d.needsLink) ensureLinkPermissions { startSending(listOf(d)) } else startSending(listOf(d))
    }

    /** Several devices, one transfer after another, then a "Sent to 2 of 3 devices" summary. */
    fun sendToMany(devs: List<Device>) {
        if (devs.isEmpty()) return
        if (!hasPayload()) { toast(getString(R.string.main_choose_first), 2); return }
        if (devs.any { it.needsLink }) ensureLinkPermissions { startSending(devs) } else startSending(devs)
    }

    private fun startSending(devs: List<Device>) {
        val text = Hub.selectionText?.takeIf { it.isNotEmpty() }
        val sel = if (text != null) emptyList() else Hub.selection
        if (sel.isEmpty() && text == null) return
        Hub.sentItems = sel
        Hub.sentText = text
        Hub.sentDevs = devs
        // Sent from outside "Send to" (Direct Share, Retry, Send back): nothing on screen holds this
        // selection, so drop it now or Home "Send" would offer the same files again later.
        if (current !is SendScreen) { Hub.selection = emptyList(); Hub.selectionText = null }
        Sender.sendMany(applicationContext, devs, sel, text)
    }

    /** Makes [text] the thing to send (instead of files) and opens "Send to". */
    fun setSendText(text: String) {
        if (text.isBlank()) { toast(getString(R.string.main_nothing_to_send), 2); return }
        if (text.codePointCount(0, text.length) > ProtoV2.MAX_TEXT) { toast(getString(R.string.main_text_too_long), 2); return }
        Hub.selection = emptyList()
        Hub.selectionText = text
        if (current is SendScreen) current?.refresh() else push(SendScreen(this))
    }

    fun clipboardText(): String? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun sendClipboard() {
        val t = clipboardText() ?: return toast(getString(R.string.main_clipboard_empty), 2)
        setSendText(t)
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast(getString(R.string.main_no_app_link), 2) }
    }

    // ---------------------------------------------------------------- permissions

    fun ensurePermissions(perms: Array<String>, then: (granted: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 23) { then(true); return }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { then(true); return }
        val code = nextPermCode
        nextPermCode = PERM_BASE + (nextPermCode - PERM_BASE + 1) % PERM_SPAN
        permCallbacks[code] = then
        requestPermissions(missing.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val cb = permCallbacks.remove(requestCode) ?: return
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val ok = if (Build.VERSION.SDK_INT in 23..32 && permissions.contains(fine))
            Links.hasPermissions(this)     // Android 12 "Approximate" grants only COARSE: not enough
        else grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        cb(ok)
    }

    /** Explains Wi-Fi Direct permissions once per run, then asks. Same-Wi-Fi use never depends on it. */
    fun ensureLinkPermissions(explicit: Boolean = false, then: (granted: Boolean) -> Unit) {
        val perms = Links.requiredPermissions()
        if (perms.isEmpty() || Links.hasPermissions(this)) { then(true); return }
        if (explicit) {
            // The user tapped something that needs it: always ask, and send them to app settings
            // once Android will not show the prompt any more.
            linkAsked = true
            ensurePermissions(perms) { ok ->
                if (!ok && Build.VERSION.SDK_INT >= 23 && perms.none { shouldShowRequestPermissionRationale(it) }) {
                    openAppSettings()
                }
                then(ok)
            }
            return
        }
        if (linkAsked) { then(false); return }
        linkAsked = true
        val body = if (Build.VERSION.SDK_INT >= 33)
            getString(R.string.main_nearby_why_33)
        else
            getString(R.string.main_nearby_why_loc)
        confirm(getString(R.string.main_find_nearby), body, getString(R.string.main_continue), getString(R.string.main_not_now)) { ok ->
            if (ok) ensurePermissions(perms) { then(it) } else then(false)
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast(getString(R.string.main_open_android_settings), 2)
        }
    }

    // ---------------------------------------------------------------- dialogs

    /** Small bottom sheet with a message and one or two buttons. [onResult] gets true for the main button. */
    fun confirm(title: String, body: String, positive: String, negative: String? = getString(R.string.main_not_now), onResult: (Boolean) -> Unit) {
        var decided = false
        var h: Sheets.Handle? = null
        fun answer(ok: Boolean) {
            if (decided) return
            decided = true
            h?.dismiss()
            onResult(ok)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, title, Type.TITLE))
        col.addView(Ui.text(this, body, Type.BODY, Theme.INK_2).apply {
            setPadding(0, Theme.dp(this@MainActivity, 8), 0, Theme.dp(this@MainActivity, 20))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (negative != null) {
            row.addView(Ui.secondaryButton(this, negative) { answer(false) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Theme.dp(this@MainActivity, 10) })
        }
        val pos = Ui.jobButton(this, positive, Job.SEND) { answer(true) }
        row.addView(pos, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)
        val handle = Sheets.show(root, col, true) {
            if (!decided) { decided = true; onResult(false) }
        }
        h = handle
        confirmHandles.add(handle)
        pos.post { pos.requestFocus() }
    }

    /** Text input as a themed bottom sheet (same look as the rest of the app; Enter confirms). */
    fun inputDialog(title: String, initial: String, inputType: Int, onOk: (String) -> Unit) {
        val multiline = inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        ScreenKit.inputSheet(this, title, "", initial, inputType, getString(android.R.string.ok), multiline, onOk = onOk)
    }

    // ---------------------------------------------------------------- received files

    fun openReceived(r: Received) {
        r.text?.let { copyText(getString(R.string.main_label_text), it); return }
        if (AppInstaller.isApk(r.name)) {
            val group = AppInstaller.groups(Hub.received.toList()).firstOrNull { g -> g.any { it === r } }
            installApp(group ?: listOf(r))
            return
        }
        val uri = r.uri ?: return toast(getString(R.string.main_file_unavailable), 2)
        // Pre-Android-10 saves give file:// links; allow handing them to a viewer.
        if (uri.scheme == "file" && Build.VERSION.SDK_INT in 24..28) {
            try { StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build()) } catch (_: Exception) { }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, Storage.mime(r.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.main_no_app_file), 2)
        } catch (_: Exception) {
            toast(getString(R.string.main_cant_open_file), 2)
        }
    }

    fun installApp(group: List<Received>) {
        if (!AppInstaller.canInstall(this)) {
            confirm(getString(R.string.main_allow_installs),
                getString(R.string.main_allow_installs_body),
                getString(R.string.main_open_settings)) { ok -> if (ok) AppInstaller.openInstallSettings(this) }
            return
        }
        AppInstaller.install(this, group) { ok, msg ->
            // The system installer and the result receiver report success; only show early failures.
            if (!ok) toast(msg, 2)
        }
    }

    // ---------------------------------------------------------------- results + intents

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> {
                val then = pickThen
                pickThen = null
                if (resultCode == RESULT_OK && hasPayload()) {   // files or text from the Text tab
                    if (then != null) then()
                    else if (current !is SendScreen) push(SendScreen(this))
                    else current?.refresh()
                }
            }
            REQ_SCAN -> {
                if (resultCode != RESULT_OK) return
                val raw = data?.getStringExtra(QrScanActivity.EXTRA_TEXT)
                val info = JoinInfo.parse(raw)
                // r=s: the code belongs to a sender (we join and receive); r=r: a receiver (we send).
                val forSend = when (codeRole(raw)) { "s" -> false; "r" -> true; else -> scanForSend }
                if (info == null) toast(getString(R.string.main_not_fastdrop_code), 2) else handleJoin(info, forSend)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Widget / Quick Settings tile entry points (see Integrations.kt)
        when (intent.getStringExtra(Integrations.EXTRA_OPEN)) {
            Integrations.OPEN_SEND -> { setIntent(Intent()); startSend(); return }
            Integrations.OPEN_RECEIVE -> { setIntent(Intent()); if (current !is ReceiveScreen) push(ReceiveScreen(this)); return }
        }
        when (intent.action) {
            // Launcher long-press shortcut for a recent device: pick files if needed, then send to it
            Shortcuts.ACTION_SEND_TO_DEVICE -> {
                val dev = Shortcuts.fromIntent(this, intent)
                Shortcuts.reportUsed(this, intent)
                setIntent(Intent())
                if (dev != null) sendTo(dev) else startSend()
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> handleShare(intent)
            Intent.ACTION_VIEW -> {
                val raw = intent.dataString
                val info = JoinInfo.parse(raw) ?: return
                setIntent(Intent())
                confirmJoinLink(info, codeRole(raw))
            }
        }
    }

    /** Optional role marker in our own codes: "s" = shown by a sender, "r" = shown by a receiver. */
    private fun codeRole(raw: String?): String? = try {
        if (raw == null) null else Uri.parse(raw.trim()).getQueryParameter("r")
    } catch (_: Exception) { null }

    private fun isPrivateIp(ip: String): Boolean {
        val p = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4) return false
        val a = p[0]; val b = p[1]
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /** Drops public addresses from a link opened from outside the app; null if nothing usable is left. */
    private fun localOnly(info: JoinInfo): JoinInfo? {
        val lan = info.lanIps.filter { isPrivateIp(it) }
        val hostIp = info.hostIp?.takeIf { isPrivateIp(it) }
        if (lan.isEmpty() && hostIp == null && !info.hasNetwork) return null
        return info.copy(lanIps = lan, hostIp = hostIp)
    }

    /** A fastdrop://join link from another app or the system camera never acts without a yes. */
    private fun confirmJoinLink(raw: JoinInfo, role: String?) {
        val info = localOnly(raw) ?: return toast(getString(R.string.main_link_not_local), 2)
        val ip = info.hostIp ?: info.lanIps.firstOrNull()
        val who = if (ip != null) "${info.name} ($ip)" else info.name
        val net = if (info.hasNetwork) getString(R.string.main_also_joins, info.ssid) else ""
        if (role == "s") {
            confirm(getString(R.string.main_connect_to, info.name), getString(R.string.main_join_body, who, net), getString(R.string.main_connect), getString(R.string.main_cancel)) { ok ->
                if (ok) handleJoin(info, false)
            }
            return
        }
        val n = Hub.selection.size
        val body = if (n == 0) getString(R.string.main_choose_files_to, who, net)
        else resources.getQuantityString(R.plurals.main_send_n_items_to, n, n, who, net)
        confirm(getString(R.string.main_send_to, info.name), body, getString(if (n == 0) R.string.main_choose_files else R.string.main_send), getString(R.string.main_cancel)) { ok ->
            if (ok) handleJoin(info, true)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent) {
        val uris = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            val u = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) uris.add(u)
        } else {
            val l = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (l != null) uris.addAll(l)
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris.add(it) } }
        }
        // Direct Share: the user picked a recent device in the share sheet -> send straight to it.
        val target = Shortcuts.fromIntent(this, intent)
        if (target != null) Shortcuts.reportUsed(this, intent)
        uris.removeAll { it.scheme.equals("file", ignoreCase = true) }
        val text = if (uris.isEmpty()) {
            try { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        } else null
        setIntent(Intent())  // don't handle it again on the next resume
        uris.removeAll { it.scheme.equals("file", ignoreCase = true) }
        if (uris.isEmpty() && text == null) { toast(getString(R.string.main_nothing_to_share), 2); return }
        if (text != null) {
            // A fastdrop:// join link is not content to send; ask before acting on it. Anything else goes as text.
            val join = JoinInfo.parse(text)
            if (join != null) confirmJoinLink(join, codeRole(text))
            else if (target != null && text.length <= ProtoV2.MAX_TEXT) {
                Hub.selection = emptyList(); Hub.selectionText = text
                sendTo(target)
            } else setSendText(text)
            return
        }
        val count = uris.size
        toast(resources.getQuantityString(R.plurals.main_preparing_items, count, count))
        val app = applicationContext
        thread(name = "prepare") {
            val items = uris.mapNotNull { try { Storage.itemFromUri(app, it) } catch (_: Exception) { null } }
            Hub.main.post {
                if (isDestroyed) return@post
                if (items.isEmpty()) {
                    toast(getString(R.string.main_cant_read_files), 2)
                } else {
                    Hub.selection = items
                    if (target != null) sendTo(target)
                    else if (current is SendScreen) current?.refresh() else push(SendScreen(this))
                }
            }
        }
    }
}
