package com.fastdrop.app

import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol 2 math (see docs/PROTOCOL.md). Pure Kotlin/JVM: no android.* imports, so it is unit-testable.
 * Every function here is per-connection or per-chunk work; nothing runs per byte except [crc32].
 */
object ProtoV2 {
    const val VERSION = 2
    const val CHUNK = 8388608
    const val MAX_TEXT = 100_000
    const val TS_WINDOW_MS = 24L * 3600 * 1000
    val CAPS = listOf("text", "resume", "trust", "crc", "ctl")

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xff
            c[i * 2] = HEX[v ushr 4]; c[i * 2 + 1] = HEX[v and 15]
        }
        return String(c)
    }

    /** Lowercase or uppercase hex to bytes; null if malformed. */
    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16); val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isHex64(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** 4-digit code both sides derive from the session id. */
    fun confirmationCode(id: String): String {
        val d = sha256(utf8(id))
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 10000).toString().padStart(4, '0')
    }

    /** [files] = (name, size) exactly as they appear in the offer. */
    fun filesDigest(files: List<Pair<String, Long>>, text: String?): String {
        val sb = StringBuilder()
        for ((name, size) in files) sb.append(name).append('\n').append(size).append('\n')
        if (text != null) sb.append("T\n").append(text)
        return hex(sha256(utf8(sb.toString())))
    }

    fun canonical(id: String, dev: String, ts: Long, filesDigest: String): String =
        "fastdrop-auth-v1\n$id\n$dev\n$ts\n$filesDigest"

    fun hmacHex(key: ByteArray, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(utf8(canonical)))
    }

    fun auth(key: ByteArray, id: String, dev: String, ts: Long, files: List<Pair<String, Long>>, text: String?): String =
        hmacHex(key, canonical(id, dev, ts, filesDigest(files, text)))

    /** Constant-time check of an offer's auth value. */
    fun verifyAuth(
        key: ByteArray, id: String, dev: String, ts: Long, files: List<Pair<String, Long>>, text: String?, given: String,
    ): Boolean {
        if (!isHex64(given)) return false
        val expected = utf8(auth(key, id, dev, ts, files, text))
        return MessageDigest.isEqual(expected, utf8(given.lowercase()))
    }

    /** Text limits count Unicode code points (like the spec, Mac and Python), not UTF-16 units. */
    fun textLenOk(text: String): Boolean = text.isNotEmpty() && text.codePointCount(0, text.length) <= MAX_TEXT

    /**
     * Resume rule: only files whose offer includes a positive mtime may resume; anything else starts
     * fresh (a provider without a modified time could otherwise splice two versions of a file).
     */
    fun resumeKeyOrNull(dev: String, name: String, size: Long, mtime: Long?): String? =
        if (mtime != null && mtime > 0) resumeKey(dev, name, size, mtime) else null

    fun tsOk(now: Long, ts: Long): Boolean = ts > 0 && Math.abs(now - ts) <= TS_WINDOW_MS

    /** [mtime] null or <= 0 means "unknown" and contributes an empty string. */
    fun resumeKey(dev: String, name: String, size: Long, mtime: Long?): String =
        hex(sha256(utf8("$dev\n$name\n$size\n${if (mtime != null && mtime > 0) mtime.toString() else ""}")))

    fun crc32(b: ByteArray, off: Int = 0, len: Int = b.size): Long {
        val c = CRC32(); c.update(b, off, len); return c.value
    }

    // ---------------------------------------------------------------- chunk ranges

    fun chunkCount(size: Long): Int = ((size + CHUNK - 1) / CHUNK).toInt()

    /** Length of chunk [idx] of a file of [size] bytes. */
    fun chunkLen(idx: Int, size: Long): Long = minOf(CHUNK.toLong(), size - idx.toLong() * CHUNK)

    /** Done chunk ranges: half-open `[start, end)` chunk-index intervals, sorted and merged. */
    object Ranges {
        fun merge(r: List<IntArray>): List<IntArray> {
            val sorted = r.filter { it.size >= 2 && it[0] >= 0 && it[1] > it[0] }.sortedBy { it[0] }
            val out = ArrayList<IntArray>()
            for (x in sorted) {
                val last = out.lastOrNull()
                if (last != null && x[0] <= last[1]) { if (x[1] > last[1]) last[1] = x[1] }
                else out.add(intArrayOf(x[0], x[1]))
            }
            return out
        }

        fun contains(r: List<IntArray>, idx: Int): Boolean = r.any { idx >= it[0] && idx < it[1] }

        fun count(r: List<IntArray>): Int = r.sumOf { it[1] - it[0] }

        /** Drops everything at or beyond [chunks]. */
        fun clamp(r: List<IntArray>, chunks: Int): List<IntArray> =
            merge(r.map { intArrayOf(it[0], minOf(it[1], chunks)) })

        /** Total payload bytes covered by [r] for a file of [size] bytes. */
        fun bytes(r: List<IntArray>, size: Long): Long {
            var n = 0L
            for (x in clamp(r, chunkCount(size))) for (i in x[0] until x[1]) n += chunkLen(i, size)
            return n
        }

        /** Highest byte offset (exclusive) covered by [r]; 0 when empty. */
        fun maxEnd(r: List<IntArray>, size: Long): Long {
            val c = clamp(r, chunkCount(size))
            return if (c.isEmpty()) 0 else minOf(size, c.last()[1].toLong() * CHUNK)
        }

        fun encode(r: List<IntArray>): String = r.joinToString(",", "[", "]") { "[${it[0]},${it[1]}]" }

        /** Lenient parser for `[[0,12],[15,20]]`; garbage yields an empty list. Result is merged. */
        fun decode(s: String): List<IntArray> {
            val nums = Regex("\\d{1,9}").findAll(s).map { it.value.toInt() }.toList()
            val out = ArrayList<IntArray>()
            var i = 0
            while (i + 1 < nums.size) { out.add(intArrayOf(nums[i], nums[i + 1])); i += 2 }
            return merge(out)
        }
    }

    /** Thread-safe set of done chunks for one file. */
    class ChunkSet(initial: List<IntArray> = emptyList()) {
        private var r: List<IntArray> = Ranges.merge(initial)

        /** True if [idx] was not there before. */
        @Synchronized fun add(idx: Int): Boolean {
            if (Ranges.contains(r, idx)) return false
            r = Ranges.merge(r + listOf(intArrayOf(idx, idx + 1)))
            return true
        }

        @Synchronized fun snapshot(): List<IntArray> = r.map { it.clone() }
        @Synchronized fun count(): Int = Ranges.count(r)
    }

    fun isHttpUrl(s: String): Boolean {
        val t = s.trim()
        val low = t.lowercase()
        return (low.startsWith("http://") || low.startsWith("https://")) && low.length > 8 && t.none { it.isWhitespace() }
    }
}

