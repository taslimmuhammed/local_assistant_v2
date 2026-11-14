package com.local.assistant.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The document reader on made-up documents of each kind, with known answers: a PDF with text, a
 * PDF of page pictures (read by the vision model), a PDF written by an app with only some of its
 * text in its text layer, a Word file, a long Word file whose answer is in one paragraph of many,
 * and a spreadsheet; a summary too. Times each read.
 *
 *     adb shell am instrument -w -e eval documents -e class com.local.assistant.files.DocumentReadingEvalTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DocumentReadingEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    /** [wants]: what the answer must say, matched without commas or spaces in numbers. */
    private class Case(val name: String, val bytes: () -> ByteArray, val question: String?, val wants: List<String>)

    private val filler = listOf(
        "The tenant shall keep the premises clean and in good repair.",
        "The owner shall carry out structural repairs within a reasonable time.",
        "Water and electricity charges are paid by the tenant as per the meters.",
        "No alteration to the premises shall be made without written consent.",
        "The tenant shall not sublet the premises or any part of it.",
    )

    private val cases = listOf(
        Case("LA_test_bill.pdf", { DocumentChatEvalTest.pdf(BILL) }, "how much do I have to pay and by when?", listOf("1846", "20 November")),
        Case("LA_test_rental.docx", { docx(RENTAL) }, "what's the security deposit?", listOf("50000")),
        Case("LA_test_rental.docx", { docx(RENTAL) }, null, listOf("18000")),
        Case("LA_test_long_lease.docx", { docx(longLease()) }, "what's the notice period to vacate?", listOf("two months")),
        Case("LA_test_expenses.xlsx", { xlsx() }, "how much did I spend on groceries in October?", listOf("4250")),
        Case("LA_test_receipt_scan.pdf", { picturePdf() }, "what's the total on this receipt?", listOf("935")),
        Case("LA_test_receipt_app.pdf", { appPdf() }, "what's the total on this receipt?", listOf("935")),
    )

    @Test
    fun documentsAreReadRight() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval documents", InstrumentationRegistry.getArguments().getString("eval") == "documents")
        var passed = 0
        for (case in cases) {
            val file = File(context.cacheDir, case.name).apply { writeBytes(case.bytes()) }
            try {
                val read = DocumentFiles.read(context, file)
                val started = SystemClock.elapsedRealtime()
                val steps = ArrayList<String>()
                val answer = container.documentReader.answer(file.name, read, case.question) { steps += it }
                val took = SystemClock.elapsedRealtime() - started
                val plain = answer.replace(Regex("(?<=\\d)[,\\s](?=\\d)"), "")
                val ok = case.wants.all { it.lowercase() in plain.lowercase() }
                if (ok) passed++
                report("${if (ok) "PASS" else "FAIL"} ${case.name} \"${case.question ?: "(summary)"}\" read as ${read::class.simpleName} in $took ms, steps $steps: ${answer.take(400).replace('\n', ' ')}")
            } finally {
                file.delete()
            }
        }
        report("$passed of ${cases.size} passed")
        assertTrue("$passed of ${cases.size} passed", passed == cases.size)
    }

    private fun longLease(): List<String> {
        val paragraphs = ArrayList<String>()
        paragraphs += "Leave and Licence Agreement"
        repeat(60) { i -> paragraphs += "Clause ${i + 1}. ${filler[i % filler.size]} This applies for the whole term of the licence." }
        // Deep in the middle, as a real clause is.
        paragraphs.add(37, "Clause 37a. Either party may end this agreement by giving two months' notice in writing to vacate.")
        return paragraphs
    }

    private fun docx(paragraphs: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = paragraphs.joinToString("") { "<w:p><w:r><w:t>${it.replace("&", "&amp;")}</w:t></w:r></w:p>" }
            zip.write("<w:document><w:body>$body</w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun xlsx(): ByteArray {
        val rows = listOf(
            listOf("Month", "Category", "Amount"),
            listOf("September", "Groceries", "3980"),
            listOf("September", "Fuel", "2100"),
            listOf("October", "Groceries", "4250"),
            listOf("October", "Fuel", "1875"),
            listOf("October", "Eating out", "1320"),
        )
        val sheet = rows.mapIndexed { r, cells ->
            "<row r=\"${r + 1}\">" + cells.mapIndexed { c, value ->
                val ref = "${'A' + c}${r + 1}"
                if (value.all(Char::isDigit)) "<c r=\"$ref\"><v>$value</v></c>" else "<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in listOf(
                "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="Expenses" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>$sheet</sheetData></worksheet>",
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A receipt as a picture of the page, with no text layer: what a scan is. */
    private fun picturePdf(): ByteArray {
        val bitmap = Bitmap.createBitmap(595 * 2, 842 * 2, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { scale(2f, 2f) }.drawReceipt()
            return onePage { canvas -> canvas.drawBitmap(bitmap, null, Rect(0, 0, 595, 842), null) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The receipt written as text by Android's own PDF writer, as an app's export is: its text
     * layer comes out with only some of the words (two of six lines on the phone, no total).
     */
    private fun appPdf(): ByteArray = onePage { canvas -> canvas.drawReceipt() }

    private fun Canvas.drawReceipt() {
        val paint = Paint().apply { color = Color.BLACK; textSize = 26f; isAntiAlias = true }
        drawColor(Color.WHITE)
        RECEIPT.forEachIndexed { i, line -> drawText(line, 60f, 120f + i * 48f, paint) }
    }

    private fun onePage(draw: (Canvas) -> Unit): ByteArray {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        draw(page.canvas)
        document.finishPage(page)
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "document-reading-eval.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "DocumentReadingEval"

        val RECEIPT = listOf("Green Leaf Cafe", "Receipt 2041", "2 x Masala dosa   Rs 240", "3 x Filter coffee   Rs 135", "1 x Thali   Rs 560", "TOTAL   Rs 935")

        val RENTAL = listOf(
            "Rental Agreement",
            "Owner: R. Iyer. Tenant: A. Kumar.",
            "Premises: Flat 3B, Lake View Apartments, Kochi.",
            "Monthly rent: Rs 18,000, due on the 5th of each month.",
            "Security deposit: Rs 50,000, refundable when the tenant leaves.",
            "Term: 11 months from 1 December 2026.",
        )

        val BILL = listOf(
            "Sample Power Company",
            "Electricity bill - October 2026",
            "Consumer number: 4471 2290 18",
            "Bill date: 5 November 2026",
            "Units used: 214",
            "Amount due: Rs 1,846.00",
            "Pay by: 20 November 2026",
            "A late fee of Rs 50 applies after the due date.",
        )
    }
}
