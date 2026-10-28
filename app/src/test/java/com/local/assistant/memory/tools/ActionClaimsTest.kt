package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionClaimsTest {

    @Test
    fun `replies that say something was done are claims`() {
        val claims = listOf(
            // From the user's chats: the first two had no tool call behind them.
            "I've sent the email to Hitesh at infosys.com with \"Hi\" in it.",
            "I have set an alarm for 7:30 AM.",
            "OK. I've sent \"Hi\" to Sandra on WhatsApp.",
            "I've added a reminder for Salah in 15 minutes.",
            "I've set a timer for Salaam in 10 minutes.",
            "I've set the alarm for in five minutes.",
            // And the other ways of saying it.
            "Alright, I've just emailed him.",
            "I sent the message to Priya.",
            "I've called Amma for you.",
            "I'm sending the email now.",
            "I've booked a cab to the airport.",
            "I've gone ahead and paid the electricity bill.",
            "I've posted it on Instagram.",
            "I've turned on the flashlight.",
            "I've turned the volume up.",
            "I've put your phone on silent.",
            "I've opened YouTube for you.",
            "I've saved that for you.",
            "I've marked it as done.",
            "I've ordered a large pizza from Domino's.",
            "I've placed the order.",
            "I've transferred 500 rupees to Priya.",
            "I've deleted that from my memory.",
            "I've cancelled the meeting.",
            "I've moved your reminder to 3 PM.",
            "Your email has been sent to Hitesh.",
            "Your alarm is set for 6 AM.",
            "The reminder has been added.",
            "Email sent!",
            "Done! It will ring at 7.",
            "All set.",
            // The power-button panel, 27 Oct 2026, with no tool call behind it.
            "Sent.",
        )
        for (text in claims) assertTrue(text, ActionClaims.claims(text))
    }

    @Test
    fun `writing, code, offers and plans are not claims`() {
        val replies = listOf(
            "I can't send messages on Teams. I can only send SMS, WhatsApp or email.",
            "I can draft an email to Hitesh, but I need the full address.",
            "Would you like me to send it now?",
            "I can set an alarm for you, just tell me the time.",
            "I'll need his email address first.",
            "I've written a short poem about the rain:",
            "I've updated the code to use flexbox.",
            "I've added comments to explain each step.",
            "I've created a simple HTML page for an ice cream shop.",
            "I've ordered the list by date.",
            "I've placed the script tag at the end of the body.",
            "I've called it `total` so it reads well.",
            "I called the function twice by mistake.",
            "I've set the background colour to blue.",
            "I've stored it in a variable called count.",
            "I've removed the unused import.",
            "I've opened with a short summary, then the details.",
            "I've turned your notes into a table.",
            "The terms have cancelled out, leaving 2x.",
            "Here is a simple C++ program that prints Hello, World!",
            "I have photos from several locations, including Munnar and Kashmir.",
            "I found 9 photos of you attending a wedding.",
            "You can set an alarm in the Clock app.",
            "To send an email, open Gmail and tap Compose.",
            "Since I am a text-based AI, I cannot show you an image of the website directly.",
            "Your passport expires on 12 March 2031.",
        )
        for (text in replies) assertTrue(text, !ActionClaims.claims(text))
    }

    @Test
    fun `saying a message was written counts only when the message isn't shown`() {
        // As in the panel and the email to Hitesh: no draft anywhere.
        assertTrue(ActionClaims.unshownDraft("I've drafted the email for you. Would you like me to send it now?"))
        assertTrue(ActionClaims.unshownDraft("I've written a message to Priya. Shall I send it?"))
        assertTrue(ActionClaims.unshownDraft("Your email is ready."))
        // The draft follows, after a colon or in quotes, or there's a whole one.
        assertTrue(!ActionClaims.unshownDraft("I've drafted an email for you:\n\nSubject: Leave"))
        assertTrue(!ActionClaims.unshownDraft("I've written a message for Priya: \"Running late, see you soon!\""))
        assertTrue(!ActionClaims.unshownDraft("I've drafted the email. " + "Hi Hitesh, good morning. ".repeat(8)))
        assertTrue(!ActionClaims.unshownDraft("I can draft an email for you, but what should it say?"))
        assertTrue(!ActionClaims.unshownDraft("I've written a short poem about the rain."))
        // Not a claim of having done something.
        assertTrue(!ActionClaims.claims("I've drafted the email for you. Would you like me to send it now?"))
    }

    @Test
    fun `the opening is the first sentence, or more while it is short, and at most 200 characters`() {
        // Not ended yet: the full stop has no space after it, and "infosys.com" is not an end.
        assertNull(ActionClaims.openingEnd("I've sent the email to Hitesh at infosys.com."))
        assertEquals(45, ActionClaims.openingEnd("I've sent the email to Hitesh at infosys.com. More"))
        // "Sure!" alone is too short to be the opening; the claim comes after it.
        val text = "Sure! I've sent the email to him. Anything else?"
        assertEquals(33, ActionClaims.openingEnd(text))
        assertTrue(ActionClaims.claims(text.substring(0, 33)))
        assertEquals(32, ActionClaims.openingEnd("Here is the HTML for your page:\n<html>"))
        assertEquals(200, ActionClaims.openingEnd("a".repeat(200)))
        assertNull(ActionClaims.openingEnd("a".repeat(199)))
    }
}
