package com.local.assistant.memory.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderCatalogTest {

    @Test
    fun anImportedEmbeddingGemmaGetsItsPromptsAndAKeyOfItsOwn() {
        val spec = EmbedderCatalog.forImport("EmbeddingGemma-300M_seq512.litertlm")
        assertEquals("task: search result | query: ", spec.queryPrefix)
        assertEquals("title: none | text: ", spec.documentPrefix)
        assertEquals(spec, EmbedderCatalog.byKey(spec.key))
        val other = EmbedderCatalog.forImport("embeddinggemma-300m_wi8.litertlm")
        assertNotEquals("vectors from a different file are never mixed", spec.key, other.key)
    }

    @Test
    fun theOldEmbeddingGemmaKeyIsNoLongerAModel() {
        // Its file is deleted at launch; until then it mustn't pass for another model.
        assertNull(EmbedderCatalog.byKey("embeddinggemma-300m-wi8"))
    }

    @Test
    fun embeddersAreOnlyImportedNow() {
        assertNull(EmbedderCatalog.FILE.downloadUrl)
    }

    @Test
    fun graniteIsStillRecognisedAndUsesBareText() {
        assertSame(EmbedderCatalog.GRANITE, EmbedderCatalog.byKey(EmbedderCatalog.GRANITE.key))
        assertSame(EmbedderCatalog.GRANITE, EmbedderCatalog.forImport("granite-embedding-311m-r2_wi8fc.litertlm"))
        assertEquals("", EmbedderCatalog.GRANITE.queryPrefix)
        assertTrue(EmbedderCatalog.GRANITE.modelId.endsWith("@256"))
    }
}
