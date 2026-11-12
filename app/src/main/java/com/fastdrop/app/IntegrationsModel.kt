package com.fastdrop.app

import org.json.JSONArray
import org.json.JSONObject

/*
 * Pure (no android.*) logic behind transfer history, Direct Share ranking and the widget status
 * line, so it can be unit-tested on the JVM.
 */

/** One file in a history entry. [uri] is a content:// or file:// string where known. */
data class HFile(
    val name: String, val size: Long, val uri: String? = null,
    val appLabel: String? = null, val appPkg: String? = null, val appVer: String? = null,
) {
    val isApp get() = appPkg != null
}

/** One finished transfer (state is "done", "failed" or "cancelled"). */
data class HistoryEntry(
    val id: String,
    val outgoing: Boolean,
    val peer: String,
    val peerId: String?,
    val files: List<HFile>,        // at most [HistoryCodec.MAX_NAMES]
    val count: Int,                // real number of files (may exceed files.size)
    val total: Long,
    val avgSpeed: Double,          // bytes per second, this session
    val durationMs: Long,
    val at: Long,                  // wall-clock ms when it finished
    val state: String,
    val message: String,
    val text: String? = null,      // text / link transfers (capped at [HistoryCodec.MAX_TEXT_CHARS])
    val textTruncated: Boolean = false,   // [text] was cut; the full text only exists at receive time
) {
    val names: List<String> get() = files.map { it.name }
    val ok get() = state == HistoryCodec.DONE
}

object HistoryCodec {
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val MAX_NAMES = 50
    const val MAX_ENTRIES = 500
    const val MAX_TEXT_CHARS = 4000
    private const val ELLIPSIS = "\u2026"

    /** Text kept in history: at most [MAX_TEXT_CHARS] chars (never splitting a surrogate pair) plus an ellipsis. */
    fun capText(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_TEXT_CHARS) return text to false
        var end = MAX_TEXT_CHARS
        if (Character.isHighSurrogate(text[end - 1])) end--
        return (text.substring(0, end) + ELLIPSIS) to true
    }

    /** Applies [capText] to an entry (no-op if already short or already truncated). */
    fun withCappedText(e: HistoryEntry): HistoryEntry {
        val t = e.text ?: return e
        if (e.textTruncated) return e
        val (c, cut) = capText(t)
        return if (cut) e.copy(text = c, textTruncated = true) else e
    }

    fun encode(list: List<HistoryEntry>): String {
        val a = JSONArray()
        for (e in list) a.put(toJson(e))
        return a.toString()
    }

    /** Tolerant: unreadable entries are skipped, garbage gives an empty list. */
    fun decode(s: String): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                try { out.add(fromJson(a.getJSONObject(i))) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Newest first, at most [max], unique ids. */
    fun capped(list: List<HistoryEntry>, max: Int = MAX_ENTRIES): List<HistoryEntry> {
        val seen = HashSet<String>()
        return list.sortedByDescending { it.at }.filter { seen.add(it.id) }.take(max)
    }

    const val KEEP_FOREVER = 0
    const val KEEP_DEFAULT_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Entries newer than [maxAgeDays] days; 0 (or less) keeps everything. Pure: never touches files. */
    fun pruned(list: List<HistoryEntry>, maxAgeDays: Int, now: Long): List<HistoryEntry> =
        if (maxAgeDays <= 0) list else list.filter { now - it.at <= maxAgeDays * DAY_MS }

    private fun toJson(e: HistoryEntry): JSONObject {
        val o = JSONObject().put("id", e.id).put("out", e.outgoing).put("peer", e.peer)
            .put("n", e.count).put("total", e.total).put("speed", e.avgSpeed).put("dur", e.durationMs)
            .put("at", e.at).put("state", e.state).put("msg", e.message)
        e.peerId?.let { o.put("pid", it) }
        e.text?.let { o.put("text", it) }
        if (e.textTruncated) o.put("tt", true)
        val fs = JSONArray()
        for (f in e.files.take(MAX_NAMES)) {
            val j = JSONObject().put("name", f.name).put("size", f.size)
            f.uri?.let { j.put("uri", it) }
            if (f.appPkg != null) j.put("al", f.appLabel ?: "").put("ap", f.appPkg).put("av", f.appVer ?: "")
            fs.put(j)
        }
        return o.put("files", fs)
    }

    private fun fromJson(o: JSONObject): HistoryEntry {
        val fa = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until fa.length()).map {
            val j = fa.getJSONObject(it)
            val pkg = if (j.has("ap")) j.optString("ap").takeIf { p -> p.isNotEmpty() } else null
            HFile(j.getString("name"), j.optLong("size"), if (j.has("uri")) j.optString("uri") else null,
                if (pkg != null) j.optString("al") else null, pkg, if (pkg != null) j.optString("av") else null)
        }
        return HistoryEntry(
            id = o.getString("id"), outgoing = o.optBoolean("out"), peer = o.optString("peer"),
            peerId = if (o.has("pid")) o.optString("pid") else null,
            files = files, count = o.optInt("n", files.size), total = o.optLong("total"),
            avgSpeed = o.optDouble("speed", 0.0), durationMs = o.optLong("dur"), at = o.optLong("at"),
            state = o.optString("state", FAILED), message = o.optString("msg"),
            text = if (o.has("text")) o.optString("text") else null,
            textTruncated = o.optBoolean("tt", false),
        ).let { withCappedText(it) }
    }
}

