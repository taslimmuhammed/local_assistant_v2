package com.local.assistant.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Where photo search stops being right, on the phone's real gallery: for each query, the top
 * [SHOWN] photos in ranked order as one contact sheet, each tile labelled with its rank, its
 * similarity and how many standard deviations it stands above the query's mean, plus a CSV of
 * the same. Someone looks at the sheets and notes where the matches end; [PhotoRanking]'s cutoff
 * is set from that. Nothing leaves the phone unless the sheets are pulled with adb.
 *
 *     adb shell am instrument -w -e eval calibrate -e queries "beach,sunset,food" \
 *         -e class com.local.assistant.gallery.GalleryCalibrationTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Writes files/calibration/<query>.png and .csv in external storage.
 */
@RunWith(AndroidJUnit4::class)
class GalleryCalibrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun contactSheets() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e eval calibrate", args.getString("eval") == "calibrate")
        val queries = args.getString("queries")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        assumeTrue("name the queries: -e queries \"beach,sunset\"", queries.isNotEmpty())
        val container = context.appContainer
        val out = File(context.getExternalFilesDir(null), "calibration").apply { mkdirs() }

        for (query in queries) {
            val all = container.galleryIndex.scoreAll(query)
            assertNotNull("photo search model didn't load", all)
            val scores = all!!.map { it.similarity.toDouble() }
            val mean = scores.average()
            val spread = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
            val shown = all.take(SHOWN)

            File(out, "$query.csv").printWriter().use { csv ->
                csv.println("rank,mediaId,similarity,z")
                shown.forEachIndexed { i, hit ->
                    csv.println("${i + 1},${hit.mediaId},%.4f,%.2f".format(hit.similarity, (hit.similarity - mean) / spread))
                }
            }

            val sheet = Bitmap.createBitmap(COLUMNS * TILE, ((shown.size + COLUMNS - 1) / COLUMNS) * (TILE + LABEL), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet).apply { drawColor(Color.WHITE) }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.BLACK }
            shown.forEachIndexed { i, hit ->
                val x = (i % COLUMNS) * TILE
                val y = (i / COLUMNS) * (TILE + LABEL)
                container.photoLibrary.bitmap(hit.mediaId, TILE)?.let { photo ->
                    // Centre-crop to a square.
                    val side = minOf(photo.width, photo.height)
                    val src = Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
                    canvas.drawBitmap(photo, src, Rect(x + 2, y + 2, x + TILE - 2, y + TILE - 2), null)
                    photo.recycle()
                }
                canvas.drawText("#${i + 1} %.3f z%.1f".format(hit.similarity, (hit.similarity - mean) / spread), x + 6f, y + TILE + 24f, text)
            }
            File(out, "$query.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
            Log.i(TAG, "\"$query\": mean %.3f spread %.3f; top %.3f; wrote ${shown.size} tiles".format(mean, spread, all.first().similarity))
        }
    }

    private companion object {
        const val TAG = "GalleryCalibration"
        const val SHOWN = 100
        const val COLUMNS = 10
        const val TILE = 200
        const val LABEL = 32
    }
}
