package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactUpsertPolicyTest {

    private val now = 1_000_000L

    private fun write(
        subject: String = "user",
        attribute: String = "dentist",
        value: String = "Dr. Rao",
        pin: Boolean = false,
        origin: FactOrigin = FactOrigin.CHAT,
        statedAt: Long = now,
    ) = FactWrite(
        subject = FactKeys.subject(subject),
        attribute = FactKeys.attribute(attribute),
        value = FactKeys.value(value),
        pin = pin,
        origin = origin,
        statedAt = statedAt,
        sourceMessageId = 7,
    )

    /** Applies a decision the way the repository does, so sequences of writes can be tested. */
    private fun apply(existing: FactEntity?, w: FactWrite, forgottenAt: Long? = null): FactEntity? =
        when (val d = FactUpsertPolicy.decide(existing, w, forgottenAt, now)) {
            is FactDecision.Insert -> d.fact.copy(id = 1)
            is FactDecision.Update -> d.fact
            is FactDecision.Confirm -> d.fact
            is FactDecision.Skip -> existing
        }

    @Test
    fun `a new fact is inserted with its category assigned in code`() {
        val decision = FactUpsertPolicy.decide(null, write(), forgottenAt = null, now = now)
        val fact = (decision as FactDecision.Insert).fact
        assertEquals("user", fact.subject)
        assertEquals("dentist", fact.attribute)
        assertEquals("Dr. Rao", fact.value)
        assertEquals(FactCategory.PEOPLE, fact.category)
        assertFalse(fact.core)
        assertEquals(now, fact.lastConfirmedAt)
    }

    @Test
    fun `normalised variants of a key are one row, and the new value replaces the old`() {
        assertEquals(FactKeys.attribute("Dentist"), FactKeys.attribute(" dentist "))
        assertEquals(FactKeys.subject("User"), FactKeys.subject(" my "))

        val first = apply(null, write(attribute = "Dentist", value = "Dr. Rao", statedAt = now - 10))
        val second = FactUpsertPolicy.decide(first, write(attribute = " dentist ", value = "Dr. Mehta"), null, now)
        val updated = (second as FactDecision.Update).fact
        assertEquals(first!!.id, updated.id)
        assertEquals("Dr. Mehta", updated.value)
    }

    @Test
    fun `the newest statement wins`() {
        val stored = apply(null, write(value = "Dr. Rao", statedAt = 100))
        val newer = FactUpsertPolicy.decide(stored, write(value = "Dr. Mehta", statedAt = 200), null, now)
        assertEquals("Dr. Mehta", (newer as FactDecision.Update).fact.value)
        assertEquals(200, newer.fact.statedAt)
    }

    @Test
    fun `an older contradicting statement changes nothing`() {
        val stored = apply(null, write(value = "Dr. Mehta", statedAt = 200))
        val older = FactUpsertPolicy.decide(stored, write(value = "Dr. Rao", statedAt = 100), null, now)
        assertEquals(FactDecision.Skip(SkipReason.STALE), older)
    }

    @Test
    fun `restating the same value only bumps last confirmed`() {
        val stored = apply(null, write(value = "Dr. Rao", statedAt = 100))!!
        val again = FactUpsertPolicy.decide(stored, write(value = "dr. rao.", statedAt = 300), null, now)
        val confirmed = (again as FactDecision.Confirm).fact
        assertEquals(stored.copy(lastConfirmedAt = 300), confirmed)
    }

    @Test
    fun `an older restatement never moves last confirmed backwards`() {
        val stored = apply(null, write(statedAt = 300))!!
        val again = FactUpsertPolicy.decide(stored, write(statedAt = 100), null, now)
        assertEquals(300, (again as FactDecision.Confirm).fact.lastConfirmedAt)
    }

    @Test
    fun `extraction never overwrites a user edit, however new`() {
        val edited = apply(null, write(value = "Dr. Rao", origin = FactOrigin.USER_EDIT, statedAt = 100))
        val extracted = FactUpsertPolicy.decide(
            edited,
            write(value = "Dr. Mehta", origin = FactOrigin.EXTRACTED, statedAt = 500),
            null,
            now,
        )
        assertEquals(FactDecision.Skip(SkipReason.USER_EDIT_PROTECTED), extracted)
    }

    @Test
    fun `a newer chat statement does overwrite a user edit`() {
        val edited = apply(null, write(value = "Dr. Rao", origin = FactOrigin.USER_EDIT, statedAt = 100))
        val chat = FactUpsertPolicy.decide(edited, write(value = "Dr. Mehta", statedAt = 500), null, now)
        assertEquals(FactOrigin.CHAT, (chat as FactDecision.Update).fact.origin)
    }

    @Test
    fun `a tombstoned fact is not re-learned from an older message`() {
        val forgottenAt = 1_000L
        val fromOldMessage = write(origin = FactOrigin.EXTRACTED, statedAt = 900)
        assertEquals(
            FactDecision.Skip(SkipReason.FORGOTTEN),
            FactUpsertPolicy.decide(null, fromOldMessage, forgottenAt, now),
        )
    }

    @Test
    fun `something said again after forgetting is recorded`() {
        val saidAgain = write(statedAt = 2_000)
        assertTrue(FactUpsertPolicy.decide(null, saidAgain, forgottenAt = 1_000, now = now) is FactDecision.Insert)
    }

    @Test
    fun `only facts about the user may be core`() {
        val aboutMother = FactUpsertPolicy.decide(null, write(subject = "amma", attribute = "birthday", value = "12 May", pin = true), null, now)
        val fact = (aboutMother as FactDecision.Insert).fact
        assertEquals("mother", fact.subject)
        assertFalse(fact.core)
    }

    @Test
    fun `pinning from chat is honoured, and a later unpinned write keeps the pin`() {
        val pinned = apply(null, write(attribute = "pref.reply_style", value = "short answers", pin = true))!!
        assertTrue(pinned.core)
        assertEquals(FactCategory.PREFERENCE, pinned.category)

        val restated = FactUpsertPolicy.decide(pinned, write(attribute = "reply style", value = "short answers"), null, now)
        assertTrue((restated as FactDecision.Confirm).fact.core)
    }

    @Test
    fun `extraction cannot pin a fact`() {
        val stored = apply(null, write(attribute = "city", value = "Bengaluru"))
        val extracted = FactUpsertPolicy.decide(stored, write(attribute = "city", value = "Bengaluru", pin = true, origin = FactOrigin.EXTRACTED), null, now)
        assertFalse((extracted as FactDecision.Confirm).fact.core)
    }

    @Test
    fun `confirming a value on the memory screen protects it from extraction`() {
        val extracted = apply(null, write(origin = FactOrigin.EXTRACTED, statedAt = 100))!!
        val confirmed = apply(extracted, write(origin = FactOrigin.USER_EDIT, statedAt = 200))!!
        assertEquals(FactOrigin.USER_EDIT, confirmed.origin)
    }

    @Test
    fun `blank keys or values are rejected`() {
        assertEquals(FactDecision.Skip(SkipReason.INVALID), FactUpsertPolicy.decide(null, write(value = "  "), null, now))
        assertEquals(FactDecision.Skip(SkipReason.INVALID), FactUpsertPolicy.decide(null, write(attribute = "!!"), null, now))
    }
}
