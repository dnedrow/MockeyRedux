package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The upstream session and the handling of redirects, against a stub upstream. Every request goes the way of
 * a request in proxy mode: the request of a client of the mock endpoint is forwarded, and the answer is
 * relayed.
 */
class SessionAndRedirectTest {
    private val text = "Content-Type" to "text/plain"
    private val redirects = listOf(301, 302, 303, 307, 308)

    /** Forwards the request of a client for the absolute target [url] within [session], and returns what the client gets. */
    private fun JdkUpstreamClient.proxied(
        session: UpstreamSession,
        method: String,
        url: String,
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): MockResponse {
        val query = url.substringAfter('?', missingDelimiterValue = "").ifEmpty { null }
        val request = MockRequest(method, "http://mockey.test:8080/service/${url.substringBefore('?')}", query, headers, body)
        return runBlocking { relayed(forward(upstreamRequest(request, url), session), method) }
    }

    private fun headers(vararg headers: Pair<String, String>) = headers.map { (name, value) -> Header(name, value) }

    /** A real service that sets the cookie `sid` at `/login` and reports where a request arrived everywhere else. */
    private fun serviceWithLogin() =
        StubUpstream(respond = { request ->
            when (request.target) {
                "/login" -> StubResponse(200, "logged in", text, "Set-Cookie" to "sid=abc; Path=/; HttpOnly")
                else -> StubResponse(200, "arrived at ${request.target}", text)
            }
        })

    /** A real service that answers `/redirect/<status>` with that status and a `Location`, and reports where any other request arrived. */
    private fun serviceWithRedirects() =
        StubUpstream(respond = { request ->
            when (
                val status =
                    request.target
                        .substringBefore('?')
                        .removePrefix("/redirect/")
                        .toIntOrNull()
            ) {
                null -> StubResponse(200, "arrived at ${request.target}", text)
                else -> StubResponse(status, "redirect $status", text, "Location" to "/final?from=$status")
            }
        })

    @Test
    fun aCookieFromTheExchangeOfOneClientIsSentForAnotherClient() {
        val session = UpstreamSession()
        serviceWithLogin().use { upstream ->
            JdkUpstreamClient().use { client ->
                val login = client.proxied(session, "POST", "${upstream.origin}/login", headers("User-Agent" to "client-a"))

                client.proxied(session, "GET", "${upstream.origin}/orders", headers("User-Agent" to "client-b"))

                val arrived = upstream.requests.last()
                assertEquals(listOf("client-b"), arrived.headerValues("User-Agent"))
                assertEquals(listOf("sid=abc"), arrived.headerValues("Cookie"))
                assertEquals(emptyList(), upstream.requests.first().headerValues("Cookie"))
                assertEquals(listOf("sid=abc; Path=/; HttpOnly"), login.headerValues("Set-Cookie"))
            }
        }
    }

    @Test
    fun theSessionIsTheSameForEveryUpstreamClient() {
        val session = UpstreamSession()
        serviceWithLogin().use { upstream ->
            JdkUpstreamClient().use { it.proxied(session, "GET", "${upstream.origin}/login") }

            JdkUpstreamClient().use { it.proxied(session, "GET", "${upstream.origin}/orders") }

            assertEquals(listOf("sid=abc"), upstream.requests.last().headerValues("Cookie"))
        }
    }

    @Test
    fun theCookiesOfAClientAreNotForwarded() {
        val session = UpstreamSession()
        serviceWithLogin().use { upstream ->
            JdkUpstreamClient().use { client ->
                client.proxied(session, "GET", "${upstream.origin}/orders", headers("Cookie" to "local=1"))
                client.proxied(session, "GET", "${upstream.origin}/login", headers("Cookie" to "local=1"))
                client.proxied(session, "GET", "${upstream.origin}/orders", headers("Cookie" to "local=1", "cookie" to "sid=mine"))

                assertEquals(listOf(emptyList(), emptyList(), listOf("sid=abc")), upstream.requests.map { it.headerValues("Cookie") })
            }
        }
    }

