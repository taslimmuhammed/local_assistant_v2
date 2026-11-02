package com.local.assistant.gallery.faces

import com.local.assistant.gallery.faces.ContactMatching.Contact
import com.local.assistant.gallery.faces.ContactMatching.Group
import com.local.assistant.gallery.faces.ContactMatching.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ContactMatchingTest {

    private var axis = 1

    /** Axis 0 is the contact's face; each face made here shares [similarity] with it and nothing else. */
    private fun face(similarity: Float): FloatArray = FloatArray(FacePrints.SIZE).also {
        it[0] = similarity
        it[axis++] = sqrt(1 - similarity * similarity)
    }

    private val contactFace = FloatArray(FacePrints.SIZE).also { it[0] = 1f }

    private fun group(id: Long, similarity: Float) = Group(id, setOf(id * 10, id * 10 + 1), listOf(face(similarity), face(similarity)))

    private fun rahul(key: String = "rahul-key") = Contact(key, "Rahul Nair", contactFace)

    @Test
    fun `a contact clearly closest to one group names it`() {
        val matches = ContactMatching.matches(listOf(rahul()), emptyList(), listOf(group(1, 0.67f), group(2, 0.40f)), emptyMap())
        assertEquals(listOf(ContactMatching.Match("rahul-key", "Rahul Nair", 1, 0.67f, auto = true)), matches.map { it.copy(score = 0.67f) })
        assertTrue(matches.single().score in 0.66f..0.68f)
    }

    @Test
    fun `a weaker but clear match is only suggested`() {
        val match = ContactMatching.matches(listOf(rahul()), emptyList(), listOf(group(1, 0.54f), group(2, 0.45f)), emptyMap()).single()
        assertEquals(false, match.auto)
        assertEquals(1L, match.groupId)
    }

    @Test
    fun `too low or too close to another group, nothing`() {
        assertEquals(emptyList<ContactMatching.Match>(), ContactMatching.matches(listOf(rahul()), emptyList(), listOf(group(1, 0.46f), group(2, 0.30f)), emptyMap()))
        assertEquals(emptyList<ContactMatching.Match>(), ContactMatching.matches(listOf(rahul()), emptyList(), listOf(group(1, 0.66f), group(2, 0.62f)), emptyMap()))
    }

    @Test
    fun `someone already named is left alone`() {
        val groups = listOf(group(1, 0.67f), group(2, 0.40f))
        // Named like them: the user named them first.
        assertEquals(0, ContactMatching.matches(listOf(rahul()), listOf(Person("rahul", null, listOf(face(0.1f)))), groups, emptyMap()).size)
        // Already linked to this contact.
        assertEquals(0, ContactMatching.matches(listOf(rahul()), listOf(Person("Ammu", "rahul-key", listOf(face(0.1f)))), groups, emptyMap()).size)
        // Looks like someone named otherwise (on the phone, a contact matched the user at 0.59).
        assertEquals(0, ContactMatching.matches(listOf(rahul()), listOf(Person("Taslim", null, listOf(face(0.59f), face(0.59f)))), groups, emptyMap()).size)
    }

    @Test
    fun `a group the user said isn't them is passed over`() {
        val matches = ContactMatching.matches(listOf(rahul()), emptyList(), listOf(group(1, 0.67f), group(2, 0.40f)), mapOf("rahul-key" to setOf(10L)))
        assertEquals(emptyList<ContactMatching.Match>(), matches)
    }

    @Test
    fun `two contacts for one group, the closer has it`() {
        val close = Contact("close", "Anu", contactFace)
        val other = FloatArray(FacePrints.SIZE).also { it[0] = 0.95f; it[100] = sqrt(1 - 0.95f * 0.95f) }
        val far = Contact("far", "Binu", other)
        val matches = ContactMatching.matches(listOf(far, close), emptyList(), listOf(group(1, 0.70f), group(2, 0.30f)), emptyMap())
        assertEquals(listOf("close"), matches.map { it.contactKey })
    }

    @Test
    fun `first names are compared`() {
        assertTrue(ContactMatching.sameFirstName("Shiyas", "shiyas K"))
        assertEquals(false, ContactMatching.sameFirstName("antha", "Anantha K"))
    }
}
