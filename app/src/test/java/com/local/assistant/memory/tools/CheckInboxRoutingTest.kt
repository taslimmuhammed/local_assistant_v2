package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.inbox.Inbox
import com.local.assistant.inbox.InboxDao
import com.local.assistant.inbox.InboxMessage
import com.local.assistant.inbox.InboxTools
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.retrieval.ChunkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CheckInboxRoutingTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun message(text: String, sender: String?, at: Long, title: String = sender ?: "Rahul", app: String = "WhatsApp") = InboxMessage(
        source = InboxMessage.Source.NOTIFICATION.name, dedupeKey = "$text$at", packageName = "com.whatsapp", appLabel = app,
        conversationKey = title, title = title, sender = sender, fromMe = sender == null, text = text, at = at, keptAt = at,
    )

    private val kept = mutableListOf(
        message("The Goa trip is on, tickets booked for Friday", "Rahul", nowMs - 20 * hour),
        message("Can you get the cake?", "Amma", nowMs - 2 * hour),
        message("Bring sunscreen for the trip", "Rahul", nowMs - 3 * hour, title = "Goa gang"),
        message("great!", null, nowMs - 19 * hour),
    )

    /** What [InboxDao] would return, worked out over [kept] without SQLite's FTS. */
    private val dao = object : InboxDao {
        override suspend fun insert(rows: List<InboxMessage>): List<Long> = rows.map { kept += it; kept.size.toLong() }
        override suspend fun search(match: String, from: Long, to: Long, limit: Int): List<InboxMessage> {
            val words = match.split(" OR ").map { it.removeSuffix("*") }
            return newest(from, to).filter { row -> words.any { row.text.contains(it, ignoreCase = true) } }.take(limit)
        }
        override suspend fun named(pattern: String, from: Long, to: Long, limit: Int): List<InboxMessage> {
            val name = pattern.trim('%')
            return newest(from, to).filter { (it.sender ?: "").contains(name, true) || it.title.contains(name, true) }.take(limit)
        }
        override suspend fun recent(from: Long, to: Long, limit: Int) = newest(from, to).take(limit)
        override suspend fun fromApp(packageName: String, since: Long) = kept.filter { it.packageName == packageName && it.at >= since }
        override suspend fun apps() = kept.groupBy { it.packageName }.map { (pkg, rows) -> com.local.assistant.inbox.KeptApp(pkg, rows.first().appLabel, rows.size) }
        override suspend fun count() = kept.size
        override suspend fun count(source: String) = kept.count { it.source == source }
        override suspend fun deleteBefore(before: Long): Int = kept.count { it.at < before }.also { kept.removeAll { it.at < before } }
        override suspend fun deleteApp(packageName: String): Int = kept.count { it.packageName == packageName }.also { kept.removeAll { it.packageName == packageName } }
        override suspend fun deleteAll() = kept.clear()
        private fun newest(from: Long, to: Long) = kept.filter { it.at in from..to }.sortedByDescending { it.at }
    }

    private var ready = true
    private val inbox = Inbox(dao, CoroutineScope(Dispatchers.Unconfined), { true }, { emptySet() }, { 0 }, { null }, { false }, clock = { nowMs })
    private val executor = ToolExecutor(
        FakeMemoryStore { nowMs }, FakeReminders(), FakeSystemAlarms(), FakeToolLog(), now = { now },
        inbox = InboxTools(inbox, now = { now }, ready = { ready }),
    )
    private val loop = ToolLoop(executor, maxRounds = 3, isOverflow = { false })

    private fun call(vararg args: Pair<String, Any?>) = ToolCall(ToolCatalog.CHECK_INBOX, mapOf(*args))

    private fun result(call: ToolCall, userText: String): Map<*, *> {
        val session = ScriptedSession(ArrayDeque(listOf(ScriptedSession.calls(call), ScriptedSession.says("Rahul said the trip is on."))))
        val context = ToolContext(chatId = 1, userMessageId = 10, userText = userText)
        runBlocking { loop.run(session, "envelope", emptyList(), context).toList() }
        return Gson().fromJson(session.sentResults.first().first().json, Map::class.java)
    }

    @Test
    fun `what someone said about something, best match first, with who and when`() {
        val result = result(call("from" to "Rahul", "query" to "trip"), "what did Rahul say about the trip?")
        assertEquals(true, result["ok"])
        val lines = result["messages"] as List<*>
        assertEquals(2, lines.size)
        // Both have the word: the newer first.
        assertEquals("Sat 14 Nov, 07:00 · WhatsApp · Rahul in Goa gang: Bring sunscreen for the trip", lines.first())
        assertEquals("Fri 13 Nov, 14:00 · WhatsApp · Rahul: The Goa trip is on, tickets booked for Friday", lines[1])
        assertTrue(result["note"].toString().contains("not the user's words"))
    }

    @Test
    fun `the turn's end says the inbox was read, so no related photos go under it`() {
        val session = ScriptedSession(ArrayDeque(listOf(ScriptedSession.calls(call("when" to "today")), ScriptedSession.says("Amma asked about the cake."))))
        val context = ToolContext(chatId = 1, userMessageId = 10, userText = "do you have any latest messages")
        val events = runBlocking { loop.run(session, "envelope", emptyList(), context).toList() }
        val done = events.last() as LoopEvent.Done
        assertEquals(setOf(ToolCatalog.CHECK_INBOX), done.tools)
        assertTrue(done.tools.any { it in ToolCatalog.ANSWERS_FROM_ELSEWHERE })
    }

    @Test
    fun `nothing from them on it, so their latest, and the note says so`() {
        val result = result(call("from" to "Amma", "query" to "flight"), "did amma message about the flight?")
        assertEquals(listOf("Sat 14 Nov, 08:00 · WhatsApp · Amma: Can you get the cake?"), result["messages"])
        assertTrue(result["note"].toString().startsWith("None of these mentions \"flight\""))
    }

    @Test
    fun `nothing at all is said plainly, with why`() {
        val result = result(call("query" to "electricity bill"), "did I get the electricity bill?")
        assertEquals(0.0, result["found"])
        assertEquals(InboxTools.NONE, result["note"])
    }

    @Test
    fun `within when, and the user's own messages as You`() {
        val result = result(call("when" to "yesterday"), "any messages yesterday?")
        assertEquals(
            listOf(
                "Fri 13 Nov, 15:00 · WhatsApp · You: great!",
                "Fri 13 Nov, 14:00 · WhatsApp · Rahul: The Goa trip is on, tickets booked for Friday",
            ),
            result["messages"],
        )
    }

    @Test
    fun `with messages not kept, the user is told where to turn it on`() {
        ready = false
        val result = result(call("from" to "Rahul"), "what did Rahul say?")
        assertEquals(false, result["ok"])
        assertTrue(result["error"].toString().contains("Settings, under Messages"))
    }

    @Test
    fun `a long message is cut to what a replay keeps`() {
        val line = InboxTools.line(message("word ".repeat(100), "Rahul", nowMs), zone)
        assertTrue(line.length <= InboxTools.MAX_LINE)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `declared, and its rule given, only while messages are kept`() {
        assertFalse(ToolCatalog.declarations(web = false).any { "\"check_inbox\"" in it })
        assertTrue(ToolCatalog.declarations(web = false, inbox = true).any { "\"check_inbox\"" in it })
        assertFalse("check_inbox" in ToolCatalog.rules(web = false))
        assertTrue("check_inbox" in ToolCatalog.rules(web = false, inbox = true))
        assertFalse("{inbox}" in ToolCatalog.rules(web = false, inbox = true))
        assertFalse("{inbox}" in ToolCatalog.rules(web = false))
    }

    @Test
    fun `other people's words are never learned from, and it doesn't run on the lock screen`() {
        assertTrue(ToolCatalog.CHECK_INBOX in ChunkPolicy.UTILITY_TOOLS)
        assertTrue(ToolCatalog.CHECK_INBOX in ToolCatalog.OUTSIDE_MEMORY)
        assertFalse(ToolCatalog.CHECK_INBOX in ToolCatalog.LOCKED)
        assertFalse(ToolCatalog.CHECK_INBOX in ToolCatalog.LOOKUPS)
    }
}
