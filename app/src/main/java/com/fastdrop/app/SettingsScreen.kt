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
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Settings (docs 4.8): device, receiving, transfers, appearance (phone), apps, permissions, about. */
class SettingsScreen(host: MainActivity) : Screen(host) {

    private lateinit var nameBox: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var autoBox: LinearLayout
    private var trustCount: TextView? = null
    private var firstRow: View? = null
    private var sigPerms: String? = null

    private fun dp(v: Int) = Theme.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    private fun lp() = LinearLayout.LayoutParams(-1, -2)

    // ---------------------------------------------------------------- build

    override fun build(): View {
        val c = ctx
        sigPerms = null
        val tvMode = Ui.tv(c)
        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun add(v: View) = content.addView(v, lp())
        fun section(label: Int) {
            add(Ui.spacer(c, 16))
            add(Ui.section(c, s(label)))
        }

        add(bigHeader(host, s(R.string.set_title)))

        // Appearance (phone only: TV is always dark)
        if (!tvMode) {
            add(Ui.section(c, s(R.string.set_sec_appearance)))
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                val g = Ui.gutter(c)
                setPadding(g, dp(4), g, dp(12))
            }
            box.addView(Ui.text(c, s(R.string.set_theme), Type.ROW), lp())
            box.addView(Ui.text(c, s(if (Build.VERSION.SDK_INT >= 29) R.string.set_theme_hint else R.string.set_theme_hint_old), Type.META)
                .apply { setPadding(0, dp(2), 0, dp(10)) }, lp())
            val options = listOf(Theme.APPEARANCE_SYSTEM, Theme.APPEARANCE_LIGHT, Theme.APPEARANCE_DARK)
            box.addView(Ui.segmented(c, listOf(s(R.string.set_theme_system), s(R.string.set_theme_light), s(R.string.set_theme_dark)),
                options.indexOf(Theme.appearance(c)).coerceAtLeast(0)) { applyAppearance(options[it]) }, lp())
            add(box)
        }

        // Device
        if (!tvMode) section(R.string.set_sec_device) else add(Ui.section(c, s(R.string.set_sec_device)))
        nameBox = Ui.column(c)
        add(nameBox)
        renderName()

        // Receiving
        section(R.string.set_sec_receiving)
        add(Ui.text(c, s(R.string.set_accept_from), Type.ROW).apply { setPadding(Ui.gutter(c), dp(4), Ui.gutter(c), dp(4)) })
        autoBox = Ui.column(c)
        add(autoBox)
        renderAuto()
        val count = Ui.text(c, Trust.list().size.toString(), Type.META).apply { setTextColor(Ui.states(Theme.INK_2, Theme.BG, tvMode)); isDuplicateParentStateEnabled = true }
        trustCount = count
        add(Ui.rule(c))
        add(Ui.row(c, R.drawable.ic_shield, s(R.string.set_trusted_devices), s(R.string.set_trusted_devices_hint),
            LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(count)
                addView(Ui.chevron(c), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }) { host.push(TrustedDevicesScreen(host)) })
        add(Ui.rule(c))
        add(Ui.row(c, R.drawable.ic_folder, s(R.string.set_save_to), null,
            Ui.mono(c, s(R.string.set_save_to_value)), null, null, null))

        // Transfers
        section(R.string.set_sec_transfers)
        add(Ui.row(c, R.drawable.ic_speed, s(R.string.set_streams), s(R.string.set_streams_hint),
            Ui.stepper(c, Hub.streams.coerceIn(1, 8), 1, 8) { Hub.streams = it }))
        add(Ui.rule(c))
        add(Ui.toggleRow(c, s(R.string.set_verify), s(R.string.set_verify_hint), Hub.verifyCrc) { Hub.verifyCrc = it })
        if (Links.p2pSupported(c)) {
            add(Ui.rule(c))
            add(Ui.toggleRow(c, s(R.string.set_p2p), s(R.string.set_p2p_hint), Hub.prefer5Ghz) { Hub.prefer5Ghz = it })
        }

