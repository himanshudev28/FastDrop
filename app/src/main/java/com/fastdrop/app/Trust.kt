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
    // Authenticated offer ids -> time seen. Persisted so a restart can't replay a captured offer.
    private val replayLock = Any()
    private var replay: ReplayCache? = null
    private fun replayFile() = java.io.File(Hub.app.filesDir, "fd_replay.json")

    private fun replayCache(now: Long): ReplayCache = replay ?: run {
        val text = try { replayFile().takeIf { it.isFile }?.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        ReplayCache.decode(text, now).also { replay = it }
    }

    private fun persistReplay(c: ReplayCache) {
        try {
            val dst = replayFile()
            val tmp = java.io.File(dst.parentFile, dst.name + ".tmp")
            tmp.writeText(c.encode(), Charsets.UTF_8)
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
        } catch (_: Exception) { }
    }

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

    /**
     * Trusts [dev] and returns the new key as hex. Refuses (returns null) when [dev] is already
     * trusted, so a spoofed device id can't replace a real key; only an explicit [rePair] may.
     * Today the way to re-pair is to remove the device in Settings first.
     */
    fun add(dev: String, name: String, rePair: Boolean = false): String? = synchronized(lock) {
        if (!rePair && load("trusted").has(dev)) return null
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
        synchronized(replayLock) {
            val c = replayCache(now)
            if (!c.add(id, now)) return false
            persistReplay(c)
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
