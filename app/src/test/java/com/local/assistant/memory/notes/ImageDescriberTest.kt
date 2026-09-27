package com.local.assistant.memory.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageDescriberTest {

    private fun parse(reply: String) = ImageDescriber.parse(reply)

    @Test
    fun `title and details as asked`() {
        val described = parse(
            """
            Title: Electricity bill, August
            Details: KSEB bill for consumer 1156 0283 4471.
            Amount due: ₹2,340 by 15 Sep 2026.
            """.trimIndent(),
        )!!
        assertEquals("Electricity bill, August", described.title)
        assertEquals("KSEB bill for consumer 1156 0283 4471.\nAmount due: ₹2,340 by 15 Sep 2026.", described.details)
    }

    @Test
    fun `bold labels, quotes and a details section starting on its own line`() {
        val described = parse(
            """
            **Title:** "Wifi password card."
            **Details:**
            - Network: HomeNet_5G
            - Password: 98450 12345
            """.trimIndent(),
        )!!
        assertEquals("Wifi password card", described.title)
        assertEquals("- Network: HomeNet_5G\n- Password: 98450 12345", described.details)
    }

    @Test
    fun `no labels at all, so the reply is the details and its first words the title`() {
        val described = parse("a whiteboard with the sprint plan: login, payments, search by Friday")!!
        assertEquals("A whiteboard with the sprint plan", described.title)
        assertEquals("a whiteboard with the sprint plan: login, payments, search by Friday", described.details)
    }

    @Test
    fun `a title with no details line keeps the rest as details`() {
        val described = parse("Title: Parking spot\nLevel B2, pillar 41, near the lift.")!!
        assertEquals("Parking spot", described.title)
        assertEquals("Level B2, pillar 41, near the lift.", described.details)
    }

    @Test
    fun `nothing said is nothing saved`() {
        assertNull(parse("   "))
    }

    @Test
    fun `long titles and details are cut to what remember_image keeps`() {
        val described = parse("Title: " + "word ".repeat(40) + "\nDetails: " + "x".repeat(5_000))!!
        assertEquals(ImageDescriber.MAX_TITLE, described.title.length)
        assertEquals(ImageDescriber.MAX_DETAILS, described.details.length)
    }
}
