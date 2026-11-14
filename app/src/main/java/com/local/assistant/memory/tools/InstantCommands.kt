package com.local.assistant.memory.tools

import com.local.assistant.llm.ToolCall
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The few requests the app can carry out without asking the model: an alarm at a time, a timer,
 * the flashlight, the ringer, Do Not Disturb, the volume, Wi-Fi and Bluetooth. For these the model
 * only ever writes the one call and a line saying it's done, which took 2–3 s on the phone before
 * the first word (13 Nov 2026); done here it takes the tool's own time.
 *
 * Only a message that is nothing but such a request matches, whole: "set an alarm for 6:30
 * tomorrow", "10 minute timer", "turn on the flashlight". Anything more — a name for the alarm,
 * a repeat, a second request, a question, "and" — goes to the model as before, so this never
 * decides what a message means, only spares the model the plainest ones. The call is the one the
 * model would make, run by [ToolExecutor] like any other: the same checks, chip and undo. If it
 * fails (a time already passed, no clock app) the model is asked after all.
 */
object InstantCommands {

    /** The call [text] asks for, or null when the model should read it. */
    fun match(text: String): ToolCall? {
        val said = plain(text) ?: return null
        alarm(said)?.let { return ToolCall(ToolCatalog.SET_ALARM, mapOf("when" to it)) }
        timer(said)?.let { return ToolCall(ToolCatalog.SET_TIMER, mapOf("duration" to it)) }
        return setting(said)
    }

    /**
     * What the app says once [call] succeeded with [result] (its JSON as the model would read it)
     * and [chip], as the model would have put it, in one short line. Null when there's more to
     * say than that (a note in the result: silent fell back to vibrate); the model is asked then.
     */
    fun reply(call: ToolCall, result: Map<String, Any?>, chip: MemoryChip?, now: ZonedDateTime): String? {
        if (result["ok"] != true) return null
        // The panels' note is always the same and said here; any other is for the model to explain.
        val opened = (result["opened"] as? String)?.removePrefix("the ")?.removeSuffix(" settings")
        if (result["note"] != null && opened == null) return null
        return when (call.name) {
            ToolCatalog.SET_ALARM -> chip?.at?.let { at -> "Alarm set for ${spoken(Instant.ofEpochMilli(at).atZone(now.zone), now)}." }
            ToolCatalog.SET_TIMER -> (result["timer"] as? String)?.let { "Timer set for $it." }
            ToolCatalog.PHONE_SETTING -> when {
                // Wi-Fi, Bluetooth: Android lets only the user switch them, so their panel is open.
                opened != null -> "Android doesn't let apps switch $opened themselves, so I've opened its settings for you."
                result["ringer"] is String -> "Ringer set to ${result["ringer"]}."
                chip != null -> chip.label + "."
                else -> null
            }
            else -> null
        }
    }

    /** "6:30 AM", "6:30 AM tomorrow", "6:30 AM on Sat 14 Nov". */
    private fun spoken(at: ZonedDateTime, now: ZonedDateTime): String {
        val time = CLOCK.format(at)
        return when (at.toLocalDate()) {
            now.toLocalDate() -> "$time today"
            now.toLocalDate().plusDays(1) -> "$time tomorrow"
            else -> "$time on ${DATE.format(at)}"
        }
    }

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    // ---- Reading the message ----

    /**
     * Lower case, without a wake word, politeness or a full stop; null for more than one line, and
     * for a question unless it's a request put as one ("can you set an alarm for 6?").
     */
    private fun plain(text: String): String? {
        if ('\n' in text.trim()) return null
        var said = text.trim().lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ")
        val question = said.endsWith("?")
        var request = false
        while (true) {
            request = request || ASKING.containsMatchIn(said)
            val shorter = said.replace(LEAD_IN, "").replace(TRAIL, "").trim(' ', ',', '.', '!', '?')
            if (shorter == said) break
            said = shorter
        }
        if (question && !request) return null
        return said.takeIf { it.isNotEmpty() }
    }

    private val LEAD_IN = Regex("^(hey luna|luna|ok|okay|please|pls|kindly|just|quickly|(can|could|would|will) (you|u)( please)?)\\b")
    private val ASKING = Regex("^(can|could|would|will) (you|u)\\b")
    private val TRAIL = Regex("\\b(please|pls|for me|now|thanks|thank you)$")

    /** "6", "6:30", "6.30", "6 am", "6:30pm", "6:30 a.m.". */
    private const val TIME = "\\d{1,2}(?:[:.]\\d{2})? ?(?:am|pm|a\\.m\\.?|p\\.m\\.?)?"

    /** Days and parts of the day an alarm's time may carry. */
    private const val DAY = "(?: (?:today|tomorrow|tonight|tomorrow morning|in the morning|in the evening|this evening|this afternoon))?"

