package com.local.assistant.memory.prompt

import android.util.Log
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.GenStats
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the chat UI hears about a turn. */
sealed interface TurnEvent {
    data class Text(val delta: String) : TurnEvent

    data class Done(val stats: GenStats?) : TurnEvent
}

/**
 * Runs one user turn: prepare the conversation, send the envelope, stream the reply.
 *
 * This is the seam the chat screen talks to. The user's message is stored before [run] is
 * called and the reply after it finishes, so a crash at any point loses nothing the user wrote;
 * [finish] then tells the memory layer how the turn ended.
 */
class TurnRunner(
    private val conversations: ConversationManager,
    private val scheduler: ModelScheduler,
    private val backend: LlmBackend,
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
                var spoke = false
                var overflowed = false
                turn.session.send(turn.envelope.text, attachment).collect { event ->
                    when (event) {
                        is GenEvent.TextDelta -> {
                            spoke = true
                            emit(TurnEvent.Text(event.text))
                        }
                        is GenEvent.Done -> emit(TurnEvent.Done(event.stats))
                        is GenEvent.Error -> {
                            if (spoke || retried || !backend.isContextOverflow(event.cause)) throw event.cause
                            overflowed = true
                        }
                        // No tools are declared yet, so the model has nothing to call.
                        is GenEvent.ToolCalls -> Log.w(TAG, "Ignoring unexpected tool calls: ${event.calls}")
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

    private companion object {
        const val TAG = "TurnRunner"
    }
}
