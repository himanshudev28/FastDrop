package com.fastdrop.app

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
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

    /** Returns false if the sender is already gone. */
    private fun reply(ch: SocketChannel, accept: Boolean, reason: String): Boolean {
        val ok = try { Io.writeJson(ch, JSONObject().put("accept", accept).put("reason", reason)); true } catch (_: Exception) { false }
        if (!accept) Io.closeQuietly(ch)
        return ok
    }

    private fun handleOffer(ch: SocketChannel, guard: ScheduledFuture<*>) {
        val o = Io.readJson(ch, 4 shl 20)
        guard.cancel(false)
        val id = o.getString("id")
        val fromName = o.optString("name", "Unknown device").take(60)
        val fromType = o.optString("type", "phone")
        val arr = o.getJSONArray("files")
        if (arr.length() > 10_000) { reply(ch, false, "invalid"); return }
        val files = (0 until arr.length()).map {
            val f = arr.getJSONObject(it)
            // Optional app tag (v2 senders): groups base + split APKs into one installable app.
            val tag = f.optJSONObject("app")?.let { a ->
                val pkg = a.optString("p").take(200)
                if (pkg.isEmpty()) null else AppTag(a.optString("l").take(80), pkg, a.optString("v").take(40))
            }
            RxFile(Storage.cleanName(f.getString("name")), f.getLong("size"), tag)
        }
        if (files.isEmpty() || files.any { it.size < 0 }) { reply(ch, false, "invalid"); return }
        if (files.any { it.size > MAX_FILE }) { reply(ch, false, "invalid"); return }
        val total = try { files.fold(0L) { a, f -> Math.addExact(a, f.size) } } catch (_: ArithmeticException) { -1L }
        if (total < 0) { reply(ch, false, "invalid"); return }

        if (!Hub.tryReserve()) { reply(ch, false, "busy"); return }
        var reserved = true
        try {
            val accept: Boolean
            var reason = ""
            if (Hub.autoAccept) {
                val needsWrite = android.os.Build.VERSION.SDK_INT in 23..28 &&
                    Hub.app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsWrite) { accept = false; reason = "storage" } else accept = true
            } else if (Hub.listener == null) {
                accept = false; reason = "closed"
            } else if (!Hub.appVisible() && !TransferService.canNotifyOffer(Hub.app)) {
                // Nobody can see the offer and the notification would be blocked: don't make the sender wait.
                accept = false; reason = "closed"
            } else {
                val offer = IncomingOffer(fromName, fromType, files, total)
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
                reply(ch, false, reason); return
            }

            val via = try { Net.viaFor(ch.socket().localAddress) } catch (_: Exception) { Device.VIA_LAN }
            val t = Transfer(false, fromName, total, files.size, via, files.take(50).map { it.name })
            val s = RxSession(id, files, t)
            sessions[id] = s
            t.register(ch)
            Hub.begin(t)
            reserved = false             // the transfer owns the busy flag now; Hub.end releases it
            if (!reply(ch, true, "")) {
                Hub.end(t, State.CANCELLED, "$fromName cancelled")
                sessions.remove(id)
                return
            }
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
                s.abortAll()
                sessions.remove(id)
                Io.closeQuietly(ch)
            }
        } finally {
            if (reserved) Hub.release()
        }
    }

    private fun handleData(ch: SocketChannel, guard: ScheduledFuture<*>) {
        val idLen = Io.readInt(ch)
        if (idLen !in 1..200) throw IOException("bad id")
        val idBuf = ByteBuffer.allocate(idLen); Io.readFully(ch, idBuf)
        val s = sessions[String(idBuf.array(), Charsets.UTF_8)] ?: throw IOException("unknown session")
        guard.cancel(false)   // from here the transfer watchdog handles stalls
        val t = s.t
        t.register(ch)

        val hdr = ByteBuffer.allocate(16)
        val buf = ByteBuffer.allocateDirect(Proto.IO_BUF)
        try {
            while (t.running) {
                hdr.clear(); Io.readFully(ch, hdr); hdr.flip()
                val fi = hdr.int; val off = hdr.long; val len = hdr.int
                if (fi < 0) break
                if (fi >= s.files.size || off < 0 || len < 0 || off > s.files[fi].size - len) throw IOException("bad frame")
                val fc = s.sink(fi).channel
                var pos = off
                var rem = len.toLong()
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
                s.chunkDone(fi, len.toLong())
            }
        } catch (e: Exception) {
            if (t.running && !s.complete) s.fail("Transfer interrupted: ${e.message ?: "connection lost"}")
        } finally {
            // A stream ending cleanly is normal (others may still be sending); real stalls hit the watchdog.
            Io.closeQuietly(ch)
        }
    }
}

class RxSession(val id: String, val files: List<RxFile>, val t: Transfer) {
    private val sinks = arrayOfNulls<Sink>(files.size)
    private val got = Array(files.size) { AtomicLong() }
    private val remaining = AtomicInteger(files.size)
    private val lock = Object()
    @Volatile var complete = false
    @Volatile private var aborted = false

    fun start() {
        files.forEachIndexed { i, f -> if (f.size == 0L) { sink(i); finishFile(i) } }
    }

    private val slotLocks = Array(files.size) { Any() }

    /** Created under a per-file lock so slow MediaStore round trips don't serialize every stream. */
    fun sink(i: Int): Sink = synchronized(slotLocks[i]) {
        synchronized(sinks) {
            if (aborted) throw IOException("transfer stopped")
            sinks[i]?.let { return@synchronized it }
            null
        } ?: run {
            val created = Storage.createSink(Hub.app, files[i].name, files[i].size)
            synchronized(sinks) {
                if (aborted) { created.abort(); throw IOException("transfer stopped") }
                sinks[i] = created
            }
            created
        }
    }

    fun chunkDone(i: Int, len: Long) {
        if (got[i].addAndGet(len) == files[i].size) finishFile(i)
    }

    private fun finishFile(i: Int) {
        val s = synchronized(sinks) { sinks[i] } ?: return
        val uri = s.finish()
        Hub.addReceived(Received(files[i].name, files[i].size, uri, t.peer, files[i].app))
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

    /** Deletes half-written files; finished files are kept. */
    fun abortAll() = synchronized(sinks) { aborted = true; sinks.forEach { it?.abort() } }
}
