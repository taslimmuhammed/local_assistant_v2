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

    private val onPhone = mutableListOf(
        FileFinder.Found("Rental_Agreement.pdf", "Download/", "/storage/emulated/0/Download/Rental_Agreement.pdf", 11, 210_000, nowMs - 3 * day, "application/pdf"),
        FileFinder.Found("Rental Agreement 2023.docx", "Documents/", "/storage/emulated/0/Documents/Rental Agreement 2023.docx", 12, 40_000, nowMs - 400 * day, null),
        FileFinder.Found("Invoice_10234.pdf", "Download/", "/storage/emulated/0/Download/Invoice_10234.pdf", 13, 90_000, nowMs - 20 * day, "application/pdf"),
        FileFinder.Found("Trip.mp3", "Music/", "/storage/emulated/0/Music/Trip.mp3", 14, 4_000_000, nowMs - 9 * day, "audio/mpeg"),
    )
    private val asked = ArrayList<Pair<String, FileNames.Type>>()
    private var granted = true
    private val bill = java.io.File.createTempFile("0f8fad5b-d9cb-469f-a165-70867728950e-", "-Electricity_Bill_Oct.pdf").apply { deleteOnExit() }
    private val attachedBill = AttachedDocument(bill.path, "Electricity_Bill_Oct.pdf")
    private var inChat = emptyList<AttachedDocument>()
    private val completions = ArrayList<String>()
    /** What a file reads as, by its name; the rental agreement's text for any other. */
    private val texts = HashMap<String, DocumentText.Read>()
    private val readNames = ArrayList<String>()

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
        read = { found ->
            readNames += found.name
            texts[found.name] ?: DocumentText.Read.Text("[Page 1]\nThe security deposit is ₹50,000, refundable.", parts = 1, unit = "page")
        },
        reader = DocumentReader(backend, object : ModelAccess {
            override suspend fun <T> withModelFree(block: suspend () -> T): T = block()
        }),
        now = { now },
        ready = { granted },
        attached = { inChat },
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
        assertEquals("Found it.", events.filterIsInstance<LoopEvent.Text>().single().delta)
        assertTrue(LoopEvent.Confirm in events)
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

    private fun attachedTurn(userText: String): List<LoopEvent> {
        val context = ToolContext(chatId = 1, userMessageId = 10, userText = userText, document = attachedBill)
        return runBlocking { loop.readAttached(context).toList() }
    }

    @Test
    fun `an attached document's question is answered from it alone, recorded as read_file`() {
        val events = attachedTurn("when is this due?")
        assertEquals("The security deposit is ₹50,000.", events.filterIsInstance<LoopEvent.Text>().joinToString("") { it.delta })
        assertTrue((events.last() as LoopEvent.Done).staleConversation)
        assertTrue(events.filterIsInstance<LoopEvent.Step>().first().label.startsWith("Reading Electricity_Bill_Oct.pdf"))
        // Shown with the message already: nothing under the reply.
        assertTrue(events.none { it is LoopEvent.Memory })
        assertTrue(completions.single().contains("when is this due?"))
        val record = log.records.values.single()
        assertEquals(ToolCatalog.READ_FILE, record.tool)
        assertEquals(true, record.arguments[ToolExecutor.ATTACHED])
        assertEquals("Electricity_Bill_Oct.pdf", record.arguments["name"])
        // Never searched the phone for it.
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an attached document with nothing said is summarised`() {
        attachedTurn("  ")
        assertTrue(completions.single().contains("Summarise this document"))
        assertFalse(log.records.values.single().arguments.containsKey("question"))
    }

    @Test
    fun `a request to act on an attached document gets notes on it for the model, kept as the call's result`() {
        val context = ToolContext(chatId = 1, userMessageId = 10, userText = "remind me to pay this bill", document = attachedBill)
        val notes = runBlocking { loop.notesOnAttached(context, "remind me to pay this bill") }
        assertEquals("[Attached document \"Electricity_Bill_Oct.pdf\". The app read it; notes on it for this request, not the user's words: The security deposit is ₹50,000.]", notes)
        assertTrue(completions.single().contains("remind me to pay this bill"))
        val record = log.records.values.single()
        assertEquals("remind me to pay this bill", record.arguments[ToolExecutor.NOTES_FOR])
        assertTrue(record.result.contains("\"notes\""))
    }

    @Test
    fun `a document attached earlier in the chat is found by its name, even without All files access`() {
        granted = false
        inChat = listOf(attachedBill)
        val (session, _) = turn(call("name" to "electricity bill"), "find the electricity bill")
        val result = result(session)
        assertEquals(true, result["ok"])
        val first = (result["files"] as List<*>).first() as Map<*, *>
        assertEquals("Electricity_Bill_Oct.pdf", first["name"])
        assertEquals(FileTools.ATTACHED_FOLDER, first["folder"])
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a question about it later reads the attached copy first`() {
        inChat = listOf(attachedBill)
        val (_, events) = turn(call("name" to "Electricity_Bill_Oct.pdf", "question" to "what's the amount?"), "what's the amount in the electricity bill?")
        assertEquals("Electricity_Bill_Oct.pdf", events.filterIsInstance<LoopEvent.Memory>().single().chip.files!!.read)
    }

    @Test
    fun `the phone's own copy of what was attached isn't listed again, and only the copy read is marked`() {
        inChat = listOf(attachedBill)
        // The same file in Download, where it was attached from; and an older bill, not the same.
        val copy = FileFinder.Found("Electricity_Bill_Oct.pdf", "Download/", "/storage/emulated/0/Download/Electricity_Bill_Oct.pdf", 40, bill.length(), nowMs - day, "application/pdf").also { onPhone += it }
        val other = FileFinder.Found("Electricity_Bill_Oct.pdf", "Documents/", "/storage/emulated/0/Documents/Electricity_Bill_Oct.pdf", 41, 55_000, nowMs - 400 * day, "application/pdf").also { onPhone += it }
        val (_, events) = turn(call("name" to "electricity bill", "question" to "what's the amount?"), "what's the amount in the electricity bill?")
        val files = events.filterIsInstance<LoopEvent.Memory>().single().chip.files!!
        assertEquals(listOf(attachedBill.path, other.path), files.files.map { it.path })
        assertEquals(listOf(true, false), files.files.map(files::wasRead))
        assertFalse(files.files.any { it.path == copy.path })
    }

    private fun onPhone(name: String, folder: String = "Download/") =
        FileFinder.Found(name, folder, "/storage/emulated/0/$folder$name", 30L + onPhone.size, 120_000, nowMs - 30 * day, null).also { onPhone += it }

    @Test
    fun `an ID number is read from a document named after its card, with its check`() {
        onPhone("e-PAN_card.pdf")
        // Named like it, but not a document: never read.
        onPhone("pan_photo.zip")
        texts["e-PAN_card.pdf"] = DocumentText.Read.Text("[Page 1]\nIncome Tax Department\nPermanent Account Number\nABCPE1234F\nName: A Person", parts = 1, unit = "page")
        val found = runBlocking { files.idNumber(com.local.assistant.gallery.PhotoReader.Field.PAN) }
        val answer = found.answer!!
        assertEquals("Your PAN card file, e-PAN_card.pdf, shows your PAN: ABCPE1234F.", answer.reply)
        assertEquals("e-PAN_card.pdf", answer.chip!!.files!!.read)
        // The number is in the reply only, not in what the chat keeps of the call.
        assertFalse(answer.result.values.any { it.toString().contains("ABCPE1234F") })
        assertEquals(listOf("e-PAN_card.pdf"), readNames)
        assertTrue(completions.isEmpty())
    }

    @Test
    fun `a number without its shape isn't taken, and a locked file is named`() {
        onPhone("PAN.pdf")
        onPhone("pan card scan.pdf", folder = "Documents/IDs/")
        texts["PAN.pdf"] = DocumentText.Read.Text("[Page 1]\nApply for a PAN at the nearest centre. Ref 123456.", parts = 1, unit = "page")
        texts["pan card scan.pdf"] = DocumentText.Read.Unreadable(DocumentText.Why.PASSWORD)
        val found = runBlocking { files.idNumber(com.local.assistant.gallery.PhotoReader.Field.PAN) }
        assertEquals(null, found.answer)
        assertEquals(
            "I also found pan card scan.pdf in IDs, but it's locked with a password, so I couldn't read it. Open it in your Files app to see the number.",
            found.lockedNote,
        )
    }

    @Test
    fun `without All files access, or for a bank card, no file is looked at`() {
        onPhone("e-PAN.pdf")
        granted = false
        assertEquals(null, runBlocking { files.idNumber(com.local.assistant.gallery.PhotoReader.Field.PAN) }.answer)
        granted = true
        assertEquals(null, runBlocking { files.idNumber(com.local.assistant.gallery.PhotoReader.Field.CARD) }.answer)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `beside a photo search, a number from a file wins, and a locked one is named when no photo gave it`() = runBlocking {
        val photoSearch = ActionResult(linkedMapOf("ok" to true, "found" to 2), reply = "The closest photo I found isn't your PAN card, so I couldn't find your PAN.")
        val fromFile = FileTools.IdFiles(ActionResult(linkedMapOf("ok" to true), reply = "Your PAN card file, e-PAN.pdf, shows your PAN: ABCPE1234F."))
        var searched = false
        val won = com.local.assistant.gallery.PhotoTools.withIdFiles(com.local.assistant.gallery.PhotoReader.Field.PAN, fromFile) { searched = true; photoSearch }
        assertEquals(fromFile.answer, won)
        assertFalse(searched)

        val locked = FileTools.IdFiles(null, listOf(FileFinder.Found("e-PAN.pdf", "Download/", null, 1, 1, 1, null)))
        val named = com.local.assistant.gallery.PhotoTools.withIdFiles(com.local.assistant.gallery.PhotoReader.Field.PAN, locked) { photoSearch }
        assertTrue(named.reply!!.endsWith("I also found e-PAN.pdf in Download, but it's locked with a password, so I couldn't read it. Open it in your Files app to see the number."))

        // A photo that gave the number: the reply as it was.
        val fromPhoto = photoSearch.copy(reply = "Your PAN card shows your PAN: ABCPE1234F.")
        assertEquals(fromPhoto, com.local.assistant.gallery.PhotoTools.withIdFiles(com.local.assistant.gallery.PhotoReader.Field.PAN, locked) { fromPhoto })

        // Photos that can't be searched yet: the locked file still said.
        val notReady = com.local.assistant.gallery.PhotoTools.withIdFiles(com.local.assistant.gallery.PhotoReader.Field.PAN, locked) { throw ToolError("The photos aren't ready for search yet.") }
        assertTrue(notReady.result["note"].toString().contains("locked with a password"))
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
