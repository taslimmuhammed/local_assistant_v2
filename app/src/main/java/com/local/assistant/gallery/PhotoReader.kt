package com.local.assistant.gallery

import android.util.Log
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.work.ModelAccess
import com.local.assistant.privacy.SensitiveText
import kotlinx.coroutines.CancellationException

/**
 * Answers a question from the photo find_photos found for it, in a short conversation of its own,
 * and writes the reply itself.
 *
 * Read inside the chat (the image past ~2,000 tokens of instructions, at the chat's temperature),
 * answers were unsteady on the phone (28 Oct 2026, `PhotoReadingEvalTest`): the PAN came out
 * differently in one run of two, a passport number was made up from a photo that shows none, and
 * plus-two maths marks were given as SSLC ones. Read on its own, each number came out the same in
 * five tries of five, the Aadhaar number passing its check digit.
 *
 * An ID number is copied on its own and checked here rather than trusted: its shape, a card's Luhn
 * digit, Aadhaar's Verhoeff one, and that the photo is the document asked about. One that fails is
 * not given; the reply says what was seen instead. (The credit card came out as 12 digits of its
 * 16, the same way five times out of five.)
 *
 * The chat's live conversation is closed for this ([ModelAccess.withModelFree]): never two at
 * once. So the turn ends with this reply, and the chat is rebuilt for the next message.
 */
class PhotoReader(private val backend: LlmBackend, private val model: ModelAccess) {

