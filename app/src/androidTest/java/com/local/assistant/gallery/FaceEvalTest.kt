package com.local.assistant.gallery

import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.local.assistant.appContainer
import com.local.assistant.gallery.faces.FaceFinder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The face models on the phone against the same pipeline in Python (TensorFlow Lite on the Mac,
 * itself checked against OpenCV's own FaceDetectorYN and FaceRecognizerSF). Pictures and the
 * expected faces are pushed to files/face-eval/ first: PNGs already shrunk to 1024 px, so both
 * sides start from the same pixels, and golden.json with each face's box and print.
 *
 *     adb shell am instrument -w -e eval faces [-e chat loaded] -e class com.local.assistant.gallery.FaceEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `-e chat loaded` loads the chat model first: LiteRT-LM and LiteRT's interpreter in one process.
 */
@RunWith(AndroidJUnit4::class)
class FaceEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    @Test
    fun matchesTheReference() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e eval faces", args.getString("eval") == "faces")
        val dir = File(context.filesDir, "face-eval")
        val golden = File(dir, "golden.json").takeIf { it.isFile }?.let { JsonParser.parseString(it.readText()).asJsonObject }
        assumeTrue("push the pictures and golden.json to files/face-eval/ first", golden != null)

        if (args.getString("chat") == "loaded") {
            assertNotNull("the chat model didn't load", context.appContainer.llmService.awaitEngine())
            report("chat model loaded; PSS ${Debug.getPss() / 1024} MB")
        }
        val finder = FaceFinder(context.assets)
        try {
            var faces = 0
            var matched = 0
            val cosines = ArrayList<Float>()
            val times = ArrayList<Long>()
            for ((name, expected) in golden!!.entrySet().sortedBy { it.key }) {
                val photo = BitmapFactory.decodeFile(File(dir, name).path)
                val started = System.currentTimeMillis()
                val found = finder.find(photo)
                times += System.currentTimeMillis() - started
                val want = expected.asJsonArray.map { it.asJsonObject }
                faces += want.size
                for (w in want) {
                    val box = w.getAsJsonArray("box").map { it.asFloat }
                    val best = found.maxByOrNull { iou(it.x, it.y, it.w, it.h, box) } ?: continue
                    if (iou(best.x, best.y, best.w, best.h, box) < 0.8f) continue
                    matched++
                    val print = w.getAsJsonArray("print").map { it.asFloat }
                    cosines += best.print.indices.sumOf { (best.print[it] * print[it]).toDouble() }.toFloat()
                }
                report("$name: expected ${want.size}, found ${found.size} in ${times.last()} ms")
            }
            report("faces matched $matched of $faces")
            if (cosines.isNotEmpty()) report("print cosine vs Python: min %.4f, median %.4f".format(cosines.min(), cosines.sorted()[cosines.size / 2]))
            report("time per photo: median ${times.sorted()[times.size / 2]} ms, max ${times.max()} ms (first ${times.first()} ms)")
            report("PSS ${Debug.getPss() / 1024} MB with the face models loaded")
            assertEquals("every expected face found", faces, matched)
            // Android's and OpenCV's resampling differ a little, more so for small faces: on the
            // phone (2026-10-25) the median was 0.993 and the lowest 0.960. The same person scores
            // 0.35 and up and different people 0.21 at most, so that much doesn't change who is who.
            assertTrue("prints agree with Python", cosines.min() >= 0.95f)
        } finally {
            finder.close()
            context.getExternalFilesDir(null)?.let { File(it, "face-eval.txt").writeText(lines.joinToString("\n")) }
        }
    }

    private fun iou(x: Float, y: Float, w: Float, h: Float, box: List<Float>): Float {
        val iw = max(0f, min(x + w, box[0] + box[2]) - max(x, box[0]))
        val ih = max(0f, min(y + h, box[1] + box[3]) - max(y, box[1]))
        val inter = iw * ih
        return inter / (w * h + box[2] * box[3] - inter + 1e-9f)
    }

    private companion object {
        const val TAG = "FaceEval"
    }
}
