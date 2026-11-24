package io.github.dnedrow.mockey.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockRequestUriTest {
    private fun parsed(requestUri: String): MockRequestUri = assertNotNull(MockRequestUri.parse(requestUri))

    @Test
    fun anAbsoluteTargetKeepsBothSlashesAndLosesTheQueryString() {
        val uri = parsed("/service/http://api.example.com/users?page=2")

        assertEquals("http://api.example.com/users", uri.target.text)
        assertTrue(uri.target.isAbsolute)
        assertEquals("page=2", uri.query)
    }

    @Test
    fun aRelativeTargetIsTheTextAfterThePrefix() {
        val uri = parsed("/service/customer/123")

        assertEquals("customer/123", uri.target.text)
        assertFalse(uri.target.isAbsolute)
        assertNull(uri.query)
    }

    @Test
    fun theQueryStringStartsAtTheFirstQuestionMark() {
        assertEquals(MockRequestUri(Target("search"), "q=a?b&next=/service/x?y"), parsed("/service/search?q=a?b&next=/service/x?y"))
    }

    @Test
    fun anEmptyQueryStringIsNotAMissingOne() {
        assertEquals("", parsed("/service/customer/123?").query)
        assertNull(parsed("/service/customer/123").query)
    }

    @Test
    fun theTargetAndTheQueryStringAreKeptAsTheClientWroteThem() {
        val uri = parsed("/service/HTTP://Api.Example.com:80/a%20b/%7Bid%7D//?name=jane%20doe&tags=a+b")

        assertEquals("HTTP://Api.Example.com:80/a%20b/%7Bid%7D//", uri.target.text)
        assertEquals("name=jane%20doe&tags=a+b", uri.query)
    }

    @Test
    fun thePrefixAloneGivesAnEmptyRelativeTarget() {
        val uri = parsed("/service/")

        assertEquals("", uri.target.text)
        assertFalse(uri.target.isAbsolute)
    }

    @Test
    fun aPathThatDoesNotBeginWithThePrefixIsNoMockTraffic() {
        val others =
            listOf("/status", "/service", "/services/customer", "/Service/customer", "/api/v1/service/x", "service/customer", "/", "")

        assertEquals(List(others.size) { null }, others.map { MockRequestUri.parse(it) })
    }

    @Test
    fun aPrefixInTheQueryStringDoesNotMakeMockTraffic() {
        assertNull(MockRequestUri.parse("/status?next=/service/customer"))
    }
}
