package com.local.assistant.files

import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A file asked for by name, matched against the names on the phone: "rental agreement" against
 * `Rental_Agreement-2024 final.pdf`, "resume" against `TaslimResume.docx`, "pan card" against
 * `e-PAN.pdf`. Only names and folders: what is inside a file is read when it's asked for
 * ([DocumentText]), never indexed (the user, 13 Nov 2026: "just index the file names").
 */
object FileNames {

    /** The kinds a file can be asked for by, and their extensions. */
    enum class Type(val extensions: Set<String>) {
        PDF(setOf("pdf")),
        WORD(setOf("docx", "doc", "odt", "rtf", "docm", "dotx")),
        SPREADSHEET(setOf("xlsx", "xls", "ods", "csv", "tsv", "xlsm")),
        SLIDES(setOf("pptx", "ppt", "odp", "ppsx")),
        TEXT(setOf("txt", "md", "markdown", "log", "json", "xml", "html", "htm")),
        AUDIO(setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "amr", "flac", "3gp")),
        ARCHIVE(setOf("zip", "rar", "7z", "tar", "gz", "apk")),

        /** Anything readable as a document: PDF, Word, spreadsheet, slides or text. */
        DOCUMENT(PDF.extensions + WORD.extensions + SPREADSHEET.extensions + SLIDES.extensions + TEXT.extensions),

        /** A folder, found by its name in the paths of the files in it. */
        FOLDER(emptySet()),

        /** Any file but a photo or a video (those are find_photos'). */
        ANY(emptySet()),
        ;

        fun allows(name: String): Boolean = when (this) {
            ANY, FOLDER -> true
            else -> extensionOf(name) in extensions
        }

