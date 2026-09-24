package com.local.assistant.memory.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactUpsertPolicy
import com.local.assistant.memory.core.FactWrite
import com.local.assistant.memory.prompt.AgendaItem
import kotlinx.coroutines.flow.Flow

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
 * Single entry point for the structured memory store.
 *
 * Every write is one transaction: the read that decides it and the write itself cannot be split
 * by a concurrent writer, so two saves of the same key never both insert.
 */
class MemoryRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val facts = database.factDao()
    private val agenda = database.agendaDao()
    private val sessions = database.sessionDao()

    /**
     * Records `subject.attribute = value` under the upsert rules in [FactUpsertPolicy]. Keys are
     * normalised here, so callers pass them as the model or the user wrote them.
     */
    suspend fun saveFact(
        subject: String,
        attribute: String,
        value: String,
        origin: FactOrigin,
        pin: Boolean = false,
        statedAt: Long = clock(),
        sourceMessageId: Long? = null,
    ): FactWriteResult {
        val write = FactWrite(
            subject = FactKeys.subject(subject),
            attribute = FactKeys.attribute(attribute),
            value = FactKeys.value(value),
            pin = pin,
            origin = origin,
            statedAt = statedAt,
            sourceMessageId = sourceMessageId,
        )
        return database.useWriterConnection { connection ->
            connection.immediateTransaction {
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
    }

    /**
     * Deletes a fact, or every fact about a subject when [attribute] is null, and leaves a
     * tombstone so extraction from older messages cannot bring it back. Returns what was deleted.
     */
    suspend fun forget(subject: String, attribute: String? = null): List<FactEntity> {
        val subjectKey = FactKeys.subject(subject)
        val attributeKey = attribute?.let(FactKeys::attribute).orEmpty()
        return database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val removed = if (attributeKey.isEmpty()) {
                    facts.bySubject(subjectKey).also { facts.deleteSubject(subjectKey) }
                } else {
                    listOfNotNull(facts.find(subjectKey, attributeKey))
                        .also { facts.deleteKey(subjectKey, attributeKey) }
                }
                facts.insertTombstone(ForgottenEntity(subjectKey, attributeKey, clock()))
                removed
            }
        }
    }

    suspend fun coreFacts(): List<FactEntity> = facts.coreFacts()

    fun observeCoreFacts(): Flow<List<FactEntity>> = facts.observeCoreFacts()

    /** The next [limit] open tasks and upcoming events, soonest first; undated tasks last. */
    suspend fun agenda(now: Long, limit: Int): List<AgendaItem> {
        val tasks = agenda.openTasks(limit).map { AgendaItem(it.title, it.dueAt) }
        val events = agenda.upcomingEvents(now, limit).map { AgendaItem(it.title, it.startsAt, it.allDay) }
        return (tasks + events)
            .sortedWith(compareBy<AgendaItem> { it.at == null }.thenBy { it.at })
            .take(limit)
    }

    /** The newest finished session summary from any chat, for a conversation's section C. */
    suspend fun latestSessionSummary(): String? = sessions.latestSummarised()?.summary
}
