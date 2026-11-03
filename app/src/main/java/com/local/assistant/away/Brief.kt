package com.local.assistant.away

import java.util.Locale

/** One chat, caller or app notification in the brief, with the unseen lines behind it. */
data class BriefItem(
    val key: String,
    val kind: Kind,
    val packageName: String,
    val appLabel: String,
    /** The person, the group, or the notification's title. */
    val title: String,
    val isGroup: Boolean,
    /** Unseen lines, oldest first; the user's own replies included. */
    val lines: List<CapturedEntity>,
    val level: Level,
    /** One line on what it is about: the model's, or the last thing said until the model has read it. */
    val gist: String,
    /** Replies the model drafted, best first; none for calls and apps. */
    val replies: List<String>,
    /** The model has read it (its last line included). */
    val read: Boolean,
) {
    val latestAt: Long get() = lines.maxOf { it.postedAt }

    /** Lines from the other side: what the user hasn't answered. */
    val fromOthers: List<CapturedEntity> get() = lines.filter { !it.fromMe }

    /** How many missed calls, or new messages. */
    val count: Int get() = fromOthers.size.coerceAtLeast(1)
}

/** The brief as shown: who needs the user, what's worth knowing, and how much noise was left out. */
data class Brief(
    val headline: String?,
    val needs: List<BriefItem>,
    val fyi: List<BriefItem>,
    val skipped: List<BriefItem>,
) {
    val isEmpty: Boolean get() = needs.isEmpty() && fyi.isEmpty() && skipped.isEmpty()

    /** Every line shown, for marking them briefed when the brief closes. */
    val lineIds: List<Long> get() = (needs + fyi + skipped).flatMap { item -> item.lines.map { it.id } }

    companion object {
        val EMPTY = Brief(null, emptyList(), emptyList(), emptyList())
    }
}

/**
 * Sorts the brief before the model has read it, and whenever it can't: calls and questions to
 * the user first, then what's worth knowing, and the rest counted as noise. The model's reading
 * ([BriefTriage]) replaces this item by item as it arrives.
 */
object BriefRules {

