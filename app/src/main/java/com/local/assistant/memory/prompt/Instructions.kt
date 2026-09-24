package com.local.assistant.memory.prompt

/**
 * Section A: the constant part of the system instruction.
 *
 * Versioned because it is part of a byte-stable prefix: changing a word here changes every
 * conversation's prefix, which forces a rebuild, and the version is how that is noticed.
 */
object Instructions {

    const val VERSION = 1

    fun render(persona: String): String = buildString {
        if (persona.isNotBlank()) {
            append(persona.trim())
            append("\n\n")
        }
        append(MEMORY_RULES)
    }

    private val MEMORY_RULES = """
        The app gives you a memory. How to use it:
        - Text in [square brackets] at the start of a user message is added by the app, not typed by the user.
        - [Now: …] is the current date and time. Trust it over anything else, and use it for words like "today" or "tomorrow".
        - "About the user" is what the user asked you to keep in mind. Follow their preferences in every reply.
        - [Memory: …] lines are notes recalled from earlier conversations. They can be out of date; when one disagrees with "About the user", that wins.
        - Use all of this naturally. Do not list or quote these notes unless the user asks what you remember.
    """.trimIndent()
}
