package com.local.assistant.files

import com.local.assistant.files.DocumentReader.Plan
import com.local.assistant.files.DocumentText.Read
import com.local.assistant.llm.BackendCapabilities
import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.work.ModelAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentReaderTest {

    private val estimator = HeuristicTokenEstimator

    /** Answers every one-shot completion with [reply], keeping what it was asked. */
    private class FakeBackend(private val vision: Int = 256, private val reply: (String) -> String) : LlmBackend {
        val inputs = ArrayList<String>()
        var freed = 0
        override val capabilities = BackendCapabilities(tools = true, constrainedJson = false, thinking = false, speculativeDecoding = false, maxContextTokens = 8192, visionTokensPerImage = vision)
        override suspend fun ensureReady() = true
        override suspend fun openChat(spec: ChatSpec): ChatSession = error("not in these tests")
        override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
            inputs += input
            return reply(input)
        }
        override suspend fun release() = Unit
        override fun isContextOverflow(error: Throwable) = false
    }

    private fun reader(backend: FakeBackend) = DocumentReader(backend, object : ModelAccess {
        override suspend fun <T> withModelFree(block: suspend () -> T): T {
            backend.freed++
            return block()
        }
    })

    /** A long text of numbered pages, each about [tokens] by the estimate. */
    private fun pages(count: Int, tokens: Int, special: Map<Int, String> = emptyMap()): String =
        (1..count).joinToString("\n\n") { page ->
            val filler = "This page talks about ordinary things in plain words. ".repeat(tokens * 7 / 2 / 54 + 1)
            "[Page $page]\n" + (special[page]?.let { "$it\n" } ?: "") + filler
        }

    @Test
    fun `what counts as asking for a summary`() {
        assertTrue(Plan.isSummary(null))
        assertTrue(Plan.isSummary("summarise it"))
        assertTrue(Plan.isSummary("give me a summary"))
        assertTrue(Plan.isSummary("what is this about?"))
        assertTrue(Plan.isSummary("what does the agreement say"))
        assertTrue(Plan.isSummary("key points please"))
        assertFalse(Plan.isSummary("what is the deposit amount?"))
        assertFalse(Plan.isSummary("when is the rent due"))
    }

    @Test
    fun `parts stay under their size and lose nothing`() {
        val text = pages(12, 600)
        val parts = Plan.chunks(text, estimator, 1_200)
        assertTrue(parts.size >= 6)
        assertTrue(parts.all { estimator.estimate(it) <= 1_200 })
        assertEquals(text.filter { !it.isWhitespace() }, parts.joinToString("").filter { !it.isWhitespace() })
    }

    @Test
    fun `one very long line is cut between words`() {
        val text = "word ".repeat(5_000)
        val parts = Plan.chunks(text, estimator, 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { estimator.estimate(it) <= 500 })
    }

    @Test
    fun `the parts about the question are picked, in order`() {
        val parts = Plan.chunks(pages(20, 1_000, mapOf(7 to "The security deposit is ₹50,000, refundable.", 15 to "Deposit returned within 30 days of leaving.")), estimator, 1_200)
        val picked = Plan.pick(parts, "how much is the security deposit?", estimator, 2_500)
        assertTrue(picked.any { "₹50,000" in parts[it] })
        assertTrue(picked.any { "30 days" in parts[it] })
        assertEquals(picked.sorted(), picked)
    }

    @Test
    fun `with no word found, the start`() {
        val parts = Plan.chunks(pages(10, 1_000), estimator, 1_200)
        assertEquals(0, Plan.pick(parts, "what's the zebra's name?", estimator, 2_500).first())
    }

    @Test
    fun `spread evenly, first always`() {
        assertEquals(listOf(0, 1, 2), Plan.spread(3, 8))
        assertEquals(listOf(0, 2, 5, 7), Plan.spread(10, 4))
    }

    @Test
    fun `a short document is read in one go`() = runBlocking {
        val backend = FakeBackend { "The rent is ₹18,000 a month." }
        val read = Read.Text("[Page 1]\nRent: ₹18,000 a month, due on the 5th.", parts = 1, unit = "page")
        val reply = reader(backend).answer("Rental_Agreement.pdf", read, "how much is the rent?")
        assertEquals("The rent is ₹18,000 a month.", reply)
        assertEquals(1, backend.inputs.size)
        assertTrue(backend.inputs[0].startsWith("The document \"Rental_Agreement.pdf\", 1 page:"))
        assertTrue("how much is the rent?" in backend.inputs[0])
        assertEquals(1, backend.freed)
    }

    @Test
    fun `a long question reads some parts and says how much`() = runBlocking {
        val backend = FakeBackend { "The deposit is ₹50,000." }
        val text = pages(40, 600, mapOf(23 to "The security deposit is ₹50,000."))
        val reply = reader(backend).answer("lease.pdf", Read.Text(text, parts = 40, unit = "page"), "what's the security deposit?")
        assertEquals(1, backend.inputs.size)
        assertTrue("₹50,000" in backend.inputs[0])
        assertTrue(reply.startsWith("The deposit is ₹50,000.\n\n(I read "))
        assertTrue(reply.endsWith("of its 40 pages for this.)"))
    }

    @Test
    fun `a long summary goes part by part, then together`() = runBlocking {
        val steps = ArrayList<String>()
        val backend = FakeBackend { input -> if (input.startsWith("Part ")) "- a note" else "- The whole thing" }
        val text = pages(60, 1_000)
        val reply = reader(backend).answer("report.pdf", Read.Text(text, parts = 60, unit = "page"), "summarise it", steps::add)
        assertEquals(DocumentReader.MAX_SUMMARY_PARTS + 1, backend.inputs.size)
        assertTrue(backend.inputs.last().startsWith("Notes on the parts of the document \"report.pdf\""))
        assertTrue(reply.startsWith("- The whole thing\n\n(I read "))
        assertEquals("Reading part 1 of 8 of report.pdf", steps.first())
        assertEquals("Putting the summary together", steps.last())
    }

    @Test
    fun `an unreadable one says why, without the model`() = runBlocking {
        val backend = FakeBackend { "x" }
        val reply = reader(backend).answer("aadhaar.pdf", Read.Unreadable(DocumentText.Why.PASSWORD), "what's my aadhaar number?")
        assertEquals("I couldn't read aadhaar.pdf: it's protected with a password.", reply)
        assertEquals(0, backend.freed)
    }

    @Test
    fun `nothing said is not an answer`() = runBlocking {
        val reply = reader(FakeBackend { "  " }).answer("a.txt", Read.Text("Some text here that is long enough to read."), "what is it?")
        assertEquals(DocumentReader.COULDNT_READ_NOW, reply)
    }

    @Test
    fun `pages to look at are read from their text when the model can't see`() = runBlocking {
        val backend = FakeBackend(vision = 0) { "The total is Rs 935." }
        val read = Read.Scanned(emptyList(), pageCount = 1, text = Read.Text("[Page 1]\nTOTAL Rs 935", parts = 1, unit = "page"))
        assertEquals("The total is Rs 935.", reader(backend).answer("receipt.pdf", read, "what's the total?"))
        assertTrue("TOTAL Rs 935" in backend.inputs.single())
    }

    @Test
    fun `pages with no text and no sight say so`() = runBlocking {
        val backend = FakeBackend(vision = 0) { "x" }
        val reply = reader(backend).answer("scan.pdf", Read.Scanned(emptyList(), pageCount = 1), "what's the total?")
        assertTrue(reply.startsWith("scan.pdf is pictures of pages"))
        assertTrue(backend.inputs.isEmpty())
    }

    @Test
    fun `the answer's language is named`() {
        assertTrue("in English." in Plan.questionAsk("how much did I spend on groceries in October?"))
        assertTrue("in Hindi." in Plan.questionAsk("किराया कितना है?"))
        assertTrue("in the language of the question." in Plan.questionAsk("???"))
    }
}
