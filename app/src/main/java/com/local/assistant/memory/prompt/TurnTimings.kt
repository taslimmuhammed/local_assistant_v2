package com.local.assistant.memory.prompt

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where each turn's time went, for working out what to make faster: one line in the log (tag
 * TurnTiming) and one row in `files/turn-timings.csv` per turn, the newest [KEEP_ROWS]. And the
 * model work around the turns that decides how fast they are (engine loads, conversations opened,
 * adopted, closed) in `files/model-events.csv`. Neither holds any words of a turn: only what was
 * done, why, how long it took, and the names of the tools that ran.
 *
 * Only one turn is ever in flight ([com.local.assistant.chat.ChatSender]), so the turn being
 * timed is held here rather than handed down through every layer. A part marked while no turn is
 * being timed (an eval driving the tool loop itself) marks nothing.
 */
object TurnTimings {

    /** One turn, from the user's send to the reply stored. Times are ms since [startedAt]. */
    class Turn internal constructor(
        /** "chat" or "panel". */
        val where: String,
        val startedAt: Long = SystemClock.elapsedRealtime(),
    ) {
        /** Waiting for the model to be free: another turn, or a background job finishing a prefill. */
        var waitedMs: Long? = null

        /** The engine had to be loaded for this turn, and how long that took. */
        var engineLoadMs: Long? = null

        /**
         * What the turn's conversation was: carried on, adopted (made ready beforehand), or opened
         * now, and then why it had to be ([ConversationManager]'s reasons).
         */
        var conversation: String = ""

        /** Opening (prefilling) the conversation on the send path. */
        var openMs: Long? = null

        /** Folding older turns into a summary on the send path ("Tidying up…"). */
        var compactMs: Long? = null

        var recallMs: Long? = null

        /** When the turn was handed to the model. */
        var sentAt: Long? = null

        /** The model's first token of the first round, and the first word the user saw. */
        var firstTokenAt: Long? = null
        var firstWordAt: Long? = null

        private val rounds = mutableListOf<String>()
        private val tools = mutableListOf<String>()

        /** Tokens per second of the last decode, as the runtime measured it. */
        var tokensPerSecond: Double? = null

        fun now(): Long = SystemClock.elapsedRealtime() - startedAt

        /**
         * One send to the model and its answer: [firstEventMs] after it was sent the first token or
         * call came, [answeredMs] after it was sent it was all in. [prefillTokens] is what the
         * runtime had to read for it, which says whether it read only the new message or more.
         */
        fun round(index: Int, firstEventMs: Long?, answeredMs: Long, stats: com.local.assistant.llm.GenStats?, calls: List<String>) {
            rounds += "r$index:first=${firstEventMs ?: "-"},done=$answeredMs,prefill=${stats?.prefillTokens ?: "-"}" +
                "@${stats?.prefillTokensPerSecond?.let { "%.0f".format(it) } ?: "-"}/s,decode=${stats?.decodeTokens ?: "-"}" +
                if (calls.isEmpty()) "" else ",calls=${calls.joinToString("+")}"
        }

        fun tool(name: String, ms: Long) {
            tools += "$name:$ms"
        }

        internal fun row(totalMs: Long, outcome: String): List<String> = listOf(
            LocalDateTime.now().format(STAMP),
            where,
            processAgeSeconds().toString(),
            conversation,
            waitedMs.orBlank(),
            engineLoadMs.orBlank(),
            openMs.orBlank(),
            compactMs.orBlank(),
            recallMs.orBlank(),
            sentAt.orBlank(),
            firstTokenAt.orBlank(),
            firstWordAt.orBlank(),
            totalMs.toString(),
            tokensPerSecond?.let { "%.1f".format(it) }.orEmpty(),
            rounds.joinToString(" "),
            tools.joinToString(" "),
            outcome,
        )

        private fun Long?.orBlank() = this?.toString().orEmpty()
    }

    /** Where the files go; nothing is written until it is set (as the app starts). */
    @Volatile
    var directory: File? = null

    /** The turn being timed, if one is. */
    @Volatile
    var current: Turn? = null
        private set

    fun begin(where: String): Turn = Turn(where).also { current = it }

    /** Marks the first word the user saw, once. */
    fun firstWord() {
        val turn = current ?: return
        if (turn.firstWordAt == null) turn.firstWordAt = turn.now()
    }

    /** The turn is over: [outcome] is "done", "stopped", "failed" or "unlock". */
    fun end(turn: Turn, outcome: String) {
        if (current === turn) current = null
        val total = turn.now()
        val row = turn.row(total, outcome)
        Log.i(TAG, HEADER.zip(row).filter { it.second.isNotEmpty() }.joinToString(", ") { "${it.first}=${it.second}" })
        append(TURNS_FILE, TURNS_HEADER, row)
    }

    /**
     * Model work outside a turn's own steps, or the cause of one: [kind] is what happened
     * ("engine_load", "opened", "adopted", "closed", "compacted"), [detail] why or what for.
     */
    fun event(kind: String, ms: Long? = null, detail: String = "") {
        Log.i(TAG, "event $kind${ms?.let { " in $it ms" }.orEmpty()}${if (detail.isNotEmpty()) " ($detail)" else ""}")
        append(EVENTS_FILE, EVENTS_HEADER, listOf(LocalDateTime.now().format(STAMP), processAgeSeconds().toString(), kind, ms?.toString().orEmpty(), detail))
    }

    private val lock = Any()

    private fun append(name: String, header: String, row: List<String>) {
        val dir = directory ?: return
        runCatching {
            synchronized(lock) {
                val file = File(dir, name)
                if (!file.isFile) file.writeText("$header\n")
                file.appendText(row.joinToString(",") { it.replace(',', ';') } + "\n")
                // Rolled over in a batch, not every turn: rewriting the file is the costly part.
                val lines = file.readLines()
                if (lines.size > KEEP_ROWS + ROLL_SLACK + 1) {
                    file.writeText((listOf(header) + lines.drop(1).takeLast(KEEP_ROWS)).joinToString("\n", postfix = "\n"))
                }
            }
        }.onFailure { Log.w(TAG, "Could not write $name", it) }
    }

    /** How long this process has been running: short after the system killed and restarted the app. */
    private fun processAgeSeconds(): Long = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000

    private const val TAG = "TurnTiming"
    const val TURNS_FILE = "turn-timings.csv"
    const val EVENTS_FILE = "model-events.csv"
    private const val KEEP_ROWS = 500
    private const val ROLL_SLACK = 50
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val HEADER = listOf(
        "at", "where", "process_age_s", "conversation", "waited_ms", "engine_load_ms", "open_ms", "compact_ms", "recall_ms",
        "sent_ms", "first_token_ms", "first_word_ms", "total_ms", "tok_s", "rounds", "tools", "outcome",
    )
    private val TURNS_HEADER = HEADER.joinToString(",")
    private const val EVENTS_HEADER = "at,process_age_s,kind,ms,detail"
}
