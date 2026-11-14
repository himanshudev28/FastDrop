package com.fastdrop.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tiny HTTP/1.1 server so any browser (iPhone, PC) can download the shared files and
 * upload files to this phone. No libraries. Every path starts with a random token.
 *
 * Timeouts: SocketChannel ignores soTimeout, so a reaper thread closes connections
 * that made no progress for [STALL_MS]. Closing unblocks any read or write in flight.
 */
object WebShare {
    const val PORT = 53532

    class Event(val text: String, val at: Long, val kind: Int)   // 0 info, 1 download, 2 upload

    @Volatile var running = false; private set
    @Volatile var downloads = 0; private set
    @Volatile var uploads = 0; private set
    @Volatile var bytesOut = 0L; private set
    @Volatile var bytesIn = 0L; private set

    val events: List<Event> get() = synchronized(lock) { ArrayList(eventList) }

    private const val STALL_MS = 45_000L
    private const val HEADER_MS = 10_000L   // absolute budget for one request line + headers
    private const val MAX_HEADER_LINE = 8192
    private const val MAX_HEADERS = 64
    private const val MAX_THUMB = 5L shl 20
    private const val BIG_BUF = 256 * 1024
    private const val SEND_CHUNK = 1L shl 20

    private val lock = Any()
    private val eventList = ArrayList<Event>()
    private val seenIps = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val conns = java.util.Collections.newSetFromMap(ConcurrentHashMap<Conn, Boolean>())
    private val bufPool = ConcurrentLinkedQueue<ByteBuffer>()
    private val rnd = SecureRandom()

    @Volatile private var server: ServerSocketChannel? = null
    @Volatile private var pool: ThreadPoolExecutor? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var token = ""
    @Volatile private var items: List<SendItem> = emptyList()
    @Volatile private var includeApp = false
    @Volatile private var lastChanged = 0L

    // ---------------- public API ----------------

