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

    /** Null when the model cannot be loaded or said nothing. */
    suspend fun describe(path: String): Description? = scheduler.runUser {
        model.withModelFree {
            if (!backend.ensureReady()) return@withModelFree null
            val reply = StringBuilder()
            val image = backend.capabilities?.visionTokensPerImage ?: 0
            val output = (DIGIT_SAFE_TOKENS - image - PROMPT_TOKENS).coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
            val spec = ChatSpec(systemPrefix = SYSTEM, history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = output)
            backend.openChat(spec).use { session ->
                session.send(PROMPT, PromptAttachment(path, AttachmentKind.IMAGE)).collect { event ->
                    when (event) {
                        is GenEvent.TextDelta -> reply.append(event.text)
                        is GenEvent.Error -> throw event.cause
                        else -> Unit
                    }
                }
            }
            parse(reply.toString())
        }
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
