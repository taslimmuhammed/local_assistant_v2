package com.local.assistant.gallery

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

    /** When each photo was taken, for sorting by date; deleted ones are missing. */
    suspend fun datesTaken(mediaIds: Collection<Long>): Map<Long, Long> =
        withContext(Dispatchers.IO) { library.datesTaken(mediaIds) }

    fun clear() = cache.evictAll()

    private companion object {
        const val THUMBNAIL_PIXELS = 256
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
