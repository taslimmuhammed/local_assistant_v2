package com.local.assistant.memory.extract

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.tools.FakeMemoryStore
import com.local.assistant.memory.tools.FakeReminders
import com.local.assistant.memory.tools.WhenResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExtractionRouterTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Monday 21 Sep 2026, 10:00. */
    private val now = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()

    private val store = FakeMemoryStore(clock = { nowMs })
    private val reminders = FakeReminders()
    private val router = ExtractionRouter(store, reminders, zone = { zone }, clock = { nowMs })

    private fun message(id: Long, at: ZonedDateTime, text: String = "…") =
        MessageEntity(id = id, chatId = 1, role = Role.USER, text = text, createdAt = at.toInstant().toEpochMilli())

    private fun fact(source: Long, subject: String, attribute: String, value: String, core: Boolean = false) =
        ExtractedItem(ExtractedItem.Type.FACT, source, subject, attribute, value, core)

    @Test
    fun factsAreStoredAsExtractedAndStatedWhenTheMessageWas() = runBlocking {
        val said = now.minusDays(3)
        val outcome = router.route(listOf(fact(7, "my mother", "Birthday", "12 March")), mapOf(7L to message(7, said)))
        assertEquals(1, outcome.facts)
        val stored = store.facts.values.single()
        assertEquals("mother", stored.subject)
        assertEquals("birthday", stored.attribute)
        assertEquals(FactOrigin.EXTRACTED, stored.origin)
        assertEquals(said.toInstant().toEpochMilli(), stored.statedAt)
        assertEquals(7L, stored.sourceMessageId)
    }

    @Test
    fun itemsPointingOutsideTheBatchAreIgnored() = runBlocking {
        val outcome = router.route(listOf(fact(99, "user", "city", "Pune")), mapOf(7L to message(7, now.minusDays(1))))
        assertEquals(ExtractionRouter.Outcome(skipped = 1), outcome)
        assertTrue(store.facts.isEmpty())
    }

    @Test
    fun onlyReplyPreferencesMayBeCore() = runBlocking {
        val m = mapOf(1L to message(1, now.minusDays(1)))
        router.route(
            listOf(
                fact(1, "user", "pref.reply_style", "short answers", core = true),
                fact(1, "user", "city", "Kochi", core = true),
                fact(1, "mother", "pref.language", "Malayalam", core = true),
            ),
            m,
        )
        val byAttribute = store.facts.values.associateBy { it.subject + "." + it.attribute }
        assertTrue(byAttribute.getValue("user.pref.reply_style").core)
        assertFalse(byAttribute.getValue("user.city").core)
        assertFalse(byAttribute.getValue("mother.pref.language").core)
    }

    @Test
    fun aForgottenFactIsNotLearnedAgainFromOlderMessages() = runBlocking {
        store.saveFact("user", "dentist", "Dr. Rao", FactOrigin.CHAT)
        store.forgetFacts("user", "dentist") // Tombstone at now.
        val older = router.route(listOf(fact(3, "user", "dentist", "Dr. Rao")), mapOf(3L to message(3, now.minusDays(2))))
        assertEquals(ExtractionRouter.Outcome(skipped = 1), older)
        assertTrue(store.facts.isEmpty())
    }

    @Test
    fun anEditOnTheMemoryScreenIsNeverOverwritten() = runBlocking {
        store.saveFact("user", "city", "Bengaluru", FactOrigin.USER_EDIT, statedAt = nowMs - 5 * DAY)
        val outcome = router.route(listOf(fact(4, "user", "city", "Kochi")), mapOf(4L to message(4, now.minusDays(1))))
        assertEquals(0, outcome.facts)
        assertEquals("Bengaluru", store.facts.values.single().value)
    }

    @Test
    fun anOlderStatementDoesNotReplaceANewerOne() = runBlocking {
        store.saveFact("user", "city", "Kochi", FactOrigin.CHAT, statedAt = nowMs - DAY)
        router.route(listOf(fact(5, "user", "city", "Pune")), mapOf(5L to message(5, now.minusDays(10))))
        assertEquals("Kochi", store.facts.values.single().value)
    }

    @Test
    fun tasksResolveAgainstTheDayTheyWereSaid() = runBlocking {
        // Said last evening: "tomorrow at 11" is this morning, still ahead.
        val said = now.minusDays(1).withHour(18)
        val task = ExtractedItem(ExtractedItem.Type.TASK, 8, title = "call the bank", whenText = "tomorrow at 11")
        val outcome = router.route(listOf(task), mapOf(8L to message(8, said)))
        assertEquals(1, outcome.tasks)
        val stored = store.tasks.values.single()
        assertEquals("Call the bank", stored.title)
        assertEquals(now.withHour(11).toInstant().toEpochMilli(), stored.dueAt)
        assertEquals(stored.dueAt, reminders.scheduled[stored.id])
    }

    @Test
    fun pastUndatedAndDuplicateTasksAreSkipped() = runBlocking {
        val past = ExtractedItem(ExtractedItem.Type.TASK, 1, title = "Pay rent", whenText = "tomorrow at 9")
        val undated = ExtractedItem(ExtractedItem.Type.TASK, 1, title = "Renew passport")
        store.insertTask(TaskEntity(title = "Call the CA", dueAt = now.plusDays(1).withHour(11).toInstant().toEpochMilli(), createdAt = 0, updatedAt = 0))
        val duplicate = ExtractedItem(ExtractedItem.Type.TASK, 2, title = "call the CA.", whenText = "tomorrow at 11:30")
        val outcome = router.route(
            listOf(past, undated, duplicate),
            mapOf(1L to message(1, now.minusDays(20)), 2L to message(2, now.minusHours(1))),
        )
        assertEquals(ExtractionRouter.Outcome(skipped = 3), outcome)
        assertEquals(1, store.tasks.size)
        assertTrue(reminders.scheduled.isEmpty())
    }

    @Test
    fun eventsGetTheirAlertAndPastOnesAreSkipped() = runBlocking {
        val said = now.minusDays(1)
        val ahead = ExtractedItem(ExtractedItem.Type.EVENT, 3, title = "Dentist appointment", whenText = "Friday at 5")
        val gone = ExtractedItem(ExtractedItem.Type.EVENT, 4, title = "Wedding", whenText = "on 2 September")
        val outcome = router.route(listOf(ahead, gone), mapOf(3L to message(3, said), 4L to message(4, said)))
        assertEquals(1, outcome.events)
        val event: EventEntity = store.events.values.single()
        val expected = WhenResolver().resolve("Friday at 5", said)!!.at
        assertEquals(expected.toInstant().toEpochMilli(), event.startsAt)
        assertEquals(event.startsAt - 30 * 60_000L, reminders.eventAlerts[event.id])
    }

    @Test
    fun theSameEventTwiceIsAddedOnce() = runBlocking {
        val said = now.minusHours(2)
        val first = ExtractedItem(ExtractedItem.Type.EVENT, 5, title = "Team dinner", whenText = "tomorrow at 8pm")
        val again = ExtractedItem(ExtractedItem.Type.EVENT, 6, title = "team dinner", whenText = "tomorrow at 8:30pm")
        router.route(listOf(first, again), mapOf(5L to message(5, said), 6L to message(6, said)))
        assertEquals(1, store.events.size)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
