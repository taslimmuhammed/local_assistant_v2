package com.local.assistant.memory.extract

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.local.assistant.data.db.MessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One thing the nightly pass found in a user message. Validated and routed in code, not trusted. */
data class ExtractedItem(
    val type: Type,
    val sourceMessageId: Long,
    val subject: String? = null,
    val attribute: String? = null,
    val value: String? = null,
    val core: Boolean = false,
    val title: String? = null,
    val whenText: String? = null,
) {
    enum class Type { FACT, TASK, EVENT }
}

/** A user message to read, with the assistant line before it for context. */
data class ExtractionInput(val message: MessageEntity, val previousAssistant: String?)

/**
 * The extraction prompt: what counts, with worked examples that include the messages that must
 * yield nothing — questions, hypotheticals, other people's opinions, moods, sarcasm. The model
 * copies time words rather than computing dates; the router resolves them against the day the
 * message was written.
 */
object ExtractionPrompt {

    val SYSTEM = """
        You read a user's past messages to an assistant and pick out what is worth remembering about the user for later.
        Reply with a JSON array. Each item is one of:
        {"type":"fact","subject":…,"attribute":…,"value":…,"core":false,"source_message_id":N} — a lasting fact the user states about themselves or the people, places and things in their life. subject is "user" or who it is about ("mother", "sister", "dr_rao", "office"); attribute is a short key ("dentist", "birthday", "city", "name"); value is the fact, in the user's words. core is true only for how the user wants replies from now on (attribute "pref.reply_style" or "pref.language"). The people who serve the user — their dentist, doctor, CA, landlord — are facts about the user: subject "user", attribute the role, value the person.
        {"type":"task","title":…,"when":…,"source_message_id":N} — something the user says they have to do at a stated time. when is the user's own time words, copied exactly.
        {"type":"event","title":…,"when":…,"source_message_id":N} — an appointment, meeting or trip at a stated time. when as above. A birthday or anniversary is not an event: it is a fact about the person (attribute "birthday").
        Only what the user states as true. Nothing from questions, hypotheticals, other people's opinions, passing moods, jokes or sarcasm. A request for help is not a fact in itself, but a lasting fact mentioned in one counts. The assistant's lines are context only: never take anything from them.
        If there is nothing, reply [].

        Example messages:
        #11 · user: "my sister Priya lives in Pune"
        #12 · user: "is it going to rain tomorrow?"
        #13 · after assistant: "How was your day?" · user: "ugh, so tired today"
        #14 · user: "if I moved to Dubai would I pay tax there?"
        #15 · user: "I have a dentist appointment on Friday at 5"
        #16 · user: "my boss thinks remote work is a waste of time"
        #17 · user: "keep your answers short from now on"
        #18 · user: "oh great, another Monday, I just love those"
        #19 · user: "Amma ka birthday 12 March ko hai"
        #20 · user: "write me a poem about my cat Milo"
        #21 · user: "my CA is Mr. Iyer, his office is in Jayanagar"
        Example reply:
        [{"type":"fact","subject":"sister","attribute":"name","value":"Priya","core":false,"source_message_id":11},{"type":"fact","subject":"sister","attribute":"city","value":"Pune","core":false,"source_message_id":11},{"type":"event","title":"Dentist appointment","when":"Friday at 5","source_message_id":15},{"type":"fact","subject":"user","attribute":"pref.reply_style","value":"short answers","core":true,"source_message_id":17},{"type":"fact","subject":"mother","attribute":"birthday","value":"12 March","core":false,"source_message_id":19},{"type":"fact","subject":"user","attribute":"cat","value":"Milo","core":false,"source_message_id":20},{"type":"fact","subject":"user","attribute":"ca","value":"Mr. Iyer, office in Jayanagar","core":false,"source_message_id":21}]
    """.trimIndent()

    /** Appended when a reply did not parse. */
    const val RETRY = "Reply with the JSON array only, starting with [ and ending with ]. No other text."

    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** One line per message: "#123 · 21 Sep 2026 · after assistant: "…" · user: "…"". */
    fun render(inputs: List<ExtractionInput>, zone: ZoneId): String = buildString {
        append("Messages:\n")
        for ((message, previous) in inputs) {
            append('#').append(message.id).append(" · ")
            append(DATE.format(Instant.ofEpochMilli(message.createdAt).atZone(zone)))
            previous?.let { append(" · after assistant: ").append(quote(it, CONTEXT_CHARS)) }
            append(" · user: ").append(quote(message.text, MESSAGE_CHARS)).append('\n')
        }
    }

    private fun quote(text: String, limit: Int): String {
        val flat = text.trim().replace(Regex("\\s+"), " ").replace("\"", "'")
        val cut = if (flat.length > limit) flat.take(limit).substringBeforeLast(' ') + " …" else flat
        return "\"$cut\""
    }

    const val CONTEXT_CHARS = 160
    const val MESSAGE_CHARS = 700
}

/** Reads the model's reply leniently: fences, prose around the array, a wrapping object. */
object ExtractionParser {

    /** Null when the reply is not JSON at all (worth one retry); empty when it says "nothing". */
    fun parse(raw: String): List<ExtractedItem>? {
        val element = jsonIn(raw) ?: return null
        val array: JsonArray = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
                ?: JsonArray().apply { add(element) }
            else -> return null
        }
        return array.mapNotNull { (it as? JsonObject)?.let(::item) }
    }

    private fun jsonIn(raw: String): JsonElement? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // Gson is lenient: prose can parse as a bare string, which is not an answer.
        runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonArray || it.isJsonObject }
            ?.let { return it }
        val start = text.indexOfFirst { it == '[' || it == '{' }
        val end = text.indexOfLast { it == ']' || it == '}' }
        if (start < 0 || end <= start) return null
        return runCatching { JsonParser.parseString(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun item(o: JsonObject): ExtractedItem? {
        val type = when (o.string("type")?.lowercase(Locale.ROOT)) {
            "fact" -> ExtractedItem.Type.FACT
            "task", "reminder", "todo" -> ExtractedItem.Type.TASK
            "event", "appointment" -> ExtractedItem.Type.EVENT
            else -> return null
        }
        val source = o.get("source_message_id")?.let { runCatching { it.asDouble.toLong() }.getOrNull() }
            ?: o.string("source_message_id")?.trim('#', ' ')?.toLongOrNull()
            ?: return null
        return ExtractedItem(
            type = type,
            sourceMessageId = source,
            subject = o.string("subject"),
            attribute = o.string("attribute"),
            value = o.string("value"),
            core = o.get("core")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
            title = o.string("title") ?: o.string("value"),
            whenText = o.string("when"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
