package com.local.assistant.llm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.prompt.PromptAssembler
import com.local.assistant.memory.tools.ActionClaims
import com.local.assistant.memory.tools.Calculator
import com.local.assistant.memory.tools.DeviceTools
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolLoop
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Several things asked for in one message ("set an alarm for 6:30 and a timer for 10 minutes"):
 * does the real model call every tool they need, over as many rounds as it takes? The tools are
 * answered here with made-up successes, so nothing is set or sent on the phone.
 *
 * The screen case sends a drawn page as the screen the user was looking at, the way the
 * assistant overlay could pass on the screenshot Android gives the digital assistant.
 *
 * Opt-in, since it loads the model:
 *
 *     adb shell am instrument -w -e eval multistep \
 *         -e class com.local.assistant.llm.MultiStepEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag MultiStepEval) and `Android/data/<app>/files/multistep-eval.txt`.
 */
@RunWith(AndroidJUnit4::class)
class MultiStepEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "multistep-eval.txt") }

    /** [needs]: tools that must all run; [never]: tools that must not; [check] looks at every call made. */
    private data class Case(
        val prompt: String,
        val needs: List<String>,
        val never: Set<String> = emptySet(),
        val screen: Boolean = false,
        val check: ((List<Call>, String) -> Boolean)? = null,
    )

    private data class Call(val name: String, val args: Map<String, Any?>)

    private val cases = listOf(
        Case("set an alarm for 6:30 am and a timer for 10 minutes", listOf("set_alarm", "set_timer")),
        Case("remind me to pay the rent tomorrow at 9 and set an alarm for 6:30 am", listOf("add_task", "set_alarm")),
        // The reply as shown, after ActionClaims.handedOff: never "I have sent".
        Case("WhatsApp Anjali that I'll be late tonight, and set an alarm at 6:30 am", listOf("send_message", "set_alarm")) { _, shown ->
            !SENT_CLAIM.containsMatchIn(shown)
        },
        Case("turn on the flashlight, put my phone on silent and set an alarm for 6:30", listOf("phone_setting", "phone_setting", "set_alarm")),
        // The second needs the first's answer.
        Case("what's 18% of 2450? remind me to pay that much to Ravi on Friday", listOf("calculate", "add_task")) { calls, _ ->
            calls.any { it.name == "add_task" && "441" in it.args.values.joinToString(" ") }
        },
        // From the chat, where there is no screen to take: nothing made up and shared, and the
        // reply mustn't say a screenshot was taken.
        Case(SCREEN_REQUEST, emptyList(), never = setOf("send_message")) { _, shown ->
            !Regex("(?i)(took|taken|captured|saved|shared|sent) (a |the |your )?screenshot").containsMatchIn(shown)
        },
        // From the assistant over a page: a WhatsApp message to Anjali with the screenshot and a summary.
        Case(SCREEN_REQUEST, listOf("send_message", "set_alarm"), screen = true) { calls, shown ->
            !SENT_CLAIM.containsMatchIn(shown) && calls.any { call ->
                val text = call.args["text"]?.toString().orEmpty().lowercase()
                call.name == "send_message" && screenshot(call) &&
                    call.args["to"].toString().contains("anjali", ignoreCase = true) &&
                    call.args["app"].toString().contains("whatsapp", ignoreCase = true) &&
                    ("rain" in text || "alert" in text)
            }
        },
        Case("take a screenshot", listOf("take_screenshot"), never = setOf("send_message"), screen = true),
        Case("take a screenshot and email it to priya@example.com", listOf("send_message"), screen = true) { calls, _ ->
            calls.any { it.name == "send_message" && screenshot(it) && "priya@example.com" in it.args["to"].toString() }
        },
        Case("share this with Priya on WhatsApp", listOf("send_message"), screen = true) { calls, _ ->
            calls.any { it.name == "send_message" && screenshot(it) && it.args["app"].toString().contains("whatsapp", ignoreCase = true) }
        },
        // Only a question: answered from the screen, nothing done.
        Case("what's on my screen?", emptyList(), never = ToolCatalog.ACTS, screen = true) { _, shown -> "rain" in shown.lowercase() },
    )

    private fun screenshot(call: Call) = call.args["screenshot"].let { it == true || it.toString() == "true" }

    @OptIn(ExperimentalApi::class)
    @Test
    fun severalThingsInOneMessage() {
        assumeTrue("Loads the real model; run with -e eval multistep", InstrumentationRegistry.getArguments().getString("eval") == "multistep")
        val modelPath = requireNotNull(SettingsStore(context).modelPath) { "No model installed" }
        ExperimentalFlags.enableSpeculativeDecoding = true
        val screen = drawScreen()
        Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                maxNumTokens = 8_192,
                cacheDir = context.cacheDir.absolutePath,
            ),
        ).apply { initialize() }.use { engine ->
            val samples = InstrumentationRegistry.getArguments().getString("samples")?.toIntOrNull() ?: 3
            // "-e only 5,6": just those cases.
            val only = InstrumentationRegistry.getArguments().getString("only")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            val tally = mutableListOf<String>()
            cases.forEachIndexed { index, case ->
                if (only != null && index !in only) return@forEachIndexed
                var passed = 0
                repeat(samples) { seed ->
                    if (run(engine, index, case, seed, screen)) passed++
                }
                tally += "case $index: $passed/$samples"
            }
            report("summary", tally.joinToString("; "))
        }
        screen.delete()
    }

    private fun run(engine: Engine, index: Int, case: Case, seed: Int, screen: File): Boolean {
        val system = Instructions.render(PERSONA, withTools = true, webSearch = true, photoSearch = true)
        val now = "[Now: ${DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).format(ZonedDateTime.now())}]"
        val calls = mutableListOf<Call>()
        val said = StringBuilder()
        val rounds = mutableListOf<String>()
        val ready = mutableListOf<String>()
        val failure = runCatching {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    tools = declarations(),
                    automaticToolCalling = false,
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0, seed = seed),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 400,
                ),
            ).use { conversation ->
                val text = listOfNotNull(now, PromptAssembler.SCREEN.takeIf { case.screen }, case.prompt).joinToString("\n")
                var message = if (case.screen) {
                    Message.user(Contents.of(Content.ImageFile(screen.absolutePath), Content.Text(text)))
                } else {
                    Message.user(Contents.of(text))
                }
                // The app's own limit (MemoryBudget.maxToolRounds), and one round past it.
                for (round in 0..MAX_ROUNDS) {
                    val these = mutableListOf<Call>()
                    runBlocking {
                        conversation.sendMessageAsync(message).collect { part ->
                            part.toolCalls.forEach { these += Call(it.name, it.arguments) }
                            if (part.toolCalls.isEmpty()) said.append(part.toString())
                        }
                    }
                    if (these.isEmpty()) break
                    // As ToolLoop does: what may need a lookup's answer waits for it.
                    val asked = these.map { ToolCall(it.name, it.args) }
                    val wait = ToolLoop.waiting(asked)
                    rounds += these.withIndex().joinToString(" + ") { (i, call) -> call.name + if (i in wait) " (held)" else "" }
                    calls += these.filterIndexed { i, _ -> i !in wait }
                    val results = these.mapIndexed { i, call ->
                        val result = if (i in wait) gson.fromJson(ToolLoop.waitFor(asked[i], asked), Map::class.java) else fakeResult(call, case)
                        if (call.name == ToolCatalog.SEND_MESSAGE && result["ok"] == true) {
                            ready += "WhatsApp is open with your message to ${call.args["to"]}; tap send."
                        }
                        Content.ToolResponse(call.name, result)
                    }
                    message = Message.tool(Contents.of(results))
                }
            }
        }.exceptionOrNull()
        val names = calls.map { it.name }
        val missing = case.needs.groupingBy { it }.eachCount().filter { (name, count) -> names.count { it == name } < count }.keys
        val forbidden = names.filter { it in case.never }
        val reply = said.toString().trim()
        val shown = ActionClaims.handedOff(reply, ready)
        val ok = failure == null && missing.isEmpty() && forbidden.isEmpty() && (case.check?.invoke(calls, shown) ?: true)
        report(
            "case $index seed $seed ${if (ok) "OK  " else "MISS"}",
            "rounds=[${rounds.joinToString(" | ")}]" +
                calls.joinToString("") { "\n    ${it.name}${it.args}" } +
                "\n    reply: ${reply.take(300).replace('\n', ' ')}" +
                (if (shown != reply) "\n    shown: ${shown.take(300).replace('\n', ' ')}" else "") +
                (failure?.let { "\n    failed: ${it.message}" } ?: ""),
        )
        return ok
    }

    /** What each tool would say had it worked; the calculator is real, and a screenshot needs a screen. */
    private fun fakeResult(call: Call, case: Case): Map<String, Any?> = when (call.name) {
        ToolCatalog.TAKE_SCREENSHOT ->
            if (case.screen) mapOf("ok" to true, "saved" to "the screenshot, in the gallery's Screenshots") else noScreenshot()
        ToolCatalog.SEND_MESSAGE ->
            if (screenshot(call) && !case.screen) {
                noScreenshot()
            } else {
                val what = if (screenshot(call)) "the screenshot and the message" else "the message"
                mapOf("ok" to true, "ready" to "${call.args["app"] ?: "Messages"} open to ${call.args["to"]} with $what; not sent yet, the user taps send")
            }
        ToolCatalog.SET_ALARM -> mapOf("ok" to true, "alarm" to "6:30 AM tomorrow")
        ToolCatalog.SET_TIMER -> mapOf("ok" to true, "timer" to "10 minutes")
        ToolCatalog.ADD_TASK -> mapOf("ok" to true, "task" to call.args["title"], "when" to call.args["when"])
        ToolCatalog.PHONE_SETTING -> mapOf("ok" to true, call.args["setting"].toString() to (call.args["value"] ?: "on"))
        ToolCatalog.CALCULATE -> runCatching {
            mapOf("ok" to true, "expression" to call.args["expression"], "result" to Calculator.run(call.args["expression"].toString()))
        }.getOrElse { mapOf("ok" to false, "error" to it.message) }
        else -> mapOf("ok" to true)
    }

    private fun noScreenshot() = mapOf("ok" to false, "error" to DeviceTools.NO_SCREENSHOT)

    /** A news page, drawn: what the user is looking at in the screen case. Made up, nobody's data. */
    private fun drawScreen(): File {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val bar = Paint().apply { color = Color.rgb(25, 80, 160) }
        canvas.drawRect(0f, 0f, 1080f, 220f, bar)
        fun paint(size: Float, colour: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = colour
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        canvas.drawText("Kerala News Today", 60f, 150f, paint(64f, Color.WHITE, bold = true))
        val title = paint(62f, Color.BLACK, bold = true)
        listOf("Orange alert: heavy rain", "for 7 districts on Thursday").forEachIndexed { i, line ->
            canvas.drawText(line, 60f, 380f + i * 80f, title)
        }
        val body = paint(42f, Color.DKGRAY)
        listOf(
            "The weather office has issued an orange alert",
            "for Ernakulam, Idukki, Kottayam, Alappuzha,",
            "Pathanamthitta, Thrissur and Palakkad on",
            "Thursday, with up to 20 cm of rain expected.",
            "",
            "Schools in Ernakulam and Idukki will stay",
            "closed. Fishermen are told not to go to sea",
            "until Saturday, and tourists should avoid",
            "hill roads to Munnar after dark.",
            "",
            "The rain should ease by Sunday.",
        ).forEachIndexed { i, line -> canvas.drawText(line, 60f, 620f + i * 64f, body) }
        val file = File(context.cacheDir, "multistep-screen.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun declarations() = ToolCatalog.declarations(web = true, photos = true).map { json ->
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
        const val TAG = "MultiStepEval"
        const val PERSONA = SettingsStore.DEFAULT_SYSTEM_PROMPT
        const val MAX_ROUNDS = 4
        const val SCREEN_REQUEST = "take a screenshot, share it on WhatsApp to Anjali with a message summarising what's in it, and then set an alarm at 6:30 a.m."
        val SENT_CLAIM = Regex("(?i)\\bI(?:'ve| have)?\\s+(?:just\\s+)?(?:sent|shared|called)\\b|\\bhas been sent\\b")
        val gson = com.google.gson.Gson()
    }
}
