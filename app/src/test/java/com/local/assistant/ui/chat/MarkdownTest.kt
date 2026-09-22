package com.local.assistant.ui.chat

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser's job is to stay sane on text that is still arriving, so most of these cover
 * half-finished input rather than well-formed Markdown.
 */
class MarkdownTest {

    // --- blocks ---------------------------------------------------------------------------

    @Test
    fun `parses headings at each level`() {
        val blocks = parseMarkdown("# One\n## Two\n### Three")
        assertEquals(
            listOf(1 to "One", 2 to "Two", 3 to "Three"),
            blocks.map { (it as MarkdownBlock.Heading).level to it.text },
        )
    }

    @Test
    fun `hash without a space is not a heading`() {
        val blocks = parseMarkdown("#hashtag")
        assertEquals("#hashtag", (blocks.single() as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `horizontal rule is not read as an empty bullet`() {
        assertTrue(parseMarkdown("---").single() is MarkdownBlock.Rule)
    }

    @Test
    fun `parses bullets nested lists and numbered items`() {
        val blocks = parseMarkdown("- one\n  - nested\n1. first\n2. second")
        val bullets = blocks.filterIsInstance<MarkdownBlock.BulletItem>()
        assertEquals(listOf("one" to 0, "nested" to 1), bullets.map { it.text to it.depth })
        val numbered = blocks.filterIsInstance<MarkdownBlock.NumberedItem>()
        assertEquals(listOf("1" to "first", "2" to "second"), numbered.map { it.label to it.text })
    }

    @Test
    fun `code fence keeps its language and content verbatim`() {
        val block = parseMarkdown("```html\n<div class=\"x\">\n</div>\n```")
            .single() as MarkdownBlock.CodeBlock
        assertEquals("html", block.language)
        assertEquals("<div class=\"x\">\n</div>", block.code)
    }

    @Test
    fun `markdown inside a code fence is left alone`() {
        val block = parseMarkdown("```\n# not a heading\n- not a bullet\n```")
            .single() as MarkdownBlock.CodeBlock
        assertEquals("# not a heading\n- not a bullet", block.code)
    }

    /** A fence with no closing fence yet is exactly what a streaming code block looks like. */
    @Test
    fun `unclosed code fence still renders as a code block`() {
        val block = parseMarkdown("Here:\n```kotlin\nval x = 1").last() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("val x = 1", block.code)
    }

    @Test
    fun `fence with no language reports none`() {
        assertNull((parseMarkdown("```\nplain\n```").single() as MarkdownBlock.CodeBlock).language)
    }

    @Test
    fun `single newlines inside a paragraph are kept`() {
        assertEquals("line one\nline two", (parseMarkdown("line one\nline two").single() as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `blank line splits paragraphs`() {
        assertEquals(2, parseMarkdown("first\n\nsecond").filterIsInstance<MarkdownBlock.Paragraph>().size)
    }

    // --- inline ---------------------------------------------------------------------------

    private fun boldRanges(source: String) = parseInline(source).spanStyles
        .filter { it.item.fontWeight == FontWeight.Bold }

    @Test
    fun `bold markers are removed and the span applied`() {
        val result = parseInline("a **bold** word")
        assertEquals("a bold word", result.text)
        val span = result.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", result.text.substring(span.start, span.end))
    }

    /** The important streaming case: a marker with no partner must not eat the rest. */
    @Test
    fun `unterminated bold marker stays literal`() {
        val result = parseInline("start **still typing")
        assertEquals("start **still typing", result.text)
        assertTrue(boldRanges("start **still typing").isEmpty())
    }

    @Test
    fun `unterminated inline code stays literal`() {
        assertEquals("a `partial", parseInline("a `partial").text)
    }

    @Test
    fun `snake case identifiers are not italicised`() {
        val result = parseInline("set max_output_token and snake_case_name")
        assertEquals("set max_output_token and snake_case_name", result.text)
        assertTrue(result.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `asterisk against whitespace is not emphasis`() {
        assertEquals("2 * 3 * 4", parseInline("2 * 3 * 4").text)
    }

    @Test
    fun `emphasis nests`() {
        val result = parseInline("***both***")
        assertEquals("both", result.text)
        val style = result.spanStyles.map { it.item }
        assertTrue(style.any { it.fontWeight == FontWeight.Bold && it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `italic inside bold applies both`() {
        val result = parseInline("**bold *and italic* here**")
        assertEquals("bold and italic here", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `inline code is not reformatted`() {
        val result = parseInline("call `a ** b` now")
        assertEquals("call a ** b now", result.text)
        assertTrue(result.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `link shows its label and drops the url syntax`() {
        assertEquals("see docs here", parseInline("see [docs](https://example.com) here").text)
    }

    @Test
    fun `bracket without a url is literal`() {
        assertEquals("[not a link] here", parseInline("[not a link] here").text)
    }

    @Test
    fun `strikethrough is applied`() {
        assertEquals("gone", parseInline("~~gone~~").text)
    }

    @Test
    fun `plain text passes through untouched`() {
        val plain = "No markdown at all, just a sentence."
        assertEquals(plain, parseInline(plain).text)
    }

    /** Every prefix of a message is a state the UI will actually render mid-stream. */
    @Test
    fun `every prefix of a rich message parses without throwing`() {
        val full = "# Title\n\nSome **bold** and `code`.\n\n- item\n\n```html\n<div>x</div>\n```\n"
        for (end in 0..full.length) {
            val prefix = full.substring(0, end)
            parseMarkdown(prefix).forEach { block ->
                if (block is MarkdownBlock.Paragraph) parseInline(block.text)
            }
        }
    }
}
