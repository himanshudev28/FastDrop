package com.fastdrop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Sending side: one control connection + N parallel data streams pulling 8 MB chunks from a shared queue. */
object Sender {
    private class Chunk(val file: Int, val off: Long, val len: Int)

    fun send(ctx: Context, dev: Device, items: List<SendItem>) {
        if (items.isEmpty()) return
        if (!Hub.tryReserve()) { Hub.message("A transfer is already running"); return }
        thread(name = "sender") { run(ctx, dev, items) }
    }

    /** Re-reads each item's real size just before sending; drops items that can no longer be opened. */
    private fun revalidate(ctx: Context, picked: List<SendItem>): List<SendItem> = picked.mapNotNull { it ->
        try {
            val real: Long = when {
                it.uri != null && it.uri.scheme.equals("file", true) -> java.io.File(it.uri.path ?: return@mapNotNull null).takeIf { f -> f.isFile }?.length() ?: return@mapNotNull null
                it.uri != null -> ctx.contentResolver.openFileDescriptor(it.uri, "r")?.use { pfd -> pfd.statSize } ?: return@mapNotNull null
                it.file != null -> if (it.file.isFile) it.file.length() else return@mapNotNull null
                else -> it.size
            }
            if (real > 0 && real != it.size) SendItem(it.name, real, it.uri, it.file, it.app) else it
        } catch (_: Exception) { null }
    }

    private fun run(ctx: Context, dev: Device, picked: List<SendItem>) {
        val items = revalidate(ctx, picked)
        if (items.isEmpty()) {
            Hub.message("Couldn't read the selected files")
            Hub.release(); Hub.changed()
            return
        }
        var t: Transfer? = null
        var linking = false
        var target = dev
        val ctrl = Socket()
        try {
            Hub.pendingSendTo = dev.name
            Hub.pendingSocket = ctrl
            Hub.pendingThread = Thread.currentThread()
            Hub.pendingStatus = null
            Hub.changed()

            // QR / Wi-Fi Direct devices: make them reachable first (same-network wins, then P2P/hotspot).
            if (dev.needsLink) {
                linking = true
                Hub.linking = true
                Hub.pendingStatus = "Connecting to ${dev.name}…"
                Hub.changed()
                target = Links.ensureReachable(ctx, dev) { Hub.pendingStatus = it; Hub.changed() }
                linking = false
                Hub.linking = false
                if (ctrl.isClosed) throw IOException("Cancelled")
                Discovery.nudge()                          // let the other side see us on the new network too
            }

            val id = UUID.randomUUID().toString()
            ctrl.tcpNoDelay = true
            Hub.pendingStatus = "Connecting to ${dev.name}…"
            Hub.changed()
            Links.bindSocket(ctrl, target.ip)
            ctrl.connect(InetSocketAddress(target.ip, target.port), 5000)
            Hub.pendingStatus = "Waiting for ${dev.name} to accept…"
            Hub.changed()
            ctrl.soTimeout = 75_000                       // receiver has 60 s to tap Accept
            val out = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))
            val inp = DataInputStream(BufferedInputStream(ctrl.getInputStream()))

            val files = JSONArray()
            items.forEach {
                val f = JSONObject().put("name", it.name).put("size", it.size)
                it.app?.let { a -> f.put("app", JSONObject().put("l", a.label).put("p", a.pkg).put("v", a.version)) }
                files.put(f)
            }
            out.writeInt(Proto.MAGIC_CTRL)
            Io.writeJson(out, JSONObject().put("id", id).put("name", Hub.myName).put("type", Hub.myType).put("files", files))

            val resp = Io.readJson(inp)
            if (!resp.optBoolean("accept")) {
                Hub.message(when (resp.optString("reason")) {
                    "busy" -> "${dev.name} is busy with another transfer"
                    "closed" -> "FastDrop isn't open on ${dev.name}"
                    "timeout" -> "${dev.name} didn't respond"
                    "invalid" -> "${dev.name} rejected the file list"
                    "storage" -> "${dev.name} needs storage permission to receive"
                    else -> "${dev.name} declined"
                })
                return
            }

            val total = items.sumOf { it.size }
            val tr = Transfer(true, dev.name, total, items.size, Net.viaFor(ctrl.localAddress), items.take(50).map { it.name })
            t = tr
            tr.register(ctrl)
            Hub.pendingSendTo = null
            Hub.pendingSocket = null
            Hub.pendingStatus = null
            Hub.begin(tr)

