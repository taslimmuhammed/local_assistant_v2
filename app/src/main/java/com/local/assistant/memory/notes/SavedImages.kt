package com.local.assistant.memory.notes

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.db.NoteDao
import com.local.assistant.memory.db.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** An image the user sent, found for `remember_image`. */
data class FoundImage(val chatId: Long, val messageId: Long, val path: String)

/** What the tools need from saved images; a map in tests. */
interface ImageNotes {
    /** The newest image the user sent in [chatId], no more than a few messages before [upToMessageId]. */
    suspend fun findImage(chatId: Long, upToMessageId: Long): FoundImage?

    /** Keeps a copy of [image] with what the model saw in it; returns the note's id. */
    suspend fun save(title: String, details: String, image: FoundImage): Long

    suspend fun delete(noteId: Long)
}

/**
 * Images the user asked to have remembered. Each is copied out of the chat's attachments into a
 * directory of its own, so it outlives the chat, and kept with the title and details the model
 * gave when saving it — which is what recall matches later questions against.
 */
class SavedImages(
    filesDir: File,
    private val notes: NoteDao,
    private val chats: ChatRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ImageNotes, com.local.assistant.memory.retrieval.NoteSource {

    private val dir = File(filesDir, DIRECTORY)

    override suspend fun findImage(chatId: Long, upToMessageId: Long): FoundImage? =
        chats.messagesFor(chatId)
            .filter { it.id <= upToMessageId }
            .takeLast(LOOKBACK)
            .lastOrNull { it.attachmentKind == AttachmentKind.IMAGE && it.attachmentPath?.let(::File)?.isFile == true }
            ?.let { FoundImage(it.chatId, it.id, it.attachmentPath!!) }

    override suspend fun save(title: String, details: String, image: FoundImage): Long {
        val copy = withContext(Dispatchers.IO) {
            dir.mkdirs()
            val source = File(image.path)
            File(dir, UUID.randomUUID().toString() + "." + source.extension.ifEmpty { "jpg" }).also { source.copyTo(it) }
        }
        val now = clock()
        return notes.insert(
            NoteEntity(
                title = title,
                details = details,
                imagePath = copy.absolutePath,
                sourceMessageId = image.messageId,
                chatId = image.chatId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** The note and its copy of the image. */
    override suspend fun delete(noteId: Long) {
        val note = notes.byId(noteId) ?: return
        notes.delete(noteId)
        note.imagePath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
    }

    /** Deletes the row but keeps the image, so an undo can bring it all back; [prune] tidies up. */
    suspend fun remove(note: NoteEntity) = notes.delete(note.id)

    suspend fun restore(note: NoteEntity) {
        notes.insert(note)
    }

    suspend fun deleteAll() {
        notes.deleteAll()
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    override suspend fun matching(match: String): List<NoteEntity> = notes.matching(match)

    override suspend fun embedded(modelId: String): List<NoteEntity> = notes.embedded(modelId)

    /** Image files no note refers to any more. */
    suspend fun prune(): Int {
        val kept = notes.imagePaths().toSet()
        return withContext(Dispatchers.IO) {
            dir.listFiles()?.count { it.absolutePath !in kept && it.delete() } ?: 0
        }
    }

    private companion object {
        const val DIRECTORY = "saved_images"

        /** "Remember this" usually comes with the image or right after it. */
        const val LOOKBACK = 6
    }
}
