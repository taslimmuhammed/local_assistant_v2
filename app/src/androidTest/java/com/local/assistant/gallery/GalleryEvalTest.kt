package com.local.assistant.gallery

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Photo search on the phone: EmbeddingGemma 2 through LiteRT-LM, on pictures whose subject is
 * known. The 16 pictures are Wikipedia articles' lead images (Sunset, Dog, Cat, …), named after
 * the article and shrunk to 512 px, pushed to files/gallery-eval/ beforehand:
 *
 *     for f in *.jpg; do adb exec-in run-as com.local.assistant sh -c "mkdir -p files/gallery-eval && cat > files/gallery-eval/$f" < $f; done
 *     adb shell am instrument -w -e eval gallery -e class com.local.assistant.gallery.GalleryEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `-e vision cpu` (and `-e threads N`) keeps the vision encoder on the CPU; `-e chat loaded` loads
 * the chat model first, as "Keep the assistant ready" has it.
 *
 * Downloads the model through the app's own manager first if it isn't installed (388 MB). Reports
 * load time, time per picture and per query, memory, and how [PhotoRanking]'s cutoff separates
 * the right pictures from the rest; results also go to files/gallery-eval.txt in external storage.
 */
@RunWith(AndroidJUnit4::class)
class GalleryEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    /** Query → the picture that answers it; null for queries several pictures answer. */
    private val queries = linkedMapOf(
        "sunset" to "Sunset", "a dog" to "Dog", "my cat" to "Cat", "beach" to "Beach", "pizza" to "Pizza",
        "mountains" to "Mountain", "birthday cake" to "Birthday_cake", "car" to "Automobile", "wedding" to "Wedding",
        "snow" to "Snow", "shop bill" to "Receipt", "elephant" to "Elephant", "biryani" to "Biryani",
        "Taj Mahal" to "Taj_Mahal", "rainy day" to "Rain", "selfie" to "Selfie", "food" to null, "animals" to null,
    )

    @Test
    fun searchesKnownPictures() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval gallery", InstrumentationRegistry.getArguments().getString("eval") == "gallery")
        val pictures = File(context.filesDir, "gallery-eval").listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("push the test pictures to files/gallery-eval/ first", pictures.isNotEmpty())

        val manager = context.appContainer.galleryModelManager
        if (manager.installed.value == null) {
            val started = System.currentTimeMillis()
            manager.download()
            val (installed, error) = withTimeout(20 * 60_000L) {
                combine(manager.installed, manager.error) { i, e -> i to e }.first { (i, e) -> i != null || e != null }
            }
            assertNotNull("download failed: $error", installed)
            report("download: ${installed!!.file.length()} bytes in ${(System.currentTimeMillis() - started) / 1000} s")
        }
        val args = InstrumentationRegistry.getArguments()
        val threads = args.getString("threads")?.toInt() ?: 4
        val gpu = args.getString("vision") != "cpu"
        report("vision on ${if (gpu) "GPU" else "CPU, $threads threads"}")
        // As with "Keep the assistant ready": the chat model already loaded on the GPU.
        if (args.getString("chat") == "loaded") {
            val started = System.currentTimeMillis()
            assertNotNull("the chat model didn't load", context.appContainer.llmService.awaitEngine())
            report("chat model loaded in ${System.currentTimeMillis() - started} ms; PSS ${Debug.getPss() / 1024} MB")
        }
        val embedder = GalleryEmbedder({ manager.installed.value?.file }, context.cacheDir, threads = threads, visionOnGpu = gpu)
        try {
            val pssBefore = Debug.getPss()
            var started = System.currentTimeMillis()
            assertNotNull("query embedding failed", embedder.embedQuery("warm up"))
            report("load + first query: ${System.currentTimeMillis() - started} ms")

            val names = pictures.map { it.nameWithoutExtension }
            val vectors = ArrayList<ByteArray>()
            val imageMs = ArrayList<Long>()
            for (picture in pictures) {
                started = System.currentTimeMillis()
                val vector = embedder.embedImage(picture.readBytes())
                imageMs += System.currentTimeMillis() - started
                vectors += Int8Vectors.quantize(assertNotNullAndGet(vector, picture.name))!!
            }
            report("per picture: median ${imageMs.sorted()[imageMs.size / 2]} ms, max ${imageMs.max()} ms (first ${imageMs.first()} ms)")
            report("memory: PSS ${pssBefore / 1024} MB before load, ${Debug.getPss() / 1024} MB with the model loaded")

            val ids = LongArray(names.size) { it.toLong() }
            val flat = ByteArray(names.size * Int8Vectors.DIMENSIONS).also { out -> vectors.forEachIndexed { i, v -> v.copyInto(out, i * Int8Vectors.DIMENSIONS) } }
            val norms = IntArray(names.size) { Int8Vectors.squaredNorm(vectors[it]) }

            var firstRight = 0
            var single = 0
            val queryMs = ArrayList<Long>()
            val matchZ = ArrayList<Double>()
            val otherZ = ArrayList<Double>()
            for ((query, want) in queries) {
                started = System.currentTimeMillis()
                val q = Int8Vectors.quantize(embedder.embedQuery(query)!!)!!
                queryMs += System.currentTimeMillis() - started
                val scores = DoubleArray(names.size) { i ->
                    Int8Vectors.similarity(Int8Vectors.cosineDistance(q, vectors[i])).toDouble()
                }
                val mean = scores.average()
                val spread = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
                val order = scores.indices.sortedByDescending { scores[it] }
                val hits = PhotoRanking.rank(q, ids, flat, norms).map { names[it.mediaId.toInt()] }
                report("\"$query\": top ${order.take(3).joinToString { "${names[it]} %.3f".format(scores[it]) }} | returned $hits")
                if (want != null) {
                    single++
                    if (names[order.first()] == want) firstRight++
                    names.indices.forEach { i -> (if (names[i] == want) matchZ else otherZ) += (scores[i] - mean) / spread }
                }
            }
            report("right picture first: $firstRight of $single")
            report("z: right pictures min %.2f; others max %.2f".format(matchZ.min(), otherZ.max()))
            report("per query: median ${queryMs.sorted()[queryMs.size / 2]} ms")
            assertTrue("right picture first in only $firstRight of $single", firstRight >= single - 1)
        } finally {
            embedder.unload()
            context.getExternalFilesDir(null)?.let { File(it, "gallery-eval.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    private fun assertNotNullAndGet(vector: FloatArray?, name: String): FloatArray {
        assertNotNull("no vector for $name", vector)
        return vector!!
    }

    private companion object {
        const val TAG = "GalleryEval"
    }
}
