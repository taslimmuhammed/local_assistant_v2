package com.local.assistant.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.local.assistant.data.db.MessageEntity
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

    @Query("SELECT * FROM facts ORDER BY id")
    suspend fun all(): List<FactEntity>

    @Query("SELECT DISTINCT subject FROM facts")
    suspend fun subjects(): List<String>

    @Query("SELECT * FROM subject_aliases")
    suspend fun aliases(): List<SubjectAliasEntity>

    @Query("UPDATE subject_aliases SET subject = :into WHERE subject = :from")
    suspend fun repointAliases(from: String, into: String)

    @Query("SELECT * FROM forgotten WHERE subject = :subject")
    suspend fun tombstonesFor(subject: String): List<ForgottenEntity>

    @Query("DELETE FROM forgotten WHERE subject = :subject")
    suspend fun deleteTombstonesFor(subject: String)

    @Query("SELECT * FROM forgotten ORDER BY forgottenAt")
    suspend fun tombstones(): List<ForgottenEntity>

    @Query("DELETE FROM facts")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM subject_aliases")
    suspend fun deleteAllAliases()

    /** Subjects any of [aliases] stand for ("amma" → mother). */
    @Query("SELECT DISTINCT subject FROM subject_aliases WHERE alias IN (:aliases)")
    suspend fun subjectsForAliases(aliases: List<String>): List<String>

    /** Non-core facts about any of [subjects], freshest first. */
    @Query("SELECT * FROM facts WHERE subject IN (:subjects) AND core = 0 ORDER BY lastConfirmedAt DESC LIMIT :limit")
    suspend fun nonCoreAbout(subjects: List<String>, limit: Int): List<FactEntity>

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

    @Query("SELECT * FROM tasks WHERE status = 'OPEN' ORDER BY dueAt IS NULL, dueAt, id")
    fun observeOpenTasks(): Flow<List<TaskEntity>>

    /** Events still to come, and every repeating one. */
    @Query("SELECT * FROM events WHERE startsAt >= :from OR recurrence IS NOT NULL ORDER BY startsAt, id")
    fun observeEvents(from: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT * FROM events ORDER BY id")
    suspend fun allEvents(): List<EventEntity>

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()
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

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY id")
    suspend fun openSessions(): List<SessionEntity>

    /** Ended sessions still waiting for a summary, oldest first. */
    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL AND summaryStatus = 'PENDING' ORDER BY endedAt, id LIMIT :limit")
    suspend fun pendingSummaries(limit: Int): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE endedAt IS NOT NULL AND summaryStatus = 'PENDING'")
    suspend fun pendingSummaryCount(): Int

    /** The session's user and assistant turns, oldest first. */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role != 'TOOL' ORDER BY id")
    suspend fun turns(sessionId: Long): List<MessageEntity>

    @Query("UPDATE sessions SET summary = :summary, summaryStatus = :status WHERE id = :sessionId")
    suspend fun setSummary(sessionId: Long, summary: String?, status: SummaryStatus)

    @Query("SELECT * FROM sessions WHERE summary IS NOT NULL ORDER BY startedAt")
    suspend fun summarised(): List<SessionEntity>

    /** "Forget everything": summaries go, and ended sessions are not summarised again. */
    @Query("UPDATE sessions SET summary = NULL, summaryStatus = 'DONE' WHERE endedAt IS NOT NULL")
    suspend fun clearSummaries()

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

/** A chunk matching a keyword search, with FTS4's `matchinfo(…, 'pcnalx')` for ranking it. */
data class KeywordRow(
    val id: Long,
    val messageId: Long,
    val chatId: Long,
    val createdAt: Long,
    val matchinfo: ByteArray,
)

/** A stored vector, for filling a vector index. */
data class StoredVector(val id: Long, val embedding: ByteArray)

@Dao
interface ChunkDao {

    @Insert
    suspend fun insert(chunk: ChunkEntity): Long

    @Query("SELECT * FROM chunks WHERE messageId = :messageId")
    suspend fun forMessage(messageId: Long): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChunkEntity>

    @Query("UPDATE chunks SET text = :text, embedding = NULL, modelId = :modelId WHERE id = :id")
    suspend fun replaceText(id: Long, text: String, modelId: String?)

    @Query("UPDATE chunks SET embedding = :embedding, modelId = :modelId WHERE id = :id AND text = :text")
    suspend fun setEmbedding(id: Long, text: String, embedding: ByteArray?, modelId: String): Int

    /** Chunks waiting for a vector, oldest first. */
    @Query("SELECT * FROM chunks WHERE modelId IS NULL ORDER BY id LIMIT :limit")
    suspend fun backlog(limit: Int): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE modelId IS NULL")
    suspend fun backlogSize(): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE modelId = :modelId AND embedding IS NOT NULL")
    suspend fun embeddedCount(modelId: String): Int

    @Query("SELECT id, embedding FROM chunks WHERE modelId = :modelId AND embedding IS NOT NULL ORDER BY id")
    suspend fun vectors(modelId: String): List<StoredVector>

    /**
     * Sends every chunk embedded by another model back to the backlog. [keep] are the markers
     * that are not model ids (never embedded on purpose).
     */
    @Query(
        "UPDATE chunks SET embedding = NULL, modelId = NULL " +
            "WHERE modelId IS NOT NULL AND modelId != :current AND modelId NOT IN (:keep)",
    )
    suspend fun resetOtherModels(current: String, keep: List<String>): Int

    /**
     * Keyword matches, newest exchanges first, with what ranking needs. Recency decides which
     * [limit] rows are scored when a common word matches thousands.
     */
    @Query(
        """
        SELECT chunks.id AS id, chunks.messageId AS messageId, chunks.chatId AS chatId,
               chunks.createdAt AS createdAt, matchinfo(chunks_fts, 'pcnalx') AS matchinfo
        FROM chunks_fts JOIN chunks ON chunks.id = chunks_fts.rowid
        WHERE chunks_fts MATCH :match
        ORDER BY chunks.messageId DESC
        LIMIT :limit
        """,
    )
    suspend fun keywordMatches(match: String, limit: Int): List<KeywordRow>

    /** User messages from before [before] that have no chunk yet, oldest first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE role = 'USER' AND id > :after AND id < :before AND offRecord = 0
          AND NOT EXISTS (SELECT 1 FROM chunks WHERE chunks.messageId = messages.id)
        ORDER BY id LIMIT :limit
        """,
    )
    suspend fun unchunkedUserMessages(after: Long, before: Long, limit: Int): List<MessageEntity>

    /** The turn after [afterId] in its chat, skipping tool records: a reply, or the next question. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id > :afterId AND role != 'TOOL' ORDER BY id LIMIT 1")
    suspend fun nextTurn(chatId: Long, afterId: Long): MessageEntity?

    @Query("SELECT MAX(id) FROM messages")
    suspend fun lastMessageId(): Long?

    /** Chunks matching an FTS4 query. */
    @Query("SELECT chunks.* FROM chunks JOIN chunks_fts ON chunks.id = chunks_fts.rowid WHERE chunks_fts MATCH :match")
    suspend fun matching(match: String): List<ChunkEntity>

    @Query("UPDATE chunks SET text = '', embedding = NULL, modelId = :marker WHERE id IN (:ids)")
    suspend fun blank(ids: List<Long>, marker: String)

    @Query("UPDATE chunks SET text = '', embedding = NULL, modelId = :marker")
    suspend fun blankAll(marker: String): Int

    @Update
    suspend fun update(chunk: ChunkEntity)
}

/** Queries for the nightly job: extraction candidates, history retention, housekeeping. */
@Dao
interface MaintenanceDao {

    /** User messages after [after] that memory may learn from, oldest first. */
    @Query("SELECT * FROM messages WHERE role = 'USER' AND id > :after AND offRecord = 0 ORDER BY id LIMIT :limit")
    suspend fun userMessagesAfter(after: Long, limit: Int): List<MessageEntity>

    /** Whether the turn [messageId] opened made a tool call (it then already acted on what was said). */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM messages t WHERE t.chatId = :chatId AND t.role = 'TOOL' AND t.id > :messageId
              AND t.id < (SELECT COALESCE(MIN(n.id), 9223372036854775807) FROM messages n
                          WHERE n.chatId = :chatId AND n.role = 'USER' AND n.id > :messageId)
        )
        """,
    )
    suspend fun turnUsedTools(chatId: Long, messageId: Long): Boolean

    /** The message just before [messageId] in its chat, skipping tool records. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id < :messageId AND role != 'TOOL' ORDER BY id DESC LIMIT 1")
    suspend fun previousTurn(chatId: Long, messageId: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE createdAt < :cutoff")
    suspend fun deleteMessagesBefore(cutoff: Long): Int

    /** Chats left with no messages, and older than [cutoff] (a brand-new chat is left alone). */
    @Query("DELETE FROM chats WHERE updatedAt < :cutoff AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.chatId = chats.id)")
    suspend fun deleteEmptyChatsBefore(cutoff: Long): Int
}
