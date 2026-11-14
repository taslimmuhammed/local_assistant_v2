package com.local.assistant.llm

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall as RuntimeToolCall
import com.google.ai.edge.litertlm.tool
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LlmBackend] on LiteRT-LM, using the engine [LlmService] owns.
 *
 * Tool calling is always manual (`automaticToolCalling = false`): the runtime reports calls as a
 * message in the same stream, and the app decides what to run, validates it, dedupes it and
 * answers — which is what makes undo and the chips under a reply possible.
 */
class LiteRtLmBackend(
    private val llm: LlmService,
    private val settings: SettingsStore,
) : LlmBackend {

    override val capabilities: BackendCapabilities? get() = llm.capabilities.value

    override suspend fun ensureReady(): Boolean = llm.awaitEngine() != null

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        // Capabilities (thinking, for one) are only known once the engine has loaded.
        llm.awaitEngine()
        val conversation = llm.createConversation(
            ConversationConfig(
                systemInstruction = spec.systemPrefix.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                initialMessages = historyMessages(spec.history),
                tools = spec.toolDeclarations.map { tool(DeclaredTool(it)) },
                samplerConfig = spec.sampling.toConfig(),
                automaticToolCalling = false,
                prefillPrefaceOnInit = spec.prefillOnOpen,
                maxOutputToken = spec.maxOutputTokens,
                thinkingConfig = thinkingOff(),
                // Multi-token prediction as the engine has it (on by default): tool results go back
                // as a message of their own ([ToolReplay.asMessage]), which it reads as fast as any.
            ),
        )
        return LiteRtChatSession(conversation, llm, settings)
    }

    /**
     * The stored turns as the runtime is handed them. A run of tool rows (one turn's calls) becomes
     * one model turn making the calls and one message with their results, as they went live.
     */
    private fun historyMessages(history: List<MessageEntity>): List<Message> {
        val messages = mutableListOf<Message>()
        var i = 0
        while (i < history.size) {
            if (history[i].role != Role.TOOL) {
                messages += history[i++].toLiteRtMessages()
                continue
            }
            val calls = mutableListOf<RuntimeToolCall>()
            val results = mutableListOf<ToolResult>()
            while (i < history.size && history[i].role == Role.TOOL) {
                val row = history[i++].text
                val (name, arguments) = ToolReplay.call(row) ?: continue
                calls += RuntimeToolCall(name, arguments)
                ToolReplay.replayed(row)?.let(results::add)
            }
            if (calls.isNotEmpty()) {
                messages += Message.model(Contents.of(emptyList()), calls)
                messages += Message.user(Contents.of(ToolReplay.asMessage(results)))
            }
        }
        return messages
    }

    override suspend fun complete(
        system: String,
        input: String,
        jsonSchema: String?,
        maxTokens: Int,
        sampling: Sampling,
    ): String {
        llm.awaitEngine()
        llm.openConversationCount().takeIf { it > 0 }?.let { Log.w("LiteRtLmBackend", "complete() with $it other conversations open") }
        val constrained = jsonSchema != null && capabilities?.constrainedJson == true
        val conversation = llm.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = sampling.toConfig(),
                automaticToolCalling = false,
                maxOutputToken = maxTokens,
                thinkingConfig = thinkingOff(),
                enableResponseFormat = constrained,
            ),
        )
        // Streamed rather than blocking, so a background job can be stopped mid-generation the
        // moment the user needs the model: cancelling the collector cancels the native decode.
        return withContext(Dispatchers.IO) {
            val out = StringBuilder()
            try {
                llm.generationStarted(conversation)
                conversation.sendMessageAsync(
                    input,
                    responseFormat = if (constrained) ResponseFormat.json(jsonSchema!!) else null,
                ).collect { chunk -> out.append(chunk.toString()) }
                out.toString()
            } catch (e: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw e
            } finally {
                withContext(NonCancellable) {
                    llm.generationFinished(conversation)
                    runCatching { conversation.close() }
                    llm.conversationClosed(conversation)
                }
            }
        }
    }

    override suspend fun release() = llm.unload()

    /** "Input token ids are too long. Exceeding the maximum number of tokens allowed: N". */
    override fun isContextOverflow(error: Throwable): Boolean {
        val message = error.message ?: return false
        return ContextWindow.reportedMaxTokens(message) != null || "too long" in message
    }

    /** Thinking stays off for chat; it costs latency the replies do not need. */
    private fun thinkingOff(): ThinkingConfig? =
        if (capabilities?.thinking == true) ThinkingConfig(enableThinking = false) else null

    override fun isUnreadableToolCall(error: Throwable): Boolean =
        error.message?.contains("Failed to parse tool calls") == true

    private fun Sampling.toConfig() = SamplerConfig(topK = topK, topP = topP, temperature = temperature, seed = seed)

    private fun MessageEntity.toLiteRtMessages(): List<Message> = when (role) {
        Role.USER -> listOf(
            Message.user(
                contentsOf(
                    ContextWindow.historyText(attachmentKind, attachmentPath, text),
                    listOfNotNull(attachmentPath?.let { path -> ContextWindow.sentAttachment(attachmentKind, text)?.let { PromptAttachment(path, it) } }),
                ),
            ),
        )
        Role.ASSISTANT -> listOf(Message.model(text))
        // Grouped with the rows beside it: see historyMessages and ConversationManager.withToolCalls.
        Role.TOOL -> emptyList()
    }

    /** A declaration the model sees. Never executed by the runtime: tool calling is manual. */
    private class DeclaredTool(private val declaration: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = declaration
        override fun execute(paramsJsonString: String): String =
            error("Tools are executed by the app, not the runtime")
    }
}

