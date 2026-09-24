package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin

/** A request to record a fact. Keys must already be normalised with [FactKeys]. */
data class FactWrite(
    val subject: String,
    val attribute: String,
    val value: String,
    /**
     * Asks for the fact to be kept always in mind. It only ever pins: a write without it leaves
     * an existing pin alone, because unpinning is something the user does on the memory screen.
     */
    val pin: Boolean,
    val origin: FactOrigin,
    /** When the statement was made — not when it was written, which for extraction is later. */
    val statedAt: Long,
    val sourceMessageId: Long?,
)

sealed interface FactDecision {
    /** A new row. */
    data class Insert(val fact: FactEntity) : FactDecision

    /** The row with its value replaced. */
    data class Update(val fact: FactEntity) : FactDecision

    /** Same value restated: only confirmation time (and possibly the pin) changes. */
    data class Confirm(val fact: FactEntity) : FactDecision

    data class Skip(val reason: SkipReason) : FactDecision
}

enum class SkipReason {
    /** Blank subject, attribute or value. */
    INVALID,

    /** Stated before the user asked to forget it. */
    FORGOTTEN,

    /** Extraction may not overwrite something the user typed themself. */
    USER_EDIT_PROTECTED,

    /** An older statement than the one already stored. */
    STALE,
}

/**
 * The upsert rules, kept pure so every one of them is tested without a database:
 *
 * - the newest statement wins, by [FactWrite.statedAt];
 * - an [FactOrigin.EXTRACTED] write never overwrites a [FactOrigin.USER_EDIT];
 * - restating the same value only bumps `lastConfirmedAt`;
 * - anything stated before a matching tombstone is ignored;
 * - only `user` facts may be core.
 *
 * An older statement that disagrees with the stored value changes nothing at all — not even the
 * confirmation time, since it confirms nothing.
 */
object FactUpsertPolicy {

    fun decide(
        existing: FactEntity?,
        write: FactWrite,
        forgottenAt: Long?,
        now: Long,
    ): FactDecision {
        if (write.subject.isBlank() || write.attribute.isBlank() || write.value.isBlank()) {
            return FactDecision.Skip(SkipReason.INVALID)
        }
        if (forgottenAt != null && write.statedAt <= forgottenAt) {
            return FactDecision.Skip(SkipReason.FORGOTTEN)
        }

        val mayBeCore = write.subject == FactKeys.USER
        val pin = write.pin && mayBeCore

        if (existing == null) {
            return FactDecision.Insert(
                FactEntity(
                    subject = write.subject,
                    attribute = write.attribute,
                    value = write.value,
                    category = FactCategorizer.categorize(write.subject, write.attribute),
                    core = pin,
                    origin = write.origin,
                    sourceMessageId = write.sourceMessageId,
                    statedAt = write.statedAt,
                    createdAt = now,
                    updatedAt = now,
                    lastConfirmedAt = write.statedAt,
                ),
            )
        }

        // Pinning is a request about how to respond, not a claim about the world, so it is
        // honoured from chat and the memory screen but never from extraction.
        val pinNow = existing.core || (pin && write.origin != FactOrigin.EXTRACTED)

        if (FactKeys.sameValue(existing.value, write.value)) {
            // The user confirming a value on the memory screen makes it theirs, and so protected.
            val origin = if (write.origin == FactOrigin.USER_EDIT) FactOrigin.USER_EDIT else existing.origin
            val changed = pinNow != existing.core || origin != existing.origin
            return FactDecision.Confirm(
                existing.copy(
                    core = pinNow,
                    origin = origin,
                    lastConfirmedAt = maxOf(existing.lastConfirmedAt, write.statedAt),
                    updatedAt = if (changed) now else existing.updatedAt,
                ),
            )
        }

        if (write.origin == FactOrigin.EXTRACTED && existing.origin == FactOrigin.USER_EDIT) {
            return FactDecision.Skip(SkipReason.USER_EDIT_PROTECTED)
        }
        if (write.statedAt < existing.statedAt) {
            return FactDecision.Skip(SkipReason.STALE)
        }

        return FactDecision.Update(
            existing.copy(
                value = write.value,
                core = pinNow,
                origin = write.origin,
                sourceMessageId = write.sourceMessageId,
                statedAt = write.statedAt,
                updatedAt = now,
                lastConfirmedAt = write.statedAt,
            ),
        )
    }
}
