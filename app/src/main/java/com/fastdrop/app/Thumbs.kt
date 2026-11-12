package com.fastdrop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Async thumbnail loader: memory cache, 3 worker threads, cancels loads for recycled views, fades in. */
object Thumbs {
    private class Job(val key: String, val task: FutureTask<*>?)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val pool = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "fd-thumb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }.apply { allowCoreThreadTimeOut(true) }

    // Which load each view currently wants. Touched on the main thread only.
    private val current = WeakHashMap<ImageView, Job>()
    // Keys that produced nothing, so scrolling doesn't retry them again and again.
    private val failed: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "flv", "wmv")
    private val AUD = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma", "mid")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun iconFor(name: String): Int {
        val e = ext(name)
        return when {
            e in IMG -> R.drawable.ic_photo
            e in VID -> R.drawable.ic_video
            e in AUD -> R.drawable.ic_music
            e == "apk" -> R.drawable.ic_apps
            else -> R.drawable.ic_file
        }
    }

    fun tintFor(name: String): Int {
        val e = ext(name)
        return when {
            e in IMG -> Theme.CAT_PHOTOS
            e in VID -> Theme.CAT_VIDEOS
            e in AUD -> Theme.CAT_MUSIC
            e == "apk" -> Theme.CAT_APPS
            else -> Theme.CAT_FILES
        }
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    /** Forget remembered failures (call when a media list reloads) so transient ones are retried. */
    fun clearFailed() { failed.clear() }

    // ---- public loaders ----

    fun media(iv: ImageView, item: MediaItem, sizePx: Int) {
        val name = when (item.kind) {
            MediaKind.IMAGE -> "x.jpg"; MediaKind.VIDEO -> "x.mp4"; MediaKind.AUDIO -> "x.mp3"
        }
        val ctx = iv.context.applicationContext
        load(iv, "m:${item.uri}:$sizePx", name) { loadMedia(ctx, item, sizePx) }
    }

    fun app(iv: ImageView, pkg: String) {
        val ctx = iv.context.applicationContext
        val px = Theme.dp(ctx, 96)
        load(iv, "a:$pkg", "x.apk") { loadAppIcon(ctx, pkg, px) }
    }

    fun file(iv: ImageView, name: String, uri: Uri?, sizePx: Int) {
        val ctx = iv.context.applicationContext
        val e = ext(name)
        val thumbable = uri != null && (e in IMG || e in VID || e in AUD)
        if (!thumbable) {
            cancel(iv)
            iv.setImageDrawable(placeholder(ctx, name))
            return
        }
        load(iv, "f:$uri:$sizePx", name) { loadFile(ctx, name, uri!!, sizePx) }
    }

    // ---- core ----

    private fun load(iv: ImageView, key: String, name: String, work: () -> Bitmap?) {
        val ctx = iv.context.applicationContext
        cancel(iv)
        iv.animate().cancel()
        iv.alpha = 1f

        val hit = cache.get(key)
        if (hit != null) { iv.setImageBitmap(hit); return }
        // Show the type icon while loading (and keep it if loading fails).
        iv.setImageDrawable(placeholder(ctx, name))
        if (failed.contains(key)) return

        lateinit var task: FutureTask<Unit>
        task = FutureTask<Unit> {
            var threw = false
            val bmp = try { work() } catch (_: Throwable) { threw = true; null }
            if (bmp != null) cache.put(key, bmp) else if (!threw) failed.add(key)
            Hub.main.post {
                val job = current[iv]
                if (bmp == null || job == null || job.key != key || job.task !== task) return@post
                current.remove(iv)
                iv.setImageBitmap(bmp)
                if (Theme.animationsOn(ctx)) {
                    iv.alpha = 0f
                    iv.animate().alpha(1f).setDuration(Theme.DUR_FAST).setInterpolator(Theme.EASE_OUT).start()
                }
            }
        }
        current[iv] = Job(key, task)
        pool.execute(task)
    }

    private fun cancel(iv: ImageView) {
        val old = current.remove(iv) ?: return
        old.task?.let { it.cancel(false); pool.remove(it) }
    }

    private fun placeholder(ctx: Context, name: String): Drawable? = try {
        val d = ctx.resources.getDrawable(iconFor(name), null).mutate()
        d.setTint(tintFor(name))
        InsetDrawable(d, (ctx.resources.displayMetrics.density * 14f).toInt())
    } catch (_: Exception) { null }

    // ---- workers (run on pool threads) ----

    private fun loadMedia(ctx: Context, m: MediaItem, px: Int): Bitmap? {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            return try { cr.loadThumbnail(m.uri, Size(px, px), null) } catch (_: Exception) { null }
        }
        // Below Android 10: old MediaStore thumbnails (none for audio).
        val kind = if (px > 160) MediaStore.Images.Thumbnails.MINI_KIND else MediaStore.Images.Thumbnails.MICRO_KIND
        @Suppress("DEPRECATION")
        return try {
            when (m.kind) {
                MediaKind.IMAGE -> MediaStore.Images.Thumbnails.getThumbnail(cr, m.id, kind, null)
                MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(cr, m.id, kind, null)
                MediaKind.AUDIO -> null
            }
        } catch (_: Exception) { null }
    }

    private fun loadFile(ctx: Context, name: String, uri: Uri, px: Int): Bitmap? {
        val e = ext(name)
        return when {
            e in IMG -> decodeSampled(ctx, uri, px)
            else -> {
                if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
                    try { return ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) } catch (_: Exception) { }
                }
                retrieve(ctx, uri, e in AUD, px)
            }
        }
    }

    private fun decodeSampled(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    // Video frame, or embedded cover art for audio.
    private fun retrieve(ctx: Context, uri: Uri, audio: Boolean, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val bmp = if (audio) {
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            bmp?.let { scaleDown(it, px) }
        } catch (_: Exception) { null } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    private fun scaleDown(b: Bitmap, px: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (px <= 0 || longest <= px * 2) return b
        val s = px * 2f / longest
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun loadAppIcon(ctx: Context, pkg: String, px: Int): Bitmap? {
        val pm = ctx.packageManager
        var d: Drawable? = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
        if (d == null && Hub.isTv) d = try { pm.getApplicationBanner(pkg) } catch (_: Exception) { null }
        if (d == null) return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) minOf(d.intrinsicWidth, px * 2) else px
        val h = if (d.intrinsicHeight > 0) minOf(d.intrinsicHeight, px * 2) else px
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }
}
