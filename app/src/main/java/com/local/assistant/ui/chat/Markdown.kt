package com.local.assistant.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.local.assistant.ui.theme.AppColors

/**
 * A deliberately small Markdown subset — the parts a chat model actually emits.
 *
 * Written by hand rather than pulled from a library for one reason: this has to render text that
 * is still arriving. Half of a `**bold**` span is a normal intermediate state here, so every
 * unmatched delimiter falls back to literal text instead of swallowing the rest of the message.
 * An unclosed code fence is treated as an open code block, which is what streaming one looks like.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletItem(val text: String, val depth: Int) : MarkdownBlock
    data class NumberedItem(val label: String, val text: String, val depth: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private const val FENCE = "```"

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")

fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = source.lines()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith(FENCE)) {
            flushParagraph()
            val language = trimmed.removePrefix(FENCE).trim().ifBlank { null }
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(FENCE)) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            // Past the end means the fence is still open, which is normal while streaming.
            i++
            blocks += MarkdownBlock.CodeBlock(language, body.toString())
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        val heading = HEADING.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++
            continue
        }

        // Checked before bullets, so a "---" rule is not read as an empty list item.
        if (RULE.matches(trimmed)) {
            flushParagraph()
            blocks += MarkdownBlock.Rule
            i++
            continue
        }

        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            blocks += MarkdownBlock.BulletItem(bullet.groupValues[2], depthOf(bullet.groupValues[1]))
            i++
            continue
        }

        val numbered = NUMBERED.matchEntire(line)
        if (numbered != null) {
            flushParagraph()
            blocks += MarkdownBlock.NumberedItem(
                label = numbered.groupValues[2],
                text = numbered.groupValues[3],
                depth = depthOf(numbered.groupValues[1]),
            )
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
            i++
            continue
        }

        // Single newlines are kept: chat models use them to mean a line break.
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(trimmed)
        i++
    }

    flushParagraph()
    return blocks
}

private fun depthOf(indent: String): Int = (indent.length / 2).coerceIn(0, 3)

// --- inline spans ---------------------------------------------------------------------------

private val CodeSpan = SpanStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    background = AppColors.SurfaceMuted,
)
private val Bold = SpanStyle(fontWeight = FontWeight.Bold)
private val Italic = SpanStyle(fontStyle = FontStyle.Italic)
private val BoldItalic = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
private val Strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val LinkStyle = TextLinkStyles(
    style = SpanStyle(color = AppColors.Accent, textDecoration = TextDecoration.Underline),
)

fun parseInline(source: String): AnnotatedString = buildAnnotatedString { appendInline(source) }

private fun AnnotatedString.Builder.appendInline(source: String) {
    var i = 0
    while (i < source.length) {
        i = when {
            source[i] == '`' -> appendCode(source, i)
            source.startsWith("***", i) -> appendDelimited(source, i, "***", BoldItalic, strict = false)
            source.startsWith("**", i) -> appendDelimited(source, i, "**", Bold, strict = false)
            // Double underscore only. Single "_" is left alone so snake_case survives.
            source.startsWith("__", i) -> appendDelimited(source, i, "__", Bold, strict = false)
            source.startsWith("~~", i) -> appendDelimited(source, i, "~~", Strike, strict = false)
            source[i] == '*' -> appendDelimited(source, i, "*", Italic, strict = true)
            source[i] == '[' -> appendLink(source, i)
            else -> {
                append(source[i])
                i + 1
            }
        }
    }
}

private fun AnnotatedString.Builder.appendCode(source: String, start: Int): Int {
    val close = source.indexOf('`', start + 1)
    if (close <= start + 1) {
        append('`')
        return start + 1
    }
    // No nested formatting inside code.
    pushStyle(CodeSpan)
    append(source, start + 1, close)
    pop()
    return close + 1
}

/**
 * [strict] rejects spans that open or close against whitespace, which keeps a lone "*" used as
 * multiplication or a stray bullet from starting an italic run.
 */
private fun AnnotatedString.Builder.appendDelimited(
    source: String,
    start: Int,
    marker: String,
    style: SpanStyle,
    strict: Boolean,
): Int {
    val contentStart = start + marker.length
    val close = source.indexOf(marker, contentStart)
    val valid = close > contentStart &&
        (!strict || (!source[contentStart].isWhitespace() && !source[close - 1].isWhitespace()))

    if (!valid) {
        append(marker)
        return contentStart
    }
    pushStyle(style)
    appendInline(source.substring(contentStart, close))
    pop()
    return close + marker.length
}

private fun AnnotatedString.Builder.appendLink(source: String, start: Int): Int {
    val closeBracket = source.indexOf(']', start + 1)
    val openParen = closeBracket + 1
    if (closeBracket < 0 || openParen >= source.length || source[openParen] != '(') {
        append('[')
        return start + 1
    }
    val closeParen = source.indexOf(')', openParen + 1)
    if (closeParen < 0) {
        append('[')
        return start + 1
    }
    val url = source.substring(openParen + 1, closeParen)
    pushLink(LinkAnnotation.Url(url, LinkStyle))
    appendInline(source.substring(start + 1, closeBracket))
    pop()
    return closeParen + 1
}
