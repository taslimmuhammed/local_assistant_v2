package com.local.assistant.memory.work

/**
 * The model, lent to background work. Only one conversation may exist at a time, so whoever holds
 * the chat's live conversation closes it before [block] runs; the next turn rebuilds it.
 */
interface ModelAccess {
    suspend fun <T> withModelFree(block: suspend () -> T): T
}
