package com.local.assistant.llm

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a smaller context window buys on the phone: the model loaded at 8K and at 4K, in turns,
 * through the app's own loader. For each, the load time and the phone-wide allocation stalls
 * during it, the app's memory, and a fresh chat with the instructions and tools: how long until
 * the first word, how fast the reply is written, and a second message in the same chat.
 *
 *     adb shell am instrument -w -e eval context -e class com.local.assistant.llm.ContextWindowEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results also go to files/context-window.txt in external storage. The window the user chose is
 * put back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ContextWindowEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val lines = ArrayList<String>()

    @Test
    fun compare() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval context", InstrumentationRegistry.getArguments().getString("eval") == "context")
        assumeTrue("no chat model", container.modelManager.installed.value != null)
        val chosen = container.settings.contextTokens
        try {
            container.photoModel.unload()
            container.embedder.unload()
            for (tokens in listOf(8_192, 4_096, 8_192, 4_096)) measure(tokens)
        } finally {
            container.settings.contextTokens = chosen
            container.llmService.unload()
            container.llmBackend.ensureReady()
            context.getExternalFilesDir(null)?.let { File(it, "context-window.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    private suspend fun measure(tokens: Int) {
        container.settings.contextTokens = tokens
        container.llmService.unload()
        settle()
        val before = pssMb()
        val stallsBefore = stalls()
        val started = SystemClock.elapsedRealtime()
        check(container.llmBackend.ensureReady()) { "the model didn't load at $tokens" }
        val loadMs = SystemClock.elapsedRealtime() - started
        val stallsDuring = stallsBefore?.let { b -> stalls()?.minus(b) }
        check(container.llmService.activeContextTokens.value == tokens) { "running at ${container.llmService.activeContextTokens.value}, not $tokens" }
        settle()
        val loaded = pssMb()

        val system = Instructions.render(SettingsStore.DEFAULT_SYSTEM_PROMPT, withTools = true, webSearch = true, photoSearch = true)
        var first: Turn
        var second: Turn
        container.llmBackend.openChat(
            ChatSpec(
                systemPrefix = system,
                history = emptyList(),
                toolDeclarations = ToolCatalog.declarations(web = true, photos = true),
                sampling = Sampling.CHAT,
                maxOutputTokens = 400,
            ),
        ).use { session ->
            first = turn(session, "Explain in about 150 words how a rainbow forms.")
            second = turn(session, "Now say the same thing in one short sentence.")
        }
        settle()
        val chatted = pssMb()
        report(
            "${tokens / 1024}K | load $loadMs ms, stalls ${stallsDuring ?: "n/a"} | PSS ${before.first} → ${loaded.first} (+${loaded.first - before.first}) → ${chatted.first} MB " +
                "(chat +${chatted.first - loaded.first}), graphics ${loaded.second} → ${chatted.second} MB",
        )
        report("    fresh chat: $first")
        report("    next message: $second")
    }

    private data class Turn(val firstWordMs: Long, val totalMs: Long, val stats: GenStats?) {
        override fun toString() = "first word $firstWordMs ms, done $totalMs ms" + (stats?.let {
            ", prefill ${it.prefillTokens} tok, ttft ${it.timeToFirstTokenMs} ms, ${it.decodeTokens} tok at %.1f tok/s".format(it.tokensPerSecond)
        } ?: "")
    }

    private suspend fun turn(session: ChatSession, message: String): Turn {
        val started = SystemClock.elapsedRealtime()
        var firstWord = 0L
        var stats: GenStats? = null
        session.send(message).collect { event ->
            when (event) {
                is GenEvent.TextDelta -> if (firstWord == 0L && event.text.isNotBlank()) firstWord = SystemClock.elapsedRealtime() - started
                is GenEvent.Done -> stats = event.stats
                is GenEvent.Error -> throw event.cause
                else -> Unit
            }
        }
        return Turn(firstWord, SystemClock.elapsedRealtime() - started, stats)
    }

    /** The app's PSS and graphics memory, in MB. */
    private fun pssMb(): Pair<Long, Long> {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return info.totalPss / 1024L to info.getMemoryStat("summary.graphics").toLong() / 1024L
    }

    /** Phone-wide allocation stalls so far (direct reclaim), when the system lets the app read them. */
    private fun stalls(): Long? = runCatching {
        File("/proc/vmstat").readLines().filter { it.startsWith("allocstall") }.sumOf { it.substringAfter(' ').trim().toLong() }
    }.getOrNull()

    private suspend fun settle() {
        repeat(2) {
            System.gc()
            delay(1_500)
        }
    }

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "ContextWindowEval"
    }
}
