package com.local.assistant.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.gallery.faces.FaceFinder
import com.local.assistant.gallery.faces.FacePrints
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File

/**
 * The faces in the photo index as they stand (who each is, named or matched, and its print), for
 * seeing which of a person's faces matching misses and why: too small, too unlike their named
 * faces, or never found. Reads the index; changes nothing. Writes to files/face-recall/ in
 * external storage: faces.csv, people.csv, prints.bin (float32 × 128 per face, in faces.csv
 * order) and atlas-N.png (the faces as 96 px tiles, 1,000 to an atlas, in the same order).
 * Delete it after use.
 *
 *     adb shell am instrument -w -e eval facerecall -e class com.local.assistant.gallery.FaceRecallTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FaceRecallTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dumpTheIndex() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval facerecall", InstrumentationRegistry.getArguments().getString("eval") == "facerecall")
        val library = context.appContainer.photoLibrary
        val database = GalleryDatabase.build(context)
        val out = File(context.getExternalFilesDir(null), "face-recall").apply { deleteRecursively(); mkdirs() }
        try {
            val dao = database.faceDao()
            val faces = dao.all().sortedBy { it.mediaId }
            File(out, "people.csv").printWriter().use { csv ->
                csv.println("id,name,isMe")
                dao.people().forEach { csv.println("${it.id},${it.name.replace(',', ' ')},${it.isMe}") }
            }
            DataOutputStream(File(out, "prints.bin").outputStream().buffered()).use { prints ->
                File(out, "faces.csv").printWriter().use { csv ->
                    csv.println("index,id,mediaId,x,y,w,h,score,personId,confirmed,notPersonId,groupId")
                    faces.forEachIndexed { i, f ->
                        FacePrints.unit(f.print).forEach(prints::writeFloat)
                        csv.println("$i,${f.id},${f.mediaId},${f.x},${f.y},${f.w},${f.h},${f.score},${f.personId ?: ""},${f.confirmed},${f.notPersonId ?: ""},${f.groupId ?: ""}")
                    }
                }
            }

            // The tiles, one photo read for all its faces.
            var atlas: Bitmap? = null
            fun save(n: Int) {
                atlas?.let { a -> File(out, "atlas-$n.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }; a.recycle() }
                atlas = null
            }
            var photo: Bitmap? = null
            var photoId = -1L
            faces.forEachIndexed { i, f ->
                if (i % PER_ATLAS == 0) {
                    if (i > 0) save(i / PER_ATLAS - 1)
                    atlas = Bitmap.createBitmap(COLUMNS * TILE, (PER_ATLAS / COLUMNS) * TILE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                }
                if (f.mediaId != photoId) {
                    photo?.recycle()
                    photo = library.bitmap(f.mediaId, FaceFinder.PHOTO_PIXELS)
                    photoId = f.mediaId
                }
                val bitmap = photo ?: return@forEachIndexed
                val side = maxOf(f.w * bitmap.width, f.h * bitmap.height) * 1.3f
                val cx = (f.x + f.w / 2) * bitmap.width
                val cy = (f.y + f.h / 2) * bitmap.height
                val slot = i % PER_ATLAS
                val src = Rect((cx - side / 2).toInt(), (cy - side / 2).toInt(), (cx + side / 2).toInt(), (cy + side / 2).toInt())
                val dst = Rect((slot % COLUMNS) * TILE, (slot / COLUMNS) * TILE, (slot % COLUMNS + 1) * TILE, (slot / COLUMNS + 1) * TILE)
                Canvas(atlas!!).drawBitmap(bitmap, src, dst, null)
            }
            photo?.recycle()
            if (faces.isNotEmpty()) save((faces.size - 1) / PER_ATLAS)
            Log.i(TAG, "Wrote ${faces.size} faces")
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TAG = "FaceRecall"
        const val TILE = 96
        const val COLUMNS = 40
        const val PER_ATLAS = 1_000
    }
}
