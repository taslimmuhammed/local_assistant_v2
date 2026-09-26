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

    /**
     * [toolRounds] is how many rounds of tool calls the turn took; 0 for a plain reply.
     * [staleConversation]: the turn was finished without the runtime (a repaired call), so the
     * live conversation no longer matches what is stored and has to be rebuilt.
     */
    data class Done(
        val stats: GenStats?,
        val changedPrefix: Boolean,
        val toolRounds: Int,
        val staleConversation: Boolean = false,
    ) : LoopEvent

    /** The first send was refused as too long for the window, before anything was said. */
    data object Overflow : LoopEvent

    /** The model's first answer was a tool call the runtime couldn't read, before anything was said. */
    data object Unreadable : LoopEvent
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
    private val isUnreadable: (Throwable) -> Boolean = { false },
    /** Reads a call the runtime rejected, when it is recoverable (`llm/ToolCallRepair`). */
    private val repair: (Throwable) -> ToolCall? = { null },
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
            var unreadable: Throwable? = null
            reply.collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> {
                        spoke = true
                        emit(LoopEvent.Text(event.text))
                    }
                    is GenEvent.ToolCalls -> calls += event.calls
                    is GenEvent.Done -> stats = event.stats
                    is GenEvent.Error -> when {
                        // Nothing shown and nothing done yet, so the turn can be tried again.
                        spoke || rounds > 0 -> throw event.cause
                        isOverflow(event.cause) -> overflowed = true
                        isUnreadable(event.cause) -> unreadable = event.cause
                        else -> throw event.cause
                    }
                }
            }
            if (overflowed) {
                emit(LoopEvent.Overflow)
                return@flow
            }
            unreadable?.let { cause ->
                // A call that only slipped in its format, to a tool that acts rather than looks
                // something up, is carried out as meant and confirmed without the model. What the
                // model should have said next is not needed: the chip shows what was done.
                val fixed = repair(cause)?.takeIf { it.name in ToolCatalog.WRITES || it.name in ToolCatalog.DEVICE_ACTIONS }
                val outcome = fixed?.let { executor.execute(it, context) }
                if (outcome == null || !outcome.ok) {
                    emit(LoopEvent.Unreadable)
                    return@flow
                }
                if (outcome.chip != null && outcome.recordId != null) emit(LoopEvent.Memory(outcome.recordId, outcome.chip))
                emit(LoopEvent.Text(REPAIRED_REPLY))
                emit(LoopEvent.Done(null, outcome.changedPrefix, toolRounds = 1, staleConversation = true))
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
        const val REPAIRED_REPLY = "Done."
        const val LIMIT_REACHED = """{"ok":false,"error":"Tool limit reached for this message. Answer the user now."}"""
    }
}
