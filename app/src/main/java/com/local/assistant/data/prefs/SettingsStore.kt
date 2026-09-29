package com.local.assistant.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.local.assistant.memory.prompt.TokenRateStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Small preference-backed store. Deliberately plain: everything the app needs to remember
 * right now is a handful of scalars.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).also { prefs ->
        if (RETIRED_KEYS.any(prefs::contains)) prefs.edit { RETIRED_KEYS.forEach(::remove) }
    }

    /** Absolute path of the installed .litertlm file, or null when no model is installed. */
    var modelPath: String?
        get() = prefs.getString(KEY_MODEL_PATH, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_MODEL_PATH) else putString(KEY_MODEL_PATH, value) }

    /** Absolute path of the installed embedding bundle, or null when there is none. */
    var embedderPath: String?
        get() = prefs.getString(KEY_EMBEDDER_PATH, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_EMBEDDER_PATH) else putString(KEY_EMBEDDER_PATH, value) }

    /** Which embedder [embedderPath] is (an `EmbedderCatalog` key), which decides its prompts. */
    var embedderKey: String?
        get() = prefs.getString(KEY_EMBEDDER_KEY, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_EMBEDDER_KEY) else putString(KEY_EMBEDDER_KEY, value) }

    /**
     * Pause memory: while on, new messages are kept in their chat but nothing is learned from
     * them — no archive, recall, summaries, extraction, and no facts saved by tools.
     */
    var memoryPaused: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_PAUSED, false)
        set(value) = prefs.edit { putBoolean(KEY_MEMORY_PAUSED, value) }

    /** Web search has been offered once, after the profile (a key saved, or skipped). */
    var webSearchAsked: Boolean
        get() = prefs.getBoolean(KEY_WEB_SEARCH_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_WEB_SEARCH_ASKED, value) }

    /** The profile has been offered once (filled in or skipped); first launch asks for it. */
    var profileAsked: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_PROFILE_ASKED, value) }

    /** Chat history older than this many days is deleted by the nightly job; 0 keeps everything. */
    var historyRetentionDays: Int
        get() = prefs.getInt(KEY_HISTORY_RETENTION, 0)
        set(value) = prefs.edit { putInt(KEY_HISTORY_RETENTION, value) }

    /** Prefer the GPU backend. Falls back to CPU automatically if the GPU engine fails to start. */
    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_GPU, value) }

    /** Multi-token prediction. Recommended on GPU, and disabled automatically if it fails. */
    var useSpeculativeDecoding: Boolean
        get() = prefs.getBoolean(KEY_SPECULATIVE, true)
        set(value) = prefs.edit { putBoolean(KEY_SPECULATIVE, value) }

    /**
     * The window the model runs with, in tokens: 8K unless the user picks more in settings. The
     * KV cache is sized by the window, not by how full it is, and the LLM shares memory with the
     * embedder and a voice model. Changing it needs an engine reload.
     */
    var contextTokens: Int
        get() = prefs.getInt(KEY_CONTEXT_TOKENS, DEFAULT_CONTEXT_TOKENS)
        set(value) = prefs.edit(commit = true) { putInt(KEY_CONTEXT_TOKENS, value) }

    /** A larger window failed to start, so the app went back to 8K; settings says so once. */
    var contextFellBack: Boolean
        get() = prefs.getBoolean(KEY_CONTEXT_FELL_BACK, false)
        set(value) = prefs.edit { putBoolean(KEY_CONTEXT_FELL_BACK, value) }

    /** The Latin characters-per-token rate measured for one model; see `MeasuredTokenEstimator`. */
    val tokenRates: TokenRateStore = object : TokenRateStore {
        override fun load(modelKey: String): Double? =
            prefs.getFloat(KEY_TOKEN_RATE, 0f).takeIf { it > 0f && prefs.getString(KEY_TOKEN_RATE_MODEL, null) == modelKey }
                ?.toDouble()

        override fun save(modelKey: String, latinCharsPerToken: Double) = prefs.edit {
            putFloat(KEY_TOKEN_RATE, latinCharsPerToken.toFloat())
            putString(KEY_TOKEN_RATE_MODEL, modelKey)
        }
    }

    /** Notifications are asked for once, the first time a timed reminder is made. */
    var askedForNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, value) }

    /** Exact alarms are offered once, the first time a reminder could only be scheduled inexactly. */
    var offeredExactAlarms: Boolean
        get() = prefs.getBoolean(KEY_OFFERED_EXACT_ALARMS, false)
        set(value) = prefs.edit { putBoolean(KEY_OFFERED_EXACT_ALARMS, value) }

    /**
     * The window an engine load is in progress at, cleared when it returns. A native
     * out-of-memory kills the process with no exception, so a value still here at the next launch
     * means that size was too much for this phone. `commit`, since an asynchronous write may not
     * reach disk before the process dies.
     */
    var initInFlightTokens: Int
        get() = prefs.getInt(KEY_INIT_IN_FLIGHT, 0)
        set(value) = prefs.edit(commit = true) { putInt(KEY_INIT_IN_FLIGHT, value) }

    /** Ceiling on a single reply, so one runaway answer cannot consume the whole context. */
    var maxOutputTokens: Int
        get() = prefs.getInt(KEY_MAX_OUTPUT, DEFAULT_MAX_OUTPUT)
        set(value) = prefs.edit { putInt(KEY_MAX_OUTPUT, value) }

    /**
     * Damps degenerate repetition loops. 1.0 disables it. Kept low because generated code and
     * markup legitimately repeat, and a heavy penalty makes the model avoid necessary tokens.
     */
    var repetitionPenalty: Float
        get() = prefs.getFloat(KEY_REPETITION_PENALTY, DEFAULT_REPETITION_PENALTY)
        set(value) = prefs.edit { putFloat(KEY_REPETITION_PENALTY, value) }

    /** How many recent tokens the repetition penalty considers. */
    var repetitionWindow: Int
        get() = prefs.getInt(KEY_REPETITION_WINDOW, DEFAULT_REPETITION_WINDOW)
        set(value) = prefs.edit { putInt(KEY_REPETITION_WINDOW, value) }

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit { putString(KEY_SYSTEM_PROMPT, value) }

    /** The assistant overlay reads its answer aloud when it was asked by voice. */
    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK_REPLIES, true)
        set(value) = prefs.edit { putBoolean(KEY_SPEAK_REPLIES, value) }

    /** The text-to-speech voice picked in settings, by name; null for the automatic choice. */
    var assistantVoice: String?
        get() = prefs.getString(KEY_ASSISTANT_VOICE, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_ASSISTANT_VOICE) else putString(KEY_ASSISTANT_VOICE, value) }

    /** How fast the voice speaks; 1.0 is the engine's normal pace. */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        set(value) = prefs.edit { putFloat(KEY_SPEECH_RATE, value) }

    fun observeModelPath(): Flow<String?> = observeKey(KEY_MODEL_PATH) { modelPath }

    fun observeMemoryPaused(): Flow<Boolean> = observeKey(KEY_MEMORY_PAUSED) { memoryPaused }

    fun observeHistoryRetentionDays(): Flow<Int> = observeKey(KEY_HISTORY_RETENTION) { historyRetentionDays }

    private fun <T> observeKey(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_EMBEDDER_PATH = "embedder_path"
        private const val KEY_EMBEDDER_KEY = "embedder_key"
        private const val KEY_MEMORY_PAUSED = "memory_paused"
        private const val KEY_PROFILE_ASKED = "profile_asked"
        private const val KEY_WEB_SEARCH_ASKED = "web_search_asked"
        private const val KEY_HISTORY_RETENTION = "history_retention_days"
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_SPECULATIVE = "speculative_decoding"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_INIT_IN_FLIGHT = "init_in_flight_tokens"
        private const val KEY_CONTEXT_TOKENS = "context_tokens"
        private const val KEY_CONTEXT_FELL_BACK = "context_fell_back"

        /** What calibration used to keep, before the window became a plain setting. */
        private val RETIRED_KEYS = listOf(
            "manual_context_tokens", "calibrated_context_tokens", "calibration_key", "generation_in_flight_tokens",
            "generation_crash_streak", "context_ceiling_tokens", "init_crash_streak",
        )
        private const val KEY_MAX_OUTPUT = "max_output_tokens"
        private const val KEY_TOKEN_RATE = "latin_chars_per_token"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_for_notifications"
        private const val KEY_OFFERED_EXACT_ALARMS = "offered_exact_alarms"
        private const val KEY_TOKEN_RATE_MODEL = "latin_chars_per_token_model"
        private const val KEY_REPETITION_PENALTY = "repetition_penalty"
        private const val KEY_REPETITION_WINDOW = "repetition_window"
        private const val KEY_SPEAK_REPLIES = "speak_replies"
        private const val KEY_ASSISTANT_VOICE = "assistant_voice"
        private const val KEY_SPEECH_RATE = "speech_rate"

        const val DEFAULT_SPEECH_RATE = 1.0f

        /** What settings offers for [speechRate]. */
        val SPEECH_RATES = listOf(0.85f to "Relaxed", 1.0f to "Normal", 1.2f to "Brisk")

        const val DEFAULT_MAX_OUTPUT = 2048
        /** 8K: what the prompt budget is designed around, and what every supported phone holds. */
        const val DEFAULT_CONTEXT_TOKENS = 8192

        /**
         * What settings offers. 4K for phones short on memory — the instructions and tools alone
         * are ~2,000 tokens, so it keeps only the last exchange or two. 16K fitted the 15.5 GB
         * test phone; more than that did not.
         */
        val CONTEXT_CHOICES = listOf(4096, 8192, 12288, 16384)
        const val DEFAULT_REPETITION_PENALTY = 1.1f
        const val DEFAULT_REPETITION_WINDOW = 256

        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant running entirely on the user's device."
    }
}
