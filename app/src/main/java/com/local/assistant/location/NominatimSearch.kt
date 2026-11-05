package com.local.assistant.location

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Finds shops, chains and addresses on OpenStreetMap, through its Nominatim search: "DMart"
 * around Bengaluru came back as 20 branches, each a supermarket with its own street (5 Nov 2026).
 * No key and no account.
 *
 * What is sent: the place's name as the user said it, and a box about 30 km across around their
 * area — its centre rounded to 0.05° (see [GeoPoint.rounded]), or a town they named. Never the
 * reminder, nor where exactly they are. Nominatim's terms ask for an identifying user agent and
 * at most one request a second, so requests wait their turn.
 */
class NominatimSearch(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = ENDPOINT,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaceSearch {

    private val turn = Mutex()
    private var lastAt = 0L

    override suspend fun search(query: String, around: GeoPoint, spanDeg: Double): List<FoundPlace> = turn.withLock {
        val wait = lastAt + MIN_GAP_MS - clock()
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) { fetch(url(endpoint, query, around, spanDeg)) }
        } finally {
            lastAt = clock()
        }
    }

    private fun fetch(url: HttpUrl): List<FoundPlace> {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept-Language", "en").build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            // Only the kind of failure (a timeout, no DNS): the URL holds the user's area.
            Log.w(TAG, "Map search failed: ${e.javaClass.simpleName}")
            throw PlaceSearchError("The map search couldn't reach the internet; the phone may be offline.")
        }
        return when (code) {
            200 -> parse(text)
            429 -> throw PlaceSearchError("The map search is busy just now. Try again in a minute.")
            else -> throw PlaceSearchError("The map search failed ($code). Try again later.")
        }
    }

    companion object {
        private const val TAG = "NominatimSearch"
        const val ENDPOINT = "https://nominatim.openstreetmap.org/search"
        private const val USER_AGENT = "LocalAssistant/0.2 (Android, on-device assistant; https://github.com/taslimmuhammed/local_assistant_v2)"
        private const val MIN_GAP_MS = 1_100L

        /** Enough for every branch of a chain in a city's box (Bengaluru had 20 DMarts). */
        private const val LIMIT = 30

        /** The request: the name, and the box it has to be in. */
        fun url(endpoint: String, query: String, around: GeoPoint, spanDeg: Double): HttpUrl {
            fun deg(value: Double) = String.format(Locale.ROOT, "%.3f", value)
            val box = listOf(around.lon - spanDeg, around.lat + spanDeg, around.lon + spanDeg, around.lat - spanDeg).joinToString(",") { deg(it) }
            return endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", LIMIT.toString())
                .addQueryParameter("viewbox", box)
                .addQueryParameter("bounded", "1")
                .build()
        }

        /** Nominatim's answer, in its order (best match first). */
        fun parse(json: String): List<FoundPlace> {
            val array = runCatching { JsonParser.parseString(json) as? JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val lat = item.string("lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = item.string("lon")?.toDoubleOrNull() ?: return@mapNotNull null
                val display = item.string("display_name").orEmpty()
                val name = item.string("name")?.takeIf { it.isNotBlank() } ?: display.substringBefore(',').trim()
                if (name.isEmpty()) return@mapNotNull null
                val point = GeoPoint(lat, lon)
                FoundPlace(name, point, PlaceSearch.radiusFor(halfDiagonal(item, point)), label(display, name), kind(item))
            }
        }

        private fun kind(item: JsonObject): FoundPlace.Kind = when (item.string("category")) {
            "boundary" -> FoundPlace.Kind.AREA
            "place" -> if (item.string("type") == "house") FoundPlace.Kind.SPOT else FoundPlace.Kind.SETTLEMENT
            else -> FoundPlace.Kind.SPOT
        }

        /** Half the box's diagonal: how far the place reaches from its middle. */
        private fun halfDiagonal(item: JsonObject, point: GeoPoint): Double {
            val box = (item.get("boundingbox") as? JsonArray)?.mapNotNull { it.asString.toDoubleOrNull() }
            if (box == null || box.size != 4) return 0.0
            return GeoPoint(box[0], box[2]).metresTo(GeoPoint(box[1], box[3])) / 2
        }

        /** "DMart, Hoodi Main Road, Hoodi, Bengaluru, …" says which DMart by "Hoodi Main Road". */
        private fun label(display: String, name: String): String? =
            display.split(',').map { it.trim() }.drop(1).firstOrNull { it.isNotEmpty() && it != name && !it.all(Char::isDigit) }

        private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
