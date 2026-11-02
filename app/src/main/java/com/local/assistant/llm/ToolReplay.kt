package com.local.assistant.llm

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

    private val gson = com.google.gson.Gson()
}
