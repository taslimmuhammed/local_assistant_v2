package com.local.assistant.llm

import com.google.gson.JsonParser

/**
 * Reads a tool call the runtime rejected. Gemma writes calls as
 * `call:add_task{title:<|"|>Call the CA<|"|>,when:<|"|>tomorrow<|"|>}`, and now and then slips:
 * a key left out (`title:<|"|>Renew passport<|"|>, next month<|"|>`) or a key quoted with its value
 * (`<|"|>to:landlord<|"|>`). The runtime then fails with the text it could not parse, which this
 * reads leniently — and only into a tool that is declared, with parameters it declares. A value
 * with no key goes to the first declared parameter still unset, which is how the slips go.
 */
class ToolCallRepair(declarations: List<String>) {

    /** Each declared tool's parameters, in declaration order. */
    private val parameters: Map<String, List<String>> = declarations.associate { json ->
        val root = JsonParser.parseString(json).asJsonObject
        val properties = root.getAsJsonObject("parameters")?.getAsJsonObject("properties")
        root["name"].asString to properties?.keySet()?.toList().orEmpty()
    }

    fun repair(error: Throwable): ToolCall? = error.message?.let(::repair)

    fun repair(message: String): ToolCall? {
        // "…from code block: call:x{…} full response: <|tool_call>call:x{…}<tool_call|>": only the block.
        val block = message.substringAfter(CODE_BLOCK, message).substringBefore(FULL_RESPONSE).trim()
        val match = CALL.find(block) ?: return null
        val name = match.groupValues[1]
        val declared = parameters[name] ?: return null
        val parts = match.groupValues[2].split(QUOTE)

        val args = linkedMapOf<String, Any?>()
        val keyless = mutableListOf<String>()
        var pendingKey: String? = null
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                // Inside the string markers.
                val key = pendingKey
                val pair = PAIR.matchEntire(part.trim())
                when {
                    key != null -> {
                        args[key] = part
                        pendingKey = null
                    }
                    pair != null && pair.groupValues[1] in declared && pair.groupValues[1] !in args ->
                        args[pair.groupValues[1]] = pair.groupValues[2].trim()
                    part.isNotBlank() -> keyless += part.trim()
                }
            } else {
                for (piece in part.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val pair = PAIR.matchEntire(piece)
                    if (pair != null && pair.groupValues[1] in declared) {
                        val value = pair.groupValues[2].trim()
                        if (value.isEmpty()) pendingKey = pair.groupValues[1] else args[pair.groupValues[1]] = scalar(value)
                    } else {
                        // Bare and keyless, it was often written as an identifier: "next_month".
                        keyless += piece.replace('_', ' ')
                    }
                }
            }
        }
        val free = declared.filter { it !in args }
        if (keyless.size > free.size) return null
        keyless.forEachIndexed { i, value -> args[free[i]] = value }
        return ToolCall(name, args).takeIf { args.isNotEmpty() }
    }

    private fun scalar(value: String): Any = when {
        value == "true" -> true
        value == "false" -> false
        else -> value.toLongOrNull() ?: value.toDoubleOrNull() ?: value
    }

    private companion object {
        const val QUOTE = "<|\"|>"
        const val CODE_BLOCK = "from code block:"
        /** Preceded by a space or a line break, depending on the runtime's mood. */
        const val FULL_RESPONSE = "full response:"
        // Both braces escaped: Android's ICU regex rejects a bare "}" that the JVM accepts.
        val CALL = Regex("call:([A-Za-z_][A-Za-z0-9_]*)\\{(.*)\\}", RegexOption.DOT_MATCHES_ALL)
        val PAIR = Regex("([A-Za-z_][A-Za-z0-9_]*):(.*)", RegexOption.DOT_MATCHES_ALL)
    }
}
