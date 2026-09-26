package com.local.assistant.memory.tools

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Arithmetic for the calculate tool, so sums, percentages and splits are exact rather than
 * whatever a 4B model works out in its head.
 *
 * `+ - * / ^`, parentheses, `%` as a percentage ("18% of 2450" or "2450*18%"), `sqrt(…)`, and
 * numbers written with thousands separators ("1,00,000", "2,450.50"). `×`, `x` between numbers
 * and `÷` are read as the operators they stand for.
 */
object Calculator {

    class Error(message: String) : Exception(message)

    fun evaluate(expression: String): Double {
        val parser = Parser(tokens(normalize(expression)))
        val value = parser.expression()
        if (!parser.done()) throw Error("I couldn't read '${expression.trim()}' as a calculation.")
        if (value.isNaN() || value.isInfinite()) throw Error("That has no finite answer.")
        return value
    }

    /** Up to 10 significant digits, no trailing zeros, no exponent: "441", "862.5", "0.3333333333". */
    fun format(value: Double): String =
        BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(GROUPING, "")
            .replace('×', '*').replace('÷', '/').replace('−', '-')
            .replace(TIMES_X, "*")
            .replace("percent", "%")
            .replace(CURRENCY, "")

    private sealed interface Token {
        data class Number(val value: Double) : Token
        data class Symbol(val char: Char) : Token
        data class Word(val text: String) : Token
    }

    private fun tokens(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() || c == '=' || c == '?' -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    out += Token.Number(text.substring(start, i).toDoubleOrNull() ?: throw Error("'${text.substring(start, i)}' is not a number."))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < text.length && text[i].isLetter()) i++
                    out += Token.Word(text.substring(start, i))
                }
                c in "+-*/^%()" -> {
                    out += Token.Symbol(c)
                    i++
                }
                else -> throw Error("I can't use '$c' in a calculation.")
            }
        }
        return out
    }

    private class Parser(private val tokens: List<Token>) {
        private var at = 0

        fun done() = at == tokens.size

        private fun peek(): Token? = tokens.getOrNull(at)

        private fun symbol(c: Char): Boolean {
            val token = peek()
            if (token is Token.Symbol && token.char == c) {
                at++
                return true
            }
            return false
        }

        private fun word(text: String): Boolean {
            val token = peek()
            if (token is Token.Word && token.text == text) {
                at++
                return true
            }
            return false
        }

        fun expression(): Double {
            var value = term()
            while (true) {
                value = when {
                    symbol('+') -> value + term()
                    symbol('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                value = when {
                    symbol('*') -> value * unary()
                    symbol('/') -> unary().let { if (it == 0.0) throw Error("Division by zero.") else value / it }
                    // "18% of 2450"
                    word("of") -> value * unary()
                    else -> return value
                }
            }
        }

        /** Below the power, as in maths: -2^2 is -4. */
        private fun unary(): Double = when {
            symbol('-') -> -unary()
            symbol('+') -> unary()
            else -> power()
        }

        private fun power(): Double {
            val base = percent()
            return if (symbol('^')) base.pow(unary()) else base
        }

        private fun percent(): Double {
            var value = primary()
            while (symbol('%')) value /= 100
            return value
        }

        private fun primary(): Double {
            val token = peek() ?: throw Error("The calculation ends too early.")
            at++
            return when {
                token is Token.Number -> token.value
                token is Token.Symbol && token.char == '(' -> expression().also { if (!symbol(')')) throw Error("A bracket is not closed.") }
                token is Token.Word && token.text == "sqrt" -> primary().let { if (it < 0) throw Error("No square root of a negative number.") else sqrt(it) }
                else -> throw Error("I couldn't read the calculation near '${describe(token)}'.")
            }
        }

        private fun describe(token: Token): String = when (token) {
            is Token.Number -> token.value.toString()
            is Token.Symbol -> token.char.toString()
            is Token.Word -> token.text
        }
    }

    /** A comma between digits groups them ("1,00,000"); anywhere else it is not a number. */
    private val GROUPING = Regex("(?<=\\d),(?=\\d)")

    /** "₹450", "Rs. 450", "rs450", "450 INR". */
    private val CURRENCY = Regex("[₹$€£]|(?<![a-z])(?:rs\\.?|inr)(?![a-z])")

    /** "12 x 4": an x between two numbers. */
    private val TIMES_X = Regex("(?<=[\\d)])\\s*x\\s*(?=[\\d(])")
}
