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

    /** Both reads as they came from the phone for the user's mark sheet. */
    private val markSheet = """
        Central Board of Secondary Education
        Secondary School Examination (Year: 2017)
        Name: TASLIM MUHAMMED MOOSA
        Roll No.: 436104
        Father's Name: MUHAMMED M. P.
        Date of Birth: 09/08/2001
        School: 06524-M E S RAJA RES SCH CHATHANGAMALAM CALICUT DT KL
        Registration No.: T117/06524/0056
        101 ENGLISH COMM. | A1 | A1 | A1 | 10
        Cumulative Grade Point Average (CGPA): 10.0
        Date: 03-06-2017
    """.trimIndent()

    private val checked = """
        Secondary School Examination (Year): 2017
        Roll No.: 4361044
        Date of Birth: 09/08/2001
        School: 06524-M E S RAJA RES SCH CHATHANGAMALAM CALICUT DT KL
        Registration No.: T117/06524/0056
        Cumulative Grade Point Average (CGPA): 10.0
        Date: 03-06-2017
    """.trimIndent()

    @Test
    fun `the labels read again are the ones with numbers`() {
        assertEquals(
            listOf("Secondary School Examination (Year", "Roll No.", "Date of Birth", "School", "Registration No.", "Cumulative Grade Point Average (CGPA)", "Date"),
            ImageDescriber.numberedLabels(markSheet),
        )
        assertEquals(emptyList<String>(), ImageDescriber.numberedLabels("A whiteboard with the sprint plan\nOwner: Ravi"))
    }

    @Test
    fun `the second read's numbers replace the description's where it misread them`() {
        val corrected = ImageDescriber.correctNumbers(markSheet, checked)
        assertEquals(markSheet.replace("Roll No.: 436104", "Roll No.: 4361044"), corrected)
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
