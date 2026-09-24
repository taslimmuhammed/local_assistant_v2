package com.local.assistant.llm

import android.app.ActivityManager
import android.util.Log
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What calibration is doing right now, for the progress line on the model screen. */
sealed interface CalibrationProgress {
    data class Trying(val tokens: Int, val step: Int, val steps: Int) : CalibrationProgress
    data class Confirming(val tokens: Int, val reached: Int) : CalibrationProgress
}

/**
 * Finds the largest context window this device can actually hold.
 *
 * Nothing in the runtime reports this. `initialize()` succeeding is not proof either — if the
 * model's KV cache is allocated lazily, a size can load cleanly and then kill the process once a
 * conversation grows into it. So a size only counts once a generation has filled the window and
 * come back.
 *
 * Because a native OOM kills the process with no exception, the size under test is written to
 * disk *before* each attempt and cleared after. A marker still present at the next launch is the
 * only evidence that a size was fatal.
 */
class ContextCalibrator(
    private val settings: SettingsStore,
    private val activityManager: ActivityManager,
) {

    /** A loaded engine and the window it is running with. */
    data class Outcome(val engine: Engine, val tokens: Int, val confirmed: Boolean)

    private val _progress = MutableStateFlow<CalibrationProgress?>(null)
    val progress: StateFlow<CalibrationProgress?> = _progress.asStateFlow()

    /**
     * Crash evidence for this load, read once. Reading it per backend would let the first
     * backend consume the marker and the rest start from the top as if nothing had happened.
     */
    private var sessionInitInFlight = 0
    private var sessionGenerationInFlight = 0

    /** Held so a long confirmation can be interrupted; the native call ignores coroutine cancel. */
    @Volatile
    private var activeConversation: com.google.ai.edge.litertlm.Conversation? = null

    @Volatile
    private var cancelled = false

    /** Call once per engine load, before trying any backend. */
    fun beginSession() {
        cancelled = false
        val (initInFlight, generationInFlight) = settings.consumeCrashMarkers()
        sessionInitInFlight = initInFlight
        sessionGenerationInFlight = generationInFlight
        settings.generationCrashStreak =
            if (generationInFlight > 0) settings.generationCrashStreak + 1 else 0
        if (initInFlight != 0 || generationInFlight != 0) {
            Log.w(TAG, "Previous run died at init=$initInFlight generation=$generationInFlight")
        }
    }

    /**
     * Returns a loaded engine, calibrating first if the ceiling for [currentKey] is unknown.
     *
     * [load] builds and initialises an engine at a given context size and may throw.
     */
    fun obtainEngine(currentKey: String, load: (Int) -> Engine): Outcome? = try {
        when (val decision = CalibrationPlanner.decide(snapshot(currentKey))) {
            is CalibrationDecision.UseKnown ->
                loadAt(decision.tokens, load)?.let { Outcome(it, decision.tokens, true) }

            is CalibrationDecision.Probe -> probe(decision.rungs, currentKey, load)

            CalibrationDecision.Exhausted -> null
        }
    } finally {
        _progress.value = null
    }

    /**
     * Interrupts measurement. The native calls do not observe coroutine cancellation, so the
     * in-flight generation is stopped through the runtime's own cancel.
     */
    fun cancel() {
        cancelled = true
        runCatching { activeConversation?.cancelProcess() }
    }

    /** Forgets the stored result so the next load measures again. */
    fun invalidate() {
        settings.calibratedContextTokens = 0
        settings.calibrationKey = null
    }

    private fun snapshot(currentKey: String): CalibrationSnapshot {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return CalibrationSnapshot(
            manualOverride = settings.manualContextTokens,
            calibratedTokens = settings.calibratedContextTokens,
            calibratedKey = settings.calibrationKey,
            currentKey = currentKey,
            initInFlight = sessionInitInFlight,
            generationInFlight = sessionGenerationInFlight,
            generationCrashStreak = settings.generationCrashStreak,
            totalMemoryBytes = memory.totalMem,
            ceilingTokens = settings.contextCeilingTokens,
        )
    }

    private fun probe(rungs: List<Int>, currentKey: String, load: (Int) -> Engine): Outcome? {
        val remaining = ArrayDeque(rungs)
        val attempted = mutableSetOf<Int>()

        while (remaining.isNotEmpty() && !cancelled) {
            val tokens = remaining.removeFirst()
            if (!attempted.add(tokens)) continue

            _progress.value = CalibrationProgress.Trying(tokens, attempted.size, rungs.size)
            val engine = loadAt(tokens, load) ?: continue

            _progress.value = CalibrationProgress.Confirming(tokens, 0)
            when (val result = confirm(engine, tokens)) {
                is Confirmation.Confirmed -> {
                    settings.calibratedContextTokens = tokens
                    settings.calibrationKey = currentKey
                    settings.generationCrashStreak = 0
                    Log.i(TAG, "Calibrated context to $tokens (reached ${result.reachedTokens})")
                    return Outcome(engine, tokens, confirmed = true)
                }

                // The runtime told us its real ceiling, so stop searching blindly and jump there.
                is Confirmation.Clamped -> {
                    val snapped = ContextLadder.snapDown(result.reportedMax)
                    Log.i(TAG, "Runtime reports a ceiling of ${result.reportedMax}; retrying at $snapped")
                    runCatching { engine.close() }
                    if (snapped !in attempted) {
                        remaining.addFirst(snapped)
                    }
                }

                Confirmation.Failed -> runCatching { engine.close() }
            }
        }
        return null
    }

    private fun loadAt(tokens: Int, load: (Int) -> Engine): Engine? {
        settings.initInFlightTokens = tokens
        return try {
            load(tokens)
        } catch (e: Throwable) {
            Log.w(TAG, "Engine init failed at $tokens tokens", e)
            null
        } finally {
            settings.initInFlightTokens = 0
        }
    }

    private sealed interface Confirmation {
        data class Confirmed(val reachedTokens: Int) : Confirmation
        data class Clamped(val reportedMax: Int) : Confirmation
        data object Failed : Confirmation
    }

    /**
     * Fills the window for real and decodes from it. A token or two of warm-up would prove
     * nothing about a cache that grows as it goes, which is the case this exists to catch.
     *
     * The filler is sized by measurement, not by a constant: how many characters make a token
     * depends entirely on the text, and an estimate that is wrong in the safe direction simply
     * never fills the window. So each round reads the real count back and tops up the shortfall.
     *
     * A short fill is never treated as the model capping us. Only the runtime saying so counts —
     * guessing from a shortfall turns a bad estimate into a confident wrong answer.
     */
    private fun confirm(engine: Engine, tokens: Int): Confirmation {
        settings.generationInFlightTokens = tokens
        var conversation: com.google.ai.edge.litertlm.Conversation? = null
        return try {
            conversation = engine.createConversation(
                ConversationConfig(maxOutputToken = CONFIRM_OUTPUT_TOKENS),
            )
            activeConversation = conversation

            val target = (tokens * FILL_FRACTION).toInt()
            var charsPerToken = INITIAL_CHARS_PER_TOKEN
            var charsSent = 0
            var reached = 0

            for (round in 1..MAX_FILL_ROUNDS) {
                if (cancelled || reached >= target) break

                val chars = ((target - reached) * charsPerToken).toInt().coerceAtLeast(MIN_CHUNK_CHARS)
                conversation.sendMessage(filler(chars))
                charsSent += chars

                val counted = runCatching { conversation.getTokenCount() }.getOrDefault(0)
                // No growth means the count is unreadable or the window is not accepting more;
                // either way, looping on it would just waste time.
                if (counted <= reached) break

                reached = counted
                charsPerToken = (charsSent.toDouble() / reached).coerceIn(1.0, MAX_CHARS_PER_TOKEN)
                _progress.value = CalibrationProgress.Confirming(tokens, reached)
                Log.i(TAG, "Fill round $round: $reached/$target tokens (~${"%.1f".format(charsPerToken)} chars/token)")
            }

            Log.i(TAG, "Confirmed $tokens tokens, filled to $reached")
            Confirmation.Confirmed(reached)
        } catch (e: Throwable) {
            val reported = CalibrationPlanner.reportedMaxTokens(e.message)
            when {
                // The runtime naming a limit at or above what we configured confirms the window
                // rather than capping it: we simply ran into our own setting.
                reported != null && reported >= tokens -> Confirmation.Confirmed(reported)
                reported != null -> Confirmation.Clamped(reported)
                else -> {
                    Log.w(TAG, "Confirmation failed at $tokens tokens", e)
                    Confirmation.Failed
                }
            }
        } finally {
            activeConversation = null
            runCatching { conversation?.close() }
            settings.generationInFlightTokens = 0
        }
    }

    /**
     * Varied filler rather than one repeated word: a long run of identical tokens is exactly the
     * input a KV cache is most likely to handle unrepresentatively.
     */
    private fun filler(targetChars: Int): String {
        val builder = StringBuilder(targetChars + 64)
        var index = 0
        while (builder.length < targetChars) {
            builder.append(FILLER_WORDS[index % FILLER_WORDS.size]).append(' ')
            if (index % 12 == 11) builder.append('\n')
            index++
        }
        builder.append("\n\nReply with the single word OK.")
        return builder.toString()
    }

    companion object {
        private const val TAG = "ContextCalibrator"
        private const val CONFIRM_OUTPUT_TOKENS = 16

        /** How much of the window the confirmation fills before calling it proven. */
        private const val FILL_FRACTION = 0.9

        /** Only a starting guess; the real ratio is measured after the first round. */
        private const val INITIAL_CHARS_PER_TOKEN = 4.0
        private const val MAX_CHARS_PER_TOKEN = 16.0
        private const val MAX_FILL_ROUNDS = 5
        private const val MIN_CHUNK_CHARS = 256

        private val FILLER_WORDS = listOf(
            "harbour", "lantern", "gravel", "meadow", "cinder", "tumble", "quartz", "willow",
            "beacon", "thicket", "marble", "drifting", "orchard", "flint", "ripple", "cavern",
        )
    }
}
