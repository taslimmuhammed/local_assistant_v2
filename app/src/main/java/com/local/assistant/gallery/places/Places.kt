package com.local.assistant.gallery.places

import java.io.InputStream
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.sync.withLock

/**
 * Towns, states and countries, offline: GeoNames' places of 5,000 people or more (about 70,000),
 * with their Latin-letter other names ("Cochin" for Kochi), states and countries. Built by
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
    private val population: IntArray,
    /** Every lowercase name and other name, to the towns that have it. */
    private val byName: Map<String, IntArray>,
    /** "IN.13" to "Kerala", and back. */
    private val regionNames: Map<String, String>,
    private val regionCodes: Map<String, String>,
    private val countryNames: Map<String, String>,
    private val countryCodes: Map<String, String>,
) {

    /** A photo's place: its nearest town, and that town's state ("IN.13") and country ("IN"). */
    data class Where(val town: String, val region: String, val country: String)

    /** What a place name stands for. */
    sealed interface Area {
        /** Within [radiusKm] of any of [points], for a town. */
        data class Near(val name: String, val points: List<Pair<Double, Double>>, val radiusKm: Double) : Area

        data class InRegion(val name: String, val code: String) : Area

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
        return if (best < 0) null else Where(names[best], "${country[best]}.${region[best]}", country[best])
    }

    /**
     * What [name] stands for: null when it names no town, state or country. [prefer] says which
     * towns of that name the user has photos near, given their points and the radius. Those win;
     * then a state or country of that name (Goa the state, not Goa the Philippine town); then the
     * biggest town of the name.
     */
    fun resolve(
        name: String,
        prefer: (List<Pair<Double, Double>>, Double) -> List<Pair<Double, Double>> = { points, _ -> points },
    ): Area? {
        val key = name.trim().lowercase(Locale.ROOT).removePrefix("the ").trim()
        if (key.isEmpty()) return null
        val towns = byName[key]
        val biggest = towns?.maxBy { population[it] }
        val radius = biggest?.let { radiusKm(population[it]) } ?: 0.0
        if (towns != null) {
            val withPhotos = prefer(towns.map { lat[it].toDouble() to lon[it].toDouble() }, radius)
            if (withPhotos.isNotEmpty()) return Area.Near(names[biggest!!], withPhotos, radius)
        }
        regionCodes[key]?.let { return Area.InRegion(regionNames.getValue(it), it) }
        countryCodes[key]?.let { return Area.InCountry(countryNames.getValue(it), it) }
        return biggest?.let { Area.Near(names[it], listOf(lat[it].toDouble() to lon[it].toDouble()), radius) }
    }

    companion object {

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

        /** Reads the place list (TSV, see [Places]). */
        fun read(tsv: InputStream): Places {
            val names = ArrayList<String>(75_000)
            val lat = ArrayList<Float>(75_000)
            val lon = ArrayList<Float>(75_000)
            val country = ArrayList<String>(75_000)
            val region = ArrayList<String>(75_000)
            val population = ArrayList<Int>(75_000)
            val byName = HashMap<String, MutableList<Int>>(200_000)
            val regionNames = HashMap<String, String>()
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
                            (listOf(f[1]) + f[2].split(',')).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
                                .forEach { byName.getOrPut(it) { ArrayList(1) } += i }
                        }
                        "R" -> regionNames[f[1]] = f[2]
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
                population.toIntArray(),
                byName.mapValues { it.value.toIntArray() },
                regionNames,
                regionNames.entries.associate { it.value.lowercase(Locale.ROOT) to it.key },
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
