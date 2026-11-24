package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpstreamSessionTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private var time = start
    private val session = UpstreamSession { time }
    private val url = "http://api.example.com/"

    /** Lets the session take the answer to a request for [url] that sets each of [cookies] with a `Set-Cookie` header. */
    private fun answer(
        url: String,
        vararg cookies: String,
    ) = session.take(url, cookies.map { Header("Set-Cookie", it) })

    /** Returns the cookies that go with a request for the URL of this test, [seconds] after the start. */
    private fun cookiesAfter(seconds: Long): String? {
        time = start.plusSeconds(seconds)
        return session.cookieHeader(url)
    }

    @Test
    fun aCookieGoesWithLaterRequestsToTheHostThatSetIt() {
        answer("http://api.example.com/login", "sid=abc")

        assertEquals("sid=abc", session.cookieHeader("http://api.example.com/orders?page=2"))
        assertEquals("sid=abc", session.cookieHeader("https://API.example.com:8443"))
        assertNull(session.cookieHeader("http://www.example.com/orders"))
        assertNull(session.cookieHeader("http://v2.api.example.com/orders"))
        assertNull(session.cookieHeader("http://example.com/orders"))
    }

    @Test
    fun aCookieThatNamesNoPathAppliesToTheDirectoryOfItsRequest() {
        answer("http://127.0.0.1:18091/stub/cookie/set", "sid=abc")

        assertEquals("sid=abc", session.cookieHeader("http://127.0.0.1:18091/stub/cookie/echo"))
        assertEquals("sid=abc", session.cookieHeader("http://127.0.0.1:18091/stub/cookie"))
        assertEquals("sid=abc", session.cookieHeader("http://127.0.0.1:18091/stub/cookie/a/b?c=d"))
        assertNull(session.cookieHeader("http://127.0.0.1:18091/stub/cookies"))
        assertNull(session.cookieHeader("http://127.0.0.1:18091/stub/echo"))
        assertNull(session.cookieHeader("http://127.0.0.1:18091/"))
    }

    @Test
    fun aCookieAppliesToThePathThatItNamesAndToWhatLiesBelow() {
        answer("http://api.example.com/login", "a=1; Path=/orders", "b=2; Path=/orders/", "c=3; Path=/", "d=4; Path=orders")

        assertEquals("a=1; c=3; d=4", session.cookieHeader("http://api.example.com/orders"))
        assertEquals("b=2; a=1; c=3; d=4", session.cookieHeader("http://api.example.com/orders/7"))
        assertEquals("c=3; d=4", session.cookieHeader("http://api.example.com/ordersX"))
        assertEquals("c=3; d=4", session.cookieHeader("http://api.example.com"))
    }

    @Test
    fun aCookieThatNamesADomainAppliesToEveryHostInIt() {
        answer(url, "wide=1; Domain=example.com", "dotted=2; Domain=.Example.COM", "own=3; Domain=api.example.com")

        assertEquals("wide=1; dotted=2; own=3", session.cookieHeader("http://api.example.com/"))
        assertEquals("wide=1; dotted=2; own=3", session.cookieHeader("http://v2.api.example.com/"))
        assertEquals("wide=1; dotted=2", session.cookieHeader("http://www.example.com/"))
        assertEquals("wide=1; dotted=2", session.cookieHeader("http://example.com/"))
        assertNull(session.cookieHeader("http://badexample.com/"))
    }

    @Test
    fun aCookieForADomainThatItsHostIsNotInIsNotTaken() {
        answer(url, "a=1; Domain=other.com", "b=2; Domain=com", "c=3; Domain=v2.api.example.com", "d=4; Domain=ample.com")

        assertNull(session.cookieHeader("http://api.example.com/"))
        assertNull(session.cookieHeader("http://v2.api.example.com/"))
        assertNull(session.cookieHeader("http://other.com/"))
    }

    @Test
    fun aHostOfOneLabelSetsCookiesForItselfOnly() {
        answer("http://localhost:8080/", "a=1; Domain=localhost")
        answer("http://intranet/", "b=2")

        assertEquals("a=1", session.cookieHeader("http://localhost/"))
        assertEquals("b=2", session.cookieHeader("http://intranet:9000/"))
        assertNull(session.cookieHeader("http://www.localhost/"))
    }

    @Test
    fun aCookieOfAnIpAddressAppliesToThatAddressOnly() {
        answer("http://127.0.0.1:18091/", "a=1", "b=2; Domain=127.0.0.1", "c=3; Domain=0.0.1")
        answer("http://[::1]:8080/", "d=4")

        assertEquals("a=1; b=2", session.cookieHeader("http://127.0.0.1:9000/"))
        assertEquals("d=4", session.cookieHeader("http://[::1]/"))
        assertNull(session.cookieHeader("http://127.0.0.2/"))
    }

    @Test
    fun aCookieTakesThePlaceOfTheOneWithItsNameDomainAndPath() {
        answer(url, "sid=old", "theme=dark")

        answer(url, "sid=new")
        answer(url, "sid=deep; Path=/orders")

        assertEquals("sid=new; theme=dark", session.cookieHeader("http://api.example.com/"))
        assertEquals("sid=deep; sid=new; theme=dark", session.cookieHeader("http://api.example.com/orders"))
    }

    @Test
    fun aSecureCookieGoesOverHttpsOnly() {
        answer("https://api.example.com/", "token=1; Secure", "plain=2; HttpOnly; SameSite=Strict")

        assertEquals("token=1; plain=2", session.cookieHeader("https://api.example.com/"))
        assertEquals("plain=2", session.cookieHeader("http://api.example.com/"))
    }

    @Test
    fun aCookieWithAMaxAgeLastsThatManySeconds() {
        answer(url, "short=1; Max-Age=60", "kept=2")

        val before = cookiesAfter(seconds = 59)
        val after = cookiesAfter(seconds = 60)

        assertEquals("short=1; kept=2", before)
        assertEquals("kept=2", after)
    }

    @Test
    fun aCookieWithAnExpiresDateLastsUntilThen() {
        answer(url, "dated=1; Expires=Thu, 01 Jan 2026 00:01:00 GMT", "kept=2")

        val before = cookiesAfter(seconds = 59)
        val after = cookiesAfter(seconds = 60)

        assertEquals("dated=1; kept=2", before)
        assertEquals("kept=2", after)
    }

    @Test
    fun aCookieThatHasExpiredAlreadyRemovesTheOneInItsPlace() {
        answer(url, "a=1", "b=2", "c=3", "d=4")

        answer(url, "a=; Max-Age=0", "b=gone; Max-Age=-1", "c=; Expires=Thu, 01 Jan 1970 00:00:00 GMT", "e=5; Max-Age=0")

        assertEquals("d=4", session.cookieHeader(url))
    }

    @Test
    fun maxAgeCountsBeforeExpiresAndAnAttributeThatIsNotWellFormedDoesNotCount() {
        answer(
            url,
            "both=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=60",
            "words=2; Max-Age=soon",
            "undated=3; Expires=tomorrow",
            "fallback=4; Max-Age=1h; Expires=Thu, 01 Jan 2026 00:00:30 GMT",
            "long=5; Max-Age=99999999999999999999999",
        )

        val atOnce = cookiesAfter(seconds = 0)
        val afterHalfAMinute = cookiesAfter(seconds = 30)
        val afterAMinute = cookiesAfter(seconds = 60)

        assertEquals("both=1; words=2; undated=3; fallback=4; long=5", atOnce)
        assertEquals("both=1; words=2; undated=3; long=5", afterHalfAMinute)
        assertEquals("words=2; undated=3; long=5", afterAMinute)
    }

    @Test
    fun theDateOfACookieIsReadInTheFormsThatServersWrite() {
        val expected = Instant.parse("1994-11-06T08:49:37Z")

        assertEquals(expected, cookieDate("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(expected, cookieDate("Sunday, 06-Nov-94 08:49:37 GMT"))
        assertEquals(expected, cookieDate("Sun Nov  6 08:49:37 1994"))
        assertEquals(expected, cookieDate("Sun, 06-Nov-1994 08:49:37 GMT"))
        assertEquals(expected, cookieDate("6 november 1994 8:49:37"))
        assertEquals(Instant.parse("2015-10-21T07:28:00Z"), cookieDate("Wed, 21-Oct-15 07:28:00 GMT"))
        assertEquals(Instant.parse("2069-12-31T23:59:59Z"), cookieDate("31 Dec 69 23:59:59"))
        assertEquals(Instant.parse("1970-01-01T00:00:00Z"), cookieDate("01 Jan 70 00:00:00"))
    }

    @Test
    fun aDateThatNamesNoTimeIsNotRead() {
        val texts =
            listOf(
                "",
                "tomorrow",
                "06 Nov 1994",
                "08:49:37 06 1994",
                "Nov 1994 08:49:37",
                "32 Nov 1994 08:49:37",
                "31 Feb 1994 08:49:37",
                "06 Nov 1600 08:49:37",
                "06 Nov 1994 24:00:00",
                "06 Nov 1994 08:60:00",
                "06 Nov 1994 08:49:60",
            )

        val read = texts.map(::cookieDate)

        assertEquals(texts.map { null }, read)
    }

    @Test
    fun aSetCookieHeaderThatNamesNoCookieIsNotTaken() {
        answer(url, "", "novalue", "=abc", " =abc; Path=/", "; Path=/")

        assertNull(session.cookieHeader(url))
    }

    @Test
    fun theValueOfACookieIsKeptAsItIsWritten() {
        answer(url, "token=a=b==", "quoted=\"x y\"", " spaced = v ; Path=/", "empty=")

        assertEquals("token=a=b==; quoted=\"x y\"; spaced=v; empty=", session.cookieHeader(url))
    }

    @Test
    fun theSetCookieHeaderIsFoundInAnyLetterCase() {
        val headers =
            listOf(
                Header("set-cookie", "a=1"),
                Header("Content-Type", "text/plain"),
                Header("SET-COOKIE", "b=2"),
                Header("Set-Cookie2", "c=3"),
                Header("Cookie", "d=4"),
            )

        session.take(url, headers)

        assertEquals("a=1; b=2", session.cookieHeader(url))
    }

    @Test
    fun emptyingTheSessionLeavesNoCookie() {
        answer(url, "sid=abc", "theme=dark; Domain=example.com; Path=/")

        session.clear()

        assertNull(session.cookieHeader(url))
        answer(url, "sid=next")
        assertEquals("sid=next", session.cookieHeader(url))
    }

    @Test
    fun aRequestGetsTheCookiesOfTheSessionAndNoOther() {
        answer(url, "sid=abc")
        val headers = listOf(Header("Accept", "text/plain"), Header("cookie", "local=1"), Header("X-Request-Id", "7"))
        val inside = UpstreamRequest("POST", "http://api.example.com/orders?x=1", headers, "posted".toByteArray())
        val outside = UpstreamRequest("GET", "http://other.example.com/", headers)

        val withCookie = session.withCookies(inside)
        val withoutCookie = session.withCookies(outside)

        val others = listOf(Header("Accept", "text/plain"), Header("X-Request-Id", "7"))
        assertEquals(others + Header("Cookie", "sid=abc"), withCookie.headers)
        assertEquals("POST" to "http://api.example.com/orders?x=1", withCookie.method to withCookie.url)
        assertContentEquals("posted".toByteArray(), withCookie.body)
        assertEquals(others, withoutCookie.headers)
        assertEquals("GET" to "http://other.example.com/", withoutCookie.method to withoutCookie.url)
    }

    @Test
    fun aUrlThatNamesNoHostHasNoCookies() {
        val setsCookie = listOf(Header("Set-Cookie", "a=1"))

        session.take("http://[::1", setsCookie)
        session.take("not a url", setsCookie)
        session.take("mailto:someone@example.com", setsCookie)

        assertNull(session.cookieHeader("http://[::1"))
        assertNull(session.cookieHeader("not a url"))
        assertNull(session.cookieHeader("mailto:someone@example.com"))
        assertNull(session.cookieHeader(url))
    }
}
