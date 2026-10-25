package com.local.assistant.llm

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How fast replies are written, measured with a clock rather than taken from the runtime's own
 * `BenchmarkInfo`, so that two runtime versions can be compared even if they count differently.
 * Uses only API that exists in both LiteRT-LM 0.17.1 and 0.18.1, so the same file runs against
 * either build. GPU, as the app loads it, with MTP on and off; the same prompt and seed each time.
 *
 *     adb shell am instrument -w -e speed true -e class com.local.assistant.llm.DecodeSpeedProbeTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Appends to files/decode-speed.txt in external storage.
 */
@RunWith(AndroidJUnit4::class)
class DecodeSpeedProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun report(line: String) {
        Log.i(TAG, line)
        context.getExternalFilesDir(null)?.let { File(it, "decode-speed.txt").appendText("$line\n") }
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun decodeSpeed() {
        assumeTrue("Loads the real model; run with -e speed true", InstrumentationRegistry.getArguments().getString("speed") == "true")
        val modelPath = requireNotNull(SettingsStore(context).modelPath) { "No model installed" }
        report("--- ${java.util.Date()}")
        for (mtp in listOf(true, false)) {
            ExperimentalFlags.enableSpeculativeDecoding = mtp
            ExperimentalFlags.enableBenchmark = true
            Engine(EngineConfig(modelPath = modelPath, backend = Backend.GPU(), maxNumTokens = 8_192, cacheDir = context.cacheDir.absolutePath))
                .apply { initialize() }
                .use { engine ->
                    repeat(ROUNDS) { round ->
                        val conversation = engine.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0, seed = 0),
                                maxOutputToken = 400,
                            ),
                        )
                        val sent = SystemClock.elapsedRealtime()
                        var first = 0L
                        val reply = StringBuilder()
                        runBlocking {
                            conversation.sendMessageAsync(Message.user(Contents.of(PROMPT))).collect {
                                if (first == 0L) first = SystemClock.elapsedRealtime()
                                reply.append(it.toString())
                            }
                        }
                        val done = SystemClock.elapsedRealtime()
                        val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                        conversation.close()
                        val writing = (done - first).coerceAtLeast(1) / 1000.0
                        report(
                            "mtp=$mtp round=$round: ${reply.length} chars in %.2f s after %d ms to first text = %.0f chars/s; ".format(writing, first - sent, reply.length / writing) +
                                "runtime: ${info?.lastDecodeTokenCount} tokens, %.1f tok/s; by clock %.1f tok/s".format(
                                    info?.lastDecodeTokensPerSecond ?: 0.0,
                                    (info?.lastDecodeTokenCount ?: 0) / writing,
                                ),
                        )
                    }
                }
        }
    }

    private companion object {
        const val TAG = "DecodeSpeed"
        const val ROUNDS = 3
        const val PROMPT = "Write the numbers from one to eighty in words, separated by commas, and nothing else."
    }
}
