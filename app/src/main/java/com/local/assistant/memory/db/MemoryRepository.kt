package com.local.assistant.memory.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactUpsertPolicy
import com.local.assistant.memory.core.FactWrite
import com.local.assistant.memory.prompt.AgendaItem
import com.local.assistant.memory.retrieval.FactSource
import com.local.assistant.memory.tools.EventTimes
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

/** The outcome of a fact write, with the row as it was before — which is all undo needs. */
data class FactWriteResult(val decision: FactDecision, val previous: FactEntity?) {
    /** The row as it now stands, or null if nothing was written. */
    val fact: FactEntity?
        get() = when (decision) {
            is FactDecision.Insert -> decision.fact
            is FactDecision.Update -> decision.fact
            is FactDecision.Confirm -> decision.fact
            is FactDecision.Skip -> null
        }
}

/**
 * The structured memory store on Room.
 *
 * Every write is one transaction: the read that decides it and the write itself cannot be split
 * by a concurrent writer, so two saves of the same key never both insert. Calls made inside
 * [transaction] join it, so a tool's whole effect commits together.
 */
class MemoryRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : MemoryStore, FactSource {
    private val facts = database.factDao()
    private val agendaDao = database.agendaDao()
    private val sessions = database.sessionDao()

    override suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    /**
     * Records `subject.attribute = value` under the upsert rules in [FactUpsertPolicy]. Keys are
     * normalised here, so callers pass them as the model or the user wrote them.
     */
    override suspend fun saveFact(
        subject: String,
        attribute: String,
        value: String,
        origin: FactOrigin,
        pin: Boolean,
        statedAt: Long?,
        sourceMessageId: Long?,
    ): FactWriteResult {
        val write = FactWrite(
            subject = resolveSubject(subject),
            attribute = FactKeys.attribute(attribute),
            value = FactKeys.value(value),
            pin = pin,
            origin = origin,
            statedAt = statedAt ?: clock(),
            sourceMessageId = sourceMessageId,
        )
        return transaction {
            val existing = facts.find(write.subject, write.attribute)
            val forgottenAt = facts.forgottenAt(write.subject, write.attribute)
            val decision = when (val d = FactUpsertPolicy.decide(existing, write, forgottenAt, clock())) {
                is FactDecision.Insert -> FactDecision.Insert(d.fact.copy(id = facts.insert(d.fact)))
                is FactDecision.Update -> d.also { facts.update(it.fact) }
                is FactDecision.Confirm -> d.also { if (it.fact != existing) facts.update(it.fact) }
                is FactDecision.Skip -> d
            }
            FactWriteResult(decision, existing)
        }
    }

    override suspend fun restoreFact(previous: FactEntity?, written: FactEntity): Boolean = transaction {
        val current = facts.byId(written.id)
        if (current != written) return@transaction false
        if (previous == null) facts.delete(written.id) else facts.update(previous)
        true
    }

    /**
     * Deletes a fact, or every fact about a subject when [attribute] is null, and leaves a
     * tombstone so extraction from older messages cannot bring it back.
     */
    override suspend fun forgetFacts(subject: String, attribute: String?): ForgetResult {
        val subjectKey = resolveSubject(subject)
        val attributeKey = attribute?.takeIf { it.isNotBlank() }?.let(FactKeys::attribute).orEmpty()
        return transaction {
            val removed = if (attributeKey.isEmpty()) {
                facts.bySubject(subjectKey).also { facts.deleteSubject(subjectKey) }
            } else {
                listOfNotNull(facts.find(subjectKey, attributeKey)).also { facts.deleteKey(subjectKey, attributeKey) }
            }
            val previous = facts.tombstone(subjectKey, attributeKey)
            val now = clock()
            facts.insertTombstone(ForgottenEntity(subjectKey, attributeKey, now))
            ForgetResult(subjectKey, attributeKey, removed, now, previous)
        }
    }

    override suspend fun unforget(result: ForgetResult) = transaction {
        facts.insertAll(result.removed)
        val previous = result.previousTombstone
        if (previous == null) {
            facts.deleteTombstone(result.subject, result.attribute)
        } else {
            facts.insertTombstone(ForgottenEntity(result.subject, result.attribute, previous))
        }
    }

    /** The subject key a name files under: normalised, then through any alias the user merged. */
    private suspend fun resolveSubject(raw: String): String {
        val key = FactKeys.subject(raw)
        return facts.subjectForAlias(key) ?: key
    }

    /**
     * Moves every fact about [from] under [into] and remembers [from] as a name for it. Where
     * both have the same attribute, the user's own edit wins, then the newer statement. Tombstones
     * move too, so nothing forgotten under the old name comes back under the new one.
     */
    suspend fun mergeSubjects(from: String, into: String): Int = transaction {
        if (from == into || from == FactKeys.USER) return@transaction 0
        var moved = 0
        for (fact in facts.bySubject(from)) {
            val existing = facts.find(into, fact.attribute)
            if (existing == null) {
                facts.update(fact.copy(subject = into))
            } else {
                val keepIncoming = when {
                    existing.origin == FactOrigin.USER_EDIT && fact.origin != FactOrigin.USER_EDIT -> false
                    fact.origin == FactOrigin.USER_EDIT && existing.origin != FactOrigin.USER_EDIT -> true
                    else -> fact.statedAt > existing.statedAt
                }
                facts.delete(fact.id)
                if (keepIncoming) facts.update(fact.copy(id = existing.id, subject = into, core = existing.core || fact.core))
            }
            moved++
        }
        for (tombstone in facts.tombstonesFor(from)) {
            val current = facts.tombstone(into, tombstone.attribute)
            if (current == null || current < tombstone.forgottenAt) facts.insertTombstone(tombstone.copy(subject = into))
        }
        facts.deleteTombstonesFor(from)
        facts.repointAliases(from, into)
        facts.insertAlias(SubjectAliasEntity(alias = from, subject = into))
        moved
    }

