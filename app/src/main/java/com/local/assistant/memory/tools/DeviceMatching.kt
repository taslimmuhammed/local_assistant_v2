package com.local.assistant.memory.tools

import java.text.Normalizer
import java.util.Locale

/** Words of a name as matching sees them: lowercase, no punctuation, no "Dr."/"ji". */
private fun nameWords(text: String): List<String> =
    Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .split(NAME_BREAK)
        .filter { it.isNotEmpty() && it !in NAME_FILLERS }

/** Compiled once: built for each contact's name, on the phone it took most of a lookup's time. */
private val NAME_BREAK = Regex("[^\\p{L}\\p{M}\\p{N}]+")

private val NAME_FILLERS = setOf(
    "my", "the", "dr", "mr", "mrs", "ms", "miss", "prof", "shri", "sri", "smt", "ji", "sir", "madam",
    "app", "application",
)

/**
 * A word of a name as it sounds, near enough: one letter for a run of the same ("Umma" and
 * "Uma", "Jaffar" and "Jafar", "Shiyaas" and "Shiyas"). Doubled letters are what come and go
 * when a name is written down from speech: the recogniser wrote "Umma" as "Uma" (the panel,
 * 4 Nov 2026), and no contact matched.
 */
private fun soundOf(word: String): String = word.replace(REPEATED_LETTER, "$1")

private val REPEATED_LETTER = Regex("(\\p{L})\\1+")
private val NAME_WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

/**
 * A word of a name as it sounds, for the ways Indian names are written in English: [soundOf]'s
 * single letter for a run, and "th" as "t", "sh" as "s", "ph" as "f", "w" as "v", "ee" as "i",
 * "oo" as "u", a closing "ah" as "a", "c" and "q" as "k"; the many Mohammeds as one. So
 * "Fathima" and "Fatima", "Shiyas" and "Siyas", "Vinod" and "Winod" meet. Written by hand, not
 * with regexes: it runs over every word of every contact, a few thousand of them, per call.
 */
internal fun soundKey(word: String): String {
    ALIASES[word]?.let { return it }
    val latin = if (word.all { it.code < 128 }) word else stripAccents(word)
    val out = StringBuilder(latin.length)
    fun add(c: Char) {
        if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
    }
    var i = 0
    while (i < latin.length) {
        val c = latin[i]
        val next = latin.getOrNull(i + 1)
        when {
            c == 'p' && next == 'h' -> { add('f'); i += 2 }
            c == 'c' && next == 'h' -> { add('c'); out.append('h'); i += 2 }
            c == 'c' && next == 'k' -> { add('k'); i += 2 }
            next == 'h' && c in ASPIRATED -> { add(c); i += 2 }
            c == 'a' && next == 'h' && i + 2 == latin.length -> { add('a'); i += 2 }
            c == 'e' && next == 'e' -> { add('i'); i += 2 }
            c == 'o' && next == 'o' -> { add('u'); i += 2 }
            c == 'c' || c == 'q' -> { add('k'); i++ }
            c == 'w' -> { add('v'); i++ }
            else -> { add(c); i++ }
        }
    }
    return out.toString()
}

