package com.local.assistant.inbox

import com.local.assistant.away.NotificationLines
import com.local.assistant.away.SeenNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxSearchTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun message(text: String, sender: String?, at: Long, app: String = "WhatsApp", pkg: String = "com.whatsapp", title: String = sender ?: "Rahul", source: InboxMessage.Source = InboxMessage.Source.NOTIFICATION) =
        InboxMessage(source = source.name, dedupeKey = "$text$at", packageName = pkg, appLabel = app, conversationKey = title, title = title, sender = sender, fromMe = sender == null, text = text, at = at, keptAt = at)

    @Test
    fun `the words asked about, not the asking`() {
        assertEquals(listOf("trip"), InboxSearch.terms("what did Rahul say about the trip", without = "Rahul"))
        assertEquals(listOf("rent", "agreement"), InboxSearch.terms("any message about the rent agreement?"))
        assertEquals("rent* OR agreement*", InboxSearch.match(listOf("rent", "agreement")))
        // Nothing an FTS query could choke on gets through.
        assertEquals(listOf("goa", "plan"), InboxSearch.terms("\"goa\" plan* (OR) -"))
    }

    @Test
    fun `most of the words first, then the newest`() {
        val rows = listOf(
            message("trip is on", "Rahul", now - 2 * day),
            message("the Goa trip plan is ready", "Rahul", now - 5 * day),
            message("plan for tomorrow?", "Rahul", now - hour),
        )
        val ranked = InboxSearch.rank(listOf("goa", "trip"), rows, now)
        assertEquals("the Goa trip plan is ready", ranked.first().text)
        assertEquals("trip is on", ranked[1].text)
    }

    @Test
    fun `by sender's name or first name, and by app`() {
        val rows = listOf(
            message("hi", "Rahul Menon", now),
            message("hello", "Anjali", now),
            message("in the group", "Rahul", now, title = "Family"),
            message("bill due", "AD-POWERB", now, app = "SMS", pkg = SmsSync.SMS_PACKAGE, source = InboxMessage.Source.SMS),
        )
        assertEquals(listOf("hi", "in the group"), InboxSearch.named(rows, "rahul").map { it.text })
        assertEquals(listOf("in the group"), InboxSearch.named(rows, "family").map { it.text })
        assertEquals(listOf("bill due"), InboxSearch.inApp(rows, "sms").map { it.text })
        assertEquals(3, InboxSearch.inApp(rows, "WhatsApp").size)
        assertEquals("%Rahul%", InboxSearch.likePattern(" Rahul "))
    }

    private fun seen(pkg: String, title: String, text: String, category: String? = "msg", channel: String? = null, messages: List<SeenNotification.Message> = emptyList()) = SeenNotification(
        packageName = pkg, appLabel = pkg.substringAfterLast('.'), key = "k", id = 1, tag = null, postedAt = now, category = category,
        ongoing = false, groupSummary = false, importance = 3, title = title, text = text, bigText = null, messages = messages, channel = channel,
    )

    private fun kept(seen: SeenNotification, smsApp: String? = "com.google.android.apps.messaging", readsSmsInbox: Boolean = false) =
        Inbox.fromNotification(seen, NotificationLines.read(seen, "com.taslim.localassistant.dev", emptySet(), now), emptySet(), smsApp, readsSmsInbox, now)

    @Test
    fun `a chat's messages are kept, the user's own as theirs`() {
        val rows = kept(
            seen(
                "com.whatsapp", "Rahul", "2 messages",
                messages = listOf(SeenNotification.Message("Rahul", "Goa trip is on for Friday", now - 60_000), SeenNotification.Message(null, "great!", now)),
            ),
        )
        assertEquals(listOf("Goa trip is on for Friday", "great!"), rows.map { it.text })
        assertEquals("Rahul", rows.first().sender)
        assertTrue(rows.last().fromMe)
        assertEquals(null, rows.last().sender)
    }

    @Test
    fun `one-time codes, ads and missed calls aren't kept`() {
        assertTrue(kept(seen("com.google.android.apps.messaging", "AX-BANKX", "Your OTP is 448812. Do not share it.")).isEmpty())
        assertTrue(kept(seen("com.google.android.apps.messaging", "VM-SHOPZZ", "Mega sale! Flat 50% off, use code X50. Shop now")).isEmpty())
        assertTrue(kept(seen("com.whatsapp", "Offers", "Hello", channel = "Promotions")).isEmpty())
        assertTrue(kept(seen("com.android.dialer", "Missed call", "Amma", category = "missed_call")).isEmpty())
    }

    @Test
    fun `the SMS app's notifications aren't kept while the SMS inbox is read`() {
        val sms = seen("com.google.android.apps.messaging", "Landlord", "Rent is due on the 5th")
        assertEquals(1, kept(sms).size)
        assertTrue(kept(sms, readsSmsInbox = true).isEmpty())
    }

    @Test
    fun `emails are kept, other apps' news isn't`() {
        assertEquals(1, kept(seen("com.google.android.gm", "Priya", "Minutes from today's meeting", category = "email")).size)
        assertTrue(kept(seen("com.swiggy", "Order update", "Your food is being prepared", category = null)).isEmpty())
    }
}
