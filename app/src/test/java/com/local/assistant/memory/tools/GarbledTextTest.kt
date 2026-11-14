package com.local.assistant.memory.tools

import com.local.assistant.memory.tools.GarbledText.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarbledTextTest {

    private fun whole(text: String) = GarbledText.verdict(text, final = true)

    @Test
    fun `token soup is caught`() {
        // Made up in the style of what the phone gave on 14 Nov 2026.
        assertEquals(Verdict.SOUP, whole("\"840tsetUauthorx205619i aponitimiCrap.M WMWWCOMXIai /isdevincr 링Tı Microsoft Sound***passou"))
        assertEquals(Verdict.SOUP, whole("blorpique setzuL6yCblueCISTI a0 _38* = 4aciحمد for \"match_to rй's lineazgedisRempass is quiet"))
        assertEquals(Verdict.SOUP, whole("_am1018:tabeicon3mkeyWapi\nfoundemnegefundenameinmeplot seinstalledResnet=6stimize\n\nItheKedit remaind::iseteorle"))
        // Two odd words in its first ten, so it is looked at further.
        assertEquals(Verdict.SOUP, whole("grt71other lamp T Auto-waiti2 Jamie_like = 'donnasitcce isparquici 00.preferencesb thank you icymdokeeandEdx (Lufisaavue4w"))
    }

    @Test
    fun `reasoning written out is caught`() {
        // As the phone gave it on 14 Nov 2026, thinking being off.
        assertEquals(Verdict.SOUP, GarbledText.verdict("[Thinking Process: \n1.  **Analyze the Request:** The user is", final = false))
        assertEquals(Verdict.SOUP, whole("Thinking Process:\n1. The user wants a fact."))
        assertEquals(Verdict.SOUP, whole("4.  **Formulate Response.**<channel|>The Eiffel Tower was built for the 1889 World's Fair."))
        assertEquals(Verdict.FINE, whole("My thinking process: I'd start with the budget, then the dates."))
    }

    @Test
    fun `soup that turns into words is caught by its opening`() {
        val opening = "wyasagätheapiszczaHisyus3amx isrmesplacedpackageIs=20pGoogle iSiQuickonlyphrase:no-fixedPbyisea is to the "
        val words = "If you meant how I read files, I can open one by its name and answer questions about it. ".repeat(5)
        assertEquals(Verdict.SOUP, whole(opening + words))
    }

    @Test
    fun `real replies pass`() {
        listOf(
            "The capital of Australia is Canberra.",
            "Paris is the capital of France.",
            "Sure! I've set an alarm for 6:30am tomorrow.",
            "Here are 3 photos from your trip to Munnar in 2024.",
            "Open WhatsApp, YouTube and LinkedIn from the home screen; MakeMyTrip and BookMyShow too.",
            "Your car MH12AB1234 is due for service. PAN ABCDE1234F is on the card, flight 6E123 leaves at 10:45.",
            "It's 5km away, about 12 mins by car. The 2nd floor has a 4K TV and 5G works there.",
            "That costs Rs500, or ₹1,250 with delivery. COVID-19 rules no longer apply. AT&T is American.",
            "I found Rental_Agreement_2024.pdf and MyResume2024.docx in Downloads; see https://example.com/files or mail a@b.com.",
            "Use `read_file` with `name=resume` and `type=pdf` to open it; it reads the first pages.",
            "किराया 18,000 रुपये महीना है और हर महीने की 5 तारीख को देना होता है।",
            "നിങ്ങളുടെ അടുത്ത മീറ്റിംഗ് നാളെ രാവിലെ പത്ത് മണിക്ക് ഓഫീസിൽ ആണ്, മറക്കരുത്.",
            "Here's how:\n```python\ndef double(x):\n    return x*2\nprint(f\"{name}={value}\")\n```",
            "Hello! How can I help you today?",
            "Done.",
            "OK",
        ).forEach { reply -> assertEquals(reply, Verdict.FINE, whole(reply)) }
    }

    @Test
    fun `while it streams, a short opening is not judged yet`() {
        assertNull(GarbledText.verdict("The capital of", final = false))
        // The last word may still be coming: "Canb" is not judged.
        assertNull(GarbledText.verdict("Hello there, the capital is Canb", final = false))
        assertEquals(Verdict.FINE, GarbledText.verdict("The capital of Australia is Canberra, a planned city built ", final = false))
    }

    @Test
    fun `a short soup reply is caught once it ends`() {
        assertEquals(Verdict.SOUP, whole("840tsetUauthorx205619i aponitimiCrap.M"))
        assertEquals(Verdict.FINE, whole("WhatsApp or YouTube?"))
    }
}