/**
 * Seen authenticated session ids with the time each was seen (replay protection). Pure: the caller
 * persists [encode] and restores with [decode]. Entries expire after [windowMs] and the cache keeps
 * at most [max] (oldest dropped first).
 */
class ReplayCache(private val max: Int = MAX, private val windowMs: Long = ProtoV2.TS_WINDOW_MS) {
    private val m = LinkedHashMap<String, Long>()

    @Synchronized
    fun prune(now: Long) {
        m.values.removeAll { now - it > windowMs }
        if (m.size > max) {
            val drop = m.entries.sortedBy { it.value }.take(m.size - max).map { it.key }
            drop.forEach { m.remove(it) }
        }
    }

    /** Records [id] and returns true if it is new; false if it was already seen inside the window. */
    @Synchronized
    fun add(id: String, now: Long): Boolean {
        prune(now)
        if (m.containsKey(id)) return false
        m[id] = now
        prune(now)
        return true
    }

    @Synchronized fun size() = m.size
    @Synchronized fun contains(id: String) = m.containsKey(id)

    @Synchronized
    fun encode(): String {
        val a = org.json.JSONArray()
        for ((k, v) in m) a.put(org.json.JSONObject().put("id", k).put("t", v))
        return a.toString()
    }

    companion object {
        const val MAX = 2000

        /** Tolerant: garbage gives an empty cache; expired entries are dropped. */
        fun decode(s: String?, now: Long, max: Int = MAX, windowMs: Long = ProtoV2.TS_WINDOW_MS): ReplayCache {
            val c = ReplayCache(max, windowMs)
            if (s.isNullOrEmpty()) return c
            try {
                val a = org.json.JSONArray(s)
                val tmp = ArrayList<Pair<String, Long>>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("id"); val t = o.optLong("t")
                    if (id.isNotEmpty() && t > 0) tmp.add(id to t)
                }
                tmp.sortBy { it.second }
                for ((id, t) in tmp) c.m[id] = t
            } catch (_: Exception) { }
            c.prune(now)
            return c
        }
    }
}
