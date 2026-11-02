package com.local.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReplayTest {

    private val photos = """{"arguments":{"person":"me","query":"sunset"},"chip":{"kind":"PHOTOS","label":"Photos","detail":"You · sunset"},""" +
        """"result":"{\"ok\":true,\"found\":3,\"people\":[\"taslim\"]}","tool":"find_photos","undone":false}"""

    @Test
    fun `a stored call is replayed as made, with what it returned`() {
        assertEquals("find_photos" to mapOf("person" to "me", "query" to "sunset"), ToolReplay.call(photos))
        assertEquals(mapOf("ok" to true, "found" to 3.0, "people" to listOf("taslim")), ToolReplay.result(photos))
    }

    @Test
    fun `long results are cut short`() {
        val answer = "a".repeat(1000)
        val web = """{"arguments":{"query":"weather"},"result":"{\"ok\":true,\"answer\":\"$answer\",\"sources\":[1,2,3,4,5,6,7,8]}","tool":"web_lookup"}"""
        val result = ToolReplay.result(web)
        assertTrue((result["answer"] as String).length <= ToolReplay.MAX_TEXT + 1)
        assertEquals(ToolReplay.MAX_ITEMS, (result["sources"] as List<*>).size)
    }

    @Test
    fun `a row that isn't a call replays as nothing`() {
        assertNull(ToolReplay.call("not json"))
        assertEquals(mapOf("ok" to false), ToolReplay.result("not json"))
    }
}
