package com.local.assistant.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
 * Owns the LiteRT-LM engine: loading it at the window chosen in settings, and releasing it.
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
    data class ContextUsage(
        val used: Int,
        val max: Int,
        /** The chat whose conversation this is; the overlay's chat can be live behind the chat on screen. */
        val chatId: Long? = null,
    ) {
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

    /**
     * The text decoder runs in fp32 ([ModelPrecision]), so the model copies digits exactly at any
     * position. False until a model has loaded, and for a file whose precision couldn't be set:
     * then the app's own checks on numbers ([com.local.assistant.memory.tools.ReplyGrounding],
     * re-reading saved images) stand in.
     */
    @Volatile
    var exactDigits: Boolean = false
        private set

    /** Off only in evals that measure the model file's own fp16 against fp32. */
    @Volatile
    internal var setsPrecision: Boolean = true

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
     *
     * The window is the one chosen in settings, 8K unless the user picked more. A larger one that
     * fails to load falls back to 8K on the same backend; one that killed the process last time
     * (a native out-of-memory leaves no exception to catch) is put back to 8K before trying.
     */
    private suspend fun ensureEngine(): Engine? = loadMutex.withLock {
        engine?.let { return@withLock it }

        val modelPath = settings.modelPath
        if (modelPath == null || !File(modelPath).isFile) {
            _state.value = State.NoModel
            return@withLock null
        }
        // Handed anything else, the runtime can take the app down with it, at every launch.
        if (!ModelFormat.isLiteRtLm(File(modelPath))) {
            Log.w(TAG, "Not loading $modelPath: not a LiteRT-LM bundle")
            _state.value = State.Failed("The model file isn't a LiteRT-LM model. Replace it under Settings \u2192 Model.")
            return@withLock null
        }

        _state.value = State.Loading
        val loadStarted = SystemClock.elapsedRealtime()
        // Before the engine opens the file: fp32 for the text decoder, or digits garble past token 2,048.
        val precision = withContext(Dispatchers.IO) {
            runCatching {
                if (setsPrecision) {
                    ModelPrecision.ensureFp32TextDecoder(File(modelPath))
                } else {
                    ModelPrecision.Outcome.ALREADY_FP32.takeIf { ModelPrecision.textDecoderPrecision(File(modelPath)) == "fp32" }
                }
            }
                .onFailure { Log.w(TAG, "Could not set the text decoder's precision", it) }
                .getOrNull()
        }
        exactDigits = precision == ModelPrecision.Outcome.PATCHED || precision == ModelPrecision.Outcome.ALREADY_FP32
        Log.i(TAG, "Text decoder precision: $precision")
        val modalities = withContext(Dispatchers.IO) { probeModalities(modelPath) }
        _modalities.value = modalities
        visionTokensPerImage = withContext(Dispatchers.IO) { probeVisionTokens(modelPath) }
        val features = withContext(Dispatchers.IO) { probeFeatures(modelPath) }

        val died = settings.initInFlightTokens
        if (died > SettingsStore.DEFAULT_CONTEXT_TOKENS && died == settings.contextTokens) {
            Log.w(TAG, "The last load at $died tokens killed the app; back to ${SettingsStore.DEFAULT_CONTEXT_TOKENS}")
            settings.contextTokens = SettingsStore.DEFAULT_CONTEXT_TOKENS
            settings.contextFellBack = true
        }
        settings.initInFlightTokens = 0
        val sizes = listOf(settings.contextTokens, SettingsStore.DEFAULT_CONTEXT_TOKENS).distinct()

        for (attempt in attempts()) {
            _loadingBackend.value = attempt.label
            for (tokens in sizes) {
                val loaded = withContext(Dispatchers.IO) { loadAt(attempt, modelPath, modalities, tokens) } ?: continue
                if (tokens != settings.contextTokens) {
                    settings.contextTokens = tokens
                    settings.contextFellBack = true
                }
                _loadingBackend.value = null
                engine = loaded
                _activeContextTokens.value = tokens
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
                    maxContextTokens = tokens,
                    visionTokensPerImage = visionTokensPerImage,
                )
                _state.value = State.Ready(attempt.label, tokens)
                Log.i(TAG, "Loaded ${attempt.label} at $tokens tokens in ${SystemClock.elapsedRealtime() - loadStarted} ms")
                return@withLock loaded
            }
        }

        _loadingBackend.value = null
        _state.value = State.Failed("Could not start the model on this device.")
        null
    }

    /** One engine load, with the size written down first in case it takes the process with it. */
    private fun loadAt(attempt: LoadAttempt, modelPath: String, modalities: SupportedModalities?, tokens: Int): Engine? {
        settings.initInFlightTokens = tokens
        return try {
            attempt.load(modelPath, modalities, tokens)
        } catch (e: Throwable) {
            Log.w(TAG, "${attempt.label} failed to load at $tokens tokens", e)
            null
        } finally {
            settings.initInFlightTokens = 0
        }
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

    /** Brackets every generation, so [stop] knows what to interrupt. */
    fun generationStarted(conversation: Conversation) {
        _lastGenerationStats.value = null
        activeConversation = conversation
    }

    /** Returns the runtime's own measurement of the generation. */
    fun generationFinished(conversation: Conversation): GenStats? {
        if (activeConversation === conversation) activeConversation = null
        return readGenerationStats(conversation).also { _lastGenerationStats.value = it }
    }

    /** Interrupts the in-flight generation. Whatever was streamed so far stays. */
    fun stop() {
        runCatching { activeConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelProcess failed", it) }
    }

    /** Loads again after a failure, or after a setting that needs a reload changed. */
    fun retryLoad() {
        scope.launch {
            unload()
            ensureEngine()
        }
    }

    /** Runs the model with a [tokens]-token window from now on; reloads it to take effect. */
    fun setContextTokens(tokens: Int) {
        if (tokens == settings.contextTokens) return
        settings.contextTokens = tokens
        settings.contextFellBack = false
        retryLoad()
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

    }
}
