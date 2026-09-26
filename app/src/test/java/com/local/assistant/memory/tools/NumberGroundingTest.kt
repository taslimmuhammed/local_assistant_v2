package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every "model wrote" below is what the model wrote on the phone, past token 2,048. */
class NumberGroundingTest {

    private fun ground(model: String, user: String) = NumberGrounding.ground(model, user)

    private fun sum(model: String, user: String) = NumberGrounding.groundExpression(model, user)

    @Test
    fun `times, dates and numbers come back as the user wrote them`() {
        assertEquals("tomorrow at 11:45", ground("tomorrow at 1:45", "remind me to call the CA tomorrow at 11:45"))
        assertEquals("6:35", ground("6:5", "set an alarm for 6:35"))
        assertEquals("17 October", ground("1 October", "remind me on 17 October to renew the car insurance"))
        assertEquals("98450 12345", ground("94501235", "call 98450 12345"))
    }

    @Test
    fun `where the shape appears twice, the one whose digits the model kept`() {
        assertEquals("5:30", ground("5:0", "move the 5:30 meeting to 6:45"))
        assertEquals("6:45", ground("6:4", "move the 5:30 meeting to 6:45"))
    }

    @Test
    fun `a sum the user wrote is the sum, however the model mangled it`() {
        assertEquals(50.0, Calculator.evaluate(sum("5+5+2", "what is 25+25")), 0.0)
        assertEquals(5555.0, Calculator.evaluate(sum("124 + 321", "what's 1234 plus 4321")), 0.0)
        assertEquals(693.0, Calculator.evaluate(sum("999*7", "what's 99 times 7")), 0.0)
        assertEquals(1024.0, Calculator.evaluate(sum("25*4", "what's 256 multiplied by 4")), 0.0)
        assertEquals(441.0, Calculator.evaluate(sum("18/10.18 * 40", "what's 18% of 2450")), 0.0)
        assertEquals(5.0, Calculator.evaluate(sum("sqrt(25^2)", "what's square root of 25")), 0.0)
        assertEquals(862.5, Calculator.evaluate(sum("3450 divided by 4", "what's 3450 divided by 4")), 0.0)
    }

    @Test
    fun `a word problem keeps the model's expression, with dropped digits put back`() {
        assertEquals("3450/4", sum("3450/4", "split 3450 between 4 people"))
        assertEquals("3450/4", sum("340/4", "split 3450 between 4 people"))
        assertEquals("5 miles in km", sum("5 miles in km", "how many km is 5 miles?"))
        // A number split in two: "8 75" for 875.
        assertEquals("1250 + 875", sum("25 + 8 75", "I spent 1250 on food and 875 on travel, what's the total?"))
    }

    @Test
    fun `numbers the model rightly wrote itself are left alone`() {
        assertEquals("tomorrow at 11", ground("tomorrow at 11", "remind me tomorrow at 11"))
        assertEquals("in 30 minutes", ground("in 30 minutes", "remind me in half an hour"))
        assertEquals("23rd", ground("23rd", "remind me 2 days before the 25th"))
        assertEquals("Dr. Rao", ground("Dr. Rao", "call dentist 98450 12345"))
        assertEquals("every Monday at 9", ground("every Monday at 9", "remind me every Monday at 9 to submit the timesheet"))
    }

    @Test
    fun `a message that is only a sum is answered without the model`() {
        assertEquals("25 × 2 = 50", NumberGrounding.directAnswer("whats 25 * 2"))
        assertEquals("√25 = 5", NumberGrounding.directAnswer("what is root of 25"))
        assertEquals("√25 = 5", NumberGrounding.directAnswer("what's the square root of 25?"))
        assertEquals("18% of 2450 = 441", NumberGrounding.directAnswer("what's 18% of 2450?"))
        assertEquals("256 × 4 = 1024", NumberGrounding.directAnswer("what's 256 multiplied by 4"))
        assertEquals("1234 + 4321 = 5555", NumberGrounding.directAnswer("calculate 1234 plus 4321"))
        assertEquals("5 + 5 = 10", NumberGrounding.directAnswer("5+5="))
        assertEquals("12 ^ 2 = 144", NumberGrounding.directAnswer("what is 12 squared"))
    }

    @Test
    fun `anything more than a sum still goes to the model`() {
        for (message in listOf(
            "split 3450 between 4 people", "is 17 prime?", "my age is 25", "call 98450 12345",
            "set a timer for 10 minutes", "what's 25", "remind me to pay 4500 rent on the 5th",
            "25-3-2026 is my exam date", "what's on 25/12", "how many km is 5 miles",
        )) {
            assertEquals(message, null, NumberGrounding.directAnswer(message))
        }
    }
}