/** "José" as "jose"; other scripts are left as they are. */
private fun stripAccents(word: String): String =
    if (word.all { it.code < 128 || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN || Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) {
        Normalizer.normalize(word, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    } else {
        word
    }

/** Consonants whose "h" after them is only a breath: th, dh, kh, gh, bh, jh, sh, zh. */
private const val ASPIRATED = "tdkgbjsz"

/** Names written so many ways that rules don't reach them all. */
private val ALIASES: Map<String, String> = buildMap {
    for (way in listOf("mohammed", "mohamed", "mohammad", "mohamad", "muhammad", "muhammed", "muhamed", "mohd", "md", "mhd")) put(way, "mohamad")
    for (way in listOf("syed", "sayed", "sayyid", "syyed", "saiyed")) put(way, "syed")
    for (way in listOf("shaikh", "sheikh", "shaik", "sheik", "shek")) put(way, "saik")
}

/**
 * How alike two words sound, 0 to 1, by Jaro-Winkler: letters in common near the same place,
 * with a lift for a shared start, which is where names written down from speech agree most.
 */
internal fun jaroWinkler(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val window = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
    val aMatched = BooleanArray(a.length)
    val bMatched = BooleanArray(b.length)
    var matches = 0
    for (i in a.indices) {
        for (j in maxOf(0, i - window)..minOf(b.length - 1, i + window)) {
            if (!bMatched[j] && a[i] == b[j]) {
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var j = 0
    for (i in a.indices) {
        if (!aMatched[i]) continue
        while (!bMatched[j]) j++
        if (a[i] != b[j]) transpositions++
        j++
    }
    val m = matches.toDouble()
    val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3
    val prefix = a.zip(b).take(4).takeWhile { (x, y) -> x == y }.size
    return jaro + prefix * 0.1 * (1 - jaro)
}

/**
 * Finds the contact the user means. Candidates are tried in order — the words the user said, then
 * what memory knows (a name, the words the user calls them by) — and the first that matches
 * anyone decides. Within it a whole-name match beats a contact whose name merely contains the
 * words; if that still leaves more than one person, the user is asked.
 *
 * Only when no candidate matches as written are they tried as they sound ([soundKey]), then as
 * the start of a name ("Abdul" for "Abdulla Rahman"): a call or message only opens the app, with
 * the contact's own name on it, so a near miss is seen before anything is sent. Last, a name
 * merely like the one said ("Sunil" for "Sunit") is offered, never picked: [Match.Near].
 */
object ContactMatcher {

    sealed interface Match {
        data class One(val contact: Contact) : Match
        /** Too many people fit; how to tell them apart when asking. */
        data class Several(val labels: List<String>) : Match
        /** No one by that name; these are like it, best first, to ask about. */
        data class Near(val labels: List<String>) : Match
        data object None : Match
    }

    fun find(candidates: List<String>, contacts: List<Contact>): Match {
        val written = contacts.map { it to nameWords(it.name) }
        // Worked out only when nothing matches as written, which is most calls.
        val sounded by lazy { written.map { (contact, words) -> contact to words.map(::soundKey) } }
        val asked = candidates.map(::nameWords).filter { it.isNotEmpty() }

        for (keyOf in listOf<(String) -> String>({ it }, ::soundKey)) {
            val indexed = if (keyOf == ::soundKey) sounded else written
            for (candidate in asked) {
                val words = candidate.map(keyOf)
                val exact = indexed.filter { (_, name) -> name == words }.map { it.first }
                val containing = indexed.filter { (_, name) -> name.containsAll(words) }.map { it.first }
                decided(exact.ifEmpty { containing })?.let { return it }
            }
        }
        for (candidate in asked) {
            val words = candidate.map(::soundKey)
            decided(sounded.filter { (_, name) -> startsOf(words, name) }.map { it.first })?.let { return it }
        }
        for (candidate in asked) {
            val words = candidate.map(::soundKey)
            val near = sounded
                .mapNotNull { (contact, name) -> likeness(words, name)?.let { contact to it } }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy(::person)
            if (near.isNotEmpty()) return Match.Near(near.take(MAX_NEAR).map(::label))
        }
        return Match.None
    }

    private fun decided(found: List<Contact>): Match? {
        val people = found.distinctBy(::person)
        return when {
            people.size == 1 -> Match.One(people.single())
            people.size > 1 -> Match.Several(people.take(MAX_LISTED).map(::label))
            else -> null
        }
    }

    private fun person(contact: Contact) = contact.name.lowercase(Locale.ROOT) to contact.phones.firstOrNull()?.number

    /** Each of [words] is a word of [name], or the start of one (four letters at least), each word used once. */
    private fun startsOf(words: List<String>, name: List<String>): Boolean {
        val left = name.toMutableList()
        return words.all { word ->
            val i = left.indexOfFirst { it == word || (word.length >= MIN_PREFIX && it.startsWith(word)) }
            if (i >= 0) left.removeAt(i)
            i >= 0
        }
    }

    /**
     * How like [name] the [words] are, when each is like one of its words ([jaroWinkler] at least
     * [NEAR]); null when one isn't. Short words must match outright: in three letters one off is
     * another name altogether ("Uma", "Ama").
     */
    private fun likeness(words: List<String>, name: List<String>): Double? {
        val left = name.toMutableList()
        var worst = 1.0
        for (word in words) {
            val (i, score) = left.mapIndexed { i, it -> i to if (word.length < MIN_FUZZY || it.length < MIN_FUZZY) (if (word == it) 1.0 else 0.0) else jaroWinkler(word, it) }
                .maxByOrNull { it.second } ?: return null
            if (score < NEAR) return null
            worst = minOf(worst, score)
            left.removeAt(i)
        }
        return worst
    }

    /**
     * [name], with the user's spelling where their message has a word that sounds the same
     * ([soundOf]) and is written otherwise. Told "Send to umma", the model still wrote "Uma", as
     * in its calls before (4 Nov 2026); with contacts named both, that is the wrong person.
     */
    fun asSaid(name: String, userText: String): String {
        val theirs = NAME_WORD.findAll(Normalizer.normalize(userText, Normalizer.Form.NFC)).map { it.value }.toList()
        val written = theirs.map { it.lowercase(Locale.ROOT) }.toSet()
        return NAME_WORD.replace(Normalizer.normalize(name, Normalizer.Form.NFC)) { word ->
            val key = word.value.lowercase(Locale.ROOT)
            if (key in written) return@replace word.value
            theirs.filter { soundOf(it.lowercase(Locale.ROOT)) == soundOf(key) }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .singleOrNull() ?: word.value
        }
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
    private const val MAX_NEAR = 3

    /** The start of a name counts from this many letters: "Abdu" for "Abdulla", not "Ab". */
    private const val MIN_PREFIX = 4

    /** Words shorter than this are only ever the same or different. */
    private const val MIN_FUZZY = 4

    /** As alike as "Fatma" and "Fatima" (0.97) or "Sunil" and "Sunit" (0.92); "Ravi" and "Rani" (0.87) aren't. */
    private const val NEAR = 0.88
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
