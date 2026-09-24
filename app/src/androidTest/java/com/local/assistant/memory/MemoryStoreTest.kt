package com.local.assistant.memory

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.SkipReason
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.work.AppForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The storage rules that only a real SQLite can prove: one row per normalised key under a
 * unique index, the keyword indexes (including Indic scripts), tombstones, and sessions. Runs on
 * the bundled SQLite the app ships, in memory, never touching the app's own database.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreTest {

    private var now = 1_000_000L
    private lateinit var database: AppDatabase
    private lateinit var memory: MemoryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        memory = MemoryRepository(database, clock = { now })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun normalisedVariantsOfAKeyAreOneRow() = runBlocking {
        memory.saveFact("User", "Dentist", "Dr. Rao", FactOrigin.CHAT, statedAt = 1)
        memory.saveFact("me", " dentist ", "Dr. Mehta", FactOrigin.CHAT, statedAt = 2)
        val rows = database.factDao().bySubject("user")
        assertEquals(1, rows.size)
        assertEquals("Dr. Mehta", rows.single().value)
        assertEquals(FactCategory.PEOPLE, rows.single().category)
    }

    @Test
    fun keywordSearchFindsAFactByNameWithoutTheHonorific() = runBlocking {
        memory.saveFact("user", "dentist", "Dr. Rao", FactOrigin.CHAT)
        memory.saveFact("user", "pref.reply_style", "short answers", FactOrigin.CHAT, pin = true)
        val found = database.factDao().search("rao", limit = 5)
        assertEquals(listOf("dentist"), found.map { it.attribute })
        // Core facts are always in the prefix, so the per-turn lookup skips them.
        assertTrue(database.factDao().search("short", limit = 5).isEmpty())
    }

    @Test
    fun keywordSearchWorksInIndicScripts() = runBlocking {
        memory.saveFact("user", "city", "बेंगलुरु", FactOrigin.CHAT)
        memory.saveFact("mother", "hometown", "கோயம்புத்தூர்", FactOrigin.CHAT)
        assertEquals("बेंगलुरु", database.factDao().search("बेंगलुरु", 5).single().value)
        assertEquals("mother", database.factDao().search("கோயம்புத்தூர்", 5).single().subject)
    }

    @Test
    fun forgettingLeavesATombstoneThatOlderMessagesCannotCross() = runBlocking {
        memory.saveFact("user", "dentist", "Dr. Rao", FactOrigin.CHAT, statedAt = 100)
        now = 2_000_000L
        val removed = memory.forgetFacts("user", "dentist").removed
        assertEquals(listOf("Dr. Rao"), removed.map { it.value })

        val fromOldMessage = memory.saveFact("user", "dentist", "Dr. Rao", FactOrigin.EXTRACTED, statedAt = 150)
        assertEquals(FactDecision.Skip(SkipReason.FORGOTTEN), fromOldMessage.decision)
        assertTrue(database.factDao().bySubject("user").isEmpty())
        assertTrue("the keyword index forgot it too", database.factDao().search("rao", 5).isEmpty())

        val saidAgain = memory.saveFact("user", "dentist", "Dr. Mehta", FactOrigin.CHAT, statedAt = 2_000_001L)
        assertTrue(saidAgain.decision is FactDecision.Insert)
    }

    @Test
    fun forgettingASubjectForgetsEverythingAboutThem() = runBlocking {
        memory.saveFact("amma", "birthday", "12 May", FactOrigin.CHAT, statedAt = 1)
        memory.saveFact("mom", "phone", "98450 00000", FactOrigin.CHAT, statedAt = 1)
        now = 10
        assertEquals(2, memory.forgetFacts("mother", null).removed.size)
        val relearned = memory.saveFact("mummy", "birthday", "12 May", FactOrigin.EXTRACTED, statedAt = 5)
        assertEquals(FactDecision.Skip(SkipReason.FORGOTTEN), relearned.decision)
    }

    @Test
    fun deletingTheSourceMessageKeepsTheFact() = runBlocking {
        val chatId = database.chatDao().insertChat(ChatEntity(title = "t", createdAt = 1, updatedAt = 1))
        val messageId = database.chatDao().insertMessage(message(chatId, "my dentist is Dr. Rao", createdAt = 1))
        memory.saveFact("user", "dentist", "Dr. Rao", FactOrigin.CHAT, sourceMessageId = messageId)
        database.chatDao().deleteChat(chatId)
        val fact = database.factDao().find("user", "dentist")!!
        assertEquals(null, fact.sourceMessageId)
    }

    @Test
    fun aLongAbsenceStartsANewSession() = runBlocking {
        var clock = 0L
        val foreground = AppForeground(clock = { clock })
        val tracker = SessionTracker(database.sessionDao(), foreground)
        val chatId = database.chatDao().insertChat(ChatEntity(title = "t", createdAt = 0, updatedAt = 0))
        val owner = FakeOwner()

        clock = 1_000
        val first = tracker.sessionFor(chatId, clock)
        database.chatDao().insertMessage(message(chatId, "hello", createdAt = clock, sessionId = first))

        // A short trip away is the same session.
        foreground.onStop(owner)
        clock += 5 * 60_000
        foreground.onStart(owner)
        assertEquals(first, tracker.sessionFor(chatId, clock))

        // Sitting idle with the app open is still the same session.
        clock += 60 * 60_000
        assertEquals(first, tracker.sessionFor(chatId, clock))

        // Ten minutes or more away is a new one, and the old one is closed.
        foreground.onStop(owner)
        clock += AppForeground.SESSION_GAP_MS
        foreground.onStart(owner)
        val second = tracker.sessionFor(chatId, clock)
        assertNotEquals(first, second)
        assertEquals("only the new session is open", second, database.sessionDao().openSession(chatId)!!.id)
    }

    private fun message(chatId: Long, text: String, createdAt: Long, sessionId: Long? = null) = MessageEntity(
        chatId = chatId,
        role = Role.USER,
        text = text,
        createdAt = createdAt,
        sessionId = sessionId,
    )

    private class FakeOwner : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this)
    }
}
