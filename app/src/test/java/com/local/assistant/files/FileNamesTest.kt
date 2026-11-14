package com.local.assistant.files

import com.local.assistant.files.FileNames.Entry
import com.local.assistant.files.FileNames.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    private val now = 1_763_000_000_000L
    private val day = 24 * 60 * 60_000L

    private fun entry(name: String, folder: String = "Download/", daysAgo: Int = 30) = Entry(name, folder, now - daysAgo * day)

    private fun ranked(asked: String, vararg names: String): List<String> {
        val words = FileNames.searchWords(asked)
        return FileNames.rank(words, names.map { entry(it) }, now, 10) { it }.map { it.name }
    }

    @Test
    fun `a name splits at punctuation, case changes and digits`() {
        assertEquals(listOf("taslim", "resume", "v", "2", "final"), FileNames.parts("TaslimResume_v2-final.docx"))
        assertEquals(listOf("e", "pan"), FileNames.parts("e-PAN.pdf"))
        assertEquals(listOf("rental", "agreement", "2024"), FileNames.parts("Rental Agreement 2024.pdf"))
        assertEquals(listOf("readme"), FileNames.parts("README"))
    }

    @Test
    fun `type words and filler are not searched for`() {
        assertEquals(listOf("rental", "agreement"), FileNames.searchWords("my rental agreement pdf"))
        assertEquals(listOf("resume"), FileNames.searchWords("the resume document"))
        assertEquals(emptyList<String>(), FileNames.searchWords("my latest pdf"))
        assertEquals(listOf("pan", "card"), FileNames.searchWords("PAN card"))
    }

    @Test
    fun `the type asked for, in the model's words`() {
        assertEquals(Type.PDF, Type.of("pdf"))
        assertEquals(Type.WORD, Type.of("Word"))
        assertEquals(Type.WORD, Type.of("docx"))
        assertEquals(Type.SPREADSHEET, Type.of("excel"))
        assertEquals(Type.SLIDES, Type.of("presentation"))
        assertEquals(Type.FOLDER, Type.of("folder"))
        assertEquals(Type.ANY, Type.of(null))
        assertEquals(Type.ANY, Type.of("whatever"))
        assertTrue(Type.DOCUMENT.allows("notes.TXT"))
        assertFalse(Type.PDF.allows("notes.docx"))
    }

    @Test
    fun `whole words first, then starts of words, then inside the name`() {
        assertEquals(
            listOf("Rental_Agreement.pdf", "RentalAgreements2023.pdf", "agreement.pdf"),
            ranked("rental agreement", "agreement.pdf", "RentalAgreements2023.pdf", "Rental_Agreement.pdf", "invoice.pdf"),
        )
        assertEquals(listOf("TaslimResume.docx"), ranked("resume", "TaslimResume.docx", "photo-list.txt"))
    }

    @Test
    fun `a letter off still finds it, a short word doesn't`() {
        assertEquals(listOf("Aadhaar_card.pdf"), ranked("aadhar card", "Aadhaar_card.pdf"))
        assertEquals(listOf("Insurance_policy.pdf"), ranked("insurence", "Insurance_policy.pdf"))
        assertEquals(emptyList<String>(), ranked("cat", "car.pdf"))
    }

    @Test
    fun `of two equal names, the newer comes first`() {
        val words = FileNames.searchWords("bill")
        val old = entry("bill.pdf", daysAgo = 400)
        val new = entry("bill.pdf", folder = "Documents/", daysAgo = 2)
        assertEquals(listOf(new, old), FileNames.rank(words, listOf(old, new), now, 10) { it })
    }

    @Test
    fun `the folder counts at half`() {
        val words = FileNames.searchWords("bank statement")
        val inName = entry("bank_statement.pdf")
        val inFolder = entry("statement.pdf", folder = "Documents/Bank/")
        val neither = entry("statement.pdf", folder = "Download/")
        assertEquals(listOf(inName, inFolder, neither), FileNames.rank(words, listOf(neither, inFolder, inName), now, 10) { it })
    }

    @Test
    fun `one letter apart`() {
        assertTrue(FileNames.within1("aadhar", "aadhaar"))
        assertTrue(FileNames.within1("insurence", "insurance"))
        assertFalse(FileNames.within1("agreement", "agrement2"))
    }

    @Test
    fun `long words are searched by their start`() {
        assertEquals(listOf("rent", "agre"), FileNames.likeParts(listOf("rent", "agreement")))
    }
}