    @Test
    fun aCookieGoesToItsHostAndItsPathOnly() {
        val session = UpstreamSession()
        val setsCookie = StubResponse(200, "set", text, "Set-Cookie" to "sid=abc; Path=/stub/cookie")
        StubUpstream(respond = { setsCookie }).use { upstream ->
            StubUpstream().use { other ->
                JdkUpstreamClient().use { client ->
                    client.proxied(session, "GET", "${upstream.origin}/stub/cookie/set")

                    client.proxied(session, "GET", "${upstream.origin}/stub/cookie/echo")
                    client.proxied(session, "GET", "${upstream.origin}/stub/echo")
                    client.proxied(session, "GET", "http://localhost:${upstream.port}/stub/cookie/echo")
                    client.proxied(session, "GET", "${other.origin}/stub/cookie/echo")

                    val cookies = upstream.requests.drop(1).map { it.headerValues("Cookie") }
                    assertEquals(listOf(listOf("sid=abc"), emptyList(), emptyList()), cookies)
                    assertEquals(listOf("sid=abc"), other.requests.single().headerValues("Cookie"))
                }
            }
        }
    }

    @Test
    fun emptyingTheSessionLeavesTheNextRequestWithoutACookie() {
        val session = UpstreamSession()
        serviceWithLogin().use { upstream ->
            JdkUpstreamClient().use { client ->
                client.proxied(session, "GET", "${upstream.origin}/login")
                client.proxied(session, "GET", "${upstream.origin}/orders")

                session.clear()
                client.proxied(session, "GET", "${upstream.origin}/orders")

                assertEquals(listOf(emptyList(), listOf("sid=abc"), emptyList()), upstream.requests.map { it.headerValues("Cookie") })
            }
        }
    }

    @Test
    fun aGetRequestFollowsARedirect() {
        serviceWithRedirects().use { upstream ->
            JdkUpstreamClient().use { client ->
                val sent = headers("Authorization" to "******", "X-Request-Id" to "7")

                val answers = redirects.map { client.proxied(UpstreamSession(), "GET", "${upstream.origin}/redirect/$it?x=1", sent) }

                assertEquals(redirects.map { 200 to "arrived at /final?from=$it" }, answers.map { it.status to it.bodyText })
                assertEquals(redirects.map { null }, answers.map { it.header("Location") })
                assertEquals(redirects.flatMap { listOf("/redirect/$it?x=1", "/final?from=$it") }, upstream.requests.map { it.target })
                assertEquals(setOf("GET"), upstream.requests.map { it.method }.toSet())
                assertEquals(setOf(listOf("******")), upstream.requests.map { it.headerValues("Authorization") }.toSet())
                assertEquals(setOf(listOf("7")), upstream.requests.map { it.headerValues("X-Request-Id") }.toSet())
            }
        }
    }

    @Test
    fun aHeadRequestFollowsARedirect() {
        serviceWithRedirects().use { upstream ->
            JdkUpstreamClient().use { client ->
                val answers = redirects.map { client.proxied(UpstreamSession(), "HEAD", "${upstream.origin}/redirect/$it") }

                assertEquals(redirects.map { 200 }, answers.map { it.status })
                assertEquals(redirects.map { 0 }, answers.map { it.body.size })
                assertEquals(redirects.map { "arrived at /final?from=$it".length.toString() }, answers.map { it.header("Content-Length") })
                assertEquals(redirects.flatMap { listOf("/redirect/$it", "/final?from=$it") }, upstream.requests.map { it.target })
                assertEquals(setOf("HEAD"), upstream.requests.map { it.method }.toSet())
            }
        }
    }

    @Test
    fun aRedirectOfARequestWithAnotherMethodIsReturnedAsItIs() {
        val methods = listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        serviceWithRedirects().use { upstream ->
            JdkUpstreamClient().use { client ->
                val answers =
                    methods.flatMap { method ->
                        redirects.map {
                            client.proxied(
                                UpstreamSession(),
                                method,
                                "${upstream.origin}/redirect/$it",
                                body = "sent".toByteArray(),
                            )
                        }
                    }

                val expected = methods.flatMap { redirects.map { status -> Triple(status, "/final?from=$status", "redirect $status") } }
                assertEquals(expected, answers.map { Triple(it.status, it.header("Location"), it.bodyText) })
                assertEquals(
                    methods.flatMap { method -> redirects.map { method to "/redirect/$it" } },
                    upstream.requests.map {
                        it.method to
                            it.target
                    },
                )
            }
        }
    }

