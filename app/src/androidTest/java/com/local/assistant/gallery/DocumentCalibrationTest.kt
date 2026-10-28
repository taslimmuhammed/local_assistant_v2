package com.local.assistant.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * For setting [DocumentPhotos.THRESHOLD] on the phone's own photos, without anyone looking at
 * the documents: the photos photo search finds for kinds of document stand for the documents,
 * and each threshold is reported as how many of those it hides and how many photos it hides in
 * all. Counts only. Needs the photos scored first (an indexing pass on the Photos screen).
 *
 *     adb shell am instrument -w -e eval doccal [-e index true] -e class com.local.assistant.gallery.DocumentCalibrationTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DocumentCalibrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thresholds() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval doccal", InstrumentationRegistry.getArguments().getString("eval") == "doccal")
        val index = context.appContainer.galleryIndex
        // "-e index true": an indexing pass first, which scores whatever isn't scored yet.
        if (InstrumentationRegistry.getArguments().getString("index") == "true") {
            val started = System.currentTimeMillis()
            index.indexUntil(started + 10 * 60_000L)
            report("indexing pass: ${System.currentTimeMillis() - started} ms")
        }
        val database = GalleryDatabase.build(context)
        try {
            val scores = database.photoDao().docScores().associate { it.mediaId to it.docScore }
            report("scored photos: ${scores.size}")
            val found = HashMap<String, Set<Long>>()
            for (query in QUERIES) found[query] = index.search(query).orEmpty().map { it.mediaId }.filter { it in scores }.toSet()
            val positives = found.values.flatten().toSet()
            report("found by document searches: ${positives.size} (${found.entries.joinToString { "${it.key} ${it.value.size}" }})")
            for (t in 60..82 step 2) {
                val threshold = t / 100f
                val hidden = scores.count { it.value >= threshold }
                val caught = positives.count { scores.getValue(it) >= threshold }
                val perQuery = found.entries.joinToString { (q, ids) -> "$q ${ids.count { scores.getValue(it) >= threshold }}/${ids.size}" }
                report("%.2f: hides %d photos, %d of %d found documents | %s".format(threshold, hidden, caught, positives.size, perQuery))
            }
        } finally {
            database.close()
        }
    }

    private fun report(line: String) {
        Log.i("DocumentCalibration", line)
        File(context.getExternalFilesDir(null), "document-calibration.txt").appendText("$line\n")
    }

    private companion object {
        val QUERIES = listOf(
            "driving licence", "passport", "aadhaar card", "pan card", "credit card", "debit card",
            "certificate", "mark sheet", "id card",
        )
    }
}
