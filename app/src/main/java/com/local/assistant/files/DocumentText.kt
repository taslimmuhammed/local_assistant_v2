package com.local.assistant.files

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * The words in a document, for reading one when it's asked about or attached; nothing is kept.
 * Word, PowerPoint and Excel files (and their OpenDocument kin) are zips of XML, read here with a
 * small tag scanner; HTML, RTF and plain text are read as text. PDFs are read by Android's own
 * renderer ([PdfText]). Old binary Office files (.doc, .xls, .ppt) and password-protected ones
 * aren't read: the reply says so.
 *
 * Pure, so it can be tested without a phone: the caller opens the file.
 */
object DocumentText {

    enum class Format { PDF, DOCX, PPTX, XLSX, ODF, TEXT, HTML, RTF, OLD_OFFICE, OTHER }

    sealed interface Read {
        /**
         * The text, cut at [MAX_CHARS] when longer ([cut]). [parts] is how many pages, slides or
         * sheets it has, when known; the text marks each ("[Page 3]").
         */
        data class Text(val text: String, val parts: Int? = null, val unit: String? = null, val cut: Boolean = false) : Read

        /**
         * A PDF to look at rather than read: up to [MAX_SCANNED_PAGES] pages as JPEG files, for the
         * vision model. [text] is the little text layer it has, if any, for when pictures can't be
         * looked at.
         */
        data class Scanned(val pages: List<File>, val pageCount: Int, val text: Text? = null) : Read

        data class Unreadable(val why: Why) : Read
    }

    enum class Why(val said: String) {
        PASSWORD("it's protected with a password"),
        OLD_FORMAT("it's in an old Office format I can't read (save it as .docx, .xlsx or PDF and I can)"),
        NOT_A_DOCUMENT("it isn't a document I can read"),
        DAMAGED("it seems to be damaged"),
        EMPTY("it has no text in it"),
        TOO_BIG("it's too big to read here"),
        MISSING("I couldn't open it"),
    }

    fun formatOf(name: String, mime: String? = null): Format = when (FileNames.extensionOf(name)) {
        "pdf" -> Format.PDF
        "docx", "docm", "dotx" -> Format.DOCX
        "pptx", "ppsx", "pptm" -> Format.PPTX
        "xlsx", "xlsm" -> Format.XLSX
        "odt", "ods", "odp" -> Format.ODF
        "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "ini", "srt", "vtt" -> Format.TEXT
        "html", "htm", "xhtml" -> Format.HTML
        "rtf" -> Format.RTF
        "doc", "xls", "ppt", "pps" -> Format.OLD_OFFICE
        else -> when {
            mime == "application/pdf" -> Format.PDF
            mime == "text/html" -> Format.HTML
            mime?.startsWith("text/") == true -> Format.TEXT
            else -> Format.OTHER
        }
    }

    /** Whether [name] is a document this can read (or name as unreadable): the attach button's filter. */
    fun isDocument(name: String, mime: String? = null): Boolean = formatOf(name, mime) != Format.OTHER

    /** The text of a document that isn't a PDF, from [input] (closed here). */
    fun read(format: Format, input: InputStream): Read = input.use { readOpen(format, BufferedInputStream(it)) }

    private fun readOpen(format: Format, stream: BufferedInputStream): Read {
        val head = peek(stream, 8)
        val ole = head.size >= 8 && OLE.indices.all { head[it] == OLE[it] }
        return when (format) {
            Format.DOCX, Format.PPTX, Format.XLSX, Format.ODF -> when {
                // An encrypted Office file is a compound file, not a zip.
                ole -> Read.Unreadable(Why.PASSWORD)
                !isZip(head) -> Read.Unreadable(Why.DAMAGED)
                else -> runCatching { fromZip(format, stream) }.getOrElse { Read.Unreadable(Why.DAMAGED) }
            }
            Format.OLD_OFFICE -> Read.Unreadable(Why.OLD_FORMAT)
            Format.TEXT -> plain(stream)
            Format.HTML -> textOf(decode(readCapped(stream) ?: return Read.Unreadable(Why.TOO_BIG)), ::html)
            Format.RTF -> textOf(String(readCapped(stream) ?: return Read.Unreadable(Why.TOO_BIG), Charsets.ISO_8859_1), ::rtf)
            Format.PDF, Format.OTHER -> Read.Unreadable(Why.NOT_A_DOCUMENT)
        }
    }

