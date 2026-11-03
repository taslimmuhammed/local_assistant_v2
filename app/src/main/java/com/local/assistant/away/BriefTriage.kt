package com.local.assistant.away

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model is asked about the brief, and how its answer is read.
 *
 * One request reads a batch of conversations: for each, how much it needs the user, a line on
 * what it's about, and for a person waiting on an answer, two replies to choose from. The replies
 * are suggestions the user confirms, never sent by themselves; still, one that brings in a number
 * the messages don't have (a time, an amount) is dropped, since the model would be making it up.
 */
object BriefTriage {

    /** Conversations per request: about 3,000 tokens of messages at most, in the 8K window. */
    const val BATCH = 10

    /** Lines of each conversation shown to the model, newest kept. */
    const val LINES = 5

    /** Characters of each line shown to the model. */
    private const val LINE_CHARS = 240

    const val OUTPUT_TOKENS = 900

    private const val MAX_REPLY_CHARS = 160
    private const val MAX_GIST_CHARS = 90

    val SYSTEM = """
        You sort the notifications that came in while the user was away from their phone, and draft short replies they can send with one tap.

        For each item, choose its level:
        - need: a person is waiting on the user: a question or request to them, something urgent, a plan that needs their answer.
        - fyi: worth knowing, nothing to do: a delivery, a payment, a change of plan, news from family or friends, an app's reminder.
        - skip: noise: offers, ads, likes, group chatter not addressed to the user, routine automated updates.

        Write "gist": one short line, at most 12 words, on what it is about. It is shown next to the person's name, so leave the name out. For example: "Asks if you're coming tonight at 7".

        For a need item where a person wrote to the user, write "replies": two short replies the user could send, in the first person, warm and natural. Write them in the language named on that item's line ("writes in English", "writes in Hinglish"), whatever language the other items are in. Make them different: one that agrees or answers, one that puts it off or says no. Never promise a time, place, amount or fact that is not already in their messages. Give no replies for apps or for chatter.

        Then write "headline": one sentence, at most 20 words, on what needs the user most, naming people. If nothing needs them, say so briefly.

        Answer with JSON only, in this shape:
        {"headline": "...", "items": [{"id": 1, "level": "need", "gist": "...", "replies": ["...", "..."]}]}
    """.trimIndent()

    /** A firmer second ask, when the first answer didn't parse. */
    const val RETRY = "Answer with the JSON object only: no other words, no code fences."

    /** One conversation as the model sees it: [id] is its number in the request. */
    data class Entry(val id: Int, val item: BriefItem)

    fun render(entries: List<Entry>, zone: ZoneId): String = buildString {
        appendLine("Items:")
        for ((id, item) in entries) {
            val others = item.fromOthers
            val what = when (item.kind) {
                Kind.CALL -> "missed call from ${item.title}"
                Kind.MESSAGE -> if (item.isGroup) "group \"${item.title}\"" else "chat with ${item.title}"
                Kind.OTHER -> "notification"
            }
            val count = if (item.kind == Kind.MESSAGE) " · ${others.size} new" else ""
            val writes = language(item)?.takeIf { item.kind == Kind.MESSAGE }?.let { " · writes in $it" }.orEmpty()
            appendLine("[$id] ${item.appLabel} · $what$count$writes")
            for (line in item.lines.takeLast(LINES)) {
                val who = when {
                    line.fromMe -> "You"
                    item.kind == Kind.OTHER -> line.title
                    else -> line.sender ?: line.title
                }
                appendLine("  $who (${clock(line.postedAt, zone)}): ${line.text.replace('\n', ' ').take(LINE_CHARS)}")
            }
        }
    }

    /** The model's answer, read; null when it isn't the JSON asked for. */
    data class Reading(val headline: String?, val items: Map<Int, ItemReading>)

    data class ItemReading(val level: Level, val gist: String, val replies: List<String>)

