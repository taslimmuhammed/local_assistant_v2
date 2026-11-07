package com.local.assistant.memory.embed

/**
 * Memory search's model: an embedder the user imported when there is one, else the photo
 * model's text side. Switching changes [modelId], so the archive is embedded again.
 */
class MemoryEmbedder(
    private val imported: Embedder,
    private val photo: Embedder,
    private val useImported: () -> Boolean,
) : Embedder {

    private val current: Embedder get() = if (useImported()) imported else photo

    override val modelId: String? get() = current.modelId

    override val similarityThreshold: Float get() = current.similarityThreshold

    override val searchThreshold: Float get() = current.searchThreshold

    override val isReady: Boolean get() = current.isReady

    override suspend fun embed(text: String, kind: EmbedKind): FloatArray? = current.embed(text, kind)

    override suspend fun prepare(): Boolean = current.prepare()

    /** Both: after a switch, the one no longer used would otherwise stay loaded. */
    override suspend fun unload() {
        imported.unload()
        photo.unload()
    }
}
