package com.local.assistant.files

import android.util.Log
import com.local.assistant.away.BriefTriage
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.files.DocumentText.Read
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.prompt.TokenEstimator
import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.memory.work.ModelAccess
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.ln

/**
 * Answers a question about a document, or summarises it, in short conversations of its own with
 * no tools (so the model reads at full speed), and writes the reply itself, as [com.local.assistant.gallery.PhotoReader]
 * does for photos.
 *
 * A document that fits is read in one go. For a question about a longer one, the parts with the
 * question's words in them are read. A summary of a longer one is made part by part (at most
 * [MAX_SUMMARY_PARTS] parts), then put together; the reply says how much was read. A PDF of page
 * pictures has its first pages looked at by the vision model, as does a short one with little text.
 *
 * The chat's live conversation is closed for this ([ModelAccess.withModelFree]): never two at once.
 */
class DocumentReader(
    private val backend: LlmBackend,
    private val model: ModelAccess,
    private val estimator: TokenEstimator = HeuristicTokenEstimator,
) {

    /**
     * The reply to [question] about the document [name], read as [read]; a summary when there's no
     * question or it asks for one. [onStep] hears how far it is ("Reading part 2 of 5").
     */
    suspend fun answer(name: String, read: Read, question: String?, onStep: (String) -> Unit = {}): String = when (read) {
        is Read.Unreadable -> couldNotRead(name, read.why)
        is Read.Scanned -> model.withModelFree {
            guarded(name) {
                when (val usable = readable(read)) {
                    is Read.Text -> text(name, usable, question, onStep)
                    else -> scanned(name, read, question, onStep)
                }
            }
        }
        is Read.Text -> model.withModelFree { guarded(name) { text(name, read, question, onStep) } }
    }

    /**
     * What of [read] the loaded model can take: a PDF's pages when it can look at pictures, else
     * the text layer it has (null when it has none). Known only once the model is loaded.
     */
    private fun readable(read: Read): Read? = when {
        read !is Read.Scanned -> read
        (backend.capabilities?.visionTokensPerImage ?: 0) > 0 -> read
        else -> read.text
    }

    /**
     * What an attached document is and says, in a few lines, for the chat model to act on with
     * its tools ("remind me to pay this bill"); null when it can't be read.
     */
    suspend fun gist(name: String, read: Read, request: String, onStep: (String) -> Unit = {}): String? {
        if (read is Read.Unreadable) return null
        return model.withModelFree {
            try {
                if (!backend.ensureReady()) return@withModelFree null
                val usable = readable(read) ?: return@withModelFree null
                if (usable is Read.Scanned) {
                    // A bill or letter saved as a picture: its pages looked at, as for a question.
                    onStep("Looking at the pages of $name")
                    return@withModelFree lookAt(name, usable, Plan.gistAsk(request)).replace("**", "").trim().takeIf { it.isNotEmpty() }
                }
                usable as Read.Text
                onStep("Reading $name")
                val parts = Plan.chunks(usable.text, estimator, QUESTION_PART_TOKENS)
                val picked = Plan.pick(parts, request, estimator, ONE_PASS_TOKENS)
                val input = Plan.documentHeader(name, usable) + Plan.joined(parts, picked) + "\n\n" + Plan.gistAsk(request)
                complete(input, GIST_TOKENS).trim().takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A document's gist failed", e)
                null
            }
        }
    }

    private suspend fun guarded(name: String, block: suspend () -> String): String = try {
        if (!backend.ensureReady()) couldNotReadNow(name) else block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Reading a document failed", e)
        couldNotReadNow(name)
    }

    private suspend fun text(name: String, read: Read.Text, question: String?, onStep: (String) -> Unit): String {
        val summary = Plan.isSummary(question)
        val head = Plan.documentHeader(name, read)
        val whole = estimator.estimate(read.text)
        if (whole <= ONE_PASS_TOKENS) {
            onStep("Reading $name")
            val said = complete(head + read.text + "\n\n" + if (summary) Plan.summaryAsk() else Plan.questionAsk(question!!), ANSWER_TOKENS)
            return Plan.reply(said, Plan.note(read, read.text, cutShort = read.cut))
        }
        if (!summary) {
            val parts = Plan.chunks(read.text, estimator, QUESTION_PART_TOKENS)
            val picked = Plan.pick(parts, question!!, estimator, ONE_PASS_TOKENS)
            onStep("Reading the parts of $name about that")
            val chosen = Plan.joined(parts, picked)
            val said = complete(head + chosen + "\n\n" + Plan.questionAsk(question), ANSWER_TOKENS)
            return Plan.reply(said, Plan.note(read, chosen, cutShort = true))
        }
        val parts = Plan.chunks(read.text, estimator, SUMMARY_PART_TOKENS)
        val picked = Plan.spread(parts.size, MAX_SUMMARY_PARTS)
        val notes = ArrayList<String>()
        for ((i, index) in picked.withIndex()) {
            onStep("Reading part ${i + 1} of ${picked.size} of $name")
            val note = complete(Plan.partInput(name, parts[index], index, parts.size), PART_NOTE_TOKENS).trim()
            if (note.isNotEmpty()) notes += note
        }
        if (notes.isEmpty()) return couldNotReadNow(name)
        onStep("Putting the summary together")
        val said = complete(Plan.combineInput(name, notes), ANSWER_TOKENS)
        return Plan.reply(said, Plan.note(read, picked.joinToString("\n") { parts[it] }, cutShort = picked.size < parts.size || read.cut))
    }

    private suspend fun scanned(name: String, read: Read.Scanned, question: String?, onStep: (String) -> Unit): String {
        if ((backend.capabilities?.visionTokensPerImage ?: 0) <= 0) return "$name is pictures of pages, with no text in it, and I can't look at pictures on this phone."
        onStep("Looking at the pages of $name")
        val reply = lookAt(name, read, if (Plan.isSummary(question)) Plan.summaryAsk() else Plan.questionAsk(question!!))
        val note = if (read.pageCount > read.pages.size) "I looked at the first ${read.pages.size} of its ${read.pageCount} pages, which are pictures with no text." else null
        return Plan.reply(reply.replace("**", ""), note)
    }

    /** [read]'s pages shown to the vision model one by one, and [ask] put with the last. */
    private suspend fun lookAt(name: String, read: Read.Scanned, question: String): String =
        backend.openChat(SCANNED_SPEC).use { session ->
            suspend fun ask(prompt: String, page: java.io.File): String {
                val out = StringBuilder()
                session.send(prompt, PromptAttachment(page.path, AttachmentKind.IMAGE)).collect { event ->
                    when (event) {
                        is GenEvent.TextDelta -> out.append(event.text)
                        is GenEvent.Error -> throw event.cause
                        else -> Unit
                    }
                }
                return out.toString().trim()
            }
            val pages = read.pages
            for ((i, page) in pages.withIndex()) {
                if (i == pages.lastIndex) break
                ask("Page ${i + 1} of the document \"$name\". Read it; reply with only: OK", page)
            }
            ask("Page ${pages.size} of the document \"$name\". From the page${if (pages.size > 1) "s" else ""} you've seen: $question", pages.last())
        }

    private suspend fun complete(input: String, maxTokens: Int): String =
        backend.complete(SYSTEM, input, maxTokens = maxTokens, sampling = Sampling.BACKGROUND).replace("**", "").trim()

    /** The prompts and the arithmetic of what to read, kept pure for tests. */
    object Plan {

        /** No question, or one asking what the document says as a whole. */
        fun isSummary(question: String?): Boolean {
            val q = question?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (q.isEmpty()) return true
            return SUMMARY.containsMatchIn(q)
        }

        /**
         * [text] in parts of about [tokens] each, cut between paragraphs where it can, else
         * between lines, else between words.
         */
        fun chunks(text: String, estimator: TokenEstimator, tokens: Int): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var currentTokens = 0
            fun flush() {
                if (current.isNotBlank()) parts += current.toString().trim()
                current.setLength(0)
                currentTokens = 0
            }
            for (piece in pieces(text, estimator, tokens)) {
                val cost = estimator.estimate(piece)
                if (currentTokens + cost > tokens && current.isNotEmpty()) flush()
                current.append(piece)
                currentTokens += cost
            }
            flush()
            return parts
        }

        /** Paragraphs (with their blank line), each no bigger than [tokens]. */
        private fun pieces(text: String, estimator: TokenEstimator, tokens: Int): Sequence<String> = sequence {
            for (paragraph in text.split(Regex("(?<=\n\n)"))) {
                if (estimator.estimate(paragraph) <= tokens) {
                    yield(paragraph)
                    continue
                }
                for (line in paragraph.split(Regex("(?<=\n)"))) {
                    if (estimator.estimate(line) <= tokens) {
                        yield(line)
                        continue
                    }
                    // One very long line: by words.
                    val words = StringBuilder()
                    for (word in line.split(Regex("(?<= )"))) {
                        if (estimator.estimate(words.toString() + word) > tokens && words.isNotEmpty()) {
                            yield(words.toString())
                            words.setLength(0)
                        }
                        words.append(word)
                    }
                    if (words.isNotEmpty()) yield(words.toString())
                }
            }
        }

        /**
         * The parts most about [question], up to [budget] tokens, in the document's order. Each
         * question word scores by how rare it is among the parts (a word in every part says
         * nothing); the first part, which usually says what the document is, wins a tie. With no
         * word found anywhere, the document's start.
         */
        fun pick(parts: List<String>, question: String, estimator: TokenEstimator, budget: Int): List<Int> {
            if (parts.isEmpty()) return emptyList()
            val terms = QueryText.terms(question).filter { it !in QUESTION_WORDS }.map(::stem).distinct()
            val lowered = parts.map { it.lowercase(Locale.ROOT) }
            val scores = DoubleArray(parts.size)
            for (term in terms) {
                val counts = lowered.map { occurrences(it, term) }
                val having = counts.count { it > 0 }
                if (having == 0) continue
                val idf = ln(1.0 + parts.size.toDouble() / having)
                counts.forEachIndexed { i, c -> scores[i] += idf * minOf(c, 3) }
            }
            scores[0] += 0.01
            val order = if (scores.all { it < 0.5 }) parts.indices.toList() else parts.indices.sortedByDescending { scores[it] }
            val chosen = ArrayList<Int>()
            var used = 0
            for (i in order) {
                val cost = estimator.estimate(parts[i])
                if (used + cost > budget) continue
                chosen += i
                used += cost
            }
            return chosen.sorted()
        }

        /** [count] indexes spread evenly over [size], the first always among them. */
        fun spread(size: Int, count: Int): List<Int> {
            if (size <= count) return (0 until size).toList()
            return (0 until count).map { (it.toLong() * size / count).toInt() }.distinct()
        }

        fun joined(parts: List<String>, picked: List<Int>): String =
            if (picked.size == parts.size) parts.joinToString("\n\n") else picked.joinToString("\n\n[…]\n\n") { parts[it] }

        fun documentHeader(name: String, read: Read.Text): String {
            val size = if (read.parts != null && read.unit != null) ", ${read.parts} ${read.unit}${if (read.parts == 1) "" else "s"}" else ""
            return "The document \"$name\"$size:\n\n"
        }

        /**
         * The language is named, not "the language of the question": asked in English about a
         * spreadsheet, the model answered in Spanish (14 Nov 2026), as the away brief's drafts
         * once came out in the next chat's language.
         */
        fun questionAsk(question: String): String {
            val language = BriefTriage.languageOf(question) ?: "the language of the question"
            return "The user asks: \"$question\"\nAnswer from the document only, in a few sentences, in $language. " +
                "Copy names, numbers, dates and amounts exactly as written. If the document doesn't say, say so in a few words."
        }

        fun summaryAsk(): String =
            "Summarise this document for the user in at most 8 short bullet points: what it is, who it's from or between, " +
                "the key facts, amounts and dates, and anything they need to do. Copy numbers exactly as written."

        fun gistAsk(request: String): String =
            "In at most 100 words, note what this document is and the facts in it an assistant needs to do this for the user: \"$request\". " +
                "Names, amounts and dates exactly as written. Reply with only the notes."

        fun partInput(name: String, part: String, index: Int, of: Int): String =
            "Part ${index + 1} of $of of the document \"$name\":\n\n$part\n\n" +
                "Note the key facts of this part (names, amounts, dates, obligations, decisions) in at most 5 short bullet points. Reply with only the bullets."

        fun combineInput(name: String, notes: List<String>): String =
            "Notes on the parts of the document \"$name\", in order:\n\n" + notes.joinToString("\n\n") + "\n\n" +
                "From these notes, " + summaryAsk().replaceFirstChar { it.lowercase(Locale.ROOT) }

        /**
         * How much of the document [read] was read when only [readText] of it was: by its pages
         * when they're marked, else as a share. Null when all of it was.
         */
        fun note(read: Read.Text, readText: String, cutShort: Boolean): String? {
            if (!cutShort) return null
            val pages = Regex("\\[(Page|Slide) (\\d+)]").findAll(readText).map { it.groupValues[2] }.distinct().count()
            val total = read.parts
            if (pages > 0 && total != null && read.unit != null && pages < total) return "I read $pages of its $total ${read.unit}s for this."
            val share = (100.0 * readText.length / read.text.length).toInt().coerceIn(1, 99)
            return if (share < 95) "I read about $share% of it for this." else null
        }

        fun reply(said: String, note: String?): String {
            val answer = said.trim()
            if (answer.isEmpty()) return COULDNT_READ_NOW
            return if (note == null) answer else "$answer\n\n($note)"
        }

        private fun occurrences(text: String, term: String): Int {
            var count = 0
            var from = 0
            while (true) {
                val at = text.indexOf(term, from)
                if (at < 0) return count
                count++
                from = at + term.length
            }
        }

        /** "payments" and "payment" alike: the start of a long word. */
        private fun stem(word: String): String = if (word.length > 6) word.take(word.length - 2) else word

        private val SUMMARY = Regex(
            "\\b(summar(y|ise|ize|ised|ized)|sum (it )?up|tl;?dr|gist|overview|key points|main points|outline|" +
                "what('s| is| does)( this| it| the \\w+)? (about|say)|brief me|explain (it|this|the (document|file|pdf)))\\b",
        )

        /** Words in a question that aren't what it's about. */
        private val QUESTION_WORDS = setOf("document", "file", "pdf", "doc", "says", "say", "mentioned", "mention", "written", "find", "check", "read")
    }

    companion object {
        private const val TAG = "DocumentReader"

        private const val SYSTEM = "You read documents for the user and answer from them. Use only what the document says; never add facts it doesn't have."

        private val SCANNED_SPEC = ChatSpec(systemPrefix = SYSTEM, history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = 500)

        /**
         * A document read in one go, by the heuristic's count (which overcounts English by about
         * half): with the question and a 500-token reply it stays inside the 8,192-token window
         * even for text the heuristic counts right.
         */
        const val ONE_PASS_TOKENS = 5_500
        private const val QUESTION_PART_TOKENS = 1_200
        private const val SUMMARY_PART_TOKENS = 5_000
        const val MAX_SUMMARY_PARTS = 8
        private const val ANSWER_TOKENS = 500
        private const val PART_NOTE_TOKENS = 220
        private const val GIST_TOKENS = 200

        const val COULDNT_READ_NOW = "I couldn't read it just now."

        // Not "I found it, but…": the same words do for a document the user attached.
        fun couldNotReadNow(name: String): String = "I couldn't read $name just now. Please try again."

        fun couldNotRead(name: String, why: DocumentText.Why): String = "I couldn't read $name: ${why.said}."
    }
}
