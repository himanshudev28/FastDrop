package com.fastdrop.app

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Local-only hotspot (API 26+): the fallback network we host when Wi-Fi Direct is not available.
 * No internet sharing, the system picks the SSID and password.
 */
internal object HotspotLink {
    @Volatile var lastError: String? = null

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val apNames = listOf("ap", "swlan", "wlan1", "wlan2", "softap")

    private fun ifaceNames(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.map { it.name }.toSet()
    } catch (_: Exception) { emptySet() }

    /** Finds the access point's interface and IPv4: a new interface, or one named like an AP. */
    private fun findHost(before: Set<String>, cancelled: () -> Boolean): Pair<String?, String?> {
        for (i in 0 until 20) {
            if (cancelled()) break
            try {
                for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val n = ni.name.lowercase()
                    if (n.contains("p2p") || n.startsWith("rmnet") || n.startsWith("lo")) continue
                    val isNew = ni.name !in before
                    if (!isNew && apNames.none { n.startsWith(it) }) continue
                    val ip = ni.interfaceAddresses.map { it.address }
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    if (ip != null) return Pair(ni.name, ip.hostAddress)
                }
            } catch (_: Exception) { }
            Thread.sleep(250)
        }
        return Pair(null, null)
    }

    private fun failText(reason: Int) = when (reason) {
        1 -> Hub.app.getString(R.string.hot_no_channel)
        3 -> Hub.app.getString(R.string.hot_turn_off_first)
        4 -> Hub.app.getString(R.string.hot_blocked)
        else -> Hub.app.getString(R.string.hot_cant_start)
    }

    @Suppress("DEPRECATION")
    @TargetApi(26)
    private fun startApi26(ctx: Context, cancelled: () -> Boolean): Links.HostNet? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) { lastError = Hub.app.getString(R.string.hot_wifi_unavailable); return null }
        stop()
        val before = ifaceNames()
        var got: WifiManager.LocalOnlyHotspotReservation? = null
        var fail = -1

        /** One start attempt. On Android 16+ we can ask for 5 GHz, which is 3–5x faster than 2.4 GHz. */
        fun attempt(fiveGhz: Boolean): Boolean {
            val latch = CountDownLatch(1)
            val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
            fail = -1
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation?) {
                    if (abandoned.get()) { try { r?.close() } catch (_: Exception) { }; return }
                    got = r; latch.countDown()
                }
                override fun onStopped() { Links.hostLost(JoinInfo.MODE_HOTSPOT, Hub.app.getString(R.string.hot_stopped)) }
                override fun onFailed(reason: Int) { fail = reason; latch.countDown() }
            }
            try {
                if (fiveGhz && Build.VERSION.SDK_INT >= 36) {
                    val bands = android.util.SparseIntArray().apply {
                        put(android.net.wifi.SoftApConfiguration.BAND_5GHZ, 0)   // 0 = any 5 GHz channel
                    }
                    val cfg = android.net.wifi.SoftApConfiguration.Builder().setChannels(bands).build()
                    wm.startLocalOnlyHotspotWithConfiguration(cfg, { r -> Hub.main.post(r) }, cb)
                } else {
                    wm.startLocalOnlyHotspot(cb, Hub.main)
                }
            } catch (_: SecurityException) {
                lastError = Hub.app.getString(R.string.hot_allow_nearby); return false
            } catch (_: Exception) {
                lastError = Hub.app.getString(R.string.hot_cant_start); return false
            }
            val end = SystemClock.elapsedRealtime() + 15_000
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelled() || SystemClock.elapsedRealtime() > end) break
            }
            if (got == null) {
                abandoned.set(true)
                lastError = if (fail >= 0) failText(fail) else Hub.app.getString(R.string.hot_too_slow)
            }
            return got != null
        }

        val ok = (Build.VERSION.SDK_INT >= 36 && Hub.prefer5Ghz && !cancelled() && attempt(true)) ||
            (!cancelled() && attempt(false))
        val r = got
        if (!ok || r == null) return null
        var ssid: String? = null
        var pass: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = r.softApConfiguration
                ssid = c.ssid
                pass = c.passphrase
            } else {
                val c = r.wifiConfiguration
                ssid = c?.SSID?.trim('"')
                pass = c?.preSharedKey?.trim('"')
            }
        } catch (_: Exception) { }
        if (ssid.isNullOrEmpty()) {
            try { r.close() } catch (_: Exception) { }
            lastError = Hub.app.getString(R.string.hot_cant_read)
            return null
        }
        reservation = r
        val (iface, ip) = findHost(before, cancelled)
        return Links.HostNet(JoinInfo.MODE_HOTSPOT, ssid, pass?.takeIf { it.isNotEmpty() }, ip, null, 0, iface)
    }

    /** Starts the local-only hotspot. Blocking; returns null with [lastError] set on failure. */
    fun start(ctx: Context, cancelled: () -> Boolean): Links.HostNet? {
        lastError = null
        if (Build.VERSION.SDK_INT < 26) { lastError = Hub.app.getString(R.string.hot_needs_8); return null }
        return try { startApi26(ctx, cancelled) } catch (_: Exception) { lastError = Hub.app.getString(R.string.hot_cant_start); null }
    }

    @android.annotation.SuppressLint("NewApi")
    fun stop() {
        val r = reservation
        reservation = null
        try { r?.close() } catch (_: Exception) { }
    }
}

