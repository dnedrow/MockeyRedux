package io.github.dnedrow.mockey.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetTest {
    private fun normalized(target: String): String = Target(target).normalized

    @Test
    fun aTargetThatBeginsWithAnHttpSchemeIsAbsolute() {
        val absolute = listOf("http://api.example.com/users", "https://api.example.com", "HTTP://API.EXAMPLE.COM", "hTTps://x/y", "http://")

        assertEquals(List(absolute.size) { true }, absolute.map { Target(it).isAbsolute })
    }

    @Test
    fun everyOtherTargetIsRelative() {
        val relative =
            listOf(
                "customer/123",
                "",
                "api.example.com/users",
                "127.0.0.1:18091/stub/echo",
                "prefix/http://api.example.com/users",
                "/http://api.example.com/users",
                " http://api.example.com",
                "http:/api.example.com",
                "http:api.example.com",
                "httpx://api.example.com",
                "ftp://files.example.com/a",
                "http%3A%2F%2Fapi.example.com",
            )

        assertEquals(List(relative.size) { false }, relative.map { Target(it).isAbsolute })
    }

    @Test
    fun percentEscapesAreDecodedAsUtf8() {
        assertEquals("customer/{123}", normalized("customer/%7B123%7D"))
        assertEquals("a b/é/€/😀", normalized("a%20b/%C3%A9/%E2%82%AC/%F0%9F%98%80"))
        assertEquals("a/b", normalized("a%2Fb"))
        assertEquals("100%", normalized("100%25"))
        assertEquals("a{b}", normalized("a%7bb%7d"))
    }

    @Test
    fun aDecodedEscapeIsNotDecodedAgain() {
        assertEquals("a%20b", normalized("a%2520b"))
    }

    @Test
    fun aPercentSignThatStartsNoEscapeStaysAsItIs() {
        assertEquals("100%", normalized("100%"))
        assertEquals("100%/a", normalized("100%/a"))
        assertEquals("%4", normalized("%4"))
        assertEquals("%zz/%4g/%%", normalized("%zz/%4g/%%"))
        assertEquals("% 41", normalized("%%2041"))
        assertEquals("%１２", normalized("%１２"))
    }

    @Test
    fun aPlusSignStaysAPlusSign() {
        assertEquals("a+b", normalized("a+b"))
        assertEquals("a+b", normalized("a%2Bb"))
        assertEquals("a+b c", normalized("a+b%20c"))
    }

    @Test
    fun bytesThatAreNotUtf8BecomeTheReplacementCharacter() {
        assertEquals("caf\uFFFD", normalized("caf%E9"))
        assertEquals("a\uFFFDb", normalized("a%FFb"))
    }

    @Test
    fun charactersOutsideEscapesStayAsTheyAre() {
        assertEquals("grüße/日本/😀", normalized("grüße/日本/😀"))
    }

    @Test
    fun oneSlashAtTheStartAndOneAtTheEndAreLeftOut() {
        assertEquals("Customer/123", normalized("Customer/123/"))
        assertEquals("customer/123", normalized("/customer/123"))
        assertEquals("customer/123", normalized("/customer/123/"))
        assertEquals("http://api.example.com/users", normalized("http://api.example.com/users/"))
        assertEquals("http://api.example.com", normalized("http://api.example.com/"))
    }

    @Test
    fun noMoreThanOneSlashIsLeftOutAtEitherEnd() {
        assertEquals("/customer/123/", normalized("//customer/123//"))
        assertEquals("a//b", normalized("a//b"))
        assertEquals("", normalized("/"))
        assertEquals("", normalized("//"))
        assertEquals("/", normalized("///"))
        assertEquals("http://api.example.com/", normalized("http://api.example.com//"))
    }

    @Test
    fun aSlashThatAnEscapeStandsForCountsAsASlash() {
        assertEquals("customer/123", normalized("%2Fcustomer/123%2F"))
    }

    @Test
    fun theSlashesAfterTheSchemeAreKept() {
        assertEquals("http://", normalized("http://"))
        assertEquals("http://", normalized("http:///"))
        assertEquals("https://", normalized("https://"))
    }

    @Test
    fun theLetterCaseIsKept() {
        assertEquals("Customer/Ab12", normalized("Customer/Ab12"))
        assertEquals("HTTP://Api.Example.COM/Users", normalized("HTTP://Api.Example.COM/Users"))
    }

    @Test
    fun theDefaultPortOfTheSchemeIsLeftOut() {
        assertEquals("http://api.example.com/users", normalized("http://api.example.com:80/users"))
        assertEquals("https://api.example.com/users", normalized("https://api.example.com:443/users"))
        assertEquals("http://api.example.com", normalized("http://api.example.com:80"))
        assertEquals("http://api.example.com", normalized("http://api.example.com:80/"))
        assertEquals("HTTPS://Api.Example.com/a", normalized("HTTPS://Api.Example.com:443/a"))
    }

    @Test
    fun anyOtherPortIsKept() {
        assertEquals("http://api.example.com:8080/users", normalized("http://api.example.com:8080/users"))
        assertEquals("http://api.example.com:443/users", normalized("http://api.example.com:443/users"))
        assertEquals("https://api.example.com:80/users", normalized("https://api.example.com:80/users"))
        assertEquals("http://api.example.com:800", normalized("http://api.example.com:800"))
        assertEquals("http://api.example.com:99999999999/a", normalized("http://api.example.com:99999999999/a"))
    }

    @Test
    fun anEmptyPortAndAPortWithLeadingZerosAreReadAsNumbers() {
        assertEquals("http://api.example.com/users", normalized("http://api.example.com:/users"))
        assertEquals("http://api.example.com/users", normalized("http://api.example.com:080/users"))
        assertEquals("http://api.example.com:0/users", normalized("http://api.example.com:0/users"))
    }

    @Test
    fun thePortIsWhatFollowsTheLastColonBeforeTheFirstSlash() {
        assertEquals("http://[::1]/a", normalized("http://[::1]:80/a"))
        assertEquals("http://[::1]/a", normalized("http://[::1]/a"))
        assertEquals("http://[::80]/a", normalized("http://[::80]/a"))
        assertEquals("http://user:80@api.example.com/a", normalized("http://user:80@api.example.com/a"))
        assertEquals("http://user:secret@api.example.com/a", normalized("http://user:secret@api.example.com:80/a"))
        assertEquals("http://api.example.com/a:80", normalized("http://api.example.com/a:80"))
        assertEquals("http://api.example.com/a:80/b", normalized("http://api.example.com/a:80/b"))
    }

    @Test
    fun aPortThatAnEscapeSpellsIsAPort() {
        assertEquals("http://api.example.com/a", normalized("http://api.example.com%3A80/a"))
    }

    @Test
    fun aRelativeTargetHasNoPort() {
        assertEquals("localhost:80/users", normalized("localhost:80/users"))
        assertEquals("prefix/http://api.example.com:80/users", normalized("prefix/http://api.example.com:80/users"))
        assertEquals("http://api.example.com:80/users", normalized("http%3A//api.example.com:80/users"))
    }

    @Test
    fun targetsWithTheSameTextAreEqual() {
        assertEquals(Target("customer/123"), Target("customer/123"))
        assertFalse(Target("customer/123") == Target("Customer/123"))
        assertTrue(Target("customer/123").hashCode() == Target("customer/123").hashCode())
    }
}
