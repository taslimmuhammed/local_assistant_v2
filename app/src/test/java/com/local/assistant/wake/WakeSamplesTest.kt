package com.local.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeSamplesTest {

    /** 100 ms loudness readings: [n] of [rms]. */
    private fun WakeSamples.Bursts.feed(rms: Float, n: Int) = repeat(n) { add(rms) }

    @Test
    fun `counts each phrase said with a pause after it`() {
        val bursts = WakeSamples.Bursts()
        bursts.feed(0.002f, 10)
        repeat(3) {
            bursts.feed(0.08f, 9)
            bursts.feed(0.002f, 12)
        }
        assertEquals(3, bursts.count)
    }

    @Test
    fun `ignores clicks and long speeches`() {
        val bursts = WakeSamples.Bursts()
        bursts.feed(0.002f, 10)
        bursts.feed(0.08f, 1)
        bursts.feed(0.002f, 10)
        bursts.feed(0.08f, 60)
        bursts.feed(0.002f, 10)
        assertEquals(0, bursts.count)
    }

    @Test
    fun `waits for the pause before counting`() {
        val bursts = WakeSamples.Bursts()
        bursts.feed(0.002f, 10)
        bursts.feed(0.08f, 9)
        bursts.feed(0.002f, 2)
        assertEquals(0, bursts.count)
        bursts.feed(0.002f, 2)
        assertEquals(1, bursts.count)
    }

    @Test
    fun `follows a noisy room's background`() {
        val bursts = WakeSamples.Bursts()
        bursts.feed(0.02f, 60)
        // Speech well above the fan is counted; the fan itself never is.
        bursts.feed(0.15f, 9)
        bursts.feed(0.02f, 12)
        assertEquals(1, bursts.count)
    }
}
