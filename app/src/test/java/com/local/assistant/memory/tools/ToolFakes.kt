package com.local.assistant.memory.tools

import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.ToolCall
import com.local.assistant.llm.ToolResult
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactUpsertPolicy
import com.local.assistant.memory.core.FactWrite
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.FactWriteResult
import com.local.assistant.memory.db.ForgetResult
import com.local.assistant.memory.db.MemoryStore
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** The memory store in plain maps, applying the same upsert policy as the real one. */
class FakeMemoryStore(private val clock: () -> Long) : MemoryStore {
    val facts = linkedMapOf<Long, FactEntity>()
    val tombstones = mutableMapOf<Pair<String, String>, Long>()
    val tasks = linkedMapOf<Long, TaskEntity>()
    val events = linkedMapOf<Long, EventEntity>()
    private var nextId = 1L

    override suspend fun <T> transaction(block: suspend () -> T): T = block()

    override suspend fun saveFact(
        subject: String,
        attribute: String,
        value: String,
        origin: FactOrigin,
        pin: Boolean,
        statedAt: Long?,
        sourceMessageId: Long?,
    ): FactWriteResult {
        val write = FactWrite(FactKeys.subject(subject), FactKeys.attribute(attribute), FactKeys.value(value), pin, origin, statedAt ?: clock(), sourceMessageId)
        val existing = facts.values.firstOrNull { it.subject == write.subject && it.attribute == write.attribute }
        val forgottenAt = listOfNotNull(tombstones[write.subject to ""], tombstones[write.subject to write.attribute]).maxOrNull()
        val decision = when (val d = FactUpsertPolicy.decide(existing, write, forgottenAt, clock())) {
            is FactDecision.Insert -> FactDecision.Insert(d.fact.copy(id = nextId++)).also { facts[it.fact.id] = it.fact }
            is FactDecision.Update -> d.also { facts[it.fact.id] = it.fact }
            is FactDecision.Confirm -> d.also { facts[it.fact.id] = it.fact }
            is FactDecision.Skip -> d
        }
        return FactWriteResult(decision, existing)
    }

    override suspend fun restoreFact(previous: FactEntity?, written: FactEntity): Boolean {
        if (facts[written.id] != written) return false
        if (previous == null) facts.remove(written.id) else facts[previous.id] = previous
        return true
    }

    override suspend fun forgetFacts(subject: String, attribute: String?): ForgetResult {
        val s = FactKeys.subject(subject)
        val a = attribute?.takeIf { it.isNotBlank() }?.let(FactKeys::attribute).orEmpty()
        val removed = facts.values.filter { it.subject == s && (a.isEmpty() || it.attribute == a) }
        removed.forEach { facts.remove(it.id) }
        val previous = tombstones[s to a]
        tombstones[s to a] = clock()
        return ForgetResult(s, a, removed, clock(), previous)
    }

    override suspend fun unforget(result: ForgetResult) {
        result.removed.forEach { facts[it.id] = it }
        val previous = result.previousTombstone
        if (previous == null) tombstones.remove(result.subject to result.attribute) else tombstones[result.subject to result.attribute] = previous
    }

    override suspend fun searchFacts(words: List<String>, limit: Int): List<FactEntity> =
        facts.values.filter { fact -> words.any { w -> listOf(fact.subject, fact.attribute, fact.value.lowercase()).any { w in it } } }.take(limit)

    override suspend fun coreFacts(): List<FactEntity> = facts.values.filter { it.core && it.subject == FactKeys.USER }

    override suspend fun insertTask(task: TaskEntity): Long = nextId++.also { tasks[it] = task.copy(id = it) }
    override suspend fun task(id: Long): TaskEntity? = tasks[id]
    override suspend fun updateTask(task: TaskEntity) { tasks[task.id] = task }
    override suspend fun deleteTask(id: Long) { tasks.remove(id) }
    override suspend fun openTasks(): List<TaskEntity> = tasks.values.filter { it.status == TaskStatus.OPEN }
    override suspend fun tasksDueBetween(from: Long, to: Long): List<TaskEntity> =
        openTasks().filter { (it.dueAt ?: -1) in from until to }.sortedBy { it.dueAt }
    override suspend fun tasksMentioning(word: String, limit: Int): List<TaskEntity> =
        tasks.values.filter { word in it.title.lowercase() }.take(limit)

    override suspend fun insertEvent(event: EventEntity): Long = nextId++.also { events[it] = event.copy(id = it) }
    override suspend fun event(id: Long): EventEntity? = events[id]
    override suspend fun updateEvent(event: EventEntity) { events[event.id] = event }
    override suspend fun deleteEvent(id: Long) { events.remove(id) }
    override suspend fun eventsBetween(from: Long, to: Long): List<EventEntity> =
        events.values.filter { it.startsAt in from until to || it.recurrence != null }
    override suspend fun eventsMentioning(word: String, limit: Int): List<EventEntity> =
        events.values.filter { word in it.title.lowercase() }.take(limit)
}

class FakeReminders(var exact: Boolean = true) : ReminderScheduler {
    val scheduled = linkedMapOf<Long, Long>()
    val eventAlerts = linkedMapOf<Long, Long>()
    override fun canScheduleExact() = exact
    override fun schedule(taskId: Long, dueAt: Long) { scheduled[taskId] = dueAt }
    override fun cancel(taskId: Long) { scheduled.remove(taskId) }
    override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) { eventAlerts[eventId] = alertAt }
    override fun cancelEvent(eventId: Long) { eventAlerts.remove(eventId) }
}

class FakeSystemAlarms(var present: Boolean = true, var reachable: Boolean = true) : SystemAlarms {
    data class Set(val hour: Int, val minute: Int, val days: kotlin.collections.Set<java.time.DayOfWeek>, val label: String?)
    val set = mutableListOf<Set>()
    override fun available() = present
    override fun set(hour: Int, minute: Int, days: kotlin.collections.Set<java.time.DayOfWeek>, label: String?): Boolean {
        if (!reachable) return false
        set += Set(hour, minute, days, label)
        return true
    }
    override fun openClock() = Unit
}

class FakeToolLog : ToolLog {
    val records = linkedMapOf<Long, ToolRecord>()
    private var nextId = 1_000L
    override suspend fun record(chatId: Long, record: ToolRecord): Long = nextId++.also { records[it] = record }
    override suspend fun read(id: Long): ToolRecord? = records[id]
    override suspend fun update(id: Long, record: ToolRecord) { records[id] = record }
}

/**
 * Plays the model: each send gets the next scripted list of events. Records what it was sent,
 * so a test can see the envelope and the tool results the model would have read.
 */
class ScriptedSession(private val script: ArrayDeque<List<GenEvent>>) : ChatSession {
    val sentEnvelopes = mutableListOf<String>()
    val sentResults = mutableListOf<List<ToolResult>>()

    override val isAlive = true

    override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> {
        sentEnvelopes += envelope
        return next()
    }

    override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> {
        sentResults += results
        return next()
    }

    private fun next(): Flow<GenEvent> = (script.removeFirstOrNull() ?: listOf(GenEvent.Done(null))).asFlow()

    override fun tokenCount(): Int? = null
    override fun cancel() = Unit
    override fun close() = Unit

    companion object {
        fun calls(vararg calls: ToolCall) = listOf(GenEvent.ToolCalls(calls.toList()), GenEvent.Done(null))
        fun says(text: String) = listOf(GenEvent.TextDelta(text), GenEvent.Done(null))
    }
}
