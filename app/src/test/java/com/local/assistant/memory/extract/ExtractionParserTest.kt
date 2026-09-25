package com.local.assistant.memory.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionParserTest {

    @Test
    fun aPlainArrayParses() {
        val items = ExtractionParser.parse(
            """[{"type":"fact","subject":"user","attribute":"dentist","value":"Dr. Rao","core":false,"source_message_id":12},
               {"type":"event","title":"Dentist appointment","when":"Friday at 5","source_message_id":13}]""",
        )!!
        assertEquals(2, items.size)
        assertEquals(ExtractedItem(ExtractedItem.Type.FACT, 12, "user", "dentist", "Dr. Rao", false, "Dr. Rao"), items[0])
        assertEquals("Friday at 5", items[1].whenText)
        assertEquals("Dentist appointment", items[1].title)
    }

    @Test
    fun nothingFoundIsAnEmptyListNotAFailure() {
        assertEquals(emptyList<ExtractedItem>(), ExtractionParser.parse("[]"))
        assertEquals(emptyList<ExtractedItem>(), ExtractionParser.parse("```json\n[]\n```"))
    }

    @Test
    fun fencesProseAndWrappersAreTolerated() {
        val fenced = ExtractionParser.parse("```json\n[{\"type\":\"task\",\"title\":\"Call the CA\",\"when\":\"tomorrow at 11\",\"source_message_id\":4}]\n```")!!
        assertEquals("Call the CA", fenced.single().title)
        val prose = ExtractionParser.parse("Here is what I found:\n[{\"type\":\"fact\",\"subject\":\"mother\",\"attribute\":\"birthday\",\"value\":\"12 March\",\"source_message_id\":\"#9\"}]\nDone.")!!
        assertEquals(9L, prose.single().sourceMessageId)
        val wrapped = ExtractionParser.parse("{\"items\":[{\"type\":\"fact\",\"subject\":\"user\",\"attribute\":\"city\",\"value\":\"Kochi\",\"source_message_id\":2}]}")!!
        assertEquals("Kochi", wrapped.single().value)
    }

    @Test
    fun itemsWithoutATypeOrSourceAreDropped() {
        val items = ExtractionParser.parse(
            """[{"type":"opinion","value":"x","source_message_id":1},
               {"type":"fact","subject":"user","attribute":"city","value":"Pune"},
               {"type":"fact","subject":"user","attribute":"city","value":"Pune","source_message_id":3}]""",
        )!!
        assertEquals(listOf(3L), items.map { it.sourceMessageId })
    }

    @Test
    fun proseWithNoJsonIsWorthARetry() {
        assertNull(ExtractionParser.parse("I could not find anything worth remembering."))
        assertNull(ExtractionParser.parse(""))
    }

    @Test
    fun thePromptsWorkedExampleParses() {
        val example = ExtractionPrompt.SYSTEM.lines().first { it.startsWith("[{") }
        val items = ExtractionParser.parse(example)!!
        // The worked example yields nothing for the question, the mood, the hypothetical, the
        // opinion and the sarcasm: messages 12, 13, 14, 16 and 18.
        assertTrue(items.none { it.sourceMessageId in setOf(12L, 13L, 14L, 16L, 18L) })
        assertEquals(setOf(11L, 15L, 17L, 19L, 20L, 21L), items.map { it.sourceMessageId }.toSet())
    }
}
