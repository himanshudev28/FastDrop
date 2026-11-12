package com.fastdrop.app

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A device we can send to.
 *  - VIA_LAN: answered on a network we're already on (same Wi-Fi, or a Wi-Fi Direct group / hotspot
 *    we've joined). [ip] is directly reachable.
 *  - VIA_P2P: seen nearby over Wi-Fi Direct service discovery but not connected yet. [ip] is a
 *    placeholder; Links.ensureReachable() connects first (receiver gets the system invite prompt).
 *  - VIA_QR: from a scanned QR code. [join] holds its LAN addresses and/or the network it hosts.
 */
class Device(
    val id: String, val name: String, val type: String,
    val ip: String, val port: Int,
    @Volatile var lastSeen: Long, val manual: Boolean = false,
    val via: String = VIA_LAN,
    val join: JoinInfo? = null,
    val p2pAddress: String? = null,
) {
    /** Protocol version it announced: 0 = unknown (Wi-Fi Direct / QR), 1 = FastDrop 2.0, 2 = FastDrop 2.1+. */
    @Volatile var ver: Int = 0
    @Volatile var caps: Set<String> = emptySet()

    val needsLink: Boolean get() = via != VIA_LAN

    /** Records the protocol version and capabilities from a HELLO / announce JSON. */
    fun withInfo(o: JSONObject): Device {
        ver = o.optInt("v", 1)
        val c = o.optJSONArray("caps")
        if (c != null) caps = (0 until c.length()).map { c.optString(it) }.toSet()
        return this
    }

    companion object {
        const val VIA_LAN = "lan"
        const val VIA_P2P = "p2p"
        const val VIA_QR = "qr"
    }
}

/** Marks files that together form one installable app (base.apk + split APKs). */
class AppTag(val label: String, val pkg: String, val version: String)

class SendItem(
    val name: String, val size: Long, val uri: Uri?, val file: java.io.File?,
    val app: AppTag? = null,
    val mtime: Long = 0L,              // source last-modified (ms), 0 = unknown; only used for resume keys
)

/** A received file, or (when [text] is set) received text / a link. */
class Received(
    val name: String, val size: Long, @Volatile var uri: Uri?, val from: String,
    val app: AppTag? = null,
    val at: Long = System.currentTimeMillis(),
    val text: String? = null,
)

class RxFile(val name: String, val size: Long, val app: AppTag? = null)

enum class State { RUNNING, DONE, FAILED, CANCELLED }

/** One transfer in either direction. Progress is a lock-free counter read by the UI. */
class Transfer(
    val outgoing: Boolean, val peer: String, val total: Long, val files: Int,
    val via: String = Device.VIA_LAN,              // how the link was made, for the UI ("Wi-Fi Direct")
    val names: List<String> = emptyList(),         // file names, for the UI (first few shown)
    val text: String? = null,                      // outgoing text transfer (for Retry)
) {
    val done = AtomicLong()
    /** Bytes that were already on the receiver when this session began (resume). Speed/ETA ignore them. */
    @Volatile var baseline = 0L
    val resumed: Boolean get() = baseline > 0
    /** Bytes moved in this session only. */
    val sessionDone: Long get() = (done.get() - baseline).coerceAtLeast(0)
    val startedAt = SystemClock.elapsedRealtime()
    @Volatile var lastProgressAt = startedAt
    /** Sender: every byte has been written; only the receiver's final ack is outstanding. */
    @Volatile var sentAll = false
    @Volatile var state = State.RUNNING
    @Volatile var message = ""
    @Volatile var finishedAt = 0L
    // --- history hooks (HistoryStore): peer's device id when known, and per-file info for the entry
    @Volatile var peerId: String? = null
    val histFiles = CopyOnWriteArrayList<HFile>()
    private val closeables = CopyOnWriteArrayList<Closeable>()

    fun add(n: Long) { done.addAndGet(n); lastProgressAt = SystemClock.elapsedRealtime() }
    fun register(c: Closeable) { closeables.add(c); if (state != State.RUNNING) Io.closeQuietly(c) }
    fun closeAll() { for (c in closeables) Io.closeQuietly(c) }

    @Synchronized
    fun finish(st: State, msg: String): Boolean {
        if (state != State.RUNNING) return false
        state = st; message = msg; finishedAt = SystemClock.elapsedRealtime()
        return true
    }

    val running get() = state == State.RUNNING
}

