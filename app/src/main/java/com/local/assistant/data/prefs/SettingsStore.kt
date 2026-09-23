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
     * Manual context size in tokens, or 0 to let calibration find the largest the device holds.
     * Changing it requires an engine reload.
     */
    var manualContextTokens: Int
        get() = prefs.getInt(KEY_MANUAL_CONTEXT, 0)
        set(value) = prefs.edit { putInt(KEY_MANUAL_CONTEXT, value) }

    /** Largest context confirmed to work on this device, or 0 if never calibrated. */
    var calibratedContextTokens: Int
        get() = prefs.getInt(KEY_CALIBRATED_CONTEXT, 0)
        set(value) = prefs.edit { putInt(KEY_CALIBRATED_CONTEXT, value) }

    /** Identity of the model and backend [calibratedContextTokens] was measured against. */
    var calibrationKey: String?
        get() = prefs.getString(KEY_CALIBRATION_KEY, null)
        set(value) = prefs.edit { putString(KEY_CALIBRATION_KEY, value) }

    /**
     * Crash guards. A native out-of-memory kills the process outright — no exception, no
     * `finally`. Writing the size we are about to attempt, and clearing it once the attempt
     * returns, is the only way to learn on the next launch that a size was fatal.
     *
     * Both use `commit` rather than `apply`: an asynchronous write is not guaranteed to reach
     * disk before the process dies, which is exactly the case these exist for.
     */
    var initInFlightTokens: Int
        get() = prefs.getInt(KEY_INIT_IN_FLIGHT, 0)
        set(value) = prefs.edit(commit = true) { putInt(KEY_INIT_IN_FLIGHT, value) }

    var generationInFlightTokens: Int
        get() = prefs.getInt(KEY_GENERATION_IN_FLIGHT, 0)
        set(value) = prefs.edit(commit = true) { putInt(KEY_GENERATION_IN_FLIGHT, value) }

    /** Consecutive launches that found a generation marker at the same context size. */
    var generationCrashStreak: Int
        get() = prefs.getInt(KEY_GENERATION_CRASH_STREAK, 0)
        set(value) = prefs.edit(commit = true) { putInt(KEY_GENERATION_CRASH_STREAK, value) }

    /** Reads both crash markers and clears them, so each death is acted on exactly once. */
    fun consumeCrashMarkers(): Pair<Int, Int> {
        val markers = initInFlightTokens to generationInFlightTokens
        if (markers.first != 0 || markers.second != 0) {
            prefs.edit(commit = true) {
                putInt(KEY_INIT_IN_FLIGHT, 0)
                putInt(KEY_GENERATION_IN_FLIGHT, 0)
            }
        }
        return markers
    }

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
        private const val KEY_MANUAL_CONTEXT = "manual_context_tokens"
        private const val KEY_CALIBRATED_CONTEXT = "calibrated_context_tokens"
        private const val KEY_CALIBRATION_KEY = "calibration_key"
        private const val KEY_INIT_IN_FLIGHT = "init_in_flight_tokens"
        private const val KEY_GENERATION_IN_FLIGHT = "generation_in_flight_tokens"
        private const val KEY_GENERATION_CRASH_STREAK = "generation_crash_streak"
        private const val KEY_MAX_OUTPUT = "max_output_tokens"
        private const val KEY_REPETITION_PENALTY = "repetition_penalty"
        private const val KEY_REPETITION_WINDOW = "repetition_window"

        const val DEFAULT_MAX_OUTPUT = 2048
        const val DEFAULT_REPETITION_PENALTY = 1.1f
        const val DEFAULT_REPETITION_WINDOW = 256

        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant running entirely on the user's device."
    }
}
