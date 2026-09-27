package com.local.assistant.memory.work

import android.util.Log
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.memory.db.AppStateEntity
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.SummaryStatus
import com.local.assistant.memory.notes.ImageDescriber
import com.local.assistant.memory.notes.SavedImages
import kotlinx.coroutines.delay
import java.io.File

/**
 * Once, the first time the model runs with its text decoder in fp32
 * ([com.local.assistant.llm.ModelPrecision]): puts right what it wrote before, in fp16, when it
 * garbled digits past token 2,048.
 *
 * Left alone, those numbers outlive the fix. The fp32 model copies what it is given exactly,
 * including its own old mistakes: asked for a roll number, it read "43610104" out of an archived
 * fp16 reply rather than 4361044 off the certificate, and a saved image's details said 436104.
 *
 * - Saved images are read again from their photos, their numbers checked with a second read,
 *   and the new details replace the old ones.
 * - Archived exchanges keep the user's words but lose a reply with digits in it.
 * - Session and rolling summaries with digits in them go; the chats themselves keep everything.
 *
 * What the user typed was never garbled, so facts and the chats' own messages are not touched.
 */
class PrecisionUpgrade(
    private val database: AppDatabase,
    private val archive: ArchiveRepository,
    private val images: SavedImages,
    /** A saved image read again, holding the model; null when the user needed it back first. */
    private val reread: suspend (path: String) -> ImageDescriber.Description?,
    /** The prompt's summary and the archive's vectors changed. */
    private val onChanged: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    data class Report(val exchanges: Int, val summaries: Int, val images: Int)

    /** Null when there was nothing left to do. */
    suspend fun run(): Report? {
        val state = database.appStateDao()
        val archiveDone = state.get(KEY_DONE) == VERSION
        val imagesDone = state.get(KEY_IMAGES_DONE) == IMAGES_VERSION
        if (archiveDone && imagesDone) return null
        // When fp32 began, kept across restarts: what was written after it needs nothing.
        val since = state.get(KEY_SINCE)?.toLongOrNull()
            ?: clock().also { state.put(AppStateEntity(KEY_SINCE, it.toString())) }

        var exchanges = 0
        var summaries = 0
        if (!archiveDone) {
            exchanges = archive.dropRepliesWithNumbers(since)
            val sessions = database.sessionDao()
            for (session in sessions.summarised()) {
                val ended = session.endedAt ?: continue
                if (ended < since && session.summary.orEmpty().any(Char::isDigit)) {
                    sessions.setSummary(session.id, null, SummaryStatus.DONE)
                    summaries++
                }
            }
            val chats = database.chatDao()
            for (chat in chats.summarisedChats()) {
                if (chat.rollingSummary.orEmpty().any(Char::isDigit)) {
                    // The turns it covered are replayed again, or summarised afresh when they don't fit.
                    chats.setRollingSummary(chat.id, null, null)
                    summaries++
                }
            }
            onChanged()
            state.put(AppStateEntity(KEY_DONE, VERSION))
        }

        var reread = 0
        if (!imagesDone) {
            // Every saved image read before this version of the reading, which now checks its
            // numbers with a second read ([ImageDescriber]); kept across restarts like [since].
            val before = state.get(KEY_IMAGES_SINCE)?.toLongOrNull()
                ?: clock().also { state.put(AppStateEntity(KEY_IMAGES_SINCE, it.toString())) }
            for (note in database.noteDao().all()) {
                if (note.updatedAt >= before) continue
                val path = note.imagePath?.takeIf { File(it).isFile } ?: continue
                var description: ImageDescriber.Description? = null
                for (attempt in 1..MAX_ATTEMPTS) {
                    description = reread(path)
                    if (description != null) break
                    delay(retryDelayMs)
                }
                if (description == null) {
                    Log.w(TAG, "Saved image ${note.id} could not be read again; its details stay as they were")
                    continue
                }
                images.rewriteDetails(note.id, description.details)
                reread++
            }
            if (reread > 0) onChanged()
            state.put(AppStateEntity(KEY_IMAGES_DONE, IMAGES_VERSION))
        }
        return Report(exchanges, summaries, reread).also { Log.i(TAG, "Put right after the move to fp32: $it") }
    }

    private companion object {
        const val TAG = "PrecisionUpgrade"
        const val KEY_DONE = "precision.fp32_cleanup"
        const val KEY_SINCE = "precision.fp32_since"
        const val VERSION = "1"

        /** Saved images, apart: "2" is the reading that checks its numbers a second time. */
        const val KEY_IMAGES_DONE = "precision.images_reread"
        const val KEY_IMAGES_SINCE = "precision.images_reread_since"
        const val IMAGES_VERSION = "2"

        /** Reading an image again waits for the user to leave the model be; half a minute at a time. */
        const val RETRY_DELAY_MS = 30_000L
        const val MAX_ATTEMPTS = 40
    }
}
