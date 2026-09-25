package com.local.assistant.memory.extract

import android.util.Log
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.llm.LlmBackend
import com.local.assistant.memory.db.AppStateDao
import com.local.assistant.memory.db.AppStateEntity
import com.local.assistant.memory.db.MaintenanceDao
import com.local.assistant.memory.prompt.TokenEstimator
import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.memory.work.ModelAccess
import com.local.assistant.memory.work.ModelScheduler
import java.time.ZoneId

/**
 * The nightly read of past messages for what the chat itself did not save.
 *
 * Works through user messages after a watermark kept in `app_state`, a batch at a time, and moves
 * the watermark only once a batch is routed — so a run stopped part-way (by the user, or by the
 * system's ten-minute limit on background work) picks up exactly where it left off, and running a
 * batch twice changes nothing (upserts and the duplicate checks see to that).
 *
 * Skipped: messages whose turn already made a tool call (the chat acted on them), trivial ones,
 * voice notes, and anything written while memory was paused.
 */
class Extractor(
    private val maintenance: MaintenanceDao,
    private val state: AppStateDao,
    private val backend: LlmBackend,
    private val router: ExtractionRouter,
    private val conversations: ModelAccess,
    private val scheduler: ModelScheduler,
    private val estimator: TokenEstimator,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Until caught up or [deadline]. True when there is nothing left to read. */
    suspend fun run(deadline: Long): Boolean {
        var total = ExtractionRouter.Outcome()
        try {
            while (clock() < deadline) {
                val after = watermark()
                val candidates = maintenance.userMessagesAfter(after, CANDIDATES)
                if (candidates.isEmpty()) return true
                val batch = mutableListOf<ExtractionInput>()
                var tokens = 0
                var readUpTo = after
                for (message in candidates) {
                    if (worthReading(message)) {
                        val previous = maintenance.previousTurn(message.chatId, message.id)
                            ?.takeIf { it.role == Role.ASSISTANT }?.text
                        val input = ExtractionInput(message, previous)
                        val cost = estimator.estimate(ExtractionPrompt.render(listOf(input), zone()))
                        if (batch.isNotEmpty() && (tokens + cost > INPUT_TOKENS || batch.size >= BATCH)) break
                        batch += input
                        tokens += cost
                    }
                    readUpTo = message.id
                }
                if (batch.isNotEmpty()) {
                    val items = extract(batch) ?: return false // The user needs the model.
                    total += router.route(items, batch.associate { it.message.id to it.message })
                }
                setWatermark(readUpTo)
            }
            return false
        } finally {
            if (total != ExtractionRouter.Outcome()) {
                Log.i(TAG, "Learned ${total.facts} facts, ${total.tasks} tasks, ${total.events} events; skipped ${total.skipped}")
            }
        }
    }

    /** Pushes the watermark past everything written so far: "Forget everything" never re-reads the past. */
    suspend fun skipToEnd(lastMessageId: Long) = setWatermark(lastMessageId)

    private suspend fun worthReading(message: MessageEntity): Boolean =
        message.attachmentKind != AttachmentKind.AUDIO &&
            message.text.isNotBlank() &&
            !QueryText.isTrivial(message.text) &&
            !maintenance.turnUsedTools(message.chatId, message.id)

    /**
     * The batch through the model, then once more with a firmer instruction if the reply does not
     * parse. Unconstrained on purpose: LiteRT-LM 0.17.1's JSON-schema decoding degraded this
     * model's output and then failed in the runtime ("compute_mask() called after stop") when
     * measured on the phone (MemoryEvalTest). Null when the model had to be given back to the
     * user; an empty list when it found nothing (or never made sense of it).
     */
    private suspend fun extract(batch: List<ExtractionInput>): List<ExtractedItem>? {
        val prompt = ExtractionPrompt.render(batch, zone())
        return scheduler.runBackground(ModelScheduler.Priority.NIGHTLY) {
            conversations.withModelFree {
                if (!backend.ensureReady()) return@withModelFree emptyList()
                backend.complete(ExtractionPrompt.SYSTEM, prompt, maxTokens = OUTPUT_TOKENS).let(ExtractionParser::parse)
                    ?: backend.complete(ExtractionPrompt.SYSTEM, "$prompt\n${ExtractionPrompt.RETRY}", maxTokens = OUTPUT_TOKENS)
                        .let(ExtractionParser::parse)
                    ?: emptyList<ExtractedItem>().also { Log.w(TAG, "Extraction reply did not parse twice; skipping the batch") }
            }
        }
    }

    private suspend fun watermark(): Long = state.get(KEY_WATERMARK)?.toLongOrNull() ?: 0L

    private suspend fun setWatermark(id: Long) = state.put(AppStateEntity(KEY_WATERMARK, id.toString()))

    private companion object {
        const val TAG = "Extractor"
        const val KEY_WATERMARK = "extract.watermark"

        /** Messages fetched per round; most are skipped cheaply before any reach the model. */
        const val CANDIDATES = 120

        /** Messages per model call. */
        const val BATCH = 35

        /**
         * The batch's share of the 8K window: the instructions with their examples take about a
         * thousand, the reply up to [OUTPUT_TOKENS].
         */
        const val INPUT_TOKENS = 3_000
        const val OUTPUT_TOKENS = 1_500
    }
}
