package com.fastdrop.app

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Makes two devices reachable without a shared router.
 *
 * Hosting (the device that shows a QR): Wi-Fi Direct group first, local-only hotspot second,
 * plain same-Wi-Fi last. Joining (the device that scans or taps a nearby device): same network
 * first, then Wi-Fi Direct connect, then WifiNetworkSpecifier / legacy WifiManager.
 *
 * Socket binding: a WifiNetworkSpecifier network is not the process default, so every outgoing
 * socket must call [bindSocket] before connect(). We do NOT use bindProcessToNetwork because that
 * would also push DNS and the rest of the app onto a no-internet network.
 *
 * The heavy lifting lives in P2pLink.kt (Wi-Fi Direct) and HotspotLink.kt (hotspot + WifiJoin).
 */
object Links {
    enum class HostState { OFF, STARTING, READY, FAILED }

    /** A network we host for others to join. */
    class HostNet(
        val mode: String, val ssid: String, val pass: String?, val hostIp: String?,
        val mac: String?, val freq: Int, val iface: String?,
    )

    @Volatile var hostState: HostState = HostState.OFF
    @Volatile var hostMode: String? = null
    @Volatile var hostError: String? = null
    @Volatile var joinedName: String? = null

    @Volatile internal var hosted: HostNet? = null
    @Volatile private var joinedSsid: String? = null
    @Volatile private var joinWhy: String? = null

    private val lock = Any()
    private val joinLock = Any()
    private val owners = LinkedHashSet<String>()
    @Volatile private var hostGen = 0
    private var stopPending = false
    private var stopGen = 0
    private val joinBusy = java.util.concurrent.atomic.AtomicInteger()
    private val joinGen = java.util.concurrent.atomic.AtomicInteger()
    private var lanRetry = false

    // One thread for host start/stop so a stop can never overlap a later start.
    private val hostExec = Executors.newSingleThreadExecutor { r -> Thread(r, "link-host").apply { isDaemon = true } }

    // ---------------------------------------------------------------- capability

    fun p2pSupported(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun hotspotSupported(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.NEARBY_WIFI_DEVICES")
        Build.VERSION.SDK_INT >= 23 -> arrayOf(
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        else -> emptyArray()
    }

    fun hasPermissions(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        // 23-32: ACCESS_FINE_LOCATION is what Wi-Fi Direct needs; an Android 12 "Approximate" grant is COARSE only.
        if (Build.VERSION.SDK_INT < 33)
            return ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED
        return requiredPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** True when the user chose "Approximate" location on Android 12: Wi-Fi Direct needs "Precise". */
    fun approximateOnly(ctx: Context): Boolean =
        Build.VERSION.SDK_INT in 31..32 &&
            ctx.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED

    /** On Android 6-12 Wi-Fi Direct discovery silently finds nothing while Location is off. */
    @Suppress("DEPRECATION")
    fun locationOffBlocksDiscovery(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT !in 23..32) return false
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled
            else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, 0) == 0
        } catch (_: Exception) { false }
    }

