package com.fastdrop.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Installs received APKs (single or split) through PackageInstaller. No FileProvider needed. */
object AppInstaller {
    const val ACTION_RESULT = "com.fastdrop.app.INSTALL_RESULT"
    const val EXTRA_LABEL = "label"
    private const val GROUP_WINDOW_MS = 10 * 60 * 1000L

    // Callers waiting for the final answer of a session.
    internal val waiting = ConcurrentHashMap<Int, (Boolean, String) -> Unit>()

    fun isApk(name: String): Boolean = name.trim().lowercase().endsWith(".apk")

    /** True when this app may start installs ("install unknown apps" on Android 8+). */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true

    fun openInstallSettings(act: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                act.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
                )
            } else {
                @Suppress("DEPRECATION")
                act.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        } catch (_: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    /**
     * Groups received files into installable apps: tagged files by package + sender within 10 minutes,
     * and each untagged .apk on its own. [received] is newest first; so are the groups.
     */
    fun groups(received: List<Received>): List<List<Received>> {
        class G(val pkg: String?, val version: Any?, val from: String, var at: Long, val items: ArrayList<Received>)
        val gs = ArrayList<G>()
        for (r in received) {
            if (!isApk(r.name)) continue
            val tag = r.app
            if (tag == null) { gs += G(null, null, r.from, r.at, arrayListOf(r)); continue }
            val g = gs.firstOrNull {
                it.pkg == tag.pkg && it.version == tag.version && it.from == r.from && Math.abs(it.at - r.at) <= GROUP_WINDOW_MS
            }
            if (g == null) { gs += G(tag.pkg, tag.version, r.from, r.at, arrayListOf(r)); continue }
            // The same file received twice: keep the newest only.
            if (g.items.none { it.name == r.name }) g.items += r
        }
        return gs.map { g ->
            g.items.sortedBy { if (it.name.substringAfterLast('/').equals("base.apk", true)) 0 else 1 }
        }
    }

    /**
     * Streams every file into one install session, then asks the system to confirm.
     * Copying runs on a worker thread. [done] runs on the main thread: false right away if something
     * fails before the system dialog, otherwise with the final result once the receiver reports it.
     */
    fun install(act: Activity, files: List<Received>, done: (ok: Boolean, msg: String) -> Unit) {
        val ctx = act.applicationContext
        fun finish(ok: Boolean, msg: String) = Hub.main.post { done(ok, msg) }

        val apks = files.filter { isApk(it.name) && it.uri != null }
        if (apks.isEmpty()) { finish(false, "No app file to install"); return }
        if (!canInstall(ctx)) {
            finish(false, "Allow FastDrop to install apps first")
            return
        }
        val label = apks.firstNotNullOfOrNull { it.app?.label }
            ?: apks[0].name.substringAfterLast('/').removeSuffix(".apk")
        val pkg = apks.firstNotNullOfOrNull { it.app?.pkg }

        thread(name = "fd-install", isDaemon = true) {
            var session: PackageInstaller.Session? = null
            var sessionId = -1
            val installer = ctx.packageManager.packageInstaller
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (!pkg.isNullOrEmpty()) params.setAppPackageName(pkg)
                val total = apks.sumOf { it.size.coerceAtLeast(0L) }
                if (total > 0) params.setSize(total)
                sessionId = installer.createSession(params)
                session = installer.openSession(sessionId)

                var idx = 0
                for (r in apks) {
                    val uri = r.uri!!
                    val entry = if (idx == 0) "base.apk" else "split_$idx.apk"
                    val input = openInput(ctx, uri) ?: throw java.io.FileNotFoundException(r.name)
                    input.use { ins ->
                        session.openWrite(entry, 0, if (r.size > 0) r.size else -1).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                            out.flush()
                            session.fsync(out)
                        }
                    }
                    idx++
                }

                val cb = Intent(ctx, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT)
                    .setPackage(ctx.packageName)
                    .putExtra(EXTRA_LABEL, label)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                // The system fills in extras, so the PendingIntent must be mutable on Android 12+.
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(ctx, sessionId, cb, flags)
                waiting[sessionId] = done
                session.commit(pi.intentSender)
                session.close()
                session = null
            } catch (e: Exception) {
                waiting.remove(sessionId)
                try { session?.abandon() } catch (_: Exception) { }
                try { session?.close() } catch (_: Exception) { }
                if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) { }
                finish(false, "Couldn't install $label: ${friendlyError(e)}")
            }
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is SecurityException -> "install isn't allowed"
        e is java.io.FileNotFoundException -> "the file is missing"
        e is java.io.IOException && (e.message ?: "").contains("ENOSPC") -> "not enough storage"
        else -> e.message?.take(80) ?: "unknown error"
    }

    // Works for content:// and file:// uris (ContentResolver handles both) with a plain-path fallback.
    private fun openInput(ctx: Context, uri: Uri): java.io.InputStream? = try {
        ctx.contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        val path = uri.path
        if (uri.scheme == "file" && path != null) try { File(path).inputStream() } catch (_: Exception) { null } else null
    }
}

/** Gets the answer from PackageInstaller: opens the confirm screen, or reports success / failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val label = intent.getStringExtra(AppInstaller.EXTRA_LABEL) ?: "The app"
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm == null) {
                report(context, sessionId, false, "Couldn't open the install screen for $label")
                return
            }
            try {
                val visible = try { Hub.appVisible() } catch (_: Throwable) { true }
                if (Build.VERSION.SDK_INT >= 29 && !visible) notifyConfirm(context, sessionId, confirm, label)
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                report(context, sessionId, false, "Couldn't open the install screen for $label")
            }
            return
        }

        val ok = status == PackageInstaller.STATUS_SUCCESS
        val msg = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "$label installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "$label was blocked by your device or Play Protect"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "$label conflicts with the installed version. Uninstall that one first"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "$label isn't compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "$label is damaged or isn't a valid app"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install $label"
            else -> "Couldn't install $label" + if (!detail.isNullOrBlank()) " ($detail)" else ""
        }
        report(context, sessionId, ok, msg)
    }

    /** Background activity starts are blocked on Android 10+, so also offer the confirm screen as a notification. */
    private fun notifyConfirm(context: Context, sessionId: Int, confirm: Intent, label: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("fd_install", "App installs", android.app.NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                context, sessionId, Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, "fd_install")
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Tap to install $label")
                .setContentText("FastDrop needs your OK to finish installing")
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(0x1D00 + sessionId, b.build())
        } catch (_: Throwable) {
        }
    }

    private fun report(context: Context, sessionId: Int, ok: Boolean, msg: String) {
        val cb = AppInstaller.waiting.remove(sessionId)
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(0x1D00 + sessionId)
        } catch (_: Throwable) {}
        val hubReady = try { Hub.app; true } catch (_: UninitializedPropertyAccessException) { false }
        if (hubReady) {
            Hub.message(msg)
        } else {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
        if (cb != null && hubReady) Hub.main.post { cb(ok, msg) }
        else if (cb != null) android.os.Handler(android.os.Looper.getMainLooper()).post { cb(ok, msg) }
    }
}