/** A device we sent to, kept so it can be offered as a Direct Share target. */
data class RecentDevice(
    val id: String, val name: String, val type: String, val ip: String, val port: Int,
    val lastUsed: Long,
)

object RecentRank {
    const val KEEP = 12
    const val PUBLISH = 4

    /** Adds / refreshes [d] (latest info wins) and returns the list newest-use first, at most [KEEP]. */
    fun touch(list: List<RecentDevice>, d: RecentDevice): List<RecentDevice> =
        (list.filter { it.id != d.id } + d).sortedByDescending { it.lastUsed }.take(KEEP)

    fun top(list: List<RecentDevice>, n: Int = PUBLISH): List<RecentDevice> =
        list.sortedByDescending { it.lastUsed }.take(n)

    fun encode(list: List<RecentDevice>): String {
        val a = JSONArray()
        for (d in list) a.put(JSONObject().put("id", d.id).put("name", d.name).put("type", d.type)
            .put("ip", d.ip).put("port", d.port).put("t", d.lastUsed))
        return a.toString()
    }

    fun decode(s: String): List<RecentDevice> {
        val out = ArrayList<RecentDevice>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) try {
                val o = a.getJSONObject(i)
                out.add(RecentDevice(o.getString("id"), o.optString("name", "Device"), o.optString("type", "phone"),
                    o.getString("ip"), o.optInt("port", Proto.PORT), o.optLong("t")))
            } catch (_: Exception) { }
        } catch (_: Exception) { }
        return out.sortedByDescending { it.lastUsed }
    }
}

/** Allows at most one update per [intervalMs]; [force] always passes (and restarts the interval). */
class UpdateThrottle(private val intervalMs: Long) {
    private var last = Long.MIN_VALUE / 2
    @Synchronized
    fun tryAcquire(now: Long, force: Boolean = false): Boolean {
        if (!force && now - last < intervalMs) return false
        last = now
        return true
    }
}

object WidgetStatus {
    /** One-line status for the home-screen widget. [pct] is 0..100 or -1 when unknown. */
    fun text(running: Boolean, outgoing: Boolean, pct: Int, connecting: Boolean): String = when {
        running && pct >= 0 -> "${if (outgoing) "Sending" else "Receiving"} $pct %"
        running -> if (outgoing) "Sending…" else "Receiving…"
        connecting -> "Connecting…"
        else -> "Ready"
    }
}
