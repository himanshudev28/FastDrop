package com.fastdrop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import kotlin.concurrent.thread

/** adb shell am broadcast -n com.fastdrop.app/.DebugSendReceiver --es ip 1.2.3.4 --es uri content://a,content://b [--ei port 53540] */
class DebugSendReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ip = intent.getStringExtra("ip") ?: return
        val uris = (intent.getStringExtra("uri") ?: return).split(',').filter { it.isNotBlank() }.map { Uri.parse(it.trim()) }
        val port = intent.getIntExtra("port", Proto.PORT)
        Hub.init(ctx)
        val pending = goAsync()
        thread {
            try {
                val items = uris.mapNotNull { Storage.itemFromUri(ctx, it) }
                if (items.isEmpty()) return@thread
                val dev = Discovery.hello(ip, manual = true, port = port)
                    ?: Device("debug", ip, "phone", ip, port, SystemClock.elapsedRealtime(), true)
                Sender.send(ctx.applicationContext, dev, items)
            } finally { pending.finish() }
        }
    }
}
