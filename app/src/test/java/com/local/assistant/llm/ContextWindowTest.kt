package com.local.assistant.llm

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextWindowTest {

    private fun message(kind: AttachmentKind? = null, durationMs: Long? = null) = MessageEntity(
        chatId = 1,
        role = Role.USER,
        text = "hello",
        createdAt = 0,
        attachmentPath = if (kind == null) null else "/tmp/file",
        attachmentKind = kind,
        attachmentDurationMs = durationMs,
    )

    @Test
    fun `text alone costs nothing extra`() {
        assertEquals(0, ContextWindow.attachmentTokens(message(), visionTokensPerImage = 256))
    }

    @Test
    fun `an image is charged its vision token budget`() {
        assertEquals(256, ContextWindow.attachmentTokens(message(AttachmentKind.IMAGE), 256))
    }

    @Test
    fun `audio is charged by its duration, rounded up to whole seconds`() {
        assertEquals(
            4 * ContextWindow.AUDIO_TOKENS_PER_SECOND,
            ContextWindow.attachmentTokens(message(AttachmentKind.AUDIO, durationMs = 3_200), 256),
        )
    }

    @Test
    fun `the reported maximum is parsed out of the runtime error`() {
        val message = "INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of tokens allowed: 8192"
        org.junit.Assert.assertEquals(8192, ContextWindow.reportedMaxTokens(message))
        org.junit.Assert.assertNull(ContextWindow.reportedMaxTokens("Failed to create engine: RESOURCE_EXHAUSTED"))
        org.junit.Assert.assertNull(ContextWindow.reportedMaxTokens(null))
    }
}