            val queue = ConcurrentLinkedQueue<Chunk>()
            items.forEachIndexed { i, it ->
                var off = 0L
                while (off < it.size) {
                    val len = minOf(Proto.CHUNK.toLong(), it.size - off).toInt()
                    queue.add(Chunk(i, off, len)); off += len
                }
            }
            val n = minOf(Hub.streams.coerceIn(1, 8), queue.size)
            val error = AtomicReference<Exception?>()
            val workers = (0 until n).map {
                thread(name = "send-$it") {
                    try {
                        worker(ctx, target, id, items, queue, tr)
                    } catch (e: Exception) {
                        if (error.compareAndSet(null, e)) tr.closeAll()  // stop the other streams too
                    }
                }
            }
            workers.forEach { it.join() }
            tr.sentAll = true                             // all bytes written; receiver may still be finalizing
            if (!tr.running) return                       // cancelled / stalled; already reported
            error.get()?.let { throw it }

            ctrl.soTimeout = 60_000
            val fin = Io.readJson(inp)
            if (fin.optBoolean("ok")) Hub.end(tr, State.DONE, "Sent")
            else Hub.end(tr, State.FAILED, "Receiver reported an error")
        } catch (e: Exception) {
            val msg = when {
                t == null && ctrl.isClosed -> "Cancelled"
                linking -> e.message ?: "Couldn't connect to ${dev.name}"
                e is java.net.SocketTimeoutException -> "${dev.name} didn't answer"
                e is java.net.ConnectException -> "Can't reach ${dev.name} (${target.ip})"
                else -> "Transfer failed: ${e.message ?: e.javaClass.simpleName}"
            }
            if (t != null) Hub.end(t, State.FAILED, msg) else Hub.message(msg)
        } finally {
            Hub.linking = false
            Hub.pendingThread = null
            if (t == null) Hub.release()
            Hub.pendingSendTo = null
            Hub.pendingSocket = null
            Hub.pendingStatus = null
            Io.closeQuietly(ctrl)
            if (t == null) try { Links.onTransferEnded() } catch (_: Exception) { }   // nothing was sent: let go of the link
            Hub.changed()
        }
    }

    private fun worker(
        ctx: Context, dev: Device, id: String, items: List<SendItem>,
        queue: ConcurrentLinkedQueue<Chunk>, t: Transfer,
    ) {
        val ch = SocketChannel.open()
        t.register(ch)
        var src: Source? = null
        try {
            ch.socket().sendBufferSize = Proto.SOCK_BUF       // before connect -> large TCP window
            Links.bindSocket(ch.socket(), dev.ip)
            ch.socket().connect(InetSocketAddress(dev.ip, dev.port), 5000)

            val idb = id.toByteArray(Charsets.UTF_8)
            val hello = ByteBuffer.allocate(8 + idb.size)
            hello.putInt(Proto.MAGIC_DATA).putInt(idb.size).put(idb).flip()
            Io.writeFully(ch, hello)

            val hdr = ByteBuffer.allocate(16)
            var fallback: ByteBuffer? = null
            var cur = -1
            while (t.running) {
                val c = queue.poll() ?: break
                if (c.file != cur) {
                    src?.close()
                    src = Storage.openSource(ctx, items[c.file])
                    cur = c.file
                }
                val fc = src!!.channel
                hdr.clear(); hdr.putInt(c.file).putLong(c.off).putInt(c.len).flip()
                Io.writeFully(ch, hdr)

                var pos = c.off
                var rem = c.len.toLong()
                while (rem > 0) {
                    // Zero-copy sendfile() first; plain read/write if the source doesn't support it.
                    var n = if (fallback == null) fc.transferTo(pos, minOf(rem, Proto.IO_BUF.toLong()), ch) else 0L
                    if (n <= 0) {
                        val b = fallback ?: ByteBuffer.allocateDirect(Proto.IO_BUF).also { fallback = it }
                        b.clear()
                        if (rem < b.capacity()) b.limit(rem.toInt())
                        val r = fc.read(b, pos)
                        if (r < 0) throw EOFException("${items[c.file].name} got shorter while sending")
                        b.flip(); Io.writeFully(ch, b)
                        n = r.toLong()
                    }
                    pos += n; rem -= n
                    t.add(n)
                }
            }
            if (!t.running) throw IOException("stopped")
            hdr.clear(); hdr.putInt(-1).putLong(0).putInt(0).flip()
            Io.writeFully(ch, hdr)
        } finally {
            src?.close()
            Io.closeQuietly(ch)
        }
    }
}
