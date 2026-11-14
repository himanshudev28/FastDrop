package com.fastdrop.app

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Wi-Fi Direct side of Links: hosting a group (with DNS-SD advertising), nearby discovery, and
 * connecting to a group as a client. All blocking calls must run on a worker thread; framework
 * callbacks arrive on the main looper and release a CountDownLatch.
 *
 * The passphrase is never put in the DNS-SD record (only id, name, type).
 */
internal object P2pLink {
    private const val SERVICE_TYPE = "_fastdrop._tcp"
    private const val SEC = -3          // our own code: SecurityException
    private const val OTHER = -2        // our own code: any other exception
    private const val A_FREQ = 0
    private const val A_BAND5 = 1
    private const val A_AUTO = 2
    private const val A_LEGACY = 3
    private const val ALNUM = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Volatile var lastError: String? = null

    private val chLock = Any()
    private var manager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var appCtx: Context? = null

    @Volatile private var hostingGroup = false   // we asked for a group; stopHost must clean up
    @Volatile private var hostReady = false      // group is up and advertised
    @Volatile private var clientGroup = false    // we joined someone else's group
    @Volatile private var connecting = false     // pauses nearby discovery during connect()
    private val hostToken = AtomicInteger()
    private val nearbyToken = AtomicInteger()
    private val nearbyOn = AtomicBoolean(false)
    private val rnd = SecureRandom()

    // ------------------------------------------------------------ channel + receiver

    private fun channel(ctx: Context): WifiP2pManager.Channel? {
        synchronized(chLock) {
            channel?.let { return it }
            val app = ctx.applicationContext
            appCtx = app
            val m = manager ?: (app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager)?.also { manager = it }
                ?: return null
            return try {
                m.initialize(app, Looper.getMainLooper(), WifiP2pManager.ChannelListener {
                    // Framework dropped us: forget the channel (next call re-initialises) and tell Links.
                    synchronized(chLock) { channel = null }
                    if (hostReady) Links.hostLost(JoinInfo.MODE_P2P, Hub.app.getString(R.string.p2p_stopped))
                    if (clientGroup) { clientGroup = false; Links.onJoinLost() }
                }).also { channel = it }
            } catch (_: Exception) { null }
        }
    }

    private val tags = HashSet<String>()
    private var registered = false

