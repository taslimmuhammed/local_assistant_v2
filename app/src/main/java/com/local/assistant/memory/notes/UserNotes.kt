package com.local.assistant.memory.notes

import com.local.assistant.memory.db.UserNoteChunkEntity
import com.local.assistant.memory.db.UserNoteDao
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.retrieval.UserNoteSource
import kotlinx.coroutines.flow.Flow

/** What edit_note needs from the user's notes; a map in tests. */
interface NoteStore {
    /** Pinned first, then the latest changed. */
    suspend fun all(): List<UserNoteEntity>

    suspend fun note(id: Long): UserNoteEntity?

    suspend fun insert(note: UserNoteEntity): Long

    suspend fun update(note: UserNoteEntity)

    suspend fun delete(id: Long)

    /** Puts a deleted note back as it was, under its own id. */
    suspend fun restore(note: UserNoteEntity)
}

/**
 * The user's notes and lists, and the parts of them recall searches. Every write remakes the
 * note's parts in the same transaction ([NoteChunks]), so what recall finds is always the note as
 * it is: a removed item is never recalled, an added one is found by its words at once and by its
 * meaning once the embedding queue has been round (parts whose text didn't change keep their
 * vectors).
 */
class UserNotes(
    private val dao: UserNoteDao,
    /** One transaction for a note and its parts; joins the tool's own when inside one. */
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : NoteStore, UserNoteSource {

    fun observe(): Flow<List<UserNoteEntity>> = dao.observe()

    override suspend fun all(): List<UserNoteEntity> = dao.all()

    override suspend fun note(id: Long): UserNoteEntity? = dao.byId(id)

    override suspend fun notes(ids: Collection<Long>): List<UserNoteEntity> = if (ids.isEmpty()) emptyList() else dao.byIds(ids)

    override suspend fun insert(note: UserNoteEntity): Long {
        var id = 0L
        transaction {
            id = dao.insert(note.copy(id = 0))
            rechunk(note.copy(id = id))
        }
        return id
    }

    override suspend fun update(note: UserNoteEntity) = transaction {
        dao.update(note)
        rechunk(note)
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun restore(note: UserNoteEntity) = transaction {
        if (dao.byId(note.id) == null) dao.insert(note) else dao.update(note)
        rechunk(note)
    }

    /** [note]'s parts made again; those with the same words as before keep their vectors. */
    private suspend fun rechunk(note: UserNoteEntity) {
        val before = dao.chunksOf(note.id).associateBy { it.text }
        val parts = NoteChunks.of(note).map { part ->
            val kept = before[part.text]?.takeIf { it.embedding != null }
            if (kept == null) part else UserNoteChunkEntity(
                noteId = part.noteId, ordinal = part.ordinal, text = part.text, fromIndex = part.fromIndex, toIndex = part.toIndex,
                embedding = kept.embedding, modelId = kept.modelId,
            )
        }
        dao.deleteChunks(note.id)
        dao.insertChunks(parts)
    }

    override suspend fun chunksMatching(match: String): List<UserNoteChunkEntity> = dao.chunksMatching(match)

    override suspend fun embeddedChunks(modelId: String): List<UserNoteChunkEntity> = dao.embeddedChunks(modelId)
}