    @Test
    fun aRedirectNamesItsTargetInFullOrRelativeToTheRequest() {
        val start = "/a/b/start?x=1"
        val respond = { request: ReceivedRequest ->
            when (request.target) {
                start -> StubResponse(302, "moved", text, "Location" to request.header("X-Location").orEmpty())
                else -> StubResponse(200, "arrived at ${request.target}", text)
            }
        }
        StubUpstream(respond = respond).use { upstream ->
            JdkUpstreamClient().use { client ->
                val locations =
                    listOf(
                        "/final" to "/final",
                        "final?y=2" to "/a/b/final?y=2",
                        "../final" to "/a/final",
                        "./" to "/a/b/",
                        "?y=2" to "/a/b/start?y=2",
                        "${upstream.origin}/elsewhere?z=3#part" to "/elsewhere?z=3",
                        "//127.0.0.1:${upstream.port}/network" to "/network",
                        "/with space|pipe?q={a}" to "/with%20space%7Cpipe?q=%7Ba%7D",
                        "/escaped%20already?q=%7C" to "/escaped%20already?q=%7C",
                    )

                val answers =
                    locations.map { (location, _) ->
                        client.proxied(UpstreamSession(), "GET", "${upstream.origin}$start", headers("X-Location" to location))
                    }

                assertEquals(locations.map { (_, arrival) -> "arrived at $arrival" }, answers.map { it.bodyText })
            }
        }
    }

    @Test
    fun aRedirectToAnotherHostIsFollowedWithTheSameRequest() {
        StubUpstream().use { other ->
            StubUpstream(respond = { StubResponse(307, "moved", text, "Location" to "${other.origin}/moved?y=2") }).use { upstream ->
                JdkUpstreamClient().use { client ->
                    val sent = headers("Authorization" to "******", "Accept" to "text/plain")

                    val answer = client.proxied(UpstreamSession(), "GET", "${upstream.origin}/start", sent, "asked".toByteArray())

                    val arrived = other.requests.single()
                    assertEquals(200 to "ok", answer.status to answer.bodyText)
                    assertEquals("GET" to "/moved?y=2", arrived.method to arrived.target)
                    assertEquals(listOf("127.0.0.1:${other.port}"), arrived.headerValues("Host"))
                    assertEquals(
                        listOf("******") to listOf("text/plain"),
                        arrived.headerValues("Authorization") to arrived.headerValues("Accept"),
                    )
                    assertEquals("asked", String(arrived.body))
                    assertEquals(1, upstream.requests.size)
                }
            }
        }
    }

    @Test
    fun aRedirectThatNamesNoUrlToFollowIsReturnedAsItIs() {
        val locations = listOf(null, "", "ftp://files.example.com/orders", "mailto:someone@example.com", "http://[::1", "http:///orders")
        val respond = { request: ReceivedRequest ->
            val location = locations[request.target.removePrefix("/case/").toInt()]
            StubResponse(302, listOfNotNull(text, location?.let { "Location" to it }), "moved".toByteArray())
        }
        StubUpstream(respond = respond).use { upstream ->
            JdkUpstreamClient().use { client ->
                val answers = locations.indices.map { client.proxied(UpstreamSession(), "GET", "${upstream.origin}/case/$it") }

                assertEquals(locations.map { 302 to "moved" }, answers.map { it.status to it.bodyText })
                assertEquals(locations, answers.map { it.header("Location") })
                assertEquals(locations.indices.map { "/case/$it" }, upstream.requests.map { it.target })
            }
        }
    }

