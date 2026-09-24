package com.local.assistant.memory.prompt

/** Where the measured rate survives restarts. Keyed by model, since the tokenizer is the model's. */
interface TokenRateStore {
    fun load(modelKey: String): Double?
    fun save(modelKey: String, latinCharsPerToken: Double)
}

/**
 * [HeuristicTokenEstimator] with its Latin rate learned from the runtime's own counts.
 *
 * The heuristic charges Latin text at 3.5 characters a token; Gemma 4 was measured nearer 5–6,
 * so on a small window the plain heuristic would leave half of it unused. After every rebuild the
 * runtime reports exactly how many tokens the conversation holds, and that — against the
 * characters that went in — gives the real rate.
 *
 * Only the Latin rate is learned, and only from conversations that are almost entirely Latin, so
 * one number is not smeared across scripts: Indic text keeps the heuristic's dense rate however
 * much English has been seen. Every assumption in the arithmetic leans the safe way, the result
 * is held between the heuristic and [MAX_LATIN_CHARS_PER_TOKEN], and the heuristic's 10% margin
 * still sits on top. The rate tightens at once when text turns out denser, relaxes only
 * gradually, and is thrown away entirely if the runtime ever rejects a prompt as too long.
 */
class MeasuredTokenEstimator(
    private val store: TokenRateStore,
    private val modelKey: () -> String?,
) : TokenEstimator {

    @Volatile
    private var rate = HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN

    @Volatile
    private var loadedFor: String? = null

    /** The Latin rate in use right now, for logging. */
    val latinCharsPerToken: Double get() = currentRate()

    override fun estimate(text: String): Int =
        HeuristicTokenEstimator.estimate(HeuristicTokenEstimator.countScripts(text), currentRate())

    /**
     * Learns from a conversation the runtime has just counted: [counts] covers the text of every
     * message in it, [messages] is how many there were, [realTokens] the runtime's count.
     * Conversations carrying images or audio must not be passed in; their cost is not text.
     */
    fun observe(counts: ScriptCounts, messages: Int, realTokens: Int) {
        if (counts.latin < MIN_LATIN_CHARS || counts.latin < counts.total * MIN_LATIN_SHARE) return

        // Attribute as few tokens as plausible to everything that is not Latin text, so as many
        // as possible are left for it: that makes the Latin rate come out low, which is safe.
        val latinTokens = realTokens -
            counts.other / SPARSE_OTHER_CHARS_PER_TOKEN -
            messages * MIN_TEMPLATE_TOKENS_PER_MESSAGE
        if (latinTokens <= 0) return

        val observed = (counts.latin / latinTokens)
            .coerceIn(HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN, MAX_LATIN_CHARS_PER_TOKEN)
        val current = currentRate()
        val next = if (observed < current) observed else current + (observed - current) * RELAX
        rate = next
        modelKey()?.let { store.save(it, next) }
    }

    /** The runtime rejected a prompt as too long: the learned rate was too hopeful. */
    fun onOverflow() {
        rate = HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN
        modelKey()?.let { store.save(it, rate) }
    }

    private fun currentRate(): Double {
        val key = modelKey()
        if (key != loadedFor) {
            loadedFor = key
            rate = key?.let(store::load) ?: HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN
        }
        return rate
    }

    companion object {
        /** Never assume Latin text packs tighter than this, whatever is measured. */
        const val MAX_LATIN_CHARS_PER_TOKEN = 6.0

        /** Too little text says more about the template than about the tokenizer. */
        const val MIN_LATIN_CHARS = 2_000

        const val MIN_LATIN_SHARE = 0.9

        /** How far each observation that would loosen the rate moves it. */
        const val RELAX = 0.3

        /** Non-Latin text charged sparsely here, the opposite of the estimate, to stay safe. */
        private const val SPARSE_OTHER_CHARS_PER_TOKEN = 4.0

        /** Likewise the chat template's markers: fewer here means a more careful Latin rate. */
        private const val MIN_TEMPLATE_TOKENS_PER_MESSAGE = 2
    }
}
