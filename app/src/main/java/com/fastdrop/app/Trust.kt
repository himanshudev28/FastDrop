package com.fastdrop.app

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Trusted devices (protocol 2). Directional: this device trusts senders it handed a key to
 * ([trusted]), and holds keys other receivers gave us ([keys]).
 */
object Trust {
    class Entry(val dev: String, val name: String, val key: String, val added: Long)

    private val prefs get() = Hub.app.getSharedPreferences("fastdrop_trust", Context.MODE_PRIVATE)
    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()   // authenticated offer ids -> time seen (replay cache)

    // ---- receiver side: senders we trust

    private fun load(k: String): JSONObject = try { JSONObject(prefs.getString(k, null) ?: "{}") } catch (_: Exception) { JSONObject() }

    fun list(): List<Entry> = synchronized(lock) {
        val o = load("trusted")
        o.keys().asSequence().mapNotNull { dev ->
            val e = o.optJSONObject(dev) ?: return@mapNotNull null
            Entry(dev, e.optString("name", "Device"), e.optString("key"), e.optLong("added"))
        }.sortedByDescending { it.added }.toList()
    }

    fun hasAny(): Boolean = synchronized(lock) { load("trusted").length() > 0 }

    fun get(dev: String): Entry? = synchronized(lock) {
        val e = load("trusted").optJSONObject(dev) ?: return null
        Entry(dev, e.optString("name", "Device"), e.optString("key"), e.optLong("added"))
    }

    /** Trusts [dev] (replacing any older key) and returns the new key as hex. */
    fun add(dev: String, name: String): String = synchronized(lock) {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = ProtoV2.hex(key)
        val o = load("trusted")
        o.put(dev, JSONObject().put("name", name.take(60)).put("key", hex).put("added", System.currentTimeMillis()))
        prefs.edit().putString("trusted", o.toString()).apply()
        hex
    }

    fun remove(dev: String) = synchronized(lock) {
        val o = load("trusted")
        o.remove(dev)
        prefs.edit().putString("trusted", o.toString()).apply()
    }

    /**
     * True if [auth] proves the offer comes from a trusted device: key known, HMAC matches (constant
     * time), timestamp within 24 h, and this id was not seen in an authenticated offer before.
     */
    fun verifyOffer(
        dev: String, id: String, ts: Long, files: List<Pair<String, Long>>, text: String?, auth: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val e = get(dev) ?: return false
        val key = ProtoV2.unhex(e.key) ?: return false
        if (!ProtoV2.verifyAuth(key, id, dev, ts, files, text, auth)) return false
        if (!ProtoV2.tsOk(now, ts)) return false
        synchronized(seen) {
            val it = seen.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > ProtoV2.TS_WINDOW_MS) it.remove() else break
            if (seen.containsKey(id)) return false
            seen[id] = now
        }
        return true
    }

    // ---- sender side: keys receivers gave us

    fun keyFor(receiverId: String): ByteArray? = synchronized(lock) {
        ProtoV2.unhex(load("keys").optString(receiverId))?.takeIf { it.size == 32 }
    }

    fun setKeyFor(receiverId: String, hex: String) = synchronized(lock) {
        if (!ProtoV2.isHex64(hex) || receiverId.isEmpty()) return
        val o = load("keys")
        o.put(receiverId, hex.lowercase())
        prefs.edit().putString("keys", o.toString()).apply()
    }
}
