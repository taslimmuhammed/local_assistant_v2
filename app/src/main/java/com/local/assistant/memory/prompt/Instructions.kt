package com.local.assistant.memory.prompt

import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.wake.WakeWord

/**
 * Section A: the constant part of the system instruction.
 *
 * Versioned because it is part of a byte-stable prefix: changing a word here changes every
 * conversation's prefix, which forces a rebuild, and the version is how that is noticed.
 */
object Instructions {

    const val VERSION = 8

    /**
     * What the model is told it is when the persona doesn't say: an assistant, named after the wake
     * word (the user, 2026-10-30: "since the model is now named as luna, it should be aware of it
     * ... also tell it to behave as an assistant if it's not present").
     */
    const val ROLE = "You are a helpful assistant running entirely on the user's device."
    const val NAMED = "Your name is ${WakeWord.NAME}."

    /** [withTools] adds the routing rules for the declared tools. */
    fun render(
        persona: String,
        withTools: Boolean = false,
        webSearch: Boolean = false,
        photoSearch: Boolean = false,
        healthData: Boolean = false,
    ): String = buildString {
        append(identity(persona))
        append("\n\n")
        append(MEMORY_RULES)
        if (withTools) {
            append("\n\n")
            append(ToolCatalog.rules(webSearch, photoSearch, healthData))
        }
    }

    /** The persona, with [ROLE] before it if it never says "assistant" and [NAMED] after if it never names it. */
    fun identity(persona: String): String {
        val trimmed = persona.trim()
        return listOfNotNull(
            ROLE.takeUnless { trimmed.contains("assistant", ignoreCase = true) },
            trimmed.takeIf { it.isNotEmpty() },
            NAMED.takeUnless { trimmed.contains(WakeWord.NAME, ignoreCase = true) },
        ).joinToString(" ")
    }

    private val MEMORY_RULES = """
        The app gives you a memory. How to use it:
        - Text in [square brackets] at the start of a user message is added by the app, not typed by the user.
        - [Now: …] is the current date and time. Trust it over anything else, and use it for words like "today" or "tomorrow".
        - "About the user" is what the user asked you to keep in mind. Follow their preferences in every reply.
        - [Memory: …] lines are facts recalled for this message. [Memory (date): …] lines are parts of earlier conversations, with the day they happened; they can be out of date. When they disagree, "About the user" wins, then [Memory: …], then the newest dated line.
        - [Saved image (date) "…": …] is an image the user asked you to remember, with what you noted when saving it. The image itself is attached to that message, or was earlier in this chat: look at it to answer, and use the note for exact text.
        - Use all of this naturally. Do not list or quote these notes unless the user asks what you remember.
    """.trimIndent()
}
