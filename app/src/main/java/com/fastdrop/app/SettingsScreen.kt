package com.fastdrop.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.LinearLayout

/** Settings: device name, receiving, speed, apps, permission status with Grant buttons, about. */
class SettingsScreen(host: MainActivity) : Screen(host) {

    private companion object {
        val STREAM_OPTIONS = intArrayOf(1, 2, 4, 6, 8)
    }

    private lateinit var nameBox: LinearLayout
    private lateinit var permBox: LinearLayout
    private var firstRow: View? = null
    private var sigPerms: String? = null

    private fun dp(v: Int) = Theme.dp(ctx, v)

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    // ---------------------------------------------------------------- build

    override fun build(): View {
        val c = ctx
        sigPerms = null
        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val blocks = ArrayList<View>()
        fun add(v: View, bottom: Int = 16) {
            content.addView(v, lp(bottom = bottom))
            blocks.add(v)
        }

        add(Ui.header(c, "Settings", { host.pop() }), 4)

        // This device
        add(Ui.label(c, "This device"), 8)
        nameBox = Ui.card(c, 6)
        add(nameBox)
        renderName()

        // Receiving
        add(Ui.label(c, "Receiving"), 8)
        val recv = Ui.card(c, 6)
        recv.addView(Ui.toggleRow(c, "Auto-accept",
            "Receive files without asking. Anyone nearby can send while this is on.", Hub.autoAccept) {
            Hub.autoAccept = it
            Hub.changed()
        }, lp())
        add(recv)

        // Speed
        add(Ui.label(c, "Speed"), 8)
        val speed = Ui.card(c, 6)
        val streamsBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        streamsBox.addView(Ui.text(c, "Parallel streams", 16f, Theme.TEXT, Theme.MEDIUM), lp())
        streamsBox.addView(Ui.text(c, "More streams can be faster on strong Wi-Fi. 4 works well for most.", 13f, Theme.MUTED),
            lp(top = 3, bottom = 12))
        val sel = STREAM_OPTIONS.indexOf(Hub.streams).let { if (it < 0) 2 else it }
        streamsBox.addView(Ui.segmented(c, STREAM_OPTIONS.map { it.toString() }, sel) { Hub.streams = STREAM_OPTIONS[it] }, lp())
        speed.addView(streamsBox, lp())
        if (Links.p2pSupported(c)) {
            speed.addView(Ui.divider(c))
            speed.addView(Ui.toggleRow(c, "Wi-Fi Direct on 5 GHz",
                "Faster direct links. Turn off if connections fail.", Hub.prefer5Ghz) { Hub.prefer5Ghz = it }, lp())
        }
        add(speed)

        // Apps
        add(Ui.label(c, "Apps"), 8)
        val apps = Ui.card(c, 6)
        apps.addView(Ui.toggleRow(c, "Show system apps",
            "Include built-in apps when you pick apps to send.", Hub.showSystemApps) { Hub.showSystemApps = it }, lp())
        apps.addView(Ui.divider(c))
        apps.addView(Ui.row(c, R.drawable.ic_share, Theme.WEB_A, "Share FastDrop app",
            "Give the app to any phone through a web page", Ui.icon(c, R.drawable.ic_chevron, Theme.MUTED, 18)) {
            host.showWebShare(true)
        }, lp())
        add(apps)

        // Permissions
        add(Ui.label(c, "Permissions"), 8)
        permBox = Ui.card(c, 6)
        add(permBox)
        renderPerms()

        // About
        add(Ui.label(c, "About"), 8)
        val about = Ui.card(c, 6)
        about.addView(Ui.row(c, R.drawable.ic_bolt, Theme.ACCENT, "FastDrop", "Version ${versionName()}", null, null), lp())
        about.addView(Ui.divider(c))
        about.addView(Ui.row(c, R.drawable.ic_folder, Theme.CAT_FILES, "Saved files", "Files save to Download/FastDrop", null, null), lp())
        about.addView(Ui.divider(c))
        about.addView(Ui.row(c, R.drawable.ic_wifi_direct, Theme.ACCENT_2, "Wi-Fi Direct",
            if (Links.p2pSupported(c)) "Supported on this device" else "Not supported. Same Wi-Fi still works", null, null), lp())
        add(about, 8)

        Anim.stagger(blocks, 40, 40)
        return Ui.scrollPage(c, content)
    }

