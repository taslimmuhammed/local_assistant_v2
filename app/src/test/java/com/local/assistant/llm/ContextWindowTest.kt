package com.local.assistant.llm

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    private fun message(kind: AttachmentKind? = null, durationMs: Long? = null, text: String = if (kind == AttachmentKind.AUDIO) "" else "hello") = MessageEntity(
        chatId = 1,
        role = Role.USER,
        text = text,
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
    fun `a voice message written down goes as its words, its audio unsent and uncharged`() {
        val transcribed = message(AttachmentKind.AUDIO, durationMs = 3_200, text = "set an alarm for six")
        assertEquals(0, ContextWindow.attachmentTokens(transcribed, 256))
        org.junit.Assert.assertNull(ContextWindow.sentAttachment(AttachmentKind.AUDIO, "set an alarm for six"))
        assertEquals(AttachmentKind.AUDIO, ContextWindow.sentAttachment(AttachmentKind.AUDIO, " "))
        assertEquals(AttachmentKind.IMAGE, ContextWindow.sentAttachment(AttachmentKind.IMAGE, "what breed is this dog"))
    }

    @Test
    fun `a document is never sent as media, and is named in the history`() {
        org.junit.Assert.assertNull(ContextWindow.sentAttachment(AttachmentKind.DOCUMENT, "summarise this"))
        org.junit.Assert.assertNull(ContextWindow.sentAttachment(AttachmentKind.DOCUMENT, ""))
        val path = "/data/files/attachments/0f8fad5b-d9cb-469f-a165-70867728950e-Electricity Bill Oct.pdf"
        assertEquals("[Attached document \"Electricity Bill Oct.pdf\"] when is it due?", ContextWindow.historyText(AttachmentKind.DOCUMENT, path, "when is it due?"))
        assertEquals("[Attached document \"Electricity Bill Oct.pdf\"]", ContextWindow.historyText(AttachmentKind.DOCUMENT, path, " "))
        assertEquals("what breed is this", ContextWindow.historyText(AttachmentKind.IMAGE, "/tmp/a.jpg", "what breed is this"))
        assertEquals(ContextWindow.DOCUMENT_NOTE_TOKENS, ContextWindow.attachmentTokens(message(AttachmentKind.DOCUMENT), 256))
    }

    @Test
    fun `an attached document keeps the name it was picked with`() {
        assertEquals("Rent agreement (final).docx", com.local.assistant.media.AttachmentStore.documentName("/x/0f8fad5b-d9cb-469f-a165-70867728950e-Rent agreement (final).docx"))
        // Not an id in front: the name as it is.
        assertEquals("2024-03-statement.pdf", com.local.assistant.media.AttachmentStore.documentName("/x/2024-03-statement.pdf"))
        assertEquals("a_b_c.pdf", com.local.assistant.media.AttachmentStore.safeName("a/b:c.pdf"))
        assertEquals("document", com.local.assistant.media.AttachmentStore.safeName("..."))
        val long = com.local.assistant.media.AttachmentStore.safeName("x".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun `a voice message being sent is heard too, up to 30 seconds, when it has words`() {
        assertTrue(ContextWindow.heard(AttachmentKind.AUDIO, "call Umma", 8_000))
        assertTrue(ContextWindow.heard(AttachmentKind.AUDIO, "call Umma", 30_000))
        // Longer: the words alone, as in the history.
        assertFalse(ContextWindow.heard(AttachmentKind.AUDIO, "a long note", 30_001))
        // No words: it goes as the recording anyway (sentAttachment), not as a hearing of it.
        assertFalse(ContextWindow.heard(AttachmentKind.AUDIO, " ", 8_000))
        assertFalse(ContextWindow.heard(AttachmentKind.AUDIO, "call Umma", null))
        assertFalse(ContextWindow.heard(AttachmentKind.IMAGE, "what is this", null))
        assertFalse(ContextWindow.heard(null, "call Umma", null))
        assertEquals(256, ContextWindow.audioTokens(8_000))
    }

    @Test
    fun `the reported maximum is parsed out of the runtime error`() {
        val message = "INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of tokens allowed: 8192"
        org.junit.Assert.assertEquals(8192, ContextWindow.reportedMaxTokens(message))
        org.junit.Assert.assertNull(ContextWindow.reportedMaxTokens("Failed to create engine: RESOURCE_EXHAUSTED"))
        org.junit.Assert.assertNull(ContextWindow.reportedMaxTokens(null))
    }
}
