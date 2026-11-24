package io.github.dnedrow.mockey.core.routing

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UrlPatternTest {
    private fun match(
        pattern: String,
        target: String,
    ): Map<String, String>? = UrlPattern.compile(pattern).match(Target(target))

    private fun matches(
        pattern: String,
        target: String,
    ): Boolean = match(pattern, target) != null

    @Test
    fun aVariableMatchesOnePartOfThePath() {
        assertEquals(mapOf("customerId" to "Ab12"), match("customer/{customerId}", "customer/Ab12"))
    }

    @Test
    fun aVariableDoesNotReachBeyondOnePartOfThePath() {
        assertNull(match("id/{ID}/test", "id/23/otherid/23/test"))
        assertNull(match("customer/{id}", "customer/a/b"))
    }

    @Test
    fun aSlashThatAnEscapeStandsForEndsAVariableToo() {
        assertNull(match("customer/{id}", "customer/a%2Fb"))
        assertEquals(mapOf("first" to "a", "second" to "b"), match("customer/{first}/{second}", "customer/a%2Fb"))
    }

    @Test
    fun theWholeTargetMustMatch() {
        assertNull(match("customer/{id}", "customer/123/orders"))
        assertNull(match("customer/{id}", "api/customer/123"))
        assertNull(match("customer/{id}/orders", "customer/123"))
        assertNull(match("customer/{id}/orders", "customer/123/orders2"))
        assertNull(match("customer", "customers"))
        assertNull(match("customers", "customer"))
        assertNull(match("customer", "xcustomer"))
    }

    @Test
    fun severalVariablesEachGetTheirValue() {
        val values = match("http://example.com/hotels/{hotel}/bookings/{booking}", "http://example.com/hotels/1/bookings/42")

        assertEquals(mapOf("hotel" to "1", "booking" to "42"), values)
        assertEquals(listOf("hotel", "booking"), values?.keys?.toList())
    }

    @Test
    fun aPatternWithoutVariablesMatchesItsOwnTextAndYieldsNoValues() {
        assertEquals(emptyMap(), match("orders/list", "orders/list"))
        assertNull(match("orders/list", "orders/other"))
    }

    @Test
    fun aVariableNeedsAtLeastOneCharacter() {
        assertNull(match("customer/{id}", "customer/"))
        assertNull(match("customer/{id}", "customer"))
        assertNull(match("customer/{id}/orders", "customer//orders"))
        assertNull(match("report-{year}.json", "report-.json"))
        assertEquals(mapOf("id" to "7"), match("customer/{id}", "customer/7"))
    }

    @Test
    fun literalTextMatchesInAnyLetterCase() {
        assertEquals(mapOf("id" to "123"), match("customer/{id}", "CUSTOMER/123"))
        assertEquals(mapOf("id" to "123"), match("Customer/{id}/Orders", "customer/123/ORDERS"))
        assertEquals(emptyMap(), match("http://API.example.com/Users", "HTTP://api.EXAMPLE.com/users"))
        assertEquals(emptyMap(), match("café/ÉCOLE/straße", "CAFÉ/école/STRAßE"))
    }

    @Test
    fun aValueKeepsTheLetterCaseOfTheTarget() {
        assertEquals(mapOf("ID" to "aBc-DeF"), match("CUSTOMER/{ID}", "customer/aBc-DeF"))
    }

    @Test
    fun aValueIsPercentDecoded() {
        assertEquals(mapOf("id" to "{123}"), match("text-customer/{id}", "text-customer/%7B123%7D"))
        assertEquals(mapOf("name" to "Jane Doe é"), match("people/{name}/profile", "people/Jane%20Doe%20%C3%A9/profile"))
        assertEquals(mapOf("id" to "a%20b"), match("customer/{id}", "customer/a%2520b"))
    }

    @Test
    fun theDefaultPortIsTheSameAsNoPort() {
        assertNotNull(match("http://api.example.com/users", "http://api.example.com:80/users"))
        assertNotNull(match("http://api.example.com:80/users", "http://api.example.com/users"))
        assertNotNull(match("https://api.example.com:443/users", "https://api.example.com/users"))
        assertNotNull(match("https://api.example.com", "https://api.example.com:443/"))
        assertNull(match("http://api.example.com/users", "http://api.example.com:8080/users"))
        assertNull(match("http://api.example.com:8080/users", "http://api.example.com/users"))
        assertNull(match("http://api.example.com/users", "https://api.example.com/users"))
    }

    @Test
    fun theLetterCaseAndASlashAtTheEndOfTheTargetAreIgnored() {
        assertEquals(mapOf("id" to "123"), match("customer/{id}", "Customer/123/"))
    }

    @Test
    fun oneSlashAtTheStartAndOneAtTheEndOfThePatternAreIgnored() {
        assertEquals(mapOf("id" to "123"), match("/customer/{id}/", "customer/123"))
        assertEquals(mapOf("id" to "123"), match("customer/{id}/", "/customer/123"))
        assertEquals(emptyMap(), match("http://api.example.com/users/", "http://api.example.com/users"))
        assertEquals(emptyMap(), match("http://api.example.com/", "http://api.example.com"))
        assertNull(match("customer/{id}//", "customer/123"))
        assertNull(match("//customer/{id}", "customer/123"))
    }

    @Test
    fun theSlashesAfterTheSchemeOfAPatternAreKept() {
        assertEquals(emptyMap(), match("http://", "http://"))
        assertNull(match("http://", "http:/"))
        assertNull(match("http://", "http:"))
    }

    @Test
    fun percentEscapesInTheLiteralTextOfAPatternAreDecoded() {
        assertEquals(emptyMap(), match("http://api.example.com/hello%20world", "http://api.example.com/hello%20world"))
        assertEquals(emptyMap(), match("http://api.example.com/hello%20world", "http://api.example.com/hello world"))
        assertEquals(emptyMap(), match("reports/100%25", "reports/100%25"))
        assertEquals(mapOf("id" to "7"), match("caf%C3%A9/{id}", "CAFÉ/7"))
        assertNull(match("reports/a%2520b", "reports/a%20b"))
        assertEquals(emptyMap(), match("reports/a%2520b", "reports/a%2520b"))
    }

    @Test
    fun aBraceThatAnEscapeStandsForIsLiteralText() {
        val pattern = "http://api.example.com/customer/%7Bid%7D"

        assertEquals(emptyMap(), match(pattern, "http://api.example.com/customer/%7Bid%7D"))
        assertEquals(emptyMap(), match(pattern, "http://api.example.com/customer/{id}"))
        assertNull(match(pattern, "http://api.example.com/customer/7"))
    }

    @Test
    fun aPlusSignInAPatternIsAPlusSign() {
        assertEquals(emptyMap(), match("search/c++", "search/c++"))
        assertEquals(emptyMap(), match("search/c++", "search/c%2B%2B"))
        assertNull(match("search/a+b", "search/a b"))
        assertNull(match("search/a+b", "search/a%20b"))
    }

    @Test
    fun aVariableCanStandForAHost() {
        assertEquals(mapOf("host" to "api.example.com"), match("http://{host}/users", "http://api.example.com/users"))
        assertEquals(mapOf("host" to "api.example.com:8080"), match("http://{host}/users", "http://api.example.com:8080/users"))
        assertEquals(mapOf("env" to "QA1"), match("https://{env}.EXAMPLE.com/users", "https://QA1.example.com/users"))
    }

    @Test
    fun theDefaultPortAfterAVariableIsTheSameAsNoPort() {
        assertEquals(mapOf("host" to "api.example.com"), match("http://{host}:80/users", "http://api.example.com/users"))
        assertEquals(mapOf("host" to "api.example.com"), match("http://{host}:80/users", "http://api.example.com:80/users"))
        assertEquals(mapOf("host" to "api.example.com"), match("http://{host}:80", "http://api.example.com/"))
        assertEquals(mapOf("host" to "api.example.com"), match("http://{host}:8080/users", "http://api.example.com:8080/users"))
        assertNull(match("http://{host}:8080/users", "http://api.example.com/users"))
    }

    @Test
    fun aPortIsOnlyLeftOutWhereItEndsTheHostOfAnAbsolutePattern() {
        assertNull(match("localhost:80/users", "localhost/users"))
        assertEquals(emptyMap(), match("localhost:80/users", "localhost:80/users"))
        assertNull(match("http://api.example.com/a:80/b", "http://api.example.com/a/b"))
        assertNull(match("http://api.example.com:80{x}/users", "http://api.example.com/users"))
        assertEquals(mapOf("x" to "80"), match("http://api.example.com:80{x}/users", "http://api.example.com:8080/users"))
        assertNull(match("{scheme}://api.example.com:80/users", "http://api.example.com:80/users"))
        assertEquals(mapOf("scheme" to "http"), match("{scheme}://api.example.com/users", "http://api.example.com:80/users"))
    }

    @Test
    fun literalTextAroundAVariableInOnePartOfThePathMustMatch() {
        assertEquals(mapOf("year" to "2024"), match("reports/report-{year}.json", "reports/Report-2024.JSON"))
        assertNull(match("reports/report-{year}.json", "reports/report-2024.xml"))
        assertNull(match("reports/report-{year}.json", "reports/summary-2024.json"))
        assertNull(match("reports/report-{year}.json", "reports/report-.json"))
        assertNull(match("v{major}", "v"))
        assertNull(match("{name}.json", ".json"))
        assertNull(match("ab{x}ab", "ab"))
        assertNull(match("ab{x}ab", "abab"))
        assertEquals(mapOf("x" to "c"), match("ab{x}ab", "abcab"))
    }

    @Test
    fun aVariableBeforeLiteralTextEndsWhereThatTextFirstOccurs() {
        assertEquals(mapOf("a" to "1", "b" to "2-3"), match("{a}-{b}", "1-2-3"))
        assertEquals(mapOf("name" to "archive", "extension" to "tar.gz"), match("files/{name}.{extension}", "files/archive.tar.gz"))
        assertEquals(mapOf("name" to "archive", "extension" to "GZ"), match("files/{name}.TAR.{extension}", "files/archive.tar.GZ"))
        assertEquals(mapOf("a" to "x", "b" to "yaz"), match("{a}a{b}a", "xayaza"))
        assertNull(match("{a}-{b}", "12"))
        assertNull(match("{a}-{b}", "1-"))
        assertNull(match("{a}-{b}", "-2"))
        assertEquals(mapOf("a" to "-", "b" to "2"), match("{a}-{b}", "--2"))
    }

    @Test
    fun aVariableBeforeAnotherVariableTakesOneCharacter() {
        assertEquals(mapOf("a" to "x", "b" to "yz"), match("{a}{b}", "xyz"))
        assertEquals(mapOf("a" to "x", "b" to "y", "c" to "z!"), match("{a}{b}{c}", "xyz!"))
        assertNull(match("{a}{b}", "x"))
        assertNull(match("{a}{b}{c}", "xy"))
        assertEquals(mapOf("a" to "x", "b" to "y", "c" to "z"), match("{a}{b}-{c}", "xy-z"))
    }

    @Test
    fun bracesWithoutANameAreLiteralText() {
        assertEquals(emptyMap(), match("a/{}/b", "a/{}/b"))
        assertNull(match("a/{}/b", "a/1/b"))
        assertEquals(emptyMap(), match("a/{id", "a/{id"))
        assertNull(match("a/{id", "a/1"))
        assertEquals(emptyMap(), match("a/id}", "a/id}"))
        assertEquals(emptyMap(), match("a/{b/c}", "a/{b/c}"))
        assertNull(match("a/{b/c}", "a/1"))
    }

    @Test
    fun aNameIsWhatStandsBetweenTheInnermostBraces() {
        assertEquals(mapOf("id" to "5"), match("a/{{id}}", "a/{5}"))
        assertNull(match("a/{{id}}", "a/5"))
        assertEquals(listOf("some-id", "x.y", "a b"), UrlPattern.compile("{some-id}/{x.y}/{a b}").variableNames)
    }

    @Test
    fun aNameThatIsUsedTwiceHasTheValueOfItsFirstUse() {
        assertEquals(mapOf("id" to "1"), match("orders/{id}/items/{id}", "orders/1/items/2"))
        assertEquals(listOf("id", "id"), UrlPattern.compile("orders/{id}/items/{id}").variableNames)
        assertEquals(2, UrlPattern.compile("orders/{id}/items/{id}").variableCount)
    }

    @Test
    fun theEmptyPatternMatchesTheEmptyTargetOnly() {
        assertEquals(emptyMap(), match("", ""))
        assertEquals(emptyMap(), match("", "/"))
        assertEquals(emptyMap(), match("/", ""))
        assertNull(match("", "customer"))
        assertNull(match("customer", ""))
        assertNull(match("{id}", ""))
    }

    @Test
    fun emptyPartsOfAPathMustBeInPatternAndTarget() {
        assertEquals(emptyMap(), match("a//b", "a//b"))
        assertNull(match("a//b", "a/b"))
        assertNull(match("a/b", "a//b"))
        assertNull(match("a/{x}/b", "a//b"))
    }

    @Test
    fun aPatternMatchesItsOwnText() {
        val patterns =
            listOf(
                "customer/{id}",
                "http://api.example.com:80/hello%20world/",
                "http://127.0.0.1:18091/customer/%7Bx%7D",
                "https://api.example.com/a+b/100%25/{x}{y}/caf%C3%A9",
                "/a//b/{}/",
                "",
            )

        assertEquals(List(patterns.size) { true }, patterns.map { matches(it, it) })
    }

    @Test
    fun aPatternTellsHowSpecificItIs() {
        val summary = UrlPattern.compile("api/cid/{customerId}/feature/summary")
        val feature = UrlPattern.compile("api/cid/{customerId}/feature/{featureId}")

        assertEquals(1, summary.variableCount)
        assertEquals("api/cid//feature/summary".length, summary.literalLength)
        assertEquals(2, feature.variableCount)
        assertEquals("api/cid//feature/".length, feature.literalLength)
    }

    @Test
    fun theLiteralLengthIsCountedInTheFormThatIsCompared() {
        assertEquals("http://api.example.com/a b".length, UrlPattern.compile("http://api.example.com:80/a%20b/").literalLength)
        assertEquals(0, UrlPattern.compile("{id}").literalLength)
        assertEquals(0, UrlPattern.compile("").literalLength)
        assertEquals(1, UrlPattern.compile("{a}/{b}").literalLength)
    }

    @Test
    @Timeout(20)
    fun aLongTargetThatDoesNotMatchIsRefusedQuickly() {
        val dashes = "-".repeat(20_000)

        assertNull(match("{a}-{b}-{c}-{d}-{e}-{f}-{g}-{h}x", dashes))
        assertNull(match("{a}{b}{c}{d}{e}{f}{g}{h}x", dashes))
        assertNotNull(match("{a}-{b}-{c}-{d}-{e}-{f}-{g}-{h}", dashes))
    }
}
