package com.fastdrop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

/**
 * Foreground service: keeps the app's process at high priority so Android doesn't
 * kill or throttle it while a transfer runs (screen off, app in background, TV screensaver).
 * Also holds the multicast lock that discovery needs, and shows progress in the notification.
 */
class TransferService : Service() {
    companion object {
        private const val CH = "fastdrop"
        private const val CH_OFFER = "fastdrop_offer"
        private const val NID = 1
        private const val NID_OFFER = 2
        @Volatile private var instance: TransferService? = null
        @Volatile private var lastKey = ""

        fun start(ctx: Context) {
            val i = Intent(ctx, TransferService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) { }
        }

        /** Stop once the app is closed and nothing is transferring, hosting, joined or web-sharing. */
        fun stopIfIdle() {
            val s = instance ?: return
            val busy = Hub.listener != null || Hub.autoActive || Hub.transfer?.running == true ||
                Hub.pendingSendTo != null ||
                Links.hostState != Links.HostState.OFF || Links.joinedName != null ||
                WebShare.running
            if (!busy) s.stopSelf()
        }

        /** Refreshes the ongoing notification (text + progress bar). Skips identical updates. */
        fun update(ctx: Context, text: String) {
            val s = instance ?: return
            try {
                val key = text + "|" + progressOf(Hub.transfer)
                if (key == lastKey) return
                lastKey = key
                s.nm().notify(NID, s.build(text))
            } catch (_: Exception) { }
        }

        /** Percent (0..100) of a running transfer, or -1 when there is nothing to show. */
        private fun progressOf(t: Transfer?): Int {
            if (t == null || !t.running || t.total <= 0) return -1
            return (t.done.get() * 100 / t.total).toInt().coerceIn(0, 100)
        }

        /** Old signature: uses the offer Hub is holding (so the buttons work), else a plain tap-to-open note. */
        fun notifyOffer(ctx: Context, from: String) {
            val o = Hub.offer
            if (o != null) { notifyOffer(ctx, o); return }
            try {
                channels(ctx)
                val n = builder(ctx, CH_OFFER)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("$from wants to send you files")
                    .setContentText("Tap to open FastDrop and accept")
                    .setAutoCancel(true)
                    .setContentIntent(openApp(ctx))
                    .build()
                (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID_OFFER, n)
            } catch (_: Exception) { }
        }

        /** High-importance heads-up with Accept / Decline buttons. */
        @Suppress("DEPRECATION")
        fun notifyOffer(ctx: Context, offer: IncomingOffer) {
            try {
                channels(ctx)
                val n = builder(ctx, CH_OFFER)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(if (offer.text != null) "${offer.from} wants to send you text" else "${offer.from} wants to send you files")
                    .setContentText(offer.text?.take(80) ?: "${offer.files.size} file(s) • ${Io.bytes(offer.total)}")
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentIntent(openApp(ctx))
                    .addAction(0, "Decline", actionIntent(ctx, OfferActionReceiver.ACTION_DECLINE, 11))
                    .addAction(0, "Accept", actionIntent(ctx, OfferActionReceiver.ACTION_ACCEPT, 12))
                if (Build.VERSION.SDK_INT >= 26) n.setTimeoutAfter(60_000)
                else n.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
                (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID_OFFER, n.build())
            } catch (_: Exception) { }
        }

        /** False when the offer notification could not be seen (notifications off, or its channel blocked). */
        fun canNotifyOffer(ctx: Context): Boolean = try {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            var ok = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled()
            if (ok && Build.VERSION.SDK_INT >= 26) {
                channels(ctx)
                ok = nm.getNotificationChannel(CH_OFFER)?.importance != NotificationManager.IMPORTANCE_NONE
            }
            ok
        } catch (_: Exception) { true }

        fun cancelOffer(ctx: Context) {
            try { (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NID_OFFER) } catch (_: Exception) { }
        }

        private fun actionIntent(ctx: Context, action: String, code: Int): PendingIntent {
            val i = Intent(ctx, OfferActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, code, i, immutable(PendingIntent.FLAG_UPDATE_CURRENT))
        }

        private fun immutable(flags: Int) = flags or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        private fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "Transfers", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_OFFER, "Incoming files", NotificationManager.IMPORTANCE_HIGH))
        }

        @Suppress("DEPRECATION")
        private fun builder(ctx: Context, ch: String): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else Notification.Builder(ctx)

        private fun openApp(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(ctx, 0, i, immutable(PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        channels(this)
        // Started with startForegroundService(): if we cannot reach startForeground() Android kills the
        // process ("did not then call Service.startForeground()"), so every variant is tried first.
        if (!goForeground(build("Ready to receive"))) stopSelf()
        // The multicast lock that discovery needs is held by Discovery itself (independent of this service).
    }

    /** connectedDevice first (no daily cap), then dataSync, then untyped. */
    private fun goForeground(n: Notification): Boolean {
        val types = if (Build.VERSION.SDK_INT >= 29)
            intArrayOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 0)
        else intArrayOf(0)
        for (type in types) {
            try {
                if (type == 0 || Build.VERSION.SDK_INT < 29) startForeground(NID, n) else startForeground(NID, n, type)
                return true
            } catch (_: SecurityException) {
                continue
            } catch (_: Exception) {
                continue     // e.g. ForegroundServiceStartNotAllowed / dataSync quota: try the next type
            }
        }
        // Last resort: plain call (uses the manifest types on 34+).
        return try { startForeground(NID, n); true } catch (_: Exception) { false }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    // Time-limited service types get a timeout callback (API 34 / 35 variants): stop cleanly.
    override fun onTimeout(startId: Int) { stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        instance = null
        lastKey = ""
        super.onDestroy()
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun build(text: String): Notification {
        val b = builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FastDrop")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(this))
        val pct = progressOf(Hub.transfer)
        if (pct >= 0) b.setProgress(100, pct, false)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }
}

/** Handles the Accept / Decline buttons on the incoming-files notification. */
class OfferActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.fastdrop.app.OFFER_ACCEPT"
        const val ACTION_DECLINE = "com.fastdrop.app.OFFER_DECLINE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val accept = intent.action == ACTION_ACCEPT
        val offer = Hub.offer
        if (offer != null && !offer.answered) {
            offer.respond(accept)
            // Close the in-app offer sheet too, if it is showing.
            try { offer.onExpired?.invoke() } catch (_: Exception) { }
        }
        TransferService.cancelOffer(ctx)
    }
}
