package com.local.assistant.memory.retrieval

import com.local.assistant.data.db.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPolicyTest {

    @Test
    fun anExchangeIsTheQuestionAndTheStartOfTheReply() {
        assertEquals(
            "User: my CA is Mr. Iyer\nAssistant: Noted, Mr. Iyer is your CA.",
            ChunkPolicy.text("  my CA is Mr. Iyer ", null, "Noted, Mr. Iyer is your CA."),
        )
        assertEquals("User: remind me later", ChunkPolicy.text("remind me later", null, null))
    }

    @Test
    fun longRepliesAreCutAtAWord() {
        val reply = "word ".repeat(200)
        val text = ChunkPolicy.text("tell me a story please", null, reply)!!
        val answer = text.substringAfter("Assistant: ")
        assertTrue(answer.length <= ChunkPolicy.REPLY_CHARS + 1)
        assertTrue(answer.endsWith("word…"))
    }

    @Test
    fun voiceNotesAndWordlessPicturesAreNotArchived() {
        assertNull(ChunkPolicy.text("", AttachmentKind.AUDIO, "Sure."))
        assertNull(ChunkPolicy.text("what did I say", AttachmentKind.AUDIO, "Sure."))
        assertNull(ChunkPolicy.text("  ", AttachmentKind.IMAGE, "A cat."))
        assertEquals("User: what breed is this dog", ChunkPolicy.text("what breed is this dog", AttachmentKind.IMAGE, null))
    }

    @Test
    fun trivialExchangesAreKeptButNotEmbedded() {
        assertFalse(ChunkPolicy.shouldEmbed("thanks!"))
        assertTrue(ChunkPolicy.shouldEmbed("my dentist is Dr. Rao"))
    }

    @Test
    fun sumsAndPhoneCommandsAreLeftOutOfTheArchive() {
        assertTrue(ChunkPolicy.isUtility("what's 256 multiplied by 4", listOf("calculate")))
        assertTrue(ChunkPolicy.isUtility("turn on the flashlight", listOf("phone_setting")))
        assertTrue(ChunkPolicy.isUtility("timer for 10 minutes", listOf("set_timer")))
        // Answered without the tool, it is still a sum.
        assertTrue(ChunkPolicy.isUtility("what is 25+25", emptyList()))
        assertTrue(ChunkPolicy.isUtility("what's square root of 25", emptyList()))
    }

    @Test
    fun anythingAboutTheUserIsKept() {
        // Another tool means the turn did something worth remembering.
        assertFalse(ChunkPolicy.isUtility("remind me to pay 2+2 rent instalments on the 5th", listOf("add_task")))
        assertFalse(ChunkPolicy.isUtility("text Priya I'm late", listOf("send_message")))
        assertFalse(ChunkPolicy.isUtility("what's the weather in Kochi", listOf("web_lookup")))
        // No operator, or no number: an ordinary message.
        assertFalse(ChunkPolicy.isUtility("my flight is 6-8 am on the 12/03", emptyList()))
        assertFalse(ChunkPolicy.isUtility("my salary went up 10% this year", emptyList()))
        assertFalse(ChunkPolicy.isUtility("I love maths, especially square roots", emptyList()))
        assertFalse(ChunkPolicy.isUtility("my dentist is Dr. Rao", emptyList()))
    }
}
