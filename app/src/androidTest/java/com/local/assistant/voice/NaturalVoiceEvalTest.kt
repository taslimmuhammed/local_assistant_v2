package com.local.assistant.voice

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.concurrent.thread

/**
 * The natural voice on the phone. Installs it the way Settings does when it isn't here, then
 * measures loading, memory, and speed at 2, 4 and 6 threads, alone and while the chat model
 * writes a reply, and the wait for the first sound through [NaturalVoice] itself, which plays
 * out loud. The audio it made is saved for listening, and for checking with a speech
 * recogniser on the Mac, under `Android/data/<app>/files/natural-voice/`; results go to
 * `natural-voice.txt` beside it.
 *
 *     adb shell am instrument -w -e eval natural-voice \
 *         -e class com.local.assistant.voice.NaturalVoiceEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The replies are the ones the Mac comparison used (Assistant Voice Auditions). The test chat
 * is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class NaturalVoiceEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val clips by lazy { File(context.getExternalFilesDir(null), "natural-voice").apply { mkdirs() } }
    private val results by lazy { File(context.getExternalFilesDir(null), "natural-voice.txt") }
    private val voice = NaturalVoices.HEART

    /** Installing, loading, memory, and speed. */
    @Test
    fun speed() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval natural-voice", InstrumentationRegistry.getArguments().getString("eval") == "natural-voice")
        val pack = install()
        report("${voice.id}: speaker uses a natural voice = ${withContext(Dispatchers.Main) { container.speaker.naturalVoiceInUse() }}")

        // Loading and memory, from a cold start of the voice.
        withContext(Dispatchers.Main) { container.speaker.release() }
        delay(1_000)
        System.gc()
        val before = pssMb()
        val loadStarted = SystemClock.elapsedRealtime()
        var tts = OfflineTts(null, voice.config(pack, 4))
        report("load, 4 threads: ${SystemClock.elapsedRealtime() - loadStarted} ms; PSS +${pssMb() - before} MB (before $before MB)")

        val peak = PeakPss()
        speed("alone, 4 threads", tts, save = true)
        report("peak PSS while speaking: +${peak.stop() - before} MB")
        tts.release()

        for (threads in listOf(2, 6)) {
            tts = OfflineTts(null, voice.config(pack, threads))
            speed("alone, $threads threads", tts, save = false)
            tts.release()
        }

        // As in the overlay: the chat model writes on the GPU while the voice speaks.
        for (threads in listOf(4, 6)) {
            tts = OfflineTts(null, voice.config(pack, threads))
            voice.generate(tts, pack, "Warming up.", 1f, null)
            whileTheModelWrites(KOCHI) { speed("while the chat model writes, $threads threads", tts, save = false) }
            tts.release()
        }
    }

    /** How long until the first sound, through [NaturalVoice] as the overlay uses it (plays out loud). */
    @Test
    fun firstSound() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval natural-voice", InstrumentationRegistry.getArguments().getString("eval") == "natural-voice")
        val pack = install()
        withContext(Dispatchers.Main) { container.speaker.release() }
        heard("first sound, voice not loaded", prepared = false, pack, SPOKEN)
        heard("first sound, prepared as the overlay opens", prepared = true, pack, SPOKEN)
        heard("first sound, prepared, a long first sentence", prepared = true, pack, SPOKEN.drop(1))
    }

    /**
     * The voice against real streaming replies, fed as the chat and overlay feed it: how long
     * after the first word appears it is heard, cut into whole sentences (before 2026-10-29)
     * and with early clauses ([SpeechChunker]). Each prompt is asked once per way; the voice
     * plays out loud. The test chats are deleted.
     */
    @Test
    fun whileWriting() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval natural-voice", InstrumentationRegistry.getArguments().getString("eval") == "natural-voice")
        val pack = install()
        withContext(Dispatchers.Main) { container.speaker.release() }
        val voice = withContext(Dispatchers.Main) {
            NaturalVoice(chosen = { NaturalVoice.Chosen(voice, pack) }, rate = { 1f }, fallback = { report("  fell back: $it") }, onSpeakingChanged = {})
        }
        voice.prepare()
        delay(TALKING_MS)
        try {
            for (prompt in PROMPTS) {
                for (clauses in listOf(false, true)) streamed(voice, prompt, clauses)
            }
        } finally {
            withContext(Dispatchers.Main) { voice.release() }
        }
    }

    private suspend fun streamed(voice: NaturalVoice, prompt: String, clauses: Boolean) = coroutineScope {
        val chunker = SpeechChunker(clauses)
        var firstText = 0L
        var firstPiece = 0L
        val say = { piece: String ->
            if (firstPiece == 0L) firstPiece = SystemClock.elapsedRealtime()
            voice.speak(piece)
        }
        val watcher = launch(Dispatchers.Main) {
            container.chatSender.live.collect { live ->
                if (live?.owner !== this@NaturalVoiceEvalTest) return@collect
                if (firstText == 0L && live.text.isNotBlank()) firstText = SystemClock.elapsedRealtime()
                chunker.push(live.text).forEach(say)
            }
        }
        val created = CompletableDeferred<Long>()
        val finished = CompletableDeferred<String>()
        val sent = container.chatSender.send(
            chatId = null,
            text = prompt,
            attachment = null,
            owner = this@NaturalVoiceEvalTest,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) {
                    created.complete(chatId)
                }

                override fun onFinished(reply: String, failure: String?) {
                    finished.complete(reply)
                }
            },
        )
        check(sent) { "the sender was busy" }
        val chat = withTimeout(60_000) { created.await() }
        try {
            val reply = withTimeout(240_000) { finished.await() }
            val replyDone = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { chunker.finish(reply).forEach(say) }
            while (voice.startDelayMs < 0 && SystemClock.elapsedRealtime() - replyDone < 30_000) delay(10)
            val firstSound = firstPiece + voice.startDelayMs
            while (voice.isSpeaking && SystemClock.elapsedRealtime() - replyDone < 120_000) delay(20)
            val speechDone = SystemClock.elapsedRealtime()
            report(
                "${if (clauses) "clauses  " else "sentences"} | first word → sound ${firstSound - firstText} ms | " +
                    "heard ${if (firstSound < replyDone) "${replyDone - firstSound} ms before" else "${firstSound - replyDone} ms after"} the text finished " +
                    "(writing took ${replyDone - firstText} ms) | speech ends ${speechDone - replyDone} ms after | " +
                    reply.take(70).replace('\n', ' '),
            )
        } finally {
            watcher.cancel()
            container.chatRepository.deleteChat(chat)
            container.conversations.forget(chat)
        }
        delay(1_000)
    }

    /** Installs the voice through the app's own download unless it is already there. */
    private suspend fun install(): File {
        val installer = container.voicePack(voice.pack)
        installer.installed.value?.let {
            report("voice already installed at ${it.path}")
            return it
        }
        val started = SystemClock.elapsedRealtime()
        var unpackStarted = 0L
        // A finished download switches the assistant to it; the eval leaves the user's choice as it was.
        val chosen = container.settings.naturalVoice
        installer.download()
        val done = withTimeout(INSTALL_TIMEOUT_MS) {
            combine(installer.installed, installer.progress, installer.error) { installed, progress, error ->
                if (progress is VoicePackInstaller.Progress.Unpacking && unpackStarted == 0L) unpackStarted = SystemClock.elapsedRealtime()
                installed ?: error
            }.first { it != null }
        }
        container.settings.naturalVoice = chosen
        check(done is File) { "install failed: $done" }
        val end = SystemClock.elapsedRealtime()
        report("install: download ${(unpackStarted - started) / 1000} s, check and unpack ${(end - unpackStarted) / 1000} s, ${done.walk().sumOf { it.length() } / 1_000_000} MB on disk")
        return done
    }

    /** Says every reply once with [tts], each on its own as the overlay hands them over. */
    private fun speed(label: String, tts: OfflineTts, save: Boolean) {
        val pack = container.voicePack(voice.pack).installed.value ?: error("not installed")
        var made = 0.0
        var spent = 0L
        REPLIES.forEachIndexed { i, text ->
            val started = SystemClock.elapsedRealtime()
            val audio = voice.generate(tts, pack, text, 1f, null)
            val ms = SystemClock.elapsedRealtime() - started
            val seconds = audio.samples.size / audio.sampleRate.toDouble()
            if (save) audio.save(File(clips, "${voice.id}-%02d.wav".format(i)).path)
            // The first one after loading also pays one-off costs; it is what the first sentence meets.
            if (i == 0) report("$label: first reply after load %.1f s of speech in $ms ms".format(seconds))
            made += seconds
            spent += ms
        }
        report("$label: %.1f s of speech in %.1f s, %.2f× real time".format(made, spent / 1000.0, made * 1000 / spent))
    }

    /**
     * How much the chat model writing slows the voice down: the same piece timed with nothing
     * else running, and while the model writes a long reply (only timings that start and end
     * while it is still writing), at 2, 4 and 6 threads.
     */
    @Test
    fun contention() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval natural-voice", InstrumentationRegistry.getArguments().getString("eval") == "natural-voice")
        val pack = install()
        withContext(Dispatchers.Main) { container.speaker.release() }
        for (threads in listOf(2, 4, 6)) {
            val tts = OfflineTts(null, voice.config(pack, threads))
            voice.generate(tts, pack, "Warming up.", 1f, null)
            val idle = List(3) { timed(tts, pack) }
            val during = mutableListOf<Pair<Long, Double>>()
            whileTheModelWrites(STORY) {
                repeat(4) {
                    if (container.llmService.isGenerating) {
                        val sample = timed(tts, pack)
                        if (container.llmService.isGenerating) during += sample
                    }
                }
            }
            fun line(samples: List<Pair<Long, Double>>) = samples.joinToString { (ms, s) -> "$ms ms (%.2f×)".format(s * 1000 / ms) }
            report("${voice.id}, $threads threads: idle ${line(idle)}; while the model writes ${line(during).ifEmpty { "no sample" }}")
            tts.release()
        }
    }

    /** Makes [PIECE] once: milliseconds taken, seconds of speech. */
    private fun timed(tts: OfflineTts, pack: File): Pair<Long, Double> {
        val started = SystemClock.elapsedRealtime()
        val audio = voice.generate(tts, pack, PIECE, 1f, null)
        return (SystemClock.elapsedRealtime() - started) to audio.samples.size / audio.sampleRate.toDouble()
    }

    /** Runs [block] while the chat model writes the reply to [prompt] in a test chat. */
    private suspend fun whileTheModelWrites(prompt: String, block: () -> Unit) {
        val created = CompletableDeferred<Long>()
        val finished = CompletableDeferred<Unit>()
        val sent = container.chatSender.send(
            chatId = null,
            text = prompt,
            attachment = null,
            owner = this,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) {
                    created.complete(chatId)
                }

                override fun onFinished(reply: String, failure: String?) {
                    finished.complete(Unit)
                }
            },
        )
        check(sent) { "the sender was busy" }
        val chat = withTimeout(60_000) { created.await() }
        try {
            withTimeout(60_000) { container.chatSender.live.first { it != null && it.text.length > 20 } }
            val stillWriting = { container.llmService.isGenerating }
            block()
            report("  the chat model was ${if (stillWriting()) "still writing" else "done"} at the end")
            withTimeout(240_000) { finished.await() }
        } finally {
            container.chatRepository.deleteChat(chat)
            container.conversations.forget(chat)
        }
    }

    /** Plays [sentences] through [NaturalVoice]: how long until the first sound, and until the last ends. */
    private suspend fun heard(label: String, prepared: Boolean, pack: File, sentences: List<String>) {
        val voice = withContext(Dispatchers.Main) {
            NaturalVoice(chosen = { NaturalVoice.Chosen(voice, pack) }, rate = { 1f }, fallback = { report("  fell back: $it") }, onSpeakingChanged = {})
        }
        try {
            if (prepared) {
                voice.prepare()
                delay(TALKING_MS)
            }
            val started = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { sentences.forEach(voice::speak) }
            while (voice.startDelayMs < 0 && SystemClock.elapsedRealtime() - started < 30_000) delay(10)
            while (voice.isSpeaking && SystemClock.elapsedRealtime() - started < 90_000) delay(20)
            val total = SystemClock.elapsedRealtime() - started
            report("$label: ${voice.startDelayMs} ms; all ${sentences.size} pieces done after $total ms")
        } finally {
            withContext(Dispatchers.Main) { voice.release() }
            delay(500)
        }
    }

    private fun pssMb(): Long = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss / 1024L

    /** The highest PSS seen until [stop]. */
    private inner class PeakPss {
        @Volatile private var running = true
        @Volatile private var peak = pssMb()
        private val sampler = thread(name = "pss-sampler") {
            while (running) {
                peak = maxOf(peak, pssMb())
                Thread.sleep(200)
            }
        }

        fun stop(): Long {
            running = false
            sampler.join()
            return peak
        }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        results.appendText("$line\n")
    }

    private companion object {
        const val TAG = "NaturalVoiceEval"
        const val INSTALL_TIMEOUT_MS = 40 * 60_000L
        const val TALKING_MS = 3_000L

        val REPLIES = listOf(
            "Sure! I've set a reminder for tomorrow at 8:30 in the morning to call Dr. Mehta about your blood test results. " +
                "By the way, it's supposed to rain around 4 PM, so you might want to carry an umbrella. Is there anything else you'd like me to do?",
            "Done! Your alarm is set for 6:45 AM tomorrow.",
            "You have three events today: a team sync at 10, lunch with Priya at 1:30, and the dentist at 5 PM.",
            "The total comes to 2,450 rupees, which is about 29 dollars.",
            "I couldn't find any photos from Goa last December. Do you want me to search for beach photos instead?",
            "Here's a quick summary: the meeting moved to Thursday, and Rahul will send the slides tonight.",
            "It's 31 degrees in Bengaluru right now, with light rain expected after 6.",
            "Sorry, I can't send that message without your permission. Should I open WhatsApp for you?",
            "Okay, I'll remember that your sister's birthday is on the 14th of March.",
        )

        /** A typical first clause, for [contention]. */
        const val PIECE = "Try to maintain a consistent sleep schedule, even on weekends."

        const val KOCHI = "Tell me about the history of Kochi in about 200 words."
        const val STORY = "Write a 400-word story about a fisherman in Kochi."

        /** Asked of the chat model by [whileWriting]: a greeting, a fact, and a paragraph. */
        val PROMPTS = listOf(
            "Hi! How are you today?",
            "What's the capital of Kerala, and what is it known for? Answer in two sentences.",
            "Give me three quick tips for falling asleep faster, in a short paragraph.",
        )

        /** As the overlay hands the first reply over (SpeechChunker): sentence by sentence. */
        val SPOKEN = listOf(
            "Sure!",
            "I've set a reminder for tomorrow at 8:30 in the morning to call Dr. Mehta about your blood test results.",
            "By the way, it's supposed to rain around 4 PM, so you might want to carry an umbrella.",
            "Is there anything else you'd like me to do?",
        )
    }
}
