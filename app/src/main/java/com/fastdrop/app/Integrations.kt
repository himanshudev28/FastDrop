package com.fastdrop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/**
 * System integrations that live outside the app UI: the 2x1 home-screen widget and the
 * "FastDrop: receive" Quick Settings tile. Hub calls [onHubChanged] (coalesced, main thread) and
 * [onProgress] (about 1 Hz from the watchdog); widget updates are throttled to one per 2 s while a
 * percentage is showing, state changes (Ready / Connecting) go through immediately.
 *
 * MainActivity understands an [EXTRA_OPEN] extra ([OPEN_SEND] / [OPEN_RECEIVE]) on its launch intent.
 *
 * RESTYLE POINTS: res/layout/widget_fastdrop.xml + drawable/widget_*.xml (widget look),
 * [buildViews] (widget content), [FastDropTileService.refresh] (tile label / icon / state).
 */
object Integrations {
    const val EXTRA_OPEN = "fastdrop.open"
    const val OPEN_SEND = "send"
    const val OPEN_RECEIVE = "receive"

    private val throttle = UpdateThrottle(2000)
    @Volatile private var lastText = ""
    @Volatile private var lastReceivable = false

    fun onHubChanged() {
        refreshWidgets()
        if (Hub.ready) {
            val r = Hub.receivable()
            if (r != lastReceivable) { lastReceivable = r; requestTileRefresh(Hub.app) }
        }
    }

    fun onProgress() = refreshWidgets()

    /** What the widget's status line says right now. */
    fun statusText(): String {
        if (!Hub.ready) return Hub.app.getString(R.string.widget_ready)
        val t = Hub.transfer
        val running = t != null && t.running
        val pct = if (t != null && running && t.total > 0) (t.done.get() * 100 / t.total).toInt().coerceIn(0, 100) else -1
        return WidgetStatus.text(running, t?.outgoing ?: true, pct, Hub.pendingSendTo != null)
    }

    private fun refreshWidgets() {
        if (!Hub.ready) return
        try {
            val text = statusText()
            if (text == lastText) return
            if (!throttle.tryAcquire(SystemClock.elapsedRealtime(), force = !text.contains('%'))) return
            lastText = text
            val ctx = Hub.app
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FastDropWidget::class.java))
            if (ids.isNotEmpty()) for (id in ids) mgr.updateAppWidget(id, buildViews(ctx, text))
        } catch (_: Exception) { }
    }

    fun buildViews(ctx: Context, status: String): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_fastdrop)
        v.setTextViewText(R.id.widget_status, status)
        // Flat fills live in the layout (Send orange / dark text, Receive ultramarine / white text).
        v.setContentDescription(R.id.widget_send, ctx.getString(R.string.widget_cd_send))
        v.setContentDescription(R.id.widget_receive, ctx.getString(R.string.widget_cd_receive))
        v.setOnClickPendingIntent(R.id.widget_send, openApp(ctx, OPEN_SEND, 31))
        v.setOnClickPendingIntent(R.id.widget_receive, openApp(ctx, OPEN_RECEIVE, 32))
        return v
    }

    /** PendingIntent that opens FastDrop on [what] (OPEN_SEND / OPEN_RECEIVE). */
    fun openApp(ctx: Context, what: String, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, what)
        return PendingIntent.getActivity(ctx, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
    }

    private fun requestTileRefresh(ctx: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        try { TileService.requestListeningState(ctx, ComponentName(ctx, FastDropTileService::class.java)) } catch (_: Exception) { }
    }
}

/** 2x1 widget: Send and Receive buttons plus a one-line status. */
class FastDropWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = Integrations.buildViews(context, Integrations.statusText())
        for (id in ids) try { mgr.updateAppWidget(id, views) } catch (_: Exception) { }
    }
}

/**
 * Quick Settings tile "FastDrop: receive". Tapping opens FastDrop on the Receive screen: in this
 * app being receivable means the UI is open (or auto-accept is on), so that is the reliable way to
 * become visible. The tile shows ACTIVE while Hub.receivable() is true.
 */
@android.annotation.TargetApi(24)
class FastDropTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onTileAdded() { super.onTileAdded(); refresh() }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Hub.ready && Hub.receivable()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = this.getString(R.string.notif_app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) this.getString(R.string.tile_visible) else this.getString(R.string.tile_off)
        try { tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile) } catch (_: Exception) { }
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")   // Intent overload only below API 34
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Integrations.EXTRA_OPEN, Integrations.OPEN_RECEIVE)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Android 14+: the Intent overload is deprecated and throws for apps targeting 34+.
                    startActivityAndCollapse(PendingIntent.getActivity(this, 33, i,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    startActivityAndCollapse(i)
                }
            } catch (_: Exception) { }
        }
    }
}
