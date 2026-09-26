package com.local.assistant.web

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.local.assistant.memory.tools.WebResult
import com.local.assistant.memory.tools.WebResults
import com.local.assistant.memory.tools.WebSearch
import com.local.assistant.memory.tools.WebSearchError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Web search through Tavily (https://tavily.com), with the user's own API key. A basic search
 * costs one credit; the free tier has 1,000 a month. Only the query the model writes is sent.
 */
class TavilySearch(
    private val key: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = ENDPOINT,
) : WebSearch {

    override val enabled: Boolean
        get() = !key().isNullOrBlank()

    override suspend fun search(query: String, news: Boolean): WebResults = withContext(Dispatchers.IO) {
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: throw WebSearchError("Web search isn't set up. The user can add a Tavily key under Web search in the menu.")
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body(query, news).toRequestBody(JSON))
            .build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw WebSearchError("The web search couldn't reach the internet. The phone may be offline.")
        }
        when (code) {
            200 -> parse(text)
            401 -> throw WebSearchError("The Tavily key was refused. The user can check it under Web search in the menu.")
            429 -> throw WebSearchError("Too many web searches just now. Try again in a minute.")
            432, 433 -> throw WebSearchError("The Tavily account has used up its searches for now.")
            else -> throw WebSearchError("The web search failed ($code). Try again later.")
        }
    }

    companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
        private val JSON = "application/json".toMediaType()
        private const val MAX_RESULTS = 5

        fun body(query: String, news: Boolean): String = Gson().toJson(
            linkedMapOf(
                "query" to query,
                "search_depth" to "basic",
                "topic" to if (news) "news" else "general",
                "max_results" to MAX_RESULTS,
                "include_answer" to "basic",
            ),
        )

        fun parse(json: String): WebResults {
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: throw WebSearchError("The web search sent back something unreadable.")
            val results = root.getAsJsonArray("results")?.mapNotNull { element ->
                val result = element as? JsonObject ?: return@mapNotNull null
                val url = result.text("url") ?: return@mapNotNull null
                WebResult(title = result.text("title").orEmpty(), url = url, snippet = result.text("content").orEmpty().replace(Regex("\\s+"), " ").trim())
            }.orEmpty()
            return WebResults(answer = root.text("answer")?.trim()?.takeIf { it.isNotEmpty() }, results = results)
        }

        private fun JsonObject.text(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
