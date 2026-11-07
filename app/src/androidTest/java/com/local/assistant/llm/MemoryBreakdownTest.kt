package com.local.assistant.llm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.wake.WakeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * What each model and feature adds to the app's memory, loaded one after another on the phone:
 * the chat model, a reply, its image and audio encoders, memory search, photo search, the
 * natural voice and the wake word. Each step reports the app's PSS (swapped-out pages included,
 * as `dumpsys meminfo` counts them) and its graphics memory, and what the step added.
 *
 *     adb shell am instrument -w -e eval ram -e class com.local.assistant.llm.MemoryBreakdownTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results also go to files/ram-breakdown.txt in external storage.
 */
@RunWith(AndroidJUnit4::class)
class MemoryBreakdownTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val lines = ArrayList<String>()

    private data class Snap(val pssMb: Long, val graphicsMb: Long, val nativeMb: Long)

    private var last: Snap? = null

    private fun snap(): Snap {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return Snap(
            pssMb = info.totalPss / 1024L,
            graphicsMb = info.getMemoryStat("summary.graphics").toLong() / 1024L,
            nativeMb = info.getMemoryStat("summary.native-heap").toLong() / 1024L,
        )
    }

    private suspend fun step(label: String, peakMb: Long? = null) {
        settle()
        val now = snap()
        val before = last
        val added = before?.let { " → +${now.pssMb - it.pssMb} MB (graphics +${now.graphicsMb - it.graphicsMb})" }.orEmpty()
        val peak = peakMb?.let { ", peak ${it} MB" }.orEmpty()
        report("%-52s PSS %5d MB, graphics %4d MB, native %4d MB%s%s".format(label, now.pssMb, now.graphicsMb, now.nativeMb, added, peak))
        last = now
    }

    @Test
    fun breakdown() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval ram", InstrumentationRegistry.getArguments().getString("eval") == "ram")
        assumeTrue("no chat model", container.modelManager.installed.value != null)
        try {
            withContext(Dispatchers.Main) { container.speaker.release() }
            container.photoModel.unload()
            container.embedder.unload()
            container.llmService.unload()
            step("app alone (UI, databases, code), no model")

            var started = SystemClock.elapsedRealtime()
            check(container.llmBackend.ensureReady()) { "the chat model didn't load" }
            step("chat model loaded (${SystemClock.elapsedRealtime() - started} ms)")

            val system = Instructions.render(SettingsStore.DEFAULT_SYSTEM_PROMPT, withTools = true, webSearch = true, photoSearch = true)
            var peak = PeakPss()
            started = SystemClock.elapsedRealtime()
            ask(system) { send("What's a good name for a cat? One line.") }
            val replyMs = SystemClock.elapsedRealtime() - started
            step("+ a chat with instructions and tools, one reply ($replyMs ms)", peak.stop())

            val image = File(context.cacheDir, "ram-picture.jpg").apply { writeBytes(picture()) }
            peak = PeakPss()
            ask(system) { send("What is in this picture? One line.", PromptAttachment(image.absolutePath, AttachmentKind.IMAGE)) }
            step("+ the chat model reads an image (vision encoder)", peak.stop())

            val audio = File(context.cacheDir, "ram-tone.wav").apply { writeBytes(tone()) }
            peak = PeakPss()
            ask(system) { send("Describe this sound in one line.", PromptAttachment(audio.absolutePath, AttachmentKind.AUDIO)) }
            step("+ the chat model hears audio (audio encoder)", peak.stop())

            check(container.embedder.prepare()) { "memory search's model didn't load" }
            step("+ memory search (${container.embedder.modelId})")

            check(container.photoModel.embedQuery("photos from the beach") != null)
            step("+ a photo search query (same engine)")

            peak = PeakPss()
            check(container.photoModel.embedImage(picture()) != null)
            step("+ indexing a photo (its vision encoder)", peak.stop())
            container.photoModel.release()
            step("  photos done: vision encoder given back")

            if (withContext(Dispatchers.Main) { container.speaker.naturalVoiceInUse() }) {
                peak = PeakPss()
                withContext(Dispatchers.Main) { container.speaker.speak("Good afternoon. Your next reminder is at five, and it looks like rain later today.") }
                delay(500)
                val until = SystemClock.elapsedRealtime() + 30_000
                while (container.speaker.speaking.value && SystemClock.elapsedRealtime() < until) delay(100)
                step("+ natural voice (Kokoro) after speaking", peak.stop())
                withContext(Dispatchers.Main) { container.speaker.release() }
                step("  natural voice released")
            } else {
                report("natural voice not chosen: skipped")
            }

            val wake = WakeDetector(context.assets)
            step("+ wake word detector (Hey Luna)")
            wake.close()
            step("  wake word detector closed")
        } finally {
            context.getExternalFilesDir(null)?.let { File(it, "ram-breakdown.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    /** A fresh chat, one message, the whole reply. */
    private suspend fun ask(system: String, send: ChatSession.() -> kotlinx.coroutines.flow.Flow<GenEvent>) {
        container.llmBackend.openChat(
            ChatSpec(
                systemPrefix = system,
                history = emptyList(),
                toolDeclarations = ToolCatalog.declarations(web = true, photos = true),
                sampling = Sampling.CHAT,
                maxOutputTokens = 60,
            ),
        ).use { session ->
            val said = StringBuilder()
            session.send().collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> said.append(event.text)
                    is GenEvent.Error -> said.append("failed: ${event.cause.message}")
                    else -> Unit
                }
            }
            report("  replied: ${said.toString().replace('\n', ' ').take(80)}")
        }
    }

    private suspend fun settle() {
        repeat(2) {
            System.gc()
            Runtime.getRuntime().gc()
            delay(1_500)
        }
    }

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    /** Samples PSS every 200 ms until stopped; the highest seen. */
    private inner class PeakPss {
        @Volatile private var running = true
        @Volatile private var peak = snap().pssMb
        private val sampler = thread(name = "pss-sampler") {
            while (running) {
                peak = maxOf(peak, snap().pssMb)
                Thread.sleep(200)
            }
        }

        fun stop(): Long {
            running = false
            sampler.join()
            return peak
        }
    }

    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(250, 200, 120))
            drawCircle(256f, 256f, 140f, Paint().apply { color = Color.rgb(200, 40, 40) })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Two seconds of a 440 Hz tone, 16 kHz mono 16-bit WAV. */
    private fun tone(): ByteArray {
        val rate = 16_000
        val samples = rate * 2
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) buffer.putShort((sin(2 * PI * 440 * i / rate) * 8_000).toInt().toShort())
        return buffer.array()
    }

    private companion object {
        const val TAG = "MemoryBreakdown"
    }
}
