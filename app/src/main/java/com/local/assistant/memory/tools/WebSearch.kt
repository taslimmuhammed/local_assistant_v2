package com.local.assistant.memory.tools

import java.net.URI
import java.util.Locale

/** One result as the model sees it: short, with the site it came from. */
data class WebResult(val title: String, val url: String, val snippet: String) {
    val site: String
        get() = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}

data class WebResults(val answer: String?, val results: List<WebResult>)

/** The internet, for what the model can't know: news, weather, prices, scores. */
interface WebSearch {
    /** Whether the user has set it up; the tool is only declared when they have. */
    val enabled: Boolean

    /** Throws [WebSearchError] with a message the model can pass on. */
    suspend fun search(query: String, news: Boolean): WebResults
}

class WebSearchError(message: String) : Exception(message)

/**
 * web_search: the query goes to the provider — the only thing that leaves the phone — and the
 * answer and top results come back cut down to what the tool-round budget can hold.
 */
class WebTools(private val web: WebSearch) {

    internal suspend fun search(args: Args): ActionResult {
        val query = args.required("query").take(MAX_QUERY)
        val news = args.text("topic")?.lowercase(Locale.ROOT)?.contains("news") == true
        val found = try {
            web.search(query, news)
        } catch (e: WebSearchError) {
            throw ToolError(e.message.orEmpty())
        }
        if (found.answer.isNullOrBlank() && found.results.isEmpty()) {
            throw ToolError("The web search found nothing for '$query'. Say so, or try other words.")
        }
        val sources = found.results.take(MAX_SOURCES).map {
            linkedMapOf("site" to it.site, "title" to it.title.take(MAX_TITLE), "text" to it.snippet.take(MAX_SNIPPET))
        }
        return ActionResult(
            linkedMapOf(
                "ok" to true,
                "answer" to found.answer?.take(MAX_ANSWER),
                "sources" to sources,
            ).filterValues { it != null },
            MemoryChip(
                MemoryChip.Kind.WEB,
                "Searched",
                query,
                note = found.results.firstOrNull()?.site?.let { "Top source: $it" },
                link = found.results.firstOrNull()?.url,
            ),
        )
    }

    private companion object {
        const val MAX_QUERY = 300
        const val MAX_SOURCES = 3

        // About 350 tokens in all, inside the 450 the budget keeps for tool rounds.
        const val MAX_ANSWER = 500
        const val MAX_TITLE = 90
        const val MAX_SNIPPET = 260
    }
}
