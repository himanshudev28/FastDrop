package com.fastdrop.app

import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Finds other FastDrop devices three ways, so it works on routers that block one of them:
 *  1. UDP multicast (224.0.0.168) on every Wi-Fi/Ethernet interface
 *  2. UDP broadcast (subnet + 255.255.255.255)
 *  3. "Scan network": TCP hello to every address in the /24 (and "Add by IP")
 */
object Discovery {
    private val group: InetAddress by lazy { InetAddress.getByName(Proto.MCAST) }
    @Volatile private var rx: MulticastSocket? = null

    @Volatile private var mcLock: android.net.wifi.WifiManager.MulticastLock? = null

    fun start() {
        // Android drops multicast packets unless an app holds this lock. Held for as long as discovery
        // runs, independent of whether the foreground service managed to start.
        try {
            val wm = Hub.app.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            mcLock = wm.createMulticastLock("fastdrop").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        thread(name = "disc-rx", isDaemon = true) { rxLoop() }
        thread(name = "disc-tx", isDaemon = true) { txLoop() }
    }

    private fun packet(kind: String): ByteArray = Hub.info().put("kind", kind).toString().toByteArray(Charsets.UTF_8)

    private fun rxLoop() {
        while (true) {
            try {
                val s = MulticastSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Proto.DISC_PORT))
                rx = s
                joinAll()
                val buf = ByteArray(8192)
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    try { handle(JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)), p.address) } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                Io.closeQuietly(rx); rx = null
                Thread.sleep(2000)
            }
        }
    }

    /**
     * Join the multicast group on each LAN interface. Called every few seconds because Wi-Fi
     * reconnects silently drop the membership; joining twice just throws "already member".
     */
    private fun joinAll() {
        val s = rx ?: return
        for (a in Net.localAddrs()) {
            try { s.joinGroup(InetSocketAddress(group, 0), a.iface) } catch (_: Exception) { }
        }
    }

    private fun handle(o: JSONObject, from: InetAddress) {
        if (o.optString("app") != "fastdrop") return
        val id = o.optString("id")
        if (id.isEmpty() || id == Hub.myId) return
        when (o.optString("kind")) {
            "bye" -> { if (Hub.devices[id]?.manual != true && Hub.devices.remove(id) != null) Hub.changed(); return }
        }
        if (!o.optBoolean("receivable", true)) return
        Hub.upsertDevice(Device(id, o.optString("name", "Device"), o.optString("type", "phone"),
            from.hostAddress ?: return, o.optInt("port", Proto.PORT), SystemClock.elapsedRealtime()))
        if (o.optString("kind") == "announce" && Hub.receivable()) {
            send(packet("reply"), from)  // answer directly so new devices appear instantly
        }
    }

    private fun send(data: ByteArray, to: InetAddress) {
        try { rx?.send(DatagramPacket(data, data.size, to, Proto.DISC_PORT)) } catch (_: Exception) { }
    }

    private val announceLock = Any()

    /** Announce right now (e.g. after joining a new network) instead of waiting for the next tick. */
    fun nudge() {
        thread(name = "disc-nudge", isDaemon = true) {
            try { joinAll(); if (Hub.receivable()) announce("announce") } catch (_: Exception) { }
        }
    }

    private fun announce(kind: String) = synchronized(announceLock) {
        announceLocked(kind)
    }

    private fun announceLocked(kind: String) {
        val s = rx ?: return
        val data = packet(kind)
        val bcast = HashSet<InetAddress>()
        for (a in Net.localAddrs()) {
            try {
                s.networkInterface = a.iface
                s.send(DatagramPacket(data, data.size, group, Proto.DISC_PORT))
            } catch (_: Exception) { }
            a.broadcast?.let { bcast.add(it) }
        }
        try { bcast.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) { }
        bcast.forEach { send(data, it) }
    }

    private fun txLoop() {
        var tick = 0
        var wasReceivable = false
        while (true) {
            try {
                Thread.sleep(2500)
                val now = SystemClock.elapsedRealtime()
                if (tick++ % 2 == 0) joinAll()
                val receivable = Hub.receivable()
                if (receivable) announce("announce")
                else if (wasReceivable) announce("bye")
                wasReceivable = receivable
                // Forget devices we haven't heard from for a while (manual ones stay).
                var removed = false
                // Wi-Fi Direct sightings come from slower service discovery, so they live longer.
                for ((k, d) in Hub.devices) {
                    val limit = if (d.via == Device.VIA_P2P) 30_000 else 10_000
                    if (!d.manual && now - d.lastSeen > limit && Hub.devices.remove(k, d)) removed = true
                }
                if (removed) Hub.changed()
            } catch (_: Exception) { }
        }
    }

    /** Asks one address directly. Adds it to the device list if it's running FastDrop. */
    fun hello(ip: String, timeoutMs: Int = 2000, manual: Boolean = true, port: Int = Proto.PORT): Device? {
        return try {
            Socket().use { s ->
                Links.bindSocket(s, ip)         // only for addresses inside a network we joined with a Wi-Fi specifier
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                s.soTimeout = 2000
                DataOutputStream(s.getOutputStream()).apply { writeInt(Proto.MAGIC_HELLO); flush() }
                val o = Io.readJson(DataInputStream(BufferedInputStream(s.getInputStream())))
                if (o.optString("app") != "fastdrop" || o.optString("id") == Hub.myId) return null
                Device(o.getString("id"), o.optString("name", ip), o.optString("type", "phone"), ip,
                    o.optInt("port", port), SystemClock.elapsedRealtime(), manual).also { Hub.upsertDevice(it) }
            }
        } catch (_: Exception) { null }
    }

    private val scanWaiters = ArrayList<(Int) -> Unit>()
    private var scanning = false

    /**
     * Tries every host on our /24 networks. [done] is called on the main thread with the number found.
     * A second call while a scan runs just waits for the running one.
     */
    fun scan(done: (Int) -> Unit) {
        synchronized(scanWaiters) {
            scanWaiters.add(done)
            if (scanning) return
            scanning = true
        }
        thread(name = "scan", isDaemon = true) {
            val found = AtomicInteger()
            val pool = Executors.newFixedThreadPool(48)
            try {
                val addrs = Net.localAddrs()
                val mine = addrs.map { it.ip.hostAddress }.toSet()
                val seen = HashSet<String>()
                for (a in addrs) {
                    val ip = a.ip as? Inet4Address ?: continue
                    val b = ip.address
                    val net = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}"
                    if (!seen.add(net)) continue
                    for (h in 1..254) {
                        val target = "$net.$h"
                        if (target in mine) continue
                        pool.execute { if (hello(target, 600, manual = false) != null) found.incrementAndGet() }
                    }
                }
                pool.shutdown()
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow()
            } catch (_: Exception) {
                pool.shutdownNow()
            }
            val waiters = synchronized(scanWaiters) {
                scanning = false
                ArrayList(scanWaiters).also { scanWaiters.clear() }
            }
            val n = found.get()
            Hub.main.post { waiters.forEach { it(n) } }
        }
    }
}
