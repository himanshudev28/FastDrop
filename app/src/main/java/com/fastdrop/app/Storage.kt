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
    private val onFinish: () -> Uri,
    private val onAbort: () -> Unit,
    /** Resumable partials only: MediaStore uri (API 29+) or temp file path, saved in the resume record. */
    val location: String? = null,
) {
    @Volatile private var closed = false

    /**
     * Seals the file and returns its final uri. Throws if it could not be finalised: a resumable
     * partial (has [location]) is left in place for a later retry; anything else is cleaned up.
     */
    @Synchronized
    @Throws(IOException::class)
    fun finish(): Uri {
        if (closed) throw IOException("already closed")
        closed = true
        Io.closeQuietly(closer)
        try {
            return onFinish()
        } catch (e: Exception) {
            if (location == null) try { onAbort() } catch (_: Exception) { }
            throw if (e is IOException) e else IOException(e.message ?: "finalize failed", e)
        }
    }

    /** Closes the file but leaves the partial on disk (for resume). */
    @Synchronized
    fun keep() {
        if (closed) return
        closed = true
        Io.closeQuietly(closer)
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

    /**
     * [partial] = resumable: the file stays invisible (IS_PENDING row, or a hidden temp file below
     * Android 10) until [Sink.finish], and [Sink.location] says where it lives.
     */
    fun createSink(ctx: Context, rel: String, size: Long, partial: Boolean = false): Sink {
        val clean = cleanName(rel)
        val dir = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        return if (Build.VERSION.SDK_INT >= 29) mediaStoreSink(ctx, dir, name, partial)
        else if (partial) filePartialSink(ctx, dir, name, size, null)
        else fileSink(ctx, dir, name, size)
    }

    /** Reopens a partial from a resume record without truncating it; null if it is gone. */
    fun reopenSink(ctx: Context, rec: Resume.Rec): Sink? = try {
        if (Build.VERSION.SDK_INT >= 29) mediaReopen(ctx, rec)
        else {
            val clean = cleanName(rec.name)
            val f = File(rec.location)
            if (!f.isFile) null
            else filePartialSink(ctx, clean.substringBeforeLast('/', ""), clean.substringAfterLast('/'), rec.size, f)
        }
    } catch (_: Exception) { null }

    /** Length of the partial file, or -1 if it no longer exists. */
    fun partialLength(ctx: Context, location: String): Long = try {
        // A row that is no longer IS_PENDING is a finished file: never reopen it for writing.
        if (Build.VERSION.SDK_INT >= 29) { if (isPending(ctx, location) == true) mediaLength(ctx, location) else -1L }
        else File(location).let { if (it.isFile) it.length() else -1L }
    } catch (_: Exception) { -1L }

    /** True only for hidden temp files this app makes for partials (".name.xxxx.fdpart"). */
    fun isPartTempName(name: String): Boolean = name.length > 1 && name.startsWith(".") && name.endsWith(".fdpart")

    /**
     * Whether the MediaStore row at [location] still exists and is IS_PENDING. Null = couldn't tell.
     * Finished files (IS_PENDING = 0) and missing rows give false.
     */
    @android.annotation.SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    fun isPending(ctx: Context, location: String): Boolean? = try {
        val uri = Uri.parse(location)
        val cols = arrayOf(MediaStore.MediaColumns.IS_PENDING)
        // Pending rows are hidden from plain queries, so ask for them explicitly.
        val c = if (Build.VERSION.SDK_INT >= 30) {
            val args = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            ctx.contentResolver.query(uri, cols, args, null)
        } else ctx.contentResolver.query(MediaStore.setIncludePending(uri), cols, null, null, null)
        c?.use { if (it.moveToFirst()) it.getInt(0) == 1 else false } ?: false
    } catch (_: Exception) { null }

    /**
     * Deletes a partial only if it is still a partial: a pending MediaStore row on API 29+, a hidden
     * .fdpart temp file below. Never touches a finished (visible) file.
     */
    fun deletePartial(ctx: Context, location: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                if (isPending(ctx, location) == true) ctx.contentResolver.delete(Uri.parse(location), null, null)
            } else {
                val f = File(location)
                if (isPartTempName(f.name)) f.delete()
            }
        } catch (_: Exception) { }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun mediaLength(ctx: Context, location: String): Long =
        ctx.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { it.statSize } ?: -1L

    @android.annotation.SuppressLint("NewApi")
    private fun mediaReopen(ctx: Context, rec: Resume.Rec): Sink? {
        val cr = ctx.contentResolver
        val uri = Uri.parse(rec.location)
        // "rw", never "w": write-only mode may truncate what is already there.
        val pfd = try { cr.openFileDescriptor(uri, "rw") } catch (_: Exception) { null } ?: return null
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        return Sink(out.channel, out,
            onFinish = {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            },
            onAbort = { cr.delete(uri, null, null) },
            location = rec.location)
    }

    @android.annotation.SuppressLint("NewApi")
    private fun mediaStoreSink(ctx: Context, dir: String, name: String, partial: Boolean): Sink {
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
            onAbort = { cr.delete(uri, null, null) },
            location = if (partial) uri.toString() else null)
    }

    @Suppress("DEPRECATION")
    private fun downloadFolder(dir: String): File {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        return (if (dir.isEmpty()) base else File(base, dir)).also { it.mkdirs() }
    }

    /** Below Android 10 a resumable file is a hidden temp file in the target folder, renamed when done. */
    private fun filePartialSink(ctx: Context, dir: String, name: String, size: Long, existing: File?): Sink {
        val folder = downloadFolder(dir)
        val temp = existing ?: File(folder, ".$name.${UUID.randomUUID().toString().take(8)}.fdpart")
        val raf = RandomAccessFile(temp, "rw")
        if (existing == null) try { raf.setLength(size) } catch (_: Exception) { }
        return Sink(raf.channel, raf,
            onFinish = {
                val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
                var f = File(folder, name)
                var i = 1
                while (f.exists() || !temp.renameTo(f)) {
                    if (i > 500) throw IOException("Cannot rename $name")
                    f = File(folder, if (ext.isEmpty() || ext == name) "$name ($i)" else "$stem ($i).$ext"); i++
                }
                val r = Uri.fromFile(f)
                MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null) { _, u ->
                    if (u != null) Hub.received.firstOrNull { it.uri == r }?.uri = u
                }
                r
            },
            onAbort = { temp.delete() },
            location = temp.absolutePath)
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

    /** Source file's last-modified time in ms (resume keys only); 0 when unknown. */
    fun lastModified(ctx: Context, item: SendItem): Long {
        item.file?.let { return it.lastModified() }
        val u = item.uri ?: return 0L
        if (u.scheme.equals("file", true)) return File(u.path ?: return 0L).lastModified()
        val cols = listOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1L, MediaStore.MediaColumns.DATE_MODIFIED to 1000L)
        for ((col, mul) in cols) {
            try {
                ctx.contentResolver.query(u, arrayOf(col), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) { val v = c.getLong(0); if (v > 0) return v * mul }
                }
            } catch (_: Exception) { }
        }
        return 0L
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
