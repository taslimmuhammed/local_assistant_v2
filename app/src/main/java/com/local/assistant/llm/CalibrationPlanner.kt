package com.local.assistant.llm

/** Everything the planner needs, gathered from preferences and the system. */
data class CalibrationSnapshot(
    /** Manual override in tokens, or 0 to calibrate automatically. */
    val manualOverride: Int,
    /** Last confirmed size, or 0 if never calibrated. */
    val calibratedTokens: Int,
    /** Identity of the setup [calibratedTokens] was measured on. */
    val calibratedKey: String?,
    /** Identity of the current setup: model file plus backend plus MTP. */
    val currentKey: String,
    /** Size an engine was being initialised at when the process last died. 0 if none. */
    val initInFlight: Int,
    /** Size a generation was running at when the process last died. 0 if none. */
    val generationInFlight: Int,
    /** Consecutive launches that found a generation marker at the same size. */
    val generationCrashStreak: Int,
    /** Consecutive launches that found an init marker. */
    val initCrashStreak: Int = 0,
    val totalMemoryBytes: Long,
    /**
     * The largest window the app will run at, even where the device holds more. Calibration
     * never probes above it: memory not spent on the KV cache is left for the models that run
     * alongside the LLM (the embedder, a voice model).
     */
    val ceilingTokens: Int = Int.MAX_VALUE,
)

sealed interface CalibrationDecision {
    /** Load straight away at this size. */
    data class UseKnown(val tokens: Int) : CalibrationDecision

    /** Walk these sizes, largest first, until one is confirmed. */
    data class Probe(val rungs: List<Int>) : CalibrationDecision

    /** Every size has been ruled out; the model cannot run here. */
    data object Exhausted : CalibrationDecision
}

/**
 * Decides whether we already know the device's context ceiling or have to go looking.
 *
 * Pure so the crash-guard path can be tested: it exists precisely for the case where the process
 * was killed and no code of ours got to run, which is impossible to exercise in an integration
 * test but trivial here.
 */
object CalibrationPlanner {

    fun decide(snapshot: CalibrationSnapshot): CalibrationDecision {
        if (snapshot.manualOverride > 0) return CalibrationDecision.UseKnown(snapshot.manualOverride)

        // A marker left behind means the process died at that size without our cleanup running.
        val initBad = snapshot.initInFlight.takeIf { it > 0 }

        // A generation marker is weaker evidence. Swiping the app away or force-stopping it
        // mid-reply leaves exactly the same trace as an out-of-memory kill, and treating that as
        // fatal would quietly shrink the window every time someone closed the app while it was
        // answering. So the first one only earns a re-check at the same size; it takes a repeat
        // to rule the size out.
        val generationBad = snapshot.generationInFlight.takeIf { it > 0 }

        // The size the app actually runs at: what was confirmed, held to the ceiling. A size
        // below a confirmed one needs no probing of its own.
        val effective = if (snapshot.calibratedTokens > 0) {
            minOf(snapshot.calibratedTokens, snapshot.ceilingTokens)
        } else {
            0
        }
        val generationIsFatal = generationBad != null &&
            (snapshot.generationCrashStreak >= REPEATS_BEFORE_FATAL || generationBad != effective)

        // An init marker at a size never confirmed here is taken at its word: the load itself is
        // what died. At the size already confirmed, it is the same weak evidence as above — an
        // install, a force-stop or a swipe during the few seconds of loading leave the same trace
        // (measured: a phone confirmed at 8K slid to 4K through app updates) — so it earns a re-check.
        val initIsFatal = initBad != null &&
            (snapshot.initCrashStreak >= REPEATS_BEFORE_FATAL || initBad != effective)

        val knownBad = listOfNotNull(initBad.takeIf { initIsFatal }, generationBad.takeIf { generationIsFatal }).minOrNull()

        val calibrationMatches = effective > 0 &&
            snapshot.calibratedKey == snapshot.currentKey &&
            (knownBad == null || effective < knownBad)

        if (calibrationMatches) {
            // Unexplained death at exactly this size: prove it still works before trusting it.
            val needsRecheck = (generationBad != null && !generationIsFatal && generationBad == effective) ||
                (initBad != null && !initIsFatal && initBad == effective)
            return if (needsRecheck) {
                CalibrationDecision.Probe(ContextLadder.rungsToTry(start = effective, knownBad = knownBad))
            } else {
                CalibrationDecision.UseKnown(effective)
            }
        }

        val rungs = ContextLadder.rungsToTry(
            start = minOf(ContextLadder.startingRung(snapshot.totalMemoryBytes), snapshot.ceilingTokens),
            knownBad = knownBad,
        )
        return if (rungs.isEmpty()) CalibrationDecision.Exhausted else CalibrationDecision.Probe(rungs)
    }

    /**
     * The ceiling the runtime states in "Input token ids are too long. Exceeding the maximum
     * number of tokens allowed: N". Reading it turns a slow descending search into one step, and
     * catches the case where the runtime silently clamped what we asked for.
     */
    /** Generation-marker deaths at one size before it is ruled out rather than re-checked. */
    const val REPEATS_BEFORE_FATAL = 2

    private val REPORTED_MAX = Regex("""maximum number of tokens allowed:\s*(\d+)""")

    fun reportedMaxTokens(errorMessage: String?): Int? =
        errorMessage?.let { REPORTED_MAX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
