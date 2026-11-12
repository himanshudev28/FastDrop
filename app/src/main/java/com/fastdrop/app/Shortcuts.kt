package com.fastdrop.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock

/**
 * Sharing Shortcuts / Direct Share: the 4 devices we sent to most recently show up in the Android
 * share sheet (API 29+ via res/xml/shortcuts.xml `<share-target>`) and as launcher shortcuts
 * (API 25+). Picking one hands MainActivity an ACTION_SEND intent carrying EXTRA_SHORTCUT_ID.
 *
 * Recents are persisted in SharedPreferences ("fastdrop_shortcuts") as JSON: id, name, type, ip,
 * port and last use. Join info (QR / Wi-Fi Direct) is NOT persisted: only devices reachable by a
 * plain LAN address are published, since that is what a saved ip can still be used for.
 */
object Shortcuts {
    const val CATEGORY = "com.fastdrop.app.category.SHARE_TARGET"      // must match res/xml/shortcuts.xml
    const val ACTION_SEND_TO_DEVICE = "com.fastdrop.app.action.SEND_TO_DEVICE"
    private const val PREFIX = "dev:"
    private const val EXTRA_DEVICE = "fastdrop.device"
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fastdrop_shortcuts", Context.MODE_PRIVATE)

    private fun load(ctx: Context): List<RecentDevice> = RecentRank.decode(prefs(ctx).getString("recent", "") ?: "")

    /** Call when a send to [dev] actually started. Updates the persisted ranking and republishes. */
    fun noteUsed(ctx: Context, dev: Device) {
        val ip = if (dev.via == Device.VIA_LAN) dev.ip else dev.join?.lanIps?.firstOrNull()
        if (ip == null || ip == "0.0.0.0") return
        synchronized(lock) {
            val l = RecentRank.touch(load(ctx), RecentDevice(dev.id, dev.name, dev.type, ip, dev.port, System.currentTimeMillis()))
            prefs(ctx).edit().putString("recent", RecentRank.encode(l)).apply()
            publish(ctx, l)
        }
    }

    /** Publishes the top devices. Safe to call at any time (no-op below API 25). */
    fun publish(ctx: Context, recents: List<RecentDevice> = load(ctx)) {
        if (Build.VERSION.SDK_INT < 25) return
        try {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
            val top = RecentRank.top(recents)
            val list = top.mapIndexed { rank, d -> build(ctx, d, rank) }
            sm.dynamicShortcuts = list
        } catch (_: Exception) { }
    }

    @Suppress("NewApi")
    private fun build(ctx: Context, d: RecentDevice, rank: Int): ShortcutInfo {
        val launch = Intent(ctx, MainActivity::class.java).setAction(ACTION_SEND_TO_DEVICE)
            .putExtra(EXTRA_DEVICE, d.id)
        val b = ShortcutInfo.Builder(ctx, PREFIX + d.id)
            .setShortLabel(d.name.take(20).ifEmpty { "Device" })
            .setLongLabel("Send to ${d.name}".take(40))
            .setIcon(icon(ctx, d.type))
            .setIntent(launch)
            .setRank(rank)
            .setCategories(setOf(CATEGORY))
        if (Build.VERSION.SDK_INT >= 30) b.setLongLived(true)
        return b.build()
    }

    /** Neutral round icon (white glyph on slate) per device type; restyle by changing the colours here. */
    @Suppress("NewApi")
    private fun icon(ctx: Context, type: String): Icon {
        val res = when (type) { "tv" -> R.drawable.ic_tv; "laptop" -> R.drawable.ic_laptop; else -> R.drawable.ic_phone }
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val adaptive = Build.VERSION.SDK_INT >= 26
        // Adaptive icons are masked to the inner 66 %: draw a full-bleed square, glyph well inside.
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4156.toInt() }
        if (adaptive) c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p) else c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        try {
            val dr = ctx.getDrawable(res)
            if (dr != null) {
                val inset = if (adaptive) size * 30 / 100 else size * 22 / 100
                dr.setBounds(inset, inset, size - inset, size - inset)
                dr.draw(c)
            }
        } catch (_: Exception) { }
        return if (adaptive) Icon.createWithAdaptiveBitmap(bmp) else Icon.createWithBitmap(bmp)
    }

    /** The device an intent is aimed at (share-sheet pick or launcher shortcut), or null. */
    fun deviceIdOf(intent: Intent?): String? {
        intent ?: return null
        val sid = try { intent.getStringExtra("android.intent.extra.shortcut.ID") } catch (_: Exception) { null }
        if (sid != null && sid.startsWith(PREFIX)) return sid.removePrefix(PREFIX)
        return try { intent.getStringExtra(EXTRA_DEVICE) } catch (_: Exception) { null }
    }

    /** Live device from Hub.devices, else the persisted last-known copy; null if neither is known. */
    fun resolve(ctx: Context, id: String): Device? {
        Hub.devices[id]?.let { return it }
        val r = load(ctx).firstOrNull { it.id == id } ?: return null
        return Device(r.id, r.name, r.type, r.ip, r.port, SystemClock.elapsedRealtime(), via = Device.VIA_LAN)
    }

    fun fromIntent(ctx: Context, intent: Intent?): Device? = deviceIdOf(intent)?.let { resolve(ctx, it) }

    /** Tells the system a shortcut was used (improves Direct Share ranking). */
    fun reportUsed(ctx: Context, intent: Intent?) {
        if (Build.VERSION.SDK_INT < 25) return
        val sid = deviceIdOf(intent) ?: return
        try { ctx.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(PREFIX + sid) } catch (_: Exception) { }
    }
}
