package com.local.assistant.memory.core

/**
 * The profile asked for on first launch and kept on the profile screen. Each field is an ordinary
 * fact about the user, pinned to "Always in mind", so it opens every conversation's system prompt
 * and stays in step with what the user says in chats ("I'm 31 now" updates the same row).
 */
object UserProfile {

    data class Field(
        /** The fact's attribute key (subject is always the user). */
        val attribute: String,
        val label: String,
        val hint: String,
        val numeric: Boolean = false,
        val maxChars: Int = 80,
    )

    val FIELDS = listOf(
        Field("name", "Name", "What should I call you?", maxChars = 60),
        Field("age", "Age", "Your age", numeric = true, maxChars = 3),
        Field("job", "Work", "What do you do? e.g. teacher, software engineer"),
        Field("city", "City", "Where do you live?"),
        Field("languages", "Languages", "e.g. English, Hindi, Malayalam"),
        Field("interests", "Interests", "e.g. cricket, cooking, photography", maxChars = 120),
    )

    /** Why [value] cannot be saved for [field], or null if it can. Blank means "clear it". */
    fun problem(field: Field, value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if (field.numeric) {
            val age = text.toIntOrNull() ?: return "Just the number"
            return if (age in 1..120) null else "Between 1 and 120"
        }
        return if (text.length > field.maxChars) "Keep it under ${field.maxChars} characters" else null
    }
}
