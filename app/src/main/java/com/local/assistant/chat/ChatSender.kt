package com.local.assistant.chat

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LlmService
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.memory.prompt.TurnEvent
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.MemoryChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends a message and streams the reply, for both screens that can: the chat, and the assistant
 * overlay that opens from the power button.
 *
 * App-scoped rather than per screen, for two reasons. Only one turn is ever in flight, whichever
 * screen started it, so two chats' replies can't interleave on the one model. And a reply outlives
 * the screen that asked for it: the overlay closing, or a tool opening the dialer, doesn't cut it
 * off — it finishes and is stored in its chat, and the app shows it streaming if opened meanwhile.
 */
class ChatSender(
    private val repository: ChatRepository,
    private val llm: LlmService,
    private val turns: TurnRunner,
    private val scope: CoroutineScope,
) {

    /** A file sent with the message. */
    data class Attachment(val path: String, val kind: AttachmentKind, val durationMs: Long? = null)

    /**
     * The reply being streamed. [chatId] is null only for the moment a new chat is being made;
     * [owner] is the screen that sent the message.
     */
    data class Live(val chatId: Long?, val text: String, val owner: Any)

    /** What the screen that sent a message hears back. Called on the main thread. */
    interface Listener {
        /** A chat was made for the message, before anything is stored in it. */
        fun onChatCreated(chatId: Long) {}

        /** The user's message is stored; the reply is on its way. */
        fun onUserMessageStored(messageId: Long) {}

        /** Something was saved, scheduled or opened; its chip is stored with the chat too. */
        fun onMemory(chip: MemoryChip) {}

        /** The turn is over. [reply] is all of it, as stored; [failure] says what went wrong, if anything. */
        fun onFinished(reply: String, failure: String?) {}
    }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    val isBusy: Boolean get() = _live.value != null

    private var job: Job? = null
    private var stopRequested = false

    /**
     * Stores [text] and [attachment] in [chatId] — a new chat when null — and streams the reply.
     * Whichever screen sends it, the model gets the same turn: the same instructions, memory and
     * tools. Returns false, storing nothing, when there is nothing to send or another turn is
     * still in flight.
     */
    fun send(
        chatId: Long?,
        text: String,
        attachment: Attachment?,
        owner: Any,
        listener: Listener,
    ): Boolean {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachment == null) || isBusy) return false

        stopRequested = false
        _live.value = Live(chatId, "", owner)

        // Main, as when each screen ran its own turns: the listeners update screen state.
        job = scope.launch(Dispatchers.Main.immediate) {
            var chat = chatId
            var userMessageId: Long? = null
            val reply = StringBuilder()
            var failure: String? = null
            var completed = false
            try {
                if (chat == null) {
                    chat = repository.createChat()
                    // Both in one go, with no suspension between, so a screen matching the stream
                    // to its chat never sees one without the other.
                    _live.update { it?.copy(chatId = chat) }
                    listener.onChatCreated(chat)
                }

                // Stored first, so nothing the user wrote is lost whatever happens next.
                userMessageId = repository.addMessage(
                    chatId = chat,
                    role = Role.USER,
                    text = prompt,
                    attachmentPath = attachment?.path,
                    attachmentKind = attachment?.kind,
                    attachmentDurationMs = attachment?.durationMs,
                )
                listener.onUserMessageStored(userMessageId)
                repository.titleFromFirstMessage(
                    chatId = chat,
                    firstMessage = prompt,
                    fallback = when (attachment?.kind) {
                        AttachmentKind.IMAGE -> "Image"
                        AttachmentKind.AUDIO -> "Voice message"
                        null -> ChatRepository.DEFAULT_TITLE
                    },
                )

                val promptAttachment = attachment?.let { PromptAttachment(it.path, it.kind) }
                turns.run(chat, userMessageId, prompt, promptAttachment).collect { event ->
                    when (event) {
                        is TurnEvent.Text -> {
                            reply.append(event.delta)
                            _live.update { it?.copy(text = reply.toString()) }
                        }
                        is TurnEvent.Memory -> listener.onMemory(event.chip)
                        is TurnEvent.Done -> completed = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Generation failed"
            } finally {
                // Runs even when stop() cancelled us, so a partial reply is never lost.
                withContext(NonCancellable) {
                    val asked = userMessageId
                    // Null only if stopped before the message was stored: then there is no turn.
                    if (chat != null && asked != null) {
                        val incomplete = stopRequested || failure != null
                        var assistantMessageId: Long? = null
                        if (reply.isNotEmpty()) {
                            val stats = llm.lastGenerationStats.value
                            assistantMessageId = repository.addMessage(
                                chatId = chat,
                                role = Role.ASSISTANT,
                                text = reply.toString(),
                                incomplete = incomplete,
                                tokensPerSecond = stats?.tokensPerSecond,
                                timeToFirstTokenMs = stats?.timeToFirstTokenMs,
                            )
                        }
                        turns.finish(chat, asked, assistantMessageId, completed = completed && !incomplete)
                    }
                    _live.value = null
                    listener.onFinished(reply.toString(), failure)
                }
            }
        }
        return true
    }

    /** Stops the reply in flight, keeping what it had said. */
    fun stop() {
        if (!isBusy) return
        stopRequested = true
        // Ask the runtime to stop decoding, then unwind the collector.
        llm.stop()
        job?.cancel()
    }
}
