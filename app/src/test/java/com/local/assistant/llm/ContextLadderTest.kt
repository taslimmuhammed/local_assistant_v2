package com.local.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextLadderTest {

    private fun gb(value: Double): Long = (value * (1L shl 30)).toLong()

    @Test
    fun `ladder is strictly descending`() {
        assertEquals(ContextLadder.RUNGS.sortedDescending(), ContextLadder.RUNGS)
        assertEquals(ContextLadder.RUNGS.distinct(), ContextLadder.RUNGS)
    }

    @Test
    fun `starting rung scales with installed memory`() {
        assertEquals(4096, ContextLadder.startingRung(gb(3.0)))
        assertEquals(8192, ContextLadder.startingRung(gb(6.0)))
        assertEquals(12288, ContextLadder.startingRung(gb(10.0)))
        assertEquals(32768, ContextLadder.startingRung(gb(32.0)))
    }

    /**
     * Measured: this device confirms 16384 and is killed at 24576, so starting here costs no
     * crash. Overshooting is not free — the process dies before the guard can step down.
     */
    @Test
    fun `a 15GB device starts where it will finish`() {
        assertEquals(16384, ContextLadder.startingRung(gb(15.5)))
    }

    @Test
    fun `rungs to try start no higher than the estimate`() {
        val rungs = ContextLadder.rungsToTry(start = 16384)
        assertEquals(16384, rungs.first())
        assertTrue(rungs.all { it <= 16384 })
    }

    /** The crash guard's contract: a size that killed the process is never offered again. */
    @Test
    fun `a known bad size and everything above it is skipped`() {
        val rungs = ContextLadder.rungsToTry(start = 32768, knownBad = 16384)
        assertTrue(rungs.none { it >= 16384 })
        assertEquals(12288, rungs.first())
    }

    @Test
    fun `everything being known bad leaves nothing to try`() {
        assertTrue(ContextLadder.rungsToTry(start = 32768, knownBad = 2048).isEmpty())
    }

    @Test
    fun `next below walks down one rung at a time`() {
        assertEquals(24576, ContextLadder.nextBelow(32768))
        assertEquals(2048, ContextLadder.nextBelow(4096))
        assertNull(ContextLadder.nextBelow(2048))
    }

    @Test
    fun `an arbitrary reported ceiling snaps down to a rung`() {
        assertEquals(8192, ContextLadder.snapDown(9000))
        assertEquals(8192, ContextLadder.snapDown(8192))
        assertEquals(2048, ContextLadder.snapDown(100))
    }
}
