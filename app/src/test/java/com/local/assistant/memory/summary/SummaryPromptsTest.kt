package com.local.assistant.memory.summary

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPromptsTest {

    private var id = 1L

    private fun msg(role: Role, text: String) = MessageEntity(id = id++, chatId = 1, role = role, text = text, createdAt = id)

    @Test
    fun theTranscriptLabelsTurnsAndLeavesToolRecordsOut() {
        val text = SummaryPrompts.transcript(
            listOf(msg(Role.USER, "my CA is Mr. Iyer"), msg(Role.TOOL, "{\"tool\":\"save_fact\"}"), msg(Role.ASSISTANT, "Noted.")),
            HeuristicTokenEstimator,
            maxTokens = 1_000,
        )
        assertEquals("User: my CA is Mr. Iyer\nAssistant: Noted.", text)
    }

    @Test
    fun longRepliesAreClippedToWhatTheyWere() {
        val code = "line of code ".repeat(300)
        val text = SummaryPrompts.transcript(listOf(msg(Role.USER, "write a website"), msg(Role.ASSISTANT, code)), HeuristicTokenEstimator, 5_000)
        val reply = text.lines().last()
        assertTrue(reply.length < 520)
        assertTrue(reply.endsWith("…"))
    }

    @Test
    fun whenItDoesNotFitTheNewestTurnsAreKept() {
        val turns = (1..60).flatMap { listOf(msg(Role.USER, "question number $it about the trip plans"), msg(Role.ASSISTANT, "answer number $it")) }
        val text = SummaryPrompts.transcript(turns, HeuristicTokenEstimator, maxTokens = 200)
        assertTrue(text.startsWith("(… "))
        assertTrue(text.endsWith("Assistant: answer number 60"))
        assertFalse("question number 1 about" in text)
    }

    @Test
    fun theRollingInputCarriesThePreviousSummary() {
        val first = SummaryPrompts.rollingInput(null, listOf(msg(Role.USER, "hello there friend")), HeuristicTokenEstimator, 500)
        assertTrue(first.startsWith("Summary so far:\n(none)"))
        val next = SummaryPrompts.rollingInput("The user planned a trip.", listOf(msg(Role.USER, "make it Coorg")), HeuristicTokenEstimator, 500)
        assertTrue("The user planned a trip." in next)
        assertTrue(next.endsWith("User: make it Coorg"))
    }

    @Test
    fun cleaningDropsLabelsAndEmptyAnswers() {
        assertEquals("The user asked for a website.", SummaryPrompts.clean("Summary: The user asked for a website."))
        assertEquals("The user asked for a website.", SummaryPrompts.clean("```\nThe user asked for a website.\n```"))
        assertNull(SummaryPrompts.clean("  ok "))
    }

    @Test
    fun wordsScaleWithTheTokenCap() {
        assertEquals(120, SummaryPrompts.wordsFor(200))
        assertEquals(180, SummaryPrompts.wordsFor(300))
    }
}
