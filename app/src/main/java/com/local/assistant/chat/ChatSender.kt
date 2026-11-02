package com.local.assistant.chat

import android.util.Log
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LlmService
import com.local.assistant.llm.ContextWindow
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
    /** [step]: what is being done before (or between) the words, for the screen to say ([ToolSteps]). */
    data class Live(val chatId: Long?, val text: String, val owner: Any, val step: String? = null)

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

        /**
         * Asked on the lock screen for something that needs an unlock: nothing was done or stored
         * as a reply. [resume] with [userMessageId] once the phone is unlocked. Before [onFinished].
         */
        fun onNeedsUnlock(chatId: Long, userMessageId: Long) {}
    }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    val isBusy: Boolean get() = _live.value != null

    private var job: Job? = null
    private var stopRequested = false

    /**
     * Stores [text] and [attachment] in [chatId] — a new chat when null — and streams the reply.
     * Whichever screen sends it, the model gets the same turn: the same instructions, memory and
     * tools. [hidden]: a new chat is left out of the chat list (the assistant overlay's), though
     * stored and learned from like any other. [locked]: asked on the lock screen
     * ([TurnRunner.run]). Returns false, storing nothing, when there is nothing to send or another
     * turn is still in flight.
     */
    fun send(
        chatId: Long?,
        text: String,
        attachment: Attachment?,
        owner: Any,
        listener: Listener,
        hidden: Boolean = false,
        locked: Boolean = false,
    ): Boolean {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachment == null) || isBusy) return false
        return start(chatId, prompt, attachment, owner, listener, hidden, locked, storedUserMessageId = null)
    }

    /**
     * Runs the turn for a message already stored, with everything this time: what was asked on
     * the lock screen, once the phone has been unlocked ([Listener.onNeedsUnlock]).
     */
    fun resume(chatId: Long, userMessageId: Long, owner: Any, listener: Listener): Boolean {
        if (isBusy) return false
        return start(chatId, prompt = "", attachment = null, owner, listener, hidden = false, locked = false, storedUserMessageId = userMessageId)
    }

    private fun start(
        chatId: Long?,
        prompt: String,
        attachment: Attachment?,
        owner: Any,
        listener: Listener,
        hidden: Boolean,
        locked: Boolean,
        storedUserMessageId: Long?,
    ): Boolean {
        stopRequested = false
        _live.value = Live(chatId, "", owner)

        // Main, as when each screen ran its own turns: the listeners update screen state.
        job = scope.launch(Dispatchers.Main.immediate) {
            var chat = chatId
            var userMessageId: Long? = storedUserMessageId
            val reply = StringBuilder()
            var failure: String? = null
            var completed = false
            var needsUnlock = false
            try {
                if (chat == null) {
                    chat = repository.createChat(hidden = hidden)
                    // Both in one go, with no suspension between, so a screen matching the stream
                    // to its chat never sees one without the other.
                    _live.update { it?.copy(chatId = chat) }
                    listener.onChatCreated(chat)
                }

                // Stored first, so nothing the user wrote is lost whatever happens next.
                val asked = userMessageId ?: repository.addMessage(
                    chatId = chat,
                    role = Role.USER,
                    text = prompt,
                    attachmentPath = attachment?.path,
                    attachmentKind = attachment?.kind,
                    attachmentDurationMs = attachment?.durationMs,
                ).also { stored ->
                    userMessageId = stored
                    listener.onUserMessageStored(stored)
                    repository.titleFromFirstMessage(
                        chatId = chat,
                        firstMessage = prompt,
                        fallback = when (attachment?.kind) {
                            AttachmentKind.IMAGE -> "Image"
                            AttachmentKind.AUDIO -> ChatRepository.VOICE_TITLE
                            null -> ChatRepository.DEFAULT_TITLE
                        },
                    )
                }

                // Resumed: the message as it was stored.
                val stored = storedUserMessageId?.let { repository.message(it) }
                val text = stored?.text ?: prompt
                // A voice message written down goes as its words; the recording is for playing back.
                val promptAttachment = if (stored != null) {
                    stored.attachmentPath?.let { path -> ContextWindow.sentAttachment(stored.attachmentKind, text)?.let { PromptAttachment(path, it) } }
                } else {
                    attachment?.let { ContextWindow.sentAttachment(it.kind, text)?.let { kind -> PromptAttachment(it.path, kind) } }
                }
                turns.run(chat, asked, text, promptAttachment, locked).collect { event ->
                    when (event) {
                        is TurnEvent.Text -> {
                            reply.append(event.delta)
                            // Words again: whatever was being done is done.
                            _live.update { it?.copy(text = reply.toString(), step = null) }
                        }
                        is TurnEvent.Step -> _live.update { it?.copy(step = event.label) }
                        is TurnEvent.Memory -> listener.onMemory(event.chip)
                        is TurnEvent.Done -> {
                            completed = true
                            _live.update { it?.copy(step = null) }
                        }
                        TurnEvent.NeedsUnlock -> needsUnlock = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Generation failed"
                // The screen shows it for a moment; the log keeps it, for working out why later.
                Log.w(TAG, "Turn failed in chat $chat", e)
            } finally {
                // Runs even when stop() cancelled us, so a partial reply is never lost.
                withContext(NonCancellable) {
                    val asked = userMessageId
                    // Null only if stopped before the message was stored: then there is no turn.
                    if (chat != null && asked != null) {
                        val incomplete = stopRequested || failure != null || needsUnlock
                        var assistantMessageId: Long? = null
                        // Stopped for an unlock: whatever the model began to say isn't the answer.
                        if (needsUnlock) reply.clear()
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
                    if (failure == null && reply.isEmpty() && !stopRequested) Log.w(TAG, "Turn in chat $chat ended with no reply (completed: $completed)")
                    _live.value = null
                    if (needsUnlock && chat != null && asked != null) listener.onNeedsUnlock(chat, asked)
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

    private companion object {
        const val TAG = "ChatSender"
    }
}
