package com.local.assistant.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportFormTest {

    private val form = ReportForm.DEVELOPER

    private fun sent(report: ReplyReport): Map<String, String> {
        val body = form.body(report)
        return (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    private val report = ReplyReport(ReportReason.DANGEROUS, " It told me to mix bleach ", "how do I clean the drain?", "Mix bleach and ammonia.", "0.3.0 (5)")

    @Test
    fun `reports are posted to the form's answer address`() {
        assertEquals(
            "https://docs.google.com/forms/d/e/1FAIpQLSe4h3_bdggIdsxMakSRhTw8tjzXEI-sx19gDIPfpGmko0VBjg/formResponse",
            form.action.toString(),
        )
    }

    @Test
    fun `the reason is the form's own choice, and the rest is written out in the paragraph`() {
        assertEquals(
            mapOf(
                "entry.1028189503" to "Dangerous or harmful advice",
                "entry.855371423" to "Question: how do I clean the drain?\n\nReply: Mix bleach and ammonia.\n\nNote: It told me to mix bleach\n\nApp: 0.3.0 (5)",
            ),
            sent(report),
        )
    }

    @Test
    fun `the form spells offensive in lower case, and that is what is sent`() {
        assertEquals("offensive or hateful", sent(report.copy(reason = ReportReason.OFFENSIVE))["entry.1028189503"])
    }

    @Test
    fun `something else goes in as the form's Other, with the words`() {
        val answers = sent(report.copy(reason = ReportReason.OTHER))

        assertEquals("__other_option__", answers["entry.1028189503"])
        assertEquals("Something else", answers["entry.1028189503.other_option_response"])
    }

    @Test
    fun `no note leaves the note out, and no question says so, since the paragraph is required`() {
        val details = sent(report.copy(note = "  ", question = ""))["entry.855371423"]

        assertEquals("Question: (none)\n\nReply: Mix bleach and ammonia.\n\nApp: 0.3.0 (5)", details)
    }
}
