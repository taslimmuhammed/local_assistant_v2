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
    private val images = FakeImageNotes()
    private var paused = false
    private val phone = FakePhone()
    private var contacts: List<Contact>? = listOf(
        Contact("Amma", listOf(ContactPhone("+91 98450 11111"))),
        Contact("Priya Sharma", listOf(ContactPhone("98450 22222")), listOf("priya@example.com")),
        Contact("Priya Nair", listOf(ContactPhone("98450 33333"))),
        Contact("Dr Rao Clinic", listOf(ContactPhone("080 4444 5555"))),
    )
    private val people = mutableMapOf<String, PersonHints>()
    private val device = DeviceTools(phone, { contacts }, { who -> people[who] ?: PersonHints.NONE }, clock, now = { now })
    private val web = object : WebSearch {
        var searched = mutableListOf<Pair<String, Boolean>>()
        var failure: String? = null
        override val enabled = true
        override suspend fun search(query: String, news: Boolean): WebResults {
            failure?.let { throw WebSearchError(it) }
            searched += query to news
            return WebResults(
                answer = "It is 29°C and humid in Kochi. " + "More detail. ".repeat(80),
                results = List(5) { WebResult("Result $it", "https://www.site$it.com/page", "Snippet $it " + "words ".repeat(100)) },
            )
        }
    }
    private val executor = ToolExecutor(store, reminders, clock, log, now = { now }, memoryPaused = { paused }, images = images, device = device, web = WebTools(web))
    private val loop = ToolLoop(
        executor,
        maxRounds = 3,
        isOverflow = { "too long" in it.message.orEmpty() },
        isUnreadable = { "Failed to parse tool calls" in it.message.orEmpty() },
        repair = com.local.assistant.llm.ToolCallRepair(ToolCatalog.declarations(web = true))::repair,
    )
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
    fun `an unreadable first tool call is reported for a retry, one after a tool round is not`() {
        val unreadable = listOf(GenEvent.Error(IllegalStateException("Status Code: 3. Message: Failed to parse tool calls")))
        val first = ScriptedSession(ArrayDeque(listOf(unreadable)))
        assertEquals(listOf(LoopEvent.Unreadable), runBlocking { loop.run(first, "envelope", null, context).toList() })

        // The first round already ran a tool: trying the whole turn again would run it twice.
        val later = ScriptedSession(ArrayDeque(listOf(ScriptedSession.calls(call("calculate", "expression" to "2+2")), unreadable)))
        val thrown = runCatching { runBlocking { loop.run(later, "envelope", null, context).toList() } }.exceptionOrNull()
        assertTrue(thrown?.message.orEmpty().contains("Failed to parse"))
    }

    @Test
    fun `a call that only slipped in its format is carried out and confirmed, and the conversation rebuilt`() {
        val slipped = listOf(GenEvent.Error(IllegalStateException("Failed to parse tool calls from code block: call:add_task{title:<|\"|>Renew passport<|\"|>, next month<|\"|>}")))
        val events = runBlocking { loop.run(ScriptedSession(ArrayDeque(listOf(slipped))), "envelope", null, context).toList() }
        val task = store.tasks.values.single()
        assertEquals("Renew passport", task.title)
        assertEquals(LoopEvent.Text("Done."), events.filterIsInstance<LoopEvent.Text>().single())
        assertTrue(events.last().let { it is LoopEvent.Done && it.staleConversation })
        assertEquals(MemoryChip.Kind.TASK, events.filterIsInstance<LoopEvent.Memory>().single().chip.kind)
    }

    @Test
    fun `a slipped call that only looks something up, or that fails, is retried instead`() {
        val lookup = listOf(GenEvent.Error(IllegalStateException("Failed to parse tool calls from code block: call:calculate{<|\"|>2+2<|\"|>}")))
        assertEquals(listOf(LoopEvent.Unreadable), runBlocking { loop.run(ScriptedSession(ArrayDeque(listOf(lookup))), "envelope", null, context).toList() })
        val badTime = listOf(GenEvent.Error(IllegalStateException("Failed to parse tool calls from code block: call:add_task{title:<|\"|>Call mom<|\"|>, more often}")))
        assertEquals(listOf(LoopEvent.Unreadable), runBlocking { loop.run(ScriptedSession(ArrayDeque(listOf(badTime))), "envelope", null, context).toList() })
        assertTrue(store.tasks.isEmpty())
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

    // ---- Saved images ----

    @Test
    fun `remember this keeps the image the user just sent with the model's details`() {
        images.sent[1] = mutableMapOf(4L to "/a/old.jpg", 9L to "/a/card.jpg", 11L to "/a/later.jpg")
        val (session, events) = turn(
            call("remember_image", "title" to "wifi card", "details" to "Network: Home_5G. Password: kochi2026. Router: TP-Link Archer C6."),
        )
        val (title, details, image) = images.saved.values.single()
        assertEquals("Wifi card", title)
        assertTrue("kochi2026" in details)
        assertEquals("the newest image up to this message", 9L, image.messageId)
        assertEquals(true, result(session)["ok"])
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.IMAGE, chip.kind)
        assertEquals("Saved image", chip.label)
    }

    @Test
    fun `remember this with no image in the chat is an error, and nothing is saved`() {
        val (session, events) = turn(call("remember_image", "title" to "receipt", "details" to "Total 450"))
        assertEquals(false, result(session)["ok"])
        assertTrue(images.saved.isEmpty())
        assertTrue(events.none { it is LoopEvent.Memory })
    }

    @Test
    fun `undo of a saved image deletes it`() {
        images.sent[1] = mutableMapOf(10L to "/a/receipt.jpg")
        val (_, events) = turn(call("remember_image", "title" to "receipt", "details" to "Total 450"))
        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(events.filterIsInstance<LoopEvent.Memory>().single().recordId) })
        assertTrue(images.saved.isEmpty())
    }

    @Test
    fun `while memory is paused no image is saved`() {
        paused = true
        images.sent[1] = mutableMapOf(10L to "/a/receipt.jpg")
        val (session, _) = turn(call("remember_image", "title" to "receipt", "details" to "Total 450"))
        assertEquals(false, result(session)["ok"])
        assertTrue(images.saved.isEmpty())
    }

    // ---- Everyday tools ----

    private fun error(session: ScriptedSession) = result(session)["error"].toString()

    @Test
    fun `call amma opens the dialer with her number, and nothing is placed`() {
        val (_, events) = turn(call("phone_call", "who" to "amma"))
        assertEquals(listOf("dial +91 98450 11111"), phone.done)
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.CALL, chip.kind)
        assertEquals("Amma · +91 98450 11111", chip.detail)
    }

    @Test
    fun `a number saved in memory is used before the contacts, which then need no permission`() {
        people["mom"] = PersonHints(names = listOf("amma"), phone = "98470 12345")
        contacts = null
        turn(call("phone_call", "who" to "mom"))
        assertEquals(listOf("dial 98470 12345"), phone.done)
    }

    @Test
    fun `my dentist is found by the name memory has for them`() {
        people["my dentist"] = PersonHints(names = listOf("Dr. Rao"))
        turn(call("phone_call", "who" to "my dentist"))
        assertEquals(listOf("dial 080 4444 5555"), phone.done)
    }

    @Test
    fun `a number said out loud is dialled as it is`() {
        turn(call("phone_call", "who" to "98450 12345"))
        assertEquals(listOf("dial 98450 12345"), phone.done)
    }

    @Test
    fun `two Priyas are asked about, no contacts access is explained, and nobody is dialled`() {
        val (several, _) = turn(call("phone_call", "who" to "Priya"))
        assertTrue(error(several).contains("Priya Sharma (…2222), Priya Nair (…3333)"))
        now = now.plusMinutes(1)
        contacts = null
        val (denied, _) = turn(call("phone_call", "who" to "Anjali"))
        assertTrue(error(denied).contains("allowed this app to read contacts"))
        assertTrue(phone.done.isEmpty())
    }

    @Test
    fun `messages open written, in the app asked for`() {
        turn(call("send_message", "to" to "Priya Sharma", "text" to "Running 10 minutes late", "app" to "whatsapp"))
        now = now.plusMinutes(1)
        turn(call("send_message", "to" to "amma", "text" to "Reached home"))
        now = now.plusMinutes(1)
        turn(call("send_message", "to" to "Priya Sharma", "text" to "Notes attached", "app" to "email"))
        assertEquals(
            listOf("WHATSAPP 98450 22222: Running 10 minutes late", "SMS +91 98450 11111: Reached home", "EMAIL priya@example.com: Notes attached"),
            phone.done,
        )
        phone.whatsapp = false
        now = now.plusMinutes(1)
        val (session, _) = turn(call("send_message", "to" to "amma", "text" to "hi", "app" to "WhatsApp"))
        assertTrue(error(session).contains("WhatsApp isn't installed"))
    }

    @Test
    fun `timers go to the clock app from the user's own words`() {
        val (session, events) = turn(call("set_timer", "duration" to "10 minutes", "label" to "pasta"))
        assertEquals(listOf(600 to "Pasta"), clock.timers)
        assertEquals("10 min", result(session)["timer"])
        assertEquals("10:10", result(session)["ends"])
        assertEquals("10 min · Pasta", events.filterIsInstance<LoopEvent.Memory>().single().chip.detail)
        now = now.plusMinutes(1)
        val (tooLong, _) = turn(call("set_timer", "duration" to "30 hours"))
        assertTrue(error(tooLong).contains("24 hours"))
    }

    @Test
    fun `open_app launches, searches and navigates`() {
        turn(call("open_app", "app" to "YouTube"))
        now = now.plusMinutes(1)
        turn(call("open_app", "app" to "maps", "query" to "Indiranagar"))
        assertEquals(
            listOf("open ${AppTarget.Launch(phone.apps[0])}", "open ${AppTarget.Directions("Indiranagar")}"),
            phone.done,
        )
        now = now.plusMinutes(1)
        val (missing, _) = turn(call("open_app", "app" to "Instagram"))
        assertTrue(error(missing).contains("no app called"))
    }

    @Test
    fun `phone settings switch what they can and open the panel for what they cannot`() {
        turn(call("phone_setting", "setting" to "flashlight", "value" to "on"))
        turn(call("phone_setting", "setting" to "silent mode", "value" to "on"))
        val (volume, _) = turn(call("phone_setting", "setting" to "volume", "value" to "up"))
        val (wifi, _) = turn(call("phone_setting", "setting" to "wifi", "value" to "on"))
        assertEquals(listOf("torch true", "ringer SILENT", "panel WIFI"), phone.done)
        assertEquals("60%", result(volume)["volume"])
        assertTrue(result(wifi)["note"].toString().contains("the user switches it there"))
    }

    @Test
    fun `silent without Do Not Disturb access falls back to vibrate, and Do Not Disturb asks for access`() {
        phone.silentNeedsAccess = true
        val (silent, _) = turn(call("phone_setting", "setting" to "ringer", "value" to "silent"))
        assertEquals("vibrate", result(silent)["ringer"])
        val (dnd, _) = turn(call("phone_setting", "setting" to "do_not_disturb", "value" to "on"))
        assertTrue(error(dnd).contains("allow Do Not Disturb access"))
    }

    @Test
    fun `calculate works the sum out exactly`() {
        val (session, events) = turn(call("calculate", "expression" to "18% of 2450"))
        assertEquals("441", result(session)["result"])
        assertTrue("no chip for arithmetic", events.none { it is LoopEvent.Memory })
    }

    @Test
    fun `with the app in the background nothing opens and the model is told why`() {
        phone.foreground = false
        val (session, _) = turn(call("phone_call", "who" to "amma"))
        assertTrue(error(session).contains("background"))
    }

    @Test
    fun `a repeated device call within seconds is not repeated, a minute later it is`() {
        turn(call("phone_call", "who" to "amma"))
        now = now.plusSeconds(5)
        turn(call("phone_call", "who" to "amma"))
        assertEquals(1, phone.done.size)
        now = now.plusMinutes(1)
        turn(call("phone_call", "who" to "amma"))
        assertEquals(2, phone.done.size)
    }

    // ---- Web search ----

    @Test
    fun `web search returns a short answer and three sources, and the chip opens the top one`() {
        val (session, events) = turn(call("web_lookup", "query" to "weather in Kochi today"))
        assertEquals(listOf("weather in Kochi today" to false), web.searched)
        val json = session.sentResults.first().single().json
        assertTrue("fits the tool-round budget: ${json.length} chars", json.length < 1_600)
        val sources = result(session)["sources"] as List<*>
        assertEquals(3, sources.size)
        assertEquals("site0.com", (sources[0] as Map<*, *>)["site"])
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.WEB, chip.kind)
        assertEquals("https://www.site0.com/page", chip.link)
    }

    @Test
    fun `news goes as news, and failures reach the model as errors`() {
        turn(call("web_lookup", "query" to "ISRO launch", "topic" to "news"))
        assertEquals(listOf("ISRO launch" to true), web.searched)
        web.failure = "The phone may be offline."
        val (session, _) = turn(call("web_lookup", "query" to "IPL score"))
        assertTrue(error(session).contains("offline"))
    }

    @Test
    fun `web search is declared, and its rule given, only when it is set up`() {
        assertFalse(ToolCatalog.declarations(web = false).any { "\"web_lookup\"" in it })
        assertTrue(ToolCatalog.declarations(web = true).any { "\"web_lookup\"" in it })
        assertFalse("web_lookup" in ToolCatalog.rules(web = false))
        assertTrue("web_lookup" in ToolCatalog.rules(web = true))
        assertFalse("{web}" in ToolCatalog.rules(web = false))
    }
}
