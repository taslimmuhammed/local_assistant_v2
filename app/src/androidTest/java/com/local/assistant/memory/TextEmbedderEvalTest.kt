package com.local.assistant.memory

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.local.assistant.appContainer
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Memory search on the photo model's shared engine, on the phone: the memory it holds as memory
 * search and photo search take turns with it, how fast it embeds, and how well it separates the
 * labelled pairs that set the recall threshold ([PAIRS], scored as the app does: the archived
 * exchange as a document, the message as a query, 256 dimensions, int8). Run on 2026-11-06 it
 * compared EmbeddingGemma 300M, memory search's own model then, with the photo model's text side.
 *
 *     adb shell am instrument -w -e eval textembed -e class com.local.assistant.memory.TextEmbedderEvalTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results also go to files/text-embed-eval.txt in external storage.
 */
@RunWith(AndroidJUnit4::class)
class TextEmbedderEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    @Test
    fun comparesTextModels() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval textembed", InstrumentationRegistry.getArguments().getString("eval") == "textembed")
        val container = context.appContainer
        assumeTrue("the photo model isn't installed", container.galleryModelManager.installed.value != null)
        val memory = container.embedder
        val photo = container.photoModel
        try {
            memory.unload()
            photo.unload()
            settle()
            report("baseline: ${usage()}")

            var started = System.currentTimeMillis()
            assertTrue("memory search's model didn't load", memory.prepare())
            report("memory search ${memory.modelId} loaded in ${System.currentTimeMillis() - started} ms; ${usage()}")
            calibrate("memory search (${memory.modelId})", memory.similarityThreshold) { text, kind -> memory.embed(text, kind) }
            report("after scoring: ${usage()}")

            started = System.currentTimeMillis()
            assertNotNull("photo search didn't embed a query", photo.embedQuery("photos from the beach"))
            report("a photo query on the same engine in ${System.currentTimeMillis() - started} ms; ${usage()}")
            started = System.currentTimeMillis()
            assertNotNull("the photo model didn't embed a picture", photo.embedImage(picture()))
            report("first picture in ${System.currentTimeMillis() - started} ms; ${usage()}")

            started = System.currentTimeMillis()
            photo.release()
            settle()
            assertTrue("memory search lost its model when photos let go", memory.isReady)
            report("photos released, text side kept (${System.currentTimeMillis() - started} ms): ${usage()}")

            memory.unload()
            settle()
            report("memory search unloaded: ${usage()}")
        } finally {
            context.getExternalFilesDir(null)?.let { File(it, "text-embed-eval.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    /**
     * The photo model's text side scored other ways than the app's: its other task prompts, and
     * more dimensions as plain floats, to see whether the prompt or the 256 int8 bytes hold it back.
     *
     *     -e eval textvariants
     */
    @Test
    fun comparesPhotoModelVariants() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval textvariants", InstrumentationRegistry.getArguments().getString("eval") == "textvariants")
        val photoFile = context.appContainer.galleryModelManager.installed.value?.file
        assumeTrue("the photo model isn't installed", photoFile != null)
        val engine = EmbeddingEngine(
            EmbeddingEngineConfig(modelPath = photoFile!!.absolutePath, backend = Backend.CPU(threadCount = 4), cacheDir = context.cacheDir.absolutePath),
        ).also { it.initialize() }
        try {
            fun raw(text: String, size: Int) = engine.computeEmbedding(listOf(InputData.Text(text)), EmbeddingOptions(normalize = true, outputSize = size)).embedding
            val prompts = listOf(
                "search" to (QUERY to DOCUMENT),
                "question answering" to ("task: question answering | query: " to DOCUMENT),
                "sentence similarity" to ("task: sentence similarity | query: " to "task: sentence similarity | query: "),
            )
            for ((label, prompt) in prompts) {
                calibrate("photo model, $label, 256 int8", null) { text, kind -> raw((if (kind == EmbedKind.QUERY) prompt.first else prompt.second) + text, 256) }
            }
            for (size in listOf(512, 768)) {
                calibrate("photo model, search, $size floats", null, quantized = false) { text, kind -> raw((if (kind == EmbedKind.QUERY) QUERY else DOCUMENT) + text, size) }
            }
        } finally {
            engine.close()
            context.getExternalFilesDir(null)?.let { File(it, "text-embed-eval.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    /** Scores every pair, then reports the separation and the threshold that suits this model. */
    private suspend fun calibrate(name: String, current: Float?, quantized: Boolean = true, embed: suspend (String, EmbedKind) -> FloatArray?) {
        val queryMs = ArrayList<Long>()
        val scored = PAIRS.map { (stored, asked, relevant) ->
            val document = embed(stored, EmbedKind.DOCUMENT)!!
            val started = System.currentTimeMillis()
            val query = embed(asked, EmbedKind.QUERY)!!
            queryMs += System.currentTimeMillis() - started
            val score = if (quantized) {
                Int8Vectors.similarity(Int8Vectors.cosineDistance(Int8Vectors.quantize(query)!!, Int8Vectors.quantize(document)!!))
            } else {
                cosine(query, document)
            }
            Scored(score, relevant, stored, asked)
        }
        val related = scored.filter { it.relevant }.map { it.score }.sorted()
        val unrelated = scored.filter { !it.relevant }.map { it.score }.sorted()
        report("== $name: per query median ${queryMs.sorted()[queryMs.size / 2]} ms")
        report("related   n=${related.size} min %.3f median %.3f".format(related.first(), related[related.size / 2]))
        report("unrelated n=${unrelated.size} max %.3f median %.3f".format(unrelated.last(), unrelated[unrelated.size / 2]))
        var best = 0.0 to 0f
        var tau = 0.30f
        while (tau <= 0.901f) {
            val (recall, precision) = at(scored, tau)
            // A wrong recall costs more than a missed one: the model may act on it.
            val fHalf = if (precision + recall > 0) 1.25 * precision * recall / (0.25 * precision + recall) else 0.0
            report("  τ %.2f recall %.2f precision %.2f".format(tau, recall, precision))
            if (fHalf > best.first) best = fHalf to tau
            tau += 0.02f
        }
        report("best F0.5 threshold: %.2f".format(best.second))
        // The fairest single comparison: how much is found while (almost) nothing wrong is.
        for (floor in listOf(1.0, 0.9)) {
            val cut = (30..95).map { it / 100f }.filter { at(scored, it).second >= floor }.maxByOrNull { at(scored, it).first }
            report("recall at precision ≥ %.1f: %s".format(floor, cut?.let { "%.2f (τ %.2f)".format(at(scored, it).first, it) } ?: "none"))
        }
        current?.let { report("at its current %.2f: recall %.2f precision %.2f".format(it, at(scored, it).first, at(scored, it).second)) }
        for ((group, prefix) in GROUPS) {
            val mine = scored.filter { it.relevant && GROUP_OF(it.stored) == prefix }
            report("  $group related: " + mine.joinToString { "%.2f".format(it.score) })
        }
        report("closest calls:")
        scored.sortedBy { kotlin.math.abs(it.score - best.second) }.take(10).forEach {
            report("  ${if (it.relevant) "+" else "-"} %.3f  ${it.stored.lineSequence().first().removePrefix("User: ").take(40)} ← ${it.asked}".format(it.score))
        }
    }

    private fun at(scored: List<Scored>, tau: Float): Pair<Double, Double> {
        val tp = scored.count { it.score >= tau && it.relevant }
        val fp = scored.count { it.score >= tau && !it.relevant }
        val recall = tp.toDouble() / scored.count { it.relevant }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        return recall to precision
    }

    private data class Scored(val score: Float, val relevant: Boolean, val stored: String, val asked: String)

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
    }

    private suspend fun settle() {
        repeat(2) {
            System.gc()
            Runtime.getRuntime().gc()
            delay(1_500)
        }
    }

    private fun usage(): String =
        "PSS ${Debug.getPss() / 1024} MB, native heap ${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB"

    /** A plain picture, enough to wake the vision encoder. */
    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(250, 200, 120))
            drawCircle(256f, 256f, 140f, Paint().apply { color = Color.rgb(200, 40, 40) })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private companion object {
        const val TAG = "TextEmbedderEval"
        const val QUERY = "task: search result | query: "
        const val DOCUMENT = "title: none | text: "

        fun chunk(user: String, reply: String = "Noted.") = "User: $user\nAssistant: $reply"

        val GROUPS = listOf("Hinglish" to "hinglish", "Hindi" to "hindi", "Malayalam" to "malayalam", "English" to "english")

        val GROUP_OF: (String) -> String = { text ->
            when {
                text.any { it in 'ഀ'..'ൿ' } -> "malayalam"
                text.any { it in 'ऀ'..'ॿ' } -> "hindi"
                HINGLISH.any { it in text } -> "hinglish"
                else -> "english"
            }
        }

        val HINGLISH = listOf(" ka ", " ke ", " ko ", " hai", "mera ", "kal ")

        /** (archived exchange, a later message, should it be recalled?) */
        val PAIRS = listOf(
            // English, related
            Triple(chunk("my CA is Mr. Iyer, his office is in Jayanagar", "Got it, Mr. Iyer in Jayanagar is your CA."), "who does my taxes?", true),
            Triple(chunk("my dentist is Dr. Rao near Indiranagar"), "I have a toothache, who should I call", true),
            Triple(chunk("let's plan Gokarna for the second weekend of October", "Sounds great, Gokarna on the second weekend of October."), "what did we decide about the trip", true),
            Triple(chunk("my daughter's school annual day is on 14 December"), "when is the school function", true),
            Triple(chunk("I'm allergic to peanuts", "Thanks, I'll keep that in mind for recipes."), "can I eat this satay sauce", true),
            Triple(chunk("my car insurance renews in May with ICICI Lombard"), "when is the vehicle policy due", true),
            Triple(chunk("I started running 5k three times a week", "That's a great routine!"), "how is my exercise plan going", true),
            Triple(chunk("the wifi password is on the sticker under the router"), "how do I get on the home internet", true),
            Triple(chunk("I want to learn Spanish before my Barcelona trip next year"), "any tips for my language learning", true),
            Triple(chunk("my landlord Suresh wants the rent by the 5th every month"), "when do I need to pay rent", true),
            Triple(chunk("I keep my passport in the blue folder in the cupboard"), "where did I put my passport", true),
            Triple(chunk("my son's football coach is Mr. D'Souza, practice is Saturday 7am"), "what time is football practice", true),
            Triple(chunk("generate a website for an ice-cream shop in HTML", "Here is a basic HTML page for an ice-cream shop..."), "what was that web page you made for me", true),
            Triple(chunk("my blood pressure was 140/90 at the last checkup", "That's a bit high, worth following up."), "what did the doctor say about my BP", true),
            Triple(chunk("I prefer window seats and vegetarian meals on flights"), "book me something for the Delhi flight", true),
            Triple(chunk("we're thinking of getting a golden retriever puppy"), "remind me what dog breed we wanted", true),
            // Hinglish, related
            Triple(chunk("Amma ka birthday 12 March ko hai"), "mother's birthday kab hai", true),
            Triple(chunk("kal Rahul ke saath lunch hai Koramangala mein, 1 baje"), "what are my plans with Rahul", true),
            Triple(chunk("mera gym membership 30 November ko khatam ho raha hai"), "when does my gym plan expire", true),
            Triple(chunk("electricity ka bill har mahine 10 tarikh tak bharna hai"), "bijli bill kab tak pay karna hai", true),
            Triple(chunk("Priya ko chocolate cake bahut pasand hai"), "what cake should I get for Priya", true),
            Triple(chunk("office ka laptop Dell hai, IT wale Ramesh bhai hain"), "laptop kharab ho gaya, kisko bolu", true),
            // Hindi / Malayalam, related
            Triple(chunk("मेरी बहन का नाम प्रिया है"), "what is my sister's name", true),
            Triple(chunk("मेरी दवाई सुबह नाश्ते के बाद लेनी है"), "when should I take my medicine", true),
            Triple(chunk("എന്റെ ഡോക്ടറുടെ പേര് ഡോ. മേനോൻ ആണ്"), "who is my doctor", true),
            Triple(chunk("അച്ഛന്റെ ഓപ്പറേഷൻ അടുത്ത ചൊവ്വാഴ്ചയാണ്"), "when is father's surgery", true),
            // hard negatives: same kind of thing, different fact
            Triple(chunk("my CA is Mr. Iyer, his office is in Jayanagar"), "who is my dentist?", false),
            Triple(chunk("my dentist is Dr. Rao near Indiranagar"), "who is my CA?", false),
            Triple(chunk("let's plan Gokarna for the second weekend of October"), "what time is my flight to Delhi tomorrow", false),
            Triple(chunk("my daughter's school annual day is on 14 December"), "when is my son's football practice", false),
            Triple(chunk("I'm allergic to peanuts"), "am I allergic to dust?", false),
            Triple(chunk("my car insurance renews in May with ICICI Lombard"), "when does my health insurance renew", false),
            Triple(chunk("I started running 5k three times a week"), "what did I eat for dinner yesterday", false),
            Triple(chunk("my landlord Suresh wants the rent by the 5th every month"), "when is the electricity bill due", false),
            Triple(chunk("I keep my passport in the blue folder in the cupboard"), "where are my car keys", false),
            Triple(chunk("Amma ka birthday 12 March ko hai"), "Rahul ka birthday kab hai", false),
            Triple(chunk("kal Rahul ke saath lunch hai Koramangala mein, 1 baje"), "what are my plans with Anjali", false),
            Triple(chunk("mera gym membership 30 November ko khatam ho raha hai"), "when does my Netflix subscription end", false),
            Triple(chunk("मेरी बहन का नाम प्रिया है"), "what is my brother's name", false),
            Triple(chunk("എന്റെ ഡോക്ടറുടെ പേര് ഡോ. മേനോൻ ആണ്"), "who is my lawyer", false),
            Triple(chunk("my blood pressure was 140/90 at the last checkup"), "what was my cholesterol result", false),
            Triple(chunk("I prefer window seats and vegetarian meals on flights"), "what hotel do I like in Goa", false),
            Triple(chunk("generate a website for an ice-cream shop in HTML"), "write me a Python script to rename files", false),
            // plain negatives: nothing to do with it
            Triple(chunk("my CA is Mr. Iyer, his office is in Jayanagar"), "what's a good pasta recipe", false),
            Triple(chunk("my dentist is Dr. Rao near Indiranagar"), "explain how black holes form", false),
            Triple(chunk("let's plan Gokarna for the second weekend of October"), "fix this null pointer exception", false),
            Triple(chunk("my daughter's school annual day is on 14 December"), "what's the capital of Peru", false),
            Triple(chunk("I'm allergic to peanuts"), "set an alarm for 6", false),
            Triple(chunk("my car insurance renews in May with ICICI Lombard"), "write a poem about the sea", false),
            Triple(chunk("I started running 5k three times a week"), "translate good morning into French", false),
            Triple(chunk("the wifi password is on the sticker under the router"), "recommend a thriller novel", false),
            Triple(chunk("Amma ka birthday 12 March ko hai"), "how do I reset my phone", false),
            Triple(chunk("kal Rahul ke saath lunch hai Koramangala mein, 1 baje"), "summarise this article for me", false),
            Triple(chunk("मेरी बहन का नाम प्रिया है"), "what's the weather like in winter", false),
            Triple(chunk("electricity ka bill har mahine 10 tarikh tak bharna hai"), "tell me a joke", false),
            Triple(chunk("Priya ko chocolate cake bahut pasand hai"), "how does compound interest work", false),
            Triple(chunk("office ka laptop Dell hai, IT wale Ramesh bhai hain"), "who won the 2011 world cup", false),
            Triple(chunk("I want to learn Spanish before my Barcelona trip next year"), "what's 15% of 2400", false),
            Triple(chunk("we're thinking of getting a golden retriever puppy"), "how do I make filter coffee", false),
            Triple(chunk("അച്ഛന്റെ ഓപ്പറേഷൻ അടുത്ത ചൊവ്വാഴ്ചയാണ്"), "suggest a name for my startup", false),
        )
    }
}
