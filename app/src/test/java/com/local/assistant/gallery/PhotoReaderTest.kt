package com.local.assistant.gallery

import com.local.assistant.gallery.PhotoReader.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up numbers throughout, each with its kind's shape and check digit where it has one. */
class PhotoReaderTest {

    @Test
    fun `the ID number a question asks for`() {
        assertEquals(Field.PAN, Field.of("what is my PAN number?"))
        assertEquals(Field.PAN, Field.of("fetch my pan number"))
        assertEquals(Field.PAN, Field.of("give me my PAN card number"))
        assertEquals(Field.PAN, Field.of("what's my PAN?"))
        assertEquals(Field.AADHAAR, Field.of("what's my Aadhaar number?"))
        assertEquals(Field.AADHAAR, Field.of("send me my aadhar card no"))
        assertEquals(Field.DRIVING_LICENCE, Field.of("my DL number?"))
        assertEquals(Field.DRIVING_LICENCE, Field.of("fetch my driving license number"))
        assertEquals(Field.PASSPORT, Field.of("what's my passport number?"))
        assertEquals(Field.CARD, Field.of("fetch my credit card number"))
        // Something else on the same documents: answered in words.
        assertNull(Field.of("what is my date of birth on my PAN card?"))
        assertNull(Field.of("when does my driving licence expire?"))
        assertNull(Field.of("when does my credit card expire?"))
        assertNull(Field.of("what was my SSLC score?"))
    }

    @Test
    fun `a number with its shape and check digit is given, named by its document`() {
        assertEquals(
            "Your e-PAN card shows your PAN: ABCPE1234F.",
            PhotoReader.reply(Field.PAN, "ABCPE1234F", "e-PAN card"),
        )
        assertEquals(
            "Your Aadhaar card shows your Aadhaar number: 2345 6789 0124.",
            PhotoReader.reply(Field.AADHAAR, "2345 6789 0124", "This is an Aadhaar card."),
        )
        assertEquals(
            "Your Indian Union Driving Licence shows your driving licence number: KL07 20110012345.",
            PhotoReader.reply(Field.DRIVING_LICENCE, "DL No. KL07 20110012345", "Indian Union Driving Licence"),
        )
        assertEquals(
            "Your credit card, front shows your card number: 4111 1111 1111 1111.",
            PhotoReader.reply(Field.CARD, "4111 1111 1111 1111", "credit card, front"),
        )
        // Checked numbers stand whatever the photo was called; the document isn't named then.
        assertEquals(
            "Your Government of India identity card shows your Aadhaar number: 2345 6789 0124.",
            PhotoReader.reply(Field.AADHAAR, "2345 6789 0124", "Government of India identity card"),
        )
        assertEquals(
            "Your PAN card shows your PAN: ABCPE1234F.",
            PhotoReader.reply(Field.PAN, "ABCPE1234F", "I can't tell"),
        )
    }

    @Test
    fun `a number that fails its check isn't given`() {
        // The card read as three groups of its four, as on the phone.
        val partial = PhotoReader.reply(Field.CARD, "4111 1111 1111", "credit card, front")
        assertTrue(partial, partial.startsWith("I could only make out 12 digits of the card number"))
        assertFalse(partial, "4111" in partial)
        // One digit wrong: Luhn fails.
        val misread = PhotoReader.reply(Field.CARD, "4111 1111 1111 1112", "credit card")
        assertTrue(misread, misread.startsWith("I couldn't read your card number clearly"))
        assertFalse(misread, "1112" in misread)
        val aadhaar = PhotoReader.reply(Field.AADHAAR, "2345 6789 0125", "Aadhaar card")
        assertFalse(aadhaar, "0125" in aadhaar)
    }

    @Test
    fun `a passport number needs a passport, and none is made up`() {
        assertEquals(
            "Your passport shows your passport number: J8369854.",
            PhotoReader.reply(Field.PASSPORT, "J8369854", "passport"),
        )
        // A nine-digit number off some other document, as the phone's best match gave.
        val other = PhotoReader.reply(Field.PASSPORT, "123456789", "bank statement")
        assertEquals("The closest photo I found isn't your passport (it looks like bank statement), so I couldn't find your passport number. The photos below may help.", other)
        val cover = PhotoReader.reply(Field.PASSPORT, "Not shown", "passport, front cover")
        assertEquals("The photo of your passport, front cover doesn't show your passport number. The photos below may help.", cover)
        val wrongShape = PhotoReader.reply(Field.PASSPORT, "123456789", "passport")
        assertFalse(wrongShape, "123456789" in wrongShape)
    }

    @Test
    fun `the document's name, without the model's framing`() {
        assertEquals("e - Permanent Account Number (e-PAN) Card", PhotoReader.documentName("e - Permanent Account Number (e-PAN) Card"))
        assertEquals("Aadhaar card", PhotoReader.documentName("This is an Aadhaar card."))
        assertEquals("SSLC mark sheet", PhotoReader.documentName("It's the SSLC mark sheet.\nMore text"))
        assertNull(PhotoReader.documentName("I can't tell what this is."))
        assertNull(PhotoReader.documentName(""))
    }

    @Test
    fun `an empty answer still says something`() {
        assertEquals(PhotoReader.COULDNT_READ, PhotoReader.answerFrom("  "))
        assertEquals("Your SSLC mark sheet shows a CGPA of 10.0.", PhotoReader.answerFrom(" Your SSLC mark sheet shows a CGPA of 10.0.\n"))
    }
}
