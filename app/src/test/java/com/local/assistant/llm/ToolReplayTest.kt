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
        assertEquals(mapOf("ok" to true, "found" to 3L, "people" to listOf("taslim")), ToolReplay.result(photos))
    }

    @Test
    fun `whole numbers reach the model whole, decimals as decimals`() {
        @Suppress("UNCHECKED_CAST")
        val result = ToolReplay.gson.fromJson("""{"steps_left_to_goal":7740,"distance_km":6.24}""", Map::class.java) as Map<String, Any?>
        // As a Double it was shown to the model as 7740.0, and the reply said "7740.0 steps".
        assertEquals(7740L, result["steps_left_to_goal"])
        assertEquals("7740", result["steps_left_to_goal"].toString())
        assertEquals(6.24, result["distance_km"])
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
