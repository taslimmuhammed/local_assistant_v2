package com.local.assistant.llm

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.local.assistant.memory.tools.ChatToolLog

/**
 * A stored tool call (a TOOL row's [com.local.assistant.memory.tools.ToolRecord]) as a rebuilt
 * conversation replays it: the call as the model made it, and its result cut short. Long text in
 * a result (a web answer, recalled snippets) is cut to [MAX_TEXT] characters and lists to
 * [MAX_ITEMS]: what matters later is that the call was made and roughly what came back, not the
 * tokens of all of it again.
 */
object ToolReplay {

    /** The tool's name and arguments, or null for a row that isn't a readable call. */
    fun call(json: String): Pair<String, Map<String, Any?>>? =
        ChatToolLog.parse(json)?.takeIf { it.tool.isNotBlank() }?.let { it.tool to it.arguments }

    /** What the call returned, shortened; an unreadable result as an error. */
    fun result(json: String): Map<String, Any?> {
        val raw = ChatToolLog.parse(json)?.result ?: return mapOf("ok" to false)
        @Suppress("UNCHECKED_CAST")
        val map = runCatching { gson.fromJson(raw, Map::class.java) as Map<String, Any?> }.getOrNull() ?: return mapOf("result" to shorten(raw))
        return map.mapValues { (_, value) -> shorten(value) }
    }

    private fun shorten(value: Any?): Any? = when (value) {
        is String -> if (value.length > MAX_TEXT) value.take(MAX_TEXT) + "…" else value
        is List<*> -> value.take(MAX_ITEMS).map(::shorten)
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to shorten(v) }
        else -> value
    }

    const val MAX_TEXT = 240
    const val MAX_ITEMS = 6

    /**
     * A tool result's JSON as the model is handed it, now or replayed. Whole numbers stay whole:
     * read Gson's default way every number became a Double, the model saw
     * "steps_left_to_goal": 7740.0 and wrote "7740.0 steps away" (5 Nov 2026).
     */
    internal val gson: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
}
