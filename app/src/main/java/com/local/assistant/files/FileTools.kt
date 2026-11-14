package com.local.assistant.files

import com.local.assistant.gallery.PhotoPeriod
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.FileRef
import com.local.assistant.memory.tools.FileSet
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolError
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * read_file: files and folders on the phone found by name or kind, shown under the reply; with a
 * question, the best-matching document read and the question answered from it on its own
 * ([DocumentReader]), which ends the turn with that answer, as find_photos does with a photo.
 */
class FileTools(
    private val find: suspend (asked: String, type: FileNames.Type, period: PhotoPeriod.Range?) -> List<FileFinder.Found>,
    private val folders: suspend (asked: String) -> List<FileFinder.Folder>,
    private val read: suspend (FileFinder.Found) -> DocumentText.Read,
    private val reader: DocumentReader,
    private val now: () -> ZonedDateTime,
    /** All files access is given. */
    private val ready: () -> Boolean,
) {

    internal suspend fun read(name: String?, typeSaid: String?, whenSaid: String?, question: String?, onStep: (String) -> Unit = {}): ActionResult {
        if (!ready()) throw ToolError("The app can't see the phone's files. Tell the user to allow All files access for it in Settings, under Files.")
        val asked = name?.trim()?.take(MAX_NAME).orEmpty()
        val type = typeSaid?.let { FileNames.Type.of(it) }?.takeIf { it != FileNames.Type.ANY } ?: FileNames.typeIn(asked) ?: FileNames.Type.ANY
        if (type == FileNames.Type.FOLDER) return findFolders(asked)
        val period = whenSaid?.trim()?.takeIf { it.isNotEmpty() }?.let {
            PhotoPeriod.parse(it, now()) ?: throw ToolError("Couldn't tell when \"$it\" is. Ask the user for a day, month or year.")
        }
        if (FileNames.searchWords(asked).isEmpty() && type == FileNames.Type.ANY && period == null) {
            throw ToolError("Say which file: words from its name, its kind (pdf, word, spreadsheet, slides) or when it was saved.")
        }
        val notes = ArrayList<String>()
        var found = find(asked, type, period)
        // "My rental agreement pdf" that was saved as a Word file: the name matters more than the kind.
        if (found.isEmpty() && type != FileNames.Type.ANY && FileNames.searchWords(asked).isNotEmpty()) {
            found = find(asked, FileNames.Type.ANY, period)
            if (found.isNotEmpty()) notes += "None of them is a ${type.name.lowercase(Locale.ROOT)}; these are named like it."
        }
        if (found.isEmpty()) {
            return ActionResult(
                linkedMapOf(
                    "ok" to true,
                    "found" to 0,
                    "note" to "No file on this phone is named like that${if (period != null) " from then" else ""}. Say so in one short sentence; " +
                        "if it's a card or ID they may have it as a photo (find_photos).",
                ),
            )
        }
        val label = asked.ifEmpty { type.name.lowercase(Locale.ROOT) }
        val wanted = question?.trim()?.takeIf { it.isNotEmpty() }
        // The one read: the best match that is a document, which the reply names.
        val chosen = if (wanted != null) found.firstOrNull { DocumentText.isDocument(it.name, it.mime) } ?: found.first() else null
        val result = linkedMapOf<String, Any?>(
            "ok" to true,
            "found" to found.size,
            "files" to found.map { describe(it) },
        )
        var reply: String? = null
        if (chosen != null) {
            onStep("Reading ${chosen.name}")
            val text = read(chosen)
            val answer = reader.answer(chosen.name, text, wanted, onStep)
            reply = if (text is DocumentText.Read.Unreadable) answer else "From ${chosen.name}:\n\n$answer"
            result["answered"] = "from ${chosen.name}, read on its own"
        } else {
            notes += "The app shows them under your reply, and the user can tap one to open it: say in one short sentence what you found, naming the best match. Don't list them all, and don't say what's in them: you haven't read them."
        }
        if (notes.isNotEmpty()) result["note"] = notes.joinToString(" ")
        val chip = MemoryChip(
            MemoryChip.Kind.FILES,
            "Files",
            if (found.size == 1) found.first().name else "${found.size} files for \"$label\"",
            files = FileSet(label, found.map(::refOf), read = chosen?.name),
        )
        return ActionResult(result, chip, reply = reply)
    }

    private suspend fun findFolders(asked: String): ActionResult {
        if (FileNames.searchWords(asked).isEmpty()) throw ToolError("Say which folder, by its name.")
        val found = folders(asked)
        if (found.isEmpty()) {
            return ActionResult(linkedMapOf("ok" to true, "found" to 0, "note" to "No folder on this phone is named like that. Say so in one short sentence."))
        }
        val result = linkedMapOf<String, Any?>(
            "ok" to true,
            "found" to found.size,
            "folders" to found.map { mapOf("name" to it.name, "path" to it.path.trimEnd('/'), "files" to it.files) },
            "note" to "The app shows them under your reply, and the user can tap one to open it in their Files app: say in one short sentence what you found.",
        )
        val refs = found.map { FileRef(it.name, it.path, path = null, modifiedMs = it.latestMs, folder = true, count = it.files) }
        val chip = MemoryChip(MemoryChip.Kind.FILES, "Folders", if (found.size == 1) found.first().name else "${found.size} folders for \"$asked\"", files = FileSet(asked, refs))
        return ActionResult(result, chip)
    }

    private fun describe(found: FileFinder.Found): Map<String, Any?> = linkedMapOf(
        "name" to found.name,
        "folder" to found.folder.trimEnd('/').ifEmpty { "(top of storage)" },
        "saved" to DATE.format(Instant.ofEpochMilli(found.modifiedMs).atZone(now().zone)),
        "size" to sizeOf(found.sizeBytes),
    )

    companion object {
        private const val MAX_NAME = 80
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        fun refOf(found: FileFinder.Found) = FileRef(found.name, found.folder, found.path, found.mediaId, found.sizeBytes, found.modifiedMs, found.mime)

        /** "2.1 MB", "340 KB", "12 bytes". */
        fun sizeOf(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ENGLISH, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes bytes"
        }

        /**
         * The user's own words when they asked what a file says or for a summary: the model left
         * question out of find_photos' calls some of the time, and reworded it at others (PhotoTools).
         */
        fun questionIn(userText: String): String? {
            val text = userText.trim()
            if (text.isEmpty()) return null
            return text.takeIf { DocumentReader.Plan.isSummary(it) || ASKS_WHAT.containsMatchIn(it.lowercase(Locale.ROOT)) }
        }

        /** "what does my lease say about pets", "what's the deposit in my rental agreement". */
        private val ASKS_WHAT = Regex("\\b(what|how much|how many|when|who|which|where)\\b.*\\b(in|on|from|of) (my|the|this|that) \\w+.*\\b(pdf|doc|docx|document|file|agreement|lease|contract|report|invoice|statement|policy|resume|cv|letter|notes)\\b|\\bwhat does (my|the) .+ say\\b")
    }
}
