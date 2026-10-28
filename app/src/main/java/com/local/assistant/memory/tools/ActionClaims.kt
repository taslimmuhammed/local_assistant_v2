package com.local.assistant.memory.tools

/**
 * Spots a reply that says it has done something only a tool can do: sent, called, booked, set an
 * alarm. With no tool run behind it, that is a false promise the user acts on. "I've sent the
 * email to Hitesh" (10 Oct 2026) came with no send_message call at all.
 *
 * English, and kept to what can only mean acting on the world: "I've written a poem" and "I've
 * updated the code" are not claims. Verbs that also describe writing or code (set, added, turned,
 * opened …) count only next to something a tool acts on: an alarm, a reminder, the flashlight,
 * an app.
 */
object ActionClaims {

    /** Whether [text] says something was done. */
    fun claims(text: CharSequence): Boolean = CLAIMS.any { it.containsMatchIn(text) }

    /**
     * Whether [text] says a message was written without showing it: "I've drafted the email for
     * you. Would you like me to send it now?" (the power-button panel, 27 Oct 2026), with no tool
     * run, means no draft exists anywhere. A draft the reply goes on to show ("…for you:", in
     * quotes, or [DRAFT_MAX] characters and more of it) is writing, not a claim.
     */
    fun unshownDraft(text: CharSequence): Boolean {
        if (text.length >= DRAFT_MAX) return false
        val said = DRAFT.find(text) ?: return false
        return !SHOWN.containsMatchIn(text.subSequence(said.range.last + 1, text.length))
    }

    /**
     * Where the opening of a reply ends, the part a claim would be in: its first sentence, or
     * more of them while that is still short ("Sure! I've sent it."), and never more than
     * [OPENING_MAX] characters. Null while the reply may still be inside it.
     */
    fun openingEnd(text: CharSequence): Int? {
        SENTENCE_END.findAll(text).firstOrNull { it.range.last + 1 >= OPENING_MIN }?.let { return it.range.last + 1 }
        return if (text.length >= OPENING_MAX) text.length else null
    }

    private const val OPENING_MIN = 30
    private const val OPENING_MAX = 200
    private const val DRAFT_MAX = 200

    /** What follows a message that is shown: a colon, or the text in quotes. */
    private val SHOWN = Regex("[:\"“«]")

    /** A sentence ends at . ! or ? once a space follows (not in "infosys.com"), or a line ends. */
    private val SENTENCE_END = Regex("[.!?](?=\\s)|\\n")

    /** "I", "I've", "I have just" …: the model speaking of what it did. */
    private const val I_DID = "\\bI(?:['’]ve|\\s+have|\\s+had)?(?:\\s+(?:just|now|already|also|successfully|(?:gone|went)\\s+ahead\\s+and))*\\s+"

    /** Between words of one sentence. */
    private const val GAP = "[^\\w.!?\\n]+"

    /** [verbs] with one of [objects] within [words] words after it. */
    private fun near(verbs: String, objects: String, words: Int = 3) =
        "$I_DID(?:$verbs)\\b(?:$GAP[\\w'’-]+){0,$words}?$GAP(?:$objects)\\b"

    private const val SCHEDULED = "alarms?|timers?|reminders?|events?|appointments?|meetings?|calendar|tasks?|to-?dos?"
    private const val PHONE_STATE = "silent|vibrate|do not disturb|dnd|airplane mode|flight mode"
    private const val SWITCHABLE = "flashlight|torch|wi-?fi|bluetooth|mobile data|hotspot|volume|ringer|$PHONE_STATE"
    private const val APPS = "app|youtube|maps|spotify|whatsapp|gmail|chrome|browser|settings|camera|music|songs?|playlist|videos?|navigation|directions|dialer|stopwatch|clock"

    private val CLAIMS = listOf(
        // Only a tool could have done these, whatever they were done to.
        "$I_DID(?:sent|e-?mailed|texted|messaged|whatsapped|forwarded|dial(?:l)?ed|booked|posted|uploaded|published|reminded|notified|contacted" +
            "|paid(?!\\s+(?:close\\s+)?attention)|cancell?ed(?!\\s+out\\b)" +
            "|called(?!\\s+(?:it|this|that)\\b)(?!\\s+the\\s+(?:function|method|variable|class|file|api)\\b))\\b",
        "\\bI(?:['’]m|\\s+am)\\s+(?:now\\s+)?(?:sending|calling|dialing|dialling|booking|ordering|paying|posting|emailing|texting|messaging|forwarding|transferring)\\b",
        // The verbs that also describe writing and code, next to what a tool acts on.
        near("set|added|created|scheduled|rescheduled|saved|put|made|booked|moved|changed|updated|deleted|removed|cleared", SCHEDULED, words = 4),
        near("turned|switched", "on|off|up|down|$SWITCHABLE"),
        near("put|set|changed|muted|silenced|enabled|disabled|activated|deactivated", "phone|$SWITCHABLE", words = 4),
        near("opened|launched|started|played|pulled up", APPS),
        near("saved|stored|noted down|added", "memory|that|this|preference|number|address|details|fact|contacts?|shopping list"),
        near("marked", "done|complete|completed|finished"),
        near("ordered|reserved|placed", "orders?|pizza|food|meal|groceries|cab|taxi|uber|ola|ride|table|seats?|tickets?|room"),
        near("transferred", "money|rupees|rs|amount|funds|payment|\\d+"),
        near("deleted|removed|erased|cleared|forgotten|forgot", "facts?|memory|memories|chats?|conversation|history|messages?|emails?|photos?|pictures?|contacts?|that|this"),
        // In the passive, and the bare "Done!" or "Email sent."
        "\\b(?:has|have)\\s+been\\s+(?:successfully\\s+)?(?:sent|e-?mailed|texted|forwarded|booked|paid|posted|scheduled|cancell?ed|delivered|placed)\\b",
        "\\b(?:$SCHEDULED)\\s+(?:has|have|is|are)\\s+(?:been\\s+|now\\s+)?(?:set|added|saved|created|scheduled|updated|deleted|cancell?ed)\\b",
        "(?:^|[.!?]\\s+)(?:your\\s+)?(?:email|e-mail|mail|message|text|sms|whatsapp)\\s+(?:was\\s+|is\\s+|has\\s+been\\s+)?(?:successfully\\s+)?sent\\b",
        "^\\s*(?:sent|done|all done|all set)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val DRAFT = Regex(
        near("drafted|written|wrote|prepared|composed|typed out|put together", "e-?mails?|mails?|messages?|texts?|whatsapps?|sms|replies|reply") +
            "|\\b(?:e-?mail|mail|message|text|reply|draft)\\s+is\\s+(?:now\\s+)?(?:ready|drafted|written|prepared)\\b",
        RegexOption.IGNORE_CASE,
    )
}
