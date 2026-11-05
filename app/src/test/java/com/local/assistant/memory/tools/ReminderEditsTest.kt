package com.local.assistant.memory.tools

import com.local.assistant.location.GeoPoint
import com.local.assistant.location.movedTo
import com.local.assistant.location.onlyAt
import com.local.assistant.location.resized
import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.tools.ReminderEdits.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderEditsTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val store = FakeMemoryStore { now }
    private val reminders = FakeReminders()
    private val edits = ReminderEdits(store, reminders, clock = { now }, townAt = { "Kozhikode" })

    private val dmart = TaskPlace("DMart", listOf(PlaceSpot(12.9889936, 77.7100422, 250, "Hoodi Main Road")))

    private fun task(dueAt: Long? = null, place: TaskPlace? = null, repeat: String? = null): TaskEntity = runBlocking {
        val id = store.insertTask(TaskEntity(title = "Buy milk", dueAt = dueAt, repeatRule = repeat, place = place, createdAt = now, updatedAt = now))
        when {
            place != null -> reminders.schedulePlace(id, place)
            dueAt != null -> reminders.schedule(id, dueAt)
        }
        store.tasks[id]!!
    }

    private fun edit(task: TaskEntity, title: String = task.title, at: Long? = task.dueAt, place: TaskPlace? = null) =
        runBlocking { edits.edit(task, title, at, place) }

    private fun within(m: Int) = PlaceDistance(m, beforeArriving = false)

    private fun saved(outcome: Outcome) = (outcome as? Outcome.Saved)?.task ?: error("refused: $outcome")

    private fun refused(outcome: Outcome) = (outcome as? Outcome.Refused)?.why ?: error("saved: $outcome")

    @Test
    fun `new words keep when it rings`() {
        val milk = task(dueAt = now + hour)

        val edited = saved(edit(milk, "Buy oat milk"))

        assertEquals("Buy oat milk", store.tasks[milk.id]!!.title)
        assertEquals(now + hour, edited.dueAt)
        assertEquals(now + hour, reminders.scheduled[milk.id])
    }

    @Test
    fun `a new time moves the alarm`() {
        val milk = task(dueAt = now + hour)

        edit(milk, at = now + 3 * hour)

        assertEquals(now + 3 * hour, store.tasks[milk.id]!!.dueAt)
        assertEquals(now + 3 * hour, reminders.scheduled[milk.id])
    }

    @Test
    fun `a time already gone is refused, and nothing changes`() {
        val milk = task(dueAt = now + hour)

        val why = refused(edit(milk, "Buy oat milk", now - hour))

        assertTrue(why, "passed" in why)
        assertEquals(milk, store.tasks[milk.id])
        assertEquals(now + hour, reminders.scheduled[milk.id])
    }

    @Test
    fun `a reminder that already rang can be renamed without picking a new time`() {
        val rang = task(dueAt = now - hour)

        saved(edit(rang, "Buy oat milk"))

        assertEquals("Buy oat milk", store.tasks[rang.id]!!.title)
        assertNull(reminders.scheduled[rang.id])
    }

    @Test
    fun `a place reminder is renamed and still rings at its place`() {
        val milk = task(place = dmart)

        val edited = saved(edit(milk, "Buy oat milk", at = now + hour))

        assertEquals("Buy oat milk", edited.title)
        assertEquals(dmart, edited.place)
        assertNull(edited.dueAt)
        assertEquals(dmart, reminders.places[milk.id])
        assertNull(reminders.scheduled[milk.id])
    }

    @Test
    fun `a place reminder's circle can be made bigger or smaller, and its fences follow`() {
        val milk = task(place = dmart)

        val edited = saved(edit(milk, place = dmart.resized(within(500), dmart)))

        assertEquals(listOf(500), edited.place!!.spots.map { it.radiusM })
        assertEquals("within 500 m of DMart", edited.place!!.where())
        assertEquals(edited.place, reminders.places[milk.id])
        // Back to the size it was found at: back to its own wording too.
        assertEquals(dmart, saved(edit(edited, place = edited.place!!.resized(within(250), dmart))).place)
    }

    @Test
    fun `a reminder that rings before arriving keeps each spot's own size`() {
        val munnar = TaskPlace(
            "Munnar",
            listOf(PlaceSpot(10.0889, 77.0595, 9000, "Munnar"), PlaceSpot(10.1, 77.07, 6000, "Old Munnar")),
            distance = PlaceDistance(5000, beforeArriving = true),
        )
        val trip = task(place = munnar)

        val edited = saved(edit(trip, place = munnar.resized(PlaceDistance(10_000, beforeArriving = true), munnar)))

        assertEquals(listOf(14_000, 11_000), edited.place!!.spots.map { it.radiusM })
        assertEquals("10 km before Munnar", edited.place!!.where())
    }

    @Test
    fun `a moved pin rings where it was put`() {
        val milk = task(place = dmart)
        // About 2 km north: off Hoodi Main Road, so it's named by its town.
        val moved = dmart.movedTo(0, GeoPoint(13.0070, 77.7100))

        val edited = saved(edit(milk, place = moved))

        val spot = edited.place!!.spots.single()
        assertEquals(13.0070, spot.lat, 0.0)
        assertEquals("Kozhikode", spot.label)
        assertEquals("DMart", edited.place!!.name)
        assertEquals(edited.place, reminders.places[milk.id])
    }

    @Test
    fun `a pin nudged onto the right building keeps its street`() {
        val milk = task(place = dmart)

        val edited = saved(edit(milk, place = dmart.movedTo(0, GeoPoint(12.9899, 77.7101))))

        assertEquals("Hoodi Main Road", edited.place!!.spots.single().label)
    }

    @Test
    fun `nothing changed on the map is no change`() {
        val milk = task(place = dmart)

        assertEquals(dmart, saved(edit(milk, "Buy oat milk", place = dmart)).place)
        assertEquals(dmart, saved(edit(milk, place = null)).place)
    }

    @Test
    fun `a circle too small to notice arrivals, too far out, a pin added or the last one taken away, is refused`() {
        val milk = task(place = dmart)

        assertTrue("100 m" in refused(edit(milk, place = dmart.resized(within(50), dmart))))
        assertTrue("100 km" in refused(edit(milk, place = dmart.resized(PlaceDistance(200_000, beforeArriving = true), dmart))))
        assertTrue("100 km" in refused(edit(milk, place = dmart.resized(within(200_000), dmart))))
        assertTrue("Delete" in refused(edit(milk, place = dmart.copy(spots = emptyList()))))
        assertTrue("not added" in refused(edit(milk, place = dmart.copy(spots = dmart.spots + dmart.spots))))
        assertEquals(milk, store.tasks[milk.id])
        assertEquals(dmart, reminders.places[milk.id])
    }

    @Test
    fun `one of several pins can be kept alone, with its own street`() {
        // Made before a town and its taluk were told apart: the taluk's middle first, then the town.
        val koduvally = TaskPlace(
            "Koduvally",
            listOf(PlaceSpot(11.3722139, 75.9222322, 3000, "Taluk"), PlaceSpot(11.3594537, 75.9119868, 3000, "Town")),
        )
        val chicken = task(place = koduvally)

        val edited = saved(edit(chicken, place = koduvally.onlyAt(1)))

        assertEquals(listOf(PlaceSpot(11.3594537, 75.9119868, 3000, "Town")), edited.place!!.spots)
        assertEquals(edited.place, reminders.places[chicken.id])
        runBlocking { edits.putBack(chicken) }
        assertEquals(koduvally, reminders.places[chicken.id])
    }

    @Test
    fun `an undo puts the pin and the circle back`() {
        val milk = task(place = dmart)
        saved(edit(milk, place = dmart.movedTo(0, GeoPoint(13.0, 77.72)).resized(within(2000), dmart)))

        runBlocking { edits.putBack(milk) }

        assertEquals(milk, store.tasks[milk.id])
        assertEquals(dmart, reminders.places[milk.id])
    }

    @Test
    fun `a repeating reminder keeps repeating from its new time, and stops with none`() {
        val gym = task(dueAt = now + hour, repeat = "FREQ=DAILY")

        assertEquals("FREQ=DAILY", saved(edit(gym, at = now + 2 * hour)).repeatRule)
        assertNull(saved(edit(gym, at = null)).repeatRule)
        assertNull(reminders.scheduled[gym.id])
    }

    @Test
    fun `an undo puts the reminder back as it was, ringing as it did`() {
        val milk = task(dueAt = now + hour)
        saved(edit(milk, "Buy oat milk", now + 3 * hour))

        runBlocking { edits.putBack(milk) }

        assertEquals(milk, store.tasks[milk.id])
        assertEquals(now + hour, reminders.scheduled[milk.id])
    }

    @Test
    fun `a reminder needs words`() {
        val milk = task(dueAt = now + hour)

        assertTrue("reminded" in refused(edit(milk, "  ")))
        assertEquals(milk, store.tasks[milk.id])
    }
}
