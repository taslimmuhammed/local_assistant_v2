package com.local.assistant.memory.tools

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The everyday tools: calling, messaging, timers, opening apps, phone settings and arithmetic.
 *
 * Nothing here calls or sends on its own. `call` opens the dialer with the number and
 * `send_message` opens the messaging app with the text written; the user presses the button. A
 * 4B model does misroute now and then, and a call placed to the wrong person cannot be undone.
 *
 * People are found memory first — a number saved for "amma", or the name the user's dentist goes
 * by — then in the phone's contacts, which are asked for the first time they are needed.
 */
class DeviceTools(
    private val phone: PhoneActions,
    private val contacts: ContactBook,
    private val people: PeopleMemory,
    private val alarms: SystemAlarms,
    private val now: () -> ZonedDateTime,
) {

    internal suspend fun call(args: Args): ActionResult {
        val who = args.required("who")
        val person = find(who, Need.PHONE)
        check(phone.dial(person.address), "the dialer")
        return ActionResult(
            ok("dialer" to "open with ${person.name} (${person.address}); the user taps call"),
            MemoryChip(MemoryChip.Kind.CALL, "Call", "${person.name} · ${person.address}", note = "Tap call in the dialer"),
        )
    }

    internal suspend fun sendMessage(args: Args): ActionResult {
        val to = args.required("to")
        val text = args.text("text")
        val app = when (args.text("app")?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z]"), "")) {
            "whatsapp", "wa", "whatsup" -> MessageApp.WHATSAPP
            "email", "mail", "gmail" -> MessageApp.EMAIL
            "sms", "text", "message", "messages", null -> if ('@' in to) MessageApp.EMAIL else MessageApp.SMS
            else -> throw ToolError("app must be sms, whatsapp or email.")
        }
        val person = find(to, if (app == MessageApp.EMAIL) Need.EMAIL else Need.PHONE)
        val appName = APP_NAMES.getValue(app)
        val handoff = phone.compose(app, person.address, text)
        if (handoff == Handoff.NO_APP) {
            throw ToolError("$appName isn't installed. Offer ${if (app == MessageApp.WHATSAPP) "an SMS" else "another way"} instead.")
        }
        check(handoff, appName)
        return ActionResult(
            ok("ready" to "$appName to ${person.name} with the message written; the user taps send"),
            MemoryChip(MemoryChip.Kind.MESSAGE, appName, person.name + (text?.let { ": ${it.take(CHIP_TEXT)}" } ?: ""), note = "Tap send in $appName"),
        )
    }

    internal fun setTimer(args: Args): ActionResult {
        val words = args.required("duration")
        val seconds = DurationParser.seconds(words) ?: throw ToolError("I couldn't understand '$words' as a length of time. Ask how long.")
        if (seconds > MAX_TIMER_SECONDS) throw ToolError("Timers go up to 24 hours. Offer a reminder or an alarm instead.")
        val label = args.text("label")?.trim()?.trimEnd('.')?.replaceFirstChar { it.titlecase(Locale.ROOT) }
            ?.takeUnless { it.equals("timer", ignoreCase = true) }
        if (!alarms.available()) throw ToolError("This phone's clock app doesn't take timers from other apps.")
        if (!alarms.setTimer(seconds, label)) throw ToolError("The clock app couldn't be reached. Ask the user to try again with the app open.")
        val length = DurationParser.describe(seconds)
        return ActionResult(
            ok("timer" to length, "ends" to TIME.format(now().plusSeconds(seconds.toLong()))),
            MemoryChip(MemoryChip.Kind.TIMER, "Timer", listOfNotNull(length, label).joinToString(" · ")),
        )
    }

    internal fun openApp(args: Args): ActionResult {
        val app = args.required("app")
        val query = args.text("query")
        val found = when (val result = AppMatcher.resolve(app, query, phone.installedApps())) {
            is AppMatcher.Result.Found -> result
            is AppMatcher.Result.Several -> throw ToolError("Several apps match '$app': ${result.labels.joinToString(", ")}. Ask which one.")
            AppMatcher.Result.None -> throw ToolError("There's no app called '$app' on this phone.")
        }
        val target = found.target
        val what = describe(target)
        check(phone.open(target), what)
        return ActionResult(
            ok("opened" to what, "note" to found.note),
            MemoryChip(MemoryChip.Kind.APP, "Opened", what),
        )
    }

    internal fun phoneSetting(args: Args): ActionResult {
        val setting = args.required("setting").lowercase(Locale.ROOT).replace(Regex("[^a-z]+"), "_").trim('_')
        val value = args.text("value")?.lowercase(Locale.ROOT)?.trim()
        return when (setting) {
            "flashlight", "torch", "flash", "light" -> {
                val on = onOff(value) ?: true
                check(phone.torch(on), "the flashlight")
                setting("flashlight" to if (on) "on" else "off", chip = "Flashlight ${if (on) "on" else "off"}")
            }
            "ringer", "ringer_mode", "sound", "sound_mode", "silent", "silent_mode", "vibrate", "vibration", "mute", "profile" ->
                ringer(value, setting)
            "volume", "media_volume", "music_volume" -> volume(value)
            "do_not_disturb", "dnd", "focus", "focus_mode" -> {
                val on = onOff(value) ?: true
                when (phone.doNotDisturb(on)) {
                    Handoff.DONE -> setting("do_not_disturb" to if (on) "on" else "off", chip = "Do Not Disturb ${if (on) "on" else "off"}")
                    Handoff.NEEDS_ACCESS -> throw ToolError("The phone needs the user to allow Do Not Disturb access for this app first; that settings screen is now open. Tell the user to allow it and ask again.")
                    else -> throw ToolError("Do Not Disturb couldn't be changed.")
                }
            }
            "brightness", "screen_brightness" -> throw ToolError("Brightness can't be changed from here. Tell the user to use the quick settings slider.")
            else -> {
                val panel = PANELS[setting] ?: throw ToolError(
                    "Unknown setting '$setting'. Use flashlight, ringer, volume, do_not_disturb, wifi, bluetooth, mobile_data, airplane_mode, hotspot or location.",
                )
                check(phone.openPanel(panel), "the ${panel.label} settings")
                ActionResult(
                    ok("opened" to "the ${panel.label} settings", "note" to "Android doesn't let apps switch ${panel.label} themselves; the user switches it there"),
                    MemoryChip(MemoryChip.Kind.SETTING, "Opened", "${panel.label.replaceFirstChar { it.titlecase(Locale.ROOT) }} settings"),
                )
            }
        }
    }

    internal fun calculate(args: Args): ActionResult {
        val expression = args.required("expression")
        val result = try {
            Calculator.run(expression)
        } catch (e: Calculator.Error) {
            throw ToolError(e.message.orEmpty())
        }
        return ActionResult(ok("result" to result))
    }

    // ---- Settings ----

    private fun ringer(value: String?, setting: String): ActionResult {
        // "silent mode on", "vibrate off": when the setting names a mode, on and off are about it.
        val named = when (setting) {
            "silent", "silent_mode", "mute" -> RingerMode.SILENT
            "vibrate", "vibration" -> RingerMode.VIBRATE
            else -> null
        }
        val mode = when (value) {
            "silent", "mute", "muted" -> RingerMode.SILENT
            "vibrate", "vibration" -> RingerMode.VIBRATE
            "normal", "ring", "sound", "loud", "general", "unmute" -> RingerMode.NORMAL
            null, "on", "true", "yes" -> named ?: if (value == null) throw ToolError("Should the ringer be silent, vibrate or normal?") else RingerMode.NORMAL
            "off", "false", "no" -> if (named != null) RingerMode.NORMAL else RingerMode.SILENT
            else -> throw ToolError("The ringer can be silent, vibrate or normal.")
        }
        return when (phone.ringer(mode)) {
            Handoff.DONE -> setting("ringer" to mode.name.lowercase(Locale.ROOT), chip = "Ringer: ${mode.name.lowercase(Locale.ROOT)}")
            // Silent can switch on Do Not Disturb, which needs the user's say-so; vibrate is the nearest.
            Handoff.NEEDS_ACCESS -> if (phone.ringer(RingerMode.VIBRATE) == Handoff.DONE) {
                setting(
                    "ringer" to "vibrate",
                    "note" to "silent needs Do Not Disturb access, which the user can allow for this app in Settings; set to vibrate for now",
                    chip = "Ringer: vibrate",
                )
            } else {
                throw ToolError("The ringer couldn't be changed.")
            }
            else -> throw ToolError("The ringer couldn't be changed.")
        }
    }

    private fun volume(value: String?): ActionResult {
        val change = when {
            value == null || value in setOf("up", "louder", "increase", "raise", "higher", "badhao") -> VolumeChange.Up
            value in setOf("down", "lower", "decrease", "softer", "quieter", "reduce", "kam") -> VolumeChange.Down
            value in setOf("mute", "off", "silent", "0", "zero") -> VolumeChange.Mute
            value in setOf("unmute", "on") -> VolumeChange.Unmute
            value in setOf("max", "maximum", "full", "100", "highest") -> VolumeChange.To(100)
            value in setOf("min", "minimum", "lowest") -> VolumeChange.To(1)
            else -> value.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 0..100 }?.let(VolumeChange::To)
                ?: throw ToolError("Volume can be up, down, mute, max or a percent.")
        }
        val level = phone.volume(change) ?: throw ToolError("The volume couldn't be changed.")
        return setting("volume" to "$level%", chip = "Volume $level%")
    }

    private fun setting(vararg fields: Pair<String, Any?>, chip: String) =
        ActionResult(ok(*fields), MemoryChip(MemoryChip.Kind.SETTING, chip, ""))

    private fun onOff(value: String?): Boolean? = when (value) {
        null -> null
        "on", "true", "yes", "enable", "enabled", "start", "chalu", "jalao" -> true
        "off", "false", "no", "disable", "disabled", "stop", "band", "bujhao" -> false
        else -> throw ToolError("value should be on or off.")
    }

    // ---- People ----

    private enum class Need { PHONE, EMAIL }

    private data class Person(val name: String, val address: String)

    /**
     * A number or email for [who]: typed out, saved in memory, or found in the contacts. Asking
     * which one when several people fit beats guessing.
     */
    private suspend fun find(who: String, need: Need): Person {
        val spoken = who.trim()
        if (need == Need.PHONE && looksLikeNumber(spoken)) return Person(spoken, spoken)
        if (need == Need.EMAIL && EMAIL.matches(spoken)) return Person(spoken, spoken)

        val hints = people.hints(spoken)
        val display = spoken.replace(POSSESSIVE, "").replaceFirstChar { it.titlecase(Locale.ROOT) }
        when (need) {
            Need.PHONE -> hints.phone?.let { return Person(display, it) }
            Need.EMAIL -> hints.email?.let { return Person(display, it) }
        }

        val everyone = contacts.all()
            ?: throw ToolError("The user hasn't allowed this app to read contacts. Ask them for ${if (need == Need.PHONE) "the number" else "the email address"}, or to allow Contacts access.")
        val reachable = everyone.filter { if (need == Need.PHONE) it.phones.isNotEmpty() else it.emails.isNotEmpty() }
        return when (val match = ContactMatcher.find(listOf(spoken) + hints.names, reachable)) {
            is ContactMatcher.Match.One -> {
                val address = if (need == Need.PHONE) ContactMatcher.number(match.contact)!! else match.contact.emails.first()
                Person(match.contact.name, address)
            }
            is ContactMatcher.Match.Several -> throw ToolError("Several contacts match '$spoken': ${match.labels.joinToString(", ")}. Ask which one.")
            ContactMatcher.Match.None -> throw ToolError(
                "No contact matches '$spoken'. Ask the user for ${if (need == Need.PHONE) "the number" else "the email address"}.",
            )
        }
    }

    private fun looksLikeNumber(text: String): Boolean =
        text.none { it.isLetter() } && text.count { it.isDigit() } >= MIN_DIGITS

    private fun check(handoff: Handoff, what: String) = when (handoff) {
        Handoff.DONE -> Unit
        Handoff.IN_BACKGROUND -> throw ToolError("The app went to the background, so it couldn't open $what. Ask the user to try again with the app open.")
        Handoff.NO_APP -> throw ToolError("Nothing on this phone can open $what.")
        Handoff.NEEDS_ACCESS -> throw ToolError("The phone needs the user to allow access for $what first.")
        Handoff.FAILED -> throw ToolError("$what couldn't be opened.".replaceFirstChar { it.titlecase(Locale.ROOT) })
    }

    private fun describe(target: AppTarget): String = when (target) {
        is AppTarget.Launch -> target.app.label
        AppTarget.Camera -> "the camera"
        AppTarget.Settings -> "Settings"
        is AppTarget.Directions -> "directions to ${target.place}"
        is AppTarget.Play -> "“${target.query}”" + (target.app?.let { " in ${it.label}" } ?: "")
        is AppTarget.Videos -> "“${target.query}” on YouTube"
        is AppTarget.WebSearch -> "a web search for “${target.query}”"
        is AppTarget.StoreSearch -> "“${target.query}” in the Play Store"
    }

    private fun ok(vararg fields: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("ok" to true).apply { fields.forEach { (k, v) -> if (v != null) put(k, v) } }

    companion object {
        /** The clock app's own limit. */
        const val MAX_TIMER_SECONDS = 24 * 60 * 60
        private const val MIN_DIGITS = 3
        private const val CHIP_TEXT = 60
        private val POSSESSIVE = Regex("^(?i)(my|the)\\s+")
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        private val APP_NAMES = mapOf(MessageApp.SMS to "Messages", MessageApp.WHATSAPP to "WhatsApp", MessageApp.EMAIL to "Email")
        private val PANELS = mapOf(
            "wifi" to SettingsPanel.WIFI, "wi_fi" to SettingsPanel.WIFI, "internet" to SettingsPanel.MOBILE_DATA,
            "bluetooth" to SettingsPanel.BLUETOOTH, "bt" to SettingsPanel.BLUETOOTH,
            "mobile_data" to SettingsPanel.MOBILE_DATA, "data" to SettingsPanel.MOBILE_DATA, "cellular" to SettingsPanel.MOBILE_DATA,
            "airplane_mode" to SettingsPanel.AIRPLANE_MODE, "airplane" to SettingsPanel.AIRPLANE_MODE, "flight_mode" to SettingsPanel.AIRPLANE_MODE,
            "location" to SettingsPanel.LOCATION, "gps" to SettingsPanel.LOCATION,
            "hotspot" to SettingsPanel.HOTSPOT, "mobile_hotspot" to SettingsPanel.HOTSPOT, "tethering" to SettingsPanel.HOTSPOT,
        )
    }
}
