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

    /** Per-message overhead for the chat template's role and turn markers. */
    const val TURN_OVERHEAD_TOKENS = 8

    /** Rough cost of a second of audio. An estimate; tune once the model's real rate is known. */
    const val AUDIO_TOKENS_PER_SECOND = 32

    fun attachmentTokens(message: MessageEntity, visionTokensPerImage: Int): Int =
        when (message.attachmentKind) {
            AttachmentKind.IMAGE -> visionTokensPerImage
            AttachmentKind.AUDIO -> {
                val seconds = ceil((message.attachmentDurationMs ?: 0L) / 1000.0).toInt()
                seconds * AUDIO_TOKENS_PER_SECOND
            }
            null -> 0
        }
}
