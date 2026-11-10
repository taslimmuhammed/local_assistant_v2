package com.local.assistant.ui.chat

import com.local.assistant.chat.ReportReason
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplyReportTest {

    private var nextId = 1L

    private fun message(role: Role, text: String, kind: AttachmentKind? = null) =
        MessageEntity(id = nextId++, chatId = 1, role = role, text = text, createdAt = 0, attachmentKind = kind, attachmentPath = kind?.let { "/x" })

    @Test
    fun `a report carries the reply and only the question just before it`() {
        val messages = listOf(
            message(Role.USER, "my name is Ravi"),
            message(Role.ASSISTANT, "Nice to meet you, Ravi."),
            message(Role.USER, "tell me a joke"),
            message(Role.ASSISTANT, "A rude joke."),
            message(Role.USER, "another"),
            message(Role.ASSISTANT, "A fine joke."),
        )

        val report = replyReport(messages, messages[3], ReportReason.OFFENSIVE, "", "0.3.0 (5)")

        assertEquals("tell me a joke", report.question)
        assertEquals("A rude joke.", report.reply)
    }

    @Test
    fun `a photo or voice message is named, and its words sent as typed ones`() {
        val messages = listOf(
            message(Role.USER, "what is this?", AttachmentKind.IMAGE),
            message(Role.ASSISTANT, "A cat."),
            message(Role.USER, "set an alarm", AttachmentKind.AUDIO),
            message(Role.ASSISTANT, "Done."),
        )

        assertEquals("[a photo] what is this?", replyReport(messages, messages[1], ReportReason.WRONG, "", "").question)
        assertEquals("[a voice message] set an alarm", replyReport(messages, messages[3], ReportReason.WRONG, "", "").question)
    }

    @Test
    fun `card numbers stay hidden in a report, even when shown on screen`() {
        val messages = listOf(
            message(Role.USER, "my card is 4111 1111 1111 1111"),
            message(Role.ASSISTANT, "Saved your card 4111 1111 1111 1111."),
        )

        val report = replyReport(messages, messages[1], ReportReason.OTHER, "", "")

        assertFalse(report.question.contains("4111 1111 1111 1111"))
        assertFalse(report.reply.contains("4111 1111 1111 1111"))
    }

    @Test
    fun `a reply with nothing asked before it has no question`() {
        val messages = listOf(message(Role.ASSISTANT, "Good morning."))

        assertEquals("", replyReport(messages, messages[0], ReportReason.OTHER, "", "").question)
    }
}
