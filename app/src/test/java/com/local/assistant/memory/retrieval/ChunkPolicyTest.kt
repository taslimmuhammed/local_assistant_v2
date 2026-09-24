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
}
