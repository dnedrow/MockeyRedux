package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TagsTest {
    @Test
    fun mixedCaseInputWithDuplicatesIsNormalized() {
        val tags = Tags.of("Regression  smoke REGRESSION")

        assertEquals(listOf("regression", "smoke"), tags.toList())
    }

    @Test
    fun inputIsSplitOnEveryKindOfWhitespace() {
        val tags = Tags.of(" one\ttwo\nthree\r\nfour\u00A0five\u3000six ")

        assertEquals(setOf("one", "two", "three", "four", "five", "six"), tags)
    }

    @Test
    fun severalInputsAreCombined() {
        assertEquals(Tags.of("a b c"), Tags.of("c", "B a", "a"))
        assertEquals(Tags.of("a b c"), Tags.of(listOf("c b", "A")))
    }

    @Test
    fun tagsAreHeldInAlphabeticalOrder() {
        assertEquals(listOf("alpha", "beta", "gamma"), Tags.of("gamma", "alpha", "beta").toList())
    }

    @Test
    fun inputWithoutWordsHasNoTags() {
        assertSame(Tags.EMPTY, Tags.of())
        assertSame(Tags.EMPTY, Tags.of("", " \t\n "))
        assertTrue(Tags.EMPTY.isEmpty())
    }

    @Test
    fun tagsAreEqualWhenTheyHoldTheSameWords() {
        val tags = Tags.of("smoke regression")

        assertEquals(tags, Tags.of("REGRESSION", "Smoke"))
        assertEquals(tags.hashCode(), Tags.of("REGRESSION", "Smoke").hashCode())
        assertEquals(setOf("regression", "smoke"), tags)
        assertEquals<Set<String>>(tags, setOf("regression", "smoke"))
        assertNotEquals(tags, Tags.of("smoke"))
    }

    @Test
    fun aTagIsFoundInItsNormalizedForm() {
        val tags = Tags.of("Smoke")

        assertTrue("smoke" in tags)
        assertTrue("Smoke" !in tags)
    }

    @Test
    fun theTextFormIsReadBack() {
        val tags = Tags.of("smoke", "Regression")

        assertEquals("regression smoke", tags.toString())
        assertEquals(tags, Tags.of(tags.toString()))
    }

    @Test
    fun tagsAreSerializedAsASortedList() {
        assertEquals("""["regression","smoke"]""", Json.encodeToString(Tags.serializer(), Tags.of("smoke Regression")))
        assertEquals("[]", Json.encodeToString(Tags.serializer(), Tags.EMPTY))
    }

    @Test
    fun tagsAreNormalizedWhenTheyAreRead() {
        val tags = Json.decodeFromString(Tags.serializer(), """["Smoke","REGRESSION smoke"]""")

        assertEquals(Tags.of("regression smoke"), tags)
        assertSame(Tags.EMPTY, Json.decodeFromString(Tags.serializer(), "[]"))
    }
}
