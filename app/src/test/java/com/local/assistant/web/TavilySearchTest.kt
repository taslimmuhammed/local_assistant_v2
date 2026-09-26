package com.local.assistant.web

import com.google.gson.JsonParser
import com.local.assistant.memory.tools.WebSearchError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilySearchTest {

    @Test
    fun `the request asks for a cheap search with a short answer`() {
        val body = JsonParser.parseString(TavilySearch.body("weather in Kochi", news = false)).asJsonObject
        assertEquals("weather in Kochi", body["query"].asString)
        assertEquals("basic", body["search_depth"].asString)
        assertEquals("general", body["topic"].asString)
        assertEquals("basic", body["include_answer"].asString)
        assertEquals(5, body["max_results"].asInt)
        assertEquals("news", JsonParser.parseString(TavilySearch.body("ISRO", news = true)).asJsonObject["topic"].asString)
    }

    @Test
    fun `answers and results are read, and results without a url dropped`() {
        val found = TavilySearch.parse(
            """{"query":"q","answer":" It is 29°C and humid in Kochi. ","results":[
                {"title":"Kochi weather","url":"https://www.weather.com/kochi","content":"Humid,\n  29°C","score":0.9},
                {"title":"no url","content":"x"},
                {"title":"IMD","url":"https://mausam.imd.gov.in/","content":"Forecast"}],"response_time":0.8}""",
        )
        assertEquals("It is 29°C and humid in Kochi.", found.answer)
        assertEquals(2, found.results.size)
        assertEquals("Humid, 29°C", found.results[0].snippet)
        assertEquals("weather.com", found.results[0].site)
        assertEquals("mausam.imd.gov.in", found.results[1].site)
    }

    @Test
    fun `no answer is fine, nonsense is an error`() {
        assertNull(TavilySearch.parse("""{"results":[]}""").answer)
        assertThrows(WebSearchError::class.java) { TavilySearch.parse("<html>") }
    }

    @Test
    fun `without a key it is off and says how to set it up`() {
        val tavily = TavilySearch(key = { null })
        assertFalse(tavily.enabled)
        val error = assertThrows(WebSearchError::class.java) { runBlocking { tavily.search("x", news = false) } }
        assertTrue(error.message!!.contains("Web search in the menu"))
    }
}