    @Test
    fun anAnswerWithAnotherStatusOfTheRangeIsNoRedirect() {
        val statuses = listOf(300, 304, 305, 306, 399, 201)
        val respond = { request: ReceivedRequest ->
            StubResponse(request.target.removePrefix("/status/").toInt(), listOf(text, "Location" to "/final"), ByteArray(0))
        }
        StubUpstream(respond = respond).use { upstream ->
            JdkUpstreamClient().use { client ->
                val answers = statuses.map { client.proxied(UpstreamSession(), "GET", "${upstream.origin}/status/$it") }

                assertEquals(statuses, answers.map { it.status })
                assertEquals(statuses.map { "/final" }, answers.map { it.header("Location") })
                assertEquals(statuses.size, upstream.requests.size)
            }
        }
    }

    @Test
    fun aCookieThatARedirectSetsGoesWithTheRequestThatFollows() {
        val respond = { request: ReceivedRequest ->
            when {
                request.target == "/login" -> StubResponse(302, "", text, "Location" to "/page", "Set-Cookie" to "sid=abc; Path=/")
                request.header("Cookie") == "sid=abc" -> StubResponse(200, "welcome", text)
                else -> StubResponse(302, "", text, "Location" to "/login")
            }
        }
        StubUpstream(respond = respond).use { upstream ->
            JdkUpstreamClient().use { client ->
                val answer = client.proxied(UpstreamSession(), "GET", "${upstream.origin}/page")

                assertEquals(200 to "welcome", answer.status to answer.bodyText)
                assertEquals(listOf("/page", "/login", "/page"), upstream.requests.map { it.target })
                assertEquals(listOf(null, null, "sid=abc"), upstream.requests.map { it.header("Cookie") })
                assertNull(answer.header("Set-Cookie"))
            }
        }
    }

    @Test
    fun twentyRedirectsInARowAreFollowed() {
        val respond = { request: ReceivedRequest ->
            val hop = request.target.removePrefix("/hop/").toInt()
            if (hop == MAX_REDIRECTS) StubResponse(200, "arrived", text) else StubResponse(302, "", text, "Location" to "/hop/${hop + 1}")
        }
        StubUpstream(respond = respond).use { upstream ->
            JdkUpstreamClient().use { client ->
                val answer = client.proxied(UpstreamSession(), "GET", "${upstream.origin}/hop/0")

                assertEquals(200 to "arrived", answer.status to answer.bodyText)
                assertEquals((0..20).map { "/hop/$it" }, upstream.requests.map { it.target })
            }
        }
    }

    @Test
    fun redirectsWithoutEndAreAFailure() {
        StubUpstream(respond = { StubResponse(302, "again", text, "Location" to it.target) }).use { upstream ->
            JdkUpstreamClient().use { client ->
                val failure =
                    assertFailsWith<UpstreamException> { client.proxied(UpstreamSession(), "GET", "${upstream.origin}/loop?token=1") }

                assertEquals(
                    "The real service at ${upstream.origin}/loop redirects without end: 20 redirects in a row led to no answer.",
                    failure.message,
                )
                assertEquals(21, upstream.requests.size)
            }
        }
    }

