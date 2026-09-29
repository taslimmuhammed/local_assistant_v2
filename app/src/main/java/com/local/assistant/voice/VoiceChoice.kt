package com.local.assistant.voice

import java.util.Locale

/** A text-to-speech voice, as much of it as choosing one needs. */
data class VoiceInfo(
    val name: String,
    val locale: Locale,
    /** `Voice.QUALITY_*`: higher is better. */
    val quality: Int,
    /** `Voice.LATENCY_*`: lower is faster. */
    val latency: Int,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

/** A voice as the settings list shows it. */
data class VoiceOption(val name: String, val label: String)

/**
 * Which of the phone's voices the assistant may use, and which it uses by default.
 *
 * Only voices that run on the phone: the app answers without a network, and so should its
 * voice. Only voices already installed, since one that isn't would fall silent or fetch data.
 * And only the phone's language, plus English, which is what the model mostly answers in.
 */
object VoiceChoice {

    fun usable(voices: List<VoiceInfo>, device: Locale): List<VoiceInfo> = voices
        .filter { !it.needsNetwork && it.installed }
        .filter { it.locale.language == device.language || it.locale.language == ENGLISH }
        .sortedWith(
            compareBy<VoiceInfo> { closeness(it.locale, device) }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.name },
        )

    /**
     * The saved choice if it is still there; otherwise the engine's own default voice (the one
     * the user already hears from the phone) if it qualifies; otherwise the best that does.
     */
    fun choose(voices: List<VoiceInfo>, saved: String?, engineDefault: String?, device: Locale): VoiceInfo? {
        val usable = usable(voices, device)
        return usable.firstOrNull { it.name == saved }
            ?: usable.firstOrNull { it.name == engineDefault }
            ?: usable.firstOrNull()
    }

    /**
     * "English (India) · Voice 2": engines name voices with codes (`en-in-x-ene-local`), so they
     * are numbered within their language and country, in the order [usable] lists them.
     */
    fun options(voices: List<VoiceInfo>, device: Locale): List<VoiceOption> {
        val counts = mutableMapOf<Locale, Int>()
        return usable(voices, device).map { voice ->
            val n = (counts[voice.locale] ?: 0) + 1
            counts[voice.locale] = n
            VoiceOption(voice.name, "${voice.locale.getDisplayName(Locale.ENGLISH)} · Voice $n")
        }
    }

    /** The phone's own language and country first, then its language, then English. */
    private fun closeness(locale: Locale, device: Locale): Int = when {
        locale.language == device.language && locale.country.equals(device.country, ignoreCase = true) -> 0
        locale.language == device.language -> 1
        else -> 2
    }

    private const val ENGLISH = "en"
}
