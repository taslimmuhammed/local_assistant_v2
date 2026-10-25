package com.local.assistant.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** One photo or video as MediaStore lists it; [durationMs] only for a video. */
data class LibraryPhoto(
    val mediaId: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
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
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    enum class Access { ALL, SOME, NONE }

    fun access(): Access = when {
        granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.ALL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SOME
        else -> Access.NONE
    }

    /**
     * Whether videos can be read too; asked for with photos, but a separate permission since
     * Android 13. "Selected only" covers videos the user picked, but Android grants it alongside
     * full photo access too, where it says nothing about videos.
     */
    fun videoAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            access() == Access.SOME
        else -> access() != Access.NONE
    }

    /** What to ask for when photos are shared but videos aren't (granted before videos were searched). */
    val videoPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else emptyArray()

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Video ids and their lengths, so one id is enough everywhere: MediaStore ids are unique
     * across photos and videos, but each has its own address. Read once, then kept current by [scan].
     */
    private val videoLengths = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var videosRead = false

    private fun knowVideos() {
        if (videosRead) return
        if (videoAccess()) {
            resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION), null, null, null)
                ?.use { while (it.moveToNext()) videoLengths[it.getLong(0)] = it.getLong(1) }
        }
        videosRead = true
    }

    /** How long [mediaId] runs if it's a video; null for a photo. */
    fun videoLength(mediaId: Long): Long? {
        knowVideos()
        return videoLengths[mediaId]
    }

    fun uri(mediaId: Long): Uri = ContentUris.withAppendedId(
        if (videoLength(mediaId) != null) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        mediaId,
    )

    /** The type to open it in another app with: any image, or any video. */
    fun mimeType(mediaId: Long): String = if (videoLength(mediaId) != null) "video/*" else "image/*"

    /**
     * Whether photos' locations can be read. Since Android 10 they come stripped unless the app
     * holds ACCESS_MEDIA_LOCATION; before that, photo access was enough.
     */
    fun locationAccess(): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** What to ask for to read locations; Android grants it with no dialog of its own. */
    val locationPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION) else emptyArray()

    /** Where the photo or video was taken, from its GPS tags: latitude and longitude, or null if it has none. */
    fun location(mediaId: Long): Pair<Double, Double>? = if (videoLength(mediaId) != null) videoLocation(mediaId) else try {
        val source = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri(mediaId)) else uri(mediaId)
        resolver.openInputStream(source)?.use { stream ->
            // The float form: the double one is Android 10 and up only.
            val out = FloatArray(2)
            @Suppress("DEPRECATION")
            if (ExifInterface(stream).getLatLong(out) && !(out[0] == 0f && out[1] == 0f)) out[0].toDouble() to out[1].toDouble() else null
        }
    } catch (e: Exception) {
        null
    }

    /** A video's location tag, "+12.4580+075.9590/" (ISO 6709), as latitude and longitude. */
    private fun videoLocation(mediaId: Long): Pair<Double, Double>? = try {
        val source = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri(mediaId)) else uri(mediaId)
        val tag = MediaMetadataRetriever().run {
            try {
                setDataSource(context, source)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            } finally {
                release()
            }
        }
        tag?.let(::iso6709)
    } catch (e: Exception) {
        null
    }

    /**
     * Moments of video [mediaId], one every [everyMs] (at most [maxFrames], at least one, each in
     * the middle of its stretch), as JPEGs at most [maxSide] pixels on the longer side, with when
     * each is. Empty if the video can't be read.
     */
    fun frames(mediaId: Long, durationMs: Long, maxSide: Int, everyMs: Long, maxFrames: Int): List<Pair<Long, ByteArray>> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri(mediaId))
            val count = (durationMs / everyMs).toInt().coerceIn(1, maxFrames)
            (0 until count).mapNotNull { i ->
                val atMs = durationMs * (2 * i + 1) / (2 * count)
                val frame = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
                } else {
                    retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaled(it, maxSide) }
                } ?: return@mapNotNull null
                try {
                    atMs to ByteArrayOutputStream().use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        out.toByteArray()
                    }
                } finally {
                    frame.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read video $mediaId: ${e.message}")
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Every photo, and video, the app may see, newest first. Empty without access. */
    fun scan(): List<LibraryPhoto> {
        if (access() == Access.NONE) return emptyList()
        val photos = scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false)
        if (!videoAccess()) return photos
        val videos = scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true)
        videoLengths.keys.retainAll(videos.mapTo(HashSet()) { it.mediaId })
        videos.forEach { videoLengths[it.mediaId] = it.durationMs!! }
        videosRead = true
        return (photos + videos).sortedByDescending { it.dateTaken }
    }

    private fun scan(collection: Uri, video: Boolean): List<LibraryPhoto> {
        // The image and video columns share their names (MediaColumns).
        val projection = listOfNotNull(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Video.Media.DURATION.takeIf { video },
        ).toTypedArray()
        val photos = ArrayList<LibraryPhoto>()
        resolver.query(
            collection,
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
            val duration = if (video) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
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
                    // Some videos list no length; one frame from their start is still something.
                    durationMs = if (video) cursor.getLong(duration).coerceAtLeast(1) else null,
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
        val collections = listOfNotNull(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI.takeIf { videoAccess() })
        for (collection in collections) for (chunk in mediaIds.chunked(SQL_BATCH)) {
            resolver.query(
                collection,
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
        val length = videoLength(mediaId)
        if (Build.VERSION.SDK_INT < 29 && length != null) {
            // A frame a little way in, past any fade from black.
            frames(mediaId, minOf(length, 2_000L), maxSide, everyMs = Long.MAX_VALUE, maxFrames = 1).firstOrNull()
                ?.let { (_, jpeg) -> BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
        } else if (Build.VERSION.SDK_INT >= 29) {
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

/** Latitude and longitude from a video's location tag, ISO 6709 ("+12.4580+075.9590/"); null if it has none. */
internal fun iso6709(tag: String): Pair<Double, Double>? =
    ISO_6709.find(tag.trim())?.let { m -> m.groupValues[1].toDouble() to m.groupValues[2].toDouble() }
        ?.takeUnless { (lat, lon) -> lat == 0.0 && lon == 0.0 }

private val ISO_6709 = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")