    fun wifiEnabled(ctx: Context): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    } catch (_: Exception) { false }

    fun linkLabel(via: String): String = when (via) {
        Device.VIA_LAN -> Hub.app.getString(R.string.link_same_wifi)
        Device.VIA_P2P -> Hub.app.getString(R.string.link_wifi_direct)
        Device.VIA_QR -> Hub.app.getString(R.string.link_qr_code)
        JoinInfo.MODE_HOTSPOT -> Hub.app.getString(R.string.link_hotspot)
        else -> Hub.app.getString(R.string.link_wifi)
    }

    // ---------------------------------------------------------------- hosting

    /** IPv4 addresses others on our own networks can use (never the hosted/joined P2P interfaces). */
    private fun lanIps(): List<String> {
        val hostedIface = hosted?.iface
        return try {
            Net.localAddrs()
                .filter { a -> val n = a.iface.name.lowercase(); !n.contains("p2p") && a.iface.name != hostedIface }
                .mapNotNull { it.ip.hostAddress }
                .distinct().take(4)
        } catch (_: Exception) { emptyList() }
    }

    /** Current QR content: always valid, gains network details once hosting is READY. */
    fun hostInfo(): JoinInfo {
        val h = hosted
        return JoinInfo(Hub.myId, Hub.myName, Hub.myType, Proto.PORT, lanIps(),
            h?.mode, h?.ssid, h?.pass, h?.hostIp, h?.mac, h?.freq ?: 0)
    }

    fun startHosting(ctx: Context, owner: String) {
        val app = ctx.applicationContext
        var gen: Int
        synchronized(lock) {
            owners.add(owner)
            stopPending = false
            stopGen++                                   // cancels a pending grace-period teardown
            val st = hostState
            // LAN-only because permission was missing / Wi-Fi was off: try again once that is fixed.
            val retry = st == HostState.READY && hostMode == null && lanRetry && hasPermissions(app) && wifiEnabled(app)
            if (st == HostState.STARTING || (st == HostState.READY && !retry)) return
            // Never tear down a link we joined as a client while it is in use (or being made).
            if (joinBusy.get() > 0) return
            if (joinedName != null && (Hub.transfer?.running == true || Hub.pendingSendTo != null)) return
            hostGen++
            gen = hostGen
            hostState = HostState.STARTING
            hostMode = null; hostError = null; hosted = null; lanRetry = false
        }
        Hub.changed()
        try { TransferService.start(app) } catch (_: Exception) { }
        val g = gen
        hostExec.execute {
            try {
                if (joinedName != null) synchronized(joinLock) { releaseJoinNow(false) }
                if (hostGen == g) runHost(app, g)
            } catch (_: Throwable) { finishHost(g, null, app.getString(R.string.link_cant_start_sharing), false) }
        }
    }

    private fun runHost(app: Context, gen: Int) {
        val cancelled = { hostGen != gen }
        var net: HostNet? = null
        var err: String? = null
        var retryable = false
        if (!hasPermissions(app)) {
            err = Hub.app.getString(R.string.link_allow_nearby)
            retryable = true
        } else {
            if (!p2pSupported(app)) {
                err = Hub.app.getString(R.string.link_p2p_unsupported)
            } else if (!wifiEnabled(app)) {
                err = Hub.app.getString(R.string.link_turn_on_wifi_p2p)
                retryable = true
            } else {
                net = P2pLink.startHost(app, cancelled)
                if (net == null) err = P2pLink.lastError ?: Hub.app.getString(R.string.link_p2p_failed)
            }
            if (net == null && !cancelled() && hotspotSupported(app)) {
                net = HotspotLink.start(app, cancelled)
                if (net == null) err = (err?.let { "$it; " } ?: "") + (HotspotLink.lastError ?: Hub.app.getString(R.string.link_hotspot_failed))
            }
        }
        if (cancelled()) return
        finishHost(gen, net, err, retryable)
    }

    private fun finishHost(gen: Int, net: HostNet?, err: String?, retryable: Boolean) {
        synchronized(lock) {
            if (hostGen != gen) return
            hosted = net
            hostMode = net?.mode
            hostState = HostState.READY
            hostError = if (net != null) null else Hub.app.getString(R.string.link_using_lan, err ?: Hub.app.getString(R.string.link_no_sharing))
            lanRetry = net == null && retryable
        }
        Hub.changed()
    }

    /** The hosted network died under us (user turned it off, channel lost). Falls back to LAN-only. */
    internal fun hostLost(mode: String, why: String) {
        synchronized(lock) {
            if (hostState != HostState.READY || hostMode != mode) return
            hosted = null; hostMode = null; lanRetry = false
            hostError = Hub.app.getString(R.string.link_using_lan, why)
        }
        Hub.changed()
    }

    fun stopHosting(owner: String) {
        var gen = -1
        synchronized(lock) {
            owners.remove(owner)
            if (owners.isEmpty() && hostState != HostState.OFF) {
                if (Hub.transfer?.running == true) stopPending = true else gen = ++stopGen
            }
        }
        if (gen < 0) return
        // Grace period: screen switches / picker / rotation must not destroy the group (and its QR).
        Hub.main.postDelayed({
            var go = false
            synchronized(lock) {
                if (stopGen == gen && owners.isEmpty() && hostState != HostState.OFF) {
                    if (Hub.transfer?.running == true) stopPending = true else go = true
                }
            }
            if (go) teardownHost()
        }, 4000L)
    }

    private fun resetHostState() {
        hostGen++
        stopPending = false
        hostState = HostState.OFF
        hostMode = null; hostError = null; hosted = null; lanRetry = false
    }

    private fun teardownHost() {
        synchronized(lock) { resetHostState() }
        hostExec.execute {
            try { P2pLink.stopHost() } catch (_: Throwable) { }
            try { HotspotLink.stop() } catch (_: Throwable) { }
            Hub.main.post { try { TransferService.stopIfIdle() } catch (_: Exception) { } }
        }
        Hub.changed()
    }

    /** Only one Wi-Fi Direct group at a time: stop hosting (owners stay) and wait for it. */
    private fun stopHostingForJoin() {
        if (hostState == HostState.OFF) return
        synchronized(lock) { resetHostState() }
        Hub.changed()
        try {
            hostExec.submit {
                try { P2pLink.stopHost() } catch (_: Throwable) { }
                try { HotspotLink.stop() } catch (_: Throwable) { }
            }.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    /** Called by Hub.end(): run the stop we deferred and start the idle timer for a joined network. */
    fun onTransferEnded() {
        val stop = synchronized(lock) { stopPending && owners.isEmpty() }
        if (stop) teardownHost()
        if (joinedName != null) releaseJoinSoon()
    }

    // ---------------------------------------------------------------- nearby

    fun startNearby(ctx: Context) {
        if (!p2pSupported(ctx)) return
        P2pLink.startNearby(ctx.applicationContext)
    }

    fun stopNearby() = P2pLink.stopNearby()

    // ---------------------------------------------------------------- joining

    fun deviceFromJoin(info: JoinInfo): Device = Device(
        info.id, info.name, info.type,
        info.hostIp ?: info.lanIps.firstOrNull() ?: "0.0.0.0", info.port,
        SystemClock.elapsedRealtime(), manual = true, via = Device.VIA_QR, join = info,
    )

    /** Blocking. See the spec in the file header; throws IOException with a friendly message. */
    fun ensureReachable(ctx: Context, dev: Device, onStatus: (String) -> Unit): Device {
        if (dev.via == Device.VIA_LAN) return dev
        val app = ctx.applicationContext
        cancelIdleRelease()
        joinGen.incrementAndGet()
        val info = dev.join
        if (info != null) probeLan(info)?.let { return it }
        joinBusy.incrementAndGet()
        var counted = true
        val unbusy = { if (counted) { counted = false; joinBusy.decrementAndGet() } }
        try {
            synchronized(joinLock) { joinGen.incrementAndGet(); return join(app, dev, info, onStatus) }
        } catch (e: InterruptedException) {
            Thread.interrupted()
            unbusy()
            cleanupAfterFailure()
            throw IOException(Hub.app.getString(R.string.msg_cancelled))
        } catch (e: IOException) {
            unbusy()
            cleanupAfterFailure()
            throw e
        } catch (e: Exception) {
            unbusy()
            cleanupAfterFailure()
            throw IOException(Hub.app.getString(R.string.msg_cant_connect_to, dev.name))
        } finally {
            unbusy()
        }
    }

    private fun cleanupAfterFailure() {
        try { synchronized(joinLock) { releaseJoinNow(true) } } catch (_: Exception) { }
    }

    private fun failMessage(dev: Device): String {
        val why = joinWhy
        return if (why.isNullOrEmpty()) Hub.app.getString(R.string.link_keep_close, dev.name)
        else Hub.app.getString(R.string.link_cant_connect_why, dev.name, why)
    }

    private fun join(app: Context, dev: Device, info: JoinInfo?, st: (String) -> Unit): Device {
        joinWhy = null
        stopHostingForJoin()
        // Already inside this host's network (e.g. second send)? Just talk to it.
        if (info != null && info.hasNetwork && joinedSsid == info.ssid) {
            helloRetry(info.id, info.port, { candidates(info) }, 2500)?.let { return it }
        }
        releaseJoinNow()
        if (!wifiEnabled(app)) throw IOException(Hub.app.getString(R.string.link_turn_on_wifi_to, dev.name))

        var result: Device? = null
        if (info != null && info.hasNetwork) {
            result = if (info.mode == JoinInfo.MODE_P2P) joinP2p(app, dev, info, st) else joinWifi(app, dev, info, st)
        }
        if (result == null && dev.p2pAddress != null && hasPermissions(app) && p2pSupported(app)) {
            result = joinPbc(app, dev, st)
        }
        return result ?: throw IOException(failMessage(dev))
    }

    private fun joinP2p(app: Context, dev: Device, info: JoinInfo, st: (String) -> Unit): Device? {
        val ssid = info.ssid ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            if (hasPermissions(app) && p2pSupported(app)) {
                st(Hub.app.getString(R.string.link_connecting_p2p))
                val go = connectCredentialsApi29(app, info)
                if (go != null) {
                    setJoined(app, ssid)
                    val d = helloRetry(info.id, info.port, { listOf(go) + candidates(info) }, 12_000)
                    if (d != null) return d
                    P2pLink.leave()
                    setJoined(app, null)
                    joinWhy = Hub.app.getString(R.string.link_connected_no_answer, dev.name)
                } else {
                    joinWhy = P2pLink.lastError
                }
            }
            return joinWifi(app, dev, info, st)   // ordinary Wi-Fi join of the DIRECT-xx network
        }
        // Android 9 and older: the group is a normal WPA2 network for us.
        var d = joinWifi(app, dev, info, st)
        if (d == null && info.mac != null && hasPermissions(app) && p2pSupported(app)) {
            st(Hub.app.getString(R.string.link_connecting_p2p))
            val go = P2pLink.connectPbc(app, info.mac, 30_000)
            if (go != null) {
                setJoined(app, ssid)
                d = helloRetry(info.id, info.port, { listOf(go) + candidates(info) }, 12_000)
                if (d == null) { P2pLink.leave(); setJoined(app, null) }
            }
        }
        return d
    }

    @TargetApi(29)
    private fun connectCredentialsApi29(app: Context, info: JoinInfo): String? =
        P2pLink.connectCredentials(app, info, 15_000)

    /** Join [info]'s SSID as an ordinary Wi-Fi network (hotspot, or a Wi-Fi Direct group seen as an AP). */
    private fun joinWifi(app: Context, dev: Device, info: JoinInfo, st: (String) -> Unit): Device? {
        val ssid = info.ssid ?: return null
        val cancelled = { Thread.currentThread().isInterrupted }
        if (Build.VERSION.SDK_INT >= 29) {
            st(Hub.app.getString(R.string.link_approve_prompt))
            val net = joinSpecApi29(app, info, cancelled)
            if (net == null) { joinWhy = WifiJoin.lastError ?: joinWhy; return null }
            setJoined(app, ssid)
            st(Hub.app.getString(R.string.msg_connecting_to, dev.name))
            val d = helloRetry(info.id, info.port, { candidates(info) }, 15_000)
            if (d == null) {
                WifiJoin.release(); setJoined(app, null)
                joinWhy = Hub.app.getString(R.string.link_joined_no_answer, dev.name)
            }
            return d
        }
        st(Hub.app.getString(R.string.link_joining, ssid))
        val gw = WifiJoin.joinLegacy(app, ssid, info.pass, 30_000, cancelled)
        if (gw == null) { joinWhy = WifiJoin.lastError ?: joinWhy; return null }
        setJoined(app, ssid)
        val d = helloRetry(info.id, info.port, { listOf(gw) + candidates(info) }, 12_000)
        if (d == null) {
            WifiJoin.release(); setJoined(app, null)
            joinWhy = Hub.app.getString(R.string.link_joined_no_answer, dev.name)
        }
        return d
    }

    @TargetApi(29)
    private fun joinSpecApi29(app: Context, info: JoinInfo, cancelled: () -> Boolean) =
        WifiJoin.joinSpecifier(app, info.ssid ?: "", info.pass, 40_000, cancelled)

    /** Nearby (DNS-SD) device without credentials: push-button connect, receiver sees the system invite. */
    private fun joinPbc(app: Context, dev: Device, st: (String) -> Unit): Device? {
        st(Hub.app.getString(R.string.link_waiting_invite, dev.name))
        val go = P2pLink.connectPbc(app, dev.p2pAddress ?: return null, 40_000)
        if (go == null) { joinWhy = P2pLink.lastError; return null }
        setJoined(app, null, dev.name)
        st(Hub.app.getString(R.string.msg_connecting_to, dev.name))
        val port = if (dev.port > 0) dev.port else Proto.PORT
        val d = helloRetry(dev.id, port, { listOf(go) }, 15_000)
        if (d == null) { P2pLink.leave(); setJoined(app, null); joinWhy = Hub.app.getString(R.string.link_connected_no_answer, dev.name) }
        return d
    }

    private fun candidates(info: JoinInfo): List<String> =
        (listOfNotNull(info.hostIp) + listOfNotNull(WifiJoin.gateway())).distinct()

    private fun setJoined(app: Context, ssid: String?, label: String? = ssid) {
        joinedSsid = ssid
        joinedName = label
        if (label != null) try { TransferService.start(app) } catch (_: Exception) { }
        Hub.changed()
    }

    /** Joined network disappeared on its own. */
    internal fun onJoinLost() {
        if (joinedName == null) return
        joinedName = null; joinedSsid = null
        Hub.changed()
        // Unregister the stale request, off the main thread (release() can wait behind a join).
        thread(name = "join-lost", isDaemon = true) {
            try {
                synchronized(joinLock) {
                    if (joinedName == null && WifiJoin.network == null) {
                        try { P2pLink.leave() } catch (_: Exception) { }
                        WifiJoin.release()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ---- LAN-first probe

    private fun sameSubnet(ip: String): Boolean {
        val t = ipToInt(ip) ?: return false
        for (a in Net.localAddrs()) {
            val mine = ipToInt(a.ip.hostAddress ?: continue) ?: continue
            val p = if (a.prefix in 8..30) a.prefix else 24
            val mask = (-1 shl (32 - p))
            if ((mine and mask) == (t and mask)) return true
        }
        return false
    }

    private fun ipToInt(s: String): Int? {
        val p = s.split('.')
        if (p.size != 4) return null
        var r = 0
        for (x in p) r = (r shl 8) or (x.toIntOrNull()?.takeIf { it in 0..255 } ?: return null)
        return r
    }

    /** Tries the QR's LAN addresses in parallel; the same device id answering means we're already on one network. */
    private fun probeLan(info: JoinInfo): Device? {
        val ips = info.lanIps.filter { JoinInfo.isIpv4(it) && sameSubnet(it) }.distinct().take(6)
        if (ips.isEmpty()) return null
        val found = AtomicReference<Device?>(null)
        val latch = CountDownLatch(ips.size)
        for (ip in ips) thread(name = "lan-probe", isDaemon = true) {
            try {
                val d = Discovery.hello(ip, 700, true)
                if (d != null && d.id == info.id) found.compareAndSet(null, d)
            } catch (_: Exception) {
            } finally { latch.countDown() }
        }
        val end = SystemClock.elapsedRealtime() + 1600
        while (found.get() == null && latch.count > 0 && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        return found.get()
    }

    // ---- HELLO over the (possibly bound) joined network

    private fun helloBound(ip: String, port: Int, timeoutMs: Int, expectedId: String): Device? = try {
        Socket().use { s ->
            bindSocket(s, ip)
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = 2500
            DataOutputStream(s.getOutputStream()).apply { writeInt(Proto.MAGIC_HELLO); flush() }
            val o = Io.readJson(DataInputStream(BufferedInputStream(s.getInputStream())))
            if (o.optString("app") != "fastdrop" || o.optString("id") != expectedId) null
            else Device(expectedId, o.optString("name", ip), o.optString("type", "phone"), ip,
                o.optInt("port", port), SystemClock.elapsedRealtime(), true).withInfo(o)
        }
    } catch (_: Exception) { null }

    /** DHCP takes a moment after joining, so keep asking for a while. */
    private fun helloRetry(id: String, port: Int, ips: () -> List<String>, totalMs: Long): Device? {
        val end = SystemClock.elapsedRealtime() + totalMs
        while (true) {
            for (ip in ips().distinct()) helloBound(ip, port, 1500, id)?.let { return it }
            if (SystemClock.elapsedRealtime() >= end) return null
            Thread.sleep(600)
        }
    }

    // ---- public join helpers

    /** Receiver scanned a sender's QR: join that network but don't send. */
    fun joinAsync(ctx: Context, info: JoinInfo, done: (ok: Boolean, msg: String) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "join", isDaemon = true) {
            var ok = false
            var msg: String
            try {
                val d = ensureReachable(app, deviceFromJoin(info)) { }
                ok = true
                try { Discovery.nudge() } catch (_: Exception) { }   // let the other side see us on this network
                msg = Hub.app.getString(R.string.link_connected_to, d.name)
                if (joinedName != null) scheduleRelease(5 * 60_000L)   // leave time for the other side to send
            } catch (e: IOException) {
                msg = e.message ?: Hub.app.getString(R.string.link_cant_connect)
            } catch (e: Exception) {
                msg = Hub.app.getString(R.string.link_cant_connect)
            }
            Hub.main.post { done(ok, msg) }
        }
    }

    /**
     * Binds [s] to the joined Network (WifiNetworkSpecifier joins), but only when [ip] lives inside
     * that network. Everything else (home LAN devices, scans) stays on the default network.
     * No-op otherwise. Never throws.
     */
    fun bindSocket(s: Socket, ip: String?) {
        try {
            val n = WifiJoin.network
            if (n != null && ip != null && Build.VERSION.SDK_INT >= 23 && WifiJoin.contains(ip)) n.bindSocket(s)
        } catch (_: Exception) { }
    }

    fun releaseJoin() {
        cancelIdleRelease()
        val g = joinGen.get()
        thread(name = "join-release", isDaemon = true) {
            try {
                synchronized(joinLock) {
                    // A newer join / send started while we waited for the lock: leave its link alone.
                    if (joinGen.get() != g || Hub.transfer?.running == true || Hub.pendingSendTo != null) return@synchronized
                    releaseJoinNow(true)
                }
            } catch (_: Exception) { }
        }
    }

    fun releaseJoinSoon() {
        if (joinedName == null) return
        scheduleRelease(90_000L)
    }

    private val idleRelease = object : Runnable {
        override fun run() {
            if (joinedName == null) return
            if (Hub.transfer?.running == true || Hub.pendingSendTo != null) {
                Hub.main.postDelayed(this, 15_000L)   // still in use
            } else releaseJoin()
        }
    }

    private fun scheduleRelease(ms: Long) {
        Hub.main.removeCallbacks(idleRelease)
        Hub.main.postDelayed(idleRelease, ms)
    }

    private fun cancelIdleRelease() = Hub.main.removeCallbacks(idleRelease)

    private fun releaseJoinNow(restartHost: Boolean = false) {
        val had = joinedName != null
        try { P2pLink.leave() } catch (_: Exception) { }
        try { WifiJoin.release() } catch (_: Exception) { }
        joinedName = null; joinedSsid = null
        if (had) {
            Hub.changed()
            Hub.main.post { try { TransferService.stopIfIdle() } catch (_: Exception) { } }
        }
        if (restartHost) {
            // Hosting was stopped to make room for the join; bring it back if a screen still wants it.
            val owner = synchronized(lock) { if (hostState == HostState.OFF) owners.firstOrNull() else null }
            if (owner != null) try { startHosting(Hub.app, owner) } catch (_: Exception) { }
        }
    }
}
