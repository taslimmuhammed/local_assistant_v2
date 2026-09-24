package com.local.assistant.model

/**
 * The single model this app runs. Everything about it lives here so swapping or adding models
 * later means touching one file.
 */
object ModelCatalog {

    const val DISPLAY_NAME = "Gemma 4 E4B"

    /**
     * The unsuffixed `.litertlm` is the on-device build for Android, iOS and desktop, and it
     * serves both the CPU and GPU backends — the backend is chosen at runtime in [EngineConfig],
     * not by the file.
     *
     * Do not substitute the `-gpu` or `-web` files from the same repo. Despite the name, `-gpu`
     * is the WebGPU build: it is byte-for-byte the same size as `-web` (2,969,059,328) and matches
     * the "Web" row of the model card, which notes that web uses a specially optimized, text-only
     * model. Loading it on Android succeeds and then emits raw vocab tokens
     * (`<unused30>`, `[multimodal]`, `<mask>`…) instead of text.
     */
    const val FILE_NAME = "gemma-4-E4B-it.litertlm"

    const val DOWNLOAD_URL =
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/$FILE_NAME"

    /** Exact size of the published file, used for progress and for the free-space check. */
    const val SIZE_BYTES = 3_659_530_240L

    /** Headroom on top of the model itself so the device is not left with a full disk. */
    const val REQUIRED_FREE_BYTES = SIZE_BYTES + 512L * 1024 * 1024

    /** The extension the file picker should accept when importing a model from storage. */
    const val FILE_EXTENSION = ".litertlm"

    val FILE = ModelFile(
        fileName = FILE_NAME,
        downloadUrl = DOWNLOAD_URL,
        sizeBytes = SIZE_BYTES,
        directory = "models",
        requiredFreeBytes = REQUIRED_FREE_BYTES,
    )
}
