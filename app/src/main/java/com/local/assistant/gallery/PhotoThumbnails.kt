package com.local.assistant.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small decoded copies of the phone's photos, for every place that shows them: the Photos screen,
 * photo results under a reply, and the assistant panel. One cache for all of them, so a photo
 * seen in a chat appears at once on the Photos screen and the other way round.
 */
class PhotoThumbnails(private val library: PhotoLibrary) {

    /** About 24 MB of decoded thumbnails, the most recently seen kept. */
    private val cache = object : LruCache<Long, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Null when the photo is gone or can't be read. */
    suspend fun thumbnail(mediaId: Long): ImageBitmap? {
        cache.get(mediaId)?.let { return it }
        return withContext(Dispatchers.IO) { library.bitmap(mediaId, THUMBNAIL_PIXELS)?.asImageBitmap() }
            ?.also { cache.put(mediaId, it) }
    }

    /** A copy big enough to fill the screen, for the viewer; not cached. */
    suspend fun full(mediaId: Long, maxSide: Int): ImageBitmap? =
        withContext(Dispatchers.IO) { library.bitmap(mediaId, maxSide)?.asImageBitmap() }

    fun uri(mediaId: Long): Uri = library.uri(mediaId)

    /** The type to open it in another app with: any image, or any video. */
    fun mimeType(mediaId: Long): String = library.mimeType(mediaId)

    /** How long [mediaId] runs if it's a video; null for a photo. */
    suspend fun videoLength(mediaId: Long): Long? = withContext(Dispatchers.IO) { library.videoLength(mediaId) }

    /** How many of [mediaIds] are videos. */
    suspend fun videoCount(mediaIds: Collection<Long>): Int = withContext(Dispatchers.IO) { mediaIds.count { library.videoLength(it) != null } }

    /** [face] cut square from its photo, with a little room around it, for a round avatar. */
    suspend fun face(face: FaceEntity): ImageBitmap? {
        val key = -face.id - 1 // never a MediaStore id
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val photo = library.bitmap(face.mediaId, FACE_SOURCE_PIXELS) ?: return@withContext null
            try {
                val side = (maxOf(face.w * photo.width, face.h * photo.height) * FACE_ROOM).toInt().coerceAtMost(minOf(photo.width, photo.height))
                val cx = ((face.x + face.w / 2) * photo.width).toInt()
                val cy = ((face.y + face.h / 2) * photo.height).toInt()
                val left = (cx - side / 2).coerceIn(0, photo.width - side)
                val top = (cy - side / 2).coerceIn(0, photo.height - side)
                val square = Bitmap.createBitmap(photo, left, top, side, side)
                Bitmap.createScaledBitmap(square, FACE_PIXELS, FACE_PIXELS, true).also { if (square !== it) square.recycle() }.asImageBitmap()
            } finally {
                photo.recycle()
            }
        }?.also { cache.put(key, it) }
    }

    /** When each photo was taken, for sorting by date; deleted ones are missing. */
    suspend fun datesTaken(mediaIds: Collection<Long>): Map<Long, Long> =
        withContext(Dispatchers.IO) { library.datesTaken(mediaIds) }

    fun clear() = cache.evictAll()

    private companion object {
        const val THUMBNAIL_PIXELS = 256
        const val FACE_SOURCE_PIXELS = 1024
        const val FACE_PIXELS = 160
        const val FACE_ROOM = 1.5f
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