    /**
     * The routes of the stub upstream of the parity harness that the groups `proxy-session` and `proxy-redirect`
     * ask for, and what their recordings state. The harness sends its own `User-Agent` with every request.
     */
    @Test
    fun theSessionAndRedirectRecordingsGoAsTheyState() {
        val harness = headers("Host" to "127.0.0.1:8080", "User-Agent" to "mockey-parity")
        val setsCookies =
            StubResponse(
                200,
                "cookie set",
                text,
                "Set-Cookie" to "sid=abc; Path=/stub/cookie; HttpOnly",
                "Set-Cookie" to "theme=dark; Path=/stub/cookie; Max-Age=3600",
            )
        val respond = { request: ReceivedRequest ->
            val route = request.target.removePrefix("/stub/")
            val echo = "http://${request.header("Host")}/stub/echo?after=redirect"
            when {
                route == "cookie/set" -> {
                    setsCookies
                }

                route == "redirect/loop" -> {
                    StubResponse(
                        302,
                        "redirect 302",
                        text,
                        "Location" to "http://${request.header("Host")}${request.target}",
                    )
                }

                route.startsWith("redirect/") -> {
                    StubResponse(
                        route.removePrefix("redirect/").toInt(),
                        "redirect ${route.removePrefix("redirect/")}",
                        text,
                        "Location" to echo,
                    )
                }

                else -> {
                    StubResponse(200, "echo", text)
                }
            }
        }
        StubUpstream(respond = respond).use { upstream ->
            JdkUpstreamClient().use { client ->
                val stub = "${upstream.origin}/stub"
                val echo = "${upstream.origin}/stub/echo?after=redirect"

                // proxy-session-set-cookie (DEV-14): the client gets both cookies with their attributes.
                val set = client.proxied(UpstreamSession(), "GET", "$stub/cookie/set", harness)
                assertEquals(200 to "cookie set", set.status to set.bodyText)
                assertEquals(
                    listOf("sid=abc; Path=/stub/cookie; HttpOnly", "theme=dark; Path=/stub/cookie; Max-Age=3600"),
                    set.headerValues("Set-Cookie"),
                )

                // proxy-session-shared-cookie, proxy-session-client-cookie, and proxy-session-cookie-path, each after cookie/set.
                val session = UpstreamSession()
                client.proxied(session, "GET", "$stub/cookie/set", harness)
                client.proxied(session, "GET", "$stub/cookie/echo", harness)
                client.proxied(session, "GET", "$stub/cookie/echo", harness + Header("Cookie", "local=1"))
                client.proxied(session, "GET", "$stub/echo", harness)
                val arrivedWithSession = upstream.requests.takeLast(3)
                assertEquals(
                    listOf(listOf("sid=abc; theme=dark"), listOf("sid=abc; theme=dark"), emptyList()),
                    arrivedWithSession.map { it.headerValues("Cookie") },
                )
                assertEquals(setOf(listOf("mockey-parity")), arrivedWithSession.map { it.headerValues("User-Agent") }.toSet())

                // proxy-redirect-get-301, -302, -303, and -307: the request arrives at the echo route as the harness sent it.
                listOf(301, 302, 303, 307).forEach { status ->
                    val answer = client.proxied(UpstreamSession(), "GET", "$stub/redirect/$status", harness)
                    val arrived = upstream.requests.last()
                    assertEquals(200, answer.status, "GET $status")
                    assertEquals("GET" to "/stub/echo?after=redirect", arrived.method to arrived.target, "GET $status")
                    assertEquals(listOf("127.0.0.1:${upstream.port}"), arrived.headerValues("Host"), "GET $status")
                    assertEquals(listOf("mockey-parity"), arrived.headerValues("User-Agent"), "GET $status")
                    assertEquals(emptyList(), arrived.headerValues("Cookie"), "GET $status")
                }

                // proxy-redirect-head-302 (DEV-10): the HEAD request follows the redirect as a HEAD request.
                val head = client.proxied(UpstreamSession(), "HEAD", "$stub/redirect/302", harness)
                assertEquals(Triple(200, "text/plain", 0), Triple(head.status, head.contentType, head.body.size))
                assertEquals("HEAD" to "/stub/echo?after=redirect", upstream.requests.last().let { it.method to it.target })

                // proxy-redirect-post-302, -303 (DEV-42), and -307: the client gets the redirect.
                listOf(302, 303, 307).forEach { status ->
                    val sent = harness + Header("Content-Type", "text/plain")
                    val answer = client.proxied(UpstreamSession(), "POST", "$stub/redirect/$status", sent, "posted".toByteArray())
                    assertEquals(
                        Triple(status, echo, "redirect $status"),
                        Triple(answer.status, answer.header("Location"), answer.bodyText),
                    )
                    assertEquals("POST" to "/stub/redirect/$status", upstream.requests.last().let { it.method to it.target })
                }

                // proxy-redirect-loop (DEV-13): the redirects without end are a failure.
                assertFailsWith<UpstreamException> { client.proxied(UpstreamSession(), "GET", "$stub/redirect/loop", harness) }
            }
        }
    }
}