    /**
     * The reply to [question] from the photo at [path]. Always one: the chat's conversation is
     * closed by now, so the chat model can't be handed the photo instead.
     */
    suspend fun answer(path: String, question: String): String = model.withModelFree {
        try {
            if (!backend.ensureReady()) return@withModelFree COULDNT_READ
            backend.openChat(SPEC).use { session ->
                val field = Field.of(question)
                if (field == null) {
                    answerFrom(ask(session, answerPrompt(question), path))
                } else {
                    // The image goes with the first message; the second looks at it again in the conversation.
                    // Worded as when it read every number the same five times in five (a "12-digit"
                    // and "only that" version had the Aadhaar number fail its check twice in two).
                    val copied = ask(session, "Copy ${field.printed} from this image exactly as printed, every character. Reply with only the value.", path)
                    val document = ask(session, DOCUMENT, null)
                    reply(field, copied, document).also {
                        // Counts only: the number itself never goes to the log.
                        Log.i(TAG, "$field: ${copied.count(Char::isDigit)} digits copied, ${if (field.valueIn(copied) != null) "passes" else "fails"} its check; photo named \"${documentName(document)}\"")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading a found photo failed", e)
            COULDNT_READ
        }
    }

    private suspend fun ask(session: ChatSession, prompt: String, path: String?): String {
        val reply = StringBuilder()
        session.send(prompt, path?.let { PromptAttachment(it, AttachmentKind.IMAGE) }).collect { event ->
            when (event) {
                is GenEvent.TextDelta -> reply.append(event.text)
                is GenEvent.Error -> throw event.cause
                else -> Unit
            }
        }
        return reply.toString().replace("**", "").trim()
    }

    /** An ID number asked for: which, how it's printed, and the documents it's on. */
    enum class Field(
        val kind: SensitiveText.Kind,
        /** As the reply names it. */
        val label: String,
        /** As the photo is asked for it. */
        val printed: String,
        /** The document it's on, as the reply names it. */
        val document: String,
        /** Words one of those documents is called by. */
        private val documentWords: List<String>,
        private val asked: Regex,
    ) {
        AADHAAR(
            SensitiveText.Kind.AADHAAR, "Aadhaar number", "the Aadhaar number", "Aadhaar card",
            listOf("aadhaar", "aadhar", "uidai", "unique identification"), Regex("\\baadhaa?r\\b"),
        ),
        PAN(
            SensitiveText.Kind.PAN, "PAN", "the Permanent Account Number (PAN)", "PAN card",
            listOf("pan", "permanent account", "income tax"), Regex("\\bpan\\b"),
        ),
        DRIVING_LICENCE(
            SensitiveText.Kind.DRIVING_LICENCE, "driving licence number", "the driving licence number", "driving licence",
            listOf("licen", "driving"), Regex("\\b(licen[cs]e|dl)\\b"),
        ),
        PASSPORT(
            SensitiveText.Kind.PASSPORT, "passport number", "the passport number", "passport",
            listOf("passport"), Regex("\\bpassport\\b"),
        ),
        // Last: "PAN card number" and "Aadhaar card number" are theirs.
        CARD(
            SensitiveText.Kind.CARD, "card number", "the card number", "card",
            listOf("card"), Regex("\\bcard\\b"),
        ),
        ;

        /** Whether [document] (as the model named the photo) is one this number is on. */
        fun isOn(document: String): Boolean = documentWords.any { it in document.lowercase() }

        /** The number as printed in [copied], when it has this one's shape (and check digit); else null. */
        fun valueIn(copied: String): String? {
            // A passport number counts only beside the word, as in a reply.
            val prefix = if (this == PASSPORT) "passport " else ""
            val text = prefix + copied
            return SensitiveText.find(text).firstOrNull { it.kind == kind }?.let { text.substring(it.start, it.end) }
        }

        companion object {
            /** The ID number [question] asks for; null when it asks something else ("when does my licence expire?"). */
            fun of(question: String): Field? {
                val text = question.lowercase()
                // "What's my PAN?" asks for the number; "my PAN card's date of birth" doesn't.
                val number = NUMBER.containsMatchIn(text) || Regex("\\bpan\\b(?!\\s*card)").containsMatchIn(text)
                if (!number) return null
                return entries.firstOrNull { it.asked.containsMatchIn(text) }
            }

            private val NUMBER = Regex("\\b(numbers?|no|num)\\b")
        }
    }

    companion object {
        private const val TAG = "PhotoReader"

        private const val SYSTEM = "You are a careful assistant."

        private val SPEC = ChatSpec(systemPrefix = SYSTEM, history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = 200)

        const val DOCUMENT = "Look at the photo again. What document or kind of photo is it, in a few words? " +
            "Its printed title if it has one. Reply with only that, no numbers."

        fun answerPrompt(question: String): String =
            // No example document: given "Your SSLC mark sheet shows…", the plus-two certificate was called that.
            "Answer this from the photo, in a sentence or two: \"$question\" Start by saying what document it is, by its printed title. " +
                "If that's a different document from the one asked about, say so plainly first. Copy names, numbers, dates and grades exactly as printed. " +
                "If it shows something close to what was asked (a grade or CGPA for a score or marks), give that. " +
                "If it doesn't show the answer, say so in a few words."

        /** The model's answer, or [COULDNT_READ] when it said nothing. */
        fun answerFrom(said: String): String = said.trim().ifEmpty { COULDNT_READ }

        const val COULDNT_READ = "I found the photos below, but couldn't read the answer from them just now."

        /** The reply for [field], from what was [copied] off the photo and what it was named ([named]). */
        fun reply(field: Field, copied: String, named: String): String {
            val document = documentName(named)
            val yours = "your ${document ?: field.document}"
            val matches = document == null || field.isOn(document)
            val value = field.valueIn(copied)
            // A number with its document's shape and check digit is that number, whatever the
            // model called the photo; a passport number's shape alone (a letter, seven digits) isn't.
            if (value != null && (matches || field != Field.PASSPORT)) {
                return "${yours.replaceFirstChar(Char::uppercase)} shows your ${field.label}: $value."
            }
            if (!matches) {
                return "The closest photo I found isn't your ${field.document} (it looks like $document), so I couldn't find your ${field.label}. The photos below may help."
            }
            if (NOT_SHOWN.containsMatchIn(copied) || copied.none(Char::isLetterOrDigit)) {
                return "The photo of $yours doesn't show your ${field.label}. The photos below may help."
            }
            val digits = copied.count(Char::isDigit)
            // Not given: a wrong number is worse than none. The photo is right below the reply.
            return if (field == Field.CARD && digits in 1..15) {
                "I could only make out $digits digits of the card number on $yours, where card numbers usually have 16, so I won't guess the rest. It's in the photo below."
            } else {
                "I couldn't read your ${field.label} clearly on $yours, so I won't guess it. It's in the photo below."
            }
        }

        /** "e-PAN card" out of "This is an e - Permanent Account Number (e-PAN) Card."; null when it couldn't say. */
        fun documentName(named: String): String? {
            val name = named.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .replace(Regex("^(it'?s|it is|this is|this photo is|the photo shows|a photo of|an image of)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(a|an|the)\\s+", RegexOption.IGNORE_CASE), "")
                .trim().trimEnd('.').trim()
            if (name.isEmpty() || name.length > MAX_NAME || CANT_TELL.containsMatchIn(name)) return null
            return name
        }

        private const val MAX_NAME = 80

        private val NOT_SHOWN = Regex("\\b(none|not (shown|visible|present|printed|there)|no (\\w+ )?(number|value)|n/a|can'?t|cannot|unable|isn'?t|doesn'?t)\\b", RegexOption.IGNORE_CASE)

        private val CANT_TELL = Regex("\\b(can'?t|cannot|unable|not sure|unclear)\\b", RegexOption.IGNORE_CASE)
    }
}
