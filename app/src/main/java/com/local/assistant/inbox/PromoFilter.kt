package com.local.assistant.inbox

import java.util.Locale

/**
 * Ads aren't kept: an offer from a shop, a loan "pre-approved" for you, a recharge plan. What a
 * person or a service sent about the user's own life is: a payment, a delivery, a booking, a bill.
 *
 * An ad is told by what says so outright (an SMS sender marked -P, as TRAI requires of
 * promotional SMS in India; a notification channel or category for promotions), or else by
 * several of an ad's usual signs ("50% off", "use code", a short link) with nothing that marks a
 * transaction. A chat app's message needs more signs than an SMS: it is usually a person.
 */
object PromoFilter {

    enum class Source { SMS, CHAT, OTHER }

    fun isPromo(source: Source, sender: String?, text: String, channel: String? = null, category: String? = null): Boolean {
        if (category == "promo") return true
        if (channel != null && PROMO_CHANNEL.containsMatchIn(channel)) return true
        val header = sender?.trim()?.uppercase(Locale.ROOT)?.let(TRAI_HEADER::matchEntire)
        if (header != null) {
            when (header.groupValues[2]) {
                "P" -> return true
                // Service, transactional and government messages, as their sender says.
                "S", "T", "G" -> return false
            }
        }
        val said = text.lowercase(Locale.ROOT)
        val signs = PROMO_SIGNS.count { it.containsMatchIn(said) }
        val threshold = if (source == Source.CHAT) CHAT_SIGNS else SIGNS
        if (signs < threshold) return false
        // "Rs 450 debited… Get 10% cashback on your next recharge": a transaction first.
        val transaction = TRANSACTION_SIGNS.any { it.containsMatchIn(said) }
        return !transaction || signs >= threshold + 2
    }

    private const val SIGNS = 2
    private const val CHAT_SIGNS = 3

    /** "JD-HDFCBK-S", "VM-AMAZON", "AX-JIOINF-P": an Indian SMS header, and its kind when given. */
    private val TRAI_HEADER = Regex("^[A-Z]{2}-([A-Z0-9]{3,9})(?:-([PSTG]))?$")

    private val PROMO_CHANNEL = Regex("promo|offer|marketing|deals?\\b|recommend|advertis", RegexOption.IGNORE_CASE)

    private val PROMO_SIGNS = listOf(
        Regex("\\d+\\s?%\\s?(off|cashback|discount)"),
        Regex("\\bflat\\s+(rs\\.?|₹|inr)?\\s?\\d+"),
        Regex("\\bcash\\s?back\\b"),
        Regex("\\b(use|apply)( the)? (promo )?code\\b|\\bcoupon\\b|\\bvoucher\\b|\\bpromo\\s?code\\b"),
        Regex("\\bsale\\b|\\bmega\\b|\\bbonanza\\b"),
        Regex("\\boffers?\\b|\\bdeals?\\b|\\bdiscounts?\\b"),
        Regex("\\bunsubscribe\\b|\\b(sms|reply|send) stop\\b|\\bstop to \\d+|\\bopt[- ]out\\b"),
        Regex("\\bt ?& ?c\\b|\\btnc\\b|\\bconditions apply\\b"),
        Regex("\\blimited (time|period|stock|offer)\\b|\\bhurry\\b|\\blast (few )?(days?|chance)\\b|\\bends (today|tonight|soon)\\b"),
        Regex("\\bpre-?approved\\b|\\binstant loan\\b|\\bloan (of|upto|up to)\\b|\\bcredit limit (of|upto|up to)\\b"),
        Regex("\\bcongratulations\\b|\\byou('ve| have) won\\b|\\bwinner\\b|\\blucky\\b"),
        Regex("\\bfree\\b|\\bgift\\b"),
        Regex("\\b(bit\\.ly|tinyurl\\.com|cutt\\.ly|rb\\.gy|t\\.ly|amzn\\.to|fkrt\\.it|myntr\\.it|1kx\\.in|i\\.am|goo\\.gl|shorturl\\.at)/"),
        Regex("\\b(shop|buy|book|order|apply|download|recharge|subscribe|grab|claim|avail) (it )?now\\b|\\bclick (here|now|to)\\b"),
        Regex("\\bvalid (till|until|upto|up to)\\b|\\bstarting (at|from)\\b|\\blowest price\\b|\\bbest price\\b|\\bexclusive\\b"),
        Regex("\\bup ?to \\d+\\s?%"),
    )

    private val TRANSACTION_SIGNS = listOf(
        Regex("\\b(debited|credited|withdrawn|deposited|spent|transferred|received)\\b"),
        Regex("\\b(a/c|acct|account) (no\\.? )?(x+|\\*+)?\\d"),
        Regex("\\b(txn|transaction|upi ref|ref no|utr)\\b"),
        Regex("\\b(delivered|shipped|dispatched|out for delivery|has been picked up|arriving)\\b"),
        Regex("\\b(booking|booked|pnr|ticket|e-?ticket|boarding|check-?in)\\b"),
        Regex("\\b(appointment|consultation|scheduled for)\\b"),
        Regex("\\b(bill|invoice|statement|emi|premium) (of|for|is|due|amount|generated)\\b|\\bdue (on|date|by)\\b|\\bpayment (of|due|received|successful|failed)\\b"),
        Regex("\\brefund(ed)?\\b|\\bcancell?ed\\b"),
    )
}
