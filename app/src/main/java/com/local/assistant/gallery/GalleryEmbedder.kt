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
 * The text side runs on the CPU, the vision encoder on the GPU while the chat model isn't loaded
 * and on the CPU while it is ([leaveGpu] says why). On the OnePlus (SM8850) a photo
 * takes 223 ms on the GPU against 495 ms on four CPU threads (2026-11-14, `IndexSpeedTest`), with
 * less process memory too. It ran on the CPU from 2026-10-26: on LiteRT-LM 0.18.1, while it was on
 * the GPU, the chat model's own vision failed on any image ("TensorBuffer must have all static
 * dimensions"). On 0.19.1 the chat model reads images in every order tried (`GpuVisionProbeTest`,
 * 2026-11-14), and GPU vectors match the CPU ones already stored (cosine 0.98 or more on 24
 * photos, each its own CPU twin's nearest), so the two mix in one index. If the GPU won't load,
 * the CPU takes over. The [com.local.assistant.memory.work.ModelScheduler] keeps it from running
 * while the chat model is generating.
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
    /** False keeps the vision encoder on the CPU, as the speed and probe tests compare. */
    private val visionOnGpu: Boolean = true,
    /** Whether the GPU may take the vision encoder now: not while the chat model holds it ([leaveGpu]). */
    private val gpuAllowed: () -> Boolean = { true },
) {

    private val mutex = Mutex()

    @Volatile
    private var engine: EmbeddingEngine? = null

    /** The loaded engine's vision encoder is on the GPU. */
    @Volatile
    private var onGpu = false

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

    /**
     * Before the chat model loads: closed if its vision is on the GPU, and opened again with the
     * vision on the CPU when next wanted. Closed on the GPU while a chat conversation was open,
     * it broke that conversation: token soup past ~4,096 tokens in the app after it came back
     * from the background, a crash in OpenCL in `AudioPositionProbeTest -e photo reload`
     * (14 Nov 2026). Closed before the chat model is up, or on the CPU, it breaks nothing.
     */
    suspend fun leaveGpu() = mutex.withLock {
        if (onGpu) close()
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
        val gpu = if (visionOnGpu && gpuAllowed()) load(file, gpu = true) else null
        return (gpu ?: load(file, gpu = false))?.also {
            engine = it
            loadedFrom = file
            onGpu = gpu != null
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
                // A second engine beside the chat model's: in the model's events too.
                com.local.assistant.memory.prompt.TurnTimings.event("photo_model_load", System.currentTimeMillis() - started, "vision on ${if (gpu) "GPU" else "CPU"}")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Could not load ${file.name} with vision on the ${if (gpu) "GPU" else "CPU"}", e)
        null
    }

    private fun close() {
        engine?.let {
            com.local.assistant.memory.prompt.TurnTimings.event("photo_model_close", detail = if (holdsVision) "with vision" else "")
            runCatching { it.close() }
        }
        engine = null
        loadedFrom = null
        onGpu = false
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
