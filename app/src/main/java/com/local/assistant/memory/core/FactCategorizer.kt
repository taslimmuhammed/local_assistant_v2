package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactCategory

/**
 * Files a fact under a category from its (normalised) keys alone.
 *
 * This is a lookup table on purpose. Asking a 4B model to classify would make the memory screen
 * depend on its judgement, and it would classify the same fact differently on different days.
 * Anything the table does not recognise lands in [FactCategory.OTHER], which is always safe.
 */
object FactCategorizer {

    fun categorize(subject: String, attribute: String): FactCategory {
        if (attribute.startsWith(FactKeys.PREFERENCE_PREFIX) || attribute in PREFERENCES) {
            return FactCategory.PREFERENCE
        }
        return if (subject == FactKeys.USER) aboutUser(attribute) else aboutSomeoneElse(subject, attribute)
    }

    private fun aboutUser(attribute: String): FactCategory = when {
        attribute in PROFILE -> FactCategory.PROFILE
        attribute in PEOPLE || attribute in RELATIONS -> FactCategory.PEOPLE
        attribute in PLACES || PLACE_SUFFIXES.any(attribute::endsWith) -> FactCategory.PLACES
        attribute in WORK -> FactCategory.WORK
        attribute in HEALTH -> FactCategory.HEALTH
        attribute in ROUTINE || ROUTINE_SUFFIXES.any(attribute::endsWith) -> FactCategory.ROUTINE
        else -> FactCategory.OTHER
    }

    /**
     * A fact about another entity is about a person if the entity is a known relation or the
     * attribute is something only people have; about a place if the entity is a known place.
     */
    private fun aboutSomeoneElse(subject: String, attribute: String): FactCategory = when {
        subject in RELATIONS || subject in PEOPLE || attribute in PERSON_ATTRIBUTES -> FactCategory.PEOPLE
        subject in PLACES || subject in PLACE_SUBJECTS || PLACE_SUFFIXES.any(attribute::endsWith) ->
            FactCategory.PLACES
        else -> FactCategory.OTHER
    }

    private val PREFERENCES = setOf(
        "favourite_food", "favorite_food", "favourite_colour", "favorite_color", "likes", "dislikes",
        "diet_preference", "music_taste",
    )

    private val PROFILE = setOf(
        "name", "full_name", "nickname", "age", "birthday", "date_of_birth", "dob", "gender",
        "pronouns", "city", "hometown", "country", "nationality", "language", "native_language",
        "languages", "religion", "marital_status", "anniversary", "interests",
    )

    /** The user's people: roles someone plays in their life. */
    private val PEOPLE = setOf(
        "dentist", "doctor", "gp", "physician", "therapist", "ca", "accountant", "lawyer",
        "advocate", "boss", "manager", "landlord", "teacher", "tutor", "mentor", "colleague",
        "assistant", "driver", "maid", "cook", "friend", "best_friend", "neighbour", "neighbor",
        "partner", "girlfriend", "boyfriend", "fiance", "fiancee", "barber", "mechanic",
        "plumber", "electrician", "trainer", "coach",
    )

    private val RELATIONS = setOf(
        "mother", "father", "wife", "husband", "spouse", "brother", "sister", "son", "daughter",
        "grandmother", "grandfather", "dadi", "dada", "nani", "nana", "uncle", "aunt", "cousin",
        "nephew", "niece", "mother_in_law", "father_in_law", "brother_in_law", "sister_in_law",
        "children", "kids", "child", "pet", "dog", "cat",
    )

    private val PLACES = setOf(
        "home", "address", "home_address", "office", "office_address", "workplace", "gym",
        "school", "college", "hospital", "clinic", "bank", "bank_branch", "native_place",
    )
    private val PLACE_SUBJECTS = setOf("house", "flat", "apartment")
    private val PLACE_SUFFIXES = listOf("_address", "_location", "_branch")

    private val WORK = setOf(
        "job", "job_title", "title", "role", "profession", "occupation", "employer", "company",
        "team", "department", "work_hours", "working_hours", "business", "designation",
    )

    private val HEALTH = setOf(
        "allergy", "allergies", "blood_group", "blood_type", "medication", "medications",
        "medicine", "condition", "conditions", "diet", "height", "weight", "insurance",
    )

    private val ROUTINE = setOf("routine", "schedule", "commute", "workout", "wake_up", "bedtime")
    private val ROUTINE_SUFFIXES = listOf("_time", "_day", "_days")

    /** Attributes that only make sense for a person, whoever they are. */
    private val PERSON_ATTRIBUTES = setOf(
        "birthday", "date_of_birth", "age", "phone", "phone_number", "email", "anniversary",
        "full_name", "nickname", "relation", "relationship", "spouse", "wife", "husband",
    )
}
