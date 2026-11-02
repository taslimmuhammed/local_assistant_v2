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
    const val GETTING_READY = "Getting ready…"

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
        else -> "Working on it…"
    }
}
