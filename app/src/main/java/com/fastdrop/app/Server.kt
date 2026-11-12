package com.fastdrop.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.zip.CRC32
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Receiving side: accepts offers and writes incoming parallel streams straight to disk. */
object Server {
    private val pool = Executors.newCachedThreadPool()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val sessions = ConcurrentHashMap<String, RxSession>()
    @Volatile var error: String? = null
    private const val MAX_FILE = 1L shl 46   // 64 TiB: far beyond any real file, well below overflow range

    fun start() = thread(name = "server", isDaemon = true) {
        while (true) {
            var ss: ServerSocketChannel? = null
            try {
                ss = ServerSocketChannel.open()
                ss.socket().reuseAddress = true
                ss.socket().receiveBufferSize = Proto.SOCK_BUF   // must be set before bind for big windows
                ss.socket().bind(InetSocketAddress(Proto.PORT), 128)
                error = null
                while (true) {
                    val ch = ss.accept()
                    pool.execute { handle(ch) }
                }
            } catch (e: Exception) {
                error = "Can't listen on port ${Proto.PORT}: ${e.message}"
                Io.closeQuietly(ss)
                Thread.sleep(3000)
            }
        }
    }

    private fun handle(ch: SocketChannel) {
        try {
            ch.socket().keepAlive = true
            // SocketChannel reads ignore soTimeout: drop connections that don't introduce themselves in 10 s.
            val guard = timer.schedule({ Io.closeQuietly(ch) }, 10, TimeUnit.SECONDS)
            when (Io.readInt(ch)) {
                Proto.MAGIC_HELLO -> { guard.cancel(false); Io.writeJson(ch, Hub.info()); ch.close() }
                Proto.MAGIC_CTRL -> handleOffer(ch, guard)
                Proto.MAGIC_DATA -> handleData(ch, guard)
                else -> ch.close()
            }
        } catch (_: Exception) {
            Io.closeQuietly(ch)
        }
    }

    /** Returns false if the sender is already gone. [extra] fields (protocol 2) are merged into the reply. */
    private fun reply(ch: SocketChannel, accept: Boolean, reason: String, v2: Boolean = false, extra: JSONObject? = null): Boolean {
        val o = JSONObject().put("accept", accept).put("reason", reason)
        if (v2) o.put("v", ProtoV2.VERSION).put("dev", Hub.myId)
        extra?.keys()?.forEach { o.put(it, extra.get(it)) }
        val ok = try { Io.writeJson(ch, o); true } catch (_: Exception) { false }
        if (!accept) Io.closeQuietly(ch)
        return ok
    }

