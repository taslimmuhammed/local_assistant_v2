package com.local.assistant.memory.notes

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.work.ModelAccess
import com.local.assistant.memory.work.ModelScheduler
import java.util.Locale

/**
 * For the Images tab's "+": the model looks at a picked image and writes the title and details
 * that `remember_image` gets in a chat, so an image is found and read again the same way however
 * it was added.
 *
 * A conversation of its own, short enough (one image, a few lines of instructions, the reply) to
 * stay under token 2,048, past which the GPU backend mis-copies digits.
 */
class ImageDescriber(
    private val backend: LlmBackend,
    private val scheduler: ModelScheduler,
    private val model: ModelAccess,
) {
    data class Description(val title: String, val details: String)

    /**
     * Null when the model cannot be loaded or said nothing. [said]: what the user wrote with it
     * ("remember this, it's the office wifi"), for context the image alone doesn't give.
     */
    suspend fun describe(path: String, said: String? = null): Description? = scheduler.runUser { read(path, said) }

    /** [describe] for a caller that already holds the model's slot (`ModelScheduler.runUser`). */
    suspend fun read(path: String, said: String? = null): Description? =
        model.withModelFree {
            if (!backend.ensureReady()) return@withModelFree null
            val image = backend.capabilities?.visionTokensPerImage ?: 0
            val output = (DIGIT_SAFE_TOKENS - image - PROMPT_TOKENS).coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
            val spec = ChatSpec(systemPrefix = SYSTEM, history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = output)
            val prompt = said?.trim()?.takeIf { it.isNotEmpty() }?.let { "$PROMPT They said: “${it.take(MAX_SAID)}”" } ?: PROMPT
            val described = parse(ask(spec, prompt, path)) ?: return@withModelFree null
            // Transcribing a whole page, the model drops digits it reads right when asked for
            // just those values: a roll number 4361044 came out 436104 in the description, 4361044
            // (twice) when asked for with the other numbered labels. So the numbered labels are
            // read once more on their own, and the description takes those numbers.
            val labels = numberedLabels(described.details)
            if (labels.isEmpty()) return@withModelFree described
            val checked = ask(spec.copy(systemPrefix = CHECK_SYSTEM), CHECK_PROMPT + labels.joinToString("\n"), path)
            described.copy(details = correctNumbers(described.details, checked))
        }

    /** One question about the image in a conversation of its own. */
    private suspend fun ask(spec: ChatSpec, prompt: String, path: String): String {
        val reply = StringBuilder()
        backend.openChat(spec).use { session ->
            session.send(prompt, PromptAttachment(path, AttachmentKind.IMAGE)).collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> reply.append(event.text)
                    is GenEvent.Error -> throw event.cause
                    else -> Unit
                }
            }
        }
        return reply.toString()
    }

    companion object {
        const val MAX_TITLE = 80
        const val MAX_DETAILS = 4_000

        /** Where the GPU backend starts dropping digits, less a margin. */
        private const val DIGIT_SAFE_TOKENS = 2_000

        /** The instructions, the turn markers and the request, with room to spare. */
        private const val PROMPT_TOKENS = 150

        /** A full page of text read off a bill or a card. */
        private const val MAX_OUTPUT_TOKENS = 1_200
        private const val MIN_OUTPUT_TOKENS = 400

        private val SYSTEM = """
            The user is saving an image to their memory. Look at it closely and reply in exactly this form:
            Title: a few words naming it
            Details: everything you can read and see in it: all the text, names, numbers, dates and amounts, copied exactly, and what it shows.
        """.trimIndent()

        private const val PROMPT = "Save this image."

        /** Enough of the user's message for context, well inside [PROMPT_TOKENS]' margin. */
        private const val MAX_SAID = 200

        private const val CHECK_SYSTEM = "You are a careful assistant."
        private const val CHECK_PROMPT = "Copy these from the image exactly as printed, every digit, one per line as label: value.\n"

        /** At most this many labels are read again: the IDs, dates and amounts that matter most come first on a page. */
        private const val MAX_CHECKED_LABELS = 8

        /** "Roll No.: 436104" → "Roll No.": a label whose value has three or more digits. */
        private val NUMBERED_LINE = Regex("^\\W*([A-Za-z][A-Za-z .'/()&-]{1,40}?)\\s*[:：]\\s*(.*\\d.*)$")

        /** The labels in [details] that carry a number of three or more digits, in order. */
        fun numberedLabels(details: String): List<String> =
            details.lines().mapNotNull { line ->
                val match = NUMBERED_LINE.find(line.trim()) ?: return@mapNotNull null
                match.groupValues[1].trim().takeIf { match.groupValues[2].count(Char::isDigit) >= 3 }
            }.distinct().take(MAX_CHECKED_LABELS)

        /** [details] with the numbers the second read ([checked]) shows were misread, by their labels. */
        fun correctNumbers(details: String, checked: String): String =
            com.local.assistant.memory.tools.ReplyGrounding.correct(details, listOf(checked))

        private val TITLE = Regex("(?im)^\\s*#*\\s*title\\s*:\\s*(.*)$")
        private val DETAILS = Regex("(?im)^\\s*#*\\s*details\\s*:[ \\t]*")

        /**
         * "Title: …" and "Details: …" out of the reply, in bold or not. Without the labels, the
         * whole reply is the details and its first words the title.
         */
        fun parse(reply: String): Description? {
            val text = reply.replace("**", "").trim()
            if (text.isEmpty()) return null
            val titleMatch = TITLE.find(text)
            val detailsMatch = DETAILS.find(text)
            val details = when {
                detailsMatch != null -> text.substring(detailsMatch.range.last + 1)
                titleMatch != null -> text.removeRange(titleMatch.range)
                else -> text
            }.trim()
            val title = titleMatch?.groupValues?.get(1)?.let(::tidy)?.takeIf { it.isNotEmpty() }
                ?: tidy(details.lineSequence().first().split(' ').take(FALLBACK_TITLE_WORDS).joinToString(" "))
                    .ifEmpty { "Image" }
            return Description(title.take(MAX_TITLE), details.take(MAX_DETAILS))
        }

        private fun tidy(title: String): String =
            title.trim().trim('"', '“', '”', '\'').trimEnd('.', ':').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

        private const val FALLBACK_TITLE_WORDS = 6
    }
}