/** A file offer waiting for the user to tap Accept / Decline. */
class IncomingOffer(
    val from: String, val fromType: String, val files: List<RxFile>, val total: Long,
    /** Text / link offer (no files). */
    val text: String? = null,
    /** 4-digit confirmation code (same value the sender shows). Empty for protocol 1 senders. */
    val code: String = "",
    /** The offer carried a valid HMAC from a trusted device. */
    val trusted: Boolean = false,
    /** The sender can be trusted (protocol 2 with a device id) and isn't yet. */
    val canTrust: Boolean = false,
) {
    private val latch = CountDownLatch(1)
    /** Set by the UI ("Always accept from X") before respond(true). */
    @Volatile var alwaysAccept = false
    @Volatile var accepted = false
    @Volatile var answered = false
    @Volatile var timedOut = false
    @Volatile var onExpired: (() -> Unit)? = null

    fun respond(accept: Boolean) {
        if (answered) return
        answered = true; accepted = accept; latch.countDown()
    }

    fun await(ms: Long): Boolean {
        if (!latch.await(ms, TimeUnit.MILLISECONDS)) {
            answered = true
            timedOut = true
            Hub.main.post { onExpired?.invoke() }
            return false
        }
        return accepted
    }
}

object Hub {
    /** A LAN sighting newer than this still counts as "live" when merging device entries. */
    private const val LAN_FRESH_MS = 15_000L

    interface Listener {
        fun onChanged()
        fun onOffer(offer: IncomingOffer)
        fun onMessage(msg: String)
    }

    lateinit var app: Context; private set
    val main = Handler(Looper.getMainLooper())
    val devices = ConcurrentHashMap<String, Device>()
    val received = CopyOnWriteArrayList<Received>()

    @Volatile var listener: Listener? = null
    @Volatile var transfer: Transfer? = null
    @Volatile var pendingSendTo: String? = null
    /** What the pending send is doing right now: "Connecting via Wi-Fi Direct…", "Waiting for X to accept…". */
    @Volatile var pendingStatus: String? = null
    @Volatile var pendingSocket: Closeable? = null
    /** The sender thread and whether it is inside link set-up (so Cancel can interrupt it). */
    @Volatile var pendingThread: Thread? = null
    @Volatile var linking = false
    @Volatile var selection: List<SendItem> = emptyList()
        set(v) { field = v; if (v.isNotEmpty()) selectionText = null }     // files and text are exclusive
    /** Text / link / clipboard waiting to be sent instead of files (selection is empty while this is set). */
    @Volatile var selectionText: String? = null
    /** Confirmation code ("0427") shown on the sender while waiting for Accept; null when not applicable. */
    @Volatile var pendingCode: String? = null
    /** Multi-device send progress, e.g. "Device 2 of 3"; null for single sends. */
    @Volatile var multiInfo: String? = null
    /** The incoming offer waiting for Accept / Decline (set by Server; notification buttons answer it). */
    @Volatile var offer: IncomingOffer? = null

    private var started = false
    private val busy = AtomicBoolean(false)
    private val changePosted = AtomicBoolean(false)
    private val lockGuard = Any()
    private var wifiLocks = ArrayList<WifiManager.WifiLock>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val prefs get() = app.getSharedPreferences("fastdrop", Context.MODE_PRIVATE)

    val myId: String by lazy {
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }
    var myName: String
        get() = prefs.getString("name", null) ?: defaultName()
        set(v) { prefs.edit().putString("name", v.trim().take(40)).apply() }
    const val AUTO_ASK = 0
    const val AUTO_TRUSTED = 1
    const val AUTO_EVERYONE = 2

