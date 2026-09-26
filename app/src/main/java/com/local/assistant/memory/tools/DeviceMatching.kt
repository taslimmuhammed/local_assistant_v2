package com.local.assistant.memory.tools

import java.text.Normalizer
import java.util.Locale

/** Words of a name as matching sees them: lowercase, no punctuation, no "Dr."/"ji". */
private fun nameWords(text: String): List<String> =
    Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
        .filter { it.isNotEmpty() && it !in NAME_FILLERS }

private val NAME_FILLERS = setOf(
    "my", "the", "dr", "mr", "mrs", "ms", "miss", "prof", "shri", "sri", "smt", "ji", "sir", "madam",
    "app", "application",
)

/**
 * Finds the contact the user means. Candidates are tried in order — the words the user said, then
 * what memory knows (a name, the words the user calls them by) — and the first that matches
 * anyone decides. Within it a whole-name match beats a contact whose name merely contains the
 * words; if that still leaves more than one person, the user is asked.
 */
object ContactMatcher {

    sealed interface Match {
        data class One(val contact: Contact) : Match
        /** Too many people fit; how to tell them apart when asking. */
        data class Several(val labels: List<String>) : Match
        data object None : Match
    }

    fun find(candidates: List<String>, contacts: List<Contact>): Match {
        val indexed = contacts.map { it to nameWords(it.name) }
        for (candidate in candidates) {
            val words = nameWords(candidate)
            if (words.isEmpty()) continue
            val exact = indexed.filter { (_, name) -> name == words }.map { it.first }
            val containing = indexed.filter { (_, name) -> name.containsAll(words) }.map { it.first }
            val best = exact.ifEmpty { containing }.distinctBy { it.name.lowercase(Locale.ROOT) to it.phones.firstOrNull()?.number }
            when {
                best.size == 1 -> return Match.One(best.single())
                best.size > 1 -> return Match.Several(best.take(MAX_LISTED).map(::label))
            }
        }
        return Match.None
    }

    /** The number to call: the one marked default, else a mobile, else the first. */
    fun number(contact: Contact): String? =
        (contact.phones.firstOrNull { it.primary } ?: contact.phones.firstOrNull { it.mobile } ?: contact.phones.firstOrNull())?.number

    /** "Priya Sharma (…4455)": the last digits tell apart two people with one name. */
    private fun label(contact: Contact): String {
        val digits = number(contact)?.filter { it.isDigit() }?.takeLast(4)
        return if (digits.isNullOrEmpty()) contact.name else "${contact.name} (…$digits)"
    }

    private const val MAX_LISTED = 4
}

/**
 * What open_app should open: an installed app by its label, or a search inside a well-known kind
 * of app — directions, songs, videos, the web, the Play Store — which work through standard
 * intents whichever app answers them.
 */
object AppMatcher {

    sealed interface Result {
        data class Found(val target: AppTarget, val note: String? = null) : Result
        data class Several(val labels: List<String>) : Result
        data object None : Result
    }

    fun resolve(app: String, query: String?, installed: List<InstalledApp>): Result {
        val words = nameWords(app)
        val key = words.joinToString(" ")
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val named = byLabel(words, installed)
        val single = (named as? Result.Found)?.let { (it.target as AppTarget.Launch).app }

        if (q != null) {
            return when {
                key in MAPS -> Result.Found(AppTarget.Directions(place(q)))
                key in BROWSER -> Result.Found(AppTarget.WebSearch(q))
                key in STORE -> Result.Found(AppTarget.StoreSearch(q))
                key in VIDEOS || single?.packageName == YOUTUBE -> Result.Found(AppTarget.Videos(q, single?.takeIf { it.packageName == YOUTUBE }))
                key in MUSIC -> Result.Found(AppTarget.Play(q, null))
                single != null && single.packageName in MUSIC_APPS -> Result.Found(AppTarget.Play(q, single))
                single != null && single.packageName == MAPS_APP -> Result.Found(AppTarget.Directions(place(q)))
                single != null -> Result.Found(AppTarget.Launch(single), note = "It opened ${single.label}; it can't search inside it.")
                else -> named
            }
        }
        return when {
            key in CAMERA -> Result.Found(AppTarget.Camera)
            key in SETTINGS -> Result.Found(AppTarget.Settings)
            named != Result.None -> named
            key in MAPS -> installed.firstOrNull { it.packageName == MAPS_APP }?.let { Result.Found(AppTarget.Launch(it)) } ?: Result.None
            key in MUSIC -> installed.firstOrNull { it.packageName in MUSIC_APPS }?.let { Result.Found(AppTarget.Launch(it)) } ?: Result.None
            else -> Result.None
        }
    }

    /** "directions to Indiranagar" → "Indiranagar": the query is the place, not the request. */
    private fun place(query: String): String = query.replace(DIRECTIONS_PREFIX, "").trim().ifEmpty { query }

    /** An exact label wins; then labels that start with the words; then labels containing them. */
    private fun byLabel(words: List<String>, installed: List<InstalledApp>): Result {
        if (words.isEmpty()) return Result.None
        val joined = words.joinToString("")
        val indexed = installed.map { it to nameWords(it.label) }
        val tiers = listOf(
            indexed.filter { (_, label) -> label == words || label.joinToString("") == joined },
            indexed.filter { (_, label) -> label.size > words.size && label.subList(0, words.size) == words },
            indexed.filter { (_, label) -> label.containsAll(words) },
        )
        for (tier in tiers) {
            val apps = tier.map { it.first }.distinctBy { it.packageName }
            when {
                apps.size == 1 -> return Result.Found(AppTarget.Launch(apps.single()))
                // "YouTube" and "YouTube Music" both start with "youtube": the shorter name is the one asked for.
                apps.size > 1 -> {
                    val shortest = apps.minBy { it.label.length }
                    val tied = apps.count { it.label.length == shortest.label.length }
                    return if (tied == 1) Result.Found(AppTarget.Launch(shortest)) else Result.Several(apps.take(4).map { it.label })
                }
            }
        }
        return Result.None
    }

    private val DIRECTIONS_PREFIX = Regex("^(?i)(?:get\\s+)?(?:directions|route|way|navigate|navigation|take me)\\s+(?:to\\s+)?")

    private const val YOUTUBE = "com.google.android.youtube"
    private const val MAPS_APP = "com.google.android.apps.maps"
    private val MUSIC_APPS = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats",
        "com.gaana", "com.bsbportal.music", "com.apple.android.music", "com.amazon.mp3",
    )

    private val MAPS = setOf("maps", "map", "google maps", "directions", "direction", "navigation", "navigate", "route")
    private val BROWSER = setOf("browser", "google", "chrome", "web", "internet", "search", "google search", "web search")
    private val STORE = setOf("play store", "playstore", "store", "google play", "app store")
    private val VIDEOS = setOf("youtube", "videos", "video", "yt")
    private val MUSIC = setOf("music", "songs", "song", "music player", "radio")
    private val CAMERA = setOf("camera", "cam")
    private val SETTINGS = setOf("settings", "setting", "phone settings")
}
