package com.local.assistant.gallery

import com.local.assistant.llm.ModelFormat
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.model.ModelFile

/**
 * The photo-search model: Google's EmbeddingGemma 2, the text + vision build (440M: the 270M text
 * model and the 170M vision encoder, no audio encoder), as published by litert-community for
 * LiteRT-LM. Apache-2.0, ungated. Text and images land in one space, so a typed query is compared
 * straight against the photos' vectors.
 *
 * Separate from memory search's embedder ([com.local.assistant.memory.embed.EmbedderCatalog]):
 * nobody has yet measured EmbeddingGemma 2 against it on this app's chat archive, so chat recall
 * stays as it is until that comparison says otherwise.
 *
 * The generic CPU/GPU file, not the per-chip NPU builds in the same repository.
 */
object GalleryModel {

    const val DISPLAY_NAME = "EmbeddingGemma 2 (photos)"

    const val KEY = "embeddinggemma-2-tv-440m"

    private const val FILE_NAME = "embeddinggemma-2-text-vision-440m.litertlm"

    const val SIZE_BYTES = 387_710_976L

    val FILE = ModelFile(
        kind = ModelFormat.Kind.GALLERY,
        fileName = FILE_NAME,
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/$FILE_NAME",
        sizeBytes = SIZE_BYTES,
        sha256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0",
        directory = "gallery",
        requiredFreeBytes = SIZE_BYTES + 256L * 1024 * 1024,
    )

    /**
     * The model's search-query prompt, from its card. Images take no prompt; prefixes are for text
     * only.
     */
    const val QUERY_PREFIX = "task: search result | query: "

    /**
     * Soft tokens per image: the bundle has signatures for 70 and 140. 70 is the one Google
     * benchmarked (119 ms per image on an S26 Ultra's GPU, 175 ms on its CPU); on the Mac it put
     * the right photo first for 16 of 16 queries over 16 test pictures.
     */
    const val VISION_TOKENS = 70

    /** The longest side of the picture handed to the model; it scales and patchifies it anyway. */
    const val INPUT_PIXELS = 512

    /** Stable name for the vectors this setup makes: a different budget or size re-indexes. */
    const val MODEL_ID = "$KEY@${Int8Vectors.DIMENSIONS}@v$VISION_TOKENS"
}
