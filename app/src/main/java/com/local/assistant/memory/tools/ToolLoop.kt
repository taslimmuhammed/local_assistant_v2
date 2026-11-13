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
    /**
     * [provisional]: shown as it comes, before it is known to stand: an opening that may yet
     * say something was done that no tool did, or a reply after a handoff that may say it was
     * sent. Not spoken until [Confirm]; taken back by [Retract].
     */
    data class Text(val delta: String, val provisional: Boolean = false) : LoopEvent

    /** All the text shown so far stands. */
    data object Confirm : LoopEvent

    /** The text shown since the last [Confirm] is withdrawn: it said something untrue. */
    data object Retract : LoopEvent

    /** A tool ([tool], its name) changed something worth showing under the reply. */
    data class Memory(val recordId: Long, val chip: MemoryChip, val tool: String? = null) : LoopEvent

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

    /**
     * On the lock screen, the model called a tool that isn't allowed there ([ToolCatalog.LOCKED]):
     * nothing ran, and the turn ends here so the user can unlock and ask again with everything.
     * [Done] follows, with a stale conversation.
     */
    data object NeedsUnlock : LoopEvent

    /** What is being done now, for the user to see ([ToolSteps]). */
    data class Step(val label: String) : LoopEvent

    /**
     * Asked to see photos, the model answered with no search in a conversation it had been
     * having: try the turn again in one rebuilt from what is stored, tool calls and all, where it
     * searches ([ToolLoop.run]'s canRebuild).
     */
    data object Rebuild : LoopEvent
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
 * taken back as soon as that is clear ([LoopEvent.Retract]), and never spoken: the model is asked
 * once more ([UNBACKED_CLAIM]), and its answer shown instead — or, if that says so too,
 * [NOT_DONE]. Until the opening is known to stand, it streams as [LoopEvent.Text.provisional]:
 * holding it back cost a second or more before the first word (up to 200 characters, ~4 s at
 * 11 tokens a second), and the user chose seeing words at once over never seeing a false claim
 * (13 Nov 2026).
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
    /** Whether find_photos is declared to the model now: photo search set up. */
    private val photosAvailable: () -> Boolean = { false },
) {

    /**
     * [sources]: what else the model has in front of it besides [envelope] — the prompt prefix
     * with its memory. Numbers the reply reads out of them, or out of a tool result, are checked
     * against them as it streams ([GroundedReply]).
     */
    fun run(
        session: ChatSession,
        envelope: String,
        /** Files that go with the message: the user's own, or a saved image and photos recalled for it. */
        attachments: List<PromptAttachment>,
        context: ToolContext,
        sources: List<String> = emptyList(),
        /** Whether [LoopEvent.Rebuild] may be asked for: not when [session] was just built for this turn. */
        canRebuild: Boolean = false,
    ): Flow<LoopEvent> = flow {
        val started = android.os.SystemClock.elapsedRealtime()
        /** Where a turn's time goes, in the log (tag TurnTiming): no words of it, only what and when. */
        fun mark(what: String) = android.util.Log.i(TIMING, "$what at ${android.os.SystemClock.elapsedRealtime() - started} ms")
        val timing = com.local.assistant.memory.prompt.TurnTimings.current
        timing?.sentAt = timing?.now()
        var reply = session.send(envelope, attachments)
        var rounds = 0
        // Text that stands has been shown; [pending]: text shown that may yet be taken back.
        var spoke = false
        var pending = false
        // Whether any of it is words, not just a newline: the shown text that stands, and what is pending.
        var wroteWords = false
        val pendingText = StringBuilder()
        var changedPrefix = false
        // The model was asked again after saying it did something no tool did.
        var checked = false
        // The model was asked to answer after writing nothing once the results came back ([EMPTY_REPLY]).
        var nudged = false
        val grounding = ReplyGrounding(sources + envelope)
        // Saying photos were found counts as a claim only with none in front of the model: with a
        // recalled photo sent along, "I found a photo of your certificate" is true.
        val seesPhotos = attachments.any { it.kind == com.local.assistant.data.db.AttachmentKind.IMAGE }
        fun claimed(text: CharSequence) = ActionClaims.claims(text) || (!seesPhotos && ActionClaims.foundPhotos(text))
        // Asked to see photos: only find_photos shows them, so a reply with no search isn't shown
        // until the model has been asked once more to search ([PHOTO_CHECK]).
        val mustSearch = photosAvailable() && !context.locked && com.local.assistant.memory.retrieval.QueryText.asksForPhotos(context.userText)
        var searchAsked = false
        // Health data read this turn: a number put right in the reply may be a reading, and those are never logged.
        var readHealth = false
        val text = if (groundNumbers()) GroundedReply(grounding) { wrote, source -> if (!readHealth) onNumberFixed(wrote, source) } else null
        // Calls held back for a lookup's answer ([waiting]) and not yet asked for again.
        val unanswered = mutableListOf<ToolCall>()
        // What calls and messages got ready for the user to send: the reply after them must not
        // say they were sent ([ActionClaims.handedOff]).
        val ready = mutableListOf<String>()
        // Whether something was done this turn, and whether any call failed: see the end.
        var acted = false
        var failed = false
        fun ran(call: ToolCall, outcome: ToolExecutor.Outcome) {
            if (call.name == ToolCatalog.HEALTH) readHealth = true
            if (!outcome.ok) failed = true else if (call.name in ToolCatalog.ACTS) acted = true
        }

        /** Reply text the user may see now; [provisional] until it is known to stand. */
        suspend fun FlowCollector<LoopEvent>.show(delta: String, provisional: Boolean = false) {
            if (delta.isEmpty()) return
            if (provisional) {
                pending = true
                pendingText.append(delta)
            } else {
                spoke = true
                if (delta.isNotBlank()) wroteWords = true
            }
            val shown = text?.push(delta) ?: delta
            if (shown.isNotEmpty()) emit(LoopEvent.Text(shown, provisional))
        }

        /** What was shown provisionally stands. */
        suspend fun FlowCollector<LoopEvent>.confirm() {
            if (!pending) return
            pending = false
            spoke = true
            if (pendingText.isNotBlank()) wroteWords = true
            pendingText.clear()
            text?.keep()
            emit(LoopEvent.Confirm)
        }

        /** What was shown provisionally is taken back. */
        suspend fun FlowCollector<LoopEvent>.retract() {
            if (!pending) return
            pending = false
            pendingText.clear()
            text?.rewind()
            emit(LoopEvent.Retract)
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
            // Before any tool has run, the opening of the reply is checked until it is known not
            // to say something was done ("I've sent the email"): it streams provisionally, and
            // with no call behind such a claim it is taken back and the model asked again. Once
            // suspect, the rest of it is not shown.
            var held: StringBuilder? = if (rounds == 0) StringBuilder() else null
            var suspect = false
            // After a call or message was got ready, the reply is checked whole: "I have sent the
            // message to Anjali" came 4 times in 6 after send_message (MultiStepEvalTest).
            val afterHandoff = if (ready.isNotEmpty()) StringBuilder() else null
            val roundSent = android.os.SystemClock.elapsedRealtime()
            var firstEvent: Long? = null
            reply.collect { event ->
                if (firstEvent == null && (event is GenEvent.TextDelta || event is GenEvent.ToolCalls)) {
                    firstEvent = android.os.SystemClock.elapsedRealtime() - roundSent
                    if (rounds == 0 && !checked) timing?.firstTokenAt = timing?.now()
                }
                when (event) {
                    is GenEvent.TextDelta -> {
                        val opening = held
                        if (afterHandoff != null) {
                            afterHandoff.append(event.text)
                            show(event.text, provisional = true)
                        } else if (opening == null) {
                            show(event.text)
                        } else {
                            opening.append(event.text)
                            if (!suspect) show(event.text, provisional = true)
                            val end = if (suspect || mustSearch) null else ActionClaims.openingEnd(opening)
                            when {
                                end == null -> Unit
                                claimed(opening.substring(0, end)) -> {
                                    suspect = true
                                    retract()
                                }
                                // Says it wrote a message: unsettled until the message shows up.
                                ActionClaims.unshownDraft(opening) -> Unit
                                else -> {
                                    held = null
                                    confirm()
                                }
                            }
                        }
                    }
                    is GenEvent.ToolCalls -> calls += event.calls
                    is GenEvent.Done -> stats = event.stats
                    is GenEvent.Error -> when {
                        // Nothing shown and nothing done yet, so the turn can be tried again.
                        spoke || rounds > 0 -> {
                            retract()
                            throw event.cause
                        }
                        isOverflow(event.cause) -> overflowed = true
                        isUnreadable(event.cause) -> unreadable = event.cause
                        else -> throw event.cause
                    }
                }
            }
            mark("model answered (round $rounds: ${calls.joinToString { it.name }.ifEmpty { "no tool" }}, ${held?.length ?: 0} characters held)")
            timing?.round(rounds, firstEvent, android.os.SystemClock.elapsedRealtime() - roundSent, stats, calls.map { it.name })
            stats?.tokensPerSecond?.let { timing?.tokensPerSecond = it }
            if (overflowed) {
                retract()
                emit(LoopEvent.Overflow)
                return@flow
            }
            unreadable?.let { cause ->
                // Whatever came before the unreadable call isn't the answer.
                retract()
                // A call that only slipped in its format, to a tool that acts rather than looks
                // something up, is carried out as meant and confirmed without the model. What the
                // model should have said next is not needed: the chip shows what was done.
                val fixed = repair(cause)?.takeIf { it.name in ToolCatalog.ACTS }
                if (fixed != null && context.locked && fixed.name !in ToolCatalog.LOCKED) {
                    emit(LoopEvent.NeedsUnlock)
                    emit(LoopEvent.Done(null, changedPrefix = false, toolRounds = 0, staleConversation = true))
                    return@flow
                }
                val outcome = fixed?.let { executor.execute(it, context) }
                if (outcome == null || !outcome.ok) {
                    emit(LoopEvent.Unreadable)
                    return@flow
                }
                if (outcome.chip != null && outcome.recordId != null) emit(LoopEvent.Memory(outcome.recordId, outcome.chip, fixed.name))
                emit(LoopEvent.Text(REPAIRED_REPLY))
                emit(LoopEvent.Done(null, outcome.changedPrefix, toolRounds = 1, staleConversation = true))
                return@flow
            }
            val opening = held
            if (opening != null && calls.isEmpty() && mustSearch && !searchAsked) {
                // "Could you show all the photos of wedding", right after a search for them,
                // answered with no search, and again after the general check (1 Nov 2026).
                retract()
                onUnbackedClaim(opening.toString())
                // Asked again in the same conversation, it repeated itself 3 times in 3; in one
                // rebuilt from what is stored, it searched 6 times in 6 (PhotoRequestsEvalTest).
                if (canRebuild) {
                    emit(LoopEvent.Rebuild)
                    return@flow
                }
                searchAsked = true
                checked = true
                reply = session.send(PHOTO_CHECK, null)
                continue
            }
            if (opening != null) {
                if (calls.isEmpty() && (suspect || claimed(opening) || ActionClaims.unshownDraft(opening))) {
                    // Said it did something no tool did: taken back, and the model asked again,
                    // once. The live conversation now holds a reply that isn't stored, so it is
                    // rebuilt from what is stored next turn.
                    retract()
                    onUnbackedClaim(opening.toString())
                    if (!checked) {
                        checked = true
                        reply = session.send(UNBACKED_CLAIM, null)
                        continue
                    }
                    // Asked again and still saying it (the panel's "yes go ahead" by voice,
                    // 27 Oct 2026): what is true goes out instead.
                    show(if (mustSearch) NOT_SEARCHED else NOT_DONE)
                } else if (suspect) {
                    // Said it did something, and a call here does it: it stands after all.
                    show(opening.toString())
                } else {
                    confirm()
                }
            }
            afterHandoff?.let {
                val said = it.toString()
                val fair = ActionClaims.handedOff(said, ready)
                if (fair == said) {
                    confirm()
                } else {
                    retract()
                    show(fair)
                }
            }
            release()
            // Nothing written once the tools' results came back, and nothing done that says it
            // for itself: an empty reply. Through the app's backend on runtime 0.19.1, with a long
            // prompt, it came 2 times in 2 (ToolResponseProbeTest, 13 Nov 2026). Asked once to
            // answer from them; the live conversation then holds turns that aren't stored.
            if (calls.isEmpty() && rounds > 0 && !wroteWords && !nudged && !(acted && !failed)) {
                nudged = true
                mark("empty reply after the results, asked again")
                reply = session.send(EMPTY_REPLY, null)
                continue
            }
            if (calls.isEmpty() || rounds > maxRounds) {
                // Held for a lookup's answer, and not asked for again: done as first asked rather
                // than not at all. The runtime never sees it, so the chat is rebuilt next turn.
                val leftOver = unanswered.isNotEmpty()
                for (call in unanswered) {
                    val running = android.os.SystemClock.elapsedRealtime()
                    val outcome = executor.execute(call, context)
                    timing?.tool(call.name, android.os.SystemClock.elapsedRealtime() - running)
                    mark("${call.name} ran, as first asked")
                    ran(call, outcome)
                    changedPrefix = changedPrefix || outcome.changedPrefix
                    if (outcome.chip != null && outcome.recordId != null) emit(LoopEvent.Memory(outcome.recordId, outcome.chip, call.name))
                }
                // All done, and the model said nothing (after take_screenshot in the panel, 4 Nov
                // 2026): the chip alone would leave the spoken panel silent, so it's said here.
                if (!spoke && acted && !failed) {
                    emit(LoopEvent.Text(ready.joinToString(" ").ifEmpty { REPAIRED_REPLY }))
                } else if (nudged && !wroteWords) {
                    // Asked again and still nothing: better a plain line than an empty bubble.
                    show(NO_REPLY)
                }
                emit(LoopEvent.Done(stats, changedPrefix, rounds, staleConversation = checked || leftOver || nudged))
                return@flow
            }

            // On the lock screen, one call that needs more than the lock screen allows stops the
            // turn before anything runs: what the user asked goes again once they unlock.
            if (context.locked && calls.any { it.name !in ToolCatalog.LOCKED }) {
                emit(LoopEvent.NeedsUnlock)
                emit(LoopEvent.Done(stats, changedPrefix, rounds, staleConversation = true))
                return@flow
            }

            rounds++
            val sums = mutableListOf<String>()
            val written = mutableListOf<String>()
            val wait = waiting(calls)
            // Asked for again: the new call stands for the one held (and is held again if it must wait again).
            unanswered.removeAll { held -> calls.any { it.name == held.name } }
            unanswered += wait.map { calls[it] }
            // What stays in this app goes before what opens another: once WhatsApp or the dialer
            // is in front, Android won't let this app open the clock app to set an alarm.
            val order = calls.indices.sortedBy { if (calls[it].name in ToolCatalog.HANDOFFS) 1 else 0 }
            val answered = arrayOfNulls<ToolResult>(calls.size)
            val ranNow = mutableListOf<Pair<ToolCall, Boolean>>()
            for (i in order) {
                val call = calls[i]
                answered[i] = if (rounds > maxRounds) {
                    ToolResult(call.name, LIMIT_REACHED)
                } else if (i in wait) {
                    ToolResult(call.name, waitFor(call, calls))
                } else {
                    emit(LoopEvent.Step(ToolSteps.of(call, context.userText)))
                    val running = android.os.SystemClock.elapsedRealtime()
                    val outcome = executor.execute(call, context)
                    timing?.tool(call.name, android.os.SystemClock.elapsedRealtime() - running)
                    mark("${call.name} ran")
                    ran(call, outcome)
                    ranNow += call to outcome.ok
                    changedPrefix = changedPrefix || outcome.changedPrefix
                    if (outcome.chip != null && outcome.recordId != null) emit(LoopEvent.Memory(outcome.recordId, outcome.chip, call.name))
                    if (call.name == ToolCatalog.CALCULATE && outcome.ok) answerOf(outcome.result)?.let(sums::add)
                    outcome.reply?.let(written::add)
                    outcome.ready?.takeIf { outcome.ok }?.let(ready::add)
                    grounding.add(outcome.result)
                    ToolResult(call.name, outcome.result, outcome.image)
                }
            }
            val results = answered.requireNoNulls().toList()
            // Done, and said so: the model takes a while to read the results and start.
            if (ranNow.isNotEmpty()) emit(LoopEvent.Step(ToolSteps.done(ranNow.map { it.first }, ranNow.map { it.second })))
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

    /** See [ToolExecutor.showRelatedPhotos]. */
    suspend fun showRelatedPhotos(chatId: Long, mediaIds: List<Long>): Pair<Long, MemoryChip> = executor.showRelatedPhotos(chatId, mediaIds)

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

        /**
         * Which of [calls] wait a round for the lookups beside them: those whose words an answer
         * could go into, a reminder's title or a message, while a sum, web search or memory search
         * is in the same round. Asked again, the model writes them with the answer in.
         */
        fun waiting(calls: List<ToolCall>): Set<Int> {
            if (calls.none { it.name in ToolCatalog.LOOKUPS }) return emptySet()
            return calls.indices.filter { calls[it].name in ToolCatalog.TAKES_ANSWERS }.toSet()
        }

        /** What a call held back for the lookups beside it is told ([waiting]). */
        fun waitFor(call: ToolCall, calls: List<ToolCall>): String {
            val lookups = calls.map { it.name }.filter { it in ToolCatalog.LOOKUPS }.distinct().joinToString(" and ")
            return gson.toJson(
                mapOf(
                    "ok" to false,
                    "error" to "Not done yet: it waits for $lookups. Call ${call.name} again now, with what $lookups returned in it where it's needed.",
                ),
            )
        }

        private const val TIMING = "TurnTiming"
        const val LIMIT_REACHED = """{"ok":false,"error":"Tool limit reached for this message. Answer the user now."}"""

        /** Shown when the model, asked again, still says it did what no tool did. */
        const val NOT_DONE = "Sorry, I didn't actually do that: nothing was sent, set or saved."

        /** Shown when, asked to see photos, the model still answered with no search. */
        const val NOT_SEARCHED = "Sorry, I didn't search your photos for that. Please ask me again."

        /** Sent when the user asked to see photos and the reply came with no search. */
        const val PHOTO_CHECK = "[App check: they asked to see photos, and only a find_photos call shows them: none ran for your last " +
            "reply, so nothing was shown. Call find_photos now for what they asked, even if an earlier search was like it. " +
            "Don't mention this check.]"

        /** Sent when, after the tools' results, the reply had no words. */
        const val EMPTY_REPLY = "[App check: your last reply was empty. Answer the user now from the tool results above. " +
            "Don't mention this check.]"

        /** Shown when, asked again, the reply still had no words. */
        const val NO_REPLY = "Sorry, I couldn't finish that. Please ask me again."

        /** Sent when a reply says something was done and no tool ran ([ActionClaims]). */
        const val UNBACKED_CLAIM = "[App check: no tool ran for your last reply, so nothing in it was done. " +
            "If one of your tools does what the user asked, call it now. If none does, tell the user plainly " +
            "that you can't do that, and offer what you can. What a tool did earlier in this chat still stands. " +
            "Don't mention this check.]"
    }
}