    /**
     * Auto-accept mode (AUTO_ASK / AUTO_TRUSTED / AUTO_EVERYONE). Migration: an old `auto = true`
     * becomes Everyone, anything else the new default, Trusted devices.
     */
    var autoMode: Int
        get() = if (prefs.contains("automode")) prefs.getInt("automode", AUTO_TRUSTED).coerceIn(0, 2)
        else if (prefs.getBoolean("auto", false)) AUTO_EVERYONE else AUTO_TRUSTED
        set(v) { prefs.edit().putInt("automode", v.coerceIn(0, 2)).apply(); changed() }
    /** Legacy switch view of [autoMode]: on = Everyone. Turning it off leaves "Ask" alone, otherwise falls back to Trusted. */
    var autoAccept: Boolean
        get() = autoMode == AUTO_EVERYONE
        set(v) { if (v) autoMode = AUTO_EVERYONE else if (autoMode == AUTO_EVERYONE) autoMode = AUTO_TRUSTED }
    /** Could an offer be accepted without a prompt right now (so we are receivable with the UI closed)? */
    val autoActive: Boolean get() = autoMode == AUTO_EVERYONE || (autoMode == AUTO_TRUSTED && Trust.hasAny())
    /** "Verify data (CRC32)": costs CPU on both sides, off by default. */
    var verifyCrc: Boolean
        get() = prefs.getBoolean("crc", false)
        set(v) { prefs.edit().putBoolean("crc", v).apply() }
    var streams: Int
        get() = prefs.getInt("streams", 4)
        set(v) { prefs.edit().putInt("streams", v).apply() }
    /** Host Wi-Fi Direct groups on 5 GHz when possible (falls back to auto). */
    var prefer5Ghz: Boolean
        get() = prefs.getBoolean("5ghz", true)
        set(v) { prefs.edit().putBoolean("5ghz", v).apply() }
    var showSystemApps: Boolean
        get() = prefs.getBoolean("sysapps", false)
        set(v) { prefs.edit().putBoolean("sysapps", v).apply() }