    /**
     * Files every fact under its attribute's current key — for rows written before a synonym was
     * added ("occupation" is now `job`). Where both exist, the user's own edit wins, then the
     * newer statement. Returns how many rows changed.
     */
    suspend fun normalizeAttributes(): Int = transaction {
        var changed = 0
        for (fact in facts.all()) {
            val key = FactKeys.attribute(fact.attribute)
            if (key.isEmpty() || key == fact.attribute) continue
            val existing = facts.find(fact.subject, key)
            val moved = fact.copy(attribute = key, category = com.local.assistant.memory.core.FactCategorizer.categorize(fact.subject, key))
            if (existing == null) {
                facts.update(moved)
            } else {
                val keepMoved = when {
                    existing.origin == FactOrigin.USER_EDIT && fact.origin != FactOrigin.USER_EDIT -> false
                    fact.origin == FactOrigin.USER_EDIT && existing.origin != FactOrigin.USER_EDIT -> true
                    else -> fact.statedAt > existing.statedAt
                }
                facts.delete(fact.id)
                if (keepMoved) facts.update(moved.copy(id = existing.id, core = existing.core || fact.core))
            }
            changed++
        }
        changed
    }

    /** Applies every certain merge (see `AliasPlanner.merges`). Returns how many facts moved. */
    suspend fun mergeCertainAliases(): Int {
        val aliases = facts.aliases().associate { it.alias to it.subject }
        val plan = com.local.assistant.memory.core.AliasPlanner.merges(facts.subjects(), aliases)
        return plan.entries.sumOf { (from, into) -> mergeSubjects(from, into) }
    }

    override suspend fun searchFacts(words: List<String>, limit: Int): List<FactEntity> {
        val match = ftsMatch(words) ?: return emptyList()
        return facts.searchAll(match, limit)
    }

    override suspend fun coreFacts(): List<FactEntity> = facts.coreFacts()

    override suspend fun subjectsForAliases(aliases: List<String>): List<String> =
        if (aliases.isEmpty()) emptyList() else facts.subjectsForAliases(aliases)

    override suspend fun nonCoreAbout(subjects: List<String>, limit: Int): List<FactEntity> =
        if (subjects.isEmpty()) emptyList() else facts.nonCoreAbout(subjects, limit)

    override suspend fun searchNonCore(match: String, limit: Int): List<FactEntity> =
        try {
            facts.search(match, limit)
        } catch (e: Exception) {
            // Recall is best-effort; a query the FTS parser rejects just finds nothing.
            emptyList()
        }

    fun observeCoreFacts(): Flow<List<FactEntity>> = facts.observeCoreFacts()

    override suspend fun insertTask(task: TaskEntity): Long = agendaDao.insertTask(task)

    override suspend fun task(id: Long): TaskEntity? = agendaDao.task(id)

    override suspend fun updateTask(task: TaskEntity) = agendaDao.updateTask(task)

    override suspend fun deleteTask(id: Long) = agendaDao.deleteTask(id)

    override suspend fun openTasks(): List<TaskEntity> = agendaDao.allOpenTasks()

    override suspend fun tasksDueBetween(from: Long, to: Long): List<TaskEntity> = agendaDao.tasksDueBetween(from, to)

    override suspend fun tasksMentioning(word: String, limit: Int): List<TaskEntity> = agendaDao.tasksMentioning(word, limit)

    override suspend fun insertEvent(event: EventEntity): Long = agendaDao.insertEvent(event)

    override suspend fun event(id: Long): EventEntity? = agendaDao.event(id)

    override suspend fun updateEvent(event: EventEntity) = agendaDao.updateEvent(event)

    override suspend fun deleteEvent(id: Long) = agendaDao.deleteEvent(id)

    override suspend fun eventsBetween(from: Long, to: Long): List<EventEntity> = agendaDao.eventsBetween(from, to)

    override suspend fun eventsMentioning(word: String, limit: Int): List<EventEntity> = agendaDao.eventsMentioning(word, limit)

    /**
     * The next [limit] open tasks and upcoming events, soonest first; undated tasks last. A
     * repeating event appears at its next occurrence.
     */
    suspend fun agenda(now: Long, limit: Int): List<AgendaItem> {
        val tasks = agendaDao.openTasks(limit).map { AgendaItem(it.title, it.dueAt, ref = "task ${it.id}") }
        val events = agendaDao.eventsBetween(now, Long.MAX_VALUE).mapNotNull { event ->
            EventTimes.nextStart(event, now, zone())?.let { AgendaItem(event.title, it, event.allDay, ref = "event ${event.id}") }
        }
        return (tasks + events)
            .sortedWith(compareBy<AgendaItem> { it.at == null }.thenBy { it.at })
            .take(limit)
    }

    /** The newest finished session summary from any chat, for a conversation's section C. */
    suspend fun latestSessionSummary(): String? = sessions.latestSummarised()?.summary

    companion object {
        /** Words as an FTS4 query: any of them, each as a prefix. Null if nothing is left. */
        fun ftsMatch(words: List<String>): String? =
            words.map { word -> word.filter { it.isLetterOrDigit() } }
                .filter { it.length >= 2 }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" OR ") { "$it*" }
    }
}
