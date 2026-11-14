package com.local.assistant.files

import com.local.assistant.gallery.PhotoPeriod
import com.local.assistant.gallery.PhotoReader
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.AttachedDocument
import com.local.assistant.memory.tools.FileRef
import com.local.assistant.memory.tools.FileSet
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolError
import java.io.File
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
    /** Documents attached to messages in a chat, so a later question about one finds it. */
    private val attached: suspend (chatId: Long) -> List<AttachedDocument> = { emptyList() },
) {

    internal suspend fun read(
        name: String?,
        typeSaid: String?,
        whenSaid: String?,
        question: String?,
        onStep: (String) -> Unit = {},
        chatId: Long? = null,
    ): ActionResult {
        val asked = name?.trim()?.take(MAX_NAME).orEmpty()
        val type = typeSaid?.let { FileNames.Type.of(it) }?.takeIf { it != FileNames.Type.ANY } ?: FileNames.typeIn(asked) ?: FileNames.Type.ANY
        val words = FileNames.searchWords(asked)
        // Attached earlier in this chat ("what's the due date in it?" after a bill was attached):
        // those first, and found without All files access.
        val inChat = if (chatId == null || words.isEmpty() || type == FileNames.Type.FOLDER) {
            emptyList()
        } else {
            val documents = attached(chatId).map(::foundOf).filter { type.allows(it.name) }
            FileNames.rank(words, documents, now().toInstant().toEpochMilli(), MAX_IN_CHAT) { it.entry }
        }
        val canSearch = ready()
        if (!canSearch && inChat.isEmpty()) throw ToolError("The app can't see the phone's files. Tell the user to allow All files access for it in Settings, under Files.")
        if (type == FileNames.Type.FOLDER) return findFolders(asked)
        val period = whenSaid?.trim()?.takeIf { it.isNotEmpty() }?.let {
            PhotoPeriod.parse(it, now()) ?: throw ToolError("Couldn't tell when \"$it\" is. Ask the user for a day, month or year.")
        }
        if (words.isEmpty() && type == FileNames.Type.ANY && period == null) {
            throw ToolError("Say which file: words from its name, its kind (pdf, word, spreadsheet, slides) or when it was saved.")
        }
        val notes = ArrayList<String>()
        var found = if (canSearch) find(asked, type, period) else emptyList()
        // "My rental agreement pdf" that was saved as a Word file: the name matters more than the kind.
        if (canSearch && found.isEmpty() && type != FileNames.Type.ANY && words.isNotEmpty()) {
            found = find(asked, FileNames.Type.ANY, period)
            if (found.isNotEmpty()) notes += "None of them is a ${type.name.lowercase(Locale.ROOT)}; these are named like it."
        }
        // The attached copy, not the phone's own of the same file too (what was attached from Download).
        if (inChat.isNotEmpty()) found = inChat + found.filterNot { file -> inChat.any { it.name == file.name && it.sizeBytes == file.sizeBytes } }
        if (period != null) found = found.filter { it.modifiedMs in period }
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
            files = FileSet(label, found.map(::refOf), read = chosen?.name, readPath = chosen?.path),
        )
        return ActionResult(result, chip, reply = reply)
    }

    /**
     * A document attached to the user's message, read for [question], or summarised when there's
     * none or it asks for that. The reply is the answer itself: the document is already shown
     * with the message, so nothing is put under the reply.
     */
    internal suspend fun readAttached(document: AttachedDocument, question: String?, onStep: (String) -> Unit = {}): ActionResult {
        val found = foundOf(document)
        onStep("Reading ${found.name}")
        val answer = reader.answer(found.name, read(found), question?.trim()?.takeIf { it.isNotEmpty() }, onStep)
        return ActionResult(
            linkedMapOf("ok" to true, "attached" to found.name, "answered" to "read on its own"),
            reply = answer,
        )
    }

    /**
     * Notes on a document attached to a request to act on it ("remind me to pay this bill"): what
     * it is and the names, amounts and dates the request needs, for the chat model to use with its
     * tools ([DocumentReader.gist]). Kept as the call's result, so the chat's history has them.
     */
    internal suspend fun notesOnAttached(document: AttachedDocument, request: String): ActionResult {
        val found = foundOf(document)
        val text = read(found)
        val notes = reader.gist(found.name, text, request)
        val result = linkedMapOf<String, Any?>("ok" to true, "attached" to found.name)
        if (notes != null) {
            result["notes"] = notes
        } else {
            result["note"] = "Couldn't read it" + if (text is DocumentText.Read.Unreadable) ": ${text.why.said}." else " just now."
        }
        return ActionResult(result)
    }

    /**
     * The ID number [field] asks for ("what's my PAN number?"), from a document named after its
     * card ("e-PAN.pdf", "EAadhaar_….pdf"): its text read here, in well under a second, and a
     * number taken only when it has that number's shape and check digit ([PhotoReader.Field.valueIn]).
     * Pictures of pages are left to the photos. The ones locked with a password are named, so
     * the reply can say why they weren't read.
     */
    internal suspend fun idNumber(field: PhotoReader.Field): IdFiles {
        val names = ID_FILE_NAMES[field] ?: return IdFiles(null)
        if (!ready()) return IdFiles(null)
        val candidates = LinkedHashMap<String, FileFinder.Found>()
        for (name in names) {
            for (found in find(name, FileNames.Type.ANY, null)) {
                if (DocumentText.isDocument(found.name, found.mime)) candidates.putIfAbsent(found.path ?: "media:${found.mediaId}", found)
            }
            if (candidates.size >= MAX_ID_FILES) break
        }
        val locked = ArrayList<FileFinder.Found>()
        for (found in candidates.values.take(MAX_ID_FILES)) {
            val words = when (val text = read(found)) {
                is DocumentText.Read.Text -> text.text
                // A short PDF with little text is looked at elsewhere; the text it has may still hold the number.
                is DocumentText.Read.Scanned -> text.text?.text
                is DocumentText.Read.Unreadable -> {
                    if (text.why == DocumentText.Why.PASSWORD) locked += found
                    null
                }
            }
            val value = words?.let(field::valueIn) ?: continue
            val chip = MemoryChip(MemoryChip.Kind.FILES, "Files", found.name, files = FileSet(field.document, listOf(refOf(found)), read = found.name, readPath = found.path))
            return IdFiles(
                ActionResult(
                    linkedMapOf("ok" to true, "answered" to "from the file ${found.name}, read on its own"),
                    chip,
                    reply = "Your ${field.document} file, ${found.name}, shows your ${field.label}: $value.",
                ),
            )
        }
        return IdFiles(null, locked)
    }

    /** What the files gave for an ID number: the answer, or the files that couldn't be opened. */
    class IdFiles(val answer: ActionResult?, val locked: List<FileFinder.Found> = emptyList()) {
        /** "I also found e-PAN.pdf in Download, but it's locked with a password…": null when none was. */
        val lockedNote: String?
            get() {
                if (locked.isEmpty()) return null
                val named = locked.take(2).joinToString(" and ") { found ->
                    val folder = found.folder.trimEnd('/').substringAfterLast('/')
                    if (folder.isEmpty()) found.name else "${found.name} in $folder"
                }
                return if (locked.size == 1) {
                    "I also found $named, but it's locked with a password, so I couldn't read it. Open it in your Files app to see the number."
                } else {
                    "I also found $named, but they're locked with passwords, so I couldn't read them. Open them in your Files app to see the number."
                }
            }
    }

    private fun foundOf(document: AttachedDocument): FileFinder.Found {
        val file = File(document.path)
        return FileFinder.Found(document.name, ATTACHED_FOLDER, document.path, mediaId = null, file.length(), file.lastModified(), mime = null)
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
        private const val MAX_IN_CHAT = 3

        /** Documents read at most for an ID number: each takes up to a few hundred ms. */
        private const val MAX_ID_FILES = 4

        /** What a file holding each ID number is called by ("e-PAN", "EAadhaar_1234"); none for bank cards. */
        private val ID_FILE_NAMES = mapOf(
            PhotoReader.Field.AADHAAR to listOf("aadhaar", "aadhar"),
            PhotoReader.Field.PAN to listOf("pan"),
            PhotoReader.Field.DRIVING_LICENCE to listOf("driving licence", "driving license", "licence", "license"),
            PhotoReader.Field.PASSPORT to listOf("passport"),
        )

        /** Where a document attached in the chat is, as the results and the list under the reply say. */
        const val ATTACHED_FOLDER = "Attached in this chat"
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
