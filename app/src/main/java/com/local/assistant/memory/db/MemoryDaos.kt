package com.local.assistant.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FactDao {

    @Query("SELECT * FROM facts WHERE subject = :subject AND attribute = :attribute")
    suspend fun find(subject: String, attribute: String): FactEntity?

    @Query("SELECT * FROM facts WHERE id = :id")
    suspend fun byId(id: Long): FactEntity?

    @Query("SELECT * FROM facts WHERE subject = :subject ORDER BY id")
    suspend fun bySubject(subject: String): List<FactEntity>

    @Insert
    suspend fun insert(fact: FactEntity): Long

    @Update
    suspend fun update(fact: FactEntity)

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM facts WHERE subject = :subject AND attribute = :attribute")
    suspend fun deleteKey(subject: String, attribute: String): Int

    @Query("DELETE FROM facts WHERE subject = :subject")
    suspend fun deleteSubject(subject: String): Int

    /**
     * Candidates for core memory. Which of them fit, and in what order, is decided in code so the
     * rendered block stays deterministic.
     */
    @Query("SELECT * FROM facts WHERE core = 1 AND subject = 'user' ORDER BY id")
    suspend fun coreFacts(): List<FactEntity>

    @Query("SELECT * FROM facts WHERE core = 1 AND subject = 'user' ORDER BY id")
    fun observeCoreFacts(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts ORDER BY category, subject, attribute")
    fun observeAll(): Flow<List<FactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(facts: List<FactEntity>)

    /** Every fact matching an FTS4 MATCH expression, core included, freshest first. */
    @Query(
        """
        SELECT facts.* FROM facts
        JOIN facts_fts ON facts.id = facts_fts.rowid
        WHERE facts_fts MATCH :match
        ORDER BY facts.lastConfirmedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchAll(match: String, limit: Int): List<FactEntity>

    /** Non-core facts matching an FTS4 MATCH expression, freshest first. */
    @Query(
        """
        SELECT facts.* FROM facts
        JOIN facts_fts ON facts.id = facts_fts.rowid
        WHERE facts_fts MATCH :match AND facts.core = 0
        ORDER BY facts.lastConfirmedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, limit: Int): List<FactEntity>

    /** The latest tombstone covering this key, whether for the attribute or the whole subject. */
    @Query(
        "SELECT MAX(forgottenAt) FROM forgotten " +
            "WHERE subject = :subject AND attribute IN ('', :attribute)",
    )
    suspend fun forgottenAt(subject: String, attribute: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: ForgottenEntity)

    @Query("SELECT forgottenAt FROM forgotten WHERE subject = :subject AND attribute = :attribute")
    suspend fun tombstone(subject: String, attribute: String): Long?

    @Query("DELETE FROM forgotten WHERE subject = :subject AND attribute = :attribute")
    suspend fun deleteTombstone(subject: String, attribute: String)

    @Query("SELECT subject FROM subject_aliases WHERE alias = :alias")
    suspend fun subjectForAlias(alias: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: SubjectAliasEntity)
}

@Dao
interface AgendaDao {

    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: Long): TaskEntity?

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("SELECT * FROM tasks WHERE status = 'OPEN' ORDER BY dueAt IS NULL, dueAt, id")
    suspend fun allOpenTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'OPEN' AND dueAt >= :from AND dueAt < :to ORDER BY dueAt, id")
    suspend fun tasksDueBetween(from: Long, to: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :word || '%' ORDER BY status = 'OPEN' DESC, id DESC LIMIT :limit")
    suspend fun tasksMentioning(word: String, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun event(id: Long): EventEntity?

    @Update
    suspend fun updateEvent(event: EventEntity)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    /** One-off events in the range, and every repeating event, whose next time is worked out in code. */
    @Query(
        "SELECT * FROM events WHERE (startsAt >= :from AND startsAt < :to) OR recurrence IS NOT NULL " +
            "ORDER BY startsAt, id",
    )
    suspend fun eventsBetween(from: Long, to: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE title LIKE '%' || :word || '%' ORDER BY startsAt DESC LIMIT :limit")
    suspend fun eventsMentioning(word: String, limit: Int): List<EventEntity>

    /** Dated tasks soonest first, then undated ones. */
    @Query(
        "SELECT * FROM tasks WHERE status = 'OPEN' " +
            "ORDER BY dueAt IS NULL, dueAt, id LIMIT :limit",
    )
    suspend fun openTasks(limit: Int): List<TaskEntity>

    @Query("SELECT * FROM events WHERE startsAt >= :from ORDER BY startsAt, id LIMIT :limit")
    suspend fun upcomingEvents(from: Long, limit: Int): List<EventEntity>
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE chatId = :chatId AND endedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openSession(chatId: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :sessionId")
    suspend fun end(sessionId: Long, endedAt: Long)

    @Query("SELECT MAX(createdAt) FROM messages WHERE sessionId = :sessionId")
    suspend fun lastActivity(sessionId: Long): Long?

    /** The most recent session anywhere that has a finished summary. */
    @Query(
        "SELECT * FROM sessions WHERE summaryStatus = 'DONE' AND summary IS NOT NULL " +
            "ORDER BY endedAt DESC LIMIT 1",
    )
    suspend fun latestSummarised(): SessionEntity?
}

@Dao
interface AppStateDao {

    @Query("SELECT value FROM app_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: AppStateEntity)
}
