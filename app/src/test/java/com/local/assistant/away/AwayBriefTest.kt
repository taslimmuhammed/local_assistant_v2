package com.local.assistant.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names, numbers and messages here are made up. */
class AwayBriefTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun seen(
        title: String? = null,
        text: String? = null,
        category: String? = null,
        messages: List<SeenNotification.Message> = emptyList(),
        packageName: String = "com.whatsapp",
        isGroup: Boolean = false,
        ongoing: Boolean = false,
        importance: Int? = 3,
        postedAt: Long = now,
        id: Int = 1,
    ) = SeenNotification(
        packageName = packageName,
        appLabel = if (packageName == "com.whatsapp") "WhatsApp" else "App",
        key = "0|$packageName|$id|null|10123",
        id = id,
        tag = null,
        postedAt = postedAt,
        category = category,
        ongoing = ongoing,
        groupSummary = false,
        importance = importance,
        title = title,
        text = text,
        bigText = null,
        messages = messages,
        isGroup = isGroup,
        shortcutId = if (messages.isEmpty()) null else "rahul@s.whatsapp.net",
    )

    private fun read(notification: SeenNotification, ignored: Set<String> = emptySet()) =
        NotificationLines.read(notification, ownPackage = "com.local.assistant", ignored = ignored, now = now)

    // ---- Reading notifications ----

    @Test
    fun `a chat's messages become lines of one conversation, the user's own marked`() {
        val lines = read(
            seen(
                title = "Rahul",
                category = "msg",
                messages = listOf(
                    SeenNotification.Message("Rahul", "Are we still on for Saturday?", now - 2 * minute),
                    SeenNotification.Message(null, "Checking", now - minute),
                ),
            ),
        )
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.kind == Kind.MESSAGE.name && it.conversationKey == "com.whatsapp|m|rahul@s.whatsapp.net" })
        assertEquals("Rahul", lines[0].title)
        assertFalse(lines[0].fromMe)
        assertTrue(lines[1].fromMe)
    }

    @Test
    fun `a missed call names the caller, whichever line holds the words`() {
        val one = read(seen(title = "Missed call", text = "Amma", category = "missed_call", packageName = "com.android.dialer")).single()
        assertEquals(Kind.CALL.name, one.kind)
        assertEquals("Amma", one.title)
        val two = read(seen(title = "Amma", text = "Missed voice call", packageName = "com.android.dialer")).single()
        assertEquals(Kind.CALL.name, two.kind)
        assertEquals("Amma", two.title)
        assertEquals(one.conversationKey, two.conversationKey)
    }

    @Test
    fun `a message about missed calls is still a message`() {
        val line = read(
            seen(title = "Rahul", category = "msg", messages = listOf(SeenNotification.Message("Rahul", "Sorry, missed calls all day", now))),
        ).single()
        assertEquals(Kind.MESSAGE.name, line.kind)
    }

    @Test
    fun `one-time codes are never kept`() {
        assertTrue(read(seen(title = "AX-BANK", text = "482913 is your OTP for login. Do not share.", packageName = "com.google.android.apps.messaging")).isEmpty())
        assertTrue(read(seen(title = "Shop", text = "Your verification code is 5521", packageName = "com.shop")).isEmpty())
        assertTrue(read(seen(title = "Bank", text = "Sensitive notification content hidden", packageName = "com.bank")).isEmpty())
    }

    @Test
    fun `what isn't news is left out`() {
        assertTrue(read(seen(title = "Now playing", text = "Song", ongoing = true, packageName = "com.music")).isEmpty())
        assertTrue(read(seen(title = "Hi", text = "There", packageName = "com.local.assistant")).isEmpty())
        assertTrue(read(seen(title = "Hi", text = "There", packageName = "com.app"), ignored = setOf("com.app")).isEmpty())
        assertTrue(read(seen(title = "Tip", text = "Try this", importance = 1, packageName = "com.app")).isEmpty())
        assertTrue(read(seen(title = "Uploading", text = "3 files", category = "progress", packageName = "com.app")).isEmpty())
    }

    @Test
    fun `the same app notification in the same hour is one line`() {
        val first = read(seen(title = "Order", text = "Your order was delivered", packageName = "com.shop", postedAt = now)).single()
        val again = read(seen(title = "Order", text = "Your order was delivered", packageName = "com.shop", postedAt = now + minute)).single()
        assertEquals(first.dedupeKey, again.dedupeKey)
        assertEquals(Kind.OTHER.name, first.kind)
    }

    @Test
    fun `long lines are cut`() {
        val line = read(seen(title = "News", text = "word ".repeat(400), packageName = "com.news")).single()
        assertTrue(line.text.length <= NotificationLines.MAX_CHARS)
        assertTrue(line.text.endsWith("…"))
    }

    // ---- Sorting without the model ----

    private var nextId = 1L

    private fun line(
        text: String,
        fromMe: Boolean = false,
        kind: Kind = Kind.MESSAGE,
        key: String = "com.whatsapp|m|rahul",
        title: String = "Rahul",
        isGroup: Boolean = false,
        at: Long = now,
        briefedAt: Long? = null,
    ) = CapturedEntity(
        id = nextId++,
        dedupeKey = "d${nextId}",
        notificationKey = "n",
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        kind = kind.name,
        conversationKey = key,
        title = title,
        sender = if (fromMe) null else title,
        fromMe = fromMe,
        isGroup = isGroup,
        text = text,
        postedAt = at,
        capturedAt = at,
        briefedAt = briefedAt,
    )

    @Test
    fun `rules put calls and questions first`() {
        assertEquals(Level.NEED, BriefRules.level(Kind.CALL, false, listOf(line("Missed call", kind = Kind.CALL))))
        assertEquals(Level.NEED, BriefRules.level(Kind.MESSAGE, false, listOf(line("Are you free tonight?"))))
        assertEquals(Level.NEED, BriefRules.level(Kind.MESSAGE, false, listOf(line("kal kab aa rahe ho"))))
        assertEquals(Level.FYI, BriefRules.level(Kind.MESSAGE, false, listOf(line("Reached home"))))
        assertEquals(Level.FYI, BriefRules.level(Kind.MESSAGE, true, listOf(line("Who's coming?", isGroup = true))))
        assertEquals(Level.SKIP, BriefRules.level(Kind.MESSAGE, true, listOf(line("lol", isGroup = true))))
        assertEquals(Level.SKIP, BriefRules.level(Kind.MESSAGE, false, listOf(line("Are you free?"), line("Yes", fromMe = true))))
        assertEquals(Level.FYI, BriefRules.level(Kind.OTHER, false, listOf(line("Your parcel was delivered", kind = Kind.OTHER))))
        assertEquals(Level.SKIP, BriefRules.level(Kind.OTHER, false, listOf(line("50% off everything", kind = Kind.OTHER))))
        // Selling wins over the words that make an app's notification worth knowing.
        assertEquals(Level.SKIP, BriefRules.level(Kind.OTHER, false, listOf(line("Free delivery on orders above 199 today only", kind = Kind.OTHER))))
        assertEquals(Level.SKIP, BriefRules.level(Kind.OTHER, false, listOf(line("Flat 50% off on your next 3 orders. Order now!", kind = Kind.OTHER))))
        assertEquals(Level.FYI, BriefRules.level(Kind.OTHER, false, listOf(line("Payment of Rs 499 received", kind = Kind.OTHER))))
    }

    @Test
    fun `the headline names who called and who is waiting until the model writes one`() {
        val brief = BriefBuilder.build(
            listOf(
                line("Missed call", kind = Kind.CALL, key = "dialer|c|amma", title = "Amma"),
                line("Are we still on for Saturday?"),
            ),
            triage = emptyList(),
            newOnly = false,
        )
        assertEquals("Amma called · Rahul is waiting on you", brief.headline)
        assertEquals(2, brief.needs.size)
    }

    // ---- The model's reading ----

    @Test
    fun `a reading counts once it has seen the last line`() {
        val first = line("Are we still on for Saturday?")
        val reading = TriageEntity("com.whatsapp|m|rahul", upToId = first.id, level = Level.NEED.name, gist = "Asks about Saturday", replies = "Yes, see you then\nCan we do Sunday?", triagedAt = now)
        val read = BriefBuilder.build(listOf(first), listOf(reading), newOnly = false).needs.single()
        assertTrue(read.read)
        assertEquals("Asks about Saturday", read.gist)
        assertEquals(listOf("Yes, see you then", "Can we do Sunday?"), read.replies)

        // A new line since: no stale replies, and the more pressing level of the two.
        val skipped = reading.copy(level = Level.SKIP.name)
        val stale = BriefBuilder.build(listOf(first, line("Hello?? Reply na")), listOf(skipped), newOnly = false).needs.single()
        assertFalse(stale.read)
        assertTrue(stale.replies.isEmpty())
        assertEquals("Hello?? Reply na", stale.gist)
    }

    @Test
    fun `the brief at unlock leaves out what an earlier brief showed`() {
        val shown = line("Are you free?", briefedAt = now - minute)
        val fresh = line("Call me when free", key = "com.whatsapp|m|priya", title = "Priya")
        val brief = BriefBuilder.build(listOf(shown, fresh), emptyList(), newOnly = true)
        assertEquals(listOf("Priya"), (brief.needs + brief.fyi).map { it.title })
        assertEquals(2, BriefBuilder.build(listOf(shown, fresh), emptyList(), newOnly = false).needs.size)
    }

    private fun entries(): List<BriefTriage.Entry> {
        val chat = BriefBuilder.build(listOf(line("Dinner at 7 tomorrow? Can you come?")), emptyList(), newOnly = false).needs.single()
        val call = BriefBuilder.build(listOf(line("Missed call", kind = Kind.CALL, key = "d|c|amma", title = "Amma")), emptyList(), newOnly = false).needs.single()
        return listOf(BriefTriage.Entry(1, chat), BriefTriage.Entry(2, call))
    }

    @Test
    fun `the answer is found inside fences and chatter`() {
        val answer = """
            Sure! Here it is:
            ```json
            {"headline": "Rahul asks about dinner; Amma called.",
             "items": [
               {"id": 1, "level": "need", "gist": "Asks if you can come to dinner", "replies": ["Yes, see you at 7!", "Can't tomorrow, sorry"]},
               {"id": 2, "level": "need", "gist": "Missed call", "replies": ["Hi"]},
               {"id": 9, "level": "need", "gist": "Not asked about"}
             ]}
            ```
        """.trimIndent()
        val reading = assertNotNullReading(BriefTriage.parse(answer, entries()))
        assertEquals("Rahul asks about dinner; Amma called.", reading.headline)
        assertEquals(setOf(1, 2), reading.items.keys)
        assertEquals(listOf("Yes, see you at 7!", "Can't tomorrow, sorry"), reading.items[1]?.replies)
        // No replies to a missed call.
        assertTrue(reading.items[2]?.replies.orEmpty().isEmpty())
    }

    @Test
    fun `a reply with a number they never said is dropped`() {
        val answer = """{"items": [{"id": 1, "level": "need", "gist": "Dinner", "replies": ["See you at 7:30", "See you at 7"]}]}"""
        assertEquals(listOf("See you at 7"), BriefTriage.parse(answer, entries())?.items?.get(1)?.replies)
    }

    @Test
    fun `only needs get replies`() {
        val answer = """{"items": [{"id": 1, "level": "fyi", "gist": "Dinner plan", "replies": ["Ok"]}]}"""
        assertTrue(BriefTriage.parse(answer, entries())?.items?.get(1)?.replies.orEmpty().isEmpty())
    }

    @Test
    fun `anything but the JSON asked for is no reading`() {
        assertNull(BriefTriage.parse("I can't help with that.", entries()))
        assertNull(BriefTriage.parse("""{"items": "none"}""", entries()))
        assertNull(BriefTriage.parse("""{"items": [{"id": 1, "level": "urgent"}]}""", entries()))
        assertNull(BriefTriage.parse("""{"items": [ {"id": 1, "level": "need" """, entries()))
    }

    private fun chat(vararg said: String): BriefItem =
        BriefBuilder.build(said.map { line(it) }, emptyList(), newOnly = false).let { it.needs + it.fyi + it.skipped }.single()

    @Test
    fun `each chat is named with the language its person writes in`() {
        assertEquals("English", BriefTriage.language(chat("Hey! Are we still on for dinner tomorrow at 8?", "Let me know, I'll book the table")))
        assertEquals("Hinglish", BriefTriage.language(chat("Bhai aaj ki class ke notes bhej dena", "kal test hai")))
        assertEquals("Hinglish", BriefTriage.language(chat("haan?")))
        assertEquals("Hindi", BriefTriage.language(chat("कल मिलते हैं?")))
        assertEquals("Malayalam", BriefTriage.language(chat("നാളെ വരുമോ?")))
        assertNull(BriefTriage.language(chat("👍🙏")))
        // English with a Hindi word in it is still English.
        assertEquals("English", BriefTriage.language(chat("Thanks bhai, see you at the station tomorrow morning")))

        val rendered = BriefTriage.render(entries(), java.time.ZoneOffset.UTC)
        assertTrue(rendered, rendered.contains("chat with Rahul · 1 new · writes in English"))
        assertFalse(rendered, rendered.contains("missed call from Amma · writes"))
    }

    @Test
    fun `an English chat gets no Hinglish replies`() {
        val answer = """{"items": [{"id": 1, "level": "need", "gist": "Dinner", "replies": ["Haan, dinner ke liye main free hoon.", "Yes, I'll come at 7!", "Sorry, kal nahi ho payega."]}]}"""
        assertEquals(listOf("Yes, I'll come at 7!"), BriefTriage.parse(answer, entries())?.items?.get(1)?.replies)
    }

    @Test
    fun `the gist leaves out the name it sits under`() {
        assertEquals("Needs to know if you are free tomorrow.", BriefTriage.withoutName("Arjun Mehta needs to know if you are free tomorrow.", "Arjun Mehta"))
        assertEquals("Needs today's class notes", BriefTriage.withoutName("Priya needs today's class notes", "Priya"))
        assertEquals("Asking about dinner", BriefTriage.withoutName("Arjun is asking about dinner", "Arjun Mehta"))
        assertEquals("Asks if you're coming", BriefTriage.withoutName("Asks if you're coming", "Priya"))
        // Someone else, or the name as part of what it's about, stays.
        assertEquals("Priyanka asks about notes", BriefTriage.withoutName("Priyanka asks about notes", "Priya"))
        assertEquals("Priya's test is tomorrow", BriefTriage.withoutName("Priya's test is tomorrow", "Priya"))
        // Nothing left but a word: keep it whole.
        assertEquals("Amma called", BriefTriage.withoutName("Amma called", "Amma"))

        val answer = """{"items": [{"id": 1, "level": "need", "gist": "Rahul asks if you can come to dinner"}]}"""
        assertEquals("Asks if you can come to dinner", BriefTriage.parse(answer, entries())?.items?.get(1)?.gist)
    }

    private fun assertNotNullReading(reading: BriefTriage.Reading?): BriefTriage.Reading {
        assertNotNull(reading)
        return reading!!
    }

    // ---- Voice ----

    @Test
    fun `said to a reply - send, the other one, cancel, or the reply itself`() {
        assertEquals(VoiceReply.Meaning.SEND, VoiceReply.meaning("Send it."))
        assertEquals(VoiceReply.Meaning.SEND, VoiceReply.meaning("haan bhej do"))
        assertEquals(VoiceReply.Meaning.SEND, VoiceReply.meaning("OK, send it"))
        assertEquals(VoiceReply.Meaning.SEND_SECOND, VoiceReply.meaning("the second one"))
        assertEquals(VoiceReply.Meaning.SEND_SECOND, VoiceReply.meaning("Dusra wala"))
        assertEquals(VoiceReply.Meaning.CANCEL, VoiceReply.meaning("Don't send"))
        assertEquals(VoiceReply.Meaning.CANCEL, VoiceReply.meaning("rehne do"))
        assertEquals(VoiceReply.Meaning.REPLY, VoiceReply.meaning("I'll be there by eight"))
        assertEquals(VoiceReply.Meaning.REPLY, VoiceReply.meaning("send me the address"))
    }
}
