package com.local.assistant.llm

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import kotlin.math.ceil

/**
 * What a stored message costs in the context window beyond its text.
 *
 * Text is estimated once, when the message is written (`MessageEntity.tokenEst`); deciding which
 * turns fit is the prompt assembler's job. What stays here is the part no estimator can see:
 * attachments and the chat template's own markers.
 */
object ContextWindow {

    /**
     * The kind of [kind]'s file the model is given with [text] in a conversation's history: none
     * for a voice message written down (`voice/Transcriber`), which goes as its words alone. The
     * message being sent may go as its recording too ([heard]).
     */
    fun sentAttachment(kind: AttachmentKind?, text: String): AttachmentKind? =
        kind.takeUnless { it == AttachmentKind.AUDIO && text.isNotBlank() }

    /**
     * Whether a voice message being sent goes as its recording, its words beside it as the phone
     * heard them (`PromptAssembler.heard`). Hearing it, the model got the names and numbers the
     * recogniser missed ("Umma" written "Uma", "2450" written "200450"), and with the words it
     * still knew the user's "set alarm", which from the recording alone it took for "Salaam" 4
     * times in 4 (VoiceInputEvalTest, 4 Nov 2026). Longer than [MAX_HEARD_MS] (the user's limit:
     * a recording costs [AUDIO_TOKENS_PER_SECOND]), the words go alone.
     */
    fun heard(kind: AttachmentKind?, text: String, durationMs: Long?): Boolean =
        kind == AttachmentKind.AUDIO && text.isNotBlank() && durationMs != null && durationMs <= MAX_HEARD_MS

    const val MAX_HEARD_MS = 30_000L

    /** Per-message overhead for the chat template's role and turn markers. */
    const val TURN_OVERHEAD_TOKENS = 8

    /** Rough cost of a second of audio. An estimate; tune once the model's real rate is known. */
    const val AUDIO_TOKENS_PER_SECOND = 32

    fun attachmentTokens(message: MessageEntity, visionTokensPerImage: Int): Int =
        when (sentAttachment(message.attachmentKind, message.text)) {
            AttachmentKind.IMAGE -> visionTokensPerImage
            AttachmentKind.AUDIO -> audioTokens(message.attachmentDurationMs)
            null -> 0
        }

    fun audioTokens(durationMs: Long?): Int = ceil((durationMs ?: 0L) / 1000.0).toInt() * AUDIO_TOKENS_PER_SECOND

    /**
     * The ceiling the runtime states when a turn is too long: "Input token ids are too long.
     * Exceeding the maximum number of tokens allowed: N". Null for any other error.
     */
    fun reportedMaxTokens(errorMessage: String?): Int? =
        errorMessage?.let { REPORTED_MAX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private val REPORTED_MAX = Regex("""maximum number of tokens allowed:\s*(\d+)""")
}
