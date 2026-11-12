package com.fastdrop.app

import android.net.Uri

/**
 * Everything another device needs to reach us: who we are, our LAN addresses and, when we host
 * a Wi-Fi Direct group or a local hotspot, the network to join. Travels in QR codes as a
 * `fastdrop://join?...` link and (for Wi-Fi Direct) in the DNS-SD TXT record.
 *
 * The device that SHOWS the code hosts the network; the device that SCANS it joins. Once joined
 * both are on one network, so either side can send.
 */
class JoinInfo(
    val id: String,
    val name: String,
    val type: String = "phone",
    val port: Int = Proto.PORT,
    val lanIps: List<String> = emptyList(),
    val mode: String? = null,          // MODE_P2P, MODE_HOTSPOT, or null = LAN only
    val ssid: String? = null,
    val pass: String? = null,
    val hostIp: String? = null,        // our address inside the hosted network, e.g. 192.168.49.1
    val mac: String? = null,           // Wi-Fi Direct device address (helps pre-Android-10 joiners)
    val freq: Int = 0,                 // hosted network frequency in MHz (0 = unknown); narrows the joiner's scan
) {
    val hasNetwork: Boolean get() = mode != null && !ssid.isNullOrEmpty()

    fun toUri(): String {
        val b = Uri.Builder().scheme(SCHEME).authority("join")
            .appendQueryParameter("v", "2")
            .appendQueryParameter("i", id)
            .appendQueryParameter("n", name)
            .appendQueryParameter("t", type)
        if (port != Proto.PORT) b.appendQueryParameter("p", port.toString())
        if (lanIps.isNotEmpty()) b.appendQueryParameter("l", lanIps.joinToString(","))
        if (hasNetwork) {
            b.appendQueryParameter("m", mode)
            b.appendQueryParameter("s", ssid)
            if (!pass.isNullOrEmpty()) b.appendQueryParameter("k", pass)
            if (!hostIp.isNullOrEmpty()) b.appendQueryParameter("h", hostIp)
            if (!mac.isNullOrEmpty()) b.appendQueryParameter("a", mac)
            if (freq > 0) b.appendQueryParameter("f", freq.toString())
        }
        return b.build().toString()
    }

    /** Standard Wi-Fi QR payload, so phones/laptops without FastDrop can join with their camera. */
    fun wifiQr(): String? {
        val s = ssid ?: return null
        if (!hasNetwork) return null
        val p = pass
        return if (p.isNullOrEmpty()) "WIFI:T:nopass;S:${esc(s)};;"
        else "WIFI:T:WPA;S:${esc(s)};P:${esc(p)};;"
    }

    fun copy(
        lanIps: List<String> = this.lanIps, mode: String? = this.mode, ssid: String? = this.ssid,
        pass: String? = this.pass, hostIp: String? = this.hostIp, mac: String? = this.mac,
        name: String = this.name, freq: Int = this.freq,
    ) = JoinInfo(id, name, type, port, lanIps, mode, ssid, pass, hostIp, mac, freq)

    override fun toString() = "JoinInfo($name, id=$id, lan=$lanIps, mode=$mode, ssid=$ssid, host=$hostIp)"

    companion object {
        const val SCHEME = "fastdrop"
        const val MODE_P2P = "p2p"
        const val MODE_HOTSPOT = "hs"

        private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
        private val MAC = Regex("""^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$""")

        fun isIpv4(s: String?) = s != null && IPV4.matches(s)

        private fun esc(s: String) = buildString {
            for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) }
        }

        /** Parses a scanned QR / deep link. Returns null for anything that isn't a FastDrop code. */
        fun parse(text: String?): JoinInfo? {
            if (text.isNullOrBlank()) return null
            return try {
                val u = Uri.parse(text.trim())
                if (!SCHEME.equals(u.scheme, ignoreCase = true) || !"join".equals(u.host, ignoreCase = true)) return null
                val id = u.getQueryParameter("i")?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: return null
                val name = (u.getQueryParameter("n") ?: "Device").trim().take(40).ifEmpty { "Device" }
                val type = if (u.getQueryParameter("t") == "tv") "tv" else "phone"
                val port = u.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Proto.PORT
                val lan = u.getQueryParameter("l")?.split(',')?.map { it.trim() }?.filter { isIpv4(it) }?.take(8) ?: emptyList()
                val mode = u.getQueryParameter("m")?.takeIf { it == MODE_P2P || it == MODE_HOTSPOT }
                val ssid = u.getQueryParameter("s")?.takeIf { it.isNotEmpty() && it.length <= 64 }
                val pass = u.getQueryParameter("k")?.takeIf { it.length in 8..63 }
                val host = u.getQueryParameter("h")?.takeIf { isIpv4(it) }
                val mac = u.getQueryParameter("a")?.takeIf { MAC.matches(it) }
                val freq = u.getQueryParameter("f")?.toIntOrNull()?.takeIf { it in 2400..7200 } ?: 0
                JoinInfo(id, name, type, port, lan, if (ssid != null) mode else null, ssid, pass, host, mac, freq)
            } catch (_: Exception) { null }
        }
    }
}
