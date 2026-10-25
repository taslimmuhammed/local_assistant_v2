package com.local.assistant.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/** One photo as MediaStore lists it. */
data class LibraryPhoto(
    val mediaId: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
)

/**
 * The phone's photos, through MediaStore: what there is, and small decoded copies of them.
 * Read-only; nothing here changes or moves a photo.
 */
class PhotoLibrary(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** The permissions to ask for on this Android version. */
    val permissions: Array<String> = when {
        // Android 14 lets people share only some photos; asking for both offers that choice.
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    enum class Access { ALL, SOME, NONE }

    fun access(): Access = when {
        granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.ALL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SOME
        else -> Access.NONE
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun uri(mediaId: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

    /** Every photo the app may see, newest first. Empty without access. */
    fun scan(): List<LibraryPhoto> {
        if (access() == Access.NONE) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val photos = ArrayList<LibraryPhoto>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val width = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val height = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (cursor.moveToNext()) {
                // DATE_TAKEN is milliseconds and can be missing (screenshots, downloads);
                // DATE_ADDED is seconds and always there.
                val takenMs = cursor.getLong(taken).takeIf { it > 0 } ?: (cursor.getLong(added) * 1000)
                photos += LibraryPhoto(
                    mediaId = cursor.getLong(id),
                    dateTaken = takenMs,
                    dateModified = cursor.getLong(modified),
                    width = cursor.getInt(width),
                    height = cursor.getInt(height),
                )
            }
        }
        return photos
    }

    /**
     * When each of [mediaIds] was taken (milliseconds), from MediaStore: DATE_TAKEN, or DATE_ADDED
     * where that is missing (screenshots, downloads). Photos deleted since are left out.
     */
    fun datesTaken(mediaIds: Collection<Long>): Map<Long, Long> {
        if (mediaIds.isEmpty() || access() == Access.NONE) return emptyMap()
        val dates = HashMap<Long, Long>(mediaIds.size * 2)
        for (chunk in mediaIds.chunked(SQL_BATCH)) {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})",
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    dates[cursor.getLong(0)] = cursor.getLong(1).takeIf { it > 0 } ?: (cursor.getLong(2) * 1000)
                }
            }
        }
        return dates
    }

    /**
     * The photo at most [maxSide] pixels on its longer side, upright, or null if it can't be
     * read (deleted since the scan, a format the phone can't decode).
     */
    fun bitmap(mediaId: Long, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            // The system's thumbnail cache: fast, and already rotated upright.
            resolver.loadThumbnail(uri(mediaId), Size(maxSide, maxSide), null)
        } else {
            decodeSampled(mediaId, maxSide)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read photo $mediaId: ${e.message}")
        null
    }

    /** [bitmap] as JPEG bytes, the form the embedding model takes. */
    fun jpeg(mediaId: Long, maxSide: Int): ByteArray? {
        val bitmap = bitmap(mediaId, maxSide) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeSampled(mediaId: Long, maxSide: Int): Bitmap? {
        val uri = uri(mediaId)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = orientation(mediaId)
        if (degrees == 0) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    @Suppress("DEPRECATION") // ORIENTATION is the only way before API 29.
    private fun orientation(mediaId: Long): Int =
        resolver.query(uri(mediaId), arrayOf(MediaStore.Images.Media.ORIENTATION), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0

    private companion object {
        const val TAG = "PhotoLibrary"
        const val JPEG_QUALITY = 90
        const val SQL_BATCH = 500
    }
}