    private val ALARMS = listOf(
        Regex("^(?:set|put|create|make|add|keep) (?:me )?(?:an |a |the |my )?alarm (?:for |at )?($TIME$DAY)$"),
        Regex("^alarm (?:for |at )?($TIME$DAY)$"),
        Regex("^wake me (?:up )?(?:at |by )($TIME$DAY)$"),
        Regex("^(?:set|put|create|make|add|keep) (?:me )?(?:an |a |the |my )?alarm in (\\d{1,3} (?:minutes?|mins?|hours?|hrs?))$"),
    )

    private val RELATIVE = Regex("(minutes?|mins?|hours?|hrs?)$")

    /** The time words as said, for set_alarm's `when`; a time of day must have a sensible hour and minute. */
    private fun alarm(said: String): String? {
        val words = ALARMS.firstNotNullOfOrNull { it.find(said)?.groupValues?.get(1) } ?: return null
        // "in 20 minutes": a length, which the resolver reads; anything else is a time of day.
        if (RELATIVE.containsMatchIn(words)) return words
        val clock = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?").find(words) ?: return null
        val hour = clock.groupValues[1].toInt()
        val minute = clock.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        return words.takeIf { hour in 0..23 && minute in 0..59 }
    }

    /** "10 minutes", "1 hour 30 minutes", "an hour and a half", "90 sec", "half an hour". */
    private const val LENGTH = "(?:(?:\\d{1,3}|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|forty five|half an?|one and a half) ?-?(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|hour)(?: (?:and )?(?:\\d{1,3} (?:seconds?|secs?|minutes?|mins?)|a half))?|half an hour)"

    private val TIMERS = listOf(
        Regex("^(?:set|start|put|create|make|keep) (?:me )?(?:an |a |the )?timer (?:for )?($LENGTH)$"),
        Regex("^timer (?:for )?($LENGTH)$"),
        Regex("^(?:(?:set|start|put|create|make|keep) (?:me )?)?(?:an |a )?($LENGTH) timer$"),
    )

    private fun timer(said: String): String? {
        val words = TIMERS.firstNotNullOfOrNull { it.find(said)?.groupValues?.get(1) } ?: return null
        return words.replace('-', ' ').takeIf { DurationParser.seconds(it) != null }
    }

    private val SWITCHED = mapOf(
        "flashlight" to "flashlight", "flash light" to "flashlight", "torch" to "flashlight",
        "do not disturb" to "do_not_disturb", "dnd" to "do_not_disturb",
        "wifi" to "wifi", "wi-fi" to "wifi", "wi fi" to "wifi",
        "bluetooth" to "bluetooth",
    )
    private val THING = SWITCHED.keys.joinToString("|") { Regex.escape(it) }

    private val SETTINGS = listOf(
        // "turn on the flashlight", "switch off wifi"
        Regex("^(?:turn|switch|put) (on|off) (?:the |my )?($THING)$") to { m: MatchResult -> m.groupValues[2] to m.groupValues[1] },
        // "turn the flashlight on", "flashlight off"
        Regex("^(?:(?:turn|switch|put) )?(?:the |my )?($THING) (on|off)$") to { m: MatchResult -> m.groupValues[1] to m.groupValues[2] },
    )

    private val RINGER = listOf(
        // "put my phone on silent", "set the phone to vibrate", "phone on silent"
        Regex("^(?:(?:put|set|keep|turn|switch) )?(?:my |the )?(?:phone|ringer|mobile) (?:on|to|in) (silent|vibrate|normal)(?: mode)?$"),
        // "turn on silent mode", "silent mode on" (off: back to normal)
        Regex("^(?:turn|switch|put) (on|off) (silent|vibrate)(?: mode)?$"),
        Regex("^(silent|vibrate)(?: mode)? (on|off)$"),
        Regex("^(?:mute|silence) (?:my |the )?(phone|ringer)$"),
    )

    private val VOLUME = Regex("^(?:(?:turn|put|set) )?(?:the )?volume (up|down)$|^(?:increase|raise) (?:the )?volume$|^(?:decrease|lower|reduce) (?:the )?volume$")

    private fun setting(said: String): ToolCall? {
        for ((pattern, read) in SETTINGS) {
            val (thing, onOff) = pattern.find(said)?.let(read) ?: continue
            val setting = SWITCHED[thing] ?: continue
            return phoneSetting(setting, onOff)
        }
        RINGER[0].find(said)?.let { return phoneSetting("ringer", it.groupValues[1]) }
        RINGER[1].find(said)?.let { return phoneSetting("ringer", if (it.groupValues[1] == "on") it.groupValues[2] else "normal") }
        RINGER[2].find(said)?.let { return phoneSetting("ringer", if (it.groupValues[2] == "on") it.groupValues[1] else "normal") }
        RINGER[3].find(said)?.let { return phoneSetting("ringer", "silent") }
        VOLUME.find(said)?.let { match ->
            val up = match.groupValues[1] == "up" || said.startsWith("increase") || said.startsWith("raise")
            return phoneSetting("volume", if (up) "up" else "down")
        }
        return null
    }

    private fun phoneSetting(setting: String, value: String) = ToolCall(ToolCatalog.PHONE_SETTING, mapOf("setting" to setting, "value" to value))
}
