package com.local.assistant.memory.tools

/**
 * The tools the model can call, as OpenAPI function declarations.
 *
 * Descriptions are routing rules, kept short on purpose: they sit in every conversation's prefix
 * and count against its budget, and a 4B model follows a crisp "use when …" better than an
 * essay. If the model misroutes a kind of request, fix it here and in [RULES] — never with regex
 * routing that goes around the model.
 */
object ToolCatalog {

    const val ADD_TASK = "add_task"
    const val UPDATE_TASK = "update_task"
    const val ADD_EVENT = "add_event"
    const val SAVE_FACT = "save_fact"
    const val GET_UPCOMING = "get_upcoming"
    const val SEARCH_MEMORY = "search_memory"
    const val FORGET = "forget"
    const val SET_ALARM = "set_alarm"
    const val REMEMBER_IMAGE = "remember_image"

    /** Tools that change something, as opposed to reading it. */
    val WRITES = setOf(ADD_TASK, UPDATE_TASK, ADD_EVENT, SAVE_FACT, FORGET, SET_ALARM, REMEMBER_IMAGE)

    val declarations: List<String> = listOf(
        function(
            ADD_TASK,
            "Use when the user wants to be reminded or to do something later (\\\"remind me\\\", \\\"don't let me forget\\\"). Put the user's own time words in when, copied exactly.",
            required = listOf("title"),
            "title" to "string",
            "when" to "string",
            "repeat" to "string",
        ),
        function(
            UPDATE_TASK,
            "Use to complete, cancel, reopen or reschedule an existing reminder. task is its id from context or words from its title.",
            required = listOf("task"),
            "task" to "string",
            "status" to "string",
            "when" to "string",
        ),
        function(
            ADD_EVENT,
            "Use for something happening at a set time that the user will attend: appointments, meetings, birthdays, trips. Not for to-dos.",
            required = listOf("title", "when"),
            "title" to "string",
            "when" to "string",
            "ends" to "string",
            "repeat" to "string",
            "notes" to "string",
        ),
        function(
            SAVE_FACT,
            "Use when the user states a lasting fact about themselves or people and places in their life (\\\"my dentist is …\\\"), and when they tell you how to reply from now on (\\\"reply in short answers\\\": attribute pref.reply_style, core=true). subject is user or who it is about; attribute is short, like dentist or birthday. Not for plans, moods or questions.",
            required = listOf("subject", "attribute", "value"),
            "subject" to "string",
            "attribute" to "string",
            "value" to "string",
            "core" to "boolean",
        ),
        function(
            GET_UPCOMING,
            "Use when the user asks what's coming up or for their schedule, beyond the agenda shown.",
            required = emptyList(),
            "days" to "integer",
        ),
        function(
            SEARCH_MEMORY,
            "Use when the user asks about something they told you before or a past conversation, and it isn't already in context. Not for advice, hypotheticals or general questions.",
            required = listOf("query"),
            "query" to "string",
        ),
        function(
            SET_ALARM,
            "Use when the user asks for an alarm (\\\"wake me up at 5:30\\\"). It rings in the phone's clock app. Put the user's own time words in when, copied exactly.",
            required = listOf("when"),
            "when" to "string",
            "label" to "string",
            "repeat" to "string",
        ),
        function(
            REMEMBER_IMAGE,
            "Use when the user asks you to remember or save an image they sent: a card, bill, document, screenshot or photo. The image itself is kept and shown to you again when they ask about it later. title: a few words naming it; details: everything you can read and see in it.",
            required = listOf("title", "details"),
            "title" to "string",
            "details" to "string",
        ),
        function(
            FORGET,
            "Use when the user asks you to forget or delete something they told you.",
            required = listOf("subject"),
            "subject" to "string",
            "attribute" to "string",
        ),
    )

    /** How to use the tools, appended to the instructions (section A) when tools are declared. */
    val RULES = """
        Tools — call them rather than only saying you will:
        - Reminders and to-dos → add_task, with the user's own time words in when.
        - Alarms ("set an alarm", "wake me up") → set_alarm, which rings in the phone's clock app.
        - "Remember this" about an image → remember_image, with everything you can read in details (not save_fact).
        - Changing, finishing or moving an existing reminder → update_task.
        - Appointments, meetings, birthdays, trips → add_event.
        - Lasting facts about the user or their people and places → save_fact. How they want you to reply from now on ("keep answers short", "reply in Hindi") → save_fact with core=true, then follow it. Never for questions, hypotheticals, other people's opinions, passing moods or jokes.
        - "What's coming up" beyond the agenda → get_upcoming. Something told before that is not above → search_memory. "Forget …" → forget.
        After a tool succeeds, confirm in one short sentence. If it returns ok:false, ask the user for what is missing.
    """.trimIndent()

    private fun function(
        name: String,
        description: String,
        required: List<String>,
        vararg properties: Pair<String, String>,
    ): String = buildString {
        append("{\"name\":\"").append(name).append("\",\"description\":\"").append(description).append("\",")
        append("\"parameters\":{\"type\":\"object\",\"properties\":{")
        append(properties.joinToString(",") { (key, type) -> "\"$key\":{\"type\":\"$type\"}" })
        append("},\"required\":[")
        append(required.joinToString(",") { "\"$it\"" })
        append("]}}")
    }
}
