package com.local.assistant.memory.db

/** What forgetting removed, and enough to put it back. */
data class ForgetResult(
    val subject: String,
    /** Empty for the whole subject. */
    val attribute: String,
    val removed: List<FactEntity>,
    val forgottenAt: Long,
    /** A tombstone this one replaced, restored on undo. */
    val previousTombstone: Long?,
)

/**
 * The structured memory as the tools see it.
 *
 * An interface so the tool layer can be tested on the JVM against an in-memory fake; the real
 * implementation is [MemoryRepository] on Room. Keys are normalised by the implementation.
 */
interface MemoryStore {

    /** Runs [block] as one transaction: every write in it commits or none does. */
    suspend fun <T> transaction(block: suspend () -> T): T

    // Facts.

    suspend fun saveFact(
        subject: String,
        attribute: String,
        value: String,
        origin: FactOrigin,
        pin: Boolean = false,
        /** When the statement was made; null for now. */
        statedAt: Long? = null,
        sourceMessageId: Long? = null,
    ): FactWriteResult

    /**
     * Undoes a fact write: puts [previous] back, or deletes [written] if there was nothing before.
     * Refuses (returns false) if the row has changed since, so an undo never clobbers a newer edit.
     */
    suspend fun restoreFact(previous: FactEntity?, written: FactEntity): Boolean

    suspend fun forgetFacts(subject: String, attribute: String?): ForgetResult

    /** Puts back what [result] removed, and its tombstone's predecessor. */
    suspend fun unforget(result: ForgetResult)

    /** Facts whose keys or value contain any of [words], freshest first. */
    suspend fun searchFacts(words: List<String>, limit: Int): List<FactEntity>

    suspend fun coreFacts(): List<FactEntity>

    // Tasks.

    suspend fun insertTask(task: TaskEntity): Long

    suspend fun task(id: Long): TaskEntity?

    suspend fun updateTask(task: TaskEntity)

    suspend fun deleteTask(id: Long)

    suspend fun openTasks(): List<TaskEntity>

    suspend fun tasksDueBetween(from: Long, to: Long): List<TaskEntity>

    suspend fun tasksMentioning(word: String, limit: Int): List<TaskEntity>

    // Events.

    suspend fun insertEvent(event: EventEntity): Long

    suspend fun event(id: Long): EventEntity?

    suspend fun updateEvent(event: EventEntity)

    suspend fun deleteEvent(id: Long)

    /** One-off events starting in the range, plus every repeating event. */
    suspend fun eventsBetween(from: Long, to: Long): List<EventEntity>

    suspend fun eventsMentioning(word: String, limit: Int): List<EventEntity>
}
