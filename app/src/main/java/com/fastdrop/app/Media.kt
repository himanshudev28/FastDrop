package com.fastdrop.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

enum class MediaKind { IMAGE, VIDEO, AUDIO }

class MediaItem(
    val id: Long, val uri: Uri, val name: String, val size: Long, val mime: String,
    val dateMs: Long, val durationMs: Long, val kind: MediaKind, val bucket: String?,
)

/** Reads photos, videos and music from MediaStore, and knows which permissions that needs. */
object Media {
    enum class Access { FULL, PARTIAL, NONE }

    private const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    private const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    private const val READ_MEDIA_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /** Permissions to request for this kind on the running Android version. */
    fun permissions(kind: MediaKind): Array<String> {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk < 23 -> emptyArray()
            sdk < 33 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            sdk == 33 -> arrayOf(
                when (kind) {
                    MediaKind.IMAGE -> READ_MEDIA_IMAGES
                    MediaKind.VIDEO -> READ_MEDIA_VIDEO
                    MediaKind.AUDIO -> READ_MEDIA_AUDIO
                }
            )
            else -> when (kind) {
                MediaKind.IMAGE -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_SELECTED)
                MediaKind.VIDEO -> arrayOf(READ_MEDIA_VIDEO, READ_MEDIA_SELECTED)
                MediaKind.AUDIO -> arrayOf(READ_MEDIA_AUDIO)
            }
        }
    }

    private fun granted(ctx: Context, perm: String) =
        (Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED)

    fun access(ctx: Context, kind: MediaKind): Access {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 23) return Access.FULL
        val perms = permissions(kind)
        if (perms.isEmpty()) return Access.FULL
        if (granted(ctx, perms[0])) return Access.FULL
        // Android 14+: the user may have picked "Select photos" only.
        if (sdk >= 34 && perms.size > 1 && granted(ctx, perms[1])) return Access.PARTIAL
        return Access.NONE
    }

    private fun collection(kind: MediaKind): Uri = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri("external")
        MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri("external")
        MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri("external")
    }

    /** Newest first, skips empty files. Call from a worker thread. */
    fun query(ctx: Context, kind: MediaKind, limit: Int = 20000): List<MediaItem> {
        if (access(ctx, kind) == Access.NONE) return emptyList()
        val base = collection(kind)
        val cols = ArrayList<String>()
        cols += MediaStore.MediaColumns._ID
        cols += MediaStore.MediaColumns.DISPLAY_NAME
        cols += MediaStore.MediaColumns.SIZE
        cols += MediaStore.MediaColumns.MIME_TYPE
        cols += MediaStore.MediaColumns.DATE_MODIFIED
        cols += MediaStore.MediaColumns.DATE_ADDED
        // Older versions can lack a display name for some rows, so keep the path as a fallback.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 29) cols += MediaStore.MediaColumns.DATA
        when (kind) {
            MediaKind.IMAGE -> { cols += MediaStore.Images.Media.DATE_TAKEN; cols += MediaStore.Images.Media.BUCKET_DISPLAY_NAME }
            MediaKind.VIDEO -> { cols += MediaStore.Video.Media.DATE_TAKEN; cols += MediaStore.Video.Media.BUCKET_DISPLAY_NAME; cols += MediaStore.Video.Media.DURATION }
            MediaKind.AUDIO -> { cols += MediaStore.Audio.Media.ALBUM; cols += MediaStore.Audio.Media.DURATION }
        }
        var selection = "${MediaStore.MediaColumns.SIZE} > 0"
        if (kind == MediaKind.AUDIO) {
            // Hide ringtones, alarms and notification sounds.
            selection += " AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0" +
                " AND ${MediaStore.Audio.Media.IS_ALARM} = 0" +
                " AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0"
        }
        Thumbs.clearFailed()
        val out = ArrayList<MediaItem>()
        try {
            ctx.contentResolver.query(base, cols.toTypedArray(), selection, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                @Suppress("DEPRECATION")
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iTaken = when (kind) {
                    MediaKind.IMAGE -> c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    MediaKind.VIDEO -> c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                    MediaKind.AUDIO -> -1
                }
                val iBucket = when (kind) {
                    MediaKind.IMAGE -> c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    MediaKind.VIDEO -> c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    MediaKind.AUDIO -> c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                }
                val iDur = when (kind) {
                    MediaKind.IMAGE -> -1
                    MediaKind.VIDEO -> c.getColumnIndex(MediaStore.Video.Media.DURATION)
                    MediaKind.AUDIO -> c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                }
                while (c.moveToNext() && out.size < limit) {
                    val size = c.getLong(iSize)
                    if (size <= 0) continue
                    val id = c.getLong(iId)
                    var name = c.getString(iName)
                    if (name.isNullOrBlank() && iData >= 0) name = c.getString(iData)?.substringAfterLast('/')
                    if (name.isNullOrBlank()) name = "${kind.name.lowercase()}_$id"
                    val taken = if (iTaken >= 0) c.getLong(iTaken) else 0L
                    var date = if (taken > 0) taken else c.getLong(iMod) * 1000
                    if (date <= 0) date = c.getLong(iAdded) * 1000
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        name = name!!,
                        size = size,
                        mime = c.getString(iMime) ?: defaultMime(kind),
                        dateMs = date,
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                        kind = kind,
                        bucket = if (iBucket >= 0) c.getString(iBucket) else null,
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked while we were querying.
        } catch (_: Exception) {
        }
        // Rows come back by date added; show newest by the best date we have.
        out.sortByDescending { it.dateMs }
        return out
    }

    private fun defaultMime(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE -> "image/*"
        MediaKind.VIDEO -> "video/*"
        MediaKind.AUDIO -> "audio/*"
    }

    fun toSendItem(m: MediaItem) = SendItem(m.name, m.size, m.uri, null)
}
