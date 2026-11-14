package com.fastdrop.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Persistent transfer history (both directions; done, failed and cancelled), kept as JSON in
 * filesDir/fd_history.json, newest first, capped at [HistoryCodec.MAX_ENTRIES].
 *
 * API for the UI:
 *  - [all], [remove], [clear], [addListener] / [removeListener] (also calls Hub.changed()).
 *  - [sendAgain] / [sendAgainText]: rebuild what was sent, or null when it can't be read any more.
 *  - [uris]: the stored URIs of an entry, for open / share / install buttons.
 * Recording is automatic: Hub.end() calls [record]; Server / Sender fill [Transfer.histFiles].
 */
object HistoryStore {
    private val lock = Any()
    private var list: List<HistoryEntry> = emptyList()
    private var loaded = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "history-io").apply { isDaemon = true } }

    private fun file(ctx: Context) = File(ctx.filesDir, "fd_history.json")

    /** True once the background load has finished; until then [all] only holds entries recorded since start-up. */
    @Volatile var ready = false
        private set
    /** Changes made before the load finished; replayed over the decoded file so nothing is lost or clobbered. */
    private val pending = ArrayList<(List<HistoryEntry>) -> List<HistoryEntry>>()

    /**
     * Starts loading from disk (once) on a background thread; returns immediately. When done it
     * refills Hub.received (so received text / recent files survive restarts) and calls
     * Hub.changed() on the main thread. Until then [all] is simply empty (plus anything new).
     */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
        }
        val app = ctx.applicationContext
        Thread({
            val decoded: List<HistoryEntry> = try {
                val f = file(app)
                if (f.isFile) HistoryCodec.capped(HistoryCodec.decode(f.readText(Charsets.UTF_8))) else emptyList()
            } catch (_: Exception) { emptyList() }
            val merged: List<HistoryEntry>
            synchronized(lock) {
                var l = decoded
                for (p in pending) l = p(l)
                pending.clear()
                // Drop entries older than the "Keep history" setting before Hub.received is refilled.
                list = HistoryCodec.pruned(l, keepDays(app), System.currentTimeMillis())
                ready = true
                merged = list
            }
            write(app, merged)
            val restored = ArrayList<Received>()
            for (e in merged) {
                if (e.outgoing) continue
                if (e.text != null) {
                    restored.add(Received(textPreview(e.text), e.text.toByteArray(Charsets.UTF_8).size.toLong(), null, e.peer, null, e.at, e.text))
                    continue
                }
                for (f in e.files) {
                    val u = f.uri ?: continue
                    val uri = try { Uri.parse(u) } catch (_: Exception) { continue }
                    if (uri.scheme == "file" && !File(uri.path ?: continue).exists()) continue
                    restored.add(Received(f.name, f.size, uri, e.peer, f.appPkg?.let { AppTag(f.appLabel ?: "", it, f.appVer ?: "") }, e.at))
                }
                if (restored.size >= 200) break
            }
            Hub.main.post {
                // Newest first; anything received since start-up (already in Hub.received) stays on top.
                for (r in restored.take(200)) Hub.received.add(r)
                while (Hub.received.size > 200) Hub.received.removeAt(Hub.received.size - 1)
                for (l in listeners) try { l() } catch (_: Exception) { }
                Hub.changed()
            }
        }, "history-load").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- retention ("Keep history")

    private const val KEY_KEEP = "history_keep_days"

    /** Settings > Keep history: 7, 30 (default) or 0 = forever. */
    fun keepDays(ctx: Context = Hub.app): Int = try {
        ctx.getSharedPreferences("fastdrop", Context.MODE_PRIVATE).getInt(KEY_KEEP, HistoryCodec.KEEP_DEFAULT_DAYS)
    } catch (_: Throwable) { HistoryCodec.KEEP_DEFAULT_DAYS }

    fun setKeepDays(days: Int) {
        Hub.app.getSharedPreferences("fastdrop", Context.MODE_PRIVATE).edit().putInt(KEY_KEEP, days.coerceAtLeast(0)).apply()
        prune(days)
    }

    /**
     * Removes history entries older than [maxAgeDays] days (0 = keep forever) and the matching items
     * from Hub.received. Never deletes any file from storage.
     */
    fun prune(maxAgeDays: Int) {
        val now = System.currentTimeMillis()
        val dropped = all().filter { it !in HistoryCodec.pruned(all(), maxAgeDays, now) }
        if (dropped.isEmpty()) return
        val ids = dropped.map { it.id }.toHashSet()
        mutate { l -> l.filter { it.id !in ids } }
        val uris = dropped.flatMap { e -> e.files.mapNotNull { it.uri } }.toHashSet()
        val texts = dropped.filter { it.text != null }.map { it.at to it.text }.toHashSet()
        Hub.main.post {
            Hub.received.removeAll { r ->
                (r.uri != null && r.uri.toString() in uris) || (r.text != null && (r.at to r.text) in texts)
            }
            Hub.changed()
        }
    }

    fun textPreview(text: String) =
        text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: Hub.app.getString(R.string.main_label_text)

    fun all(): List<HistoryEntry> = synchronized(lock) { list }

    fun remove(id: String) = mutate { l -> l.filter { it.id != id } }

    fun clear() = mutate { emptyList() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun uris(e: HistoryEntry): List<Uri> = e.files.mapNotNull { f -> f.uri?.let { try { Uri.parse(it) } catch (_: Exception) { null } } }

    private fun add(e: HistoryEntry) {
        val c = HistoryCodec.withCappedText(e)      // full text is only needed at receive time
        mutate { l -> HistoryCodec.capped(listOf(c) + l) }
    }

    private fun write(ctx: Context, snapshot: List<HistoryEntry>) {
        io.execute {
            try {
                val dst = file(ctx)
                val tmp = File(dst.parentFile, dst.name + ".tmp")
                tmp.writeText(HistoryCodec.encode(snapshot), Charsets.UTF_8)
                if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
            } catch (_: Exception) { }
        }
    }

    private fun mutate(f: (List<HistoryEntry>) -> List<HistoryEntry>) {
        val snapshot: List<HistoryEntry>
        synchronized(lock) {
            list = f(list); snapshot = list
            if (!ready) pending.add(f)     // replayed after the file loads; don't overwrite the file yet
        }
        if (ready) write(Hub.app, snapshot)
        for (l in listeners) try { l() } catch (_: Exception) { }
        Hub.changed()
    }

    // ---------------------------------------------------------------- recording (called by Hub / Server)

    /** Called by Hub.end() once per transfer. */
    fun record(t: Transfer) {
        val state = when (t.state) {
            State.DONE -> HistoryCodec.DONE
            State.CANCELLED -> HistoryCodec.CANCELLED
            else -> HistoryCodec.FAILED
        }
        val skipped = t.skippedFiles.toSet()
        // Outgoing histFiles are in file order, so a position is a file index. Skipped files were not delivered.
        val files = if (t.outgoing) ArrayList(t.histFiles.filterIndexed { i, _ -> i !in skipped }) else ArrayList<HFile>(t.histFiles)
        if (!t.outgoing) {
            // Files that didn't finish are listed by name only (no uri); skipped ones not at all.
            val have = files.map { it.name }.toHashSet()
            t.names.forEachIndexed { i, n -> if (i !in skipped && files.size < HistoryCodec.MAX_NAMES && have.add(n)) files.add(HFile(n, 0)) }
        }
        val dur = ((if (t.finishedAt > 0) t.finishedAt else SystemClock.elapsedRealtime()) - t.startedAt).coerceAtLeast(0)
        val speed = if (dur > 0) t.sessionDone * 1000.0 / dur else 0.0
        add(HistoryEntry(
            id = UUID.randomUUID().toString(), outgoing = t.outgoing, peer = t.peer, peerId = t.peerId,
            files = files.take(HistoryCodec.MAX_NAMES), count = t.files - skipped.size, total = t.total,
            avgSpeed = speed, durationMs = dur, at = System.currentTimeMillis(),
            state = state, message = t.message, text = t.text,
        ))
    }

    /** Received text has no Transfer: record it directly. */
    fun recordIncomingText(from: String, fromId: String?, text: String) {
        add(HistoryEntry(UUID.randomUUID().toString(), false, from, fromId?.takeIf { it.isNotEmpty() }, emptyList(), 0,
            text.toByteArray(Charsets.UTF_8).size.toLong(), 0.0, 0, System.currentTimeMillis(), HistoryCodec.DONE, Hub.app.getString(R.string.msg_received), text))
    }

    /** Stored form of a [SendItem] (its uri, or a file: uri for cache files). */
    fun fileOf(i: SendItem): HFile {
        val u = i.uri?.toString() ?: i.file?.let { Uri.fromFile(it).toString() }
        return HFile(i.name, i.size, u, i.app?.label, i.app?.pkg, i.app?.version)
    }

    // ---------------------------------------------------------------- send again

    /** Blocking (touches the content resolver): call off the main thread. Null if anything can't be read. */
    fun sendAgain(ctx: Context, e: HistoryEntry): List<SendItem>? {
        if (e.text != null || e.files.isEmpty() || e.count > e.files.size) return null
        val out = ArrayList<SendItem>()
        for (f in e.files) {
            val u = f.uri ?: return null
            val uri = try { Uri.parse(u) } catch (_: Exception) { return null }
            val item = try { Storage.itemFromUri(ctx, uri) } catch (_: Exception) { null } ?: return null
            val tag = f.appPkg?.let { AppTag(f.appLabel ?: "", it, f.appVer ?: "") }
            out.add(SendItem(f.name.ifEmpty { item.name }, item.size, item.uri, item.file, tag ?: item.app))
        }
        return out
    }

    /** The text to send again for a text entry, else null. */
    fun sendAgainText(e: HistoryEntry): String? = if (e.textTruncated) null else e.text?.takeIf { it.isNotEmpty() }
}