    private fun versionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "2.0"
    } catch (_: Exception) { "2.0" }

    // ---------------------------------------------------------------- device name

    private fun renderName() {
        val c = ctx
        val icon = if (Hub.isTv) R.drawable.ic_tv else R.drawable.ic_phone
        val row = Ui.row(c, icon, Theme.ACCENT, "Device name", Hub.myName,
            Ui.icon(c, R.drawable.ic_edit, Theme.MUTED, 20)) {
            host.inputDialog("Device name", Hub.myName, InputType.TYPE_CLASS_TEXT) { name ->
                Hub.myName = name
                try { Discovery.nudge() } catch (_: Exception) { }
                renderName()
                Hub.changed()
            }
        }
        nameBox.removeAllViews()
        nameBox.addView(row, lp())
        firstRow = row
    }

    // ---------------------------------------------------------------- permissions

    private class Perm(val title: String, val sub: String, val icon: Int, val granted: Boolean, val grant: () -> Unit)

    private fun granted(p: String): Boolean =
        Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun perms(): List<Perm> {
        val c = ctx
        val l = ArrayList<Perm>()
        val link = Links.requiredPermissions()
        if (link.isNotEmpty()) {
            val approx = Links.approximateOnly(c)
            l += Perm(if (approx) "Choose Precise location" else if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location",
                if (approx) "Wi-Fi Direct needs Precise location. Tap to open settings" else "Needed to connect over Wi-Fi Direct",
                R.drawable.ic_wifi_direct, Links.hasPermissions(c)) { if (approx) openAppSettings() else grantRuntime(link) }
        }
        if (QrScanActivity.hasCamera(c)) {
            l += Perm("Camera", "Needed to scan QR codes", R.drawable.ic_scan, granted(Manifest.permission.CAMERA)) {
                grantRuntime(arrayOf(Manifest.permission.CAMERA))
            }
        }
        val notif: Boolean = when {
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= 24 -> try {
                (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
            } catch (_: Exception) { true }
            else -> true
        }
        l += Perm("Notifications", "Shows progress and incoming files in the background", R.drawable.ic_bolt, notif) {
            if (Build.VERSION.SDK_INT >= 33) grantRuntime(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else openNotificationSettings()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            l += Perm("Install apps", "Lets you install apps you receive", R.drawable.ic_install, AppInstaller.canInstall(c)) {
                AppInstaller.openInstallSettings(host)
            }
        }
        return l
    }

    /** Asks for permissions; if Android won't show the prompt any more, opens app settings instead. */
    private fun grantRuntime(p: Array<String>) {
        host.ensurePermissions(p) { ok ->
            if (!ok && Build.VERSION.SDK_INT >= 23 && p.none { host.shouldShowRequestPermissionRationale(it) }) {
                openAppSettings()
            }
            sigPerms = null
            renderPerms()
        }
    }

    private fun openAppSettings() {
        try {
            host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}")))
        } catch (_: Exception) {
            host.toast("Open Android Settings and allow it for FastDrop", 2)
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                host.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName))
            } else openAppSettings()
        } catch (_: Exception) { openAppSettings() }
    }

    private fun renderPerms() {
        if (!::permBox.isInitialized) return
        val list = perms()
        val sig = list.joinToString("|") { "${it.title}:${it.granted}" }
        if (sig == sigPerms) return
        sigPerms = sig
        val c = ctx
        permBox.removeAllViews()
        list.forEachIndexed { i, p ->
            if (i > 0) permBox.addView(Ui.divider(c))
            val chip = if (p.granted) Ui.chip(c, "Allowed", Theme.SUCCESS, R.drawable.ic_check)
            else Ui.chip(c, "Allow", Theme.ACCENT)
            permBox.addView(
                Ui.row(c, p.icon, if (p.granted) Theme.SUCCESS else Theme.ACCENT, p.title, p.sub, chip,
                    if (p.granted) null else p.grant), lp())
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onShow() {
        // The user may have changed a permission in system settings.
        renderPerms()
    }

    override fun refresh() = renderPerms()

    override fun firstFocus(): View? = firstRow
}
