package com.local.assistant.memory

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.EmbedderCatalog
import com.local.assistant.memory.embed.InstalledEmbedder
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.embed.LiteRtEmbedder
import com.local.assistant.memory.retrieval.ChunkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * The installed embedder on this phone: load time, query and chunk latency, and similarity on
 * labelled pairs to calibrate the inject threshold. Opt-in, and needs an embedder installed in
 * the app (or `-e embedder /path/to/bundle.litertlm`):
 *
 *     adb shell am instrument -w -e embed true -e class com.local.assistant.memory.EmbedderProbeTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results also go to files/embedder-probe.txt in the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class EmbedderProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val out = StringBuilder()

    private fun report(line: String) {
        out.appendLine(line)
        android.util.Log.i("EmbedderProbe", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("probe", line) })
        File(context.getExternalFilesDir(null), "embedder-probe.txt").writeText(out.toString())
    }

    /** Pairs a user might really produce; true when the second should recall the first. */
    private val pairs = listOf(
        Triple("my CA is Mr. Iyer, his office is in Jayanagar", "who does my taxes?", true),
        Triple("my dentist is Dr. Rao near Indiranagar", "I have a toothache, who should I see", true),
        Triple("we should go to Gokarna on the second weekend of October", "what was the plan for the trip", true),
        Triple("my daughter's school annual day is on 14 December", "when is the school function", true),
        Triple("I'm allergic to peanuts", "can I eat this satay sauce", true),
        Triple("my car insurance renews in May with ICICI Lombard", "when is the vehicle policy due", true),
        Triple("remind me to pay the electricity bill every month", "did I set up anything for bills", true),
        Triple("I started running 5k three times a week", "how is my fitness routine going", true),
        Triple("Amma ka birthday 12 March ko hai", "mother's birthday kab hai", true),
        Triple("kal Rahul ke saath lunch hai Koramangala mein", "what are my plans with Rahul", true),
        Triple("मेरी बहन का नाम प्रिया है", "what is my sister's name", true),
        Triple("the wifi password is on the router sticker", "how do I connect to the internet at home", true),
        Triple("my CA is Mr. Iyer, his office is in Jayanagar", "what's a good pasta recipe", false),
        Triple("my dentist is Dr. Rao near Indiranagar", "explain how black holes form", false),
        Triple("we should go to Gokarna on the second weekend of October", "fix this Python error", false),
        Triple("my daughter's school annual day is on 14 December", "what's the capital of Peru", false),
        Triple("I'm allergic to peanuts", "set an alarm for 6", false),
        Triple("my car insurance renews in May with ICICI Lombard", "write a poem about the sea", false),
        Triple("remind me to pay the electricity bill every month", "who won the 2011 world cup", false),
        Triple("I started running 5k three times a week", "translate good morning into French", false),
        Triple("Amma ka birthday 12 March ko hai", "how do I reset my phone", false),
        Triple("kal Rahul ke saath lunch hai Koramangala mein", "summarise this article for me", false),
        Triple("मेरी बहन का नाम प्रिया है", "what's the weather like in winter", false),
        Triple("the wifi password is on the router sticker", "recommend a thriller novel", false),
    )

    @Test
    fun probe() = runBlocking {
        assumeTrue("opt-in: -e embed true", args.getString("embed") == "true")
        val settings = SettingsStore(context)
        val path = args.getString("embedder") ?: settings.embedderPath
        assumeTrue("no embedder installed and no -e embedder path", path != null && File(path).isFile)
        val spec = if (args.getString("embedder") != null) {
            EmbedderCatalog.forImport(File(path!!).name)
        } else {
            EmbedderCatalog.byKey(settings.embedderKey) ?: EmbedderCatalog.DEFAULT
        }
        val embedder = LiteRtEmbedder({ InstalledEmbedder(File(path!!), spec) }, context.cacheDir)
        report("model ${spec.modelId} (${File(path!!).length() / 1_000_000} MB), prefixes q='${spec.queryPrefix}' d='${spec.documentPrefix}'")

        var started = System.nanoTime()
        assertTrue(embedder.prepare())
        report("load: ${(System.nanoTime() - started) / 1_000_000} ms")

        // Output checks: at least 256 dimensions, unit length.
        val sample = embedder.embed("hello there, how are you doing today", EmbedKind.QUERY)
        assertNotNull(sample)
        val norm = sqrt(sample!!.take(Int8Vectors.DIMENSIONS).sumOf { (it * it).toDouble() })
        report("output: ${sample.size} floats, first-256 norm %.4f".format(norm))
        assertEquals(1.0, norm, 0.01)

        suspend fun time(texts: List<String>, kind: EmbedKind): List<Long> = texts.map { text ->
            started = System.nanoTime()
            embedder.embed(text, kind)
            (System.nanoTime() - started) / 1_000_000
        }
        val queries = pairs.map { it.second } + pairs.map { it.first }
        val documents = pairs.map { (fact, _, _) ->
            ChunkPolicy.text(fact, null, "Noted. " + "I will keep that in mind and bring it up when it matters. ".repeat(6))!!
        }
        time(queries.take(3), EmbedKind.QUERY) // Warm-up.
        val q = time(queries, EmbedKind.QUERY).sorted()
        val d = time(documents, EmbedKind.DOCUMENT).sorted()
        report("query embed: median ${q[q.size / 2]} ms, p95 ${q[(q.size * 95) / 100]} ms, max ${q.last()} ms (${q.size} texts)")
        report("chunk embed: median ${d[d.size / 2]} ms, p95 ${d[(d.size * 95) / 100]} ms, max ${d.last()} ms (~${documents.first().length} chars)")

        // Similarity as the archive computes it: document side stored, query side asked.
        val related = mutableListOf<Float>()
        val unrelated = mutableListOf<Float>()
        for ((stored, asked, relevant) in pairs) {
            val a = Int8Vectors.quantize(embedder.embed(ChunkPolicy.text(stored, null, null)!!, EmbedKind.DOCUMENT)!!)!!
            val b = Int8Vectors.quantize(embedder.embed(asked, EmbedKind.QUERY)!!)!!
            val similarity = 1 - Int8Vectors.cosineDistance(a, b)
            (if (relevant) related else unrelated) += similarity
            report("%s %.3f  \"%s\" ← \"%s\"".format(if (relevant) "+" else "-", similarity, stored.take(40), asked))
        }
        report(
            "related: min %.3f median %.3f · unrelated: max %.3f median %.3f · threshold now %.2f".format(
                related.min(), related.sorted()[related.size / 2], unrelated.max(), unrelated.sorted()[unrelated.size / 2], spec.similarityThreshold,
            ),
        )
        embedder.unload()
    }
}