    val isTv: Boolean by lazy {
        val ui = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val myType get() = if (isTv) "tv" else "phone"

    private fun defaultName(): String {
        val n = if (Build.VERSION.SDK_INT >= 25)
            try { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        else null
        return (n ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(40)
    }

    fun init(ctx: Context) {
        synchronized(this) {
            if (started) return
            app = ctx.applicationContext
            started = true
        }
        // Temp copies from earlier runs (made for un-seekable shared streams).
        thread(isDaemon = true) { Storage.clearSendCache(app) }
        // Resume records (and their partial files) older than 7 days.
        thread(isDaemon = true) { try { Resume.prune(app) } catch (_: Exception) { } }
        try { HistoryStore.init(app) } catch (_: Exception) { }      // history: restores Hub.received
        Server.start()
        Discovery.start()
        thread(name = "watchdog", isDaemon = true) { watchdog() }
    }

    /** True once [init] has run (widget / tile can be asked before the app was ever opened). */
    val ready: Boolean get() = started

    /** Can someone send to us right now? */
    fun receivable() = listener != null || autoActive

    fun info(): JSONObject = JSONObject()
        .put("app", "fastdrop").put("v", ProtoV2.VERSION).put("caps", org.json.JSONArray(ProtoV2.CAPS))
        .put("id", myId).put("name", myName).put("type", myType)
        .put("port", Proto.PORT).put("receivable", receivable())

    /**
     * Merge rules: a fresh same-network (LAN) entry always beats a Wi-Fi Direct / QR entry for the
     * same id, but picks up its join info so we can still use it later. QR entries are manual
     * (kept until removed). A manual flag set earlier is never lost.
     */
    @Synchronized
    fun upsertDevice(d: Device) {
        val old = devices[d.id]
        val merged: Device = when {
            old == null -> d
            d.via == Device.VIA_LAN -> {
                if (old.manual || old.join != null || old.p2pAddress != null)
                    Device(d.id, d.name, d.type, d.ip, d.port, d.lastSeen, d.manual || old.manual,
                        Device.VIA_LAN, d.join ?: old.join, d.p2pAddress ?: old.p2pAddress)
                else d
            }
            old.via == Device.VIA_LAN && SystemClock.elapsedRealtime() - old.lastSeen < LAN_FRESH_MS -> {
                // Keep the live LAN entry (and its timestamp); just remember how else to reach it.
                if (d.join == null && d.p2pAddress == null) old
                else Device(old.id, old.name, old.type, old.ip, old.port, old.lastSeen, old.manual,
                    Device.VIA_LAN, d.join ?: old.join, d.p2pAddress ?: old.p2pAddress)
            }
            d.via == Device.VIA_P2P && old.via == Device.VIA_QR -> {
                // A scanned QR is manual and richer than a nearby sighting.
                Device(old.id, old.name, old.type, old.ip, old.port, d.lastSeen, true,
                    Device.VIA_QR, old.join, d.p2pAddress ?: old.p2pAddress)
            }
            d.via == Device.VIA_QR && old.p2pAddress != null && d.p2pAddress == null ->
                Device(d.id, d.name, d.type, d.ip, d.port, d.lastSeen, d.manual, d.via, d.join, old.p2pAddress)
            else -> d
        }
        if (old != null && merged !== old && merged.ver == 0) { merged.ver = old.ver; merged.caps = old.caps }
        if (d.ver > 0) { merged.ver = d.ver; merged.caps = d.caps }
        devices[d.id] = merged
        if (old == null || old.name != merged.name || old.ip != merged.ip || old.via != merged.via) changed()
    }

    fun removeDevice(id: String) {
        if (devices.remove(id) != null) changed()
    }

    /** True while one of our activities is in front (not just a foreground service running). */
    fun appVisible(): Boolean = try {
        val i = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(i)
        i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (_: Exception) { true }

    // ---- transfer lifecycle ----
    fun tryReserve() = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }

    fun begin(t: Transfer) {
        transfer = t
        acquireLocks()
        TransferService.start(app)          // keeps us alive in the background (no-op if already running)
        changed()
    }

    fun end(t: Transfer, st: State, msg: String) {
        if (!t.finish(st, msg)) return
        t.closeAll()
        releaseLocks()
        busy.set(false)
        try { HistoryStore.record(t) } catch (_: Exception) { }      // history hook
        changed()
        TransferService.update(this.app, statusLine(t))
        try { Links.onTransferEnded() } catch (_: Exception) { }
        TransferService.stopIfIdle()
    }

    fun cancel() {
        Sender.queueAborted = true
        Io.closeQuietly(pendingSocket)
        if (linking) try { pendingThread?.interrupt() } catch (_: Exception) { }
        transfer?.let { if (it.running) end(it, State.CANCELLED, "Cancelled") }
    }

    fun addReceived(r: Received) {
        received.add(0, r)
        while (received.size > 200) received.removeAt(received.size - 1)
        changed()
    }

    /** Coalesced: a burst of changes becomes one UI refresh. */
    fun changed() {
        if (changePosted.compareAndSet(false, true)) main.post {
            changePosted.set(false)
            listener?.onChanged()
            Integrations.onHubChanged()      // widget + Quick Settings tile
        }
    }
    fun message(msg: String) = main.post {
        listener?.onMessage(msg) ?: android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun statusLine(t: Transfer): String {
        val dir = if (t.outgoing) "Sending to" else "Receiving from"
        return when (t.state) {
            State.RUNNING -> {
                val pct = if (t.total > 0) t.done.get() * 100 / t.total else 100
                val secs = (SystemClock.elapsedRealtime() - t.startedAt) / 1000.0
                val sp = if (secs > 0) t.sessionDone / secs else 0.0
                "$dir ${t.peer} • $pct% • ${Io.speed(sp)}"
            }
            State.DONE -> if (t.text != null || (t.files == 0 && t.total == 0L)) "Text ${if (t.outgoing) "sent to" else "from"} ${t.peer}"
            else "Done: ${t.files} file(s), ${Io.bytes(t.total)} ${if (t.outgoing) "sent to" else "from"} ${t.peer}"
            else -> t.message
        }
    }

    // Wi-Fi power-save is the #1 reason LAN transfers slow down after a few seconds.
    // 29+: low-latency lock (HIGH_PERF is deprecated and mapped to it on 34+), older: high-perf.
    // The partial wake lock keeps the CPU up.
    @Suppress("DEPRECATION")
    private fun acquireLocks() { synchronized(lockGuard) {
        try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLocks.isEmpty()) {
                if (Build.VERSION.SDK_INT >= 29)
                    wifiLocks.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fastdrop:ll"))
                if (Build.VERSION.SDK_INT < 34)
                    wifiLocks.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fastdrop:hp"))
                wifiLocks.forEach { it.setReferenceCounted(false) }
            }
            wifiLocks.forEach { try { if (!it.isHeld) it.acquire() } catch (_: Exception) { } }
        } catch (_: Exception) { }
        try {
            if (wakeLock == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fastdrop:xfer").apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        } catch (_: Exception) { }
    } }

    private fun releaseLocks() { synchronized(lockGuard) {
        wifiLocks.forEach { try { if (it.isHeld) it.release() } catch (_: Exception) { } }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
    } }

    /** Aborts stalled transfers and keeps the notification's progress fresh. */
    private fun watchdog() {
        while (true) {
            try {
                Thread.sleep(1000)
                val t = transfer ?: continue
                if (!t.running) continue
                if (!t.sentAll && SystemClock.elapsedRealtime() - t.lastProgressAt > 30_000) {
                    end(t, State.FAILED, "Connection lost (no data for 30 s)")
                } else {
                    TransferService.update(app, statusLine(t))
                    Integrations.onProgress()    // widget progress, throttled to 1 per 2 s
                }
            } catch (_: Exception) { }
        }
    }
}
