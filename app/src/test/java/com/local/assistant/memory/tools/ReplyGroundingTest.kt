package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyGroundingTest {

    /** The envelope of the phone's eval turn, with the card's details inlined as the app sends them. */
    private val envelope = """
        [Now: Sat 27 Sep 2026, 07:48]
        [Saved image (27 Sep 2026) “Home WiFi Network Details”: HOME WIFI Network: Home_5G Password: kochi2026 Router: TP-Link Archer C6 Admin PIN: 4417 Support: 1800-209-4455 It is attached to this message.]
        what's the admin PIN on the wifi card I saved?
    """.trimIndent()

    private val prefix = """
        You are a helpful assistant on the user's phone.

        About the user:
        - Name: Taslim
        - Sister's phone: +91 98450 12345

        Agenda as of Sat 27 Sep:
        - Tue 30 Sep 11:45 · Dentist
        - Wed 1 Oct 13:05 · Team lunch
        - Fri 3 Oct · Electricity bill: pay ₹2,340
    """.trimIndent()

    /** The reply streamed one character at a time, and in one piece: both must come out the same. */
    private fun ground(reply: String, vararg extra: String): String {
        val sources = listOf(prefix, envelope) + extra
        val whole = GroundedReply(ReplyGrounding(sources)).let { it.push(reply) + it.flush() }
        val streamed = GroundedReply(ReplyGrounding(sources)).let { stream ->
            reply.map { stream.push(it.toString()) }.joinToString("") + stream.flush()
        }
        assertEquals("streamed and whole agree", whole, streamed)
        return whole
    }

    @Test
    fun `the PIN and support number measured on the phone`() {
        assertEquals(
            "The admin PIN on the WiFi card you saved is 4417.",
            ground("The admin PIN on the WiFi card you saved is 44417."),
        )
        assertEquals(
            "The support number on your router card is 1800-209-4455.",
            ground("The support number on your router card is 18000-2095555."),
        )
    }

    @Test
    fun `several numbers in one reply, each by its own label`() {
        assertEquals(
            "Here are the details:\n- Admin PIN: 4417\n- Support: 1800-209-4455\n- Password: kochi2026",
            ground("Here are the details:\n- Admin PIN: 44417\n- Support: 18000-29-45555\n- Password: kochi20226"),
        )
    }

    @Test
    fun `agenda times, where the label comes after the number in the source`() {
        assertEquals("Your dentist appointment is at 11:45 on Tuesday.", ground("Your dentist appointment is at 1:45 on Tuesday."))
    }

    @Test
    fun `a 24-hour time read out as 12-hour is left alone`() {
        assertEquals("Team lunch is at 1:05 PM on Wednesday.", ground("Team lunch is at 1:05 PM on Wednesday."))
    }

    @Test
    fun `a phone number with its country code left off is left alone, and mended when garbled`() {
        assertEquals("Your sister's phone is 98450 12345.", ground("Your sister's phone is 98450 12345."))
        assertEquals("Your sister's phone is 98450 12345.", ground("Your sister's phone is 98450 1235."))
    }

    @Test
    fun `numbers found in a tool result`() {
        val result = """{"ok":true,"results":[{"text":"Locker code: 7391","at":"12 Aug 2026"}]}"""
        assertEquals("Your locker code is 7391.", ground("Your locker code is 73391.", result))
    }

    @Test
    fun `right numbers, other numbers and unlabelled numbers are left alone`() {
        val untouched = listOf(
            "The admin PIN is 4417.",
            "The bill is ₹2340, due Friday.",
            "Split between 2 people, the electricity bill is ₹1,170 each.",
            "The Eiffel Tower was finished in 1889 and is 330 metres tall.",
            "It's 27 Sep 2026 today.",
            "The support line ends in 4455.",
            // Said before it is named: nothing to tie it to.
            "44417 is the admin PIN.",
            "Mix 250 g of flour with 3 eggs.",
        )
        for (reply in untouched) assertEquals(reply, ground(reply))
    }

    @Test
    fun `a number like two sources equally is left alone`() {
        val sources = listOf("Gate code: 4417. Door code: 4471.")
        val stream = GroundedReply(ReplyGrounding(sources))
        assertEquals("The code is 4447.", stream.push("The code is 4447.") + stream.flush())
    }

    @Test
    fun `digits are held back only until the number is complete`() {
        val stream = GroundedReply(ReplyGrounding(listOf(envelope)))
        assertEquals("The admin PIN is ", stream.push("The admin PIN is 444"))
        assertEquals("", stream.push("17"))
        assertEquals("4417 — keep it", stream.push(" — keep it"))
        assertEquals(" safe.", stream.push(" safe.") + stream.flush())
    }

    @Test
    fun `a reply that ends on a number gives it up at the end`() {
        val stream = GroundedReply(ReplyGrounding(listOf(envelope)))
        assertEquals("Admin PIN: ", stream.push("Admin PIN: 44417"))
        assertEquals("4417", stream.flush())
    }
}
