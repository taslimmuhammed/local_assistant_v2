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
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The on-device routing eval: ~30 requests through the real model with the real instructions and
 * tool declarations, scoring whether each routes to the right tool — or, for the negatives, to
 * none. Runs at temperature 1.0 and 0.7, so the sampler choice rests on numbers.
 *
 * Opt-in, since it loads the model:
 *
 *     adb shell am instrument -w -e eval true \
 *         -e class com.local.assistant.llm.RoutingEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag RoutingEval) and `Android/data/<app>/files/routing-eval.txt`.
 */
@RunWith(AndroidJUnit4::class)
class RoutingEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "routing-eval.txt").apply { writeText("") } }

    /** [accept] holds the acceptable first tool; null in it means "no tool call" is right. */
    private data class Case(val prompt: String, val accept: Set<String?>, val check: ((Map<String, Any?>) -> Boolean)? = null)

    private val cases = listOf(
        Case("remind me to call the CA tomorrow at 11", setOf("add_task")) { args -> args["when"].toString().let { "tomorrow" in it && "11" in it } },
        Case("kal subah 11 baje CA ko call karna yaad dilana", setOf("add_task")),
        Case("don't let me forget to pay the electricity bill on Friday", setOf("add_task")),
        Case("I need to renew my passport next month", setOf("add_task")),
        Case("remind me every Monday at 9 to submit the timesheet", setOf("add_task")) { args -> args["repeat"] != null },
        Case("parso shaam 6 baje mummy ko call karna hai, yaad dilana", setOf("add_task")),
        Case("move the CA call to 3", setOf("update_task")),
        Case("I called the CA, mark it done", setOf("update_task")),
        Case("cancel the passport reminder", setOf("update_task")),
        Case("my dentist is Dr. Rao", setOf("save_fact")) { args -> args["value"].toString().contains("Rao") },
        Case("reply in short answers from now on", setOf("save_fact")) { args -> args["core"] == true || args["core"] == "true" },
        Case("my wife's birthday is 12 May", setOf("save_fact", "add_event")),
        Case("I live in Bengaluru", setOf("save_fact")),
        Case("my CA is Mr. Iyer", setOf("save_fact")),
        Case("I'm allergic to peanuts", setOf("save_fact")),
        Case("mera dentist Dr. Rao hai", setOf("save_fact")),
        Case("dentist appointment on Thursday at 5pm", setOf("add_event")),
        Case("team meeting tomorrow from 10 to 11", setOf("add_event")),
        Case("flight to Kochi on 25 Oct at 6am", setOf("add_event")),
        Case("what's coming up this week?", setOf("get_upcoming")),
        Case("set an alarm for 6am", setOf("set_alarm")),
        Case("wake me up at 5:30 tomorrow", setOf("set_alarm")),
        Case("subah 6 baje ka alarm laga do", setOf("set_alarm")),
        Case("set an alarm for 7 on weekdays", setOf("set_alarm")) { args -> args["repeat"] != null },
        Case("what did I tell you about my CA?", setOf("search_memory")),
        Case("forget my dentist", setOf("forget")),
        Case("what's my dentist's name?", setOf(null, "search_memory")),
        Case("I'm tired today", setOf(null)),
        Case("what's the capital of Kerala?", setOf(null)),
        Case("if I had a dentist, what should I ask them?", setOf(null)),
        Case("my friend thinks Python is better than Java", setOf(null)),
        Case("oh great, another Monday", setOf(null)),
        Case("write a haiku about rain", setOf(null)),
        Case("should I call the CA tomorrow?", setOf(null)),
    )

    @Before
    fun onlyWhenAsked() {
        assumeTrue("Loads the real model; run with -e eval true", InstrumentationRegistry.getArguments().getString("eval") == "true")
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun routing() {
        val modelPath = requireNotNull(SettingsStore(context).modelPath) { "No model installed" }
        ExperimentalFlags.enableSpeculativeDecoding = true
        ExperimentalFlags.enableBenchmark = true
        Engine(
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), maxNumTokens = 8_192, cacheDir = context.cacheDir.absolutePath),
        ).apply { initialize() }.use { engine ->
            measureDeclarations(engine)
            // "-e temps 1.0" and "-e only <words>" narrow a run to check one fix quickly.
            val temps = InstrumentationRegistry.getArguments().getString("temps")
                ?.split(',')?.map { it.trim().toDouble() } ?: listOf(1.0, 0.7)
            for (temperature in temps) evaluate(engine, temperature)
        }
    }

    /** What the tool declarations really cost: the same prefix with and without them. */
    private fun measureDeclarations(engine: Engine) {
        val system = Instructions.render(PERSONA, withTools = true)
        fun count(withTools: Boolean): Int = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                tools = if (withTools) declarations() else emptyList(),
                automaticToolCalling = false,
                prefillPrefaceOnInit = true,
            ),
        ).use { it.getTokenCount() }
        val without = count(false)
        val with = count(true)
        report("prefix.tokens.instructions", without)
        report("prefix.tokens.toolDeclarations", with - without)
    }

    private fun evaluate(engine: Engine, temperature: Double) {
        val system = Instructions.render(PERSONA, withTools = true)
        val now = "[Now: ${DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).format(ZonedDateTime.now())}]"
        var right = 0
        var positivesRight = 0
        var negativesRight = 0
        val latencies = mutableListOf<Long>()

        val only = InstrumentationRegistry.getArguments().getString("only")
        // At temperature 1.0 one sample per prompt is noisy; "-e samples 3" asks each three times.
        val samples = InstrumentationRegistry.getArguments().getString("samples")?.toIntOrNull() ?: 1
        val selected = (if (only == null) cases else cases.filter { only in it.prompt }).flatMap { case -> List(samples) { case } }
        for (case in selected) {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    tools = declarations(),
                    automaticToolCalling = false,
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = temperature),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 160,
                ),
            )
            val started = SystemClock.elapsedRealtime()
            var firstAt = 0L
            // The whole response, not its first chunk: a model may say a few words before it calls.
            val (call, text) = runCatching {
                runBlocking {
                    var firstCall: com.google.ai.edge.litertlm.ToolCall? = null
                    val said = StringBuilder()
                    conversation.sendMessageAsync("$now\n${case.prompt}")
                        .onEach { if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime() }
                        .collect { message ->
                            if (firstCall == null) firstCall = message.toolCalls.firstOrNull()
                            if (message.toolCalls.isEmpty()) said.append(message.toString())
                        }
                    firstCall to said.toString()
                }
            }.getOrElse { null to "failed: ${it.message}" }
            conversation.close()
            latencies += (if (firstAt > 0) firstAt else SystemClock.elapsedRealtime()) - started

            val routedTo = call?.name
            val argsOk = call == null || case.check == null || case.check.invoke(call.arguments)
            val correct = routedTo in case.accept && argsOk
            if (correct) right++
            if (null in case.accept && case.accept.size == 1) { if (correct) negativesRight++ } else if (correct) positivesRight++
            report(
                "t$temperature ${if (correct) "OK  " else "MISS"} ${case.prompt}",
                routedTo?.let { "$it(${call.arguments})" } ?: "no call: ${text.take(60).replace('\n', ' ')}",
            )
        }

        val negatives = selected.count { null in it.accept && it.accept.size == 1 }
        report("t$temperature.accuracy", "$right/${selected.size}")
        report("t$temperature.positives", "$positivesRight/${selected.size - negatives}")
        report("t$temperature.negatives", "$negativesRight/$negatives")
        report("t$temperature.firstEventMs.median", latencies.sorted()[latencies.size / 2])
        report("t$temperature.firstEventMs.p90", latencies.sorted()[(latencies.size * 9) / 10])
    }

    private fun declarations() = ToolCatalog.declarations.map { json ->
        tool(object : OpenApiTool {
            override fun getToolDescriptionJsonString() = json
            override fun execute(paramsJsonString: String): String = error("manual")
        })
    }

    private fun report(key: String, value: Any) {
        Log.i(TAG, "$key = $value")
        out.appendText("$key = $value\n")
    }

    private companion object {
        const val TAG = "RoutingEval"
        const val PERSONA = "You are a helpful assistant running entirely on the user's device."
    }
}
