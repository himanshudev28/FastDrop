package com.fastdrop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Receiver-side records of partially received files (protocol 2 resume). One record per file:
 * where the partial lives (MediaStore uri on API 29+, hidden temp file path below) and which 8 MB
 * chunks are fully written. Persisted as JSON in filesDir.
 */
object Resume {
    class Rec(
        val key: String, val name: String, val size: Long, val location: String,
        val chunks: List<IntArray>, val updated: Long,
    )

    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private val lock = Any()
    private var recs: LinkedHashMap<String, Rec>? = null

    private fun file() = File(Hub.app.filesDir, "fd_resume.json")

    private fun loaded(): LinkedHashMap<String, Rec> {
        recs?.let { return it }
        val m = LinkedHashMap<String, Rec>()
        try {
            val f = file()
            if (f.isFile) {
                val a = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val r = Rec(o.getString("key"), o.getString("name"), o.getLong("size"), o.getString("loc"),
                        ProtoV2.Ranges.decode(o.optString("chunks")), o.optLong("updated"))
                    m[r.key] = r
                }
            }
        } catch (_: Exception) { }
        recs = m
        return m
    }

    private fun persist(m: Map<String, Rec>) {
        try {
            val a = JSONArray()
            for (r in m.values) a.put(JSONObject().put("key", r.key).put("name", r.name).put("size", r.size)
                .put("loc", r.location).put("chunks", ProtoV2.Ranges.encode(r.chunks)).put("updated", r.updated))
            val tmp = File(Hub.app.filesDir, "fd_resume.json.tmp")
            tmp.writeText(a.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()) }
        } catch (_: Exception) { }
    }

    fun get(key: String): Rec? = synchronized(lock) { loaded()[key] }

    fun put(r: Rec) = synchronized(lock) { val m = loaded(); m[r.key] = r; persist(m) }

    fun remove(key: String) = synchronized(lock) { val m = loaded(); if (m.remove(key) != null) persist(m) }

    /** Startup: deletes records older than 7 days together with their partial files. */
    fun prune(ctx: Context, now: Long = System.currentTimeMillis()) {
        val old = synchronized(lock) {
            val m = loaded()
            val dead = m.values.filter { now - it.updated > MAX_AGE_MS }
            dead.forEach { m.remove(it.key) }
            if (dead.isNotEmpty()) persist(m)
            dead
        }
        for (r in old) try { Storage.deletePartial(ctx, r.location) } catch (_: Exception) { }
    }
}
