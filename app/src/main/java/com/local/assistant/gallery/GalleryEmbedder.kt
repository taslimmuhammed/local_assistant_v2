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
 * keeps it from running while the chat model is generating.
 *
 * One engine serves photo search and memory search ([com.local.assistant.memory.embed.PhotoModelEmbedder]),
 * so the two never hold a copy each. Photos use it while they are indexed or searched and then
 * [release] it; memory search holds it from [embedText] or [prepare] until [unloadText]. Released
 * while memory search holds it, the engine is opened again without the vision encoder, which
 * loads only with the first image and is most of its memory (on the OnePlus, 2026-11-06,
 * `TextEmbedderEvalTest`: ≈250 MB for the text side, ≈350 MB more once a photo is embedded).
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

    /** Memory search is using it: [release] keeps the text side loaded. */
    @Volatile
    private var textHeld = false

    /** An image went through since it was opened, so the vision encoder is in memory too. */
    @Volatile
    private var holdsVision = false

    /** The last text embedded and its vector: a question goes to photo and memory search alike. */
    private var lastText: String? = null
    private var lastVector: FloatArray? = null

    val isInstalled: Boolean get() = modelFile()?.isFile == true

    val isLoaded: Boolean get() = engine != null

    /** A normalised vector for a photo given as encoded image bytes (JPEG), or null on failure. */
    suspend fun embedImage(jpeg: ByteArray): FloatArray? = compute(InputData.Image(jpeg))

    /** A normalised vector for what someone typed, with the model's search-query prompt. */
    suspend fun embedQuery(text: String): FloatArray? = compute(InputData.Text(GalleryModel.QUERY_PREFIX + text.trim()))

    /** For memory search: a normalised vector for [text] as given (the caller adds the task prompt). */
    suspend fun embedText(text: String): FloatArray? {
        textHeld = true
        return compute(InputData.Text(text))
    }

    /** For memory search: opens the engine now, so the next question needn't wait. True when it is ready. */
    suspend fun prepare(): Boolean = mutex.withLock {
        textHeld = true
        modelFile()?.let { engineFor(it) } != null
    }

    /**
     * Photos are done with it for now: closed, or while memory search holds it, opened again
     * without the vision encoder (≈0.6 s) if an image had loaded it.
     */
    suspend fun release() = mutex.withLock {
        if (!textHeld) {
            close()
        } else if (holdsVision) {
            close()
            modelFile()?.let { engineFor(it) }
        }
    }

    /** Memory search is done with it: closed, unless photos are being indexed with it (their [release] closes it then). */
    suspend fun unloadText() = mutex.withLock {
        textHeld = false
        if (!holdsVision) close()
    }

    /** Closes it whoever is using it; the next call opens it again. */
    suspend fun unload() = mutex.withLock {
        textHeld = false
        close()
    }

    private suspend fun compute(input: InputData): FloatArray? = mutex.withLock {
        val text = (input as? InputData.Text)?.text
        if (text != null && text == lastText) lastVector?.let { return@withLock it.copyOf() }
        val engine = engineFor(modelFile() ?: return@withLock null) ?: return@withLock null
        withContext(Dispatchers.Default) {
            try {
                engine.computeEmbedding(listOf(input), OPTIONS).embedding.takeIf { it.size >= Int8Vectors.DIMENSIONS }
            } catch (e: Exception) {
                Log.w(TAG, "Embedding failed", e)
                null
            }
        }?.also { vector ->
            if (text != null) {
                lastText = text
                lastVector = vector.copyOf()
            } else {
                holdsVision = true
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
        holdsVision = false
        lastText = null
        lastVector = null
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
