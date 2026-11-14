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
import java.util.zip.CRC32
import kotlin.concurrent.thread

/** Sending side: one control connection + N parallel data streams pulling 8 MB chunks from a shared queue. */
object Sender {
    private class Chunk(val file: Int, val off: Long, val len: Int)

    /** Set when the user cancels; stops a multi-device send from moving on to the next device. */
    @Volatile var queueAborted = false

    private fun str(id: Int, vararg a: Any): String = Hub.app.getString(id, *a)

    /** Sends [items], or [text] (a text / link offer with no files) when it is non-null. */
    fun send(ctx: Context, dev: Device, items: List<SendItem>, text: String? = null) {
        if (items.isEmpty() && text.isNullOrEmpty()) return
        if (!Hub.tryReserve()) { Hub.message(str(R.string.msg_transfer_running)); return }
        queueAborted = false
        thread(name = "sender") { run(ctx, dev, items, text) }
    }

    /** One normal transfer per device, one after another, then a summary toast ("Sent to 2 of 3 devices"). */
    fun sendMany(ctx: Context, devs: List<Device>, items: List<SendItem>, text: String? = null) {
        if (devs.size == 1) { send(ctx, devs[0], items, text); return }
        if (devs.isEmpty() || (items.isEmpty() && text.isNullOrEmpty())) return
        if (!Hub.tryReserve()) { Hub.message(str(R.string.msg_transfer_running)); return }
        queueAborted = false
        thread(name = "sender-multi") {
            var ok = 0
            var busy = 0
            try {
                for ((i, d) in devs.withIndex()) {
                    if (queueAborted) break
                    if (i > 0) {
                        // The previous transfer releases the busy flag as it ends. Never run without it.
                        var waited = 0
                        var got = Hub.tryReserve()
                        while (!got && waited < 100) { Thread.sleep(100); waited++; got = Hub.tryReserve() }
                        if (!got) { busy++; continue }
                    }
                    Hub.multiInfo = str(R.string.msg_device_n_of, i + 1, devs.size)
                    if (run(ctx, d, items, text)) ok++
                }
            } finally {
                Hub.multiInfo = null
                Hub.changed()
            }
            Hub.message(if (busy > 0) str(R.string.msg_sent_to_n_of_busy, ok, devs.size, busy) else str(R.string.msg_sent_to_n_of, ok, devs.size))
        }
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
            val mt = Storage.lastModified(ctx, it)
            if ((real > 0 && real != it.size) || mt != it.mtime) SendItem(it.name, if (real > 0) real else it.size, it.uri, it.file, it.app, mt) else it
        } catch (_: Exception) { null }
    }

    /** Returns true when the receiver confirmed everything. */
    private fun run(ctx: Context, dev: Device, picked: List<SendItem>, text: String?): Boolean {
        val items = revalidate(ctx, picked)
        if (items.isEmpty() && text == null) {
            Hub.message(str(R.string.msg_cant_read_selected))
            Hub.release(); Hub.changed()
            return false
        }
        var success = false
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
                Hub.pendingStatus = str(R.string.msg_connecting_to, dev.name)
                Hub.changed()
                target = Links.ensureReachable(ctx, dev) { Hub.pendingStatus = it; Hub.changed() }
                linking = false
                Hub.linking = false
                if (ctrl.isClosed) throw IOException("Cancelled")
                Discovery.nudge()                          // let the other side see us on the new network too
            }

            val id = UUID.randomUUID().toString()
            ctrl.tcpNoDelay = true
            Hub.pendingStatus = str(R.string.msg_connecting_to, dev.name)
            Hub.changed()
            Links.bindSocket(ctrl, target.ip)
            ctrl.connect(InetSocketAddress(target.ip, target.port), 5000)
            Hub.pendingStatus = str(R.string.msg_waiting_accept, dev.name)
            Hub.changed()
            ctrl.soTimeout = 75_000                       // receiver has 60 s to tap Accept
            val out = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))
            val inp = DataInputStream(BufferedInputStream(ctrl.getInputStream()))

            val files = JSONArray()
            items.forEach {
                val f = JSONObject().put("name", it.name).put("size", it.size)
                if (it.mtime > 0) f.put("mtime", it.mtime)
                it.app?.let { a -> f.put("app", JSONObject().put("l", a.label).put("p", a.pkg).put("v", a.version)) }
                files.put(f)
            }
            // Protocol 2 fields are all optional: a FastDrop 2.0 receiver just ignores them.
            val ts = System.currentTimeMillis()
            val key = Trust.keyFor(dev.id)
            val wantCrc = Hub.verifyCrc
            val offer = JSONObject().put("id", id).put("name", Hub.myName).put("type", Hub.myType)
                .put("v", ProtoV2.VERSION).put("dev", Hub.myId).put("ts", ts).put("chunk", ProtoV2.CHUNK)
                .put("files", files)
            if (text != null) offer.put("text", text)
            if (wantCrc) offer.put("crc", true)
            if (key != null) offer.put("auth", ProtoV2.auth(key, id, Hub.myId, ts, items.map { it.name to it.size }, text))
            // First-time senders (no key yet) show the code so the receiver can confirm who is asking.
            Hub.pendingCode = if (key == null && dev.ver != 1) ProtoV2.confirmationCode(id) else null
            Hub.changed()
            out.writeInt(Proto.MAGIC_CTRL)
            Io.writeJson(out, offer)

            val resp = Io.readJson(inp)
            val peerV2 = resp.optInt("v", 1) >= 2
            if (!resp.optBoolean("accept")) {
                val reason = resp.optString("reason")
                Hub.message(when {
                    reason == "invalid" && text != null && !peerV2 -> str(R.string.msg_needs_21_text, dev.name)
                    reason == "busy" -> str(R.string.msg_peer_busy, dev.name)
                    reason == "closed" -> str(R.string.msg_peer_closed, dev.name)
                    reason == "timeout" -> str(R.string.msg_peer_timeout, dev.name)
                    reason == "invalid" -> str(R.string.msg_peer_rejected, dev.name)
                    reason == "storage" -> str(R.string.msg_peer_storage, dev.name)
                    else -> str(R.string.msg_peer_declined, dev.name)
                })
                return false
            }
            if (peerV2) {
                // "Always accept" was ticked on the other side: it handed us a key for authenticated offers.
                val trust = resp.optString("trust")
                val rid = resp.optString("dev")
                if (rid.isNotEmpty() && ProtoV2.isHex64(trust)) Trust.setKeyFor(rid, trust)
            }
            val useCrc = wantCrc && peerV2 && resp.optBoolean("crc")
            // Chunks the receiver already has (resume).
            val have = HashMap<Int, List<IntArray>>()
            if (peerV2) resp.optJSONObject("have")?.let { h ->
                for (k in h.keys()) {
                    val idx = k.toIntOrNull() ?: continue
                    if (idx !in items.indices) continue
                    val r = ProtoV2.Ranges.clamp(ProtoV2.Ranges.decode(h.optJSONArray(k)?.toString() ?: ""), ProtoV2.chunkCount(items[idx].size))
                    if (r.isNotEmpty()) have[idx] = r
                }
            }
            var baseline = 0L
            for ((idx, r) in have) baseline += ProtoV2.Ranges.bytes(r, items[idx].size)

            val total = if (text != null) text.toByteArray(Charsets.UTF_8).size.toLong() else items.sumOf { it.size }
            val tr = Transfer(true, dev.name, total, items.size, Net.viaFor(ctrl.localAddress),
                if (text != null) listOf(str(R.string.main_label_text)) else items.take(50).map { it.name }, text)
            tr.baseline = baseline
            tr.peerId = dev.id                                                       // history hook
            items.take(HistoryCodec.MAX_NAMES).forEach { tr.histFiles.add(HistoryStore.fileOf(it)) }
            try { Shortcuts.noteUsed(ctx, dev) } catch (_: Exception) { }            // Direct Share ranking
            if (text != null) tr.done.set(total)
            else if (baseline > 0) tr.done.set(baseline)
            t = tr
            tr.register(ctrl)
            Hub.pendingSendTo = null
            Hub.pendingSocket = null
            Hub.pendingStatus = null
            Hub.pendingCode = null
            Hub.begin(tr)

            val queue = ConcurrentLinkedQueue<Chunk>()
            items.forEachIndexed { i, it ->
                var off = 0L
                val got = have[i]
                while (off < it.size) {
                    val len = minOf(Proto.CHUNK.toLong(), it.size - off).toInt()
                    if (got == null || !ProtoV2.Ranges.contains(got, (off / Proto.CHUNK).toInt())) queue.add(Chunk(i, off, len))
                    off += len
                }
            }
            val n = minOf(Hub.streams.coerceIn(1, 8), queue.size)
            val error = AtomicReference<Exception?>()
            val workers = (0 until n).map {
                thread(name = "send-$it") {
                    try {
                        worker(ctx, target, id, items, queue, tr, useCrc)
                    } catch (e: Exception) {
                        if (error.compareAndSet(null, e)) tr.closeAll()  // stop the other streams too
                    }
                }
            }
            workers.forEach { it.join() }
            tr.sentAll = true                             // all bytes written; receiver may still be finalizing
            if (!tr.running) return false                 // cancelled / stalled; already reported
            error.get()?.let { throw it }

            ctrl.soTimeout = 60_000
            val fin = Io.readJson(inp)
            if (fin.optBoolean("ok")) { success = true; Hub.end(tr, State.DONE, str(R.string.msg_sent)) }
            else Hub.end(tr, State.FAILED, str(R.string.msg_receiver_error))
        } catch (e: Exception) {
            val msg = when {
                t == null && ctrl.isClosed -> str(R.string.msg_cancelled)
                linking -> e.message ?: str(R.string.msg_cant_connect_to, dev.name)
                e is java.net.SocketTimeoutException -> str(R.string.msg_peer_no_answer, dev.name)
                e is java.net.ConnectException -> str(R.string.msg_cant_reach, dev.name, target.ip)
                else -> str(R.string.msg_transfer_failed, e.message ?: e.javaClass.simpleName)
            }
            if (t != null) Hub.end(t, State.FAILED, msg) else Hub.message(msg)
        } finally {
            Hub.linking = false
            Hub.pendingThread = null
            Hub.pendingCode = null
            if (t == null) Hub.release()
            Hub.pendingSendTo = null
            Hub.pendingSocket = null
            Hub.pendingStatus = null
            Io.closeQuietly(ctrl)
            if (t == null) try { Links.onTransferEnded() } catch (_: Exception) { }   // nothing was sent: let go of the link
            Hub.changed()
        }
        return success
    }

    private fun worker(
        ctx: Context, dev: Device, id: String, items: List<SendItem>,
        queue: ConcurrentLinkedQueue<Chunk>, t: Transfer, crcOn: Boolean,
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
            // CRC mode only: read through a heap buffer so the bytes can be checksummed.
            val crc = if (crcOn) CRC32() else null
            val crcBuf = if (crcOn) ByteBuffer.allocate(Proto.IO_BUF) else null
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
                if (crc != null && crcBuf != null) {
                    crc.reset()
                    while (rem > 0) {
                        crcBuf.clear()
                        if (rem < crcBuf.capacity()) crcBuf.limit(rem.toInt())
                        val r = fc.read(crcBuf, pos)
                        if (r < 0) throw EOFException("${items[c.file].name} got shorter while sending")
                        crc.update(crcBuf.array(), 0, r)
                        crcBuf.flip(); Io.writeFully(ch, crcBuf)
                        pos += r; rem -= r
                        t.add(r.toLong())
                    }
                    val tail = ByteBuffer.allocate(4)
                    tail.putInt(crc.value.toInt()).flip()
                    Io.writeFully(ch, tail)
                    continue
                }
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
