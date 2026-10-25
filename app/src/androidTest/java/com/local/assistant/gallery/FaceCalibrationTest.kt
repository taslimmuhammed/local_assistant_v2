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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File

/**
 * Every face in the phone's photos, for setting [com.local.assistant.gallery.faces.FaceGrouping]'s
 * thresholds by looking at how they group. In memory only: nothing goes into the photo index and
 * "Find people" stays as it is. Writes to files/face-calibration/ in external storage:
 * prints.bin (float32 × 128 per face), faces.csv (index, photo, box, score), and atlas-N.png,
 * the faces as 96 px tiles, 1,000 to an atlas, in index order. Delete it after use.
 *
 *     adb shell am instrument -w -e eval facecal -e class com.local.assistant.gallery.FaceCalibrationTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FaceCalibrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyFace() {
        assumeTrue("opt-in: -e eval facecal", InstrumentationRegistry.getArguments().getString("eval") == "facecal")
        val library = context.appContainer.photoLibrary
        val out = File(context.getExternalFilesDir(null), "face-calibration").apply { deleteRecursively(); mkdirs() }
        val photos = library.scan()
        val finder = FaceFinder(context.assets)
        val prints = DataOutputStream(File(out, "prints.bin").outputStream().buffered())
        val csv = File(out, "faces.csv").printWriter()
        csv.println("index,mediaId,x,y,w,h,score")
        var atlas: Bitmap? = null
        var index = 0
        val started = System.currentTimeMillis()

        fun flushAtlas() {
            atlas?.let { a ->
                File(out, "atlas-${(index - 1) / PER_ATLAS}.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
                a.recycle()
            }
            atlas = null
        }

        try {
            for ((n, photo) in photos.withIndex()) {
                val bitmap = library.bitmap(photo.mediaId, FaceFinder.PHOTO_PIXELS) ?: continue
                for (face in finder.find(bitmap)) {
                    face.print.forEach(prints::writeFloat)
                    csv.println("$index,${photo.mediaId},${face.x},${face.y},${face.w},${face.h},${face.score}")
                    if (index % PER_ATLAS == 0) {
                        flushAtlas()
                        atlas = Bitmap.createBitmap(COLUMNS * TILE, (PER_ATLAS / COLUMNS) * TILE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    }
                    val slot = index % PER_ATLAS
                    // A little room around the box, square, so the face can be recognised.
                    val side = maxOf(face.w * bitmap.width, face.h * bitmap.height) * 1.3f
                    val cx = (face.x + face.w / 2) * bitmap.width
                    val cy = (face.y + face.h / 2) * bitmap.height
                    val src = Rect((cx - side / 2).toInt(), (cy - side / 2).toInt(), (cx + side / 2).toInt(), (cy + side / 2).toInt())
                    val dst = Rect((slot % COLUMNS) * TILE, (slot / COLUMNS) * TILE, (slot % COLUMNS + 1) * TILE, (slot / COLUMNS + 1) * TILE)
                    Canvas(atlas!!).drawBitmap(bitmap, src, dst, null)
                    index++
                }
                bitmap.recycle()
                if (n % 200 == 0) Log.i(TAG, "$n of ${photos.size} photos, $index faces, ${(System.currentTimeMillis() - started) / 1000} s")
            }
            if (index > 0) flushAtlas()
            Log.i(TAG, "Done: ${photos.size} photos, $index faces, in ${(System.currentTimeMillis() - started) / 1000} s")
        } finally {
            prints.close()
            csv.close()
            finder.close()
        }
    }

    private companion object {
        const val TAG = "FaceCalibration"
        const val TILE = 96
        const val COLUMNS = 40
        const val PER_ATLAS = 1_000
    }
}
