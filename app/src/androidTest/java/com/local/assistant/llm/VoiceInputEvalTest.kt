package com.local.assistant.llm

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.prompt.PromptAssembler
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.voice.Transcriber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Voice messages two ways (the user, 4 Nov 2026: "when it was directly read by the model the
 * accuracy was better, check if it increases the latency"): the recording itself, as the app sent
 * voice messages until 1 Nov 2026, or the words the phone's recogniser writes down ([Transcriber]),
 * as it sends them now (`ContextWindow.sentAttachment`).
 *
 *     adb shell am instrument -w -e eval voiceinput [-e part known|yours] [-e only <id>|<id>] \
 *         -e class com.local.assistant.llm.VoiceInputEvalTest com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Known: requests with a right answer, `voice-input/known/<id>.wav` under the app's external files
 * (`say -v Rishi`, 16 kHz mono, silence either side). Yours: `voice-input/yours.tsv`, one recording
 * of the user's per line (its path, then what the recogniser wrote at the time). There's no right
 * answer for those, so where the two ways part is listed for the user to judge.
 *
 * Time is from the end of the audio: the words come once the recogniser is done with a recording
 * fed at the pace it was made; the recording goes as soon as it ends. Then to the model's first
 * event, and to its last. Through the app's own engine (see HonestyEvalTest). Results: logcat
 * (tag VoiceInputEval) and `Android/data/<app>/files/voice-input-eval.txt`.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "voice-input-eval.txt").apply { writeText("") } }

    private class Case(val id: String, val said: String, val accept: Set<String?>, val check: ((Map<String, Any?>) -> Boolean)? = null)

    private val known = listOf(
        Case("umma-whatsapp", "Send this to Umma on WhatsApp", setOf(ToolCatalog.SEND_MESSAGE)) { it.has("to", "umma") },
        Case("umma-call", "Call Umma", setOf(ToolCatalog.CALL)) { it.has("who", "umma") },
        Case("shiyas-call", "Call Shiyas", setOf(ToolCatalog.CALL)) { it.has("who", "shiyas") },
        Case("anjali-late", "Message Anjali on WhatsApp that I'll be late tonight", setOf(ToolCatalog.SEND_MESSAGE)) {
            it.has("to", "anjali") && it.has("text", "late")
        },
        Case("priya-email", "Email Priya that the meeting has moved to Thursday", setOf(ToolCatalog.SEND_MESSAGE)) {
            it.has("to", "priya") && it.has("text", "thursday")
        },
        Case("alarm-630", "Set an alarm for 6:30 tomorrow morning", setOf(ToolCatalog.SET_ALARM)) { it.has("when", "6:30") || it.has("when", "6.30") },
        Case("timer-10", "Set a timer for ten minutes", setOf(ToolCatalog.SET_TIMER)) { it.has("duration", "10") || it.has("duration", "ten") },
        Case("bill-friday", "Remind me to pay the electricity bill on Friday at 9 a.m.", setOf(ToolCatalog.ADD_TASK)) {
            it.has("title", "electricity") && it.has("when", "friday")
        },
        Case("flashlight", "Turn on the flashlight", setOf(ToolCatalog.PHONE_SETTING)) { it.has("setting", "flash") || it.has("setting", "torch") },
        Case("silent", "Put my phone on silent", setOf(ToolCatalog.PHONE_SETTING)) { it.has("value", "silent") || it.has("setting", "silent") },
        Case("percent", "What is eighteen percent of two thousand four hundred and fifty?", setOf(ToolCatalog.CALCULATE)) { it.has("expression", "2450") },
        Case("youtube", "Open YouTube and play Arijit Singh songs", setOf(ToolCatalog.OPEN_APP)) { it.has("app", "youtube") && it.has("query", "arijit") },
        Case("munnar-photos", "Show me my photos from Munnar", setOf(ToolCatalog.FIND_PHOTOS)) { it.has("query", "munnar") || it.has("place", "munnar") },
        Case("car-service", "Remember that my car service is due in March", setOf(ToolCatalog.SAVE_FACT)) { it.values.any { v -> "march" in v.toString().lowercase() } },
        Case("capital", "What is the capital of Australia?", setOf(null)),
        Case("joke", "Tell me a short joke", setOf(null)),
    )

    @Test
    fun onlyWhenAsked() {
        assumeTrue("Loads the real model; run with -e eval voiceinput", InstrumentationRegistry.getArguments().getString("eval") == "voiceinput")
    }

    @Test
    fun voiceInput() = runBlocking<Unit> {
        assumeTrue("Loads the real model; run with -e eval voiceinput", InstrumentationRegistry.getArguments().getString("eval") == "voiceinput")
        val container = context.appContainer
        check(container.transcriber.available) { "No on-device recogniser" }
        container.conversations.withModelFree {
            check(container.llmBackend.ensureReady()) { "No model loaded" }
            evaluate(container.llmBackend, container.transcriber)
        }
    }

    private suspend fun evaluate(backend: LlmBackend, transcriber: Transcriber) {
        val args = InstrumentationRegistry.getArguments()
        val part = args.getString("part")
        val only = args.getString("only")?.split('|')
        val root = File(context.getExternalFilesDir(null), "voice-input")
        val system = Instructions.render(SettingsStore.DEFAULT_SYSTEM_PROMPT, withTools = true, webSearch = true, photoSearch = true)
        val now = "[Now: ${DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).format(ZonedDateTime.now())}]"
        // "-e ways words,audio,both,hint": words alone (as until 4 Nov), the recording alone (as
        // before 1 Nov), the recording with the words as if typed, or with them marked as the
        // phone's hearing of it (PromptAssembler.heard: as the app sends one now). The words must
        // be ready first wherever they go.
        val ways = (args.getString("ways") ?: "words,audio").split(',').map { it.trim() }
        val times = ways.associateWith { Times() }

        suspend fun tries(clip: File): Map<String, Try> {
            val (words, wordsAfter) = VoiceClips.transcribe(transcriber, clip)
            val heard = words.orEmpty()
            val recording = PromptAttachment(clip.absolutePath, AttachmentKind.AUDIO)
            return ways.associateWith { way ->
                val attempt = when (way) {
                    "words" -> ask(backend, system) { send("$now\n$heard") }.copy(heard = words, before = wordsAfter)
                    "audio" -> ask(backend, system) { send(now, recording) }
                    "both" -> ask(backend, system) { send("$now\n$heard", recording) }.copy(heard = words, before = wordsAfter)
                    "hint" -> ask(backend, system) { send("$now\n${PromptAssembler.heard(heard)}", recording) }.copy(heard = words, before = wordsAfter)
                    else -> error("No way '$way'")
                }
                times.getValue(way).add(attempt)
                attempt
            }
        }

        if (part == null || part == "known") {
            val right = ways.associateWith { 0 }.toMutableMap()
            var ran = 0
            for (case in known) {
                if (only != null && case.id !in only) continue
                val clip = File(root, "known/${case.id}.wav").takeIf { it.isFile } ?: continue
                ran++
                for ((way, attempt) in tries(clip)) {
                    val ok = attempt.tool in case.accept && (attempt.args == null || case.check == null || case.check.invoke(attempt.args))
                    if (ok) right[way] = right.getValue(way) + 1
                    report("known ${case.id} | $way ${if (ok) "OK  " else "MISS"} | ${attempt.line()}")
                }
            }
            for (way in ways) report("known.right.$way = ${right[way]}/$ran")
        }

        if (part == null || part == "yours") {
            val list = File(root, "yours.tsv").takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }
            var same = 0
            var ran = 0
            for ((i, line) in list.withIndex()) {
                if (only != null && "y$i" !in only) continue
                val clip = File(line.substringBefore('\t')).takeIf { it.isFile } ?: continue
                ran++
                val attempts = tries(clip)
                val agree = attempts.values.map { it.tool to it.args?.let(::essence) }.distinct().size == 1
                if (agree) same++
                report("yours y$i | ${if (agree) "same" else "DIFFER"} | then: ${line.substringAfter('\t', "")}")
                for ((way, attempt) in attempts) report("    $way | ${attempt.line()}")
            }
            report("yours.same = $same/$ran")
        }

        for ((way, t) in times) {
            report("time.$way.wordsReadyAfterAudioMs.median = ${t.before.median()}")
            report("time.$way.firstEventAfterAudioMs.median = ${t.first.median()} p90 = ${t.first.p90()}")
            report("time.$way.doneAfterAudioMs.median = ${t.done.median()} p90 = ${t.done.p90()}")
        }
    }

    /** One try: what the model did, and when, in ms after the audio ended. */
    private data class Try(
        val tool: String?,
        val args: Map<String, Any?>?,
        val text: String,
        val firstMs: Long,
        val doneMs: Long,
        val heard: String? = null,
        val before: Long = 0,
    ) {
        fun line() = (heard?.let { "heard \"$it\" after $before ms | " } ?: "") +
            "first ${before + firstMs} ms, done ${before + doneMs} ms | " +
            (tool?.let { "$it($args)" } ?: "no call") + (text.takeIf { it.isNotBlank() }?.let { ": " + it.take(160).replace('\n', ' ') } ?: "")
    }

    private class Times {
        val before = mutableListOf<Long>()
        val first = mutableListOf<Long>()
        val done = mutableListOf<Long>()
        fun add(t: Try) {
            before += t.before
            first += t.before + t.firstMs
            done += t.before + t.doneMs
        }
    }

    private fun List<Long>.median() = if (isEmpty()) 0 else sorted()[size / 2]
    private fun List<Long>.p90() = if (isEmpty()) 0 else sorted()[(size * 9) / 10]

    /** A fresh chat with the app's instructions and tools, one message, the whole response. */
    private suspend fun ask(backend: LlmBackend, system: String, send: ChatSession.() -> Flow<GenEvent>): Try =
        backend.openChat(
            ChatSpec(
                systemPrefix = system,
                history = emptyList(),
                toolDeclarations = ToolCatalog.declarations(web = true, photos = true),
                sampling = Sampling.CHAT,
                maxOutputTokens = 200,
            ),
        ).use { session ->
            val started = SystemClock.elapsedRealtime()
            var first = 0L
            var call: ToolCall? = null
            val said = StringBuilder()
            session.send().collect { event ->
                if (first == 0L) first = SystemClock.elapsedRealtime() - started
                when (event) {
                    is GenEvent.TextDelta -> said.append(event.text)
                    is GenEvent.ToolCalls -> if (call == null) call = event.calls.firstOrNull()
                    is GenEvent.Error -> said.append("failed: ${event.cause.message}")
                    is GenEvent.Done -> Unit
                }
            }
            Try(call?.name, call?.arguments, said.toString(), first, SystemClock.elapsedRealtime() - started)
        }

    /** What must match for two calls to mean the same: the arguments, lowercased, doubled letters as one. */
    private fun essence(args: Map<String, Any?>) = args.mapValues { (_, v) -> v.toString().lowercase(Locale.ROOT).replace(Regex("(\\p{L})\\1+"), "$1").trim() }

    private fun Map<String, Any?>.has(key: String, part: String) = this[key]?.toString()?.lowercase(Locale.ROOT)?.contains(part) == true

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "VoiceInputEval"
    }
}
