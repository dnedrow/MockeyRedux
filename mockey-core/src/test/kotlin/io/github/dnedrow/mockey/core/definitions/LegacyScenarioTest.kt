package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cases of the legacy `com.mockey.model.ScenarioTest`, with the names of its test methods. A legacy scenario
 * held its tags as a list of texts and its response headers as one text; the new one holds [Tags] and a list
 * of [Header]s.
 */
class LegacyScenarioTest {
    private fun scenario(
        name: String,
        responseBody: String = "",
        tags: Tags = Tags.EMPTY,
        headers: List<Header> = emptyList(),
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = responseBody, tags = tags, headers = headers)

    @Test
    fun addTagsToScenario() {
        val scenario = scenario("ABC", tags = Tags.of("development", "dEvelOpment", " development ", "DEVELOPMENT"))

        assertEquals(listOf("development"), scenario.tags.toList())
    }

    @Test
    fun checkAlphabeticOrderOfTags() {
        val scenario = scenario("ABC", tags = Tags.of(listOf("xyz", "abc", "def")))

        assertEquals("abc def xyz", scenario.tags.toString())
    }

    @Test
    fun addCheckSameScenario() {
        assertTrue(scenario("ABC").hasSameNameAndResponseBodyAs(scenario("ABC")))
        assertTrue(scenario(" abc ", " Body\n").hasSameNameAndResponseBodyAs(scenario("ABC", "body")))
    }

    @Test
    fun addCheckNotSameScenario() {
        assertFalse(scenario("ABC").hasSameNameAndResponseBodyAs(scenario("ABC", "xxx")))
        assertFalse(scenario("ABC", "xxx").hasSameNameAndResponseBodyAs(scenario("ABD", "xxx")))
    }

    /** The legacy case read `Content-Type` out of one header text. Splitting that text is the importer's work. */
    @Test
    fun testdHeaderValue() {
        val headers = listOf(Header("Content-Type", "text/html; charset=utf-8"), Header("Cache-Control", "max-age=3600"))

        val scenario = scenario("ABC", headers = headers)

        assertEquals("text/html; charset=utf-8", scenario.headers.single { it.name == "Content-Type" }.value)
        assertEquals(headers, scenario.headers)
    }
}
