package com.local.assistant.memory.core

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises fact keys before every write, so "Dentist", " dentist " and "DENTIST" are one row.
 *
 * Keys are lowercased, trimmed, whitespace-collapsed and snake_cased. Honorifics are stripped
 * from keys only — the display value keeps "Dr. Rao" — and a small, deterministic synonym table
 * folds kinship terms together, so "amma", "mummy" and "mom" all file under `mother` whichever
 * word the user (or the model) happened to use.
 */
object FactKeys {

    const val USER = "user"

    /** Namespace for standing instructions on how to respond: `pref.reply_style`. */
    const val PREFERENCE_PREFIX = "pref."

    fun subject(raw: String): String {
        val tokens = tokens(raw).toMutableList()
        // "my mother" → mother; a bare "my"/"me" is the user themself.
        if (tokens.size > 1 && tokens.first() in POSSESSIVES) tokens.removeAt(0)
        val key = join(stripHonorifics(tokens))
        return when {
            key.isEmpty() -> ""
            key in SELF -> USER
            else -> RELATION_SYNONYMS[key] ?: key
        }
    }

    fun attribute(raw: String): String {
        val key = join(stripHonorifics(tokens(raw)))
        val namespaced = PREFERENCE_ALIASES.firstOrNull { key.startsWith(it) }
            ?.let { PREFERENCE_PREFIX + key.removePrefix(it) }
            ?: key.takeIf { it in BARE_PREFERENCES }?.let { PREFERENCE_PREFIX + it }
            ?: key
        return RELATION_SYNONYMS[namespaced] ?: ATTRIBUTE_SYNONYMS[namespaced] ?: namespaced
    }

    /** Display text as the user will see it: trimmed, with runs of whitespace collapsed. */
    fun value(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /** Whether two values say the same thing, ignoring case, spacing and a trailing full stop. */
    fun sameValue(a: String, b: String): Boolean = comparable(a) == comparable(b)

    private fun comparable(value: String): String =
        value(value).trimEnd('.').lowercase(Locale.ROOT)

    /**
     * Splits on whitespace, underscores and hyphens. A dot inside a token is kept — it is how
     * attributes are namespaced — but a trailing one ("Dr.") is not.
     */
    private fun tokens(raw: String): List<String> =
        // NFC first: the same Indic word can arrive as different code-point sequences.
        Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .split(SEPARATORS)
            .map { token -> token.filter { it.isLetterOrDigit() || it == '.' || Character.getType(it) in MARKS } }
            .map { it.trim('.') }
            .filter { it.isNotEmpty() }

    /** Honorifics go only when something is left: "Dr" alone stays "dr". */
    private fun stripHonorifics(tokens: List<String>): List<String> {
        var kept = tokens
        if (kept.size > 1 && kept.first() in HONORIFIC_PREFIXES) kept = kept.drop(1)
        if (kept.size > 1 && kept.last() in HONORIFIC_SUFFIXES) kept = kept.dropLast(1)
        return kept
    }

    private fun join(tokens: List<String>): String = tokens.joinToString("_")

    private val WHITESPACE = Regex("\\s+")
    private val SEPARATORS = Regex("[\\s_\\-]+")

    /** Combining marks carry the vowel signs of Indic scripts; dropping them would garble keys. */
    private val MARKS = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )

    private val POSSESSIVES = setOf("my", "mine", "our", "users")
    private val SELF = setOf("user", "me", "i", "my", "myself", "self", "mine")

    private val HONORIFIC_PREFIXES = setOf(
        "dr", "mr", "mrs", "ms", "miss", "mx", "prof", "shri", "sri", "shree", "smt", "kumari",
        "adv", "er", "capt", "col",
    )
    private val HONORIFIC_SUFFIXES = setOf(
        "ji", "sir", "madam", "maam", "saab", "sahab", "sahib", "garu",
    )

    private val PREFERENCE_ALIASES = listOf("preference.", "preferences.", "pref_")

    /**
     * Other words for the fields on the profile screen, so "occupation" said in a chat and "Work"
     * typed on the profile are one row. Only words that mean the same whoever the subject is:
     * "location" is left alone, since an office's location is an address, not a city.
     */
    private val ATTRIBUTE_SYNONYMS: Map<String, String> = buildMap {
        fun map(target: String, vararg words: String) = words.forEach { put(it, target) }
        map("job", "occupation", "profession", "designation", "job_title")
        map("city", "current_city", "lives_in", "residence", "home_city")
        map("languages", "language", "languages_spoken", "spoken_languages")
        map("interests", "interest", "hobbies", "hobby")
    }

    /** Response-style keys the model sometimes writes without the namespace. */
    private val BARE_PREFERENCES = setOf(
        "reply_style", "response_style", "answer_style", "reply_length", "reply_language",
        "response_language", "tone",
    )

    /**
     * Kinship terms across English and the common Indian languages. Only unambiguous words are
     * here: "baba" (father in some languages, grandfather in others), "anna" (a brother, and a
     * name) and "beta" (any child, affectionately) are left alone, and paternal and maternal
     * grandparents stay distinct.
     */
    private val RELATION_SYNONYMS: Map<String, String> = buildMap {
        fun map(target: String, vararg words: String) = words.forEach { put(it, target) }
        // Not "mama": in Hindi that is a maternal uncle.
        map("mother", "mom", "mum", "mummy", "mommy", "amma", "ammi", "maa", "umma", "mataji", "mother")
        map("father", "dad", "daddy", "papa", "appa", "abba", "pitaji", "achan", "uppa", "vappa", "vaappa", "father")
        map("wife", "wife", "biwi", "patni")
        map("husband", "husband", "pati")
        map("brother", "brother", "bro", "bhai", "bhaiya", "bhaiyya")
        map("sister", "sister", "sis", "didi", "behen", "akka", "chechi")
        // The same words in their own scripts: Devanagari, Tamil, Malayalam.
        map("mother", "माँ", "मां", "मम्मी", "अम्मा", "அம்மா", "അമ്മ", "ഉമ്മ")
        map("father", "पापा", "पिताजी", "அப்பா", "അച്ഛൻ", "ഉപ്പ", "വാപ്പ")
        map("wife", "पत्नी", "बीवी")
        map("husband", "पति")
        map("brother", "भाई", "भैया")
        map("sister", "बहन", "दीदी")
    }
}