    fun parse(answer: String, entries: List<Entry>): Reading? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { JsonParser.parseString(answer.substring(start, end + 1)).asJsonObject }.getOrNull() ?: return null
        val byId = entries.associateBy { it.id }
        val items = HashMap<Int, ItemReading>()
        val listed = runCatching { root.getAsJsonArray("items") }.getOrNull() ?: return null
        for (element in listed) {
            val obj = element as? JsonObject ?: continue
            val id = runCatching { obj.get("id").asInt }.getOrNull() ?: continue
            val entry = byId[id] ?: continue
            val level = when (obj.string("level")?.lowercase(Locale.ROOT)) {
                "need" -> Level.NEED
                "fyi" -> Level.FYI
                "skip" -> Level.SKIP
                else -> continue
            }
            val gist = withoutName(obj.string("gist").orEmpty().let(::oneLine), entry.item.title).take(MAX_GIST_CHARS)
            val english = language(entry.item) == ENGLISH
            val replies = runCatching { obj.getAsJsonArray("replies") }.getOrNull()?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
                .map(::oneLine)
                .filter { it.isNotEmpty() && it.length <= MAX_REPLY_CHARS && grounded(it, entry.item) }
                .filterNot { english && hinglish(it) }
                .distinct()
                .take(2)
            items[id] = ItemReading(level, gist, if (level == Level.NEED && entry.item.kind == Kind.MESSAGE) replies else emptyList())
        }
        if (items.isEmpty()) return null
        return Reading(root.string("headline")?.let(::oneLine)?.takeIf { it.isNotEmpty() }, items)
    }

    /** A reply's numbers all come from the conversation: "by 7" when they said 7, not "by 7:30". */
    internal fun grounded(reply: String, item: BriefItem): Boolean {
        val said = item.lines.joinToString(" ") { it.text }
        val known = NUMBER.findAll(said).map { it.value }.toSet()
        return NUMBER.findAll(reply).all { it.value in known }
    }

    private val NUMBER = Regex("\\d+")

    /**
     * The language a person writes in, from their own messages; named on their line so each
     * conversation's replies are drafted in it. Told only "in the language of their messages",
     * the model gave an English chat Hinglish replies when the chat next to it was in Hinglish
     * (seen on the phone). Null when the messages don't say: emoji only, a missed call.
     */
    internal fun language(item: BriefItem): String? {
        val text = item.fromOthers.joinToString(" ") { it.text }
        val letters = HashMap<Character.UnicodeScript, Int>()
        text.codePoints().filter { Character.isLetter(it) }.forEach { letters.merge(Character.UnicodeScript.of(it), 1, Int::plus) }
        val script = letters.maxByOrNull { it.value }?.key ?: return null
        if (script != Character.UnicodeScript.LATIN) return SCRIPT_LANGUAGES[script]
        return if (hinglish(text)) HINGLISH else ENGLISH
    }

    /** Hindi written in English letters: two of its everyday words, or a quarter of a short line. */
    internal fun hinglish(text: String): Boolean {
        val words = WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        val hits = words.count { it in HINGLISH_WORDS }
        return hits >= 2 || (hits > 0 && hits * 4 >= words.size)
    }

    /**
     * The gist without the name it opens with ("Arjun Mehta needs to know…" → "Needs to know…"):
     * it is shown under the name already, and the model names them however it's asked.
     */
    internal fun withoutName(gist: String, title: String): String {
        val full = title.trim()
        for (name in listOf(full, full.substringBefore(' ')).filter { it.length >= 2 }.distinct()) {
            if (!gist.startsWith(name, ignoreCase = true)) continue
            val next = gist.getOrNull(name.length)
            // "Priyanka asks…" isn't Priya, and "Priya's exam…" needs her name.
            if (next != null && (next.isLetterOrDigit() || next == '\'' || next == '’')) continue
            val rest = gist.substring(name.length).trimStart(' ', ':', ',', '-', '–').removePrefix("is ")
            if (rest.split(' ').size < 2) return gist
            return rest.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        return gist
    }

    private const val ENGLISH = "English"
    private const val HINGLISH = "Hinglish"

    private val SCRIPT_LANGUAGES = mapOf(
        Character.UnicodeScript.DEVANAGARI to "Hindi",
        Character.UnicodeScript.MALAYALAM to "Malayalam",
        Character.UnicodeScript.TAMIL to "Tamil",
        Character.UnicodeScript.TELUGU to "Telugu",
        Character.UnicodeScript.KANNADA to "Kannada",
        Character.UnicodeScript.BENGALI to "Bengali",
        Character.UnicodeScript.GUJARATI to "Gujarati",
        Character.UnicodeScript.GURMUKHI to "Punjabi",
    )

    private val WORD = Regex("[a-z]+")

    /** Everyday Hindi words as typed in English letters; none of them an English word ("main", "mat", "mere", "the" left out). */
    private val HINGLISH_WORDS = setOf(
        "hai", "hain", "hu", "hoon", "ho", "tha", "thi", "raha", "rahi", "rahe", "gaya", "gayi", "gaye",
        "kya", "kyaa", "kab", "kahan", "kidhar", "kaise", "kaisa", "kaisi", "kitna", "kitne", "kaun", "kyun", "kyu", "kyon",
        "nahi", "nahin", "nhi", "haan", "na", "ji", "toh", "bhi", "abhi", "aur", "lekin", "phir",
        "kal", "aaj", "parso", "subah", "shaam", "raat", "jaldi",
        "ke", "ki", "ka", "ko", "se", "ne", "mein", "liye", "wala", "wali", "wale",
        "mujhe", "mera", "meri", "tera", "teri", "tere", "tum", "tumhe", "tumhara", "aap", "aapka", "apna", "humko",
        "kar", "karo", "karna", "karke", "karenge", "kiya", "diya", "dena", "dedo", "bhej", "bhejo", "bhejna", "bhejdo",
        "bata", "batao", "bol", "bolo", "dekh", "dekho", "suno", "milte", "chal", "chalo", "aa", "ja", "jaa", "jaana", "aana",
        "payega", "paunga", "sakta", "sakti", "sakte", "chahiye",
        "bhai", "yaar", "yar", "accha", "acha", "achha", "theek", "thik", "sahi", "bas", "matlab", "pata", "zaroor",
        "kuch", "koi", "sab", "kaam", "ghar", "khana", "paise", "paisa",
    )

    private fun JsonObject.string(name: String): String? = runCatching { get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim().trim('"')

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    private fun clock(at: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(at).atZone(zone)).lowercase(Locale.ROOT)
}
