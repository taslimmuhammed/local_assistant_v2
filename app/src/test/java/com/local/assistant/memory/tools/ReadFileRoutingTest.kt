package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.files.DocumentReader
import com.local.assistant.files.DocumentText
import com.local.assistant.files.FileFinder
import com.local.assistant.files.FileNames
import com.local.assistant.files.FileTools
import com.local.assistant.gallery.PhotoPeriod
import com.local.assistant.llm.BackendCapabilities
import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.Sampling
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.work.ModelAccess
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** read_file through the real loop and executor, with the phone's files and the model faked. */
class ReadFileRoutingTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private val day = 24 * 60 * 60_000L

    private val onPhone = listOf(
        FileFinder.Found("Rental_Agreement.pdf", "Download/", "/storage/emulated/0/Download/Rental_Agreement.pdf", 11, 210_000, nowMs - 3 * day, "application/pdf"),
        FileFinder.Found("Rental Agreement 2023.docx", "Documents/", "/storage/emulated/0/Documents/Rental Agreement 2023.docx", 12, 40_000, nowMs - 400 * day, null),
        FileFinder.Found("Invoice_10234.pdf", "Download/", "/storage/emulated/0/Download/Invoice_10234.pdf", 13, 90_000, nowMs - 20 * day, "application/pdf"),
        FileFinder.Found("Trip.mp3", "Music/", "/storage/emulated/0/Music/Trip.mp3", 14, 4_000_000, nowMs - 9 * day, "audio/mpeg"),
    )
    private val asked = ArrayList<Pair<String, FileNames.Type>>()
    private var granted = true
    private val completions = ArrayList<String>()

    private val backend = object : LlmBackend {
        override val capabilities = BackendCapabilities(tools = true, constrainedJson = false, thinking = false, speculativeDecoding = false, maxContextTokens = 8192, visionTokensPerImage = 256)
        override suspend fun ensureReady() = true
        override suspend fun openChat(spec: ChatSpec): ChatSession = error("not used")
        override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
            completions += input
            return "The security deposit is ₹50,000."
        }
        override suspend fun release() = Unit
        override fun isContextOverflow(error: Throwable) = false
    }

    private val files = FileTools(
        find = { name, type, period: PhotoPeriod.Range? ->
            asked += name to type
            val words = FileNames.searchWords(name)
            FileNames.rank(words, onPhone.filter { type.allows(it.name) && (period == null || it.modifiedMs in period) }, nowMs, 6) { it.entry }
        },
        folders = { listOf(FileFinder.Folder("Bank", "Documents/Bank/", 7, nowMs)) },
        read = { DocumentText.Read.Text("[Page 1]\nThe security deposit is ₹50,000, refundable.", parts = 1, unit = "page") },
        reader = DocumentReader(backend, object : ModelAccess {
            override suspend fun <T> withModelFree(block: suspend () -> T): T = block()
        }),
        now = { now },
        ready = { granted },
    )

    private val log = FakeToolLog()
    private val executor = ToolExecutor(
        FakeMemoryStore { nowMs }, FakeReminders(), FakeSystemAlarms(), log, now = { now }, files = files,
    )
    private val loop = ToolLoop(executor, maxRounds = 3, isOverflow = { false })

    private fun call(vararg args: Pair<String, Any?>) = ToolCall(ToolCatalog.READ_FILE, mapOf(*args))

    private fun turn(call: ToolCall, userText: String, locked: Boolean = false): Pair<ScriptedSession, List<LoopEvent>> {
        val session = ScriptedSession(ArrayDeque(listOf(ScriptedSession.calls(call), ScriptedSession.says("Found it."))))
        val context = ToolContext(chatId = 1, userMessageId = 10, userText = userText, locked = locked)
        return session to runBlocking { loop.run(session, "envelope", emptyList(), context).toList() }
    }

    private fun result(session: ScriptedSession): Map<*, *> = Gson().fromJson(session.sentResults.first().first().json, Map::class.java)

    @Test
    fun `files found are listed for the model and shown under the reply`() {
        val (session, events) = turn(call("name" to "rental agreement"), "find my rental agreement")
        val result = result(session)
        assertEquals(true, result["ok"])
        assertEquals(2.0, result["found"])
        assertEquals(listOf("Rental_Agreement.pdf", "Rental Agreement 2023.docx"), (result["files"] as List<*>).map { (it as Map<*, *>)["name"] })
        val chip = events.filterIsInstance<LoopEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.FILES, chip.kind)
        assertEquals(listOf(11L, 12L), chip.files!!.files.map { it.mediaId })
        assertEquals(LoopEvent.Text("Found it."), events.filterIsInstance<LoopEvent.Text>().single())
        assertTrue(completions.isEmpty())
    }

    @Test
    fun `a question is answered from the best match on its own, and ends the turn`() {
        val (session, events) = turn(call("name" to "rental agreement", "question" to "what's the deposit?"), "what's the deposit in my rental agreement?")
        assertTrue(session.sentResults.isEmpty())
        val text = events.filterIsInstance<LoopEvent.Text>().joinToString("") { it.delta }
        assertEquals("From Rental_Agreement.pdf:\n\nThe security deposit is ₹50,000.", text)
        assertTrue((events.last() as LoopEvent.Done).staleConversation)
        assertTrue(events.filterIsInstance<LoopEvent.Step>().any { it.label == "Reading Rental_Agreement.pdf" })
        assertEquals("Rental_Agreement.pdf", events.filterIsInstance<LoopEvent.Memory>().single().chip.files!!.read)
    }

    @Test
    fun `asked for a summary in the user's words, it reads even when the model left the question out`() {
        val (session, events) = turn(call("name" to "rental agreement"), "summarise my rental agreement")
        assertTrue(session.sentResults.isEmpty())
        assertTrue(events.filterIsInstance<LoopEvent.Text>().joinToString("") { it.delta }.startsWith("From Rental_Agreement.pdf:"))
        assertTrue(completions.single().contains("Summarise this document"))
    }

    @Test
    fun `a kind with no such file falls back to the name`() {
        val (session, _) = turn(call("name" to "rental agreement 2023", "type" to "spreadsheet"), "find my rental agreement 2023 spreadsheet")
        val result = result(session)
        assertEquals("Rental Agreement 2023.docx", ((result["files"] as List<*>).first() as Map<*, *>)["name"])
        assertTrue(result["note"].toString().startsWith("None of them is a spreadsheet"))
        assertEquals(listOf("rental agreement 2023" to FileNames.Type.SPREADSHEET, "rental agreement 2023" to FileNames.Type.ANY), asked)
    }

    @Test
    fun `nothing named like it says so`() {
        val (session, events) = turn(call("name" to "passport"), "find my passport file")
        val result = result(session)
        assertEquals(0.0, result["found"])
        assertTrue(result["note"].toString().contains("No file on this phone"))
        assertTrue(events.none { it is LoopEvent.Memory })
    }

    @Test
    fun `a file's name keeps the numbers the model gave it`() {
        turn(call("name" to "Invoice_10234"), "open the invoice from last month")
        assertEquals("Invoice_10234", asked.single().first)
    }

    @Test
    fun `the kind is read from the name when the model gave none`() {
        turn(call("name" to "invoice pdf"), "find my invoice pdf")
        assertEquals(FileNames.Type.PDF, asked.single().second)
    }

    @Test
    fun `folders by name`() {
        val (session, events) = turn(call("name" to "bank", "type" to "folder"), "open my bank folder")
        val result = result(session)
        assertEquals("Documents/Bank", ((result["folders"] as List<*>).single() as Map<*, *>)["path"])
        val ref = events.filterIsInstance<LoopEvent.Memory>().single().chip.files!!.files.single()
        assertTrue(ref.folder)
        assertEquals(7, ref.count)
    }

    @Test
    fun `without All files access it says how to allow it`() {
        granted = false
        val (session, _) = turn(call("name" to "rental agreement"), "find my rental agreement")
        val result = result(session)
        assertEquals(false, result["ok"])
        assertTrue(result["error"].toString().contains("All files access"))
    }

    @Test
    fun `on the lock screen it asks for an unlock`() {
        val (session, events) = turn(call("name" to "rental agreement"), "find my rental agreement", locked = true)
        assertTrue(session.sentResults.isEmpty())
        assertTrue(LoopEvent.NeedsUnlock in events)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `declared, and its rule given, only with All files access`() {
        assertFalse(ToolCatalog.declarations(web = false).any { "\"read_file\"" in it })
        assertTrue(ToolCatalog.declarations(web = false, files = true).any { "\"read_file\"" in it })
        assertFalse("read_file" in ToolCatalog.rules(web = false))
        assertTrue("read_file" in ToolCatalog.rules(web = false, files = true))
        assertFalse("{files}" in ToolCatalog.rules(web = false))
        assertFalse("{cant_inbox}" in ToolCatalog.rules(web = false))
        assertTrue("reading their inbox" in ToolCatalog.rules(web = false))
        assertFalse("reading their inbox" in ToolCatalog.rules(web = false, inbox = true))
    }

    @Test
    fun `every declaration is valid JSON with a name and a description`() {
        for (json in ToolCatalog.declarations(web = true, photos = true, health = true, files = true, inbox = true)) {
            val parsed = com.google.gson.JsonParser.parseString(json).asJsonObject
            assertTrue(json, parsed.get("name").asString.isNotBlank())
            assertTrue(json, parsed.get("description").asString.isNotBlank())
        }
    }
}
