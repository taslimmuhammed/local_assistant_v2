package com.local.assistant.memory.prompt

import android.util.Log
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.GenStats
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.llm.ToolReplay
import com.local.assistant.memory.tools.AttachedDocument
import com.local.assistant.memory.tools.InstantCommands
import com.local.assistant.memory.tools.LoopEvent
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.NumberGrounding
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolLoop
import com.local.assistant.memory.tools.ToolSteps
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the chat UI hears about a turn. */
sealed interface TurnEvent {
    /** [provisional]: may yet be taken back ([Retract]); not to be spoken before [Confirm]. */
    data class Text(val delta: String, val provisional: Boolean = false) : TurnEvent

    /** All the text so far stands. */
    data object Confirm : TurnEvent

    /** The text since the last [Confirm] is withdrawn. */
    data object Retract : TurnEvent

    /** All of the reply so far is withdrawn, what stood too: the turn goes again from the start. */
    data object Restart : TurnEvent

    /** Something was saved, scheduled or forgotten; its chip is also stored with the chat. */
    data class Memory(val recordId: Long, val chip: MemoryChip) : TurnEvent

    data class Done(val stats: GenStats?) : TurnEvent

    /** On the lock screen, the request needs more than is allowed there: unlock, then ask again. */
    data object NeedsUnlock : TurnEvent

    /** What is being done now, for the user to see: recall, a rebuild, a tool ([ToolSteps]). */
    data class Step(val label: String) : TurnEvent
}

/**
 * Runs one user turn: prepare the conversation, send the envelope, run any tool calls, stream
 * the reply.
 *
 * This is the seam the chat screen talks to. The user's message is stored before [run] is
 * called and the reply after it finishes, so a crash at any point loses nothing the user wrote;
 * [finish] then tells the memory layer how the turn ended.
 */