    private val recv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!on && hostReady) Links.hostLost(JoinInfo.MODE_P2P, Hub.app.getString(R.string.p2p_turned_off))
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> checkAlive()
            }
        }
    }

    /** Group vanished while we thought it was up (e.g. user switched Wi-Fi off)? Check twice before reacting. */
    private fun checkAlive(second: Boolean = false) {
        val ch = channel ?: return
        val m = manager ?: return
        try {
            if (hostReady) {
                m.requestGroupInfo(ch, WifiP2pManager.GroupInfoListener { g ->
                    if (g == null && hostReady) {
                        if (second) Links.hostLost(JoinInfo.MODE_P2P, Hub.app.getString(R.string.p2p_stopped))
                        else Hub.main.postDelayed({ checkAlive(true) }, 1500)
                    }
                })
            } else if (clientGroup) {
                m.requestConnectionInfo(ch, WifiP2pManager.ConnectionInfoListener { ci ->
                    if (clientGroup && !ci.groupFormed) {
                        if (second) { clientGroup = false; Links.onJoinLost() }
                        else Hub.main.postDelayed({ checkAlive(true) }, 1500)
                    }
                })
            }
        } catch (_: Exception) { }
    }

    private fun acquire(ctx: Context, tag: String) {
        synchronized(tags) {
            tags.add(tag)
            if (registered) return
            val app = ctx.applicationContext
            appCtx = app
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(recv, f, Context.RECEIVER_NOT_EXPORTED)
                else app.registerReceiver(recv, f)
                registered = true
            } catch (_: Exception) { }
        }
    }

    private fun release(tag: String) {
        synchronized(tags) {
            tags.remove(tag)
            if (tags.isEmpty() && registered) {
                try { appCtx?.unregisterReceiver(recv) } catch (_: Exception) { }
                registered = false
            }
        }
    }

    // ------------------------------------------------------------ blocking helpers

    private class Res {
        val latch = CountDownLatch(1)
        @Volatile var ok = false
        @Volatile var code = -1
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { ok = true; latch.countDown() }
            override fun onFailure(reason: Int) { code = reason; latch.countDown() }
        }
    }

    /** Runs one ActionListener call and waits for its result. */
    private fun action(timeoutMs: Long = 8000, call: (WifiP2pManager.ActionListener) -> Unit): Res {
        val r = Res()
        try {
            call(r.listener)
        } catch (_: SecurityException) { r.code = SEC; r.latch.countDown()
        } catch (_: Exception) { r.code = OTHER; r.latch.countDown() }
        r.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return r
    }

    private fun why(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> Hub.app.getString(R.string.p2p_unsupported_device)
        WifiP2pManager.BUSY -> Hub.app.getString(R.string.p2p_busy)
        SEC -> Hub.app.getString(R.string.p2p_allow_permission)
        WifiP2pManager.ERROR -> Hub.app.getString(R.string.p2p_failed_wifi_on)
        else -> Hub.app.getString(R.string.p2p_failed_code, code)
    }

    private fun groupInfo(ch: WifiP2pManager.Channel): WifiP2pGroup? {
        val m = manager ?: return null
        val latch = CountDownLatch(1)
        var g: WifiP2pGroup? = null
        try {
            m.requestGroupInfo(ch, WifiP2pManager.GroupInfoListener { g = it; latch.countDown() })
        } catch (_: Exception) { return null }
        latch.await(2500, TimeUnit.MILLISECONDS)
        return g
    }

    private fun connInfo(ch: WifiP2pManager.Channel): WifiP2pInfo? {
        val m = manager ?: return null
        val latch = CountDownLatch(1)
        var i: WifiP2pInfo? = null
        try {
            m.requestConnectionInfo(ch, WifiP2pManager.ConnectionInfoListener { i = it; latch.countDown() })
        } catch (_: Exception) { return null }
        latch.await(2500, TimeUnit.MILLISECONDS)
        return i
    }

    private fun removeGroupBlocking(ch: WifiP2pManager.Channel) {
        val m = manager ?: return
        try { m.cancelConnect(ch, null) } catch (_: Exception) { }
        if (groupInfo(ch) == null) return
        action(4000) { m.removeGroup(ch, it) }
        Thread.sleep(400)
    }

    private fun stopDiscoveryQuiet(ch: WifiP2pManager.Channel) {
        val m = manager ?: return
        try { m.stopPeerDiscovery(ch, null) } catch (_: Exception) { }
        try { m.clearServiceRequests(ch, null) } catch (_: Exception) { }
    }

    private fun rand(n: Int) = buildString { repeat(n) { append(ALNUM[rnd.nextInt(ALNUM.length)]) } }

    private fun validMac(s: String?) =
        s != null && s.length == 17 && s != "02:00:00:00:00:00" && s.count { it == ':' } == 5

    // ------------------------------------------------------------ hosting

    @Suppress("DEPRECATION")
    private fun staFreq(ctx: Context): Int = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val f = wm.connectionInfo?.frequency ?: 0
        if (f > 0) f else 0
    } catch (_: Exception) { 0 }

    /** 5 GHz channels without radar (DFS) rules: 36-48 and 149-165. */
    private fun nonDfs5(f: Int) = f in 5170..5250 || f in 5735..5835

    @TargetApi(29)
    private fun hostConfig(att: Int, ssid: String, pass: String, freq: Int): WifiP2pConfig {
        val b = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).enablePersistentMode(false)
        when (att) {
            A_FREQ -> b.setGroupOperatingFrequency(freq)
            A_BAND5 -> b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            else -> b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        }
        return b.build()
    }

    @TargetApi(29)
    private fun createWithConfig(m: WifiP2pManager, ch: WifiP2pManager.Channel, att: Int, ssid: String, pass: String, freq: Int): Res =
        action(10_000) { m.createGroup(ch, hostConfig(att, ssid, pass, freq), it) }

    /**
     * Creates a Wi-Fi Direct group and advertises it. Returns null (and sets [lastError]) on failure;
     * the caller then tries the hotspot fallback. [cancelled] is polled between steps.
     */
    fun startHost(ctx: Context, cancelled: () -> Boolean): Links.HostNet? {
        lastError = null
        val app = ctx.applicationContext
        val ch = channel(app) ?: run { lastError = Hub.app.getString(R.string.p2p_not_available); return null }
        val m = manager ?: return null
        hostToken.incrementAndGet()
        acquire(app, "host")
        hostingGroup = true
        hostReady = false
        stopDiscoveryQuiet(ch)
        removeGroupBlocking(ch)

        val ssid = "DIRECT-FD-" + rand(4)
        val pass = rand(10)
        val sta = staFreq(app)
        val attempts = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= 29) {
            if (Hub.prefer5Ghz) {
                if (nonDfs5(sta)) attempts.add(A_FREQ)
                attempts.add(A_BAND5)
            }
            attempts.add(A_AUTO)
        }
        attempts.add(A_LEGACY)

        var created = -1
        loop@ for (att in attempts) {
            var backoff = 1000L
            for (i in 0..3) {
                if (cancelled()) return null
                val r = if (att == A_LEGACY || Build.VERSION.SDK_INT < 29) action(10_000) { m.createGroup(ch, it) }
                else createWithConfig(m, ch, att, ssid, pass, sta)
                if (r.ok) { created = att; break@loop }
                lastError = why(r.code)
                if (r.code == WifiP2pManager.P2P_UNSUPPORTED || r.code == SEC) return null
                if (r.code == WifiP2pManager.BUSY && i < 3) {
                    // Busy usually means a stale group or a running discovery: clear it and back off 1/2/4 s.
                    removeGroupBlocking(ch)
                    Thread.sleep(backoff); backoff *= 2
                    continue
                }
                break
            }
            removeGroupBlocking(ch)
        }
        if (created < 0) return null

        // Read back what the system actually created.
        var grp: WifiP2pGroup? = null
        for (i in 0 until 10) {
            if (cancelled()) return null
            grp = groupInfo(ch)
            if (grp != null && grp.isGroupOwner && !grp.networkName.isNullOrEmpty()) break
            Thread.sleep(400)
        }
        val ownConfig = created != A_LEGACY && Build.VERSION.SDK_INT >= 29
        val name = grp?.networkName?.takeIf { it.isNotEmpty() } ?: if (ownConfig) ssid else null
        val pw = grp?.passphrase?.takeIf { it.isNotEmpty() } ?: if (ownConfig) pass else null
        if (name == null || pw == null) { lastError = Hub.app.getString(R.string.p2p_cant_read); return null }

        val iface = grp?.`interface`
        var freq = 0
        if (Build.VERSION.SDK_INT >= 29) freq = groupFrequency(grp)
        if (freq <= 0 && created == A_FREQ) freq = sta
        val mac = grp?.owner?.deviceAddress?.takeIf { validMac(it) }

        // Owner IP: connection info first, then the interface's own address, then the well-known default.
        var ip: String? = null
        for (i in 0 until 8) {
            if (cancelled()) return null
            val ci = connInfo(ch)
            if (ci != null && ci.groupFormed && ci.isGroupOwner) { ip = ci.groupOwnerAddress?.hostAddress; if (ip != null) break }
            Thread.sleep(400)
        }
        if (ip == null) ip = ifaceIpv4(iface)
        if (ip == null) ip = "192.168.49.1"

        advertise(ch)
        hostReady = true
        startKeepAlive()
        return Links.HostNet(JoinInfo.MODE_P2P, name, pw, ip, mac, freq, iface)
    }

    @TargetApi(29)
    private fun groupFrequency(g: WifiP2pGroup?): Int = try { g?.frequency ?: 0 } catch (_: Exception) { 0 }

    private fun ifaceIpv4(name: String?): String? {
        if (name.isNullOrEmpty()) return null
        return try {
            NetworkInterface.getByName(name)?.interfaceAddresses
                ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } catch (_: Exception) { null }
    }

    /** DNS-SD record so nearby phones can list us without a QR. No passphrase in here, ever. */
    private fun advertise(ch: WifiP2pManager.Channel) {
        val m = manager ?: return
        try {
            action(3000) { m.clearLocalServices(ch, it) }
            val rec = HashMap<String, String>()
            rec["v"] = "2"
            rec["i"] = Hub.myId
            rec["n"] = Hub.myName.take(24)
            rec["t"] = Hub.myType
            val inst = "fd" + Hub.myId.replace("-", "").take(10)
            val svc = WifiP2pDnsSdServiceInfo.newInstance(inst, SERVICE_TYPE, rec)
            action(5000) { m.addLocalService(ch, svc, it) }
            action(5000) { m.discoverPeers(ch, it) }
        } catch (_: Exception) { }
    }

    /** Some stacks stop being visible after a while; poke discovery every ~20 s while hosting. */
    private fun startKeepAlive() {
        val tok = hostToken.get()
        thread(name = "p2p-keepalive", isDaemon = true) {
            try {
                while (hostToken.get() == tok && hostReady) {
                    for (i in 0 until 80) {
                        if (hostToken.get() != tok) return@thread
                        Thread.sleep(250)
                    }
                    val ch = channel
                    if (ch != null && !connecting) try { manager?.discoverPeers(ch, null) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    /** Removes our group and service. Safe to call when nothing is hosted. */
    fun stopHost() {
        hostToken.incrementAndGet()
        hostReady = false
        if (!hostingGroup) return
        val ch = channel
        val m = manager
        if (ch != null && m != null) {
            try { m.clearLocalServices(ch, null) } catch (_: Exception) { }
            try { m.stopPeerDiscovery(ch, null) } catch (_: Exception) { }
            action(4000) { m.removeGroup(ch, it) }
        }
        hostingGroup = false
        release("host")
    }

    // ------------------------------------------------------------ nearby (DNS-SD)

    fun startNearby(ctx: Context) {
        if (!nearbyOn.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val tok = nearbyToken.incrementAndGet()
        thread(name = "p2p-nearby", isDaemon = true) {
            acquire(app, "nearby")
            try {
                while (nearbyToken.get() == tok) {
                    if (!connecting && Links.hasPermissions(app) && Links.wifiEnabled(app) &&
                        !Links.locationOffBlocksDiscovery(app)) nearbyCycle(app, tok)
                    for (i in 0 until 40) {            // ~10 s, restart because the stack gets flaky
                        if (nearbyToken.get() != tok) break
                        Thread.sleep(250)
                    }
                }
            } catch (_: Exception) {
            } finally { if (nearbyToken.get() == tok || !nearbyOn.get()) release("nearby") }
        }
    }

    fun stopNearby() {
        if (!nearbyOn.compareAndSet(true, false)) return
        nearbyToken.incrementAndGet()
        val ch = channel ?: return
        val m = manager ?: return
        if (hostReady || clientGroup) {
            try { m.clearServiceRequests(ch, null) } catch (_: Exception) { }   // keep discoverability while hosting
        } else stopDiscoveryQuiet(ch)
    }

    private fun nearbyCycle(app: Context, tok: Int) {
        val ch = channel(app) ?: return
        val m = manager ?: return
        try {
            m.setDnsSdResponseListeners(ch,
                WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
                WifiP2pManager.DnsSdTxtRecordListener { _, rec, dev ->
                    val id = rec?.get("i")
                    val mac = dev?.deviceAddress
                    if (!id.isNullOrEmpty() && id != Hub.myId && !mac.isNullOrEmpty()) {
                        Hub.upsertDevice(Device(
                            id, rec["n"] ?: dev.deviceName ?: Hub.app.getString(R.string.p2p_device),
                            if (rec["t"] == "tv") "tv" else "phone",
                            "0.0.0.0", Proto.PORT, SystemClock.elapsedRealtime(),
                            manual = false, via = Device.VIA_P2P, join = null, p2pAddress = mac))
                    }
                })
            if (nearbyToken.get() != tok) return
            action(3000) { m.clearServiceRequests(ch, it) }
            if (nearbyToken.get() != tok) return
            action(4000) { m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE), it) }
            if (nearbyToken.get() != tok) return
            action(4000) { m.discoverPeers(ch, it) }
            if (nearbyToken.get() != tok) return
            action(4000) { m.discoverServices(ch, it) }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------ joining a group as client

    /** API 29+: connect by SSID + passphrase. Returns the group owner's IP, or null (see [lastError]). */
    @TargetApi(29)
    fun connectCredentials(ctx: Context, info: JoinInfo, timeoutMs: Long): String? {
        val ssid = info.ssid ?: return null
        val pass = info.pass ?: return null
        return connectAndWait(ctx, timeoutMs) {
            val b = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
            val mac = info.mac
            if (mac != null) try { b.setDeviceAddress(MacAddress.fromString(mac)) } catch (_: Exception) { }
            if (info.freq > 0) b.setGroupOperatingFrequency(info.freq)
            else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            b.build()
        }
    }

    /** Push-button connect to a device address; the other side's system shows the invite. */
    @Suppress("DEPRECATION")
    fun connectPbc(ctx: Context, mac: String, timeoutMs: Long): String? =
        connectAndWait(ctx, timeoutMs) {
            WifiP2pConfig().apply {
                deviceAddress = mac
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0     // we want to be the client
            }
        }

    private fun connectAndWait(ctx: Context, timeoutMs: Long, cfg: () -> WifiP2pConfig): String? {
        lastError = null
        val app = ctx.applicationContext
        val ch = channel(app) ?: run { lastError = Hub.app.getString(R.string.p2p_not_available); return null }
        val m = manager ?: return null
        acquire(app, "join")
        connecting = true
        try {
            stopDiscoveryQuiet(ch)
            removeGroupBlocking(ch)           // stale group would make connect() fail
            val config = try { cfg() } catch (_: Exception) { lastError = Hub.app.getString(R.string.p2p_details_rejected); return null }
            var r = action(10_000) { m.connect(ch, config, it) }
            if (!r.ok && r.code == WifiP2pManager.BUSY) {
                Thread.sleep(1500)
                r = action(10_000) { m.connect(ch, config, it) }
            }
            if (!r.ok) { lastError = why(r.code); return null }

            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val ci = connInfo(ch)
                if (ci != null && ci.groupFormed) {
                    if (!ci.isGroupOwner) {
                        val ip = ci.groupOwnerAddress?.hostAddress
                        if (ip != null) { clientGroup = true; return ip }
                    } else {
                        lastError = Hub.app.getString(R.string.p2p_made_host)
                        break
                    }
                }
                Thread.sleep(400)
            }
            if (lastError == null) lastError = Hub.app.getString(R.string.p2p_timeout)
            try { m.cancelConnect(ch, null) } catch (_: Exception) { }
            try { m.removeGroup(ch, null) } catch (_: Exception) { }
            return null
        } catch (e: InterruptedException) {
            // Cancelled while linking: don't leave a half-made connection behind.
            try { m.cancelConnect(ch, null) } catch (_: Exception) { }
            try { m.removeGroup(ch, null) } catch (_: Exception) { }
            throw e
        } finally {
            connecting = false
            if (!clientGroup) release("join")
        }
    }

    /** Leave the group we joined. Does not block (safe on the main thread). */
    fun leave() {
        if (!clientGroup) return
        clientGroup = false
        val ch = channel
        val m = manager
        if (ch != null && m != null) {
            try { m.cancelConnect(ch, null) } catch (_: Exception) { }
            try { m.removeGroup(ch, null) } catch (_: Exception) { }
        }
        release("join")
    }
}
