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
}
