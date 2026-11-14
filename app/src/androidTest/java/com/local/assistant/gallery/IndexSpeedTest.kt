package com.local.assistant.gallery

import android.graphics.Bitmap
import android.util.Log
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.gallery.faces.FaceFinder
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * How fast the phone's own photos are indexed, photos and faces, as the app indexes them — into
 * a database in memory, so the app's gallery.db is never touched (the app's own indexing is
 * paused meanwhile). Only timings and counts are reported; nothing about what is in the photos.
 *
 *     adb shell am instrument -w -e eval speed -e class com.local.assistant.gallery.IndexSpeedTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `-e vision gpu|cpu` (default gpu, as the app runs), `-e threads N` (4), `-e faces on|off` (on),
 * `-e seconds N` (90) for the whole-pass run, `-e sample N` (24) photos for the per-step timings.
 * Results also go to files/index-speed.txt in the app's external storage.
 */
@RunWith(AndroidJUnit4::class)
class IndexSpeedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    @Test
    fun indexesThePhonesPhotos() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval speed", args.getString("eval") == "speed")
        val container = context.appContainer
        val modelFile = container.galleryModelManager.installed.value?.file
        assumeTrue("the photo model isn't installed", modelFile != null)
        val library = container.photoLibrary
        assumeTrue("no photo access", library.access() != PhotoLibrary.Access.NONE)

        val gpu = args.getString("vision") != "cpu"
        val threads = args.getString("threads")?.toInt() ?: 4
        val withFaces = args.getString("faces") != "off"
        val seconds = args.getString("seconds")?.toLong() ?: 90L
        val sample = args.getString("sample")?.toInt() ?: 24
        report("vision ${if (gpu) "GPU" else "CPU"}, $threads threads, faces ${if (withFaces) "on" else "off"}, ${seconds}s pass, $sample-photo sample")

        // The app's own indexing and its copy of the model out of the way.
        container.galleryIndex.pause()
        container.photoModel.unload()

        val embedder = GalleryEmbedder({ modelFile }, context.cacheDir, threads = threads, visionOnGpu = gpu)
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            steps(library, embedder, sample)
            embedder.unload()

            val faces = FaceIndex(database = database, library = library, newFinder = { FaceFinder(context.assets) }, enabled = { withFaces })
            val index = GalleryIndex(dao = database.photoDao(), library = library, embedder = embedder, scheduler = ModelScheduler(), scope = scope, faces = faces)
            val started = System.currentTimeMillis()
            val finished = index.indexUntil(started + seconds * 1000)
            val elapsed = (System.currentTimeMillis() - started) / 1000.0
            val dao = database.photoDao()
            val total = dao.count()
            val left = dao.pendingCount(GalleryModel.MODEL_ID)
            val videos = dao.videos().map { it.mediaId }.toHashSet()
            val photoCount = total - videos.size
            val framesDone = dao.frameVectors(GalleryModel.MODEL_ID).size
            val photoVectors = dao.vectors(GalleryModel.MODEL_ID).size
            val facesLeft = dao.facesPendingCount(FaceIndex.MODEL_ID)
            val facesLooked = photoCount - facesLeft
            val faceRows = database.faceDao().all().size
            report("pass: ${"%.1f".format(elapsed)} s, finished=$finished; library $total items ($photoCount photos, ${videos.size} videos)")
            report("embedded: $photoVectors photos + $framesDone video frames (${total - left} items done, $left left)")
            report("rate: ${"%.1f".format((photoVectors + framesDone) / elapsed * 60)} images/min, ${"%.0f".format(elapsed * 1000 / (photoVectors + framesDone).coerceAtLeast(1))} ms per image")
            report("faces: looked at $facesLooked photos, $faceRows faces found")
        } finally {
            scope.cancel()
            embedder.unload()
            database.close()
            context.getExternalFilesDir(null)?.let { File(it, "index-speed.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    /** Each step on its own, on the newest [sample] photos: decodes at both sizes, encoding, the embed, the face models. */
    private suspend fun steps(library: PhotoLibrary, embedder: GalleryEmbedder, sample: Int) {
        val ids = library.scan().filter { it.durationMs == null }.take(sample).map { it.mediaId }
        if (ids.isEmpty()) return
        // Loads the engine (and its vision encoder) before timing.
        library.jpeg(ids.first(), GalleryModel.INPUT_PIXELS)?.let { embedder.embedImage(it) }
        val finder = FaceFinder(context.assets)
        val small = ArrayList<Long>()
        val large = ArrayList<Long>()
        val encode = ArrayList<Long>()
        val fromLarge = ArrayList<Long>()
        val embed = ArrayList<Long>()
        val face = ArrayList<Long>()
        var largeSide = 0
        try {
            for (id in ids) {
                var t = System.nanoTime()
                val b512 = library.bitmap(id, GalleryModel.INPUT_PIXELS) ?: continue
                small += ms(t)
                t = System.nanoTime()
                val jpeg = ByteArrayOutputStream().use { b512.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
                encode += ms(t)
                b512.recycle()
                t = System.nanoTime()
                embedder.embedImage(jpeg)
                embed += ms(t)
                t = System.nanoTime()
                val b1024 = library.bitmap(id, FaceFinder.PHOTO_PIXELS) ?: continue
                large += ms(t)
                largeSide = maxOf(largeSide, maxOf(b1024.width, b1024.height))
                t = System.nanoTime()
                val scale = GalleryModel.INPUT_PIXELS.toFloat() / maxOf(b1024.width, b1024.height)
                val down = if (scale < 1f) Bitmap.createScaledBitmap(b1024, (b1024.width * scale).toInt(), (b1024.height * scale).toInt(), true) else b1024
                ByteArrayOutputStream().use { down.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (down !== b1024) down.recycle()
                fromLarge += ms(t)
                t = System.nanoTime()
                finder.find(b1024)
                face += ms(t)
                b1024.recycle()
            }
        } finally {
            finder.close()
        }
        // The face models again on the same photos with the photo model closed: what they cost
        // when nothing else is running.
        embedder.unload()
        val alone = ArrayList<Long>()
        val again = FaceFinder(context.assets)
        try {
            for (id in ids) {
                val b1024 = library.bitmap(id, FaceFinder.PHOTO_PIXELS) ?: continue
                val t = System.nanoTime()
                again.find(b1024)
                alone += ms(t)
                b1024.recycle()
            }
        } finally {
            again.close()
        }
        report("per photo (median of ${embed.size}): decode 512 px ${median(small)} ms, decode 1024 px ${median(large)} ms (largest side $largeSide), " +
            "JPEG ${median(encode)} ms, 512 from the 1024 copy ${median(fromLarge)} ms, embed ${median(embed)} ms, faces ${median(face)} ms right after an embed, " +
            "${median(alone)} ms alone (first ${alone.firstOrNull()} ms, last ${alone.lastOrNull()} ms)")
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun median(values: List<Long>) = values.sorted().getOrNull(values.size / 2) ?: -1

    private companion object {
        const val TAG = "IndexSpeed"
    }
}