        // History retention
        section(R.string.set_sec_history)
        val keepBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val g = Ui.gutter(c)
            setPadding(g, dp(4), g, dp(12))
        }
        keepBox.addView(Ui.text(c, s(R.string.set_keep_history), Type.ROW), lp())
        keepBox.addView(Ui.text(c, s(R.string.set_keep_history_hint), Type.META).apply { setPadding(0, dp(2), 0, dp(10)) }, lp())
        val keepOptions = listOf(7, HistoryCodec.KEEP_DEFAULT_DAYS, HistoryCodec.KEEP_FOREVER)
        keepBox.addView(Ui.segmented(c, listOf(s(R.string.set_keep_7), s(R.string.set_keep_30), s(R.string.set_keep_forever)),
            keepOptions.indexOf(HistoryStore.keepDays()).let { if (it < 0) 1 else it }) { HistoryStore.setKeepDays(keepOptions[it]) }, lp())
        add(keepBox)

        // Apps
        section(R.string.set_sec_apps)
        add(Ui.toggleRow(c, s(R.string.set_system_apps), s(R.string.set_system_apps_hint), Hub.showSystemApps) { Hub.showSystemApps = it })
        add(Ui.rule(c))
        add(Ui.row(c, R.drawable.ic_share, s(R.string.set_share_app), s(R.string.set_share_app_hint), Ui.chevron(c)) {
            host.showWebShare(true)
        })

        // Permissions
        section(R.string.set_sec_permissions)
        permBox = Ui.column(c)
        add(permBox)
        renderPerms()

        // About
        section(R.string.set_sec_about)
        add(Ui.row(c, R.drawable.ic_bolt, s(R.string.set_about_app), s(R.string.set_about_version, versionName()), null, null))
        add(Ui.rule(c))
        add(Ui.row(c, R.drawable.ic_folder, s(R.string.set_about_saved), s(R.string.set_about_saved_hint), null, null))
        add(Ui.rule(c))
        add(Ui.row(c, R.drawable.ic_wifi_direct, s(R.string.set_about_p2p),
            s(if (Links.p2pSupported(c)) R.string.set_about_p2p_yes else R.string.set_about_p2p_no), null, null))

        if (!tvMode) return Ui.scrollPage(c, content)
        // TV: a single centred column, 600 dp wide.
        val holder = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        holder.addView(content, LinearLayout.LayoutParams(dp(600), -2))
        return Ui.scrollPage(c, holder)
    }

    /** Saves the choice, re-resolves the theme and rebuilds the visible screen right away (also API 21-28). */
    private fun applyAppearance(v: Int) {
        if (Theme.appearance(ctx) == v) return
        Theme.setAppearance(ctx, v)
        // MainActivity.onConfigurationChanged re-runs Theme.resolve / applyWindow and rebuilds the current screen.
        host.onConfigurationChanged(host.resources.configuration)
    }

    // ---------------------------------------------------------------- accept files from

    private fun renderAuto() {
        val c = ctx
        // Keep D-pad focus on the same radio after the rows are rebuilt.
        var focusIdx = -1
        for (i in 0 until autoBox.childCount) if (autoBox.getChildAt(i).hasFocus()) focusIdx = i
        autoBox.removeAllViews()
        val modes = listOf(
            Triple(Hub.AUTO_ASK, R.string.set_accept_ask, R.string.set_accept_ask_hint),
            Triple(Hub.AUTO_TRUSTED, R.string.set_accept_trusted, R.string.set_accept_trusted_hint),
            Triple(Hub.AUTO_EVERYONE, R.string.set_accept_everyone, R.string.set_accept_everyone_hint),
        )
        for ((mode, title, hint) in modes) {
            autoBox.addView(Ui.radioRow(c, s(title), s(hint), Hub.autoMode == mode) {
                if (Hub.autoMode != mode) { Hub.autoMode = mode; renderAuto() }
            }, lp())
        }
        if (focusIdx >= 0) autoBox.getChildAt(focusIdx)?.requestFocus()
    }

    private fun versionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "2.1"
    } catch (_: Exception) { "2.1" }

    // ---------------------------------------------------------------- device name

    private fun renderName() {
        val c = ctx
        val p = Theme.plateFor(if (Hub.isTv) "tv" else "phone")
        val lead = Ui.plate(c, if (Hub.isTv) R.drawable.ic_tv else R.drawable.ic_phone, p.fill, p.on, if (Ui.tv(c)) 48 else 40)
        val row = Ui.row(c, null, s(R.string.set_device_name), Hub.myName,
            Ui.icon(c, R.drawable.ic_edit, Theme.INK_2, 20), lead) {
            host.inputDialog(s(R.string.set_device_name_dialog), Hub.myName, InputType.TYPE_CLASS_TEXT) { name ->
                Hub.myName = name
                try { Discovery.nudge() } catch (_: Exception) { }
                renderName()
                Hub.changed()
                firstRow?.let { r -> r.post { if (!r.isInTouchMode) r.requestFocus() } }
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
            l += Perm(s(if (approx) R.string.set_perm_precise else if (Build.VERSION.SDK_INT >= 33) R.string.set_perm_nearby else R.string.set_perm_location),
                s(if (approx) R.string.set_perm_precise_hint else R.string.set_perm_p2p_hint),
                R.drawable.ic_wifi_direct, Links.hasPermissions(c)) { if (approx) openAppSettings() else grantRuntime(link) }
        }
        if (QrScanActivity.hasCamera(c)) {
            l += Perm(s(R.string.set_perm_camera), s(R.string.set_perm_camera_hint), R.drawable.ic_scan, granted(Manifest.permission.CAMERA)) {
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
        l += Perm(s(R.string.set_perm_notif), s(R.string.set_perm_notif_hint), R.drawable.ic_bolt, notif) {
            if (Build.VERSION.SDK_INT >= 33) grantRuntime(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else openNotificationSettings()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            l += Perm(s(R.string.set_perm_install), s(R.string.set_perm_install_hint), R.drawable.ic_install, AppInstaller.canInstall(c)) {
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
            host.toast(s(R.string.set_perm_open_settings), 2)
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
            if (i > 0) permBox.addView(Ui.rule(c))
            val chip = Ui.tag(c, s(if (p.granted) R.string.set_allowed else R.string.set_allow))
            permBox.addView(
                Ui.row(c, p.icon, p.title, p.sub, chip,
                    onClick = if (p.granted) null else p.grant), lp())
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onShow() {
        // The user may have changed a permission in system settings, or removed trusted devices.
        trustCount?.text = Trust.list().size.toString()
        renderPerms()
    }

    override fun refresh() = renderPerms()

    override fun firstFocus(): View? = firstRow
}
