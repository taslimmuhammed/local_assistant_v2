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
import com.local.assistant.memory.prompt.TurnTimings
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
    /**
     * [step]: what is being done before (or between) the words, for the screen to say
     * ([ToolSteps]). [standing]: how much of [text] is known to stand; the rest is shown but may
     * yet be taken back ([TurnEvent.Retract]), so it is not spoken until it stands.
     * [asked]: the words just sent, for the screen to show before the stored copy comes back from
     * the database; [askedId] is that copy's id once it is stored.
     */
    data class Live(
        val chatId: Long?,
        val text: String,
        val owner: Any,
        val step: String? = null,
        val standing: Int = text.length,
        val asked: String? = null,
        val askedId: Long? = null,
    ) {
        /** What may be read aloud. */
        val sayable: String get() = text.take(standing)
    }

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
        /** The screenshot of the screen the assistant overlay was opened over, if it was. */
        screen: String? = null,
    ): Boolean {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachment == null) || isBusy) return false
        return start(chatId, prompt, attachment, owner, listener, hidden, locked, storedUserMessageId = null, screen = screen)
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
        screen: String? = null,
    ): Boolean {
        stopRequested = false
        // Plain words only: a photo or a recording is shown once stored, as it was sent.
        _live.value = Live(chatId, "", owner, asked = prompt.takeIf { it.isNotEmpty() && attachment == null && storedUserMessageId == null })
        val timing = TurnTimings.begin(if (hidden) "panel" else "chat")

        // Main, as when each screen ran its own turns: the listeners update screen state.
        job = scope.launch(Dispatchers.Main.immediate) {
            var chat = chatId
            var userMessageId: Long? = storedUserMessageId
            val reply = StringBuilder()
            var failure: String? = null
            var completed = false
            var needsUnlock = false
            // How much of the reply stands, and what is being done: published together (publish).
            var standing = 0
            var step: String? = null
            var publishedAt = 0L
            var publishing: Job? = null
            // The words go to the screen at most every [PUBLISH_MS]: each update recomposes the
            // reply, and bursts of tokens (multi-token prediction) came several in a frame.
            fun publish(now: Boolean) {
                val wait = PUBLISH_MS - (android.os.SystemClock.uptimeMillis() - publishedAt)
                if (now || wait <= 0) {
                    publishing?.cancel()
                    publishing = null
                    publishedAt = android.os.SystemClock.uptimeMillis()
                    _live.update { it?.copy(text = reply.toString(), standing = standing, step = step) }
                } else if (publishing == null) {
                    publishing = launch {
                        kotlinx.coroutines.delay(wait)
                        publishing = null
                        publish(now = true)
                    }
                }
            }
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
                    _live.update { it?.copy(askedId = stored) }
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
                // A voice message written down goes as its words, and its recording too when it
                // isn't long (ContextWindow.heard, decided with the rest of the turn).
                val promptAttachment = if (stored != null) {
                    stored.attachmentPath?.let { path -> ContextWindow.sentAttachment(stored.attachmentKind, text)?.let { PromptAttachment(path, it) } }
                } else {
                    attachment?.let { ContextWindow.sentAttachment(it.kind, text)?.let { kind -> PromptAttachment(it.path, kind) } }
                }
                val heard = if (stored != null) {
                    ContextWindow.heard(stored.attachmentKind, text, stored.attachmentDurationMs)
                } else {
                    ContextWindow.heard(attachment?.kind, text, attachment?.durationMs)
                }
                turns.run(chat, asked, text, promptAttachment, locked, screen, heard).collect { event ->
                    when (event) {
                        is TurnEvent.Text -> {
                            val first = reply.isEmpty()
                            if (first) TurnTimings.firstWord()
                            reply.append(event.delta)
                            if (!event.provisional) standing = reply.length
                            // Words again: whatever was being done is done.
                            step = null
                            publish(now = first)
                        }
                        TurnEvent.Confirm -> {
                            standing = reply.length
                            publish(now = true)
                        }
                        TurnEvent.Retract -> {
                            // Said something untrue (that it did what no tool did): gone from the
                            // screen before it is read aloud, while the model is asked again.
                            reply.setLength(standing)
                            if (reply.isEmpty()) step = com.local.assistant.memory.tools.ToolSteps.THINKING
                            publish(now = true)
                        }
                        is TurnEvent.Step -> {
                            step = event.label
                            publish(now = true)
                        }
                        is TurnEvent.Memory -> listener.onMemory(event.chip)
                        is TurnEvent.Done -> {
                            completed = true
                            standing = reply.length
                            step = null
                            publish(now = true)
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
                publishing?.cancel()
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
                                // From the send to the first word on screen, as the user waited for
                                // it. The runtime's own figure stays that of the conversation's first
                                // reply: two turns in a row carried the same value.
                                timeToFirstTokenMs = timing.firstWordAt,
                            )
                        }
                        turns.finish(chat, asked, assistantMessageId, completed = completed && !incomplete)
                    }
                    if (failure == null && reply.isEmpty() && !stopRequested) Log.w(TAG, "Turn in chat $chat ended with no reply (completed: $completed)")
                    TurnTimings.end(
                        timing,
                        when {
                            needsUnlock -> "unlock"
                            stopRequested -> "stopped"
                            failure != null -> "failed"
                            else -> "done"
                        },
                    )
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

        /** The least time between two updates of the words on screen. */
        const val PUBLISH_MS = 50L
    }
}
