package com.local.assistant.memory.embed

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** An embedding bundle on disk and what it is. */
data class InstalledEmbedder(val file: File, val spec: EmbedderSpec)

/**
 * [Embedder] on LiteRT-LM's `EmbeddingEngine`, on the CPU so it never competes with the chat
 * model for the GPU.
 *
 * The engine is created on first use (about 2 s) and kept until [unload]; it holds roughly half
 * a gigabyte. One call at a time: the engine serialises them anyway, and the mutex lets a
 * caller wait without blocking a thread.
 */
class LiteRtEmbedder(
    private val installed: () -> InstalledEmbedder?,
    private val cacheDir: File?,
    private val threads: Int = DEFAULT_THREADS,
) : Embedder {

    private val mutex = Mutex()

    @Volatile
    private var engine: EmbeddingEngine? = null

    @Volatile
    private var loadedFrom: InstalledEmbedder? = null

    override val modelId: String? get() = installed()?.spec?.modelId

    override val similarityThreshold: Float
        get() = installed()?.spec?.similarityThreshold ?: EmbedderCatalog.DEFAULT.similarityThreshold

    override val isReady: Boolean get() = engine != null && loadedFrom == installed()

    override suspend fun embed(text: String, kind: EmbedKind): FloatArray? {
        val target = installed() ?: return null
        val prefix = if (kind == EmbedKind.QUERY) target.spec.queryPrefix else target.spec.documentPrefix
        return mutex.withLock {
            val engine = engineFor(target) ?: return@withLock null
            withContext(Dispatchers.Default) { compute(engine, prefix, text) }
        }
    }

    override suspend fun prepare(): Boolean {
        val target = installed() ?: return false
        return mutex.withLock { engineFor(target) != null }
    }

    override suspend fun unload() = mutex.withLock { close() }

    /**
     * Longer inputs than the bundle's longest signature are an error in this runtime version
     * rather than being cut, so the text is capped up front and halved on refusal. A few hundred
     * characters is plenty to place a chunk.
     */
    private fun compute(engine: EmbeddingEngine, prefix: String, text: String): FloatArray? {
        var body = text.take(MAX_CHARS)
        while (true) {
            try {
                val response = engine.computeEmbedding(
                    listOf(InputData.Text(prefix + body)),
                    EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = Int8Vectors.DIMENSIONS),
                )
                return response.embedding.takeIf { it.size >= Int8Vectors.DIMENSIONS }
            } catch (e: Exception) {
                if (body.length <= MIN_CHARS) {
                    Log.w(TAG, "Embedding failed for ${body.length} chars", e)
                    return null
                }
                body = body.take(body.length / 2)
            }
        }
    }

    /** The engine for [target], creating it (and dropping one for a different file) if needed. */
    private suspend fun engineFor(target: InstalledEmbedder): EmbeddingEngine? {
        engine?.let { if (loadedFrom == target) return it }
        close()
        if (!target.file.isFile) return null
        return try {
            // Created without cancellation, so an interrupted load cannot leak a native engine.
            withContext(NonCancellable + Dispatchers.Default) {
                val started = System.currentTimeMillis()
                EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = target.file.absolutePath,
                        backend = Backend.CPU(threadCount = threads),
                        cacheDir = cacheDir?.absolutePath,
                    ),
                ).also {
                    it.initialize()
                    Log.i(TAG, "Loaded ${target.spec.key} in ${System.currentTimeMillis() - started} ms")
                }
            }.also {
                engine = it
                loadedFrom = target
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not load ${target.file.name}", e)
            null
        }
    }

    private fun close() {
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedFrom = null
    }

    private companion object {
        const val TAG = "Embedder"

        /** Two to four threads leave the other cores to the UI and the chat model's CPU work. */
        const val DEFAULT_THREADS = 4

        const val MAX_CHARS = 1_200
        const val MIN_CHARS = 150
    }
}
