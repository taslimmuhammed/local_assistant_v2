package com.local.assistant.data.prefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Small preference-backed store. Deliberately plain: everything the app needs to remember
 * right now is a handful of scalars.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Absolute path of the installed .litertlm file, or null when no model is installed. */
    var modelPath: String?
        get() = prefs.getString(KEY_MODEL_PATH, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_MODEL_PATH) else putString(KEY_MODEL_PATH, value) }

    /** Prefer the GPU backend. Falls back to CPU automatically if the GPU engine fails to start. */
    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_GPU, value) }

    /** Multi-token prediction. Recommended on GPU, and disabled automatically if it fails. */
    var useSpeculativeDecoding: Boolean
        get() = prefs.getBoolean(KEY_SPECULATIVE, true)
        set(value) = prefs.edit { putBoolean(KEY_SPECULATIVE, value) }

    /**
     * Context window, in tokens. This is the KV cache size, so it costs memory: the model itself
     * supports up to 32k, but a phone will not enjoy that. Changing it requires an engine reload.
     */
    var maxContextTokens: Int
        get() = prefs.getInt(KEY_MAX_CONTEXT, DEFAULT_MAX_CONTEXT)
        set(value) = prefs.edit { putInt(KEY_MAX_CONTEXT, value) }

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

    fun observeModelPath(): Flow<String?> = observeKey(KEY_MODEL_PATH) { modelPath }

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
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_SPECULATIVE = "speculative_decoding"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_MAX_CONTEXT = "max_context_tokens"
        private const val KEY_MAX_OUTPUT = "max_output_tokens"
        private const val KEY_REPETITION_PENALTY = "repetition_penalty"
        private const val KEY_REPETITION_WINDOW = "repetition_window"

        /** The model supports 32k; this is a phone-friendly starting point. */
        const val DEFAULT_MAX_CONTEXT = 4096
        const val DEFAULT_MAX_OUTPUT = 2048
        const val DEFAULT_REPETITION_PENALTY = 1.1f
        const val DEFAULT_REPETITION_WINDOW = 256

        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant running entirely on the user's device."
    }
}
