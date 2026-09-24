package com.local.assistant.memory.tools

import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.GenStats
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.ToolCall
import com.local.assistant.llm.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What one turn produced, as it happens. */
sealed interface LoopEvent {
    data class Text(val delta: String) : LoopEvent

    /** A tool changed something worth showing under the reply. */
    data class Memory(val recordId: Long, val chip: MemoryChip) : LoopEvent

    /** [toolRounds] is how many rounds of tool calls the turn took; 0 for a plain reply. */
    data class Done(val stats: GenStats?, val changedPrefix: Boolean, val toolRounds: Int) : LoopEvent

    /** The first send was refused as too long for the window, before anything was said. */
    data object Overflow : LoopEvent
}

/**
 * One user turn with manual tool calling: send, and while the model answers with tool calls,
 * run them and send their results back — at most [maxRounds] rounds. Past that, the calls get a
 * result telling the model to answer instead, and a model that still will not is cut off.
 *
 * The runtime is never allowed to run tools itself: going through [executor] is what validates
 * the arguments, dedupes repeats, records the call and makes undo possible.
 */
class ToolLoop(
    private val executor: ToolExecutor,
    private val maxRounds: Int,
    private val isOverflow: (Throwable) -> Boolean,
) {

    fun run(
        session: ChatSession,
        envelope: String,
        attachment: PromptAttachment?,
        context: ToolContext,
    ): Flow<LoopEvent> = flow {
        var reply = session.send(envelope, attachment)
        var rounds = 0
        var spoke = false
        var changedPrefix = false

        while (true) {
            val calls = mutableListOf<ToolCall>()
            var stats: GenStats? = null
            var overflowed = false
            reply.collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> {
                        spoke = true
                        emit(LoopEvent.Text(event.text))
                    }
                    is GenEvent.ToolCalls -> calls += event.calls
                    is GenEvent.Done -> stats = event.stats
                    is GenEvent.Error ->
                        if (!spoke && rounds == 0 && isOverflow(event.cause)) overflowed = true else throw event.cause
                }
            }
            if (overflowed) {
                emit(LoopEvent.Overflow)
                return@flow
            }
            if (calls.isEmpty() || rounds > maxRounds) {
                emit(LoopEvent.Done(stats, changedPrefix, rounds))
                return@flow
            }

            rounds++
            val results = calls.map { call ->
                if (rounds > maxRounds) {
                    ToolResult(call.name, LIMIT_REACHED)
                } else {
                    val outcome = executor.execute(call, context)
                    changedPrefix = changedPrefix || outcome.changedPrefix
                    if (outcome.chip != null && outcome.recordId != null) emit(LoopEvent.Memory(outcome.recordId, outcome.chip))
                    ToolResult(call.name, outcome.result)
                }
            }
            reply = session.sendToolResults(results)
        }
    }

    companion object {
        const val LIMIT_REACHED = """{"ok":false,"error":"Tool limit reached for this message. Answer the user now."}"""
    }
}
