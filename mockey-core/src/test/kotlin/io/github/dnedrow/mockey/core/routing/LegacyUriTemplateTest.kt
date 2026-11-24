package io.github.dnedrow.mockey.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The cases of the legacy `com.mockey.model.UriTemplateTest`, with the names of its test methods. The legacy
 * `UriTemplate` answered with a map of the variables, which was empty for a URL that did not match and for a
 * match without variables alike. A [UrlPattern] answers `null` for a target that does not match. Legacy wrote
 * the names of the variables in lower case; they are kept as the pattern writes them.
 *
 * At the legacy commit that is the reference, a variable matches only text in braces (DEV-01). The three
 * cases that expect a pattern with variables to match fail there.
 */
class LegacyUriTemplateTest {
    private fun match(
        template: String,
        url: String,
    ): Map<String, String>? = UrlPattern.compile(template).match(Target(url))

    @Test
    fun validateParametizedUrl() {
        val results = assertNotNull(match("http://feeling/{id}", "http://feeling/happy"))

        assertEquals(mapOf("id" to "happy"), results)
    }

    @Test
    fun validateSimpleURL() {
        val results = assertNotNull(match("http://feeling", "http://feeling"))

        assertEquals(0, results.size)
    }

    @Test
    fun validateMultipleVariablesInURL() {
        val results = assertNotNull(match("http://id/{ID1}/otherid/{ID2}/test", "http://id/23/otherid/23/test"))

        assertEquals(2, results.size)
    }

    /** `{ID}` must not have the value `23/otherid/23`. */
    @Test
    fun validateOddMatchVariablesInURL() {
        assertNull(match("http://id/{ID}/test", "http://id/23/otherid/23/test"))
    }

    @Test
    fun validateCaseInsensitiveVariablesInURL() {
        val results = assertNotNull(match("http://id/{ID1}/OTHERid/{ID2}/test", "http://id/23/otherID/23/test"))

        assertEquals(mapOf("ID1" to "23", "ID2" to "23"), results)
    }
}
