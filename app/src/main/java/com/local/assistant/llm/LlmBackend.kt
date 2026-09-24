package com.local.assistant.llm

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * The model, as the rest of the app sees it.
 *
 * Everything above this line — prompt assembly, the tool loop, memory — talks to this interface
 * rather than to LiteRT-LM, so another runtime (AICore, say) can slot in later. A backend
 * without native tool calling would run the same tools over a JSON-in-text protocol behind the
 * same [ChatSession].
 */
interface LlmBackend {

    /** Null until a model is loaded; what it reports is read from the model file, not assumed. */
    val capabilities: BackendCapabilities?

    /** Loads the model if needed. Returns false if it cannot be loaded. */
    suspend fun ensureReady(): Boolean

    /** A conversation holding [ChatSpec.systemPrefix] and [ChatSpec.history] in its KV cache. */
    suspend fun openChat(spec: ChatSpec): ChatSession

    /**
     * A one-shot completion in a short-lived conversation of its own: summaries, extraction.
     * With [jsonSchema], output is constrained to it where the backend supports that.
     */
    suspend fun complete(
        system: String,
        input: String,
        jsonSchema: String? = null,
        maxTokens: Int,
        sampling: Sampling = Sampling.BACKGROUND,
    ): String

    /** Frees the model and every conversation on it. */
    suspend fun release()

    /**
     * Whether [error] is the runtime refusing a prompt that does not fit its window — the one
     * failure that shrinking the prompt and trying again can fix.
     */
    fun isContextOverflow(error: Throwable): Boolean
}

data class BackendCapabilities(
    val tools: Boolean,
    val constrainedJson: Boolean,
    val thinking: Boolean,
    val speculativeDecoding: Boolean,
    /** The window the engine is actually running with. */
    val maxContextTokens: Int,
    /** What one image costs in the context, as the model reports it. */
    val visionTokensPerImage: Int,
)

/**
 * Sampler settings. In LiteRT-LM 0.17.1 these are fixed per conversation, not per message, so
 * chat turns and tool follow-ups necessarily share one setting.
 */
data class Sampling(val topK: Int, val topP: Double, val temperature: Double) {
    companion object {
        /** Gemma's recommended settings, and what chat has always used. */
        val CHAT = Sampling(topK = 64, topP = 0.95, temperature = 1.0)

        /** Summaries and extraction: the same answer every time matters more than variety. */
        val BACKGROUND = Sampling(topK = 64, topP = 0.95, temperature = 0.2)
    }
}

data class ChatSpec(
    /** Sections A–D, byte-stable for the life of the session. */
    val systemPrefix: String,
    /** Replayed verbatim; user and assistant turns only. */
    val history: List<MessageEntity>,
    /** OpenAPI function declarations, one JSON object each. Empty until tools exist. */
    val toolDeclarations: List<String> = emptyList(),
    val sampling: Sampling = Sampling.CHAT,
    val maxOutputTokens: Int,
    /**
     * Prefill the prefix and history now rather than on the first send, so a rebuild done while
     * the chat is idle costs the next reply nothing.
     */
    val prefillOnOpen: Boolean = false,
)

/** A file sent alongside the text of a turn. */
data class PromptAttachment(val path: String, val kind: AttachmentKind)

interface ChatSession : AutoCloseable {

    val isAlive: Boolean

    /** Streams the reply to one user turn. [envelope] is the full user message, E–G. */
    fun send(envelope: String, attachment: PromptAttachment? = null): Flow<GenEvent>

    /** Answers the model's tool calls and streams whatever it says next. */
    fun sendToolResults(results: List<ToolResult>): Flow<GenEvent>

    /** Tokens currently held by the conversation, as the runtime counts them. */
    fun tokenCount(): Int?

    /** Interrupts the reply in flight; what was streamed so far stays. */
    fun cancel()
}

data class ToolCall(val name: String, val arguments: Map<String, Any?>)

data class ToolResult(val name: String, val json: String)

data class GenStats(
    val decodeTokens: Int,
    val tokensPerSecond: Double,
    val timeToFirstTokenMs: Long,
    val prefillTokens: Int,
)

sealed interface GenEvent {
    data class TextDelta(val text: String) : GenEvent
    data class ToolCalls(val calls: List<ToolCall>) : GenEvent
    data class Done(val stats: GenStats?) : GenEvent
    data class Error(val cause: Throwable) : GenEvent
}
