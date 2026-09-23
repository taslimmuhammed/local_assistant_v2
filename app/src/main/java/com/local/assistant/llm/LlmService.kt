package com.local.assistant.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SupportedModalities
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Wraps the LiteRT-LM engine.
 *
 * There is exactly one [Engine] for the process — loading it costs seconds and gigabytes, so it
 * is shared across chats. Each chat gets its own [Conversation], which is what holds the KV
 * cache for that thread. The active conversation is cached so a continuing chat only has to
 * prefill the new turn; switching chats or resuming after a restart rebuilds it from the stored
 * history via [ConversationConfig.initialMessages].
 */
class LlmService(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    /** Decode speed for one reply, as measured by the runtime rather than timed from outside. */
    data class GenerationStats(
        val decodeTokens: Int,
        val tokensPerSecond: Double,
        val timeToFirstTokenMs: Long,
    )

    /** A file to send with the next prompt. */
    data class PromptAttachment(val path: String, val kind: AttachmentKind)

    sealed interface State {
        /** No model file installed yet. */
        data object NoModel : State

        data object Idle : State

        data object Loading : State

        data class Ready(val backend: String, val contextTokens: Int) : State

        data class Failed(val message: String) : State
    }

    /**
     * How much of the context window the active chat is using. This is the real number reported
     * by the runtime, not an estimate, so it is the way to tell whether a chat is actually
     * running out of room rather than misbehaving for some other reason.
     */
    data class ContextUsage(val used: Int, val max: Int) {
        val fraction: Float get() = if (max > 0) (used.toFloat() / max).coerceIn(0f, 1f) else 0f
        val isNearlyFull: Boolean get() = fraction >= NEARLY_FULL_FRACTION
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _contextUsage = MutableStateFlow<ContextUsage?>(null)
    val contextUsage: StateFlow<ContextUsage?> = _contextUsage.asStateFlow()

    /**
     * What the installed model file actually accepts, read from the file itself rather than
     * assumed. The composer uses this to decide whether to offer the image and mic buttons.
     */
    private val _modalities = MutableStateFlow<SupportedModalities?>(null)
    val modalities: StateFlow<SupportedModalities?> = _modalities.asStateFlow()

    private val calibrator = ContextCalibrator(
        settings = settings,
        activityManager = requireNotNull(context.getSystemService<ActivityManager>()),
    )

    /** Non-null only while the device's context ceiling is being measured. */
    val calibrationProgress: StateFlow<CalibrationProgress?> = calibrator.progress

    /** Backend currently being attempted, for the startup screen's commentary. */
    private val _loadingBackend = MutableStateFlow<String?>(null)
    val loadingBackend: StateFlow<String?> = _loadingBackend.asStateFlow()

    /** The window the loaded engine is actually running with. 0 until an engine is loaded. */
    private val _activeContextTokens = MutableStateFlow(0)
    val activeContextTokens: StateFlow<Int> = _activeContextTokens.asStateFlow()

    /** How many stored messages the current conversation had to drop to fit the window. */
    private val _droppedFromContext = MutableStateFlow(0)
    val droppedFromContext: StateFlow<Int> = _droppedFromContext.asStateFlow()

    /**
     * Stats for the reply that just finished. Held here rather than emitted through the text
     * flow so a stopped generation still reports what it managed.
     */
    private val _lastGenerationStats = MutableStateFlow<GenerationStats?>(null)
    val lastGenerationStats: StateFlow<GenerationStats?> = _lastGenerationStats.asStateFlow()

    /** Vision tokens an image costs, read from the model so the budget maths is not a guess. */
    private var visionTokensPerImage = DEFAULT_VISION_TOKENS

    private val loadMutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationChatId: Long? = null

    /** How many stored messages the cached conversation already knows about. */
    private var conversationMessageCount: Int = 0

    /**
     * Read without the lock by [stop], which has to interrupt a generation that is holding it.
     */
    @Volatile
    private var activeConversation: Conversation? = null

    init {
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
    }

    /** Starts loading the engine in the background. Safe to call repeatedly. */
    fun warmUp() {
        scope.launch { ensureEngine() }
    }

    /**
     * Loads the engine if it is not already loaded, trying the configured backend first and
     * falling back so a device without a working GPU path still ends up with a usable engine.
     */
    private suspend fun ensureEngine(): Engine? = loadMutex.withLock {
        engine?.let { return@withLock it }

        val modelPath = settings.modelPath
        if (modelPath == null || !File(modelPath).isFile) {
            _state.value = State.NoModel
            return@withLock null
        }

        _state.value = State.Loading
        val modalities = withContext(Dispatchers.IO) { probeModalities(modelPath) }
        _modalities.value = modalities
        visionTokensPerImage = withContext(Dispatchers.IO) { probeVisionTokens(modelPath) }
        calibrator.beginSession()

        for (attempt in attempts()) {
            // Each backend has its own memory profile, so the ceiling is calibrated per backend
            // rather than shared. Falling back to CPU on a context failure would otherwise hide
            // the fact that the GPU could have managed a smaller window.
            val key = "${File(modelPath).name}:${File(modelPath).length()}:${attempt.label}"
            _loadingBackend.value = attempt.label
            val outcome = withContext(Dispatchers.IO) {
                calibrator.obtainEngine(key) { tokens -> attempt.load(modelPath, modalities, tokens) }
            }
            if (outcome != null) {
                _loadingBackend.value = null
                engine = outcome.engine
                _activeContextTokens.value = outcome.tokens
                _state.value = State.Ready(attempt.label, outcome.tokens)
                return@withLock outcome.engine
            }
        }

        _loadingBackend.value = null
        _state.value = State.Failed(
            "Could not start the model on any backend or context size.",
        )
        null
    }

    /** Backends to try, best first. */
    private fun attempts(): List<LoadAttempt> = buildList {
        if (settings.useGpu) {
            if (settings.useSpeculativeDecoding) {
                add(LoadAttempt("GPU + MTP", speculative = true) { Backend.GPU() })
            }
            add(LoadAttempt("GPU", speculative = false) { Backend.GPU() })
        }
        add(LoadAttempt("CPU", speculative = false) { Backend.CPU() })
    }

    private inner class LoadAttempt(
        val label: String,
        private val speculative: Boolean,
        private val backend: () -> Backend,
    ) {
        @OptIn(ExperimentalApi::class)
        fun load(modelPath: String, modalities: SupportedModalities?, contextTokens: Int): Engine {
            // Both are global flags, so they have to be set before initialize().
            ExperimentalFlags.enableSpeculativeDecoding = speculative
            // Timing instrumentation only; this is what makes per-reply speed reportable.
            ExperimentalFlags.enableBenchmark = true
            return Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend(),
                    // Left unset this defaults to the runtime's own (small) context window.
                    // The value comes from calibration, which measured what this device holds.
                    maxNumTokens = contextTokens,
                    // Only declared when the model actually has the encoder. They are loaded
                    // lazily, so naming them costs nothing until an attachment is sent.
                    visionBackend = backend().takeIf { modalities?.vision == true },
                    audioBackend = Backend.CPU().takeIf { modalities?.audio == true },
                    // A writable cache dir lets the runtime reuse compiled kernels on later loads.
                    cacheDir = context.cacheDir.absolutePath,
                ),
            ).apply { initialize() }
        }
    }

    /** The model's own vision token budget, used when costing images against the window. */
    private fun probeVisionTokens(modelPath: String): Int = runCatching {
        Capabilities(modelPath).use { it.maxVisionTokenBudget() }
    }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_VISION_TOKENS

    /** Reads the model file's own declaration of what it accepts. Cheap: it does not load it. */
    private fun probeModalities(modelPath: String): SupportedModalities? = runCatching {
        Capabilities(modelPath).use { it.inputModalities() }
    }.onFailure { Log.w(TAG, "Could not read model capabilities", it) }.getOrNull()

    /**
     * Streams a reply to [prompt]. Emitted values are incremental chunks, not the running total.
     *
     * [history] is the conversation as stored, excluding [prompt] itself.
     */
    fun generate(
        chatId: Long,
        history: List<MessageEntity>,
        prompt: String,
        attachment: PromptAttachment? = null,
    ): Flow<String> = flow {
        val engine = ensureEngine() ?: error(failureMessage())
        val conversation = loadMutex.withLock { conversationFor(engine, chatId, history) }
        activeConversation = conversation

        // Same crash guard as calibration: if a real chat is what finally exhausts memory, the
        // marker is the only trace left after the process is killed.
        settings.generationInFlightTokens = _activeContextTokens.value
        _lastGenerationStats.value = null
        var completed = false
        try {
            conversation.sendMessageAsync(
                contentsOf(prompt, attachment?.path, attachment?.kind),
                repetitionPenaltyConfig = RepetitionPenaltyConfig(
                    repetitionPenalty = settings.repetitionPenalty,
                    windowSize = settings.repetitionWindow,
                ),
            ).collect { message ->
                // Message.toString() renders its Contents, which for a text model is the text.
                val chunk = message.toString()
                if (chunk.isNotEmpty()) emit(chunk)
            }
            completed = true
        } finally {
            activeConversation = null
            settings.generationInFlightTokens = 0
            withContext(NonCancellable) {
                loadMutex.withLock {
                    // Must run before closeConversation() below, which would drop the numbers.
                    _lastGenerationStats.value = readGenerationStats()
                    reportContextUsage()
                    if (completed && conversationChatId == chatId) {
                        // The conversation now also holds this user turn and the model's reply.
                        conversationMessageCount = history.size + 2
                    } else {
                        // Interrupted part-way: the cache no longer matches what we stored,
                        // so drop it and rebuild from the database on the next turn.
                        closeConversation()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Interrupts the in-flight generation. Whatever was streamed so far stays. */
    fun stop() {
        runCatching { activeConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelProcess failed", it) }
    }

    /** Forgets the measured ceiling so the next load measures again. Caller should unload first. */
    fun invalidateCalibration() = calibrator.invalidate()

    /**
     * Abandons measurement and settles for a window known to be modest enough to just work.
     *
     * Measuring means filling the window for real, which is slow by nature. This is the way out
     * for someone who would rather start chatting than wait for the largest possible answer.
     */
    fun useSafeWindow() {
        settings.manualContextTokens = SAFE_CONTEXT_TOKENS
        calibrator.cancel()
        scope.launch {
            unload()
            ensureEngine()
        }
    }

    /** Clears any manual choice and measures again from scratch. */
    fun retryLoad(remeasure: Boolean) {
        calibrator.cancel()
        scope.launch {
            unload()
            if (remeasure) {
                settings.manualContextTokens = 0
                calibrator.invalidate()
            }
            ensureEngine()
        }
    }

    /** Drops the cached conversation for [chatId], e.g. after its messages were deleted. */
    suspend fun forget(chatId: Long) = loadMutex.withLock {
        if (conversationChatId == chatId) closeConversation()
    }

    /** Releases the engine and all native memory. */
    suspend fun unload() = loadMutex.withLock {
        closeConversation()
        runCatching { engine?.close() }
        engine = null
        _activeContextTokens.value = 0
        _state.value = if (settings.modelPath == null) State.NoModel else State.Idle
    }

    private fun conversationFor(
        engine: Engine,
        chatId: Long,
        history: List<MessageEntity>,
    ): Conversation {
        val cached = conversation
        if (cached != null && conversationChatId == chatId && conversationMessageCount == history.size) {
            return cached
        }

        closeConversation()

        // Older turns are dropped rather than letting the window overflow, which the runtime
        // would reject outright with "Input token ids are too long".
        val trimmed = ContextWindow.trimToBudget(
            history = history,
            budgetTokens = ContextWindow.budgetFor(
                contextTokens = _activeContextTokens.value,
                maxOutputTokens = effectiveMaxOutputTokens(),
                systemPrompt = settings.systemPrompt,
            ),
            visionTokensPerImage = visionTokensPerImage,
        )
        _droppedFromContext.value = trimmed.droppedCount

        val fresh = engine.createConversation(
            ConversationConfig(
                systemInstruction = settings.systemPrompt
                    .takeIf { it.isNotBlank() }
                    ?.let { Contents.of(it) },
                initialMessages = trimmed.messages.map { it.toLiteRtMessage() },
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE,
                ),
                maxOutputToken = effectiveMaxOutputTokens(),
            ),
        )
        conversation = fresh
        conversationChatId = chatId
        conversationMessageCount = history.size
        return fresh
    }

    /** Must be called with [loadMutex] held, and before the conversation is closed. */
    @OptIn(ExperimentalApi::class)
    private fun readGenerationStats(): GenerationStats? {
        val current = conversation ?: return null
        return runCatching {
            val info = current.getBenchmarkInfo()
            GenerationStats(
                decodeTokens = info.lastDecodeTokenCount,
                tokensPerSecond = info.lastDecodeTokensPerSecond,
                timeToFirstTokenMs = (info.timeToFirstTokenInSecond * 1000).toLong(),
            ).takeIf { it.decodeTokens > 0 && it.tokensPerSecond > 0 }
        }.getOrNull()
    }

    /** Must be called with [loadMutex] held. */
    private fun reportContextUsage() {
        val current = conversation
        _contextUsage.value = if (current == null) {
            null
        } else {
            runCatching { ContextUsage(current.getTokenCount(), _activeContextTokens.value) }.getOrNull()
        }
    }

    /**
     * A reply may not claim more than a quarter of the window, so a long answer cannot leave the
     * next turn with nowhere to go.
     */
    private fun effectiveMaxOutputTokens(): Int {
        val window = _activeContextTokens.value
        if (window <= 0) return settings.maxOutputTokens
        return settings.maxOutputTokens.coerceAtMost(max(MIN_OUTPUT_TOKENS, window / 4))
    }

    private fun closeConversation() {
        runCatching { conversation?.close() }
        conversation = null
        conversationChatId = null
        conversationMessageCount = 0
        _contextUsage.value = null
        _droppedFromContext.value = 0
    }

    private fun failureMessage(): String =
        (state.value as? State.Failed)?.message ?: "The model is not loaded."

    private fun MessageEntity.toLiteRtMessage(): Message = when (role) {
        Role.USER -> Message.user(contentsOf(text, attachmentPath, attachmentKind))
        Role.ASSISTANT -> Message.model(text)
    }

    /**
     * Media first, then text — the order the runtime's own examples use.
     *
     * A missing attachment file degrades to text rather than failing the whole turn: the chat is
     * still readable, and the alternative is a chat that can never be reopened.
     */
    private fun contentsOf(text: String, attachmentPath: String?, kind: AttachmentKind?): Contents {
        val file = attachmentPath?.let(::File)?.takeIf { it.isFile }
        if (file == null || kind == null) return Contents.of(text)

        val media = when (kind) {
            AttachmentKind.IMAGE -> Content.ImageFile(file.absolutePath)
            AttachmentKind.AUDIO -> Content.AudioFile(file.absolutePath)
        }
        return if (text.isBlank()) {
            Contents.of(media)
        } else {
            Contents.of(media, Content.Text(text))
        }
    }

    companion object {
        private const val TAG = "LlmService"
        private const val TOP_K = 64
        private const val TOP_P = 0.95
        private const val TEMPERATURE = 1.0
        private const val NEARLY_FULL_FRACTION = 0.85f
        private const val DEFAULT_VISION_TOKENS = 256
        private const val MIN_OUTPUT_TOKENS = 256

        /** Small enough that essentially any device that can hold the model can hold this too. */
        const val SAFE_CONTEXT_TOKENS = 4096
    }
}
