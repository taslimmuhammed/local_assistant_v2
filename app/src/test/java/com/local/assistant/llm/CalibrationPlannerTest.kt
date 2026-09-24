package com.local.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPlannerTest {

    private fun snapshot(
        manualOverride: Int = 0,
        calibratedTokens: Int = 0,
        calibratedKey: String? = null,
        currentKey: String = "model-gpu-mtp",
        initInFlight: Int = 0,
        generationInFlight: Int = 0,
        generationCrashStreak: Int = 0,
        totalMemoryBytes: Long = 16L shl 30,
        ceilingTokens: Int = Int.MAX_VALUE,
    ) = CalibrationSnapshot(
        manualOverride = manualOverride,
        calibratedTokens = calibratedTokens,
        calibratedKey = calibratedKey,
        currentKey = currentKey,
        initInFlight = initInFlight,
        generationInFlight = generationInFlight,
        generationCrashStreak = generationCrashStreak,
        totalMemoryBytes = totalMemoryBytes,
        ceilingTokens = ceilingTokens,
    )

    @Test
    fun `a manual override wins outright`() {
        val decision = CalibrationPlanner.decide(snapshot(manualOverride = 12288, calibratedTokens = 4096))
        assertEquals(CalibrationDecision.UseKnown(12288), decision)
    }

    @Test
    fun `a confirmed calibration is reused`() {
        val decision = CalibrationPlanner.decide(
            snapshot(calibratedTokens = 16384, calibratedKey = "model-gpu-mtp"),
        )
        assertEquals(CalibrationDecision.UseKnown(16384), decision)
    }

    @Test
    fun `changing the model or backend invalidates the calibration`() {
        val decision = CalibrationPlanner.decide(
            snapshot(calibratedTokens = 16384, calibratedKey = "other-model-cpu"),
        )
        assertTrue(decision is CalibrationDecision.Probe)
    }

    @Test
    fun `an unseen device probes from the memory-derived rung`() {
        val decision = CalibrationPlanner.decide(snapshot(totalMemoryBytes = 10L shl 30))
        assertEquals(12288, (decision as CalibrationDecision.Probe).rungs.first())
    }

    /** The whole point of the guard: the process died and none of our cleanup ran. */
    @Test
    fun `a death during init rules out that size and everything above it`() {
        val decision = CalibrationPlanner.decide(snapshot(initInFlight = 16384))
        val rungs = (decision as CalibrationDecision.Probe).rungs
        assertTrue(rungs.none { it >= 16384 })
        assertEquals(12288, rungs.first())
    }

    @Test
    fun `a death mid-generation at an uncalibrated size rules it out`() {
        val decision = CalibrationPlanner.decide(snapshot(generationInFlight = 8192))
        assertTrue((decision as CalibrationDecision.Probe).rungs.none { it >= 8192 })
    }

    @Test
    fun `the lower of the two crash markers wins`() {
        val decision = CalibrationPlanner.decide(
            snapshot(initInFlight = 16384, generationInFlight = 8192),
        )
        assertTrue((decision as CalibrationDecision.Probe).rungs.none { it >= 8192 })
    }

    @Test
    fun `a crash above the calibrated size does not discard the calibration`() {
        val decision = CalibrationPlanner.decide(
            snapshot(
                calibratedTokens = 8192,
                calibratedKey = "model-gpu-mtp",
                initInFlight = 16384,
            ),
        )
        assertEquals(CalibrationDecision.UseKnown(8192), decision)
    }

    /**
     * Swiping the app away mid-reply leaves the same trace as an out-of-memory kill, so the
     * first one must not shrink the window — it only earns a re-check at the same size.
     */
    @Test
    fun `one death at the calibrated size re-checks that size rather than shrinking`() {
        val decision = CalibrationPlanner.decide(
            snapshot(
                calibratedTokens = 8192,
                calibratedKey = "model-gpu-mtp",
                generationInFlight = 8192,
                generationCrashStreak = 1,
            ),
        )
        assertEquals(8192, (decision as CalibrationDecision.Probe).rungs.first())
    }

    @Test
    fun `a repeated death at the calibrated size does shrink the window`() {
        val decision = CalibrationPlanner.decide(
            snapshot(
                calibratedTokens = 8192,
                calibratedKey = "model-gpu-mtp",
                generationInFlight = 8192,
                generationCrashStreak = CalibrationPlanner.REPEATS_BEFORE_FATAL,
            ),
        )
        assertTrue((decision as CalibrationDecision.Probe).rungs.none { it >= 8192 })
    }

    /** An init-time death is unambiguous — nothing but the load was running. */
    @Test
    fun `a death during init shrinks immediately without a second chance`() {
        val decision = CalibrationPlanner.decide(
            snapshot(
                calibratedTokens = 8192,
                calibratedKey = "model-gpu-mtp",
                initInFlight = 8192,
            ),
        )
        assertTrue((decision as CalibrationDecision.Probe).rungs.none { it >= 8192 })
    }

    @Test
    fun `crashing at the smallest rung leaves nothing to try`() {
        assertEquals(
            CalibrationDecision.Exhausted,
            CalibrationPlanner.decide(snapshot(initInFlight = ContextLadder.SMALLEST)),
        )
    }

    // --- reading the runtime's own stated ceiling ------------------------------------------

    @Test
    fun `the reported maximum is parsed out of the runtime error`() {
        val message = "INVALID_ARGUMENT: Input token ids are too long. " +
            "Exceeding the maximum number of tokens allowed: 8192"
        assertEquals(8192, CalibrationPlanner.reportedMaxTokens(message))
    }

    @Test
    fun `an unrelated error reports nothing`() {
        assertNull(CalibrationPlanner.reportedMaxTokens("Failed to create engine: RESOURCE_EXHAUSTED"))
        assertNull(CalibrationPlanner.reportedMaxTokens(null))
    }

    // ---- Ceiling: leaving memory for the models that run alongside the LLM ----

    @Test
    fun `a device confirmed above the ceiling runs at the ceiling without re-probing`() {
        val decision = CalibrationPlanner.decide(
            snapshot(calibratedTokens = 16384, calibratedKey = "model-gpu-mtp", ceilingTokens = 8192),
        )
        assertEquals(CalibrationDecision.UseKnown(8192), decision)
    }

    @Test
    fun `probing never starts above the ceiling`() {
        val decision = CalibrationPlanner.decide(snapshot(ceilingTokens = 8192))
        assertEquals(CalibrationDecision.Probe(listOf(8192, 6144, 4096, 2048)), decision)
    }

    @Test
    fun `one death at the capped size re-checks it rather than shrinking`() {
        val decision = CalibrationPlanner.decide(
            snapshot(
                calibratedTokens = 16384,
                calibratedKey = "model-gpu-mtp",
                ceilingTokens = 8192,
                generationInFlight = 8192,
                generationCrashStreak = 1,
            ),
        )
        assertEquals(CalibrationDecision.Probe(listOf(8192, 6144, 4096, 2048)), decision)
    }

    @Test
    fun `an init death at the capped size shrinks below it`() {
        val decision = CalibrationPlanner.decide(
            snapshot(calibratedTokens = 16384, calibratedKey = "model-gpu-mtp", ceilingTokens = 8192, initInFlight = 8192),
        )
        assertEquals(CalibrationDecision.Probe(listOf(6144, 4096, 2048)), decision)
    }

    @Test
    fun `a manual override is the user's call, ceiling or not`() {
        val decision = CalibrationPlanner.decide(snapshot(manualOverride = 16384, ceilingTokens = 8192))
        assertEquals(CalibrationDecision.UseKnown(16384), decision)
    }
}
