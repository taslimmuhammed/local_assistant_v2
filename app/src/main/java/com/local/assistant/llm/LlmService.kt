package com.local.assistant.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SupportedModalities
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the LiteRT-LM engine: loading it, calibrating its window, and releasing it.
 *
 * There is exactly one [Engine] for the process — loading it costs seconds and gigabytes, so it
 * is shared across chats. Conversations on it are created through [createConversation], which
 * tracks them so [unload] can close every one before the engine goes. What a conversation
 * *contains*, and when to rebuild it, is decided above this class (see `LiteRtLmBackend` and
 * the memory layer's `ConversationManager`).
 */
class LlmService(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

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

    /** What the loaded model supports, read from the model file. Null until a model is loaded. */
    private val _capabilities = MutableStateFlow<BackendCapabilities?>(null)
    val capabilities: StateFlow<BackendCapabilities?> = _capabilities.asStateFlow()

    /**
     * Stats for the generation that just finished. Held here rather than only emitted through the
     * stream, so a stopped generation — whose stream is cancelled — still reports what it managed.
     */
    private val _lastGenerationStats = MutableStateFlow<GenStats?>(null)
    val lastGenerationStats: StateFlow<GenStats?> = _lastGenerationStats.asStateFlow()

    /** Vision tokens an image costs, read from the model so the budget maths is not a guess. */
    private var visionTokensPerImage = DEFAULT_VISION_TOKENS

    private val loadMutex = Mutex()

    private var engine: Engine? = null

    /** Every conversation still open on [engine]; closed before the engine is. Guarded by itself. */
    private val openConversations = mutableSetOf<Conversation>()

    /**
     * Read without the lock by [stop], which has to interrupt a generation that is holding it.
     */
    @Volatile
    private var activeConversation: Conversation? = null

    /** True while a reply or background completion is being decoded. */
    val isGenerating: Boolean get() = activeConversation != null

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
        val features = withContext(Dispatchers.IO) { probeFeatures(modelPath) }
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
                _capabilities.value = BackendCapabilities(
                    // Not features.functionCalling: Gemma 4 E4B's file reports false, yet native
                    // tool calls work (measured on device, EngineProbeTest.toolCalling). The
                    // runtime supports manual tool calling; how well a model routes is an eval
                    // question, not a capability flag.
                    tools = true,
                    // The runtime API exists in this version; whether this model honours it is
                    // checked on device before anything relies on it.
                    constrainedJson = true,
                    thinking = features.thinking,
                    speculativeDecoding = features.speculative && attempt.speculative,
                    maxContextTokens = outcome.tokens,
                    visionTokensPerImage = visionTokensPerImage,
                )
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
        val speculative: Boolean,
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

    private data class ModelFeatures(val functionCalling: Boolean, val thinking: Boolean, val speculative: Boolean)

    /** What the model file says it can do. Unknown reads as "no", never as "yes". */
    private fun probeFeatures(modelPath: String): ModelFeatures = runCatching {
        Capabilities(modelPath).use {
            ModelFeatures(it.supportsFunctionCalling(), it.supportsThinking(), it.hasSpeculativeDecodingSupport())
        }
    }.onFailure { Log.w(TAG, "Could not read model features", it) }
        .getOrDefault(ModelFeatures(functionCalling = false, thinking = false, speculative = false))
        .also { Log.i(TAG, "Model features: $it") }

    /** Loads the engine if needed and waits for it. Null if no model can be loaded. */
    suspend fun awaitEngine(): Engine? = ensureEngine()

    /**
     * Opens a conversation on the loaded engine, loading it first if necessary. The conversation
     * is tracked so [unload] can close it; call [conversationClosed] after closing it yourself.
     */
    suspend fun createConversation(config: ConversationConfig): Conversation {
        val loaded = ensureEngine() ?: error(failureMessage())
        // Creation may prefill, which the native side cannot abandon half-way. Let it finish,
        // then close the result if the caller stopped waiting, rather than leaking it.
        val created = withContext(Dispatchers.IO + NonCancellable) {
            loadMutex.withLock {
                // The engine may have been swapped out while we waited for the lock.
                val current = engine ?: error(failureMessage())
                check(current === loaded) { "The model was reloaded; try again." }
                current.createConversation(config).also { synchronized(openConversations) { openConversations += it } }
            }
        }
        if (!currentCoroutineContext().isActive) {
            runCatching { created.close() }
            conversationClosed(created)
            currentCoroutineContext().ensureActive()
        }
        return created
    }

    fun conversationClosed(conversation: Conversation) {
        synchronized(openConversations) { openConversations -= conversation }
    }

    /**
     * Brackets every generation. The marker is the same crash guard calibration uses: if a real
     * chat is what finally exhausts memory, it is the only trace left after the process is killed.
     */
    fun generationStarted(conversation: Conversation) {
        _lastGenerationStats.value = null
        activeConversation = conversation
        settings.generationInFlightTokens = _activeContextTokens.value
    }

    /** Clears the guard and returns the runtime's own measurement of the generation. */
    fun generationFinished(conversation: Conversation): GenStats? {
        if (activeConversation === conversation) activeConversation = null
        settings.generationInFlightTokens = 0
        return readGenerationStats(conversation).also { _lastGenerationStats.value = it }
    }

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

    /** Releases the engine, every conversation on it, and all native memory. */
    suspend fun unload() = loadMutex.withLock {
        val open = synchronized(openConversations) { openConversations.toList().also { openConversations.clear() } }
        open.forEach { runCatching { it.close() } }
        runCatching { engine?.close() }
        engine = null
        _activeContextTokens.value = 0
        _capabilities.value = null
        _state.value = if (settings.modelPath == null) State.NoModel else State.Idle
    }

    /** Must be called before the conversation is closed, which would drop the numbers. */
    @OptIn(ExperimentalApi::class)
    private fun readGenerationStats(conversation: Conversation): GenStats? = runCatching {
        val info = conversation.getBenchmarkInfo()
        GenStats(
            decodeTokens = info.lastDecodeTokenCount,
            tokensPerSecond = info.lastDecodeTokensPerSecond,
            timeToFirstTokenMs = (info.timeToFirstTokenInSecond * 1000).toLong(),
            prefillTokens = info.lastPrefillTokenCount,
        ).takeIf { it.decodeTokens > 0 && it.tokensPerSecond > 0 }
    }.getOrNull()

    private fun failureMessage(): String =
        (state.value as? State.Failed)?.message ?: "The model is not loaded."

    companion object {
        private const val TAG = "LlmService"
        private const val NEARLY_FULL_FRACTION = 0.85f
        private const val DEFAULT_VISION_TOKENS = 256

        /** Small enough that essentially any device that can hold the model can hold this too. */
        const val SAFE_CONTEXT_TOKENS = 4096
    }
}
