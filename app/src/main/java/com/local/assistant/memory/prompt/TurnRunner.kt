package com.local.assistant.memory.prompt

import com.local.assistant.llm.GenStats
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.memory.tools.LoopEvent
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolLoop
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the chat UI hears about a turn. */
sealed interface TurnEvent {
    data class Text(val delta: String) : TurnEvent

    /** Something was saved, scheduled or forgotten; its chip is also stored with the chat. */
    data class Memory(val recordId: Long, val chip: MemoryChip) : TurnEvent

    data class Done(val stats: GenStats?) : TurnEvent
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
) {

    /**
     * Streams the reply to [userText]. [userMessageId] is the stored user message; only the
     * user's own words are stored, never the envelope the model actually receives.
     *
     * If the runtime refuses the turn as too long before anything was said, the conversation is
     * replanned conservatively and the turn sent once more.
     */
    fun run(
        chatId: Long,
        userMessageId: Long,
        userText: String,
        attachment: PromptAttachment? = null,
    ): Flow<TurnEvent> = flow {
        scheduler.runUser {
            var retried = false
            while (true) {
                val turn = conversations.prepareTurn(chatId, userMessageId, userText)
                var overflowed = false
                val reply = StringBuilder()
                tools.run(turn.session, turn.envelope.text, attachment, ToolContext(chatId, userMessageId)).collect { event ->
                    when (event) {
                        is LoopEvent.Text -> {
                            reply.append(event.delta)
                            emit(TurnEvent.Text(event.delta))
                        }
                        is LoopEvent.Memory -> emit(TurnEvent.Memory(event.recordId, event.chip))
                        is LoopEvent.Done -> {
                            // The model saw the change in its own history, so it needs no rebuild.
                            if (event.changedPrefix) conversations.acknowledgePrefixChange(chatId)
                            // A plain text turn is a clean sample of what text costs.
                            if (event.toolRounds == 0 && attachment == null) {
                                conversations.learnFromTurn(chatId, turn.envelope.text, reply.toString())
                            }
                            emit(TurnEvent.Done(event.stats))
                        }
                        LoopEvent.Overflow -> {
                            if (retried) error("That message is too long for the model's window.")
                            overflowed = true
                        }
                    }
                }
                if (!overflowed) break
                conversations.recoverFromOverflow(chatId)
                retried = true
            }
        }
    }

    /** Called once the reply (if any) is stored; [assistantMessageId] is null if nothing was. */
    suspend fun finish(chatId: Long, assistantMessageId: Long?, completed: Boolean) =
        conversations.finishTurn(chatId, assistantMessageId, completed)

    /** The user is typing: background model work should get out of the way. */
    fun onUserActivity() = scheduler.onUserActivity()
}
