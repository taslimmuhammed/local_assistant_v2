package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.core.CoreMemoryRenderer
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Test group 3: the model's tool calls, scripted, run through the real loop and executor against
 * in-memory fakes. Fixed clock: Monday 21 September 2026, 10:00, Asia/Kolkata.
 */
class ToolRoutingTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, zone)
    private val store = FakeMemoryStore { now.toInstant().toEpochMilli() }
    private val reminders = FakeReminders()
    private val log = FakeToolLog()
    private val clock = FakeSystemAlarms()
    private val executor = ToolExecutor(store, reminders, clock, log, now = { now })
    private val loop = ToolLoop(executor, maxRounds = 3, isOverflow = { "too long" in it.message.orEmpty() })
    private val context = ToolContext(chatId = 1, userMessageId = 10)

    private fun call(name: String, vararg args: Pair<String, Any?>) = ToolCall(name, mapOf(*args))

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(2026, month, day, hour, minute), zone).toInstant().toEpochMilli()

    /** Runs one turn in which the model makes [calls] and then says [reply]. */
    private fun turn(vararg calls: ToolCall, reply: String = "Done."): Pair<ScriptedSession, List<LoopEvent>> {
        val session = ScriptedSession(ArrayDeque(listOf(ScriptedSession.calls(*calls), ScriptedSession.says(reply))))
        val events = runBlocking { loop.run(session, "envelope", null, context).toList() }
        return session to events
    }

    private fun result(session: ScriptedSession, index: Int = 0): Map<*, *> =
        Gson().fromJson(session.sentResults.first()[index].json, Map::class.java)

    // ---- The brief's three cases ----

    @Test
    fun `remind me to call the CA tomorrow at 11`() {
        val (session, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))

        val task = store.tasks.values.single()
        assertEquals("Call the CA", task.title)
        assertEquals(at(9, 22, 11), task.dueAt)
        assertEquals(mapOf(task.id to at(9, 22, 11)), reminders.scheduled)

        assertEquals(true, result(session)["ok"])
        assertEquals("Tue 22 Sep 11:00", result(session)["due"])
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals(MemoryChip(MemoryChip.Kind.TASK, "Reminder", "Call the CA", at(9, 22, 11), editable = true), chip)
        assertEquals(LoopEvent.Text("Done."), events.filterIsInstance<LoopEvent.Text>().single())
        assertTrue(events.last() is LoopEvent.Done)
    }

    @Test
    fun `my dentist is Dr Rao`() {
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao", "core" to false))

        val fact = store.facts.values.single()
        assertEquals(Triple("user", "dentist", "Dr. Rao"), Triple(fact.subject, fact.attribute, fact.value))
        assertFalse(fact.core)
        assertEquals(FactCategory.PEOPLE, fact.category)
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals("Saved", chip.label)
        assertEquals("Dentist: Dr. Rao", chip.detail)
        assertFalse((events.last() as LoopEvent.Done).changedPrefix)
    }

    @Test
    fun `reply in short answers becomes core and re-renders the prefix`() {
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "pref.reply_style", "value" to "short answers", "core" to true))

        val fact = store.facts.values.single()
        assertEquals("pref.reply_style", fact.attribute)
        assertTrue(fact.core)
        assertEquals(FactCategory.PREFERENCE, fact.category)
        val core = CoreMemoryRenderer(HeuristicTokenEstimator).render(runBlocking { store.coreFacts() }, 1_200)
        assertTrue(core.text, "Preferences: reply style: short answers" in core.text)
        assertTrue("prefix marked dirty", (events.last() as LoopEvent.Done).changedPrefix)
    }

    // ---- Beyond the three ----

    @Test
    fun `hinglish time words resolve the same way`() {
        turn(call("add_task", "title" to "CA ko call karna", "when" to "kal subah 11 baje"))
        assertEquals(at(9, 22, 11), store.tasks.values.single().dueAt)
    }

    @Test
    fun `no time means an undated to-do with no alarm`() {
        val (_, events) = turn(call("add_task", "title" to "renew the passport"))
        assertNull(store.tasks.values.single().dueAt)
        assertTrue(reminders.scheduled.isEmpty())
        assertEquals("To-do", events.filterIsInstance<LoopEvent.Memory>().single().chip.label)
    }

    @Test
    fun `arguments the app cannot use come back as errors, and nothing is written`() {
        val (session, events) = turn(call("add_task", "title" to "call the CA", "when" to "someday"))
        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("someday"))
        assertTrue(store.tasks.isEmpty())
        assertTrue(events.none { it is LoopEvent.Memory })

        val (past, _) = turn(call("add_task", "title" to "call the CA", "when" to "today at 9"))
        assertTrue(result(past)["error"].toString().contains("passed"))

        val (missing, _) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist"))
        assertTrue(result(missing)["error"].toString().contains("value"))
    }

    @Test
    fun `every call is recorded, errors included`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "city", "value" to "Bengaluru"))
        turn(call("add_task", "title" to "x", "when" to "never ever"))
        assertEquals(listOf("save_fact", "add_task"), log.records.values.map { it.tool })
        assertNull(log.records.values.last().chip)
    }

    @Test
    fun `the same call within two minutes is not repeated`() {
        val add = call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11")
        val (first, _) = turn(add)
        now = now.plusSeconds(60)
        val (second, events) = turn(add)
        assertEquals(1, store.tasks.size)
        assertEquals(result(first)["id"], result(second)["id"])
        assertTrue("no second chip", events.none { it is LoopEvent.Memory })

        now = now.plusMinutes(2)
        turn(add)
        assertEquals(2, store.tasks.size)
    }

    @Test
    fun `undo takes a reminder back, alarm and all, exactly once`() {
        val (_, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val recordId = events.filterIsInstance<LoopEvent.Memory>().single().recordId

        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(recordId) })
        assertTrue(store.tasks.isEmpty())
        assertTrue(reminders.scheduled.isEmpty())
        assertTrue(log.records.getValue(recordId).undone)
        assertEquals(ToolExecutor.UndoResult.ALREADY_UNDONE, runBlocking { executor.undo(recordId) })
    }

    @Test
    fun `undo of an updated fact puts the old value back`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao"))
        now = now.plusMinutes(5)
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Mehta"))
        assertEquals("Updated", events.filterIsInstance<LoopEvent.Memory>().single().chip.label)

        runBlocking { executor.undo(events.filterIsInstance<LoopEvent.Memory>().single().recordId) }
        assertEquals("Dr. Rao", store.facts.values.single().value)
    }

    @Test
    fun `undo refuses when the fact has changed since`() {
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao"))
        now = now.plusMinutes(5)
        turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Mehta"))
        val first = events.filterIsInstance<LoopEvent.Memory>().single().recordId
        assertEquals(ToolExecutor.UndoResult.CHANGED_SINCE, runBlocking { executor.undo(first) })
        assertEquals("Dr. Mehta", store.facts.values.single().value)
    }

    @Test
    fun `move the CA call to 3 keeps the reminder's own day`() {
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val (session, events) = turn(call("update_task", "task" to "the CA call", "when" to "3"))
        val task = store.tasks.values.single()
        assertEquals(at(9, 22, 15), task.dueAt)
        assertEquals(at(9, 22, 15), reminders.scheduled[task.id])
        assertEquals("Moved", events.filterIsInstance<LoopEvent.Memory>().single().chip.label)
        assertEquals("Tue 22 Sep 15:00", result(session)["due"])
    }

    @Test
    fun `finishing a repeating reminder moves it to the next time`() {
        turn(call("add_task", "title" to "Submit timesheet", "when" to "monday 9am", "repeat" to "every Monday"))
        val (_, events) = turn(call("update_task", "task" to "timesheet", "status" to "done"))
        val task = store.tasks.values.single()
        assertEquals(TaskStatus.OPEN, task.status)
        assertEquals(at(10, 5, 9), task.dueAt)
        assertEquals("Done, next one set", events.filterIsInstance<LoopEvent.Memory>().single().chip.label)
    }

    @Test
    fun `finishing a one-off reminder closes it and silences it`() {
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        turn(call("update_task", "task" to "1", "status" to "completed"))
        assertEquals(TaskStatus.DONE, store.tasks.values.single().status)
        assertTrue(reminders.scheduled.isEmpty())
    }

    @Test
    fun `an ambiguous reminder is asked about, a specific one is found`() {
        runBlocking {
            store.insertTask(TaskEntity(title = "Call the CA", createdAt = 0, updatedAt = 0))
            store.insertTask(TaskEntity(title = "Call the CA office", createdAt = 0, updatedAt = 0))
        }
        val (ambiguous, _) = turn(call("update_task", "task" to "call CA", "status" to "done"))
        assertTrue(result(ambiguous)["error"].toString().contains("More than one"))

        turn(call("update_task", "task" to "CA office", "status" to "done"))
        assertEquals(TaskStatus.DONE, store.tasks.values.single { it.title == "Call the CA office" }.status)
    }

    @Test
    fun `forget my dentist finds it either way it is asked, and can be undone`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao"))
        val (session, events) = turn(call("forget", "subject" to "dentist"))
        assertEquals(1.0, result(session)["removed"])
        assertTrue(store.facts.isEmpty())
        assertEquals("Forgot", events.filterIsInstance<LoopEvent.Memory>().single().chip.label)

        runBlocking { executor.undo(events.filterIsInstance<LoopEvent.Memory>().single().recordId) }
        assertEquals("Dr. Rao", store.facts.values.single().value)
        assertTrue("tombstone lifted", store.tombstones.isEmpty())
    }

    @Test
    fun `events carry their end, and a date alone is all day`() {
        turn(call("add_event", "title" to "Team meeting", "when" to "tomorrow 10am", "ends" to "11am"))
        val meeting = store.events.values.single()
        assertEquals(at(9, 22, 10), meeting.startsAt)
        assertEquals(at(9, 22, 11), meeting.endsAt)

        turn(call("add_event", "title" to "Wife's birthday", "when" to "12 Oct", "repeat" to "every year"))
        val birthday = store.events.values.last()
        assertTrue(birthday.allDay)
        assertEquals("FREQ=YEARLY", birthday.recurrence)
    }

    @Test
    fun `events alert thirty minutes ahead, all-day ones at eight that morning`() {
        turn(call("add_event", "title" to "Dentist", "when" to "thursday 5pm"))
        val dentist = store.events.values.single()
        assertEquals(at(9, 24, 16, 30), reminders.eventAlerts[dentist.id])

        turn(call("add_event", "title" to "Anniversary", "when" to "friday"))
        val anniversary = store.events.values.last()
        assertEquals(at(9, 25, 8), reminders.eventAlerts[anniversary.id])

        runBlocking { executor.undo(log.records.entries.last().key) }
        assertNull(reminders.eventAlerts[anniversary.id])
    }

    @Test
    fun `a repeating event's alert is for its next occurrence`() {
        turn(call("add_event", "title" to "Standup", "when" to "tomorrow 10am", "repeat" to "every day"))
        val standup = store.events.values.single()
        assertEquals(at(9, 22, 9, 30), reminders.eventAlerts[standup.id])
        val next = EventTimes.nextAlert(standup, at(9, 22, 9, 45), zone, WhenDefaults().dateOnly)!!
        assertEquals(at(9, 23, 9, 30), next.at)
        assertEquals(at(9, 23, 10), next.startsAt)
    }

    // ---- Clock alarms ----

    @Test
    fun `wake me up at 6 sets a clock alarm for the next 6 o'clock`() {
        val (session, events) = turn(call("set_alarm", "when" to "6am", "label" to "wake up"))
        assertEquals(listOf(FakeSystemAlarms.Set(6, 0, emptySet(), "Wake up")), clock.set)
        assertEquals("Tue 22 Sep 06:00", result(session)["alarm"])
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.ALARM, chip.kind)
        assertEquals(at(9, 22, 6), chip.at)
        assertNull("the clock app owns it; no undo from here", log.records.values.single().undo)
    }

    @Test
    fun `wake me up at 5 30 tomorrow means the morning, and a label that just says alarm is dropped`() {
        turn(call("set_alarm", "when" to "tomorrow at 5:30", "label" to "Alarm"))
        assertEquals(listOf(FakeSystemAlarms.Set(5, 30, emptySet(), null)), clock.set)
    }

    @Test
    fun `a weekday alarm repeats on those days`() {
        val (session, events) = turn(call("set_alarm", "when" to "7am", "repeat" to "weekdays"))
        val days = java.time.DayOfWeek.entries.take(5).toSet()
        assertEquals(listOf(FakeSystemAlarms.Set(7, 0, days, null)), clock.set)
        assertEquals("Mon, Tue, Wed, Thu, Fri", result(session)["repeats"])
        assertEquals(at(9, 22, 7), events.filterIsInstance<LoopEvent.Memory>().single().chip.at)
    }

    @Test
    fun `what the clock app cannot do is refused, so the model can offer a reminder`() {
        val (farOff, _) = turn(call("set_alarm", "when" to "friday 6am"))
        assertTrue(result(farOff)["error"].toString().contains("24 hours"))

        val (noTime, _) = turn(call("set_alarm", "when" to "tomorrow"))
        assertTrue(result(noTime)["error"].toString().contains("What time"))

        val (oddRepeat, _) = turn(call("set_alarm", "when" to "6am", "repeat" to "every other day"))
        assertTrue(result(oddRepeat)["error"].toString().contains("days of the week"))
        assertTrue(clock.set.isEmpty())
    }

    @Test
    fun `no clock app, or one that cannot be reached, is an error rather than a false promise`() {
        clock.reachable = false
        val (unreachable, _) = turn(call("set_alarm", "when" to "6am"))
        assertEquals(false, result(unreachable)["ok"])

        clock.present = false
        val (absent, _) = turn(call("set_alarm", "when" to "6am"))
        assertTrue(result(absent)["error"].toString().contains("reminder"))
    }

    @Test
    fun `get upcoming lists what is due, soonest first`() {
        turn(call("add_task", "title" to "Pay rent", "when" to "friday 9am"))
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        runBlocking { store.insertEvent(EventEntity(title = "Dentist", startsAt = at(9, 24, 17), createdAt = 0, updatedAt = 0)) }
        val (session, _) = turn(call("get_upcoming", "days" to 7))
        val titles = (result(session)["items"] as List<*>).map { (it as Map<*, *>)["title"] }
        assertEquals(listOf("Call the CA", "Dentist", "Pay rent"), titles)
    }

    @Test
    fun `search memory finds facts and reminders by their words`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "ca", "value" to "Mr. Iyer"))
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val (session, _) = turn(call("search_memory", "query" to "CA"))
        assertEquals(listOf("CA: Mr. Iyer"), result(session)["facts"])
        assertTrue((result(session)["items"] as List<*>).single().toString().startsWith("Call the CA"))
    }

    @Test
    fun `a model that will not stop calling tools is cut off after three rounds`() {
        val looping = call("get_upcoming")
        val session = ScriptedSession(ArrayDeque(List(6) { ScriptedSession.calls(looping) }))
        val events = runBlocking { loop.run(session, "envelope", null, context).toList() }
        assertEquals(4, session.sentResults.size)
        assertEquals(ToolLoop.LIMIT_REACHED, session.sentResults.last().single().json)
        assertEquals(4, (events.last() as LoopEvent.Done).toolRounds)
    }

    @Test
    fun `a first send refused as too long is reported, not thrown`() {
        val session = ScriptedSession(ArrayDeque(listOf(listOf(GenEvent.Error(IllegalStateException("Input token ids are too long"))))))
        val events = runBlocking { loop.run(session, "envelope", null, context).toList() }
        assertEquals(listOf(LoopEvent.Overflow), events)
    }

    @Test
    fun `without exact alarms the chip says it may be late`() {
        reminders.exact = false
        val (_, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        assertEquals("may be a few minutes late", events.filterIsInstance<LoopEvent.Memory>().single().chip.note)
    }

    @Test
    fun `editing a chip's time moves the reminder and its alarm`() {
        val (_, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val recordId = events.filterIsInstance<LoopEvent.Memory>().single().recordId
        assertTrue(runBlocking { executor.editTime(recordId, at(9, 23, 16)) })
        val task = store.tasks.values.single()
        assertEquals(at(9, 23, 16), task.dueAt)
        assertEquals(at(9, 23, 16), reminders.scheduled[task.id])
        assertEquals(at(9, 23, 16), log.records.getValue(recordId).chip!!.at)
    }
}
