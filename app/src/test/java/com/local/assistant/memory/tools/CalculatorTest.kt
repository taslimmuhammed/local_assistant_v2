package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalculatorTest {

    private fun calc(expression: String) = Calculator.format(Calculator.evaluate(expression))

    @Test
    fun `the everyday sums`() {
        assertEquals("441", calc("2450*18/100"))
        assertEquals("441", calc("18% of 2450"))
        assertEquals("441", calc("2450 * 18%"))
        assertEquals("862.5", calc("3450/4"))
        assertEquals("2891", calc("2450 + 18% of 2450"))
        assertEquals("1234.56", calc("1,000.56 + 234"))
        assertEquals("100000", calc("1,00,000"))
        assertEquals("48", calc("12 x 4"))
        assertEquals("48", calc("12 × 4"))
        assertEquals("450", calc("₹450"))
        assertEquals("900", calc("Rs. 450 * 2"))
        assertEquals("0.3333333333", calc("1/3"))
        assertEquals("0.3", calc("0.1 + 0.2"))
    }

    @Test
    fun `precedence, brackets, powers and roots`() {
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("1024", calc("2^10"))
        assertEquals("12", calc("sqrt(144)"))
        assertEquals("11576.25", calc("10000 * (1 + 5/100)^3"))
    }

    @Test
    fun `what cannot be worked out says so`() {
        assertThrows(Calculator.Error::class.java) { Calculator.evaluate("5/0") }
        assertThrows(Calculator.Error::class.java) { Calculator.evaluate("(2 + 3") }
        assertThrows(Calculator.Error::class.java) { Calculator.evaluate("two plus two") }
        assertThrows(Calculator.Error::class.java) { Calculator.evaluate("") }
        assertThrows(Calculator.Error::class.java) { Calculator.evaluate("5 # 3") }
    }
}