    private fun textOf(source: String, extract: (String) -> String): Read = finish(extract(source))

    /** Tidied, cut to [MAX_CHARS], or [Why.EMPTY]. */
    fun finish(text: String, parts: Int? = null, unit: String? = null): Read {
        val tidy = tidy(text)
        if (tidy.count(Char::isLetterOrDigit) < MIN_LETTERS) return Read.Unreadable(Why.EMPTY)
        val cut = tidy.length > MAX_CHARS
        return Read.Text(if (cut) tidy.take(MAX_CHARS) else tidy, parts, unit, cut)
    }

    // ---- Zipped XML: Word, PowerPoint, Excel, OpenDocument -------------------------------------

    private fun fromZip(format: Format, stream: InputStream): Read {
        val wanted: (String) -> Boolean = when (format) {
            Format.DOCX -> { name -> name == "word/document.xml" }
            Format.PPTX -> { name -> SLIDE.matches(name) }
            Format.XLSX -> { name -> name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" || SHEET.matches(name) }
            else -> { name -> name == "content.xml" }
        }
        val entries = HashMap<String, String>()
        var total = 0L
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !wanted(entry.name)) continue
                val bytes = readCapped(zip, MAX_ENTRY_BYTES) ?: return Read.Unreadable(Why.TOO_BIG)
                total += bytes.size
                if (total > MAX_ZIP_BYTES) return Read.Unreadable(Why.TOO_BIG)
                entries[entry.name] = String(bytes, Charsets.UTF_8)
            }
        }
        return when (format) {
            Format.DOCX -> entries["word/document.xml"]?.let { finish(docx(it)) } ?: Read.Unreadable(Why.DAMAGED)
            Format.PPTX -> {
                val slides = entries.filterKeys { SLIDE.matches(it) }.toSortedMap(compareBy { numberIn(it) })
                if (slides.isEmpty()) return Read.Unreadable(Why.EMPTY)
                val text = slides.values.mapIndexed { i, xml -> "[Slide ${i + 1}]\n" + pptx(xml) }.joinToString("\n\n")
                finish(text, slides.size, "slide")
            }
            Format.XLSX -> xlsx(entries)
            else -> entries["content.xml"]?.let { finish(odf(it)) } ?: Read.Unreadable(Why.DAMAGED)
        }
    }

    /** Word: the runs' text (`w:t`), a line per paragraph, a table's cells side by side. */
    internal fun docx(xml: String): String {
        val out = StringBuilder()
        var inText = false
        var cellDepth = 0
        scan(xml, onTag = { name, closing, selfClosing, attrs ->
            when (name) {
                "w:t" -> inText = !closing && !selfClosing
                "w:tab" -> if (attrs.isBlank() && !closing) out.append('\t')
                "w:br", "w:cr" -> if (!closing) out.append('\n')
                "w:p" -> if (closing) out.append(if (cellDepth > 0) " " else "\n")
                "w:tc" -> if (closing) { cellDepth--; out.trimEndSpaces().append(" | ") } else if (!selfClosing) cellDepth++
                "w:tr" -> if (closing) { out.dropSuffixes(" | ").append('\n') }
            }
        }, onText = { if (inText) out.append(it) })
        return out.toString()
    }

    /** PowerPoint: a slide's text (`a:t`), a line per paragraph. */
    internal fun pptx(xml: String): String {
        val out = StringBuilder()
        var inText = false
        scan(xml, onTag = { name, closing, selfClosing, _ ->
            when (name) {
                "a:t" -> inText = !closing && !selfClosing
                "a:br" -> if (!closing) out.append('\n')
                "a:p" -> if (closing) out.append('\n')
            }
        }, onText = { if (inText) out.append(it) })
        return out.toString()
    }

    /** Excel: each sheet by name, a line per row, cells side by side; shared strings looked up. */
    private fun xlsx(entries: Map<String, String>): Read {
        val shared = entries["xl/sharedStrings.xml"]?.let(::sharedStrings) ?: emptyList()
        val sheets = entries.filterKeys { SHEET.matches(it) }
        if (sheets.isEmpty()) return Read.Unreadable(Why.EMPTY)
        val ordered = sheetOrder(entries["xl/workbook.xml"], entries["xl/_rels/workbook.xml.rels"])
            .mapNotNull { (name, path) -> sheets[path]?.let { name to it } }
            .ifEmpty { sheets.toSortedMap(compareBy { numberIn(it) }).entries.mapIndexed { i, e -> "Sheet ${i + 1}" to e.value } }
        val text = ordered.joinToString("\n\n") { (name, xml) -> "[Sheet: $name]\n" + sheet(xml, shared) }
        return finish(text, ordered.size, "sheet")
    }

    internal fun sharedStrings(xml: String): List<String> {
        val strings = ArrayList<String>()
        val current = StringBuilder()
        var inText = false
        var phonetic = false
        scan(xml, onTag = { name, closing, selfClosing, _ ->
            when (name) {
                "si" -> if (closing) { strings += current.toString(); current.setLength(0) } else if (selfClosing) strings += ""
                "rPh" -> phonetic = !closing && !selfClosing
                "t" -> inText = !closing && !selfClosing
            }
        }, onText = { if (inText && !phonetic) current.append(it) })
        return strings
    }

    /** (sheet name, its path in the zip) in the workbook's order. */
    private fun sheetOrder(workbook: String?, rels: String?): List<Pair<String, String>> {
        if (workbook == null || rels == null) return emptyList()
        val targets = HashMap<String, String>()
        scan(rels, onTag = { name, closing, _, attrs ->
            if (name == "Relationship" && !closing) {
                val id = attr(attrs, "Id")
                val target = attr(attrs, "Target")
                if (id != null && target != null) targets[id] = "xl/" + target.removePrefix("/xl/").removePrefix("/")
            }
        }, onText = {})
        val order = ArrayList<Pair<String, String>>()
        scan(workbook, onTag = { name, closing, _, attrs ->
            if (name == "sheet" && !closing) {
                val id = attr(attrs, "r:id")
                val sheetName = attr(attrs, "name")
                val path = id?.let(targets::get)
                if (sheetName != null && path != null) order += sheetName to path
            }
        }, onText = {})
        return order
    }

    internal fun sheet(xml: String, shared: List<String>): String {
        val out = StringBuilder()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var type: String? = null
        var inValue = false
        var inInline = false
        scan(xml, onTag = { name, closing, selfClosing, attrs ->
            when (name) {
                "c" -> when {
                    selfClosing -> row += ""
                    !closing -> { type = attr(attrs, "t"); cell.setLength(0) }
                    else -> {
                        val raw = cell.toString()
                        row += when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let(shared::getOrNull).orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                    }
                }
                "v" -> inValue = !closing && !selfClosing
                "is" -> inInline = !closing && !selfClosing
                "t" -> if (inInline) inValue = !closing && !selfClosing
                "row" -> if (closing) {
                    while (row.isNotEmpty() && row.last().isBlank()) row.removeAt(row.lastIndex)
                    if (row.isNotEmpty()) out.append(row.joinToString(" | ")).append('\n')
                    row.clear()
                }
            }
        }, onText = { if (inValue) cell.append(it) })
        return out.toString()
    }

    /** OpenDocument (text, sheet or slides): paragraphs, headings, table rows and slides. */
    internal fun odf(xml: String): String {
        val out = StringBuilder()
        val body = xml.indexOf("<office:body").let { if (it >= 0) xml.substring(it) else xml }
        // A text document's tables have names too, which aren't sheets.
        val spreadsheet = "<office:spreadsheet" in body
        var slides = 0
        var cellOpen = false
        var inText = 0
        scan(body, onTag = { name, closing, selfClosing, attrs ->
            when (name) {
                "text:p", "text:h" -> when {
                    closing -> { inText--; out.append(if (cellOpen) " " else "\n") }
                    !selfClosing -> inText++
                }
                "text:s" -> if (!closing) out.append(" ".repeat((attr(attrs, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 8)))
                "text:tab" -> if (!closing) out.append('\t')
                "text:line-break" -> if (!closing) out.append('\n')
                "table:table" -> if (spreadsheet && !closing && !selfClosing) attr(attrs, "table:name")?.let { out.append("[Sheet: ").append(it).append("]\n") }
                "table:table-cell", "table:covered-table-cell" -> when {
                    closing -> { cellOpen = false; out.trimEndSpaces().append(" | ") }
                    selfClosing -> out.append(" | ")
                    else -> cellOpen = true
                }
                "table:table-row" -> if (closing) {
                    while (out.endsWith(" | ")) out.setLength(out.length - 3)
                    out.append('\n')
                }
                "draw:page" -> if (!closing && !selfClosing) out.append("\n[Slide ").append(++slides).append("]\n")
            }
        }, onText = { if (inText > 0) out.append(it) })
        return out.toString()
    }

    // ---- HTML, RTF, plain text ----------------------------------------------------------------

    internal fun html(source: String): String {
        val withoutCode = source
            .replace(Regex("(?is)<(script|style|head|noscript|svg)\\b.*?</\\1\\s*>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
        val out = StringBuilder()
        var i = 0
        while (i < withoutCode.length) {
            val open = withoutCode.indexOf('<', i)
            if (open < 0) {
                out.append(entities(withoutCode.substring(i)))
                break
            }
            out.append(entities(withoutCode.substring(i, open)))
            val close = withoutCode.indexOf('>', open)
            if (close < 0) break
            val inside = withoutCode.substring(open + 1, close).trim()
            val closing = inside.startsWith('/')
            val tag = inside.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
            when (tag) {
                "br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "table", "section", "article", "header", "footer", "ul", "ol", "blockquote", "pre" -> out.append('\n')
                "td", "th" -> out.append(if (closing) " | " else " ")
                else -> out.append(' ')
            }
            i = close + 1
        }
        // Spaces from the markup's own line breaks: each line squeezed, a row's last divider and
        // empty lines dropped.
        return out.lineSequence()
            .map { it.replace(Regex("[ \\t]+"), " ").trim().removeSuffix("|").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** RTF: the text, without its control words, font and colour tables, pictures and fields' code. */
    internal fun rtf(source: String): String {
        val out = StringBuilder()
        // Depth at which a skipped group started, or -1.
        var skipFrom = -1
        var depth = 0
        var pendingSkip = 0
        val bytes = ByteArrayOutputStream()
        fun flushBytes() {
            if (bytes.size() > 0) {
                if (skipFrom < 0) out.append(String(bytes.toByteArray(), WINDOWS_1252))
                bytes.reset()
            }
        }
        fun emit(s: String) {
            flushBytes()
            if (skipFrom < 0) out.append(s)
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '{' -> { flushBytes(); depth++; i++ }
                '}' -> {
                    flushBytes()
                    if (skipFrom >= 0 && depth <= skipFrom) skipFrom = -1
                    depth--
                    i++
                }
                '\\' -> {
                    if (i + 1 >= source.length) { i++; continue }
                    val next = source[i + 1]
                    when {
                        next == '\'' && i + 3 < source.length -> {
                            source.substring(i + 2, i + 4).toIntOrNull(16)?.let {
                                if (pendingSkip > 0) pendingSkip-- else if (skipFrom < 0) bytes.write(it)
                            }
                            i += 4
                        }
                        next == '\\' || next == '{' || next == '}' -> { emit(next.toString()); i += 2 }
                        next == '~' -> { emit(" "); i += 2 }
                        next == '_' -> { emit("-"); i += 2 }
                        next == '*' -> { if (skipFrom < 0) skipFrom = depth; i += 2 }
                        next == '\n' || next == '\r' -> { emit("\n"); i += 2 }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < source.length && source[j].isLetter()) j++
                            val word = source.substring(i + 1, j)
                            var k = j
                            if (k < source.length && (source[k] == '-' || source[k].isDigit())) {
                                k++
                                while (k < source.length && source[k].isDigit()) k++
                            }
                            val number = source.substring(j, k).toIntOrNull()
                            if (k < source.length && source[k] == ' ') k++
                            i = k
                            when (word) {
                                "par", "line", "sect", "page", "row" -> emit("\n")
                                "tab" -> emit("\t")
                                "cell" -> emit(" | ")
                                "emdash" -> emit("—")
                                "endash" -> emit("–")
                                "bullet" -> emit("•")
                                "lquote", "rquote" -> emit("'")
                                "ldblquote", "rdblquote" -> emit("\"")
                                "u" -> if (number != null) {
                                    emit(String(Character.toChars(if (number < 0) number + 65536 else number)))
                                    // The fallback for readers without Unicode that follows.
                                    pendingSkip = 1
                                }
                                in SKIPPED_RTF -> if (skipFrom < 0) skipFrom = depth
                            }
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else if (skipFrom < 0) {
                        if (c.code < 0x80) emit(c.toString()) else bytes.write(c.code and 0xFF)
                    }
                    i++
                }
            }
        }
        flushBytes()
        return out.toString()
    }

    private fun plain(stream: InputStream): Read {
        val bytes = readCapped(stream) ?: return Read.Unreadable(Why.TOO_BIG)
        val hasBom = bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        // Zero bytes in what should be text: a binary file with a text name.
        if (!hasBom && bytes.take(4096).any { it == 0.toByte() }) return Read.Unreadable(Why.NOT_A_DOCUMENT)
        return finish(decode(bytes))
    }

    /** Text bytes by their byte-order mark, else UTF-8, else Windows-1252 when UTF-8 doesn't fit. */
    internal fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val utf8 = String(bytes, Charsets.UTF_8)
        val broken = utf8.count { it == '�' }
        return if (broken > 0 && broken * 100 > utf8.length) String(bytes, WINDOWS_1252) else utf8
    }

    // ---- The tag scanner ---------------------------------------------------------------------

    /**
     * Walks [xml]: each tag's name, whether it closes (`</w:p>`) or closes itself (`<w:br/>`),
     * and its attributes as written; and the text between tags, entities decoded. Comments and
     * processing instructions are skipped; CDATA is text.
     */
    internal inline fun scan(
        xml: String,
        onTag: (name: String, closing: Boolean, selfClosing: Boolean, attrs: String) -> Unit,
        onText: (String) -> Unit,
    ) {
        var i = 0
        val n = xml.length
        while (i < n) {
            val open = xml.indexOf('<', i)
            if (open < 0) {
                if (i < n) onText(entities(xml.substring(i)))
                return
            }
            if (open > i) onText(entities(xml.substring(i, open)))
            when {
                xml.startsWith("<!--", open) -> {
                    val end = xml.indexOf("-->", open + 4)
                    i = if (end < 0) n else end + 3
                }
                xml.startsWith("<![CDATA[", open) -> {
                    val end = xml.indexOf("]]>", open + 9)
                    onText(xml.substring(open + 9, if (end < 0) n else end))
                    i = if (end < 0) n else end + 3
                }
                xml.startsWith("<?", open) || xml.startsWith("<!", open) -> {
                    val end = xml.indexOf('>', open)
                    i = if (end < 0) n else end + 1
                }
                else -> {
                    val end = xml.indexOf('>', open)
                    if (end < 0) return
                    val closing = xml[open + 1] == '/'
                    val selfClosing = xml[end - 1] == '/'
                    val nameStart = open + 1 + (if (closing) 1 else 0)
                    var nameEnd = nameStart
                    while (nameEnd < end && !xml[nameEnd].isWhitespace() && xml[nameEnd] != '/') nameEnd++
                    val name = xml.substring(nameStart, nameEnd)
                    val attrs = if (nameEnd < end) xml.substring(nameEnd, if (selfClosing) end - 1 else end) else ""
                    onTag(name, closing, selfClosing && !closing, attrs)
                    i = end + 1
                }
            }
        }
    }

    internal fun attr(attrs: String, name: String): String? {
        var from = 0
        while (true) {
            val at = attrs.indexOf("$name=", from)
            if (at < 0) return null
            val before = if (at == 0) ' ' else attrs[at - 1]
            val quoteAt = at + name.length + 1
            if (before.isWhitespace() && quoteAt < attrs.length && (attrs[quoteAt] == '"' || attrs[quoteAt] == '\'')) {
                val quote = attrs[quoteAt]
                val end = attrs.indexOf(quote, quoteAt + 1)
                if (end < 0) return null
                return entities(attrs.substring(quoteAt + 1, end))
            }
            from = at + 1
        }
    }

    internal fun entities(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i)
            if (end < 0 || end - i > 10) {
                out.append(c)
                i++
                continue
            }
            val entity = text.substring(i + 1, end)
            val decoded = when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[entity]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? = if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null

    // ---- Helpers ------------------------------------------------------------------------------

    /** Lines without trailing spaces, no-break spaces as spaces, at most one empty line in a row. */
    internal fun tidy(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ')
            .lineSequence().map { it.trimEnd() }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Spaces only: a line break before an empty cell is the row before's end. */
    private fun StringBuilder.trimEndSpaces(): StringBuilder {
        while (isNotEmpty() && last() == ' ') setLength(length - 1)
        return this
    }

    private fun StringBuilder.dropSuffixes(suffix: String): StringBuilder {
        while (endsWith(suffix)) setLength(length - suffix.length)
        return this
    }

    private fun numberIn(path: String): Int = Regex("(\\d+)\\.xml$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun peek(stream: BufferedInputStream, count: Int): ByteArray {
        stream.mark(count)
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val r = stream.read(bytes, read, count - read)
            if (r < 0) break
            read += r
        }
        stream.reset()
        return bytes.copyOf(read)
    }

    private fun isZip(head: ByteArray): Boolean = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    /** All of [stream], or null past [limit] bytes. */
    private fun readCapped(stream: InputStream, limit: Int = MAX_TEXT_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val r = stream.read(buffer)
            if (r < 0) break
            out.write(buffer, 0, r)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /** About 250,000 words: far more than a reply reads, enough to find a question's part in. */
    const val MAX_CHARS = 1_500_000

    /** Pages of a scanned PDF looked at; the reply says when there are more. */
    const val MAX_SCANNED_PAGES = 3

    /** Fewer letters and digits than this is no text: an empty file. (A PDF has its own bar, [PdfText].) */
    private const val MIN_LETTERS = 3

    private const val MAX_TEXT_BYTES = 20 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 40 * 1024 * 1024
    private const val MAX_ZIP_BYTES = 120L * 1024 * 1024

    private val SLIDE = Regex("ppt/slides/slide\\d+\\.xml")
    private val SHEET = Regex("xl/worksheets/sheet\\d+\\.xml")

    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    private val SKIPPED_RTF = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "themedata", "colorschememapping",
        "latentstyles", "datastore", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator",
        "header", "footer", "headerl", "headerr", "footerl", "footerr", "fldinst", "bkmkstart", "bkmkend",
    )

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"",
        "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™", "rupee" to "₹", "euro" to "€", "pound" to "£",
        "bull" to "•", "middot" to "·", "deg" to "°",
    )
}
