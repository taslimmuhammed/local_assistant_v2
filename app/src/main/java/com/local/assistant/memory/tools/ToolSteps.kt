package com.local.assistant.memory.tools

import com.local.assistant.llm.ToolCall

/**
 * What the user is shown while the assistant works, in a few words: beside the waiting dot, or
 * under the reply when a tool runs partway through it. The model doesn't think aloud (thinking is
 * off), so these are the steps the app itself can see: recall, a rebuild, each tool call.
 */
object ToolSteps {

    const val THINKING = "Thinking…"
    const val CHECKING_MEMORY = "Checking your memories…"
    const val READING_CONVERSATION = "Reading the conversation…"

    /** A new chat's conversation opened on the send path: the instructions and tools are read. */
    const val READING_INSTRUCTIONS = "Reading the instructions…"

    /** The turn waits for the model to load; the screen adds how long that should take. */
    const val LOADING_MODEL = "Loading the model…"

    /** After the tools ran, while the model reads their results and starts its reply. */
    const val WRITING_REPLY = "Writing the reply…"

    /** What [call] is doing, for a message that said [userText]. */
    fun of(call: ToolCall, userText: String): String = when (call.name) {
        ToolCatalog.FIND_PHOTOS ->
            if (call.arguments["question"] != null || com.local.assistant.gallery.PhotoTools.questionIn(userText) != null) {
                "Searching your photos and reading one…"
            } else {
                "Searching your photos…"
            }
        ToolCatalog.SEARCH_MEMORY -> "Searching your memories…"
        ToolCatalog.WEB_SEARCH -> "Searching the web…"
        ToolCatalog.GET_UPCOMING -> "Checking what's coming up…"
        ToolCatalog.ADD_TASK -> "Setting a reminder…"
        ToolCatalog.UPDATE_TASK -> "Updating the reminder…"
        ToolCatalog.ADD_EVENT -> "Adding it to your calendar…"
        ToolCatalog.SAVE_FACT -> "Saving it to memory…"
        ToolCatalog.FORGET -> "Forgetting it…"
        ToolCatalog.SET_ALARM -> "Setting an alarm…"
        ToolCatalog.SET_TIMER -> "Setting a timer…"
        ToolCatalog.REMEMBER_IMAGE -> "Saving the image…"
        ToolCatalog.CALL -> "Opening the dialer…"
        ToolCatalog.SEND_MESSAGE -> "Writing the message…"
        ToolCatalog.OPEN_APP -> "Opening the app…"
        ToolCatalog.PHONE_SETTING -> "Changing the setting…"
        ToolCatalog.CALCULATE -> "Calculating…"
        ToolCatalog.TAKE_SCREENSHOT -> "Saving the screenshot…"
        ToolCatalog.EDIT_NOTE -> "Updating your notes…"
        ToolCatalog.HEALTH -> when (com.local.assistant.health.HealthMetric.parse(call.arguments["metric"]?.toString())) {
            com.local.assistant.health.HealthMetric.SLEEP -> "Checking your sleep…"
            com.local.assistant.health.HealthMetric.HEART_RATE, com.local.assistant.health.HealthMetric.RESTING_HEART_RATE -> "Checking your heart rate…"
            com.local.assistant.health.HealthMetric.EXERCISE -> "Checking your workouts…"
            else -> "Checking your activity…"
        }
        else -> "Working on it…"
    }

    /**
     * What is true once [calls] have run ([ok] for each), while the model reads their results:
     * the reply after a tool can take seconds to start, and the step that ran it ("Setting a
     * reminder…") would otherwise stay up as if it were still being done.
     */
    fun done(calls: List<ToolCall>, ok: List<Boolean>): String {
        val single = calls.singleOrNull()
        if (single == null) return if (ok.isNotEmpty() && ok.all { it }) "All done · ${WRITING_REPLY.lowercase()}" else WRITING_REPLY
        if (ok.firstOrNull() != true) return WRITING_REPLY
        val did = when (single.name) {
            ToolCatalog.ADD_TASK -> "Reminder set"
            ToolCatalog.UPDATE_TASK -> "Reminder updated"
            ToolCatalog.ADD_EVENT -> "Added to your calendar"
            ToolCatalog.SAVE_FACT -> "Saved to memory"
            ToolCatalog.FORGET -> "Forgotten"
            ToolCatalog.SET_ALARM -> "Alarm set"
            ToolCatalog.SET_TIMER -> "Timer set"
            ToolCatalog.REMEMBER_IMAGE -> "Image saved"
            ToolCatalog.CALL -> "Dialer ready"
            ToolCatalog.SEND_MESSAGE -> "Message ready"
            ToolCatalog.OPEN_APP -> "App opened"
            ToolCatalog.PHONE_SETTING -> "Setting changed"
            ToolCatalog.CALCULATE -> "Worked out"
            ToolCatalog.TAKE_SCREENSHOT -> "Screenshot taken"
            ToolCatalog.EDIT_NOTE -> "Notes updated"
            ToolCatalog.GET_UPCOMING -> "Agenda read"
            ToolCatalog.SEARCH_MEMORY -> "Memories searched"
            ToolCatalog.WEB_SEARCH -> "Web searched"
            ToolCatalog.FIND_PHOTOS -> "Photos searched"
            ToolCatalog.HEALTH -> "Health data read"
            else -> return WRITING_REPLY
        }
        return "$did · ${WRITING_REPLY.lowercase()}"
    }
}
