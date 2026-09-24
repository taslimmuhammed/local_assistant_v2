package com.local.assistant.memory.prompt

import android.util.Log
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.BackendCapabilities
import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.LlmService
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.retrieval.Recall
import com.local.assistant.memory.retrieval.Retriever
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Keeps one live conversation — the one for the chat in use — and decides when to rebuild it.
 *
 * A conversation holds its system prefix and history in the KV cache, so each turn only has to
 * prefill the new message. Rebuilding means prefilling everything again, so it happens only at
 * natural boundaries:
 *
 * - the first turn in a chat, or after the process or engine restarted;
 * - when the conversation no longer matches what is stored (a turn was interrupted);
 * - when the prefix has changed in a way the model has not seen (see [finishTurn]), and then only
 *   right after a reply, while the model is idle — never on the send path;
 * - when the next turn would not fit, as a last resort before sending.
 *
 * Only one conversation is ever open. Measured on the target phone (EngineProbeTest), a second
 * conversation on the same engine costs about 1.1 GB more at 16K, prefills five times slower
 * than the first, and leaves around 2 GB resident after both are closed. So a rebuild closes
 * the old conversation first, and once started it runs to completion rather than being thrown
 * away half-prefilled.
 */
class ConversationManager(
    private val backend: LlmBackend,
    private val chats: ChatRepository,
    private val memory: MemoryRepository,
    private val settings: SettingsStore,
    private val scheduler: ModelScheduler,
    private val scope: CoroutineScope,
    private val estimator: MeasuredTokenEstimator,
    /** OpenAPI declarations for the tools the model may call; part of the stable prefix. */
    private val tools: List<String> = emptyList(),
    /** Per-turn recall of facts and archived exchanges; none when null. */
    private val retriever: Retriever? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A turn ready to send. */
    data class PreparedTurn(val session: ChatSession, val envelope: Envelope, val budget: MemoryBudget)

    private class Live(
        val chatId: Long,
        val session: ChatSession,
        val budget: MemoryBudget,
        /**
         * The prefix the model knows about: the one it was built with, updated when a tool result
         * in its own history tells it about a change. A stored prefix that differs from this is a
         * change the model has not seen, and is what makes a rebuild worthwhile.
         */
        var acknowledgedPrefix: String,
        /** The newest stored message this conversation contains. */
        var lastMessageId: Long,
        /**
         * The oldest message of this chat the conversation holds verbatim; everything from here on
         * is already in front of the model, so recall leaves it out.
         */
        val windowStartId: Long,
        var lastNowShown: ZonedDateTime? = null,
        /** The runtime's count just before the turn in flight, to measure what the turn cost. */
        var tokensBeforeTurn: Int? = null,
    )

    /** Plain turns' text and real cost, gathered until there is enough to learn from. */
    private var turnSample = TurnSample()

    private data class TurnSample(val counts: ScriptCounts = ScriptCounts.ZERO, val messages: Int = 0, val tokens: Int = 0)

    private val mutex = Mutex()
    private var live: Live? = null
    private var maintenance: Job? = null

    private val _droppedFromContext = MutableStateFlow(0)

    /** How many stored messages of the current chat did not fit its conversation. */
    val droppedFromContext: StateFlow<Int> = _droppedFromContext.asStateFlow()

    private val _contextUsage = MutableStateFlow<LlmService.ContextUsage?>(null)

    /** Real context consumption of the current chat's conversation, as the runtime counts it. */
    val contextUsage: StateFlow<LlmService.ContextUsage?> = _contextUsage.asStateFlow()

    /**
     * Readies the conversation for [chatId] and builds this turn's envelope. [userMessageId] is
     * the user's message, already stored; everything before it is history.
     */
    suspend fun prepareTurn(chatId: Long, userMessageId: Long, userText: String): PreparedTurn = mutex.withLock {
        // A rebuild still waiting for a quiet spell is moot now; one already running has finished
        // by the time we hold the lock.
        maintenance?.cancel()
        check(backend.ensureReady()) { "The model is not loaded." }
        val capabilities = checkNotNull(backend.capabilities) { "The model is not loaded." }
        val budget = MemoryBudget.forWindow(capabilities.maxContextTokens)
        val assembler = assemblerFor(budget, capabilities)
        val history = historyFor(chatId, userMessageId)
        val now = now()

        val reusable = live?.takeIf {
            it.chatId == chatId &&
                it.lastMessageId == (history.lastOrNull()?.id ?: 0L) &&
                it.budget == budget &&
                it.session.isAlive
        }
        var current: Live = reusable ?: run {
            _contextUsage.value = null
            rebuild(chatId, history, budget, capabilities)
        }

        val recall = recall(chatId, userText, current.windowStartId, budget)
        val inputs = EnvelopeInputs(
            now = now,
            showNow = assembler.shouldShowNow(current.lastNowShown, now),
            facts = recall.facts,
            snippets = recall.snippets,
            userText = userText,
        )
        val liveTokens = current.session.tokenCount() ?: 0
        var envelope = assembler.fitEnvelope(liveTokens, inputs)
        if (envelope == null) {
            // Too full to take even this turn. Rebuild smaller now rather than overflow; rolling
            // compaction (which summarises instead of dropping) keeps this rare.
            Log.i(TAG, "Conversation at $liveTokens tokens cannot take the next turn; rebuilding")
            current = rebuild(chatId, history, budget, capabilities, next = inputs)
            envelope = assembler.buildEnvelope(inputs.copy(showNow = true))
        }
        if (envelope.showedNow) current.lastNowShown = now
        current.tokensBeforeTurn = current.session.tokenCount()
        PreparedTurn(current.session, envelope, budget)
    }

    /** Recall is best-effort: a failure costs this turn its memory lines, never the turn. */
    private suspend fun recall(chatId: Long, userText: String, windowStartId: Long, budget: MemoryBudget): Recall {
        val retriever = retriever ?: return Recall.NONE
        return try {
            retriever.recall(chatId, userText, windowStartId, budget.maxEnvelopeFacts, budget.maxSnippets)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recall failed; sending without it", e)
            Recall.NONE
        }
    }

    /**
     * Records how the turn ended. A turn that did not complete, or whose reply was not stored,
     * leaves the conversation out of step with the database, so it is dropped and rebuilt from
     * what was stored next time.
     */
    suspend fun finishTurn(chatId: Long, lastStoredMessageId: Long?, completed: Boolean) {
        mutex.withLock {
            val current = live?.takeIf { it.chatId == chatId } ?: return
            if (!completed || lastStoredMessageId == null) {
                close(current)
                return
            }
            current.lastMessageId = lastStoredMessageId
            publishUsage(current)
        }
        scheduleMaintenance()
    }

    /**
     * The model has just seen a change to what the prefix renders — typically a tool result in
     * its own history — so a rebuild for that change alone would buy nothing.
     */
    suspend fun acknowledgePrefixChange(chatId: Long) = mutex.withLock {
        val current = live?.takeIf { it.chatId == chatId } ?: return@withLock
        val capabilities = backend.capabilities ?: return@withLock
        current.acknowledgedPrefix = assemblerFor(current.budget, capabilities)
            .buildPrefix(prefixInputs(chatId, current.budget)).text
    }

    /**
     * Something changed what the prefix would say, outside the conversation — an undo, an edit,
     * a reminder done from its notification. Rebuild once the chat has gone quiet.
     */
    fun prefixMayHaveChanged() = scheduleMaintenance()

    /** The user switched chats: the meter and notice should stop describing the old one. */
    fun onChatSelected(chatId: Long?) {
        if (live?.chatId != chatId) {
            _contextUsage.value = null
            _droppedFromContext.value = 0
        }
    }

    /**
     * Learns from a finished plain turn — no tools, no attachments — what text really costs: the
     * runtime's count went up by exactly the envelope and the reply. Turns are pooled until there
     * is enough text to say something (see [MeasuredTokenEstimator.MIN_LATIN_CHARS]).
     */
    suspend fun learnFromTurn(chatId: Long, envelope: String, reply: String) = mutex.withLock {
        val current = live?.takeIf { it.chatId == chatId } ?: return@withLock
        val before = current.tokensBeforeTurn ?: return@withLock
        val after = current.session.tokenCount() ?: return@withLock
        if (after <= before) return@withLock
        val counts = HeuristicTokenEstimator.countScripts(envelope) + HeuristicTokenEstimator.countScripts(reply)
        turnSample = TurnSample(turnSample.counts + counts, turnSample.messages + 2, turnSample.tokens + (after - before))
        if (turnSample.counts.latin >= MeasuredTokenEstimator.MIN_LATIN_CHARS) {
            estimator.observe(turnSample.counts, turnSample.messages, turnSample.tokens)
            Log.i(TAG, "Learned from turns: ${turnSample.tokens} tokens for ${turnSample.counts.total} chars; Latin rate now ${"%.2f".format(estimator.latinCharsPerToken)}")
            turnSample = TurnSample()
        }
    }

    /**
     * The runtime refused a turn as too long for the window. The estimate that planned the
     * conversation was too hopeful: forget what was measured, and drop the conversation so the
     * next attempt is planned again, conservatively.
     */
    suspend fun recoverFromOverflow(chatId: Long) = mutex.withLock {
        Log.w(TAG, "Runtime rejected a turn as too long at ${estimator.latinCharsPerToken} chars/token")
        estimator.onOverflow()
        live?.takeIf { it.chatId == chatId }?.let(::close)
    }

    /** Drops the conversation for [chatId], e.g. after its messages were deleted. */
    suspend fun forget(chatId: Long) = mutex.withLock {
        live?.takeIf { it.chatId == chatId }?.let(::close)
    }

    /**
     * Once the chat has been quiet for a while after a reply: rebuild if the stored prefix has
     * changed in a way the model has not seen.
     *
     * A rebuild prefills for several seconds and cannot be interrupted, so it waits for a quiet
     * spell and gives way to typing or a new turn up until it starts. Once started it finishes,
     * and the next turn uses the result.
     */
    private fun scheduleMaintenance() {
        maintenance?.cancel()
        maintenance = scope.launch {
            delay(IDLE_BEFORE_REBUILD_MS)
            scheduler.runBackground(ModelScheduler.Priority.COMPACTION) {
                mutex.withLock {
                    val current = live ?: return@withLock
                    val capabilities = backend.capabilities ?: return@withLock
                    val prefix = assemblerFor(current.budget, capabilities)
                        .buildPrefix(prefixInputs(current.chatId, current.budget))
                    if (prefix.text == current.acknowledgedPrefix) return@withLock
                    Log.i(TAG, "Prefix changed outside the conversation; rebuilding while idle")
                    val history = historyFor(current.chatId, Long.MAX_VALUE)
                    withContext(NonCancellable) {
                        rebuild(current.chatId, history, current.budget, capabilities)
                    }
                }
            }
        }
    }

    /**
     * Opens a conversation for [chatId] and makes it the live one. Must hold [mutex].
     *
     * [next], when given, is the turn about to be sent, so the plan leaves room for it.
     *
     * The prefix and history are always prefilled at creation. The next reply would have to
     * prefill them anyway, so it costs nothing extra, and it means the runtime's count straight
     * after opening is exactly what was planned — which is how the estimator learns.
     */
    private suspend fun rebuild(
        chatId: Long,
        history: List<MessageEntity>,
        budget: MemoryBudget,
        capabilities: BackendCapabilities,
        next: EnvelopeInputs = EnvelopeInputs(now = now(), showNow = true, userText = ""),
    ): Live {
        live?.let(::close)
        val prefixInputs = prefixInputs(chatId, budget)

        val declared = tools.takeIf { capabilities.tools }.orEmpty()
        // The runtime renders declarations in its own template format; the JSON is a fair,
        // conservative stand-in for what they cost.
        val toolTokens = if (declared.isEmpty()) 0 else estimator.estimate(declared.joinToString("\n"))

        suspend fun open(): Pair<PromptPlan, ChatSession> {
            val plan = assemblerFor(budget, capabilities).plan(prefixInputs, history, next, toolTokens)
            if (plan.shed.isNotEmpty()) Log.i(TAG, "Shed ${plan.shed} to fit ${plan.totalTokens} tokens")
            val spec = ChatSpec(
                systemPrefix = plan.prefix.text,
                history = plan.history.messages,
                toolDeclarations = declared,
                maxOutputTokens = minOf(settings.maxOutputTokens, budget.generationReserve),
                prefillOnOpen = true,
            )
            return plan to backend.openChat(spec)
        }

        val (plan, session) = try {
            open()
        } catch (e: Exception) {
            if (!backend.isContextOverflow(e)) throw e
            // Planned with a rate that proved too hopeful: start again from the safe one.
            Log.w(TAG, "Rebuild overflowed at ${estimator.latinCharsPerToken} chars/token; replanning", e)
            estimator.onOverflow()
            open()
        }
        learnFrom(plan, session, toolTokens)
        _droppedFromContext.value = plan.history.droppedCount
        val lastId = history.lastOrNull()?.id ?: 0L
        return Live(
            chatId = chatId,
            session = session,
            budget = budget,
            acknowledgedPrefix = plan.prefix.text,
            lastMessageId = lastId,
            // With nothing kept verbatim, the window starts at the next message.
            windowStartId = plan.history.messages.firstOrNull()?.id ?: (lastId + 1),
        ).also { live = it }
    }

    /** Tells the estimator what the runtime counted for what was planned. */
    private fun learnFrom(plan: PromptPlan, session: ChatSession, toolTokens: Int) {
        // Declarations are rendered by the runtime's template, not as the text planned here, so a
        // conversation carrying them says nothing clean about the Latin rate.
        if (toolTokens > 0) return
        val real = session.tokenCount() ?: return
        // Images and audio cost tokens that have nothing to do with text.
        if (plan.history.messages.any { it.attachmentKind != null }) return
        val counts = plan.history.messages.fold(HeuristicTokenEstimator.countScripts(plan.prefix.text)) { sum, message ->
            sum + HeuristicTokenEstimator.countScripts(message.text)
        }
        val planned = plan.prefix.tokens + plan.history.tokens
        estimator.observe(counts, messages = plan.history.messages.size + 1, realTokens = real)
        Log.i(TAG, "Rebuilt: planned $planned, runtime counted $real; Latin rate now ${"%.2f".format(estimator.latinCharsPerToken)}")
    }

    private suspend fun prefixInputs(chatId: Long, budget: MemoryBudget): PrefixInputs {
        val chat = chats.chat(chatId)
        val summary = chat?.rollingSummary
            ?.let { SummaryBlock(SummaryBlock.Kind.EARLIER_IN_CHAT, it) }
            ?: memory.latestSessionSummary()?.let { SummaryBlock(SummaryBlock.Kind.LAST_SESSION, it) }
        return PrefixInputs(
            instructions = Instructions.render(settings.systemPrompt, withTools = tools.isNotEmpty() && backend.capabilities?.tools == true),
            coreFacts = memory.coreFacts(),
            summary = summary,
            agendaDate = now().toLocalDate(),
            agenda = memory.agenda(clock(), budget.agendaItems),
        )
    }

    /** Stored turns before [beforeId], minus any that a rolling summary already covers. */
    private suspend fun historyFor(chatId: Long, beforeId: Long): List<MessageEntity> {
        val summarisedUpTo = chats.chat(chatId)?.rollingUptoMessageId ?: 0L
        return chats.turnsBefore(chatId, beforeId).filter { it.id > summarisedUpTo }
    }

    private fun publishUsage(current: Live) {
        val window = backend.capabilities?.maxContextTokens ?: current.budget.contextTokens
        _contextUsage.value = current.session.tokenCount()?.let { LlmService.ContextUsage(it, window) }
    }

    private fun close(current: Live) {
        runCatching { current.session.close() }
        if (live === current) live = null
    }

    private fun assemblerFor(budget: MemoryBudget, capabilities: BackendCapabilities) =
        PromptAssembler(budget, estimator, zone(), capabilities.visionTokensPerImage)

    private fun now(): ZonedDateTime = Instant.ofEpochMilli(clock()).atZone(zone())

    private companion object {
        const val TAG = "ConversationManager"

        /** How long a chat must be quiet after a reply before an idle rebuild may start. */
        const val IDLE_BEFORE_REBUILD_MS = 30_000L
    }
}
