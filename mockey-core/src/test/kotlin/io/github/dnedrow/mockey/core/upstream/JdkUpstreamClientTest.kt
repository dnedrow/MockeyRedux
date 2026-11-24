package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkUpstreamClientTest {
    private val text = "Content-Type" to "text/plain"
    private val allBytes = ByteArray(256) { it.toByte() }

    private fun headers(vararg headers: Pair<String, String>) = headers.map { (name, value) -> Header(name, value) }

    private fun JdkUpstreamClient.send(
        method: String,
        url: String,
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): MockResponse = runBlocking { send(UpstreamRequest(method, url, headers, body)) }

    /** Sends a request to [url] and returns what arrived at the stub. */
    private fun StubUpstream.receive(
        method: String,
        path: String = "/orders",
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): ReceivedRequest {
        JdkUpstreamClient().use { it.send(method, "$origin$path", headers, body) }
        return requests.last()
    }

    /** Returns the answer of a stub that answers every request with [response], as the client returns it for [method]. */
    private fun answer(
        response: StubResponse,
        method: String = "GET",
    ): MockResponse =
        StubUpstream(respond = { response }).use { upstream ->
            JdkUpstreamClient().use { it.send(method, "${upstream.origin}/orders") }
        }

    @Test
    fun everyMethodIsForwardedUnchanged() {
        StubUpstream().use { upstream ->
            val methods = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "PROPFIND")

            methods.forEach { upstream.receive(it) }

            assertEquals(methods, upstream.requests.map { it.method })
        }
    }

    @Test
    fun theBodyIsForwardedByteForByte() {
        StubUpstream().use { upstream ->
            val binary = upstream.receive("POST", headers = headers("Content-Type" to "application/octet-stream"), body = allBytes)
            val accented =
                upstream.receive(
                    "PUT",
                    headers = headers("Content-Type" to "text/plain; charset=utf-8"),
                    body = "caf\u00e9 \u20ac".toByteArray(),
                )

            assertContentEquals(allBytes, binary.body)
            assertEquals(listOf("application/octet-stream"), binary.headerValues("Content-Type"))
            assertContentEquals("caf\u00e9 \u20ac".toByteArray(), accented.body)
            assertEquals(listOf("text/plain; charset=utf-8"), accented.headerValues("Content-Type"))
        }
    }

    @Test
    fun aRequestWithoutABodyGetsNoBodyAndNoContentType() {
        StubUpstream().use { upstream ->
            val posted = upstream.receive("POST")
            val got = upstream.receive("GET")
            val deleted = upstream.receive("DELETE")

            assertEquals(0, posted.body.size + got.body.size + deleted.body.size)
            assertEquals(emptyList(), (posted.headerNames + got.headerNames + deleted.headerNames).filter { it == "content-type" })
            assertEquals(listOf("0", null, null), listOf(posted, got, deleted).map { it.header("Content-Length") })
        }
    }

    @Test
    fun hostAndContentLengthFitTheForwardedRequest() {
        StubUpstream().use { upstream ->
            val arrived = upstream.receive("POST", body = "posted".toByteArray())

            assertEquals(listOf("127.0.0.1:${upstream.port}"), arrived.headerValues("Host"))
            assertEquals(listOf("6"), arrived.headerValues("Content-Length"))
        }
    }

    @Test
    fun theHeadersOfARequestArriveWithEveryValueInTheirOrder() {
        StubUpstream().use { upstream ->
            val sent =
                headers("Authorization" to "Bearer abc", "X-Request-Id" to "7", "Accept" to "application/json", "X-Request-Id" to "8")

            val arrived = upstream.receive("GET", headers = sent + Header("User-Agent", "curl/8.7.1"))

            assertEquals("Bearer abc", arrived.header("Authorization"))
            assertEquals(listOf("7", "8"), arrived.headerValues("X-Request-Id"))
            assertEquals(listOf("application/json"), arrived.headerValues("Accept"))
            assertEquals(listOf("curl/8.7.1"), arrived.headerValues("User-Agent"))
            assertEquals(
                listOf("accept", "authorization", "host", "user-agent", "x-request-id", "x-request-id"),
                arrived.headerNames.sorted(),
            )
        }
    }

    @Test
    fun aHeaderThatTheJdkClientDoesNotSendIsLeftOut() {
        StubUpstream().use { upstream ->
            val refused = headers("Expect" to "100-continue", "Bad Name" to "x", "X-Control" to "a\u0001b")

            val arrived = upstream.receive("POST", headers = refused + Header("X-After", "kept"), body = "posted".toByteArray())

            assertEquals(listOf("content-length", "host", "user-agent", "x-after"), arrived.headerNames.sorted())
            assertContentEquals("posted".toByteArray(), arrived.body)
        }
    }

    @Test
    fun theQueryStringArrivesAsItIsWritten() {
        StubUpstream().use { upstream ->
            val targets =
                listOf(
                    "/echo?page=2",
                    "/echo?b=2&a=1&name=jane%20doe",
                    "/echo?id=1&id=2",
                    "/echo?flag=&x=1",
                    "/echo?a=1&&b=2",
                    "/echo?q=a+b%2Bc",
                )

            assertEquals(targets, targets.map { upstream.receive("GET", it).target })
        }
    }

    @Test
    fun thePathArrivesAsItIsWritten() {
        StubUpstream().use { upstream ->
            val targets = listOf("/", "/a%2Fb/c%20d", "//double//slash/", "/caf%C3%A9", "/a;v=1/b", "/a:b@c")

            assertEquals(targets, targets.map { upstream.receive("GET", it).target })
        }
    }

    @Test
    fun charactersThatAUriRefusesAreEscaped() {
        StubUpstream().use { upstream ->
            assertEquals("/a%7Cb?q=%7Bx%7D&s=a%20b", upstream.receive("GET", "/a|b?q={x}&s=a b").target)
            assertEquals("/a%5B1%5D%25zz?x=%7Bq%7D&y=%22a%22", upstream.receive("GET", "/a[1]%zz?x=%7Bq%7D&y=\"a\"").target)
            assertEquals("/caf%C3%A9?n=%E2%82%AC", upstream.receive("GET", "/caf\u00e9?n=\u20ac").target)
        }
    }

    @Test
    fun theAnswerIsReturnedAsItCame() {
        val json = """{"error":"not found"}""".toByteArray()

        val notFound = answer(StubResponse(404, listOf("Content-Type" to "application/json", "X-Upstream" to "one"), json))
        val binary = answer(StubResponse(200, listOf("Content-Type" to "application/octet-stream"), allBytes))

        assertEquals(404, notFound.status)
        assertEquals(headers("content-length" to "21", "content-type" to "application/json", "x-upstream" to "one"), notFound.headers)
        assertContentEquals(json, notFound.body)
        assertEquals(200, binary.status)
        assertContentEquals(allBytes, binary.body)
    }

    @Test
    fun everyValueOfARepeatedHeaderOfAnAnswerIsReturnedAndACookieKeepsItsAttributes() {
        val sent =
            listOf(
                "Set-Cookie" to "sid=abc; Path=/; HttpOnly; Secure",
                "X-Multi" to "a",
                "Set-Cookie" to "theme=dark; Path=/stub/cookie; Max-Age=3600",
                "X-Multi" to "b",
            )

        val answer = answer(StubResponse(200, sent, "cookie set".toByteArray()))

        assertEquals(
            listOf("sid=abc; Path=/; HttpOnly; Secure", "theme=dark; Path=/stub/cookie; Max-Age=3600"),
            answer.headerValues("Set-Cookie"),
        )
        assertEquals(listOf("a", "b"), answer.headerValues("X-Multi"))
    }

    @Test
    fun theBodyOfAnAnswerIsCompleteHoweverItsEndIsMarked() {
        listOf(Framing.CONTENT_LENGTH, Framing.CHUNKED, Framing.UNTIL_CLOSE).forEach { framing ->
            val relayed = relayed(answer(StubResponse(200, listOf(text), allBytes, framing)), "GET")

            assertContentEquals(allBytes, relayed.body, framing.name)
            assertEquals(listOf("256"), relayed.headerValues("Content-Length"), framing.name)
            assertEquals(
                emptyList(),
                relayed.headers
                    .map {
                        it.name.lowercase()
                    }.filter { it == "transfer-encoding" || it == "connection" },
                framing.name,
            )
        }
    }

    @Test
    fun theAnswerToAHeadRequestHasTheHeadersAndNoBody() {
        val answer = answer(StubResponse(200, listOf(text), ByteArray(172)), method = "HEAD")

        assertEquals(200, answer.status)
        assertEquals(0, answer.body.size)
        assertEquals(headers("content-length" to "172", "content-type" to "text/plain"), relayed(answer, "HEAD").headers)
    }

    @Test
    fun anAnswerWithAStatusThatHasNoBodyIsReturned() {
        val noContent = answer(StubResponse(204, listOf("X-A" to "b")), method = "DELETE")
        val notModified = answer(StubResponse(304, listOf("ETag" to "\"v1\"")))

        assertEquals(204 to headers("x-a" to "b"), noContent.status to relayed(noContent, "DELETE").headers)
        assertEquals(304 to headers("etag" to "\"v1\""), notModified.status to relayed(notModified, "GET").headers)
        assertEquals(0, noContent.body.size + notModified.body.size)
    }

    @Test
    fun anAnswerThatAsksForCredentialsIsReturnedAsItIs() {
        val answer = answer(StubResponse(401, "who are you", "WWW-Authenticate" to "Basic realm=\"orders\""))

        assertEquals(401, answer.status)
        assertEquals(listOf("Basic realm=\"orders\""), answer.headerValues("WWW-Authenticate"))
        assertEquals("who are you", answer.bodyText)
    }

    @Test
    fun aRedirectOfAPostRequestIsReturnedAsItIs() {
        StubUpstream(respond = { StubResponse(302, "moved", "Location" to "/after") }).use { upstream ->
            val answer = JdkUpstreamClient().use { it.send("POST", "${upstream.origin}/before", body = "posted".toByteArray()) }

            assertEquals(302, answer.status)
            assertEquals(listOf("/after"), answer.headerValues("Location"))
            assertEquals("moved", answer.bodyText)
            assertEquals(listOf("/before"), upstream.requests.map { it.target })
        }
    }

    @Test
    fun aRealServiceThatCannotBeReachedIsAFailure() {
        val refused =
            assertFailsWith<UpstreamException> { JdkUpstreamClient().use { it.send("GET", "http://127.0.0.1:1/orders?token=secret") } }
        val unknown =
            assertFailsWith<UpstreamException> { JdkUpstreamClient().use { it.send("GET", "http://unknown-host.invalid/orders") } }

        assertEquals("The real service at http://127.0.0.1:1/orders gave no complete answer: no connection could be made.", refused.message)
        assertContains(unknown.message!!, "The real service at http://unknown-host.invalid/orders gave no complete answer")
    }

    @Test
    fun anAnswerThatBreaksOffOrDoesNotComeIsAFailure() {
        val cut = StubResponse(200, listOf(text), "cut short".toByteArray(), Framing.CUT_SHORT)
        StubUpstream(respond = { if (it.target == "/cut") cut else null }).use { upstream ->
            JdkUpstreamClient().use { client ->
                val brokenOff = assertFailsWith<UpstreamException> { client.send("GET", "${upstream.origin}/cut") }
                val none = assertFailsWith<UpstreamException> { client.send("GET", "${upstream.origin}/none") }

                assertContains(brokenOff.message!!, "The real service at ${upstream.origin}/cut gave no complete answer")
                assertContains(none.message!!, "The real service at ${upstream.origin}/none gave no complete answer")
            }
        }
    }

    @Test
    fun aUrlThatCannotBeAskedIsAFailure() {
        JdkUpstreamClient().use { client ->
            listOf(
                "http:///orders",
                "http://my_host/orders",
                "ftp://files.example.com/orders",
                "orders",
                "http://[::1/orders",
                "",
            ).forEach { url ->
                val failure = assertFailsWith<UpstreamException>(url) { client.send("GET", url) }

                assertContains(failure.message!!, "cannot be asked", message = url)
            }
        }
    }

    @Test
    fun aMethodThatCannotBeSentIsAFailure() {
        StubUpstream().use { upstream ->
            JdkUpstreamClient().use { client ->
                assertFailsWith<UpstreamException> { client.send("BAD METHOD", "${upstream.origin}/orders") }
                assertEquals(0, upstream.requests.size)
            }
        }
    }

    @Test
    fun cancelingARequestEndsTheExchange() {
        StubUpstream(respond = {
            Thread.sleep(SLOW_MILLIS)
            StubResponse(200, "late", text)
        }).use { upstream ->
            JdkUpstreamClient().use { client ->
                val start = System.nanoTime()

                val outcome =
                    runBlocking(Dispatchers.Default) {
                        val pending = async { client.send(UpstreamRequest("GET", "${upstream.origin}/slow")) }
                        while (upstream.requests.isEmpty()) Thread.sleep(POLL_MILLIS)
                        pending.cancelAndJoin()
                        runCatching { pending.await() }.exceptionOrNull()
                    }

                assertTrue(outcome is CancellationException)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < SLOW_MILLIS)
            }
        }
    }

    @Test
    fun closingTheClientEndsARequestThatIsUnderWay() {
        StubUpstream(respond = {
            Thread.sleep(SLOW_MILLIS)
            StubResponse(200, "late", text)
        }).use { upstream ->
            val client = JdkUpstreamClient()
            val start = System.nanoTime()

            val outcome =
                runBlocking(Dispatchers.Default) {
                    val pending = async { runCatching { client.send(UpstreamRequest("GET", "${upstream.origin}/slow")) } }
                    while (upstream.requests.isEmpty()) Thread.sleep(POLL_MILLIS)
                    client.close()
                    pending.await().exceptionOrNull()
                }

            assertIs<UpstreamException>(outcome)
            assertEquals(
                "The real service at ${upstream.origin}/slow gave no complete answer: the upstream client was closed.",
                outcome.message,
            )
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < SLOW_MILLIS)
        }
    }

    @Test
    fun aFutureThatSomeoneElseCancelsIsAFailureAndNoCancellationOfTheCaller() {
        val future = CompletableFuture<String>()

        val outcome =
            runBlocking(Dispatchers.Default) {
                val waiting = async { runCatching { future.awaitOrCancel() } }
                future.cancel(true)
                waiting.await().exceptionOrNull()
            }

        assertIs<IOException>(outcome)
        assertEquals("the exchange was ended from outside", outcome.message)
    }

    @Test
    fun aFutureGivesItsValueOrItsFailureToTheOneWhoWaits() {
        val done = CompletableFuture.completedFuture("answer")
        val failed = CompletableFuture.supplyAsync<String> { throw IOException("broke off") }

        val value = runBlocking { done.awaitOrCancel() }
        val failure = assertFailsWith<IOException> { runBlocking { failed.awaitOrCancel() } }

        assertEquals("answer", value)
        assertEquals("broke off", failure.message)
    }

    @Test
    fun aClosedClientSendsNoRequest() {
        StubUpstream().use { upstream ->
            val client = JdkUpstreamClient()
            client.send("GET", "${upstream.origin}/before")

            client.close()

            assertFailsWith<Exception> { client.send("GET", "${upstream.origin}/after") }
            assertEquals(listOf("/before"), upstream.requests.map { it.target })
        }
    }

    @Test
    fun aClientThatNeverSentARequestCloses() {
        JdkUpstreamClient().close()
    }

    /** A recording of the parity group `proxy-forwarding`: the request of the client, and what its stated new response says arrived. */
    private class Forwarded(
        val name: String,
        val method: String,
        val target: String,
        val sent: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        val arrived: List<String> = emptyList(),
    )

    /** The request headers that the stub upstream of the parity harness reports, next to those whose name starts with `x-`. */
    private val reported =
        setOf(
            "accept",
            "accept-encoding",
            "authorization",
            "content-type",
            "cookie",
            "host",
            "if-modified-since",
            "if-none-match",
            "keep-alive",
            "proxy-authorization",
            "te",
            "trailer",
            "user-agent",
        )

    /**
     * The requests of the recordings of the parity group `proxy-forwarding` whose target is an absolute URL. Each
     * goes the way of a request in proxy mode: the request of the client, with the headers that the harness adds,
     * is forwarded to the stub. What arrives is what the stated new response of the recording reports: the method
     * of the client (DEV-10), its body byte for byte with no header added (DEV-11), its query string as written
     * (DEV-38), and its headers without those that are not forwarded (DEV-39, DEV-40).
     */
    @Test
    fun theRequestsOfTheForwardingRecordingsArriveAsTheRecordingsState() {
        val plain = listOf(text)
        val plainArrived = listOf("content-type: text/plain")
        val conditional =
            listOf(
                "Accept-Encoding" to "gzip",
                "If-Modified-Since" to "Mon, 23 Nov 2026 00:00:00 GMT",
                "If-None-Match" to "\"v1\"",
            )
        val application = listOf("Accept" to "application/json", "Authorization" to "Bearer t0ken", "X-Request-Id" to "7")
        val form = "application/x-www-form-urlencoded"
        val recordings =
            listOf(
                Forwarded("proxy-method-get", "GET", "/stub/echo"),
                Forwarded("proxy-method-post", "POST", "/stub/echo", plain, "posted".toByteArray(), plainArrived),
                Forwarded("proxy-method-put", "PUT", "/stub/echo", plain, "put body".toByteArray(), plainArrived),
                Forwarded("proxy-method-patch", "PATCH", "/stub/echo", plain, "patch body".toByteArray(), plainArrived),
                Forwarded("proxy-method-delete", "DELETE", "/stub/echo"),
                Forwarded("proxy-method-head", "HEAD", "/stub/echo"),
                Forwarded("proxy-method-options", "OPTIONS", "/stub/echo"),
                Forwarded("proxy-method-trace", "TRACE", "/stub/echo"),
                Forwarded("proxy-query-forwarded", "GET", "/stub/echo?page=2"),
                Forwarded("proxy-query-order-and-encoding", "GET", "/stub/echo?b=2&a=1&name=jane%20doe"),
                Forwarded("proxy-query-repeated-parameter", "GET", "/stub/echo?id=1&id=2"),
                Forwarded("proxy-query-empty-value", "GET", "/stub/echo?flag=&x=1"),
                Forwarded(
                    "proxy-headers-application",
                    "GET",
                    "/stub/echo",
                    application,
                    arrived = listOf("accept: application/json", "authorization: Bearer t0ken", "x-request-id: 7"),
                ),
                Forwarded("proxy-headers-conditional-and-encoding", "GET", "/stub/echo", conditional),
                Forwarded("proxy-headers-client-cookie", "GET", "/stub/echo", listOf("Cookie" to "local=1")),
                Forwarded(
                    "proxy-headers-hop-by-hop",
                    "GET",
                    "/stub/echo",
                    listOf("Keep-Alive" to "timeout=5", "TE" to "trailers", "Trailer" to "X-Checksum"),
                ),
                Forwarded("proxy-body-text", "POST", "/stub/echo", plain, "plain text body".toByteArray(), plainArrived),
                Forwarded(
                    "proxy-body-utf8",
                    "POST",
                    "/stub/echo",
                    listOf("Content-Type" to "text/plain; charset=utf-8"),
                    "caf\u00e9 \u20ac".toByteArray(),
                    listOf("content-type: text/plain; charset=utf-8"),
                ),
                Forwarded(
                    "proxy-body-binary",
                    "POST",
                    "/stub/echo",
                    listOf("Content-Type" to "application/octet-stream"),
                    allBytes,
                    listOf("content-type: application/octet-stream"),
                ),
                Forwarded(
                    "proxy-body-form",
                    "POST",
                    "/stub/echo",
                    listOf("Content-Type" to form),
                    "a=1&b=two+words".toByteArray(),
                    listOf("content-type: $form"),
                ),
                Forwarded("proxy-body-empty-post", "POST", "/stub/echo"),
            )

        StubUpstream().use { upstream ->
            JdkUpstreamClient().use { client ->
                recordings.forEach { recording ->
                    val harness = listOf(Header("Host", "127.0.0.1:8080"), Header("User-Agent", "mockey-parity"))
                    val length =
                        listOfNotNull(
                            recording.body.size
                                .takeIf { it > 0 }
                                ?.let { Header("Content-Length", it.toString()) },
                        )
                    val sent = harness + recording.sent.map { (name, value) -> Header(name, value) } + length
                    val url = "${upstream.origin}${recording.target}"
                    val path = "http://127.0.0.1:8080/service/${url.substringBefore('?')}"
                    val query = recording.target.substringAfter('?', missingDelimiterValue = "").ifEmpty { null }
                    val request = MockRequest(recording.method, path, query, sent, recording.body)

                    runBlocking { client.send(upstreamRequest(request, url)) }

                    val arrived = upstream.requests.last()
                    val headers =
                        arrived.headers
                            .map { (name, value) -> name.lowercase() to value }
                            .filter { (name, _) -> name in reported || name.startsWith("x-") }
                            .sortedBy { (name, _) -> name }
                            .map { (name, value) -> "$name: $value" }
                    val expected = (recording.arrived + "host: 127.0.0.1:${upstream.port}" + "user-agent: mockey-parity").sorted()
                    assertEquals(recording.method to recording.target, arrived.method to arrived.target, recording.name)
                    assertEquals(expected, headers, recording.name)
                    assertContentEquals(recording.body, arrived.body, recording.name)
                }
            }
        }
    }

    /**
     * The routes of the stub upstream of the parity harness that the group `proxy-relay` asks for, and the stated
     * new responses of its recordings. Every value of a repeated header is relayed (DEV-41).
     */
    @Test
    fun theAnswersOfTheRelayRecordingsAreRelayedAsTheRecordingsState() {
        val latin1 = "caf\u00e9".toByteArray(Charsets.ISO_8859_1)
        val utf8 = "caf\u00e9".toByteArray(Charsets.UTF_8)
        val withHeaders =
            listOf(
                text,
                "X-Upstream" to "one",
                "X-Multi" to "a",
                "X-Multi" to "b",
                "Cache-Control" to "max-age=60",
                "Location" to "/created/9",
            )
        val routes =
            mapOf(
                "/stub/status/404" to StubResponse(404, "status 404", text),
                "/stub/status/500" to StubResponse(500, "status 500", text),
                "/stub/headers" to StubResponse(201, withHeaders, "with headers".toByteArray()),
                "/stub/binary" to StubResponse(200, listOf("Content-Type" to "application/octet-stream"), allBytes),
                "/stub/text/latin1" to StubResponse(200, listOf("Content-Type" to "text/plain; charset=ISO-8859-1"), latin1),
                "/stub/text/undeclared" to StubResponse(200, listOf(text), utf8),
            )
        val stated =
            mapOf(
                "/stub/status/404" to Triple(404, listOf("content-type: text/plain"), "status 404".toByteArray()),
                "/stub/status/500" to Triple(500, listOf("content-type: text/plain"), "status 500".toByteArray()),
                "/stub/headers" to
                    Triple(
                        201,
                        listOf(
                            "cache-control: max-age=60",
                            "content-type: text/plain",
                            "location: /created/9",
                            "x-multi: a",
                            "x-multi: b",
                            "x-upstream: one",
                        ),
                        "with headers".toByteArray(),
                    ),
                "/stub/binary" to Triple(200, listOf("content-type: application/octet-stream"), allBytes),
                "/stub/text/latin1" to Triple(200, listOf("content-type: text/plain; charset=ISO-8859-1"), latin1),
                "/stub/text/undeclared" to Triple(200, listOf("content-type: text/plain"), utf8),
            )

        StubUpstream(respond = { routes.getValue(it.target) }).use { upstream ->
            JdkUpstreamClient().use { client ->
                stated.forEach { (route, expected) ->
                    val (status, headers, body) = expected

                    val relayed = relayed(client.send("GET", "${upstream.origin}$route"), "GET")

                    val withoutLength = relayed.headers.filterNot { it.name.equals("Content-Length", ignoreCase = true) }
                    assertEquals(status, relayed.status, route)
                    assertEquals(headers, withoutLength.map { "${it.name.lowercase()}: ${it.value}" }.sorted(), route)
                    assertContentEquals(body, relayed.body, route)
                    assertEquals(listOf(body.size.toString()), relayed.headerValues("Content-Length"), route)
                }
            }
        }
        assertFalse(routes.isEmpty())
        assertNull(routes["/stub/echo"])
    }

    private companion object {
        const val SLOW_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