        companion object {
            /** The type the model gave, in its words or the enum's; [ANY] when it gave none it knows. */
            fun of(said: String?): Type {
                val word = said?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (word.isEmpty()) return ANY
                entries.firstOrNull { it.name.lowercase(Locale.ROOT) == word }?.let { return it }
                entries.firstOrNull { word in it.extensions && it != DOCUMENT }?.let { return it }
                return TYPE_WORDS[word] ?: ANY
            }
        }
    }

    /** A file on the phone, as its name, folder and date are known. */
    data class Entry(
        val name: String,
        /** Its folder from the storage root, `Download/Bank/`; empty when unknown. */
        val folder: String,
        val modifiedMs: Long,
    )

    /**
     * The words in [name] that a file could be called by: "my rental agreement pdf" gives
     * `rental`, `agreement`. Type words ("pdf", "document", "file") and filler are left out, so a
     * name of only those finds the latest files of the type.
     */
    fun searchWords(name: String): List<String> =
        WORD.findAll(name.lowercase(Locale.ROOT).replace('’', '\''))
            .map { it.value.trim('\'') }
            .filter { it.isNotEmpty() && it !in FILLER && it !in TYPE_WORDS && Type.entries.none { t -> it == t.name.lowercase(Locale.ROOT) } }
            .distinct()
            .toList()

    /** The kind of file [name] says it is ("my resume pdf"), when it says one. */
    fun typeIn(name: String?): Type? =
        name?.let { WORD.findAll(it.lowercase(Locale.ROOT)).map { m -> m.value.trim('\'') }.firstNotNullOfOrNull { word -> TYPE_WORDS[word]?.takeIf { t -> t != Type.ANY } } }

    /**
     * The parts a file name is written in: `TaslimResume_v2-final.docx` gives `taslim`, `resume`,
     * `v`, `2`, `final`. Split at anything not a letter or digit, at a lower-to-upper case change,
     * and between letters and digits; the extension is left out.
     */
    fun parts(fileName: String): List<String> {
        val stem = fileName.substringBeforeLast('.', fileName).ifEmpty { fileName }
        val parts = ArrayList<String>()
        val current = StringBuilder()
        fun cut() {
            if (current.isNotEmpty()) parts += current.toString().lowercase(Locale.ROOT)
            current.setLength(0)
        }
        var previous: Char? = null
        for (c in stem) {
            if (!c.isLetterOrDigit()) {
                cut()
                previous = null
                continue
            }
            val p = previous
            if (p != null && ((p.isLowerCase() && c.isUpperCase()) || (p.isLetter() != c.isLetter()))) cut()
            current.append(c)
            previous = c
        }
        cut()
        return parts
    }

    /** The extension, lower case, without the dot; empty when there's none. */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }.orEmpty()

    /**
     * How well [entry] answers [words] (from [searchWords]), 0 when it doesn't: each word scores
     * by its best match in the name (whole part 1, the start of a part 0.85, inside the name 0.6,
     * one letter off 0.7), at half that in the folder's names; averaged over the words, plus a
     * little for a recent file, so of two equal names the newer comes first. With no words, only
     * how recent it is.
     */
    fun score(words: List<String>, entry: Entry, nowMs: Long): Double {
        val recency = RECENT_WEIGHT * exp(-max(0L, nowMs - entry.modifiedMs).toDouble() / RECENT_SCALE_MS)
        if (words.isEmpty()) return recency + MIN_SCORE
        val nameParts = parts(entry.name)
        val joined = nameParts.joinToString("")
        val folderParts = entry.folder.split('/').filter { it.isNotEmpty() }.flatMap { parts(it) }
        var total = 0.0
        var matched = 0
        for (word in words) {
            val inName = match(word, nameParts, joined)
            val inFolder = FOLDER_WEIGHT * match(word, folderParts, folderParts.joinToString(""))
            val best = max(inName, inFolder)
            if (best > 0) matched++
            total += best
        }
        if (matched == 0) return 0.0
        return total / words.size + recency
    }

    /** The best match of [word] among [parts] (and their run together, [joined]); 0 when none. */
    private fun match(word: String, parts: List<String>, joined: String): Double {
        var best = 0.0
        for (part in parts) {
            val score = when {
                part == word -> 1.0
                // "agreement" for `agreements`, "aadhar" for `aadhaarcard`.
                word.length >= 3 && part.startsWith(word) -> 0.85
                part.length >= 3 && word.startsWith(part) && part.length * 4 >= word.length * 3 -> 0.85
                word.length >= 5 && part.length >= 4 && within1(word, part) -> 0.7
                else -> 0.0
            }
            best = max(best, score)
        }
        // "resume" in `taslimresume`, one word in the name.
        if (best < 0.6 && word.length >= 4 && word in joined) best = 0.6
        return best
    }

    /** Whether [a] and [b] are at most one letter added, dropped or changed apart. */
    internal fun within1(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        edits += (a.length - i) + (b.length - j)
        return edits <= 1
    }

    /**
     * The search for a LIKE query: the start of each word (4 letters of a long one, so a slip
     * past them still finds it), and for each the part it must contain.
     */
    fun likeParts(words: List<String>): List<String> =
        words.map { if (it.length >= 6) it.take(min(4, it.length)) else it }.distinct()

    /** Ranked best first, at most [limit], those scoring under [MIN_SCORE] left out. */
    fun <T> rank(words: List<String>, items: List<T>, nowMs: Long, limit: Int, entryOf: (T) -> Entry): List<T> =
        items.map { it to score(words, entryOf(it), nowMs) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /**
     * A name scores at least this to count: one of two words whole, or one word inside the name.
     * At 0.3, "pan card" found 6 files on the phone (14 Nov 2026) where only 2 were about it,
     * the others having "card" somewhere inside their names.
     */
    const val MIN_SCORE = 0.4

    private const val FOLDER_WEIGHT = 0.5
    private const val RECENT_WEIGHT = 0.1
    private const val RECENT_SCALE_MS = 90L * 24 * 60 * 60_000

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}']+")

    /** Kinds of file, in people's words. */
    private val TYPE_WORDS = mapOf(
        "pdf" to Type.PDF, "pdfs" to Type.PDF,
        "word" to Type.WORD, "doc" to Type.WORD, "docx" to Type.WORD,
        "excel" to Type.SPREADSHEET, "spreadsheet" to Type.SPREADSHEET, "spreadsheets" to Type.SPREADSHEET,
        "sheet" to Type.SPREADSHEET, "sheets" to Type.SPREADSHEET, "xlsx" to Type.SPREADSHEET, "csv" to Type.SPREADSHEET,
        "slides" to Type.SLIDES, "slide" to Type.SLIDES, "presentation" to Type.SLIDES, "presentations" to Type.SLIDES,
        "powerpoint" to Type.SLIDES, "ppt" to Type.SLIDES, "pptx" to Type.SLIDES, "deck" to Type.SLIDES,
        "text" to Type.TEXT, "txt" to Type.TEXT,
        "audio" to Type.AUDIO, "recording" to Type.AUDIO, "recordings" to Type.AUDIO, "song" to Type.AUDIO, "mp3" to Type.AUDIO,
        "zip" to Type.ARCHIVE, "apk" to Type.ARCHIVE, "archive" to Type.ARCHIVE,
        "document" to Type.DOCUMENT, "documents" to Type.DOCUMENT, "docs" to Type.DOCUMENT,
        "folder" to Type.FOLDER, "folders" to Type.FOLDER, "directory" to Type.FOLDER,
        "file" to Type.ANY, "files" to Type.ANY,
    )

    private val FILLER = setOf(
        "the", "a", "an", "my", "mine", "our", "your", "of", "for", "in", "on", "at", "to", "from", "with",
        "and", "or", "this", "that", "these", "those", "me", "i", "find", "open", "show", "read", "search",
        "look", "get", "copy", "latest", "last", "new", "newest", "recent", "old", "downloaded", "saved",
        "phone", "called", "named", "about", "please", "some", "any", "all", "which", "where", "is", "it",
    )
}
