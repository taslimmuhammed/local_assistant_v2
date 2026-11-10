package com.local.assistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportFormTest {

    /** As Google Forms gives it: Get pre-filled link, each answer filled with its field's name. */
    private val link = "https://docs.google.com/forms/d/e/1FAIpQLSdExample_Id/viewform?usp=pp_url" +
        "&entry.1001=reason&entry.1002=question&entry.1003=reply&entry.1004=note&entry.1005=version"

    private val report = ReplyReport(ReportReason.DANGEROUS, " It told me to mix bleach ", "how do I clean the drain?", "Mix bleach and ammonia.", "0.3.0 (5)")

    @Test
    fun `a pre-filled link gives the address answers are posted to`() {
        val form = ReportForm.parse(link)!!

        assertEquals("https://docs.google.com/forms/d/e/1FAIpQLSdExample_Id/formResponse", form.action.toString())
    }

    @Test
    fun `each part of the report goes in the field named for it`() {
        val body = ReportForm.parse(link)!!.body(report)

        val sent = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals(
            mapOf(
                "entry.1001" to "Dangerous or harmful advice",
                "entry.1002" to "how do I clean the drain?",
                "entry.1003" to "Mix bleach and ammonia.",
                "entry.1004" to "It told me to mix bleach",
                "entry.1005" to "0.3.0 (5)",
            ),
            sent,
        )
    }

    @Test
    fun `the field names can be typed any case, around spaces`() {
        val form = ReportForm.parse(link.replace("=reply", "=%20Reply%20").replace("=note", "=NOTE"))

        assertEquals("Mix bleach and ammonia.", form!!.body(report).let { body -> (0 until body.size).first { body.name(it) == "entry.1003" }.let(body::value) })
    }

    @Test
    fun `a link missing a field, or not a form's, is not used`() {
        assertNull(ReportForm.parse(""))
        assertNull(ReportForm.parse(link.replace("&entry.1004=note", "")))
        assertNull(ReportForm.parse(link.replace("docs.google.com", "example.com")))
        assertNull(ReportForm.parse(link.replace("/viewform", "/edit")))
    }
}
