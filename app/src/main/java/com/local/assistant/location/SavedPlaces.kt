package com.local.assistant.location

import com.google.gson.Gson
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.db.MemoryStore
import com.local.assistant.memory.db.PlaceSpot

/**
 * The user's home and office for place reminders. Where they are is what the user told the
 * assistant (a `save_fact` like "my office is in Infopark, Kakkanad"); where that is on the map
 * is looked up the first time and kept in app state ([get], [put]) beside the words it came
 * from, so it's looked up again only when they change.
 */
class SavedPlaces(
    private val memory: MemoryStore,
    private val get: suspend (String) -> String?,
    private val put: suspend (String, String) -> Unit,
) : OwnPlaces {

    private data class Kept(val address: String, val spot: PlaceSpot)

    override suspend fun address(key: String): String? {
        val attributes = ATTRIBUTES[key] ?: return null
        return memory.searchFacts(listOf(key) + attributes, 20)
            .filter { it.subject == FactKeys.USER && it.attribute in attributes }
            .minByOrNull { attributes.indexOf(it.attribute) }
            ?.value
    }

    override suspend fun spot(key: String, address: String): PlaceSpot? =
        get(STATE_PREFIX + key)
            ?.let { runCatching { gson.fromJson(it, Kept::class.java) }.getOrNull() }
            ?.takeIf { it.address == address }
            ?.spot

    override suspend fun keep(key: String, address: String, spot: PlaceSpot) = put(STATE_PREFIX + key, gson.toJson(Kept(address, spot)))

    private companion object {
        const val STATE_PREFIX = "place."
        val gson = Gson()

        /** The attributes a home or office is saved under, the likeliest first. */
        val ATTRIBUTES = mapOf(
            "home" to listOf("home", "home_address", "address", "house", "home_location"),
            "office" to listOf("office", "office_address", "workplace", "work", "work_address", "office_location", "work_location"),
        )
    }
}
