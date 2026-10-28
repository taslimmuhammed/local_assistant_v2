package com.local.assistant.privacy

import com.local.assistant.privacy.SensitiveText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every number here is made up; the card numbers are the networks' published test numbers. */
class SensitiveTextTest {

    private fun kinds(text: String) = SensitiveText.find(text).map { it.kind }

    @Test
    fun `card numbers pass their check, other long numbers don't`() {
        assertEquals(listOf(Kind.CARD), kinds("Your credit card number is 4111 1111 1111 1111."))
        assertEquals(listOf(Kind.CARD), kinds("card: 5555-5555-5555-4444"))
        assertEquals(listOf(Kind.CARD), kinds("4012888888881881"))
        // One digit off fails Luhn.
        assertEquals(emptyList<Kind>(), kinds("order 4111 1111 1111 1112 shipped"))
    }

    @Test
    fun `aadhaar passes Verhoeff`() {
        // 2341 2341 2346 is Verhoeff-valid; a slip in the last digit is not.
        assertEquals(listOf(Kind.AADHAAR), kinds("Aadhaar: 2341 2341 2346"))
        assertEquals(emptyList<Kind>(), kinds("Aadhaar: 2341 2341 2347"))
    }

    @Test
    fun `account numbers only beside their word`() {
        assertEquals(listOf(Kind.ACCOUNT), kinds("SBI account number: 30512345678"))
        assertEquals(listOf(Kind.ACCOUNT), kinds("a/c no 1234567890"))
        assertEquals(emptyList<Kind>(), kinds("tracking 30512345678 arrives Monday"))
    }

    @Test
    fun `IDs by their format`() {
        assertEquals(listOf(Kind.PAN), kinds("PAN: ABCPE1234F"))
        assertEquals(listOf(Kind.DRIVING_LICENCE), kinds("licence KL11 20110012345"))
        assertEquals(listOf(Kind.PASSPORT), kinds("passport number is J8369854"))
        // A passport-like code with no word for it is left alone.
        assertEquals(emptyList<Kind>(), kinds("part number J8369854"))
    }

    @Test
    fun `secrets after their word`() {
        assertEquals(listOf(Kind.SECRET), kinds("my atm pin is 4417"))
        assertEquals(listOf(Kind.SECRET), kinds("cvv 123"))
        assertEquals(listOf(Kind.SECRET), kinds("the password for the wifi is kochi2026"))
        assertEquals(listOf(Kind.SECRET), kinds("OTP: 445566."))
        assertEquals(listOf(Kind.SECRET), kinds("password: hunter"))
        assertEquals(listOf(Kind.CARD, Kind.SECRET), kinds("my cc number is 4111111111111111 atm pin is 4417"))
    }

    @Test
    fun `ordinary text stays`() {
        for (text in listOf(
            "Call me on +91 98765 43210",
            "call 9876543210 after 5",
            "The meeting is on 12-03-2026 at 10:30",
            "It costs ₹1,25,000",
            "the pin code is 682001",
            "your password is weak, change it",
            "I'll send you the OTP",
            "I found 282 photos of you",
        )) assertFalse(text, SensitiveText.contains(text))
    }

    @Test
    fun `hidden on screen keeps the last four, secrets keep nothing`() {
        assertEquals("Card: •••• •••• •••• 1111.", SensitiveText.masked("Card: 4111 1111 1111 1111."))
        assertEquals("PAN ••••••234F", SensitiveText.masked("PAN ABCPE1234F"))
        assertEquals("atm pin is ••••", SensitiveText.masked("atm pin is 4417"))
    }

    @Test
    fun `hidden in speech says how it ends`() {
        assertEquals("Your card number is ending in 1 1 1 1.", SensitiveText.spoken("Your card number is 4111 1111 1111 1111."))
        assertEquals("the pin is hidden", SensitiveText.spoken("the pin is 4417"))
    }

    @Test
    fun `a number still being written waits`() {
        assertEquals("Your card number is …", SensitiveText.settled("Your card number is 4111 1111"))
        assertEquals("I found 3 photos.", SensitiveText.settled("I found 3 photos."))
        assertTrue(SensitiveText.settled("PAN ABCPE12").endsWith("…"))
    }
}
