package com.local.assistant.away

import java.util.Locale

/**
 * What the user said to a reply in the brief: "send it" sends the draft, "the second one" sends
 * the other suggestion, "cancel" leaves it, and anything else is the reply itself, put in the box
 * for them to check and send.
 */
object VoiceReply {

    enum class Meaning { SEND, SEND_SECOND, CANCEL, REPLY }

    private val SEND = setOf(
        "send", "send it", "send that", "send this", "send the reply", "yes send", "yes send it", "yes", "yeah", "yep", "sure",
        "ok send", "okay send", "ok send it", "okay send it", "go ahead", "send the first one", "the first one", "first one",
        "bhej do", "bhejo", "haan", "haan bhej do", "haan bhejo", "theek hai bhej do", "ha bhej do",
    )

    private val SEND_SECOND = setOf(
        "the second one", "second one", "send the second one", "the other one", "send the other one", "other one", "second",
        "doosra", "dusra", "doosra wala", "dusra wala", "doosra bhejo", "dusra bhejo",
    )

    private val CANCEL = setOf(
        "cancel", "no", "nope", "don't send", "dont send", "do not send", "stop", "never mind", "nevermind", "leave it",
        "nahi", "mat bhejo", "rehne do", "nahi bhejo",
    )

    /** Leans the recogniser towards the short words that decide things. */
    val HINTS = listOf("send it", "bhej do", "the second one", "cancel", "don't send")

    fun meaning(said: String): Meaning {
        val words = said.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return when (words) {
            in SEND -> Meaning.SEND
            in SEND_SECOND -> Meaning.SEND_SECOND
            in CANCEL -> Meaning.CANCEL
            else -> Meaning.REPLY
        }
    }
}
