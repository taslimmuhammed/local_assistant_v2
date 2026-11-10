package com.local.assistant.memory.embed

import com.local.assistant.llm.ModelFormat
import com.local.assistant.model.ModelFile
import java.util.Locale

/**
 * One embedding model the app knows how to use: where it comes from and how to prompt it.
 *
 * Only `.litertlm` bundles made for LiteRT-LM's `EmbeddingEngine` load; they carry the
 * tokenizer and pooling themselves. A bare `.tflite` does not.
 */
data class EmbedderSpec(
    /** Stable name for the vectors this model makes; see [Embedder.modelId]. */
    val key: String,
    val displayName: String,
    val downloadUrl: String?,
    val sizeBytes: Long,
    /** Of the published file, checked after a download. */
    val sha256: String?,
    val queryPrefix: String = "",
    val documentPrefix: String = "",
    /** Longest input the bundle accepts, in its tokens. Longer text is cut before embedding. */
    val maxInputTokens: Int = 512,
    /** Starting point for the inject rule; calibrate on real pairs (debug builds log scores). */
    val similarityThreshold: Float = 0.6f,
) {
    val modelId: String get() = "$key@${Int8Vectors.DIMENSIONS}"
}

/**
 * The text embedders memory search can use instead of the photo model's text side
 * ([PhotoModelEmbedder]): only ever imported from storage now.
 */
object EmbedderCatalog {

    /**
     * IBM's Granite Embedding 311M multilingual R2, as published by litert-community for
     * `EmbeddingEngine` (int8, 64–512 token signatures, 768-d with Matryoshka truncation).
     * Apache-2.0. It was the download in the first builds; still recognised for the installs made
     * then, and when imported. Bare text on both sides: its card measured that a prefix hurts
     * retrieval.
     *
     * Pinned to the commit with this file: `main` has had a re-laid-out one since 8 Nov 2026
     * (332,398,592 bytes, for LiteRT-LM 0.19).
     */
    val GRANITE = EmbedderSpec(
        key = "granite-311m-r2-wi8fc",
        displayName = "Granite Embedding 311M (multilingual)",
        downloadUrl = "https://huggingface.co/litert-community/granite-embedding-311m-multilingual-r2/resolve/1b10683d630c0d2877bf0430af89668cda7f26c4/granite-embedding-311m-r2_wi8fc.litertlm",
        sizeBytes = 332_365_313L,
        sha256 = "beb2be205abc766a670522e651be5713cecb4e4c33e5ef5c30f6a710d4226db5",
    )

    /**
     * An imported embedder on disk. Stored under one name whichever bundle it is (the settings
     * remember which), in a directory of its own so the chat model's leftover cleanup never
     * touches it.
     */
    val FILE = ModelFile(
        kind = ModelFormat.Kind.EMBEDDER,
        fileName = "embedder.litertlm",
        downloadUrl = null,
        sizeBytes = 0,
        sha256 = null,
        directory = "embedders",
        requiredFreeBytes = 0,
    )

    fun byKey(key: String?): EmbedderSpec? = when {
        key == null -> null
        key == GRANITE.key -> GRANITE
        key.startsWith(IMPORTED) -> forImport(key.removePrefix(IMPORTED))
        else -> null
    }

    /**
     * A spec for a bundle the user imported. Its file name is the only hint to what it is, so a
     * name that says EmbeddingGemma gets that model's prompts; anything else is used bare. The
     * name is part of the key, so importing a different file re-embeds the archive.
     */
    fun forImport(fileName: String): EmbedderSpec {
        val name = fileName.lowercase(Locale.ROOT)
        if (name == GRANITE_FILE) return GRANITE
        val gemma = "embeddinggemma" in name || "embedding-gemma" in name || "embedding_gemma" in name
        return EmbedderSpec(
            key = IMPORTED + fileName.filter { it.isLetterOrDigit() || it in "._-" }.take(80),
            displayName = fileName,
            downloadUrl = null,
            sizeBytes = 0,
            sha256 = null,
            queryPrefix = if (gemma) GEMMA_QUERY else "",
            documentPrefix = if (gemma) GEMMA_DOCUMENT else "",
        )
    }

    private const val IMPORTED = "import:"
    private const val GRANITE_FILE = "granite-embedding-311m-r2_wi8fc.litertlm"
}

/** EmbeddingGemma's task prompts for retrieval, from its model card and sentence-transformers config. */
private const val GEMMA_QUERY = "task: search result | query: "
private const val GEMMA_DOCUMENT = "title: none | text: "
