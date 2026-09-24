package com.local.assistant.memory.work

import android.util.Log
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Embeds the archive's backlog in the background: new exchanges a moment after their reply,
 * and everything from before the embedder was installed.
 *
 * Every batch goes through the [ModelScheduler] at the lowest-but-one priority, so it runs only
 * while the user is neither generating nor typing and stops between chunks when they start. A
 * chunk takes tens of milliseconds, so the wait for one already running is short.
 */
class EmbeddingQueue(
    private val archive: ArchiveRepository,
    private val embedder: Embedder,
    private val scheduler: ModelScheduler,
    private val scope: CoroutineScope,
    private val inForeground: () -> Boolean,
    /** Small-memory devices give the embedder's memory back once the backlog is done. */
    private val unloadWhenDrained: Boolean,
) {

    private var job: Job? = null

    /** The model the archive was last brought in line with, in this process. */
    private var adopted: String? = null

    private val _remaining = MutableStateFlow<Int?>(null)

    /** Chunks still waiting for a vector; null until first counted. */
    val remaining: StateFlow<Int?> = _remaining.asStateFlow()

    /** Something was archived, the app came back, or a model was installed: drain when idle. */
    @Synchronized
    fun kick() {
        // A kick during a drain that has just seen an empty backlog must not be lost.
        requested = true
        if (job?.isActive == true) return
        job = scope.launch {
            while (requested) {
                requested = false
                try {
                    drain()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Embedding backlog stopped", e)
                    return@launch
                }
            }
        }
    }

    @Volatile
    private var requested = false

    private suspend fun drain() {
        val modelId = embedder.modelId ?: return
        // Once per model per process: it scans the whole index, too much for every message.
        if (adopted != modelId) {
            archive.adoptModel(modelId)
            adopted = modelId
        }
        _remaining.value = archive.backlogSize()
        // Ready for the next query, even when there is nothing to embed.
        if (inForeground() && !embedder.isReady) runWhenIdle { embedder.prepare() }

        var embedded = 0
        while (embedder.modelId == modelId) {
            val batch = archive.backlog(BATCH)
            if (batch.isEmpty()) break
            val finished = runWhenIdle {
                for (chunk in batch) {
                    coroutineContext.ensureActive()
                    val vector = embedder.embed(chunk.text, EmbedKind.DOCUMENT)?.let(Int8Vectors::quantize)
                    // No vector because the model would not load: stop, and leave the backlog be.
                    if (vector == null && !embedder.isReady) return@runWhenIdle false
                    archive.saveEmbedding(chunk, vector, modelId)
                    embedded++
                }
                true
            }
            if (!finished) break
            _remaining.value = archive.backlogSize()
        }
        if (embedded > 0) Log.i(TAG, "Embedded $embedded chunks; ${_remaining.value} left")
        if (unloadWhenDrained && !inForeground()) embedder.unload()
    }

    /**
     * Runs [block] in a background slot, waiting out the user's typing and turns. False if the
     * block itself gave up.
     */
    private suspend fun runWhenIdle(block: suspend () -> Boolean): Boolean {
        while (true) {
            val result = scheduler.runBackground(ModelScheduler.Priority.EMBEDDING_BACKLOG) { block() }
            if (result != null) return result
            delay(ModelScheduler.QUIET_MS)
        }
    }

    private companion object {
        const val TAG = "EmbeddingQueue"
        const val BATCH = 16
    }
}