    private fun handleOffer(ch: SocketChannel, guard: ScheduledFuture<*>) {
        val o = Io.readJson(ch, 4 shl 20)
        guard.cancel(false)
        val id = o.getString("id")
        val fromName = o.optString("name", "Unknown device").take(60)
        val fromType = o.optString("type", "phone")
        // Protocol 2 is opt-in per offer; anything else is treated exactly like FastDrop 2.0.
        val v2 = o.optInt("v", 1) >= 2
        val senderDev = if (v2) o.optString("dev").take(200) else ""
        val text: String? = if (v2 && o.has("text") && !o.isNull("text")) o.optString("text") else null
        if (text != null && (!ProtoV2.textLenOk(text))) { reply(ch, false, "invalid", v2); return }
        val arr = o.getJSONArray("files")
        if (arr.length() > 10_000) { reply(ch, false, "invalid", v2); return }
        val rawNames = ArrayList<String>(arr.length())
        val mtimes = ArrayList<Long>(arr.length())
        val files = (0 until arr.length()).map {
            val f = arr.getJSONObject(it)
            // Optional app tag (v2 senders): groups base + split APKs into one installable app.
            val tag = f.optJSONObject("app")?.let { a ->
                val pkg = a.optString("p").take(200)
                if (pkg.isEmpty()) null else AppTag(a.optString("l").take(80), pkg, a.optString("v").take(40))
            }
            val raw = f.getString("name")
            rawNames.add(raw)
            mtimes.add(if (v2) f.optLong("mtime", 0L) else 0L)
            RxFile(Storage.cleanName(raw), f.getLong("size"), tag)
        }
        if ((files.isEmpty() && text == null) || (files.isNotEmpty() && text != null) || files.any { it.size < 0 }) { reply(ch, false, "invalid", v2); return }
        if (files.any { it.size > MAX_FILE }) { reply(ch, false, "invalid", v2); return }
        val total = try { files.fold(0L) { a, f -> Math.addExact(a, f.size) } } catch (_: ArithmeticException) { -1L }
        if (total < 0) { reply(ch, false, "invalid", v2); return }

        // Authenticated offer from a device we gave a key to?
        val auth = o.optString("auth")
        val trusted = v2 && senderDev.isNotEmpty() && ProtoV2.isHex64(auth) &&
            Trust.verifyOffer(senderDev, id, o.optLong("ts", 0L), rawNames.zip(files.map { it.size }), text, auth)
        val mode = Hub.autoMode
        // A device id we already trust but whose offer isn't authenticated may be an impostor:
        // never offer "Always accept" for it (that would overwrite the real key).
        val knownDev = v2 && senderDev.isNotEmpty() && Trust.get(senderDev) != null
        val canTrust = v2 && senderDev.isNotEmpty() && !trusted && !knownDev
        val auto = mode == Hub.AUTO_EVERYONE || (mode == Hub.AUTO_TRUSTED && trusted)

        if (!Hub.tryReserve()) { reply(ch, false, "busy", v2); return }
        var reserved = true
        try {
            val accept: Boolean
            var reason = ""
            var shown: IncomingOffer? = null
            if (auto) {
                val needsWrite = text == null && android.os.Build.VERSION.SDK_INT in 23..28 &&
                    Hub.app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsWrite) { accept = false; reason = "storage" } else accept = true
            } else if (Hub.listener == null) {
                accept = false; reason = "closed"
            } else if (!Hub.appVisible() && !TransferService.canNotifyOffer(Hub.app)) {
                // Nobody can see the offer and the notification would be blocked: don't make the sender wait.
                accept = false; reason = "closed"
            } else {
                val offer = IncomingOffer(fromName, fromType, files, if (text != null) 0L else total, text,
                    code = if (v2) ProtoV2.confirmationCode(id) else "", trusted = trusted,
                    canTrust = canTrust, dev = senderDev)
                shown = offer
                var noUi = false
                Hub.offer = offer
                Hub.main.post { Hub.listener?.onOffer(offer) ?: run { noUi = true; offer.respond(false) } }
                // Screen not in front: show a notification with Accept / Decline buttons.
                if (!Hub.appVisible()) TransferService.notifyOffer(Hub.app, offer)
                accept = try { offer.await(60_000) } finally {
                    if (Hub.offer === offer) Hub.offer = null
                    TransferService.cancelOffer(Hub.app)
                }
                if (!accept) reason = when { noUi -> "closed"; offer.timedOut -> "timeout"; else -> "declined" }
            }
            if (!accept) {
                Hub.release(); reserved = false      // free before replying so an instant retry isn't "busy"
                reply(ch, false, reason, v2); return
            }

            val extra = JSONObject()
            // "Always accept from <sender>" ticked: pair by handing the sender a fresh key.
            if (shown?.alwaysAccept == true && canTrust) Trust.add(senderDev, fromName)?.let { extra.put("trust", it) }

            if (text != null) {
                // Text / link: nothing to stream. Accept, confirm immediately, keep it in the recent list.
                Hub.release(); reserved = false
                if (!reply(ch, true, "", true, extra)) return
                try { Io.writeJson(ch, JSONObject().put("ok", true)) } catch (_: Exception) { }
                val preview = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: "Text"
                Hub.addReceived(Received(preview, text.toByteArray(Charsets.UTF_8).size.toLong(), null, fromName, text = text))
                HistoryStore.recordIncomingText(fromName, senderDev, text)     // history hook
                Hub.message("Text from $fromName")
                Io.closeQuietly(ch)
                return
            }

            val via = try { Net.viaFor(ch.socket().localAddress) } catch (_: Exception) { Device.VIA_LAN }
            val t = Transfer(false, fromName, total, files.size, via, files.take(50).map { it.name })
            t.peerId = senderDev.ifEmpty { null }                               // history hook
            // Resume + CRC are protocol 2 only: they need the sender's device id and 8 MB chunks.
            val resumable = v2 && senderDev.isNotEmpty() && o.optInt("chunk", 0) == Proto.CHUNK
            val keys = ArrayList<String?>(files.size)
            val used = HashSet<String>()
            for (i in files.indices) {
                // Only files whose offer carries a positive mtime may resume; the rest always start fresh.
                val k = if (resumable && files[i].size > 0) ProtoV2.resumeKeyOrNull(senderDev, rawNames[i], files[i].size, mtimes[i]) else null
                keys.add(if (k != null && used.add(k)) k else null)
            }
            val crc = v2 && o.optBoolean("crc")
            val s = RxSession(id, files, t, keys, crc)
            sessions[id] = s
            t.register(ch)
            val have = try { s.prepareResume() } catch (_: Exception) { emptyMap() }
            if (have.isNotEmpty()) extra.put("have", JSONObject().also { h ->
                for ((i, r) in have) h.put(i.toString(), JSONArray(ProtoV2.Ranges.encode(r)))
            })
            if (crc) extra.put("crc", true)
            Hub.begin(t)
            reserved = false             // the transfer owns the busy flag now; Hub.end releases it
            if (!reply(ch, true, "", v2, extra)) {
                Hub.end(t, State.CANCELLED, "$fromName cancelled")
                s.closeOut()
                sessions.remove(id)
                return
            }
            val checkpoint = timer.scheduleWithFixedDelay({ try { s.checkpoint() } catch (_: Exception) { } }, 10, 10, TimeUnit.SECONDS)
            try {
                s.start()
                while (!s.complete && t.running) s.waitDone(500)
                if (s.complete && t.running) {
                    try { Io.writeJson(ch, JSONObject().put("ok", true)) } catch (_: Exception) { }
                    Hub.end(t, State.DONE, "Received")
                }
            } catch (e: Exception) {
                Hub.end(t, State.FAILED, "Couldn't save: ${e.message}")
            } finally {
                checkpoint.cancel(false)
                s.closeOut()
                sessions.remove(id)
                Io.closeQuietly(ch)
            }
        } finally {
            if (reserved) Hub.release()
        }
    }

    /** A chunk's CRC32 did not match. */
    private class DataCheckException : IOException("Data check failed")

    private fun handleData(ch: SocketChannel, guard: ScheduledFuture<*>) {
        val idLen = Io.readInt(ch)
        if (idLen !in 1..200) throw IOException("bad id")
        val idBuf = ByteBuffer.allocate(idLen); Io.readFully(ch, idBuf)
        val s = sessions[String(idBuf.array(), Charsets.UTF_8)] ?: throw IOException("unknown session")
        guard.cancel(false)   // from here the transfer watchdog handles stalls
        val t = s.t
        t.register(ch)

        val hdr = ByteBuffer.allocate(16)
        // CRC off: the original zero-extra-work loop with a direct buffer. CRC on: a heap buffer we can checksum.
        val crcOn = s.crc
        val buf = if (crcOn) ByteBuffer.allocate(Proto.IO_BUF) else ByteBuffer.allocateDirect(Proto.IO_BUF)
        val crc = if (crcOn) CRC32() else null
        val tail = ByteBuffer.allocate(4)
        try {
            while (t.running) {
                hdr.clear(); Io.readFully(ch, hdr); hdr.flip()
                val fi = hdr.int; val off = hdr.long; val len = hdr.int
                if (fi < 0) break
                if (fi >= s.files.size || off < 0 || len < 0 || off > s.files[fi].size - len) throw IOException("bad frame")
                if (!s.frameOk(fi, off, len)) throw IOException("bad frame")
                val fc = s.sink(fi).channel
                var pos = off
                var rem = len.toLong()
                if (crc != null) {
                    crc.reset()
                    while (rem > 0) {
                        buf.clear()
                        if (rem < buf.capacity()) buf.limit(rem.toInt())
                        val n = ch.read(buf)
                        if (n < 0) throw EOFException("Sender disconnected")
                        crc.update(buf.array(), 0, n)
                        buf.flip()
                        while (buf.hasRemaining()) pos += fc.write(buf, pos)
                        rem -= n
                        t.add(n.toLong())
                    }
                    tail.clear(); Io.readFully(ch, tail); tail.flip()
                    if ((tail.int.toLong() and 0xffffffffL) != crc.value) throw DataCheckException()
                } else {
                    while (rem > 0) {
                        buf.clear()
                        if (rem < buf.capacity()) buf.limit(rem.toInt())
                        val n = ch.read(buf)
                        if (n < 0) throw EOFException("Sender disconnected")
                        buf.flip()
                        while (buf.hasRemaining()) pos += fc.write(buf, pos)
                        rem -= n
                        t.add(n.toLong())
                    }
                }
                // Done only now: the whole payload is on disk (and passed its CRC, if checking).
                s.chunkDone(fi, off, len.toLong())
            }
        } catch (e: Exception) {
            if (t.running && !s.complete) {
                s.fail(if (e is DataCheckException) "Data check failed" else "Transfer interrupted: ${e.message ?: "connection lost"}")
            }
        } finally {
            // A stream ending cleanly is normal (others may still be sending); real stalls hit the watchdog.
            Io.closeQuietly(ch)
        }
    }
}