    @Synchronized
    fun start(ctx: Context, items: List<SendItem>, includeApp: Boolean): Boolean {
        val app = ctx.applicationContext
        appCtx = app
        this.items = ArrayList(items)
        this.includeApp = includeApp
        if (running) return true
        val ss: ServerSocketChannel
        try {
            ss = ServerSocketChannel.open()
            try {
                ss.socket().reuseAddress = true
                ss.socket().bind(InetSocketAddress(PORT), 64)
            } catch (e: Exception) {
                Io.closeQuietly(ss)
                return false
            }
        } catch (e: Exception) {
            return false
        }
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        token = String(CharArray(6) { chars[rnd.nextInt(chars.length)] })
        synchronized(lock) {
            eventList.clear()
            downloads = 0; uploads = 0; bytesOut = 0; bytesIn = 0
        }
        seenIps.clear()
        val p = ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, LinkedBlockingQueue(8)) { r, _ ->
            // Too busy: the runnable is a connection handler we can't run, drop it.
            (r as? ConnTask)?.reject()
        }
        p.allowCoreThreadTimeOut(true)
        pool = p
        server = ss
        running = true
        try { TransferService.start(app) } catch (_: Exception) { }
        thread(name = "web-accept", isDaemon = true) { acceptLoop(ss, p) }
        thread(name = "web-reaper", isDaemon = true) { reaper() }
        addEvent(Hub.app.getString(R.string.web_ev_started), 0)
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        Io.closeQuietly(server)
        server = null
        for (c in conns) Io.closeQuietly(c.ch)
        conns.clear()
        pool?.shutdownNow()
        pool = null
        Hub.changed()
        try { TransferService.stopIfIdle() } catch (_: Exception) { }
    }

    /** One address per local IPv4; hosted Wi-Fi Direct / hotspot networks first. */
    fun urls(): List<String> {
        if (!running) return emptyList()
        fun hosted(n: String) = n.startsWith("p2p") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")
        return try {
            Net.localAddrs()
                .sortedBy { if (hosted(it.iface.name.lowercase())) 0 else 1 }
                .mapNotNull { it.ip.hostAddress }
                .distinct()
                .map { "http://$it:$PORT/$token/" }
        } catch (_: Exception) { emptyList() }
    }

    fun url(): String? = urls().firstOrNull()

    // ---------------- events / stats ----------------

    private fun addEvent(text: String, kind: Int) {
        synchronized(lock) {
            eventList.add(0, Event(text, System.currentTimeMillis(), kind))
            while (eventList.size > 30) eventList.removeAt(eventList.size - 1)
        }
        Hub.changed()
    }

    private fun addOut(n: Long) { synchronized(lock) { bytesOut += n }; maybeChanged() }
    private fun addIn(n: Long) { synchronized(lock) { bytesIn += n }; maybeChanged() }

    /** Progress updates without flooding the UI thread. */
    private fun maybeChanged() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastChanged > 500) { lastChanged = now; Hub.changed() }
    }

    // ---------------- connection plumbing ----------------

    private class Req(val method: String, val target: String, val http11: Boolean, val headers: Map<String, String>) {
        val path: String get() = target.substringBefore('?')
        val query: Map<String, String> by lazy {
            val m = HashMap<String, String>()
            val q = target.substringAfter('?', "")
            if (q.isNotEmpty()) for (kv in q.split('&')) {
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                try { m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { }
            }
            m
        }
        val keepAlive: Boolean get() {
            val c = headers["connection"]?.lowercase()
            return if (http11) c != "close" else c == "keep-alive"
        }
    }

    private class Conn(val ch: SocketChannel) {
        @Volatile var last = SystemClock.elapsedRealtime()
        /** Header buffer, kept in read mode (position..limit = unread bytes). */
        val rbuf: ByteBuffer = ByteBuffer.allocate(16 * 1024).also { it.limit(0) }

        fun touch() { last = SystemClock.elapsedRealtime() }

        /** Absolute deadline for receiving a request line + headers (0 = none). Slow-drip clients can't extend it. */
        @Volatile var hdrDeadline = 0L

        private fun fill(): Boolean {
            rbuf.clear()
            val n = ch.read(rbuf)
            rbuf.flip()
            if (n > 0) touch()
            return n > 0
        }

        private fun readLine(allowEof: Boolean): String? {
            val sb = StringBuilder()
            while (true) {
                if (!rbuf.hasRemaining() && !fill()) {
                    if (sb.isEmpty() && allowEof) return null
                    throw EOFException()
                }
                val b = rbuf.get().toInt() and 0xff
                if (b == 10) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
                if (sb.length > MAX_HEADER_LINE) throw IOException("Header too long")
            }
        }

        /** Null on a clean close between requests. */
        fun readRequest(): Req? {
            hdrDeadline = SystemClock.elapsedRealtime() + HEADER_MS
            var line = readLine(true) ?: return null
            if (line.isEmpty()) line = readLine(true) ?: return null   // stray CRLF after a body
            val p = line.split(' ')
            if (p.size < 3 || !p[2].startsWith("HTTP/")) throw IOException("Bad request line")
            val headers = HashMap<String, String>()
            var count = 0
            while (true) {
                val h = readLine(false) ?: throw EOFException()
                if (h.isEmpty()) break
                if (++count > MAX_HEADERS) throw IOException("Too many headers")
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            hdrDeadline = 0L
            return Req(p[0].uppercase(), p[1], p[2] != "HTTP/1.0", headers)
        }

        fun writeAll(b: ByteBuffer) {
            while (b.hasRemaining()) { ch.write(b); touch() }
        }

        fun writeAll(s: String) = writeAll(ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1)))
    }

    /** Runnable wrapper so the pool's reject handler can close the socket. */
    private class ConnTask(val ch: SocketChannel, val body: () -> Unit) : Runnable {
        override fun run() = body()
        fun reject() {
            try { ch.write(ByteBuffer.wrap("HTTP/1.1 503 Busy\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())) } catch (_: Exception) { }
            Io.closeQuietly(ch)
        }
    }

    private fun acceptLoop(ss: ServerSocketChannel, p: ThreadPoolExecutor) {
        while (running) {
            val ch = try { ss.accept() } catch (_: Exception) { break }
            try {
                ch.socket().tcpNoDelay = true
                ch.socket().keepAlive = true
                try { ch.socket().sendBufferSize = Proto.SOCK_BUF } catch (_: Exception) { }
                p.execute(ConnTask(ch) { serve(ch) })
            } catch (_: Exception) {
                Io.closeQuietly(ch)
            }
        }
    }

    private fun reaper() {
        while (running) {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { }
            val now = SystemClock.elapsedRealtime()
            for (c in conns) if (now - c.last > STALL_MS || (c.hdrDeadline != 0L && now > c.hdrDeadline)) { conns.remove(c); Io.closeQuietly(c.ch) }
        }
    }

    private fun serve(ch: SocketChannel) {
        val c = Conn(ch)
        conns.add(c)
        try {
            var first = true
            while (running) {
                val req = c.readRequest() ?: break
                if (first) {
                    first = false
                    val ip = (ch.socket().remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
                    if (ip != null && seenIps.add(ip)) addEvent(Hub.app.getString(R.string.web_ev_connected, ip), 0)
                }
                if (!route(c, req)) break
            }
        } catch (_: Exception) {
        } finally {
            conns.remove(c)
            Io.closeQuietly(ch)
        }
    }

    // ---------------- routing ----------------

    /** Returns true to keep the connection open for another request. */
    private fun route(c: Conn, req: Req): Boolean {
        val segs = req.path.split('/').filter { it.isNotEmpty() }
        val tok = token
        if (segs.isEmpty() || !MessageDigest.isEqual(segs[0].toByteArray(), tok.toByteArray())) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { }   // slows token guessing
            sendText(c, 403, "Forbidden", "Forbidden", false, false)
            return false
        }
        val rest = segs.drop(1)
        val head = req.method == "HEAD"
        val keep = req.keepAlive
        val read = req.method == "GET" || head

        if (rest.isEmpty()) {
            if (!read) return methodNotAllowed(c, "GET, HEAD")
            if (!req.path.endsWith("/")) {
                send(c, 301, "Moved", "text/plain", ByteArray(0), keep, head, listOf("Location: /$tok/"))
                return keep
            }
            val body = WebPage.html().toByteArray(Charsets.UTF_8)
            send(c, 200, "OK", "text/html; charset=utf-8", body, keep, head, listOf(
                "Cache-Control: no-store",
                "Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'",
                "X-Content-Type-Options: nosniff"))
            return keep
        }
        return when (rest[0]) {
            "api" -> {
                if (rest.size != 2 || rest[1] != "list") return notFound(c)
                if (!read) return methodNotAllowed(c, "GET, HEAD")
                send(c, 200, "OK", "application/json; charset=utf-8", listJson().toByteArray(Charsets.UTF_8),
                    keep, head, listOf("Cache-Control: no-store"))
                keep
            }
            "f" -> {
                if (!read) return methodNotAllowed(c, "GET, HEAD")
                val item = rest.getOrNull(1)?.toIntOrNull()?.let { items.getOrNull(it) } ?: return notFound(c)
                download(c, req, item, item.name.substringAfterLast('/'), req.query["inline"] == "1")
            }
            "app.apk" -> {
                if (!read) return methodNotAllowed(c, "GET, HEAD")
                val ctx = appCtx
                val f = if (includeApp && ctx != null) File(ctx.applicationInfo.sourceDir) else null
                if (f == null || !f.isFile) return notFound(c)
                download(c, req, SendItem("FastDrop.apk", f.length(), null, f), "FastDrop.apk", false)
            }
            "up" -> {
                if (req.method != "PUT" && req.method != "POST") return methodNotAllowed(c, "PUT")
                upload(c, req)
            }
            else -> notFound(c)
        }
    }

    private fun notFound(c: Conn): Boolean { sendText(c, 404, "Not Found", "Not found", false, false); return false }
    private fun methodNotAllowed(c: Conn, allow: String): Boolean {
        send(c, 405, "Method Not Allowed", "text/plain", "Method not allowed".toByteArray(), false, false, listOf("Allow: $allow"))
        return false
    }

    private fun listJson(): String {
        val arr = JSONArray()
        val list = items
        for ((i, f) in list.withIndex()) {
            val short = f.name.substringAfterLast('/')
            val mime = mimeOf(short)
            val kind = when {
                short.endsWith(".apk", true) -> "apk"
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                else -> "other"
            }
            val thumb = kind == "image" && f.size <= MAX_THUMB && mime != "image/svg+xml" && mime != "image/heic" && mime != "image/heif"
            arr.put(JSONObject().put("n", f.name).put("s", f.size).put("k", kind)
                .put("th", thumb).put("u", "f/$i/${pct(short)}"))
        }
        val o = JSONObject().put("name", Hub.myName).put("files", arr)
        val ctx = appCtx
        if (includeApp && ctx != null) {
            val f = File(ctx.applicationInfo.sourceDir)
            if (f.isFile) o.put("app", JSONObject().put("n", "FastDrop.apk").put("s", f.length()))
        }
        return o.toString()
    }

    private fun mimeOf(name: String) =
        if (name.endsWith(".apk", true)) "application/vnd.android.package-archive" else Storage.mime(name)

    // ---------------- responses ----------------

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 400 -> "Bad Request"; 403 -> "Forbidden"
        404 -> "Not Found"; 411 -> "Length Required"; 416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"
        507 -> "Insufficient Storage"; else -> "OK"
    }

    private fun headerBlock(code: Int, ctype: String?, len: Long, keep: Boolean, extra: List<String>): String {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        if (ctype != null) sb.append("Content-Type: ").append(ctype).append("\r\n")
        sb.append("Content-Length: ").append(len).append("\r\n")
        sb.append("Connection: ").append(if (keep) "keep-alive" else "close").append("\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        return sb.append("\r\n").toString()
    }

    private fun send(c: Conn, code: Int, @Suppress("UNUSED_PARAMETER") why: String, ctype: String, body: ByteArray,
                     keep: Boolean, headOnly: Boolean, extra: List<String> = emptyList()) {
        val h = headerBlock(code, ctype, body.size.toLong(), keep, extra).toByteArray(Charsets.ISO_8859_1)
        val buf = ByteBuffer.allocate(h.size + if (headOnly) 0 else body.size)
        buf.put(h)
        if (!headOnly) buf.put(body)
        buf.flip()
        c.writeAll(buf)
    }

    private fun sendText(c: Conn, code: Int, why: String, text: String, keep: Boolean, headOnly: Boolean) =
        send(c, code, why, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), keep, headOnly)

    /** Percent-encodes UTF-8 bytes, keeping only RFC 5987 safe characters. */
    private fun pct(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xff)
            val c = ch.toChar()
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~') sb.append(c)
            else sb.append('%').append("0123456789ABCDEF"[ch shr 4]).append("0123456789ABCDEF"[ch and 15])
        }
        return sb.toString()
    }

    private fun asciiName(s: String): String {
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in ' '..'~' && ch != '"' && ch != '\\' && ch != ';' && ch != '%') ch else '_')
        return sb.toString().ifEmpty { "file" }
    }

    // ---------------- downloads ----------------

    private fun borrow(): ByteBuffer = bufPool.poll() ?: ByteBuffer.allocateDirect(BIG_BUF)
    private fun giveBack(b: ByteBuffer) { if (bufPool.size < 16) { b.clear(); bufPool.offer(b) } }

    private fun download(c: Conn, req: Req, item: SendItem, name: String, inline: Boolean): Boolean {
        val ctx = appCtx ?: return notFound(c)
        val keep = req.keepAlive
        val head = req.method == "HEAD"
        val size = item.size
        val mime = mimeOf(name)
        val canInline = inline && mime.startsWith("image/") && mime != "image/svg+xml"

        // Range handling: single range only; anything odd falls back to the whole file.
        var start = 0L
        var end = size - 1
        var partial = false
        val rh = req.headers["range"]
        if (rh != null && size > 0 && req.headers["if-range"] == null && rh.startsWith("bytes=") && !rh.contains(',')) {
            val spec = rh.substring(6).trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                try {
                    val a = spec.substring(0, dash).trim()
                    val b = spec.substring(dash + 1).trim()
                    if (a.isEmpty()) {
                        val n = b.toLong()
                        start = maxOf(0L, size - n); end = size - 1
                        if (n <= 0) throw IllegalArgumentException()
                    } else {
                        start = a.toLong()
                        end = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
                    }
                    if (start >= size || start > end) {
                        send(c, 416, "", "text/plain", ByteArray(0), keep, head, listOf("Content-Range: bytes */$size"))
                        return keep
                    }
                    partial = true
                } catch (_: NumberFormatException) {
                    start = 0; end = size - 1; partial = false
                } catch (_: IllegalArgumentException) {
                    send(c, 416, "", "text/plain", ByteArray(0), keep, head, listOf("Content-Range: bytes */$size"))
                    return keep
                }
            }
        }
        val len = if (size > 0) end - start + 1 else 0L

        val extra = ArrayList<String>()
        extra.add("Accept-Ranges: bytes")
        extra.add("X-Content-Type-Options: nosniff")
        if (partial) extra.add("Content-Range: bytes $start-$end/$size")
        val disp = if (canInline) "inline" else "attachment"
        extra.add("Content-Disposition: $disp; filename=\"${asciiName(name)}\"; filename*=UTF-8''${pct(name)}")
        val ctype = if (canInline) mime else if (mime.startsWith("image/") || mime == "application/vnd.android.package-archive") mime else "application/octet-stream"

        if (head) {
            c.writeAll(headerBlock(if (partial) 206 else 200, ctype, len, keep, extra))
            return keep
        }

        val src = try { Storage.openSource(ctx, item) } catch (_: Exception) {
            sendText(c, 500, "", "Cannot open file", false, false); return false
        }
        var buf: ByteBuffer? = null
        try {
            c.writeAll(headerBlock(if (partial) 206 else 200, ctype, len, keep, extra))
            val fc = src.channel
            var pos = start
            var left = len
            var useBuf = false
            while (left > 0) {
                val n: Long
                if (!useBuf) {
                    n = fc.transferTo(pos, minOf(left, SEND_CHUNK), c.ch)
                    if (n <= 0L) { useBuf = true; continue }
                } else {
                    val b = buf ?: borrow().also { buf = it }
                    b.clear()
                    b.limit(minOf(b.capacity().toLong(), left).toInt())
                    val r = fc.read(b, pos)
                    if (r <= 0) return false   // file shorter than promised: can't recover, drop the connection
                    b.flip()
                    c.writeAll(b)
                    n = r.toLong()
                }
                pos += n; left -= n
                c.touch()
                addOut(n)
            }
            if (end == size - 1 || size == 0L) {
                synchronized(lock) { downloads++ }
                addEvent(Hub.app.getString(R.string.web_ev_downloaded, name.take(60), Io.bytes(size)), 1)
            }
            return keep
        } catch (_: Exception) {
            return false
        } finally {
            buf?.let { giveBack(it) }
            src.close()
        }
    }

    // ---------------- uploads ----------------

    private fun upload(c: Conn, req: Req): Boolean {
        if (req.headers["transfer-encoding"] != null) { sendText(c, 411, "", "Content-Length required", false, false); return false }
        val cl = req.headers["content-length"]?.trim()?.toLongOrNull()
        if (cl == null) { sendText(c, 411, "", "Content-Length required", false, false); return false }
        if (cl < 0) { sendText(c, 400, "", "Bad length", false, false); return false }
        val raw = req.query["name"]
        if (raw.isNullOrBlank()) { sendText(c, 400, "", "Missing name", false, false); return false }
        // Flat names only: path tricks are removed by cleanName, folders are dropped here.
        val name = Storage.cleanName(raw).substringAfterLast('/').take(120).ifEmpty { "file" }
        val ctx = appCtx
        if (ctx == null || !running) { sendText(c, 500, "", "Not ready", false, false); return false }

        // Refuse bodies that cannot fit (keeps a reserve) before anything is created or pre-allocated.
        val free = try {
            @Suppress("DEPRECATION")
            android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
        } catch (_: Exception) { Long.MAX_VALUE }
        if (cl > free - (64L shl 20)) { sendText(c, 507, "", "Not enough space", false, false); return false }

        val sink = try { Storage.createSink(ctx, name, cl) } catch (_: Exception) {
            sendText(c, 500, "", "Cannot create file", false, false); return false
        }
        val buf = borrow()
        var left = cl
        var netErr = false
        var diskErr = false
        try {
            if (req.headers["expect"]?.equals("100-continue", true) == true) c.writeAll("HTTP/1.1 100 Continue\r\n\r\n")
            while (left > 0) {
                buf.clear()
                if (c.rbuf.hasRemaining()) {
                    // Body bytes that arrived together with the headers.
                    val n = minOf(c.rbuf.remaining().toLong(), left, buf.capacity().toLong()).toInt()
                    val oldLimit = c.rbuf.limit()
                    c.rbuf.limit(c.rbuf.position() + n)
                    buf.put(c.rbuf)
                    c.rbuf.limit(oldLimit)
                } else {
                    buf.limit(minOf(buf.capacity().toLong(), left).toInt())
                    val r = c.ch.read(buf)
                    if (r < 0) { netErr = true; break }
                    if (r == 0) continue
                    c.touch()
                }
                buf.flip()
                val n = buf.remaining()
                try {
                    while (buf.hasRemaining()) sink.channel.write(buf)
                } catch (_: IOException) { diskErr = true; break }
                left -= n
                addIn(n.toLong())
            }
        } catch (_: Exception) {
            netErr = true
        } finally {
            giveBack(buf)
        }

        if (diskErr) {
            sink.abort()
            addEvent(Hub.app.getString(R.string.web_ev_up_storage, name.take(60)), 2)
            sendText(c, 507, "", "Not enough space", false, false)
            return false
        }
        if (netErr || left > 0) {
            sink.abort()
            addEvent(Hub.app.getString(R.string.web_ev_up_interrupted, name.take(60)), 2)
            return false
        }
        val uri = sink.finish()
        if (uri == null) {
            addEvent(Hub.app.getString(R.string.web_ev_up_failed, name.take(60)), 2)
            sendText(c, 500, "", "Could not save file", false, false)
            return false
        }
        Hub.addReceived(Received(name, cl, uri, Hub.app.getString(R.string.web_browser_peer)))
        synchronized(lock) { uploads++ }
        addEvent(Hub.app.getString(R.string.web_ev_received, name.take(60), Io.bytes(cl)), 2)
        send(c, 200, "OK", "application/json", "{\"ok\":true}".toByteArray(), req.keepAlive, false, listOf("Cache-Control: no-store"))
        return req.keepAlive
    }
}
