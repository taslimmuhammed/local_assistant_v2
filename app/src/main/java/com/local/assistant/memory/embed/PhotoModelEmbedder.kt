package com.local.assistant.memory.embed

import com.local.assistant.gallery.GalleryEmbedder
import com.local.assistant.gallery.GalleryModel

/**
 * Memory search on the photo model's text side ([GalleryModel], EmbeddingGemma 2): one engine
 * for photos and memory, instead of a second model just for text.
 *
 * EmbeddingGemma 300M did this before (2026-11-07). On the OnePlus (`TextEmbedderEvalTest`,
 * 2026-11-06) the two were both loaded during chats, ≈250 MB each; this keeps one. On the same
 * 61 labelled pairs, 27 related and 34 not, scored as the app scores them (256-d, int8), both
 * recall 73% of related pairs at the chosen threshold, where this model's precision is ≈0.90
 * against 0.95. Asked for no wrong pair at all it does worse: 54% of related pairs against 69%.
 * Other prompts and longer vectors were no better.
 *
 * Its similarities sit high and close together: related pairs had a median of 0.788 (lowest
 * 0.681), unrelated ones 0.660 (highest 0.772). Hence [similarityThreshold] at 0.75, where 73% are
 * recalled at ≥0.9 precision (0.78 for none wrong recalls 54%). When the model asks itself
 * ([searchThreshold]) the bar is just under the lowest related pair: the 0.1 the older model took
 * off its 0.42 was about half the gap between its medians (0.47 and 0.25), and half of this one's
 * is 0.06–0.07.
 */
class PhotoModelEmbedder(private val model: GalleryEmbedder) : Embedder {

    override val modelId: String? get() = if (model.isInstalled) MODEL_ID else null

    override val similarityThreshold: Float = 0.75f

    override val searchThreshold: Float = 0.68f

    override val isReady: Boolean get() = model.isLoaded

    /**
     * Longer inputs than the bundle's longest signature are an error rather than being cut, so
     * the text is capped up front and halved on refusal, as [LiteRtEmbedder] does.
     */
    override suspend fun embed(text: String, kind: EmbedKind): FloatArray? {
        val prefix = if (kind == EmbedKind.QUERY) GalleryModel.QUERY_PREFIX else DOCUMENT_PREFIX
        var body = (if (kind == EmbedKind.QUERY) text.trim() else text).take(MAX_CHARS)
        while (true) {
            model.embedText(prefix + body)?.let { return it }
            // Not loaded: no model, or it failed to open; halving won't help.
            if (!model.isLoaded || body.length <= MIN_CHARS) return null
            body = body.take(body.length / 2)
        }
    }

    override suspend fun prepare(): Boolean = model.prepare()

    override suspend fun unload() = model.unloadText()

    private companion object {
        /** Its own name, not [GalleryModel.MODEL_ID]: text vectors don't depend on the image budget. */
        val MODEL_ID = "${GalleryModel.KEY}@${Int8Vectors.DIMENSIONS}"

        /** EmbeddingGemma's document prompt, from its model card, as for the older model. */
        const val DOCUMENT_PREFIX = "title: none | text: "

        const val MAX_CHARS = 1_200
        const val MIN_CHARS = 150
    }
}
