package com.local.assistant.reminders

import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceAlertsTest {

    private val now = 1_800_000_000_000L
    private val place = TaskPlace("DMart", listOf(PlaceSpot(12.99, 77.71, 250, "Hoodi Main Road")))
    private val task = TaskEntity(id = 42, title = "Buy milk", createdAt = now, updatedAt = now, place = place)

    @Test
    fun `a fence's name says its reminder and spot`() {
        assertEquals("place:42:3", PlaceAlerts.fenceId(42, 3))
        assertEquals(42L to 3, PlaceAlerts.spotOf(PlaceAlerts.fenceId(42, 3)))
        assertNull(PlaceAlerts.spotOf("something:else"))
        assertNull(PlaceAlerts.spotOf("place:x:1"))
    }

    @Test
    fun `an open place reminder rings on arriving`() {
        assertTrue(PlaceAlerts.shouldRing(task, now))
    }

    @Test
    fun `arriving again soon after ringing is the same visit`() {
        val rang = task.copy(place = place.copy(rangAt = now - 10 * 60_000L))
        assertFalse(PlaceAlerts.shouldRing(rang, now))
        assertTrue(PlaceAlerts.shouldRing(rang, now + PlaceAlerts.QUIET_MS))
    }

    @Test
    fun `once it rings it is over, and next time opens it again for another visit`() {
        val rang = PlaceAlerts.rang(task, now)
        assertEquals(TaskStatus.DONE, rang.status)
        assertEquals(now, rang.completedAt)
        assertEquals(now, rang.place!!.rangAt)
        assertFalse(PlaceAlerts.shouldRing(rang, now + PlaceAlerts.QUIET_MS))

        val again = PlaceAlerts.nextTime(rang, now + 60_000L)!!
        assertEquals(TaskStatus.OPEN, again.status)
        assertNull(again.completedAt)
        assertEquals(place.spots, again.place!!.spots)
        // Not on the visit it rang on: on the next one.
        assertFalse(PlaceAlerts.shouldRing(again, now + 60_000L))
        assertTrue(PlaceAlerts.shouldRing(again, now + PlaceAlerts.QUIET_MS))
    }

    @Test
    fun `next time does nothing for a reminder deleted since, or one at a time`() {
        assertNull(PlaceAlerts.nextTime(task.copy(status = TaskStatus.CANCELLED), now))
        assertNull(PlaceAlerts.nextTime(task.copy(place = null, dueAt = now), now))
    }

    @Test
    fun `done, cancelled or timed reminders don't ring at a place`() {
        assertFalse(PlaceAlerts.shouldRing(task.copy(status = TaskStatus.DONE), now))
        assertFalse(PlaceAlerts.shouldRing(task.copy(status = TaskStatus.CANCELLED), now))
        assertFalse(PlaceAlerts.shouldRing(task.copy(place = null, dueAt = now), now))
    }
}