private class LiteRtChatSession(
    private val conversation: Conversation,
    private val llm: LlmService,
    private val settings: SettingsStore,
) : ChatSession {

    override val isAlive: Boolean get() = runCatching { conversation.isAlive }.getOrDefault(false)

    override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> = send(envelope, listOfNotNull(attachment))

    override fun send(envelope: String, attachments: List<PromptAttachment>): Flow<GenEvent> =
        stream(Message.user(contentsOf(envelope, attachments)))

    override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> =
        stream(Message.user(Contents.of(ToolReplay.asMessage(results))))

    private fun stream(message: Message): Flow<GenEvent> = flow {
        llm.soupNextReply?.let { soup ->
            llm.soupNextReply = null
            soup.split(' ').forEach { emit(GenEvent.TextDelta("$it ")) }
            emit(GenEvent.Done(null))
            return@flow
        }
        llm.generationStarted(conversation)
        var stats: GenStats? = null
        var failure: Throwable? = null
        try {
            conversation.sendMessageAsync(
                message,
                repetitionPenaltyConfig = settings.repetitionPenalty.takeIf { it > 1f }?.let {
                    RepetitionPenaltyConfig(repetitionPenalty = it, windowSize = settings.repetitionWindow)
                },
            ).collect { chunk ->
                if (chunk.toolCalls.isNotEmpty()) {
                    emit(GenEvent.ToolCalls(chunk.toolCalls.map { ToolCall(it.name, it.arguments) }))
                } else {
                    // Message.toString() renders its Contents, which for a text model is the text.
                    val text = chunk.toString()
                    if (text.isNotEmpty()) emit(GenEvent.TextDelta(text))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            // A stopped generation still reports what it managed.
            stats = llm.generationFinished(conversation)
        }
        emit(if (failure != null) GenEvent.Error(failure) else GenEvent.Done(stats))
    }.flowOn(Dispatchers.IO)

    override fun tokenCount(): Int? = runCatching { conversation.getTokenCount() }.getOrNull()

    override fun cancel() {
        runCatching { conversation.cancelProcess() }.onFailure { Log.w(TAG, "cancelProcess failed", it) }
    }

    override fun close() {
        runCatching { conversation.close() }
        llm.conversationClosed(conversation)
    }

    private companion object {
        const val TAG = "LiteRtChatSession"
    }
}

/**
 * Media first, then text — the order the runtime's own examples use.
 *
 * A missing attachment file degrades to text rather than failing the whole turn: the chat is
 * still readable, and the alternative is a chat that can never be reopened.
 */
private fun contentsOf(text: String, attachments: List<PromptAttachment>): Contents {
    val media = attachments.mapNotNull { attachment ->
        val file = File(attachment.path).takeIf { it.isFile } ?: return@mapNotNull null
        when (attachment.kind) {
            AttachmentKind.IMAGE -> Content.ImageFile(file.absolutePath)
            AttachmentKind.AUDIO -> Content.AudioFile(file.absolutePath)
            // Read by the app, never sent as media (ContextWindow.sentAttachment).
            AttachmentKind.DOCUMENT -> null
        }
    }
    if (media.isEmpty()) return Contents.of(text)
    return Contents.of(if (text.isBlank()) media else media + Content.Text(text))
}