class TurnRunner(
    private val conversations: ConversationManager,
    private val scheduler: ModelScheduler,
    private val tools: ToolLoop,
    /** The exchange opened by this user message is stored, reply and all: archive it. */
    private val onExchangeStored: suspend (userMessageId: Long) -> Unit = {},
) {

    /**
     * Streams the reply to [userText]. [userMessageId] is the stored user message; only the
     * user's own words are stored, never the envelope the model actually receives.
     *
     * If the runtime refuses the turn as too long before anything was said, the conversation is
     * replanned conservatively and the turn sent once more. If the model's first answer is a tool
     * call the runtime can't read, the turn is sent once more with a fresh seed.
     *
     * [locked]: asked on the lock screen. The model sees none of the user's memory, and only the
     * lock screen's tools run ([ToolCatalog.LOCKED]); a request for anything else ends in
     * [TurnEvent.NeedsUnlock].
     */
    fun run(
        chatId: Long,
        userMessageId: Long,
        userText: String,
        attachment: PromptAttachment? = null,
        locked: Boolean = false,
        /** A screenshot of the screen the assistant was opened over ([com.local.assistant.assist.ScreenCapture]). */
        screen: String? = null,
        /** A voice message the model may hear ([com.local.assistant.llm.ContextWindow.heard]): [userText] is the phone's hearing of it. */
        heard: Boolean = false,
        /**
         * A document attached to the message. A question about it, a summary, or nothing said is
         * answered from it by the app's reader alone ([ToolLoop.readAttached]); a request to act
         * on it ("remind me to pay this bill") goes to the chat model with notes on it.
         */
        document: AttachedDocument? = null,
    ): Flow<TurnEvent> = flow {
        // Never over the lock screen; shown to the model only for a message about it ("share
        // this with Anjali"), though the tools may still send it or save it either way.
        val screenshot = screen?.takeIf { !locked && java.io.File(it).isFile }
        val showScreen = screenshot != null && QueryText.asksAboutScreen(userText)
        // A message that is nothing but a sum is answered here: the model would only misread its
        // digits (see NumberGrounding). The live conversation never saw the exchange, so it goes;
        // the next message rebuilds it with this one in its history. Not a spoken one: its digits
        // are the recogniser's, which wrote "two thousand four hundred and fifty" as "200450".
        if (attachment == null && document == null && !showScreen && !heard) {
            NumberGrounding.directAnswer(userText)?.let { answer ->
                TurnTimings.current?.conversation = "direct_answer"
                conversations.forget(chatId, "direct_answer")
                emit(TurnEvent.Text(answer))
                emit(TurnEvent.Done(null))
                return@flow
            }
        }
        // The plainest commands (an alarm at a time, a timer, the flashlight) are carried out here,
        // without the model, which took 2–3 s to write the one call and a line saying so. If the
        // call fails, the model is asked as usual; its own call of it gets the same answer.
        if (attachment == null && !showScreen) {
            InstantCommands.match(userText)?.takeIf { !locked || it.name in ToolCatalog.LOCKED }?.let { call ->
                val outcome = tools.runInstant(call, ToolContext(chatId, userMessageId, userText, locked, screenshot, heard = heard))
                if (outcome.chip != null && outcome.recordId != null) emit(TurnEvent.Memory(outcome.recordId, outcome.chip))
                @Suppress("UNCHECKED_CAST")
                val result = runCatching { ToolReplay.gson.fromJson(outcome.result, Map::class.java) as Map<String, Any?> }.getOrNull()
                val reply = result?.let { InstantCommands.reply(call, it, outcome.chip, java.time.ZonedDateTime.now()) }
                if (outcome.ok && reply != null) {
                    TurnTimings.current?.conversation = "instant"
                    emit(TurnEvent.Text(reply))
                    conversations.answeredWithoutModel(chatId, userMessageId, locked, userText, call.name, outcome.result, reply)
                    emit(TurnEvent.Done(null))
                    return@flow
                }
            }
        }
        val asked = android.os.SystemClock.elapsedRealtime()
        scheduler.runUser {
            // Waiting here means the model was busy: a background job that can't be stopped mid-prefill.
            TurnTimings.current?.waitedMs = android.os.SystemClock.elapsedRealtime() - asked
            if (document != null && locked) {
                emit(TurnEvent.NeedsUnlock)
                emit(TurnEvent.Done(null))
                return@runUser
            }
            val acting = document != null && QueryText.isCommand(userText)
            if (document != null && !acting) {
                TurnTimings.current?.conversation = "attached_document"
                tools.readAttached(ToolContext(chatId, userMessageId, userText, document = document)).collect { event ->
                    when (event) {
                        is LoopEvent.Text -> emit(TurnEvent.Text(event.delta))
                        is LoopEvent.Memory -> emit(TurnEvent.Memory(event.recordId, event.chip))
                        is LoopEvent.Step -> emit(TurnEvent.Step(event.label))
                        is LoopEvent.Done -> {
                            // Answered without the chat's conversation: rebuilt next turn from what is stored.
                            conversations.forget(chatId, "attached_document")
                            emit(TurnEvent.Done(null))
                        }
                        else -> Unit
                    }
                }
                return@runUser
            }
            // A request to act on it: notes on the document go to the model with the message.
            val notes = if (acting) {
                emit(TurnEvent.Step("Reading ${document!!.name}…"))
                tools.notesOnAttached(ToolContext(chatId, userMessageId, userText, document = document), userText)
            } else {
                null
            }
            val said = if (notes != null) "$userText\n\n$notes" else userText
            var retried = false
            var reseeded = false
            var rebuiltForPhotos = false
            var reloaded = false
            while (true) {
                val preparing = android.os.SystemClock.elapsedRealtime()
                val turn = conversations.prepareTurn(chatId, userMessageId, said, locked, screen = showScreen) { emit(TurnEvent.Step(it)) }
                android.util.Log.i("TurnTiming", "turn prepared in ${android.os.SystemClock.elapsedRealtime() - preparing} ms (${if (turn.fresh) "fresh" else "carried on"}, ${turn.recalledImages.size} images${if (turn.recording != null) ", the recording" else ""}, ${turn.envelope.tokens} tokens)")
                emit(TurnEvent.Step(ToolSteps.THINKING))
                var overflowed = false
                var unreadable = false
                var rebuild = false
                var garbled = false
                var garbledAfterActing = false
                var photosShown = false
                var acted = false
                val reply = StringBuilder()
                // How much of [reply] stands: the rest may be taken back (LoopEvent.Retract).
                var standing = 0
                // A saved image the message is about is looked at again, as if sent with it, and so
                // are photos from the gallery a question may be about.
                // A voice message's recording, when it goes with its words (ContextWindow.heard).
                val recording = turn.recording?.let { PromptAttachment(it, AttachmentKind.AUDIO) }
                val sent = listOfNotNull(screenshot?.takeIf { showScreen }?.let { PromptAttachment(it, AttachmentKind.IMAGE) }) +
                    (attachment?.let(::listOf) ?: recording?.let(::listOf) ?: turn.recalledImages.map { PromptAttachment(it, AttachmentKind.IMAGE) })
                val context = ToolContext(chatId, userMessageId, userText, locked, screenshot, heard = recording != null, earlierText = turn.earlierText, documentNotes = notes.orEmpty())
                tools.run(turn.session, turn.envelope.text, sent, context, sources = listOf(turn.prefix), canRebuild = !turn.fresh && !rebuiltForPhotos).collect { event ->
                    when (event) {
                        is LoopEvent.Text -> {
                            reply.append(event.delta)
                            if (!event.provisional) standing = reply.length
                            emit(TurnEvent.Text(event.delta, event.provisional))
                        }
                        LoopEvent.Confirm -> {
                            standing = reply.length
                            emit(TurnEvent.Confirm)
                        }
                        LoopEvent.Retract -> {
                            reply.setLength(standing)
                            emit(TurnEvent.Retract)
                        }
                        is LoopEvent.Memory -> {
                            if (event.chip.kind == MemoryChip.Kind.PHOTOS) photosShown = true
                            if (event.tool in ToolCatalog.ACTS) acted = true
                            emit(TurnEvent.Memory(event.recordId, event.chip))
                        }
                        is LoopEvent.Done -> {
                            if (event.staleConversation) {
                                // Finished without the runtime: rebuild from what is stored.
                                conversations.forget(chatId, "stale_turn")
                            } else if (event.changedPrefix) {
                                // The model saw the change in its own history, so it needs no rebuild.
                                conversations.acknowledgePrefixChange(chatId)
                            }
                            // A plain text turn is a clean sample of what text costs.
                            if (event.toolRounds == 0 && sent.isEmpty()) {
                                conversations.learnFromTurn(chatId, turn.envelope.text, reply.toString())
                            }
                            // Photos the message itself matched, under the reply, unless a photo
                            // search already put its own there, or the turn did something (an
                            // alarm, a call): a command, however put. "Set alarm at 4:30" heard as
                            // "sarlamat 4:30 p.m. today" got the card (2 Nov 2026). Nor under an
                            // answer from the user's messages or files.
                            val elsewhere = event.tools.any { it in ToolCatalog.ANSWERS_FROM_ELSEWHERE }
                            if (!photosShown && !acted && !elsewhere && turn.relatedPhotos.isNotEmpty()) {
                                val (recordId, chip) = tools.showRelatedPhotos(chatId, turn.relatedPhotos)
                                emit(TurnEvent.Memory(recordId, chip))
                            }
                            emit(TurnEvent.Done(event.stats))
                        }
                        LoopEvent.Overflow -> {
                            if (retried) error("That message is too long for the model's window.")
                            overflowed = true
                        }
                        LoopEvent.Unreadable -> {
                            if (reseeded) error("The model's answer couldn't be read. Please try again.")
                            unreadable = true
                        }
                        LoopEvent.NeedsUnlock -> emit(TurnEvent.NeedsUnlock)
                        LoopEvent.Rebuild -> rebuild = true
                        is LoopEvent.Garbled -> {
                            reply.setLength(0)
                            standing = 0
                            emit(TurnEvent.Restart)
                            garbled = true
                            garbledAfterActing = event.afterActing
                        }
                        is LoopEvent.Step -> emit(TurnEvent.Step(event.label))
                    }
                }
                when {
                    overflowed -> {
                        conversations.recoverFromOverflow(chatId)
                        retried = true
                    }
                    unreadable -> {
                        conversations.reseed(chatId)
                        reseeded = true
                    }
                    rebuild -> {
                        // The next prepareTurn rebuilds it from what is stored, tool calls included.
                        conversations.forget(chatId, "photo_retry")
                        rebuiltForPhotos = true
                    }
                    garbled && garbledAfterActing -> {
                        // Something was done already (its chip is under the reply): not asked
                        // again, so it isn't done twice. The next turn gets a fresh engine.
                        conversations.recoverFromGarble(chatId)
                        emit(TurnEvent.Text(GARBLED_AFTER_ACTING))
                        emit(TurnEvent.Done(null))
                        break
                    }
                    garbled && reloaded -> {
                        // Soup again on a fresh engine: said plainly, rather than shown.
                        conversations.forget(chatId, "garbled_again")
                        emit(TurnEvent.Text(GARBLED_AGAIN))
                        emit(TurnEvent.Done(null))
                        break
                    }
                    garbled -> {
                        // Once an engine gives soup it gives nothing else (14 Nov 2026): loaded
                        // again, and the chat rebuilt from what is stored, about 20 s.
                        emit(TurnEvent.Step(ToolSteps.RESTARTING))
                        conversations.recoverFromGarble(chatId)
                        reloaded = true
                    }
                    else -> break
                }
            }
        }
    }

    /**
     * Called once the reply (if any) is stored; [assistantMessageId] is null if nothing was. A
     * stopped or failed turn is archived too, with the part of the reply that exists.
     */
    suspend fun finish(chatId: Long, userMessageId: Long, assistantMessageId: Long?, completed: Boolean) {
        conversations.finishTurn(chatId, assistantMessageId, completed)
        // The backfill catches anything missed here, so a failure must not reach the chat screen.
        try {
            onExchangeStored(userMessageId)
        } catch (e: Exception) {
            Log.w("TurnRunner", "Could not archive message $userMessageId", e)
        }
    }

    /** The user is typing: background model work should get out of the way. */
    fun onUserActivity() = scheduler.onUserActivity()

    companion object {
        /** The reply came out garbled on an engine just loaded again, too ([com.local.assistant.memory.tools.GarbledText]). */
        const val GARBLED_AGAIN = "My reply came out garbled, even after restarting the model. Closing the app and opening it again should fix it."

        /** Garbled after a tool had acted: what was done shows under it. */
        const val GARBLED_AFTER_ACTING = "I did what's shown below, but my reply after it came out garbled, so I've restarted the model."
    }
}