/**
 * Joining someone else's network as an ordinary Wi-Fi client:
 *  - API 29+: WifiNetworkSpecifier (system dialog once). Sockets must be bound via Links.bindSocket.
 *  - API < 29: legacy WifiManager.addNetwork; the previous network is restored on release.
 */
internal object WifiJoin {
    @Volatile var network: Network? = null
    @Volatile var lastError: String? = null

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var appCtx: Context? = null
    private var legacyActive = false
    private var legacyId = -1
    private var prevId = -1
    @Volatile private var legacyGateway: String? = null

    @Synchronized
    @TargetApi(29)
    fun joinSpecifier(ctx: Context, ssid: String, pass: String?, timeoutMs: Long, cancelled: () -> Boolean): Network? {
        release()
        lastError = null
        val app = ctx.applicationContext
        appCtx = app
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) { lastError = Hub.app.getString(R.string.hot_no_netservice); return null }
        val spec = try {
            val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!pass.isNullOrEmpty()) b.setWpa2Passphrase(pass)
            b.build()
        } catch (_: Exception) { lastError = Hub.app.getString(R.string.hot_details_rejected); return null }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()

        val latch = CountDownLatch(1)
        var got: Network? = null
        var unavailable = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { got = n; network = n; latch.countDown() }
            override fun onUnavailable() { unavailable = true; latch.countDown() }
            override fun onLost(n: Network) {
                if (network == n) { network = null; Links.onJoinLost() }
            }
        }
        callback = cb
        try {
            cm.requestNetwork(req, cb, Hub.main, timeoutMs.toInt())
        } catch (_: Exception) {
            callback = null
            lastError = Hub.app.getString(R.string.hot_cant_ask_join)
            return null
        }
        val end = SystemClock.elapsedRealtime() + timeoutMs + 3000
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (cancelled() || SystemClock.elapsedRealtime() > end) break
        }
        val n = got
        if (n == null) {
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }
            callback = null
            network = null
            lastError = if (unavailable) Hub.app.getString(R.string.hot_prompt_declined) else Hub.app.getString(R.string.hot_join_timeout)
            return null
        }
        return n
    }

    /** Joins with the old WifiManager API. Returns the gateway IP once connected, else null. */
    @Synchronized
    @Suppress("DEPRECATION")
    fun joinLegacy(ctx: Context, ssid: String, pass: String?, timeoutMs: Long, cancelled: () -> Boolean): String? {
        release()
        lastError = null
        val app = ctx.applicationContext
        appCtx = app
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) { lastError = Hub.app.getString(R.string.hot_wifi_unavailable); return null }
        try {
            prevId = try { wm.connectionInfo?.networkId ?: -1 } catch (_: Exception) { -1 }
            val quoted = "\"$ssid\""
            val c = WifiConfiguration()
            c.SSID = quoted
            if (pass.isNullOrEmpty()) c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            else c.preSharedKey = "\"$pass\""
            var id = wm.addNetwork(c)
            if (id == -1) {
                // Already saved earlier: reuse it.
                id = try { wm.configuredNetworks?.firstOrNull { it.SSID == quoted }?.networkId ?: -1 } catch (_: Exception) { -1 }
            }
            if (id == -1) { lastError = Hub.app.getString(R.string.hot_wouldnt_add); return null }
            legacyId = id
            legacyActive = true
            wm.disconnect()
            wm.enableNetwork(id, true)
            wm.reconnect()
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end) {
                if (cancelled()) break
                val ci = wm.connectionInfo
                if (ci != null && ci.networkId == id && ci.supplicantState == SupplicantState.COMPLETED) {
                    val gw = wm.dhcpInfo?.gateway ?: 0
                    if (gw != 0) {
                        val s = "${gw and 255}.${(gw shr 8) and 255}.${(gw shr 16) and 255}.${(gw shr 24) and 255}"
                        legacyGateway = s
                        return s
                    }
                }
                Thread.sleep(400)
            }
            lastError = Hub.app.getString(R.string.hot_join_timeout)
        } catch (_: SecurityException) {
            lastError = Hub.app.getString(R.string.hot_blocked_join)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = Hub.app.getString(R.string.msg_cancelled)
        } catch (_: Exception) {
            lastError = Hub.app.getString(R.string.hot_cant_join)
        }
        release()
        return null
    }

    /** True when [ip] (dotted IPv4) is inside the joined network's subnet or is its gateway. Never throws. */
    fun contains(ip: String): Boolean {
        val n = network ?: return false
        return try {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            var t = 0
            for (x in parts) t = (t shl 8) or (x.toIntOrNull()?.takeIf { it in 0..255 } ?: return false)
            if (ip == gateway()) return true
            val cm = appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val lp = cm.getLinkProperties(n) ?: return false
            lp.linkAddresses.any { la ->
                val a4 = la.address as? Inet4Address ?: return@any false
                val b = a4.address
                var mine = 0
                for (i in 0 until 4) mine = (mine shl 8) or (b[i].toInt() and 255)
                val p = la.prefixLength.coerceIn(1, 32)
                val mask = if (p == 32) -1 else (-1 shl (32 - p))
                (mine and mask) == (t and mask)
            }
        } catch (_: Exception) { false }
    }

    /** Gateway of the joined network (the host's address inside it), if we can tell. */
    fun gateway(): String? {
        legacyGateway?.let { return it }
        val n = network ?: return null
        return try {
            val cm = appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val lp = cm.getLinkProperties(n) ?: return null
            lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                ?: lp.dnsServers.firstOrNull { it is Inet4Address }?.hostAddress
        } catch (_: Exception) { null }
    }

    /** Leaves the joined network: unregisters the callback, unbinds, restores the previous Wi-Fi. */
    @Synchronized
    @Suppress("DEPRECATION")
    fun release() {
        val app = appCtx
        val cb = callback
        callback = null
        if (cb != null && app != null) {
            try { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
            catch (_: Exception) { }
        }
        network = null
        legacyGateway = null
        if (legacyActive && app != null) {
            legacyActive = false
            try {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    wm.removeNetwork(legacyId)
                    if (prevId != -1) wm.enableNetwork(prevId, true)
                    wm.reconnect()
                }
            } catch (_: Exception) { }
        }
        legacyActive = false
        legacyId = -1
        prevId = -1
    }
}
