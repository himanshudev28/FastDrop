package com.fastdrop.app

import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.Locale

/**
 * Wire protocol (plain TCP on the LAN, no TLS -> no CPU bottleneck on TVs):
 *
 *  Every connection starts with a 4-byte magic.
 *  HELLO: client sends magic, server answers [int len][json device info].
 *  CTRL : sender sends magic + [len][offer json], receiver answers [len][{"accept":bool}],
 *         and after all bytes arrive, [len][{"ok":true}].
 *  DATA : sender opens N of these in parallel. magic + [int idLen][id bytes], then frames:
 *         [int fileIndex][long offset][int length][raw bytes...], ended by fileIndex = -1.
 */
object Proto {
    const val PORT = 53530
    const val DISC_PORT = 53531
    const val MCAST = "224.0.0.168"          // link-local: switches always flood it

    const val MAGIC_CTRL = 0x46444331         // "FDC1"
    const val MAGIC_DATA = 0x46444431         // "FDD1"
    const val MAGIC_HELLO = 0x46444831        // "FDH1"

    const val CHUNK = 8 shl 20                // 8 MB work units shared by the parallel streams
    const val SOCK_BUF = 4 shl 20             // big TCP windows for fast Wi-Fi
    const val IO_BUF = 1 shl 20
    const val MAX_JSON = 32 shl 20
}

object Io {
    fun readFully(ch: SocketChannel, b: ByteBuffer) {
        while (b.hasRemaining()) if (ch.read(b) < 0) throw EOFException("Connection closed")
    }

    fun writeFully(ch: SocketChannel, b: ByteBuffer) {
        while (b.hasRemaining()) ch.write(b)
    }

    fun readInt(ch: SocketChannel): Int {
        val b = ByteBuffer.allocate(4); readFully(ch, b); b.flip(); return b.int
    }

    fun readJson(ch: SocketChannel, max: Int = Proto.MAX_JSON): JSONObject {
        val n = readInt(ch)
        if (n < 0 || n > max) throw IOException("Bad message")
        val b = ByteBuffer.allocate(n); readFully(ch, b)
        return JSONObject(String(b.array(), Charsets.UTF_8))
    }

    fun writeJson(ch: SocketChannel, o: JSONObject) {
        val d = o.toString().toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(d.size + 4)
        b.putInt(d.size); b.put(d); b.flip()
        writeFully(ch, b)
    }

    fun readJson(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        if (n < 0 || n > Proto.MAX_JSON) throw IOException("Bad message")
        val b = ByteArray(n); inp.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    fun writeJson(out: DataOutputStream, o: JSONObject) {
        val d = o.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(d.size); out.write(d); out.flush()
    }

    fun closeQuietly(c: Closeable?) {
        try { c?.close() } catch (_: Exception) { }
    }

    fun bytes(n: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$n B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
    }

    fun speed(bytesPerSec: Double): String {
        val mb = bytesPerSec / (1024 * 1024)
        return if (mb >= 1) String.format(Locale.US, "%.1f MB/s", mb)
        else String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024)
    }

    fun duration(sec: Long): String {
        if (sec < 0) return "--"
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}

object Net {
    class Addr(val ip: InetAddress, val broadcast: InetAddress?, val prefix: Int, val iface: NetworkInterface)

    // Mobile-data / VPN interfaces often carry private 10.x addresses too; never use them.
    private val skip = listOf("rmnet", "ccmni", "pdp", "tun", "dummy", "v4-", "clat", "ppp", "ipsec", "lo")

    fun localAddrs(): List<Addr> {
        val out = ArrayList<Addr>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifs) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase(Locale.US)
                    if (skip.any { name.startsWith(it) }) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address
                        if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                            out.add(Addr(a, ia.broadcast, ia.networkPrefixLength.toInt(), ni))
                        }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return out.sortedBy { rank(it.iface.name) }
    }

    /** wlan first, then ethernet, then Wi-Fi Direct / hotspot interfaces, then anything else. */
    private fun rank(n: String): Int = when {
        n.startsWith("wlan") -> 0
        n.startsWith("eth") -> 1
        n.startsWith("p2p") || n.startsWith("ap") || n.startsWith("swlan") -> 2
        else -> 3
    }

    /** IPv4 addresses other devices can try (for QR codes). [excludeIface] drops a hosted P2P/hotspot interface. */
    fun lanIps(excludeIface: String? = null): List<String> =
        localAddrs().filter { excludeIface == null || it.iface.name != excludeIface }
            .mapNotNull { it.ip.hostAddress }.distinct()

    /** VIA_P2P if [local] belongs to a Wi-Fi Direct interface, else VIA_LAN. */
    fun viaFor(local: InetAddress?): String = try {
        val n = local?.let { NetworkInterface.getByInetAddress(it)?.name }
        if (n != null && n.lowercase(Locale.US).startsWith("p2p")) Device.VIA_P2P else Device.VIA_LAN
    } catch (_: Exception) { Device.VIA_LAN }

    fun primaryIp(): String? = localAddrs().firstOrNull()?.ip?.hostAddress
}
