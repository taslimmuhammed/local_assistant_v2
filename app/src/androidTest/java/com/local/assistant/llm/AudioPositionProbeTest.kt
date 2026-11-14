package com.local.assistant.llm

import android.os.SystemClock
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
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import com.local.assistant.appContainer
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.gallery.GalleryEmbedder
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.prompt.PromptAssembler
import com.local.assistant.memory.tools.GarbledText
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Where in a conversation a voice message stops being read: the same spoken question, and the
 * same typed, with the conversation padded to each position first (14 Nov 2026: replies turned to
 * token soup once a recording landed past ~4,096 in a chat). The engine is loaded as the app loads
 * it (GPU, 8K, the recording read on the CPU) and the chat opened as the app opens one, with its
 * instructions and tools and prediction off.
 *
 * The clip is `voice-input/known/capital.wav` under the app's external files ("What is the capital
 * of Australia?", 16 kHz mono).
 *
 *     adb shell am instrument -w -e eval audioposition [-e at 3700,4300] -e class com.local.assistant.llm.AudioPositionProbeTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AudioPositionProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "audio-position-probe.txt").apply { writeText("") } }

    private val filler = "This is an ordinary note about the weather, the garden and the week's plans, kept only to fill the conversation. "

    @OptIn(ExperimentalApi::class)
    @Test
    fun voiceAtEachPosition() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Loads the real model; run with -e eval audioposition", args.getString("eval") == "audioposition")
        // "-e clip <path> -e words <what was heard>": one of the user's own recordings instead,
        // judged only by whether the reply reads as sentences, and not written out.
        val own = args.getString("clip")?.let(::File)
        val clip = own ?: File(context.getExternalFilesDir(null), "voice-input/known/capital.wav")
        check(clip.isFile) { "No clip at $clip" }
        val words = args.getString("words") ?: "what is the capital of Australia"
        val targets = args.getString("at")?.split(',')?.map { it.trim().toInt() } ?: listOf(3_700, 3_950, 4_050, 4_300, 4_600)
        val modelPath = requireNotNull(SettingsStore(context).modelPath) { "No model installed" }
        // As LiteRtLmBackend: thinking turned off where the model has it.
        val thinks = ModelInfo.from(modelPath).use { (it as? ModelInfo.Llm)?.supportsThinking() == true }
        report("model thinks: $thinks")
        // "-e photo concurrent": the photo model (memory search's too) loads while the chat model
        // does, as the app did when it came to the front on 14 Nov 2026; "after": once it's up;
        // "reload": up before each conversation opens, then closed and loaded again before the
        // question, as when the app went away and came back with a chat open. Its vision on the
        // GPU that crashed the chat model in OpenCL (14 Nov 2026); "-e vision cpu" keeps it off.
        val photo = args.getString("photo")
        val photoModel = if (args.getString("vision") == "cpu") {
            GalleryEmbedder({ context.appContainer.galleryModelManager.installed.value?.file }, context.cacheDir, visionOnGpu = false)
        } else {
            context.appContainer.photoModel
        }
        val clock = SystemClock.elapsedRealtime()
        val photoLoad = if (photo == "concurrent") {
            thread {
                val started = SystemClock.elapsedRealtime() - clock
                val ready = runBlocking { photoModel.prepare() }
                report("photo model ready $ready, loaded from $started to ${SystemClock.elapsedRealtime() - clock} ms")
            }
        } else {
            null
        }
        ExperimentalFlags.enableSpeculativeDecoding = true
        Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = 8_192,
                visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            ),
        ).apply { initialize() }.use { engine ->
            report("chat model loaded at ${SystemClock.elapsedRealtime() - clock} ms")
            photoLoad?.join()
            if (photo == "after" || photo == "reload") report("photo model ready ${runBlocking { photoModel.prepare() }}, after the chat model")
            val system = Instructions.render(
                SettingsStore.DEFAULT_SYSTEM_PROMPT, withTools = true, webSearch = true, photoSearch = true, healthData = true, fileSearch = true, inbox = true,
            )
            val tools = ToolCatalog.declarations(web = true, photos = true, health = true, files = true, inbox = true)
            fun open(pairs: Int) = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = (1..pairs).flatMap { i -> listOf(Message.user(Contents.of("Note $i, please.")), Message.model(Contents.of(filler.repeat(4)))) },
                    tools = tools.map { json ->
                        tool(object : OpenApiTool {
                            override fun getToolDescriptionJsonString() = json
                            override fun execute(paramsJsonString: String): String = error("manual")
                        })
                    },
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0, seed = 0),
                    automaticToolCalling = false,
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 80,
                    thinkingConfig = if (thinks) ThinkingConfig(enableThinking = false) else null,
                    speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.OFF),
                ),
            )
            val base = open(0).use { it.getTokenCount() }
            val perPair = open(2).use { it.getTokenCount() } - base
            report("base $base tokens, ${perPair / 2} a filler pair")
            for (target in targets) {
                val pairs = ((target - base) * 2 / perPair).coerceAtLeast(0)
                for (voice in listOf(true, false)) {
                    open(pairs).use { conversation ->
                        val at = conversation.getTokenCount()
                        if (photo == "reload") {
                            runBlocking { photoModel.unload() }
                            check(runBlocking { photoModel.prepare() }) { "Photo model didn't load again" }
                        }
                        val text = "[Now: Sat 14 Nov 2026, 13:00]\n" +
                            if (voice) PromptAssembler.heard(words) else words
                        val message = if (voice) Message.user(Contents.of(Content.AudioFile(clip.absolutePath), Content.Text(text))) else Message.user(Contents.of(text))
                        val started = SystemClock.elapsedRealtime()
                        // Any channel the runtime splits off (a "thought" one if the model thought), by length.
                        val channels = HashMap<String, Int>()
                        val calls = ArrayList<String>()
                        val said = runCatching {
                            runBlocking {
                                val reply = StringBuilder()
                                conversation.sendMessageAsync(message).collect { chunk ->
                                    reply.append(chunk.toString())
                                    chunk.toolCalls.forEach { calls += it.name }
                                    chunk.channels.forEach { (name, text) -> channels.merge(name, text.length, Int::plus) }
                                }
                                reply.toString()
                            }
                        }.getOrElse { "failed: ${it.message}" }
                        val after = conversation.getTokenCount()
                        // A tool call is a sound reply too: the messages question should make one.
                        val ok = if (own != null) calls.isNotEmpty() || readsAsSentences(said) else "canberra" in said.lowercase()
                        report(
                            "${if (ok) "OK  " else "BAD "} ${if (voice) "voice" else "typed"} from $at to $after tokens in ${SystemClock.elapsedRealtime() - started} ms" +
                                (if (calls.isEmpty()) "" else ", calls $calls") +
                                (if (channels.isEmpty()) "" else ", channels $channels") +
                                if (own != null) ", ${said.length} chars" else ": ${said.take(160).replace('\n', ' ')}",
                        )
                    }
                }
            }
        }
        if (photo != null) runBlocking { photoModel.unload() }
    }

    /** Words, not token soup ("840tsetUauthorx205619i aponitimiCrap.M"). */
    private fun readsAsSentences(reply: String): Boolean =
        reply.isNotBlank() && GarbledText.verdict(reply, final = true) == GarbledText.Verdict.FINE

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "AudioPositionProbe"
    }
}
