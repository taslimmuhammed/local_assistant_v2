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
    /** Not "call": Gemma writes a tool call as `call:<name>{…}`, and `call:call` fails to parse. */
    const val CALL = "phone_call"
    const val SEND_MESSAGE = "send_message"
    const val SET_TIMER = "set_timer"
    const val OPEN_APP = "open_app"
    const val PHONE_SETTING = "phone_setting"
    const val CALCULATE = "calculate"
    /** Not "web_search": beside search_memory the model blended the two ("web_search_memory"). */
    const val WEB_SEARCH = "web_lookup"

    /** Tools that change something, as opposed to reading it. */
    val WRITES = setOf(ADD_TASK, UPDATE_TASK, ADD_EVENT, SAVE_FACT, FORGET, SET_ALARM, REMEMBER_IMAGE)

    /** The everyday tools (`DeviceTools`): they act on the phone, never on memory. */
    val DEVICE = setOf(CALL, SEND_MESSAGE, SET_TIMER, OPEN_APP, PHONE_SETTING, CALCULATE)

    /** Device tools with an effect a repeat would duplicate: a second dialer, a second timer. */
    val DEVICE_ACTIONS = DEVICE - CALCULATE

    /** Tools that don't touch memory, run outside its transaction: they wait on apps, dialogs or the network. */
    val OUTSIDE_MEMORY = DEVICE + WEB_SEARCH

    /** Every tool the model is given; web search only once the user has set it up. */
    fun declarations(web: Boolean): List<String> = if (web) declarations + WEB_DECLARATION else declarations

    /** How to use the tools, appended to the instructions (section A) when tools are declared. */
    fun rules(web: Boolean): String = RULES.replace(WEB_SLOT, if (web) WEB_RULE + "\n" else "")

    private val WEB_DECLARATION = function(
        WEB_SEARCH,
        "Use only for things that change or are too recent for you to know: news, weather, prices, scores, schedules; or when asked to look something up online. query: a short search phrase; topic: news for recent events.",
        required = listOf("query"),
        "query" to "string",
        "topic" to "string",
    )

    private const val WEB_RULE =
        "- News, weather, prices, scores and other things that change, or when asked to look something up online → web_lookup; answer from its results and name the site. General knowledge, how-to, advice and recipes: answer yourself."
    private const val WEB_SLOT = "{web}\n"

    /** The tools declared whatever is set up; see [declarations] for the full list. */
    private val declarations: List<String> = listOf(
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
        function(
            CALL,
            "Use when the user asks you to call someone now. who: their name, relation or number, as said.",
            required = listOf("who"),
            "who" to "string",
        ),
        function(
            SEND_MESSAGE,
            "Use when the user asks you to text, WhatsApp or email someone now. to: name, relation, number or email; text: the message to send; app: sms, whatsapp or email.",
            required = listOf("to"),
            "to" to "string",
            "text" to "string",
            "app" to "string",
        ),
        function(
            SET_TIMER,
            "Use for a countdown timer. duration: the user's words for how long, copied exactly.",
            required = listOf("duration"),
            "duration" to "string",
            "label" to "string",
        ),
        function(
            OPEN_APP,
            "Use to open an app, or for directions, songs, videos or a web search. app: its name, or maps, music, youtube or browser; query: what to find.",
            required = listOf("app"),
            "app" to "string",
            "query" to "string",
        ),
        function(
            PHONE_SETTING,
            "Use to change a phone setting now. setting: flashlight, ringer, volume, do_not_disturb, wifi, bluetooth, mobile_data or airplane_mode; value: on, off, silent, vibrate, up, down or a percent.",
            required = listOf("setting"),
            "setting" to "string",
            "value" to "string",
        ),
        function(
            CALCULATE,
            "Use for any arithmetic or unit conversion. expression: the sum or conversion, like 2450*18/100 or 5 miles in km.",
            required = listOf("expression"),
            "expression" to "string",
        ),
    )

    private val RULES = """
        Tools — call them rather than only saying you will:
        - Reminders and to-dos → add_task, with the user's own time words in when.
        - Alarms ("set an alarm", "wake me up") → set_alarm, which rings in the phone's clock app.
        - "Remember this" about an image → remember_image, with everything you can read in details (not save_fact).
        - Calls, messages, timers, apps and phone settings only when asked for now: "remind me to call amma at 6" is add_task, "I should call her" is no tool.
        - Any arithmetic or unit conversion → calculate.
        - Changing, finishing or moving an existing reminder → update_task.
        - Appointments, meetings, birthdays, trips → add_event.
        - Lasting facts about the user or their people and places → save_fact. How they want you to reply from now on ("keep answers short", "reply in Hindi") → save_fact with core=true, then follow it. Never for questions, hypotheticals, wishes, other people's opinions, passing moods or jokes.
        - "What's coming up" beyond the agenda → get_upcoming. Something told before that is not above → search_memory. "Forget …" → forget.
        {web}
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
