package com.local.assistant.inbox

import com.local.assistant.inbox.PromoFilter.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up messages in the shapes Indian SMS and chat notifications come in. */
class PromoFilterTest {

    private fun promo(text: String, sender: String? = null, source: Source = Source.SMS, channel: String? = null, category: String? = null) =
        PromoFilter.isPromo(source, sender, text, channel, category)

    @Test
    fun `ads are left out`() {
        assertTrue(promo("Mega Sale! Flat 60% off on shoes. Use code SHOE60. Shop now: bit.ly/abc T&C apply", "VM-SHOPZZ"))
        assertTrue(promo("Congratulations! You have a pre-approved personal loan of Rs 5,00,000. Apply now", "AX-LOANCO"))
        assertTrue(promo("Get unlimited 5G data at Rs 349. Recharge now and get 10% cashback. Valid till 30 Nov", "JM-TELCO"))
        assertTrue(promo("Weekend deals: up to 70% off on electronics", "VK-STORE"))
    }

    @Test
    fun `what the sender marks as promotional is an ad, whatever it says`() {
        assertTrue(promo("Your favourites are back in stock", "AD-BRAND-P"))
        assertTrue(promo("New arrivals", channel = "Promotions", source = Source.CHAT))
        assertTrue(promo("Something for you", category = "promo", source = Source.OTHER))
    }

    @Test
    fun `payments, deliveries, bookings and bills are kept`() {
        assertFalse(promo("Rs 1,250.00 debited from A/c XX1234 on 12-Nov to UPI ref 4567. Not you? Call 1800", "JD-BANKX-S"))
        assertFalse(promo("Your order has been delivered. Rate your experience", "VM-SHOPZZ"))
        assertFalse(promo("PNR 4512367890: Train 16301 on 20 Nov, coach S4, seat 32", "IX-RAILWY"))
        assertFalse(promo("Your electricity bill of Rs 1,846 is due on 20 Nov 2026", "AD-POWERB"))
        // A transaction with an ad in its tail: kept.
        assertFalse(promo("Rs 450 credited to your wallet. Get 10% cashback on your next recharge, T&C apply", "VM-WALLET"))
    }

    @Test
    fun `service and government messages are kept by their sender's mark`() {
        assertFalse(promo("Offer: 20% off on renewal. Your policy renews on 1 Dec", "AX-INSURE-S"))
        assertFalse(promo("Free vaccination camp this Sunday", "GV-HEALTH-G"))
    }

    @Test
    fun `a person's message needs more than a word or two of an ad`() {
        assertFalse(promo("There's a sale at the mall tomorrow, want to go?", "Rahul", Source.CHAT))
        assertFalse(promo("Got 20% off on the tickets, booked for Friday", "Anjali", Source.CHAT))
        assertFalse(promo("Are you free this evening?", "Amma", Source.CHAT))
        assertFalse(promo("Can you send the rent agreement?", "Landlord"))
    }
}
