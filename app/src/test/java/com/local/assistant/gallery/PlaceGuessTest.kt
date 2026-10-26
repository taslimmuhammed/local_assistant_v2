package com.local.assistant.gallery

import com.local.assistant.gallery.places.PlaceGuess
import com.local.assistant.gallery.places.PlaceGuess.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceGuessTest {

    private val hour = 3_600_000L
    private val morning = 1_790_000_000_000L

    // Two photos in Munnar four hours apart, one in Kochi (85 km away) that evening.
    private val munnarAm = Fix(1, morning, 10.0889, 77.0595)
    private val munnarPm = Fix(2, morning + 4 * hour, 10.10, 77.06)
    private val kochi = Fix(3, morning + 9 * hour, 9.9399, 76.2602)
    private val located = listOf(munnarAm, munnarPm, kochi)

    private fun guess(vararg unplaced: Pair<Long, Long>) =
        PlaceGuess.guess(unplaced.toMap(), located, located.map { it.takenAt } + unplaced.map { it.second })

    @Test
    fun `between two photos of one place, it is there`() {
        assertEquals(munnarAm, guess(10L to morning + hour)[10L])
        assertEquals(munnarPm, guess(10L to morning + 3 * hour)[10L])
    }

    @Test
    fun `between two places far apart, only one close in time says where`() {
        // Six hours after Munnar, three before Kochi: on the road, no idea.
        assertNull(guess(10L to morning + 6 * hour)[10L])
        // Half an hour before Kochi.
        assertEquals(kochi, guess(10L to morning + 9 * hour - 30 * 60_000)[10L])
    }

    @Test
    fun `long after the last located photo, nothing`() {
        assertNull(guess(10L to morning + 12 * hour)[10L])
    }

    @Test
    fun `photos dated in a bunch to the second are copies, left alone`() {
        val at = morning + hour
        assertEquals(emptyMap<Long, Fix>(), guess(10L to at, 11L to at + 1, 12L to at + 2, 13L to at + 3))
    }
}
