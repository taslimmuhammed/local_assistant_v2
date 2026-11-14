package com.local.assistant.files

import android.content.ContentValues
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Finding and reading files on the phone.
 *
 * [findsAndReadsItsOwnFiles] makes a PDF and a Word file in Download (files this app made are
 * listed to it even without All files access), finds them by name, reads them, and deletes them.
 *
 * [coverage] needs All files access, given by the user: how many documents MediaStore lists, how
 * many the folder walk finds that it doesn't (by folder, so whether the walk is needed), and how
 * long the queries take. Counts only: no file's name goes to the log or the report.
 *
 *     adb shell am instrument -w -e eval files -e class com.local.assistant.files.FilesProbeTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FilesProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    private fun save() {
        context.getExternalFilesDir(null)?.let { File(it, "files-probe.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
    }

    @Test
    fun findsAndReadsItsOwnFiles() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval files", InstrumentationRegistry.getArguments().getString("eval") == "files")
        assumeTrue("MediaStore downloads need Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val made = ArrayList<Uri>()
        try {
            made += download("Probe_Rental_Agreement_$STAMP.pdf", "application/pdf", pdf("Rental Agreement", "The monthly rent is 18,000 rupees, due on the 5th.", "The security deposit is 50,000 rupees."))
            made += download("ProbeScan$STAMP.pdf", "application/pdf", picturePdf())
            made += download("ProbeTripPlan$STAMP.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx("Trip to Munnar", "Leave on Friday at 6 am."))
            val finder = FileFinder(context)

            var started = SystemClock.elapsedRealtime()
            val agreements = finder.find("probe rental agreement", FileNames.Type.PDF, null)
            report("PDF by name: ${agreements.size} found in ${SystemClock.elapsedRealtime() - started} ms; first is ours: ${agreements.firstOrNull()?.name?.contains(STAMP.toString())}")
            val agreement = agreements.first { STAMP.toString() in it.name }

            started = SystemClock.elapsedRealtime()
            val pdfText = DocumentFiles.read(context, agreement)
            report("PDF read in ${SystemClock.elapsedRealtime() - started} ms: ${pdfText::class.simpleName}")
            assertTrue("PDF text: $pdfText", pdfText is DocumentText.Read.Text && "50,000" in pdfText.text && "[Page 3]" in pdfText.text)
            assertEquals(3, (pdfText as DocumentText.Read.Text).parts)

            val scan = finder.find("probe scan", FileNames.Type.PDF, null).first { STAMP.toString() in it.name }
            val scanRead = DocumentFiles.read(context, scan)
            report("Picture PDF: $scanRead")
            assertTrue(scanRead is DocumentText.Read.Scanned && scanRead.pageCount == 2 && scanRead.pages.all { it.length() > 0 })

            val trips = finder.find("trip plan", FileNames.Type.WORD, null)
            val trip = trips.first { STAMP.toString() in it.name }
            val docText = DocumentFiles.read(context, trip)
            assertTrue("Word text: $docText", docText is DocumentText.Read.Text && "Munnar" in docText.text)

            // Folders by name: ours is in Download.
            val folders = finder.folders("download")
            report("Folders for \"download\": ${folders.map { it.path }}")
            assertTrue(folders.any { it.path == "${Environment.DIRECTORY_DOWNLOADS}/" })
        } finally {
            made.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            save()
        }
    }

    /**
     * One of our own test documents (named LA_test_…, never the user's) read as the chat reads
     * it, and asked [question]: its text and the answer go to the report.
     *
     *     -e eval files -e path /sdcard/Download/LA_test_power_bill.pdf -e question "how much…"
     */
    @Test
    fun readsATestDocument() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval files", InstrumentationRegistry.getArguments().getString("eval") == "files")
        val path = InstrumentationRegistry.getArguments().getString("path")
        assumeTrue("-e path to an LA_test_ document", path != null && File(path).name.startsWith("LA_test_"))
        val file = File(path!!)
        val question = InstrumentationRegistry.getArguments().getString("question")
        val read = DocumentFiles.read(context, file)
        report("Read ${file.name}: ${read::class.simpleName}")
        if (read is DocumentText.Read.Text) report("Text:\n${read.text}\n---")
        val started = SystemClock.elapsedRealtime()
        val answer = context.appContainer.documentReader.answer(file.name, read, question?.takeIf { it.isNotBlank() }) { report("step: $it") }
        report("Answer in ${SystemClock.elapsedRealtime() - started} ms:\n$answer")
        save()
    }

    @Test
    fun coverage() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval files", InstrumentationRegistry.getArguments().getString("eval") == "files")
        assumeTrue("needs All files access, given by the user", FileAccess.granted(context))
        try {
            val finder = FileFinder(context)
            for (type in listOf(FileNames.Type.PDF, FileNames.Type.WORD, FileNames.Type.SPREADSHEET, FileNames.Type.SLIDES, FileNames.Type.DOCUMENT, FileNames.Type.ANY)) {
                val started = SystemClock.elapsedRealtime()
                val found = finder.find("", type, null, limit = 10_000)
                val took = SystemClock.elapsedRealtime() - started
                val byFolder = found.groupingBy { it.folder.split('/').filter(String::isNotEmpty).take(3).joinToString("/") }.eachCount()
                    .entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}: ${it.value}" }
                val walkedOnly = found.filter { it.mediaId == null }
                val walkedFolders = walkedOnly.groupingBy { it.folder.split('/').filter(String::isNotEmpty).take(5).joinToString("/") }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}: ${it.value}" }
                report("$type: ${found.size} files (${walkedOnly.size} only from the folder walk: $walkedFolders) in $took ms; by folder: $byFolder")
            }
            for (asked in listOf("pan card", "resume", "agreement", "invoice", "statement")) {
                val started = SystemClock.elapsedRealtime()
                val found = finder.find(asked, FileNames.Type.ANY, null)
                report("\"$asked\": ${found.size} found in ${SystemClock.elapsedRealtime() - started} ms; readable kinds: ${found.map { DocumentText.formatOf(it.name, it.mime) }}")
            }
        } finally {
            save()
        }
    }

    /**
     * Which pages of a PDF give their text: a made-up PDF read in order and backwards, and the
     * phone's own PDFs by letters per page (counts only).
     */
    @Test
    fun pdfPages() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval files", InstrumentationRegistry.getArguments().getString("eval") == "files")
        assumeTrue("text layers need Android 15", Build.VERSION.SDK_INT >= 35)
        try {
            fun lettersByPage(file: File, order: List<Int>? = null): List<String> =
                android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    (order ?: (0 until minOf(renderer.pageCount, 6)).toList()).map { i ->
                        val page = renderer.openPage(i)
                        val letters = page.textContents.sumOf { piece -> piece.text.count(Char::isLetterOrDigit) }
                        page.close()
                        "p${i + 1}=$letters"
                    }
                }
            val same = File(context.cacheDir, "same.pdf").apply { writeBytes(pdf("Rental Agreement", "Rental Agreement", "Rental Agreement")) }
            report("Same text on 3 pages: ${lettersByPage(same)}")
            val different = File(context.cacheDir, "different.pdf").apply { writeBytes(pdf("Rental Agreement", "The monthly rent is 18,000 rupees.", "Deposit fifty thousand")) }
            report("Different text, in order: ${lettersByPage(different)}; backwards: ${lettersByPage(different, listOf(2, 1, 0))}")
            same.delete()
            different.delete()
            if (FileAccess.granted(context)) {
                val pdfs = FileFinder(context).find("", FileNames.Type.PDF, null, limit = 8).filter { it.path != null }
                pdfs.forEachIndexed { n, found ->
                    val counts = runCatching { lettersByPage(File(found.path!!)) }.getOrElse { listOf(it::class.simpleName.orEmpty()) }
                    report("Phone PDF ${n + 1}: $counts")
                }
            }
        } finally {
            save()
        }
    }

    /** A file in Download through MediaStore; this app owns it, so it can delete it after. */
    private fun download(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("No row for $name")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    /**
     * A PDF of one page per line, written out by hand in Helvetica so it has a plain text layer.
     * (Android's own PdfDocument gives none for most words: "The monthly rent is 18,000 rupees."
     * read back as nothing, 14 Nov 2026.)
     */
    private fun pdf(vararg pages: String): ByteArray {
        val objects = ArrayList<String>()
        val pageIds = pages.indices.map { 4 + it * 2 }
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        for (line in pages) {
            val contentId = objects.size + 2
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents $contentId 0 R >>"
            val escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            val stream = "BT /F1 12 Tf 40 780 Td ($escaped) Tj ET"
            objects += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
        }
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body ->
            val at = out.length
            out.append(i + 1).append(" 0 obj\n").append(body).append("\nendobj\n")
            at
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** A PDF of page pictures and no text, as a scanned document is. */
    private fun picturePdf(): ByteArray {
        val document = PdfDocument()
        val paint = Paint().apply { color = android.graphics.Color.DKGRAY }
        repeat(2) { i ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawRect(40f, 40f, 400f, 300f, paint)
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun docx(vararg paragraphs: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
            zip.write("<w:document><w:body>$body</w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "FilesProbeTest"
        private val STAMP = System.currentTimeMillis() / 1000
    }
}