    /** Words that make a message something to answer, in English and Hinglish. */
    private val ASKS = Regex(
        "\\?|\\b(urgent|asap|call me|call back|emergency|please reply|reply|let me know|lmk|can you|could you|will you|are you|did you|" +
            "kab|kya|kahan|kidhar|kaise|kyun|batao|bata|bolo|jaldi|zaroor)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Selling: left out even when it mentions a delivery or a payment ("free delivery on orders above 199"). */
    private val PROMO = Regex(
        "\\b(\\d+\\s*% off|flat \\d+|off on|sale|offer|deal|discount|cashback|coupon|promo|free delivery|order now|shop now|buy now|limited time|today only)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** An app's notification worth a line: money, deliveries, travel, plans. */
    private val WORTH_KNOWING = Regex(
        "\\b(delivered|delivery|arriving|out for delivery|shipped|credited|debited|payment|paid|refund|bill|due|" +
            "meeting|invite|invitation|reminder|appointment|flight|train|booking|boarding|cancelled|rescheduled|ticket)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun level(kind: Kind, isGroup: Boolean, lines: List<CapturedEntity>): Level {
        val last = lines.lastOrNull() ?: return Level.SKIP
        return when (kind) {
            Kind.CALL -> Level.NEED
            Kind.MESSAGE -> when {
                // Answered already, from the notification or the app.
                last.fromMe -> Level.SKIP
                isGroup -> if (lines.any { !it.fromMe && ASKS.containsMatchIn(it.text) }) Level.FYI else Level.SKIP
                lines.any { !it.fromMe && ASKS.containsMatchIn(it.text) } -> Level.NEED
                else -> Level.FYI
            }
            Kind.OTHER -> when {
                lines.all { PROMO.containsMatchIn(it.title + " " + it.text) } -> Level.SKIP
                lines.any { WORTH_KNOWING.containsMatchIn(it.title + " " + it.text) } -> Level.FYI
                else -> Level.SKIP
            }
        }
    }

    /** The line shown before the model has read it. */
    fun gist(kind: Kind, lines: List<CapturedEntity>): String {
        val others = lines.filter { !it.fromMe }
        return when (kind) {
            Kind.CALL -> if (others.size > 1) "${others.size} missed calls" else "Missed call"
            else -> (others.lastOrNull() ?: lines.last()).text.lines().first().trim()
        }
    }
}

/** Puts stored lines and the model's readings together into a [Brief]. */
object BriefBuilder {

    /**
     * [lines]: unseen lines, any order. [newOnly]: only items with something not shown in a
     * brief before (the brief at unlock); otherwise everything still unseen (opened by hand).
     */
    fun build(lines: List<CapturedEntity>, triage: List<TriageEntity>, newOnly: Boolean): Brief {
        val readings = triage.associateBy { it.conversationKey }
        val items = lines.groupBy { it.conversationKey }
            .values
            .filter { group -> group.any { !it.fromMe } }
            .filter { group -> !newOnly || group.any { it.briefedAt == null } }
            .map { group -> item(group.sortedWith(compareBy({ it.postedAt }, { it.id })), readings[group.first().conversationKey]) }
            .sortedByDescending { it.latestAt }
        val headline = readings[TriageEntity.HEADLINE_KEY]?.gist?.takeIf { it.isNotBlank() && items.any { item -> item.read } }
        return Brief(
            headline = headline ?: ruleHeadline(items),
            needs = items.filter { it.level == Level.NEED },
            fyi = items.filter { it.level == Level.FYI },
            skipped = items.filter { it.level == Level.SKIP },
        )
    }

    private fun item(lines: List<CapturedEntity>, reading: TriageEntity?): BriefItem {
        val first = lines.first()
        val kind = runCatching { Kind.valueOf(first.kind) }.getOrDefault(Kind.OTHER)
        val title = lines.last().title
        // A reading counts once it has seen the last line; until then the rules sort it.
        val read = reading != null && reading.upToId >= lines.maxOf { it.id }
        val ruled = BriefRules.level(kind, first.isGroup, lines)
        val readLevel = reading?.let { runCatching { Level.valueOf(it.level) }.getOrNull() }
        val level = when {
            kind == Kind.CALL -> Level.NEED
            lines.last().fromMe -> Level.SKIP
            readLevel == null -> ruled
            read -> readLevel
            // Read before the latest lines came: the more pressing of the two until it's read again.
            else -> minOf(readLevel, ruled)
        }
        val current = reading?.takeIf { read }
        val replies = current?.replies?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            .takeIf { kind == Kind.MESSAGE && !lines.last().fromMe }
            .orEmpty()
        return BriefItem(
            key = first.conversationKey,
            kind = kind,
            packageName = first.packageName,
            appLabel = first.appLabel,
            title = title,
            isGroup = first.isGroup,
            lines = lines,
            level = level,
            gist = current?.gist?.takeIf { it.isNotBlank() && kind != Kind.CALL } ?: BriefRules.gist(kind, lines),
            replies = replies,
            read = read || kind == Kind.CALL,
        )
    }

    /** "Amma called · Rahul is waiting on you": the headline until the model has written one. */
    internal fun ruleHeadline(items: List<BriefItem>): String? {
        val needs = items.filter { it.level == Level.NEED }
        if (needs.isEmpty()) return if (items.isEmpty()) null else "Nothing urgent."
        val callers = needs.filter { it.kind == Kind.CALL }.map { it.title }
        val writers = needs.filter { it.kind != Kind.CALL }.map { it.title }
        val parts = listOfNotNull(
            callers.takeIf { it.isNotEmpty() }?.let { names(it) + " called" },
            writers.takeIf { it.isNotEmpty() }?.let { names(it) + if (it.size == 1) " is waiting on you" else " are waiting on you" },
        )
        return parts.joinToString(" · ").replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun names(names: List<String>): String = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
    }
}
