package com.local.assistant.gallery.places

import java.io.InputStream
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.sync.withLock

/**
 * Towns, districts, states and countries, offline: GeoNames' places of 5,000 people or more (about
 * 70,000), with their Latin-letter other names ("Cochin" for Kochi), their districts ("Idukki", where
 * Munnar is), states and countries. Built by
 * tools/places/build_places.py into assets/places/places.tsv (5.6 MB, 2.7 MB in the APK); GeoNames, CC BY 4.0.
 *
 * Two jobs: the town nearest a photo, worked out once when it's indexed; and what area a place
 * name the user says stands for.
 */
class Places private constructor(
    private val names: Array<String>,
    private val lat: FloatArray,
    private val lon: FloatArray,
    private val country: Array<String>,
    private val region: Array<String>,
    /** "IN.13.596", or "" when GeoNames gives none. */
    private val district: Array<String>,
    private val population: IntArray,
    /** Every lowercase name and other name, to the towns that have it. */
    private val byName: Map<String, IntArray>,
    /** "IN.13" to "Kerala"; and back, also by the parts around "and" ("kashmir" for Jammu and Kashmir). */
    private val regionNames: Map<String, String>,
    private val regionCodes: Map<String, List<String>>,
    /** "IN.13.596" to "Idukki"; and back, also without "District" ("thrissur" for Thrissur District). */
    private val districtNames: Map<String, String>,
    private val districtCodes: Map<String, List<String>>,
    private val countryNames: Map<String, String>,
    private val countryCodes: Map<String, String>,
) {

    /** A photo's place: its nearest town, and that town's district ("IN.13.596", or ""), state ("IN.13") and country ("IN"). */
    data class Where(val town: String, val district: String, val region: String, val country: String)

    /** What a place name stands for. */
    sealed interface Area {
        /** Within [radiusKm] of any of [points], for a town. */
        data class Near(val name: String, val points: List<Pair<Double, Double>>, val radiusKm: Double) : Area

        /** Photos whose nearest town is in one of [codes]. */
        data class InDistrict(val name: String, val codes: List<String>) : Area

        data class InRegion(val name: String, val codes: List<String>) : Area

        data class InCountry(val name: String, val code: String) : Area
    }

    /** Towns by one-degree cell, for finding the nearest one without measuring to all 70,000. */
    private val cells: Map<Long, IntArray> by lazy {
        val grouped = HashMap<Long, MutableList<Int>>()
        for (i in names.indices) grouped.getOrPut(cell(lat[i].toDouble(), lon[i].toDouble())) { ArrayList() } += i
        grouped.mapValues { it.value.toIntArray() }
    }

    /** The nearest town within about 150 km, or null out at sea or in empty country. */
    fun nearest(atLat: Double, atLon: Double): Where? {
        var best = -1
        var bestKm = Double.MAX_VALUE
        val cy = floor(atLat).toInt()
        val cx = floor(atLon).toInt()
        for (dy in -1..1) for (dx in -1..1) {
            val towns = cells[key(cy + dy, cx + dx)] ?: continue
            for (i in towns) {
                val km = km(atLat, atLon, lat[i].toDouble(), lon[i].toDouble())
                if (km < bestKm) {
                    bestKm = km
                    best = i
                }
            }
        }
        return if (best < 0) null else Where(names[best], district[best], "${country[best]}.${region[best]}", country[best])
    }

    /**
     * What [name] stands for: null when it names no town, district, state or country. [prefer]
     * says which towns of that name the user has photos near, given their points and the radius.
     * Those win, as their district when they're in a district of the same name ("Thrissur" is
     * the town and the district around it: the district, so the places around count too). Then a
     * district, a state or a country of that name (Goa the state, not Goa the Philippine town);
     * then the biggest town of the name. Failing all of those, a word search ([byWords]).
     *
     * An area is named in full, whatever part of it was asked for: "Kashmir" is Jammu and
     * Kashmir, and the reply says so.
     */
    fun resolve(
        name: String,
        prefer: (List<Pair<Double, Double>>, Double) -> List<Pair<Double, Double>> = { points, _ -> points },
    ): Area? {
        val key = key(name)
        if (key.isEmpty()) return null
        val towns = byName[key]
        val biggest = towns?.maxBy { population[it] }
        val radius = biggest?.let { radiusKm(population[it]) } ?: 0.0
        val districts = districtCodes[key] ?: districtCodes[DISTRICT_WORD.replace(key, "")]
        if (towns != null) {
            val withPhotos = prefer(towns.map { lat[it].toDouble() to lon[it].toDouble() }, radius).toSet()
            if (withPhotos.isNotEmpty()) {
                val chosen = towns.filter { (lat[it].toDouble() to lon[it].toDouble()) in withPhotos }
                val town = biggest!!
                // By the town's own name too: "Calicut" is Kozhikode, in Kozhikode district.
                val sameNamed = districts ?: districtCodes[key(names[town])]
                if (sameNamed != null && chosen.all { district[it] in sameNamed }) {
                    return Area.InDistrict(districtName(sameNamed.first()), sameNamed)
                }
                return Area.Near(names[town], withPhotos.toList(), radius)
            }
        }
        districts?.let { return Area.InDistrict(districtName(it.first()), it) }
        regionCodes[key]?.let { codes -> return Area.InRegion(named(codes.map(regionNames::getValue)), codes) }
        countryCodes[key]?.let { return Area.InCountry(countryNames.getValue(it), it) }
        biggest?.let { return Area.Near(names[it], listOf(lat[it].toDouble() to lon[it].toDouble()), radius) }
        return byWords(key)
    }

    /**
     * States, else districts, else countries whose names have every word of [key] ("kashmir" in
     * Azad Kashmir, "nadu" in Tamil Nadu); towns aren't searched this way, far too many share
     * words. Null when nothing has them, or more than [MAX_WORD_MATCHES] do: too loose to mean
     * anything ("north").
     */
    private fun byWords(key: String): Area? {
        val wanted = words(key)
        if (wanted.isEmpty()) return null
        fun matching(names: Map<String, String>) = names.filterValues { words(key(it)).containsAll(wanted) }.keys.sorted()
            .takeIf { it.isNotEmpty() && it.size <= MAX_WORD_MATCHES }
        matching(regionNames)?.let { return Area.InRegion(named(it.map(regionNames::getValue)), it) }
        matching(districtNames)?.let { return Area.InDistrict(named(it.map(::districtName)), it) }
        matching(countryNames)?.singleOrNull()?.let { return Area.InCountry(countryNames.getValue(it), it) }
        return null
    }

    /** "Thrissur District" as "Thrissur". */
    private fun districtName(code: String): String = districtNames.getValue(code).let { DISTRICT_WORD.replace(it, "") }

    companion object {

        const val MAX_WORD_MATCHES = 3

        /** Words that say what kind of place, not which. */
        private val KIND_WORDS = setOf("and", "the", "of", "state", "district", "province", "region", "county", "territory", "division")

        private fun words(text: String): Set<String> =
            text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in KIND_WORDS }.toSet()

        /** Areas' names as one: up to three in full ("Jammu and Kashmir, Azad Kashmir"), else two and how many more. */
        private fun named(names: List<String>): String = when {
            names.size <= 3 -> names.joinToString(", ")
            else -> "${names.take(2).joinToString(", ")} and ${names.size - 2} more"
        }

        private val DISTRICT_WORD = Regex("\\s+(district|county|division|zila|zilla)$", RegexOption.IGNORE_CASE)

        /** How a name is looked up: lowercase, no leading "the". */
        private fun key(name: String) = name.trim().lowercase(Locale.ROOT).removePrefix("the ").trim()

        /** A name as its own key, and its parts around "and" or "&" ("kashmir" for "Jammu and Kashmir"). */
        private fun keysOf(name: String): List<String> {
            val whole = key(name)
            return listOf(whole) + whole.split(Regex("\\s+(?:and|&)\\s+")).map { it.trim() }.filter { it.isNotEmpty() && it != whole }
        }

        /** Bigger towns sprawl further: a village 8 km, a city 15, a metropolis 30. */
        fun radiusKm(population: Int): Double = when {
            population < 50_000 -> 8.0
            population < 1_000_000 -> 15.0
            else -> 30.0
        }

        fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val p = Math.PI / 180
            val h = 0.5 - cos((bLat - aLat) * p) / 2 + cos(aLat * p) * cos(bLat * p) * (1 - cos((bLon - aLon) * p)) / 2
            return 12_742 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun key(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)

        private fun cell(atLat: Double, atLon: Double): Long = key(floor(atLat).toInt(), floor(atLon).toInt())

        /**
         * Every lookup key of each name to the codes that have it, whole names first: a part
         * stands for every state it's a part of, unless some state has it as its whole name.
         */
        private fun codesByName(names: Map<String, String>, keys: (String) -> List<String>): Map<String, List<String>> {
            val whole = HashMap<String, MutableList<String>>()
            val parts = HashMap<String, MutableList<String>>()
            for ((code, name) in names) {
                keys(name).forEachIndexed { i, k -> (if (i == 0) whole else parts).getOrPut(k) { ArrayList(1) } += code }
            }
            for ((k, codes) in parts) if (k !in whole) whole[k] = codes
            return whole
        }

        /** Reads the place list (TSV, see [Places]). */
        fun read(tsv: InputStream): Places {
            val names = ArrayList<String>(75_000)
            val lat = ArrayList<Float>(75_000)
            val lon = ArrayList<Float>(75_000)
            val country = ArrayList<String>(75_000)
            val region = ArrayList<String>(75_000)
            val district = ArrayList<String>(75_000)
            val population = ArrayList<Int>(75_000)
            val byName = HashMap<String, MutableList<Int>>(200_000)
            val regionNames = HashMap<String, String>()
            val districtNames = HashMap<String, String>()
            val countryNames = HashMap<String, String>()
            tsv.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t')
                    when (f[0]) {
                        "P" -> {
                            val i = names.size
                            names += f[1]
                            lat += f[3].toFloat()
                            lon += f[4].toFloat()
                            country += f[5]
                            region += f[6]
                            population += f[7].toIntOrNull() ?: 0
                            district += f.getOrNull(8)?.takeIf { it.isNotEmpty() }?.let { "${f[5]}.${f[6]}.$it" } ?: ""
                            (listOf(f[1]) + f[2].split(',')).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
                                .forEach { byName.getOrPut(it) { ArrayList(1) } += i }
                        }
                        "R" -> regionNames[f[1]] = f[2]
                        "D" -> districtNames[f[1]] = f[2]
                        "C" -> countryNames[f[1]] = f[2]
                    }
                }
            }
            return Places(
                names.toTypedArray(),
                lat.toFloatArray(),
                lon.toFloatArray(),
                country.toTypedArray(),
                region.toTypedArray(),
                district.toTypedArray(),
                population.toIntArray(),
                byName.mapValues { it.value.toIntArray() },
                regionNames,
                codesByName(regionNames) { keysOf(it) },
                districtNames,
                codesByName(districtNames) { listOf(key(DISTRICT_WORD.replace(it, ""))) },
                countryNames,
                countryNames.entries.associate { it.value.lowercase(Locale.ROOT) to it.key },
            )
        }
    }
}

/** [Places] from the APK, read once on first use (about a second) and kept. */
class PlaceList(private val assets: android.content.res.AssetManager) {

    private val lock = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var loaded: Places? = null

    suspend fun get(): Places = loaded ?: lock.withLock {
        loaded ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assets.open(ASSET).use { Places.read(it) }
        }.also { loaded = it }
    }

    private companion object {
        const val ASSET = "places/places.tsv"
    }
}