class RxSession(
    val id: String, val files: List<RxFile>, val t: Transfer,
    /** Resume key per file; null = this file is not resumable (v1 session, empty file, duplicate). */
    private val keys: List<String?>,
    /** Every DATA frame is followed by a CRC32 of its payload. */
    val crc: Boolean,
) {
    private val sinks = arrayOfNulls<Sink>(files.size)
    private val got = Array(files.size) { AtomicLong() }
    private val doneChunks = Array(files.size) { ProtoV2.ChunkSet() }
    private val finished = BooleanArray(files.size)
    /** Guards [finished] together with every Resume.put/remove for this session (finishFile vs checkpoint). */
    private val recLock = Any()
    private val remaining = AtomicInteger(files.size)
    private val lock = Object()
    @Volatile var complete = false
    @Volatile private var aborted = false

    /**
     * Reopens usable partials from earlier attempts (only when the file still exists and is long enough)
     * and returns the chunk ranges we already have, per file index, for the accept reply's `have`.
     */
    fun prepareResume(): Map<Int, List<IntArray>> {
        val out = HashMap<Int, List<IntArray>>()
        for (i in files.indices) {
            val key = keys[i] ?: continue
            val rec = Resume.get(key) ?: continue
            val f = files[i]
            val chunks = ProtoV2.Ranges.clamp(rec.chunks, ProtoV2.chunkCount(f.size))
            val ok = rec.size == f.size && chunks.isNotEmpty() &&
                Storage.partialLength(Hub.app, rec.location) >= ProtoV2.Ranges.maxEnd(chunks, f.size)
            val sink = if (ok) Storage.reopenSink(Hub.app, rec) else null
            if (sink == null) {
                // Gone, shorter than its records say, or unusable: start this file over.
                Storage.deletePartial(Hub.app, rec.location)
                Resume.remove(key)
                continue
            }
            synchronized(sinks) { sinks[i] = sink }
            doneChunks[i] = ProtoV2.ChunkSet(chunks)
            val bytes = ProtoV2.Ranges.bytes(chunks, f.size)
            got[i].set(bytes)
            t.baseline += bytes
            t.add(bytes)
            out[i] = chunks
        }
        return out
    }

    fun start() {
        files.forEachIndexed { i, f ->
            if (f.size == 0L) { sink(i); finishFile(i) }
            else if (got[i].get() == f.size) finishFile(i)       // every chunk was already there
        }
    }

    private val slotLocks = Array(files.size) { Any() }

    /** Created under a per-file lock so slow MediaStore round trips don't serialize every stream. */
    fun sink(i: Int): Sink = synchronized(slotLocks[i]) {
        synchronized(sinks) {
            if (aborted) throw IOException("transfer stopped")
            sinks[i]?.let { return@synchronized it }
            null
        } ?: run {
            val created = Storage.createSink(Hub.app, files[i].name, files[i].size, partial = keys[i] != null)
            synchronized(sinks) {
                if (aborted) { created.abort(); throw IOException("transfer stopped") }
                sinks[i] = created
            }
            created
        }
    }

    /** Resumable files only accept frames cut exactly on 8 MB chunk boundaries. */
    fun frameOk(i: Int, off: Long, len: Int): Boolean {
        if (keys[i] == null) return true
        if (off % Proto.CHUNK != 0L) return false
        return len.toLong() == ProtoV2.chunkLen((off / Proto.CHUNK).toInt(), files[i].size)
    }

    fun chunkDone(i: Int, off: Long, len: Long) {
        if (keys[i] != null) {
            // A chunk counts once, even if a sender repeats it.
            if (!doneChunks[i].add((off / Proto.CHUNK).toInt())) return
        }
        if (got[i].addAndGet(len) == files[i].size) finishFile(i)
    }

    private fun finishFile(i: Int) {
        val s = synchronized(sinks) { sinks[i] } ?: return
        synchronized(recLock) { if (finished[i]) return; finished[i] = true }
        val uri = try { s.finish() } catch (_: Exception) {
            // Keep the resume record (the data is all there); closeOut() re-saves it. No "Received" entry.
            synchronized(recLock) { finished[i] = false }
            fail("Couldn't save ${files[i].name}")
            return
        }
        synchronized(recLock) { keys[i]?.let { Resume.remove(it) } }
        Hub.addReceived(Received(files[i].name, files[i].size, uri, t.peer, files[i].app))
        if (t.histFiles.size < HistoryCodec.MAX_NAMES)                          // history hook
            t.histFiles.add(HFile(files[i].name, files[i].size, uri?.toString(),
                files[i].app?.label, files[i].app?.pkg, files[i].app?.version))
        if (remaining.decrementAndGet() == 0) {
            complete = true
            synchronized(lock) { lock.notifyAll() }
        }
    }

    fun waitDone(ms: Long) = synchronized(lock) { if (!complete) lock.wait(ms) }

    fun fail(msg: String) {
        Hub.end(t, State.FAILED, msg)
        synchronized(lock) { lock.notifyAll() }
    }

    private fun record(i: Int) {
        val key = keys[i] ?: return
        val loc = synchronized(sinks) { sinks[i] }?.location ?: return
        // Re-check "finished" under the same lock finishFile uses: a record written after the file was
        // finalised would point at the finished MediaStore row and later get that file deleted.
        synchronized(recLock) {
            if (finished[i]) return
            Resume.put(Resume.Rec(key, Storage.cleanName(files[i].name), files[i].size, loc, doneChunks[i].snapshot(), System.currentTimeMillis()))
        }
    }

    /** Called about every 10 s while running so a killed app can still resume. */
    fun checkpoint() {
        for (i in files.indices) {
            if (keys[i] == null || aborted || synchronized(recLock) { finished[i] }) continue
            if (doneChunks[i].count() > 0) record(i)
        }
    }

    /**
     * The transfer is over. Finished files stay. Unfinished resumable files with at least one done
     * chunk keep their partial (and a saved record); everything else is deleted, as before.
     */
    fun closeOut() {
        synchronized(sinks) { aborted = true }
        for (i in files.indices) {
            val s = synchronized(sinks) { sinks[i] } ?: continue
            if (synchronized(recLock) { finished[i] }) continue
            val key = keys[i]
            if (key != null && doneChunks[i].count() > 0) { record(i); s.keep() }
            else { s.abort(); if (key != null) Resume.remove(key) }
        }
    }
}
