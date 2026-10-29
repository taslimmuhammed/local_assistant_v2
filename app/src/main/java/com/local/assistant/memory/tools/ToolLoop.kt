package com.local.assistant.memory.tools

import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.GenStats
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.ToolCall
import com.local.assistant.llm.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
 *
 * A reply that opens by saying something was done (sent, called, set …) with no tool run is
 * never shown: the model is asked once more ([UNBACKED_CLAIM]), and its answer shown instead —
 * or, if that says so too, [NOT_DONE].
 */
class ToolLoop(
    private val executor: ToolExecutor,
    private val maxRounds: Int,
    private val isOverflow: (Throwable) -> Boolean,
    private val isUnreadable: (Throwable) -> Boolean = { false },
    /** Reads a call the runtime rejected, when it is recoverable (`llm/ToolCallRepair`). */
    private val repair: (Throwable) -> ToolCall? = { null },
    /** A number in a reply was put right from what the model read ([ReplyGrounding]). */
    private val onNumberFixed: (wrote: String, source: String) -> Unit = { _, _ -> },
    /**
     * Whether replies' numbers need checking: not when the model copies digits exactly (its text
     * decoder in fp32), where a check could only change a number that was right.
     */
    private val groundNumbers: () -> Boolean = { true },
    /** Whether the model takes images; a result's picture is dropped when it doesn't. */
    private val seesImages: () -> Boolean = { true },
    /** A reply said something was done with no tool run, and was held back ([ActionClaims]). */
    private val onUnbackedClaim: (said: String) -> Unit = {},
) {

    /**
     * [sources]: what else the model has in front of it besides [envelope] — the prompt prefix
     * with its memory. Numbers the reply reads out of them, or out of a tool result, are checked
     * against them as it streams ([GroundedReply]).
     */
    fun run(
        session: ChatSession,
        envelope: String,
        attachment: PromptAttachment?,
        context: ToolContext,
        sources: List<String> = emptyList(),
    ): Flow<LoopEvent> = flow {
        var reply = session.send(envelope, attachment)
        var rounds = 0
        var spoke = false
        var changedPrefix = false
        // The model was asked again after saying it did something no tool did.
        var checked = false
        val grounding = ReplyGrounding(sources + envelope)
        val text = if (groundNumbers()) GroundedReply(grounding, onNumberFixed) else null

        /** Reply text the user may see now. */
        suspend fun FlowCollector<LoopEvent>.show(delta: String) {
            if (delta.isEmpty()) return
            spoke = true
            val shown = text?.push(delta) ?: delta
            if (shown.isNotEmpty()) emit(LoopEvent.Text(shown))
        }

        /** A pause in the reply (a tool call, the end): what was held back goes out now. */
        suspend fun FlowCollector<LoopEvent>.release() {
            val rest = text?.flush().orEmpty()
            if (rest.isNotEmpty()) emit(LoopEvent.Text(rest))
        }

        while (true) {
            val calls = mutableListOf<ToolCall>()
            var stats: GenStats? = null
            var overflowed = false
            var unreadable: Throwable? = null
            // Before any tool has run, the opening of the reply is held back until it is known
            // not to say something was done ("I've sent the email"): with no call behind it,
            // that is never shown, and the model is asked again. Once suspect, all of it is held.
            var held: StringBuilder? = if (rounds == 0) StringBuilder() else null
            var suspect = false
            reply.collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> {
                        val opening = held
                        if (opening == null) {
                            show(event.text)
                        } else {
                            opening.append(event.text)
                            val end = if (suspect) null else ActionClaims.openingEnd(opening)
                            when {
                                end == null -> Unit
                                ActionClaims.claims(opening.substring(0, end)) -> suspect = true
                                // Says it wrote a message: held until the message shows up.
                                ActionClaims.unshownDraft(opening) -> Unit
                                else -> {
                                    held = null
                                    show(opening.toString())
                                }
                            }
                        }
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
            val opening = held
            if (opening != null) {
                if (calls.isEmpty() && (suspect || ActionClaims.claims(opening) || ActionClaims.unshownDraft(opening))) {
                    // Said it did something no tool did: not shown, and the model asked again,
                    // once. The live conversation now holds a reply nobody saw, so it is
                    // rebuilt from what is stored next turn.
                    onUnbackedClaim(opening.toString())
                    if (!checked) {
                        checked = true
                        reply = session.send(UNBACKED_CLAIM, null)
                        continue
                    }
                    // Asked again and still saying it (the panel's "yes go ahead" by voice,
                    // 27 Oct 2026): what is true goes out instead.
                    show(NOT_DONE)
                } else {
                    show(opening.toString())
                }
            }
            release()
            if (calls.isEmpty() || rounds > maxRounds) {
                emit(LoopEvent.Done(stats, changedPrefix, rounds, staleConversation = checked))
                return@flow
            }

            rounds++
            val sums = mutableListOf<String>()
            val written = mutableListOf<String>()
            val results = calls.map { call ->
                if (rounds > maxRounds) {
                    ToolResult(call.name, LIMIT_REACHED)
                } else {
                    val outcome = executor.execute(call, context)
                    changedPrefix = changedPrefix || outcome.changedPrefix
                    if (outcome.chip != null && outcome.recordId != null) emit(LoopEvent.Memory(outcome.recordId, outcome.chip))
                    if (call.name == ToolCatalog.CALCULATE && outcome.ok) answerOf(outcome.result)?.let(sums::add)
                    outcome.reply?.let(written::add)
                    grounding.add(outcome.result)
                    ToolResult(call.name, outcome.result, outcome.image)
                }
            }
            // A photo question answered from the photo in a conversation of its own (PhotoReader),
            // which closed this one: the reply is that answer, and the chat is rebuilt next turn.
            if (written.isNotEmpty()) {
                emit(LoopEvent.Text((if (spoke) "\n" else "") + written.joinToString("\n")))
                emit(LoopEvent.Done(stats, changedPrefix, rounds, staleConversation = true))
                return@flow
            }
            // Only sums, all worked out: the reply is the result itself, written here. The model
            // would only restate it, and past token 2,048 it can't be trusted to copy the digits
            // (see NumberGrounding). The runtime never hears the results, so rebuild next turn.
            if (sums.isNotEmpty() && sums.size == calls.size) {
                emit(LoopEvent.Text((if (spoke) "\n" else "") + sums.joinToString("\n")))
                emit(LoopEvent.Done(stats, changedPrefix, rounds, staleConversation = true))
                return@flow
            }
            // A picture to look at (a found photo to read a question from) can't go in a tool
            // message, so the results go with it in a message of their own, marked as theirs.
            val image = results.firstNotNullOfOrNull { it.image }?.takeIf { seesImages() && java.io.File(it).isFile }
            reply = if (image != null) {
                session.send(toolResultsWithImage(results), PromptAttachment(image, com.local.assistant.data.db.AttachmentKind.IMAGE))
            } else {
                session.sendToolResults(results)
            }
        }
    }

    /** The results as text, for the message that carries a result's picture. */
    private fun toolResultsWithImage(results: List<ToolResult>): String = buildString {
        append("[Tool results, not the user's words; the image is the photo the result's note refers to]\n")
        results.forEach { append(it.name).append(": ").append(it.json).append('\n') }
    }

    private fun answerOf(json: String): String? = runCatching {
        val result = gson.fromJson(json, Map::class.java)
        Calculator.answer(result["expression"] as String, result["result"] as String)
    }.getOrNull()

    companion object {
        private val gson = com.google.gson.Gson()
        const val REPAIRED_REPLY = "Done."
        const val LIMIT_REACHED = """{"ok":false,"error":"Tool limit reached for this message. Answer the user now."}"""

        /** Shown when the model, asked again, still says it did what no tool did. */
        const val NOT_DONE = "Sorry, I didn't actually do that: nothing was sent, set or saved."

        /** Sent when a reply says something was done and no tool ran ([ActionClaims]). */
        const val UNBACKED_CLAIM = "[App check: no tool ran for your last reply, so nothing in it was done. " +
            "If one of your tools does what the user asked, call it now. If none does, tell the user plainly " +
            "that you can't do that, and offer what you can. What a tool did earlier in this chat still stands. " +
            "Don't mention this check.]"
    }
}
