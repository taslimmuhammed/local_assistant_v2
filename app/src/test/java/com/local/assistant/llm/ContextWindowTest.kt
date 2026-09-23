package com.local.assistant.llm

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    private var nextId = 1L

    private fun message(
        text: String,
        role: Role = Role.USER,
        kind: AttachmentKind? = null,
        durationMs: Long? = null,
    ) = MessageEntity(
        id = nextId++,
        chatId = 1,
        role = role,
        text = text,
        createdAt = nextId,
        attachmentPath = if (kind == null) null else "/tmp/file",
        attachmentKind = kind,
        attachmentDurationMs = durationMs,
    )

    @Test
    fun `history that fits is returned untouched`() {
        val history = listOf(message("short"), message("also short"))
        val result = ContextWindow.trimToBudget(history, budgetTokens = 10_000, visionTokensPerImage = 256)
        assertSame(history, result.messages)
        assertEquals(0, result.droppedCount)
        assertFalse(result.trimmed)
    }

    @Test
    fun `empty history is handled`() {
        assertEquals(0, ContextWindow.trimToBudget(emptyList(), 1000, 256).droppedCount)
    }

    @Test
    fun `oldest messages are dropped first`() {
        val history = (1..10).map { message("x".repeat(300)) } // ~100 tokens each
        val result = ContextWindow.trimToBudget(history, budgetTokens = 350, visionTokensPerImage = 256)
        assertTrue(result.trimmed)
        // Whatever survives must be a suffix of the original.
        assertEquals(history.takeLast(result.messages.size), result.messages)
    }

    @Test
    fun `trimmed history fits the budget`() {
        val history = (1..20).map { message("y".repeat(300)) }
        val budget = 500
        val result = ContextWindow.trimToBudget(history, budget, visionTokensPerImage = 256)
        val total = result.messages.sumOf { ContextWindow.estimateTokens(it, 256) }
        assertTrue("kept $total tokens for a $budget budget", total <= budget)
    }

    /** Sending a turn with no content at all is worse than letting the runtime complain. */
    @Test
    fun `the newest message is kept even when it alone blows the budget`() {
        val history = listOf(message("old"), message("z".repeat(100_000)))
        val result = ContextWindow.trimToBudget(history, budgetTokens = 100, visionTokensPerImage = 256)
        assertEquals(1, result.messages.size)
        assertEquals(history.last(), result.messages.single())
        assertEquals(1, result.droppedCount)
    }

    @Test
    fun `an image is charged its vision token budget`() {
        val plain = ContextWindow.estimateTokens(message("hello"), visionTokensPerImage = 256)
        val withImage = ContextWindow.estimateTokens(
            message("hello", kind = AttachmentKind.IMAGE),
            visionTokensPerImage = 256,
        )
        assertEquals(plain + 256, withImage)
    }

    @Test
    fun `audio is charged by its duration`() {
        val plain = ContextWindow.estimateTokens(message("hi"), visionTokensPerImage = 256)
        val withAudio = ContextWindow.estimateTokens(
            message("hi", kind = AttachmentKind.AUDIO, durationMs = 4_000),
            visionTokensPerImage = 256,
        )
        assertEquals(plain + 4 * ContextWindow.AUDIO_TOKENS_PER_SECOND, withAudio)
    }

    @Test
    fun `budget leaves room for the reply and the system prompt`() {
        val budget = ContextWindow.budgetFor(
            contextTokens = 8192,
            maxOutputTokens = 2048,
            systemPrompt = "You are a helpful assistant.",
        )
        assertTrue(budget < 8192 - 2048)
        assertTrue(budget > 5000)
    }

    @Test
    fun `budget never goes negative on a tiny window`() {
        val budget = ContextWindow.budgetFor(512, 2048, "a long system prompt ".repeat(50))
        assertTrue(budget > 0)
    }
}
