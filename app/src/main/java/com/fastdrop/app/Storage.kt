package com.fastdrop.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID

/** A file being received. Parallel streams write into it at their own offsets. */
class Sink(
    val channel: FileChannel,
    private val closer: Closeable,
    private val onFinish: () -> Uri?,
    private val onAbort: () -> Unit,
) {
    @Volatile private var closed = false

    @Synchronized
    fun finish(): Uri? {
        if (closed) return null
        closed = true
        Io.closeQuietly(closer)
        return try { onFinish() } catch (_: Exception) { null }
    }

    @Synchronized
    fun abort() {
        if (closed) return
        closed = true
        Io.closeQuietly(closer)
        try { onAbort() } catch (_: Exception) { }
    }
}

class Source(val channel: FileChannel, private val closer: Closeable) : Closeable {
    override fun close() = Io.closeQuietly(closer)
}

object Storage {
    const val FOLDER = "FastDrop"

    /** Strips path tricks ("..", absolute paths, illegal chars). Keeps sub-folders. */
    fun cleanName(rel: String): String {
        val parts = rel.split('/', '\\')
            .map { p -> p.filter { it >= ' ' && it !in ":*?\"<>|" }.trim().take(120) }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        return if (parts.isEmpty()) "file" else parts.joinToString("/")
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun createSink(ctx: Context, rel: String, size: Long): Sink {
        val clean = cleanName(rel)
        val dir = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        return if (Build.VERSION.SDK_INT >= 29) mediaStoreSink(ctx, dir, name) else fileSink(ctx, dir, name, size)
    }

    @android.annotation.SuppressLint("NewApi")
    private fun mediaStoreSink(ctx: Context, dir: String, name: String): Sink {
        val cr = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + (if (dir.isEmpty()) "" else "/$dir"))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Cannot create $name in Downloads")
        val pfd = try { cr.openFileDescriptor(uri, "rw") } catch (e: Exception) { cr.delete(uri, null, null); throw e }
            ?: run { cr.delete(uri, null, null); throw IOException("Cannot open $name") }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        return Sink(out.channel, out,
            onFinish = {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            },
            onAbort = { cr.delete(uri, null, null) })
    }

    @Suppress("DEPRECATION")
    private fun fileSink(ctx: Context, dir: String, name: String, size: Long): Sink {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        val folder = if (dir.isEmpty()) base else File(base, dir)
        folder.mkdirs()
        var f = File(folder, name)
        var i = 1
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        // createNewFile is atomic: parallel uploads with the same name can't pick the same file.
        while (true) {
            val made = try { f.createNewFile() } catch (e: IOException) { if (!f.exists()) throw e else false }
            if (made) break
            f = File(folder, if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext"); i++
        }
        val raf = RandomAccessFile(f, "rw")
        try { raf.setLength(size) } catch (_: Exception) { }
        val target = f
        return Sink(raf.channel, raf,
            onFinish = {
                val r = Uri.fromFile(target)
                MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), null) { _, u ->
                    if (u != null) Hub.received.firstOrNull { it.uri == r }?.uri = u
                }
                r
            },
            onAbort = { target.delete() })
    }

    // ---------------- sending side ----------------

    fun openSource(ctx: Context, item: SendItem): Source {
        item.file?.let { val fis = FileInputStream(it); return Source(fis.channel, fis) }
        val pfd = ctx.contentResolver.openFileDescriptor(item.uri!!, "r") ?: throw IOException("Cannot open ${item.name}")
        val inp = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return Source(inp.channel, inp)
    }

    /** file:// from other apps: never our own private storage; only the shared external storage tree. */
    private fun isSharableFile(ctx: Context, f: File): Boolean = try {
        val p = f.canonicalPath
        val priv = listOfNotNull(File(ctx.applicationInfo.dataDir), ctx.cacheDir, ctx.filesDir, ctx.codeCacheDir)
            .map { it.canonicalPath } + ("/data/data/" + ctx.packageName)
        @Suppress("DEPRECATION")
        val ext = Environment.getExternalStorageDirectory().canonicalPath
        val myExt = ctx.getExternalFilesDir(null)?.parentFile?.canonicalPath
        priv.none { p == it || p.startsWith("$it/") } &&
            (myExt == null || !(p == myExt || p.startsWith("$myExt/"))) &&
            (p.startsWith("$ext/") || p.startsWith("/storage/") || p.startsWith("/mnt/media_rw/"))
    } catch (_: Exception) { false }

    fun itemFromUri(ctx: Context, uri: Uri, prefix: String = ""): SendItem? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (!isSharableFile(ctx, f)) return null
            return if (f.isFile) SendItem(prefix + f.name, f.length(), null, f) else null
        }
        var name: String? = null
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) { }
        val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        // The real size from the file descriptor (also proves it's seekable = can use parallel streams).
        var seekable = false
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { val s = it.statSize; if (s >= 0) { size = s; seekable = true } }
        } catch (_: Exception) { return null }
        if (!seekable) {
            // Streams without a real file behind them: copy to cache once, then send at full speed.
            val dir = File(ctx.cacheDir, "send").apply { mkdirs() }
            val f = File(dir, UUID.randomUUID().toString() + "_" + cleanName(n).replace('/', '_'))
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o, Proto.IO_BUF) } } ?: return null
            return SendItem(prefix + n, f.length(), null, f)
        }
        return SendItem(prefix + n, size, uri, null)
    }

    fun itemsFromTree(ctx: Context, tree: Uri): List<SendItem> {
        val out = ArrayList<SendItem>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var rootName = "Folder"
        try {
            ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) rootName = it.getString(0) ?: rootName
            }
        } catch (_: Exception) { }
        walkTree(ctx, tree, rootId, "$rootName/", out, 0)
        return out
    }

    private fun walkTree(ctx: Context, tree: Uri, docId: String, prefix: String, out: MutableList<SendItem>, depth: Int) {
        if (depth > 32) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: "file"
                val mime = c.getString(2)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(ctx, tree, id, "$prefix$name/", out, depth + 1)
                } else {
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (size >= 0) out.add(SendItem(prefix + name, size, docUri, null))
                    else itemFromUri(ctx, docUri, prefix)?.let { out.add(it) }
                }
            }
        }
    }

    fun itemsFromFile(f: File, prefix: String = "", out: MutableList<SendItem> = ArrayList(), depth: Int = 0): MutableList<SendItem> {
        if (depth > 32) return out
        if (f.isDirectory) {
            f.listFiles()?.sortedBy { it.name.lowercase(Locale.US) }?.forEach {
                itemsFromFile(it, "$prefix${f.name}/", out, depth + 1)
            }
        } else if (f.isFile && f.canRead()) {
            out.add(SendItem(prefix + f.name, f.length(), null, f))
        }
        return out
    }

    fun clearSendCache(ctx: Context) {
        File(ctx.cacheDir, "send").listFiles()?.forEach { it.delete() }
    }
}
