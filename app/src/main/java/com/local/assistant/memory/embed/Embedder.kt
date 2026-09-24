package com.local.assistant.memory.embed

/** Which side of a search a text is on; some models are trained with a prompt for each. */
enum class EmbedKind { QUERY, DOCUMENT }

/**
 * Turns text into vectors for the archive.
 *
 * Vectors from different models live in different spaces and are never compared: every stored
 * vector carries the [modelId] that made it, and changing model means re-embedding the archive.
 */
interface Embedder {

    /** The model now installed, as "key@dimensions"; null when there is none. */
    val modelId: String?

    /** The cosine similarity above which a recalled snippet counts as relevant for this model. */
    val similarityThreshold: Float

    /** Whether a query can be embedded right now without first loading the model. */
    val isReady: Boolean

    /**
     * A normalised vector of at least [Int8Vectors.DIMENSIONS] floats, or null when no model is
     * installed or it failed. Loads the model on first use, which takes a couple of seconds.
     */
    suspend fun embed(text: String, kind: EmbedKind): FloatArray?

    /** Loads the model if it is installed and not loaded yet. True when it is ready. */
    suspend fun prepare(): Boolean

    /** Frees the model's memory; the next [embed] loads it again. */
    suspend fun unload()
}
