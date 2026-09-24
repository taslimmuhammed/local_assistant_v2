package com.local.assistant.memory.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderCatalogTest {

    @Test
    fun theBuiltEmbeddingGemmaBundleIsRecognisedByName() {
        val spec = EmbedderCatalog.forImport("embeddinggemma-300m_wi8.litertlm")
        assertSame(EmbedderCatalog.EMBEDDING_GEMMA, spec)
        assertEquals("task: search result | query: ", spec.queryPrefix)
        assertEquals("title: none | text: ", spec.documentPrefix)
        assertEquals(0.42f, spec.similarityThreshold)
        assertSame(spec, EmbedderCatalog.byKey(spec.key))
    }

    @Test
    fun anotherEmbeddingGemmaFileKeepsThePromptsButNotTheKey() {
        val other = EmbedderCatalog.forImport("EmbeddingGemma-300M_seq512.litertlm")
        assertEquals("task: search result | query: ", other.queryPrefix)
        assertNotEquals("vectors from a different build are never mixed", EmbedderCatalog.EMBEDDING_GEMMA.key, other.key)
        assertEquals(other, EmbedderCatalog.byKey(other.key))
    }

    @Test
    fun graniteIsTheDownloadAndUsesBareText() {
        assertSame(EmbedderCatalog.GRANITE, EmbedderCatalog.DEFAULT)
        assertSame(EmbedderCatalog.GRANITE, EmbedderCatalog.forImport("granite-embedding-311m-r2_wi8fc.litertlm"))
        assertEquals("", EmbedderCatalog.GRANITE.queryPrefix)
        assertTrue(EmbedderCatalog.GRANITE.modelId.endsWith("@256"))
    }
}
