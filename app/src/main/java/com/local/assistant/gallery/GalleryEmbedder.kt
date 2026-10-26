package com.local.assistant.gallery

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.local.assistant.llm.ModelFormat
import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma 2 on LiteRT-LM's `EmbeddingEngine`: photos and search words into one space.
 *
 * Everything runs on the CPU. The vision encoder was on the GPU at first: on the OnePlus (SM8850,
 * 2026-10-25, `GalleryEvalTest`) a photo took 224 ms there against 474 ms on four CPU threads (six
 * or eight threads were no faster), with 560 MB of process memory against 812 MB. But while it was
 * loaded on the GPU, the chat model's own vision failed on any image ("TensorBuffer must have all
 * static dimensions", 2026-10-26): a photo sent in a chat after a search, or a found photo given
 * to it to read a question from. With this one on the CPU, both work. The [com.local.assistant.memory.work.ModelScheduler] already
 * keeps it from running while the chat model is generating. The engine is only kept while photos
 * are being indexed or searched; [unload] gives it back.
 */
class GalleryEmbedder(
    private val modelFile: () -> File?,
    private val cacheDir: File?,
    private val threads: Int = DEFAULT_THREADS,
    /** True puts the vision encoder on the GPU: faster, but the chat model can't see images meanwhile. */
    private val visionOnGpu: Boolean = false,
) {

    private val mutex = Mutex()

    @Volatile
    private var engine: EmbeddingEngine? = null

    @Volatile
    private var loadedFrom: File? = null

    val isInstalled: Boolean get() = modelFile()?.isFile == true

    val isLoaded: Boolean get() = engine != null

    /** A normalised vector for a photo given as encoded image bytes (JPEG), or null on failure. */
    suspend fun embedImage(jpeg: ByteArray): FloatArray? = compute(listOf(InputData.Image(jpeg)))

    /** A normalised vector for what someone typed, with the model's search-query prompt. */
    suspend fun embedQuery(text: String): FloatArray? = compute(listOf(InputData.Text(GalleryModel.QUERY_PREFIX + text.trim())))

    suspend fun unload() = mutex.withLock { close() }

    private suspend fun compute(input: List<InputData>): FloatArray? = mutex.withLock {
        val engine = engineFor(modelFile() ?: return@withLock null) ?: return@withLock null
        withContext(Dispatchers.Default) {
            try {
                engine.computeEmbedding(input, OPTIONS).embedding.takeIf { it.size >= Int8Vectors.DIMENSIONS }
            } catch (e: Exception) {
                Log.w(TAG, "Embedding failed", e)
                null
            }
        }
    }

    private suspend fun engineFor(file: File): EmbeddingEngine? {
        engine?.let { if (loadedFrom == file) return it }
        close()
        if (!file.isFile) return null
        // Handed anything else, the runtime can take the app down with it (ModelFormat).
        ModelFormat.problem(file, ModelFormat.Kind.GALLERY)?.let { problem ->
            Log.w(TAG, "Not loading ${file.name}: $problem")
            return null
        }
        val gpu = if (visionOnGpu) load(file, gpu = true) else null
        return (gpu ?: load(file, gpu = false))?.also {
            engine = it
            loadedFrom = file
        }
    }

    private suspend fun load(file: File, gpu: Boolean): EmbeddingEngine? = try {
        // Created without cancellation, so an interrupted load cannot leak a native engine.
        withContext(NonCancellable + Dispatchers.Default) {
            val started = System.currentTimeMillis()
            EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(threadCount = threads),
                    visionBackend = if (gpu) Backend.GPU() else Backend.CPU(threadCount = threads),
                    cacheDir = cacheDir?.absolutePath,
                    visionTokensPerImage = GalleryModel.VISION_TOKENS,
                ),
            ).also {
                it.initialize()
                Log.i(TAG, "Loaded ${GalleryModel.KEY} (vision on ${if (gpu) "GPU" else "CPU"}) in ${System.currentTimeMillis() - started} ms")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Could not load ${file.name} with vision on the ${if (gpu) "GPU" else "CPU"}", e)
        null
    }

    private fun close() {
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedFrom = null
    }

    private companion object {
        const val TAG = "GalleryEmbedder"

        /** Leaves the other cores to the UI and, if it is loaded, the chat model's CPU work. */
        const val DEFAULT_THREADS = 4

        val OPTIONS = EmbeddingOptions(
            normalize = true,
            outputSize = Int8Vectors.DIMENSIONS,
            visionTokensPerImage = GalleryModel.VISION_TOKENS,
        )
    }
}
