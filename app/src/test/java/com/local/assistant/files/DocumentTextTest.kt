package com.local.assistant.files

import com.local.assistant.files.DocumentText.Format
import com.local.assistant.files.DocumentText.Read
import com.local.assistant.files.DocumentText.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Documents made here, as Word, PowerPoint, Excel and LibreOffice write them (trimmed). */
class DocumentTextTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(format: Format, bytes: ByteArray): Read = DocumentText.read(format, ByteArrayInputStream(bytes))

    private fun text(format: Format, bytes: ByteArray): String = (read(format, bytes) as Read.Text).text

    @Test
    fun `word paragraphs, tabs, breaks and tables`() {
        val document = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>Rental Agreement</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Rent: </w:t></w:r><w:r><w:t>₹18,000 &amp; deposit</w:t></w:r><w:r><w:tab/><w:t>due on the 5th</w:t></w:r><w:r><w:br/><w:t>Second line</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Tenant</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Taslim</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>Owner</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>R. Iyer</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:t>Signed</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        assertEquals(
            "Rental Agreement\nRent: ₹18,000 & deposit\tdue on the 5th\nSecond line\nTenant | Taslim\nOwner | R. Iyer\nSigned",
            text(Format.DOCX, zip("word/document.xml" to document)),
        )
    }

    @Test
    fun `slides in their order, each marked`() {
        fun slide(vararg lines: String) = "<p:sld><p:cSld><p:spTree>" +
            lines.joinToString("") { "<p:sp><p:txBody><a:p><a:r><a:t>$it</a:t></a:r></a:p></p:txBody></p:sp>" } +
            "</p:spTree></p:cSld></p:sld>"
        val bytes = zip(
            "ppt/slides/slide10.xml" to slide("The end"),
            "ppt/slides/slide2.xml" to slide("Budget", "₹2 lakh"),
            "ppt/slides/slide1.xml" to slide("Trip plan"),
        )
        val read = read(Format.PPTX, bytes) as Read.Text
        assertEquals("[Slide 1]\nTrip plan\n\n[Slide 2]\nBudget\n₹2 lakh\n\n[Slide 3]\nThe end", read.text)
        assertEquals(3, read.parts)
        assertEquals("slide", read.unit)
    }

    @Test
    fun `sheets by name, shared and inline strings, numbers and booleans`() {
        val shared = """<sst count="3"><si><t>Item</t></si><si><r><t>Amou</t></r><r><t>nt</t></r></si><si><t>Rent</t><rPh><t>れんと</t></rPh></si></sst>"""
        val workbook = """<workbook xmlns:r="r"><sheets><sheet name="Expenses" sheetId="1" r:id="rId1"/><sheet name="Notes" sheetId="2" r:id="rId2"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="rId2" Target="worksheets/sheet2.xml"/><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>"""
        val sheet1 = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>18000</v></c><c r="C2" s="1"/></row>
            <row r="3"/>
            <row r="4"><c r="A4" t="inlineStr"><is><t>Paid</t></is></c><c r="B4" t="b"><v>1</v></c></row>
            </sheetData></worksheet>"""
        val sheet2 = """<worksheet><sheetData><row r="1"><c r="A1" t="str"><f>A1</f><v>Due on the 5th</v></c></row></sheetData></worksheet>"""
        val bytes = zip(
            "xl/worksheets/sheet1.xml" to sheet1,
            "xl/worksheets/sheet2.xml" to sheet2,
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to rels,
            "xl/sharedStrings.xml" to shared,
        )
        val read = read(Format.XLSX, bytes) as Read.Text
        assertEquals("[Sheet: Expenses]\nItem | Amount\nRent | 18000\nPaid | TRUE\n\n[Sheet: Notes]\nDue on the 5th", read.text)
        assertEquals(2, read.parts)
    }

    @Test
    fun `an OpenDocument text, its spaces and its table`() {
        val content = """<office:document-content><office:automatic-styles><style:style style:name="P1"/></office:automatic-styles><office:body><office:text>
            <text:h text:outline-level="1">Invoice 42</text:h>
            <text:p>Total:<text:s text:c="3"/>₹5,400<text:tab/>paid</text:p>
            <table:table table:name="Table1"><table:table-row><table:table-cell><text:p>GST</text:p></table:table-cell><table:table-cell><text:p>18%</text:p></table:table-cell></table:table-row></table:table>
            </office:text></office:body></office:document-content>"""
        assertEquals("Invoice 42\nTotal:   ₹5,400\tpaid\nGST | 18%", text(Format.ODF, zip("content.xml" to content)))
    }

    @Test
    fun `an OpenDocument spreadsheet names its sheets`() {
        val content = """<office:document-content><office:body><office:spreadsheet>
            <table:table table:name="Budget"><table:table-row><table:table-cell><text:p>Food</text:p></table:table-cell><table:table-cell><text:p>4000</text:p></table:table-cell><table:table-cell table:number-columns-repeated="1000"/></table:table-row></table:table>
            </office:spreadsheet></office:body></office:document-content>"""
        assertEquals("[Sheet: Budget]\nFood | 4000", text(Format.ODF, zip("content.xml" to content)))
    }

    @Test
    fun `html without its scripts and styles`() {
        val page = """<html><head><title>x</title><style>p{color:red}</style></head><body><h1>Ticket</h1><p>PNR&nbsp;4521&#8203;  <b>confirmed</b></p><script>alert(1)</script><table><tr><td>Seat</td><td>23B</td></tr></table><!-- note --></body></html>"""
        val read = text(Format.HTML, page.toByteArray())
        assertEquals("Ticket\nPNR 4521​ confirmed\nSeat | 23B", read)
    }

    @Test
    fun `rtf without its tables and control words`() {
        val rtf = """{\rtf1\ansi\deff0{\fonttbl{\f0 Arial;}}{\colortbl;\red0\green0\blue0;}{\*\generator Riched20}\f0\fs22 Caf\'e9 bill\par Total ${"\\"}u8377?500\tab paid\par {\b Thanks}}"""
        assertEquals("Café bill\nTotal ₹500\tpaid\nThanks", text(Format.RTF, rtf.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `plain text by its byte order mark, or not text at all`() {
        assertEquals("Shopping list: milk, bread, eggs", text(Format.TEXT, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Shopping list: milk, bread, eggs".toByteArray()))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Meeting notes for Monday".toByteArray(Charsets.UTF_16LE)
        assertEquals("Meeting notes for Monday", text(Format.TEXT, utf16))
        assertEquals(Read.Unreadable(Why.NOT_A_DOCUMENT), read(Format.TEXT, byteArrayOf(1, 0, 2, 0, 3) + ByteArray(100)))
    }

    @Test
    fun `what can't be read says why`() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(512)
        assertEquals(Read.Unreadable(Why.PASSWORD), read(Format.DOCX, ole))
        assertEquals(Read.Unreadable(Why.OLD_FORMAT), read(Format.OLD_OFFICE, ole))
        assertEquals(Read.Unreadable(Why.DAMAGED), read(Format.XLSX, "not a zip at all".toByteArray()))
        assertEquals(Read.Unreadable(Why.EMPTY), read(Format.TEXT, "  \n ".toByteArray()))
        assertEquals(Read.Unreadable(Why.EMPTY), read(Format.PPTX, zip("docProps/app.xml" to "<x/>")))
    }

    @Test
    fun `formats by name, then by type`() {
        assertEquals(Format.PDF, DocumentText.formatOf("e-PAN.PDF"))
        assertEquals(Format.DOCX, DocumentText.formatOf("resume.docx"))
        assertEquals(Format.OLD_OFFICE, DocumentText.formatOf("old.doc"))
        assertEquals(Format.TEXT, DocumentText.formatOf("data.csv"))
        assertEquals(Format.PDF, DocumentText.formatOf("download", "application/pdf"))
        assertEquals(Format.OTHER, DocumentText.formatOf("song.mp3", "audio/mpeg"))
        assertTrue(DocumentText.isDocument("notes.md"))
        assertFalse(DocumentText.isDocument("app.apk"))
    }

    @Test
    fun `long text is cut and says so`() {
        val long = "word ".repeat(DocumentText.MAX_CHARS / 5 + 100)
        val read = read(Format.TEXT, long.toByteArray()) as Read.Text
        assertTrue(read.cut)
        assertEquals(DocumentText.MAX_CHARS, read.text.length)
    }

    @Test
    fun `entities and attributes`() {
        assertEquals("a & b < c ₹ \"d\" &unknown; &", DocumentText.entities("a &amp; b &lt; c &#x20B9; &quot;d&quot; &unknown; &"))
        assertEquals("s", DocumentText.attr(""" r="A1" s="2" t="s"""", "t"))
        assertEquals(null, DocumentText.attr(""" xt="s"""", "t"))
    }
}
