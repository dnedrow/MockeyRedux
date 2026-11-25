package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ResponseMode
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The responses of the mock endpoint over real HTTP: what a client gets of the response that the engine
 * gives, of a scenario and of a real service, and the default headers that every response carries.
 */
class MockResponseTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05.678Z"), ZoneOffset.UTC)
    private val module = MockeyServer.mockListenerModule(MockEndpoint(engine, MEBIBYTE, clock), StatusEndpoint(clock.instant()))
    private val listener = Listener.start(ListenerKind.MOCK, RawHttp.loopback(0), module)
    private val upstreams = mutableListOf<StubUpstream>()

    @AfterTest
    fun stopEverything() {
        listener.stop()
        engine.close()
        upstreams.forEach(StubUpstream::close)
    }

    /** Defines a service at [mockUrl] that answers every request with a scenario of [status], [headers], and [body]. */
    private fun scenario(
        mockUrl: String,
        status: Int = 200,
        headers: List<Header> = emptyList(),
        body: String = "",
    ) = runBlocking {
        val service = engine.services.create { it.copy(name = mockUrl, mockUrl = mockUrl, responseMode = ResponseMode.STATIC) }.getOrThrow()
        engine.scenarios
            .create(
                service.id,
            ) { it.copy(name = "answer", statusCode = status, headers = headers, responseBody = body) }
            .getOrThrow()
    }

    private fun upstream(
        status: String = "200 OK",
        headers: List<String> = listOf("Content-Type: text/plain"),
        body: String = "answered by the real service",
    ): StubUpstream = StubUpstream(status, headers, body).also { upstreams += it }

    private fun send(
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer = RawHttp.exchange(listener.address, RawHttp.request(method, target, body = body))

    /** Returns [block]'s result and what it wrote to the log, which goes to the standard output. */
    private fun <T> withLog(block: () -> T): Pair<T, String> {
        val before = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setOut(before)
        }
    }

    /** What ends the headers of every response to a request that asks to close the connection: [length] is the length of the body. */
    private fun framing(length: Int) = listOf("Content-Length: $length", "Connection: close")

    // The response of the engine

    @Test
    fun aClientGetsTheStatusTheHeadersAndTheBodyOfAScenario() {
        val headers =
            listOf(
                Header("Content-Type", "text/plain; charset=iso-8859-1"),
                Header("X-Tier", "gold"),
                Header("Set-Cookie", "a=1"),
                Header("Set-Cookie", "b=2; Path=/"),
                Header("x-tier", "silver"),
            )
        scenario("orders", status = 201, headers = headers, body = "caf\u00e9 au lait")

        val answer = send("POST", "/service/orders")

        assertEquals("HTTP/1.1 201 Created", answer.statusLine)
        assertEquals("caf\u00e9 au lait", answer.body)
        assertEquals(headers.map { "${it.name}: ${it.value}" } + DEFAULTS + framing(12), answer.headers)
    }

    @Test
    fun aScenarioSendsAnyHeaderThatHttpAllows() {
        val headers =
            listOf(
                Header("Upgrade", "HTTP/3.0"),
                Header("Date", "Tue, 03 Jul 2001 06:00:00 GMT"),
                Header("Server", "mocked"),
                Header("Via", "1.1 mocked"),
                Header("Connection", "Upgrade"),
            )
        scenario("upgrade", status = 426, headers = headers, body = "upgrade")

        val answer = send("GET", "/service/upgrade")

        assertEquals("HTTP/1.1 426 Upgrade Required" to "upgrade", answer.statusLine to answer.body)
        assertEquals(headers.map { "${it.name}: ${it.value}" } + DEFAULTS + "Content-Length: 7", answer.headers)
    }

    /** The names of the headers of a real service are in small letters, because the upstream client of the engine reads them so. */
    @Test
    fun aClientGetsTheStatusTheHeadersAndTheBodyOfARealServiceByteForByte() {
        val everyByte = String(ByteArray(256) { it.toByte() }, StandardCharsets.ISO_8859_1)
        val real =
            upstream("203 Non-Authoritative Information", listOf("Content-Type: application/octet-stream", "X-Served-By: real"), everyByte)

        val answer = send("GET", "/service/${real.origin}/files/1")

        assertEquals("HTTP/1.1 203 Non-Authoritative Information", answer.statusLine)
        assertEquals(everyByte, answer.body)
        assertEquals(listOf("content-type: application/octet-stream", "x-served-by: real") + DEFAULTS + framing(256), answer.headers)
    }

    @Test
    fun theServerStatesTheLengthOfTheBodyWhateverAScenarioSays() {
        val headers = listOf(Header("Content-Length", "3"), Header("Transfer-Encoding", "chunked"), Header("X-Kept", "yes"))
        scenario("stated", headers = headers, body = "hello")
        scenario("empty", headers = listOf(Header("Content-Length", "99")))

        val stated = send("GET", "/service/stated")
        val statedToHead = send("HEAD", "/service/stated")
        val empty = send("GET", "/service/empty")

        assertEquals("hello", stated.body)
        assertEquals(listOf("X-Kept: yes") + DEFAULTS + framing(5), stated.headers)
        assertEquals("" to stated.headers, statedToHead.body to statedToHead.headers)
        assertEquals("", empty.body)
        assertEquals(DEFAULTS + framing(0), empty.headers)
    }

    @Test
    fun theAnswerToAHeadRequestEndsWithItsHeadersAndStatesTheLengthOfTheBody() {
        scenario("orders", headers = listOf(Header("Content-Type", "text/plain")), body = "hello")

        val toHead = send("HEAD", "/service/orders")
        val toGet = send("GET", "/service/orders")

        assertEquals("HTTP/1.1 200 OK" to "", toHead.statusLine to toHead.body)
        assertEquals("hello", toGet.body)
        assertEquals(listOf("Content-Type: text/plain") + DEFAULTS + framing(5), toHead.headers)
        assertEquals(toGet.headers, toHead.headers)
    }

    @Test
    fun theAnswerOfARealServiceToAHeadRequestStatesTheLengthThatTheRealServiceStated() {
        val real = upstream(body = "a body of 21 bytes...")

        val answer = send("HEAD", "/service/${real.origin}/files/1")

        assertEquals("HEAD", real.requests.single().method)
        assertEquals("HTTP/1.1 200 OK" to "", answer.statusLine to answer.body)
        assertEquals(listOf("21"), answer.headerValues("Content-Length"))
    }

    @Test
    fun aResponseWhoseStatusAllowsNoBodyEndsWithItsHeaders() {
        scenario("no-content", status = 204, headers = listOf(Header("X-Kept", "yes")), body = "not sent")
        scenario("not-modified", status = 304, headers = listOf(Header("ETag", "\"v1\"")), body = "not sent")

        val noContent = send("GET", "/service/no-content")
        val notModified = send("GET", "/service/not-modified")

        assertEquals("HTTP/1.1 204 No Content" to "", noContent.statusLine to noContent.body)
        assertEquals(listOf("X-Kept: yes") + DEFAULTS + "Connection: close", noContent.headers)
        assertEquals("HTTP/1.1 304 Not Modified" to "", notModified.statusLine to notModified.body)
        assertEquals(listOf("ETag: \"v1\"") + DEFAULTS + framing(8), notModified.headers)
    }

    @Test
    fun anyStatusOfThreeDigitsIsSent() {
        val statuses = listOf(200, 299, 302, 404, 418, 500, 599, 600, 999)
        statuses.forEach { scenario("status/$it", status = it, body = "body of $it") }

        val answers = statuses.map { send("GET", "/service/status/$it") }

        assertEquals(statuses, answers.map { it.status })
        assertEquals(statuses.map { "body of $it" }, answers.map { it.body })
    }

    @Test
    fun aStatusThatIsNoStatusOfHttpGetsTheStatus500WithAnExplanation() {
        val statuses = listOf(0, 42, 99, 1000, -1)
        statuses.forEach { scenario("status/$it", status = it, body = "not sent") }

        val (answers, log) = withLog { statuses.map { send("GET", "/service/status/$it") } }

        assertEquals(List(statuses.size) { "HTTP/1.1 500 Internal Server Error" }, answers.map { it.statusLine })
        assertEquals(statuses.map { "The response has the status $it, which is no status of HTTP." }, answers.map { it.body })
        assertContains(log, " WARN  [")
        assertContains(log, "MockEndpoint - A response with the status 42 was not sent, because that is no status of HTTP.")
    }

    @Test
    fun aHeaderThatHttpDoesNotAllowIsLeftOutAndTheLogNamesItWithoutItsValue() {
        val headers =
            listOf(
                Header("X-Before", "1"),
                Header("Bad Name", "secret-7c2e"),
                Header("X-Line", "one\r\nX-Injected: secret-7c2e"),
                Header("X-After", "2"),
            )
        scenario("headers", headers = headers, body = "sent")

        val (answer, log) = withLog { send("GET", "/service/headers") }

        assertEquals("HTTP/1.1 200 OK" to "sent", answer.statusLine to answer.body)
        assertEquals(listOf("X-Before: 1", "X-After: 2") + DEFAULTS + framing(4), answer.headers)
        assertContains(log, "MockEndpoint - The response header Bad Name was not sent, because HTTP does not allow its name or its value.")
        assertContains(log, "MockEndpoint - The response header X-Line was not sent, because HTTP does not allow its name or its value.")
        assertFalse("secret-7c2e" in log, log)
    }

    // The default headers

    /** The scenario "Defaults are added". */
    @Test
    fun aResponseOfAScenarioWithoutHeadersCarriesTheFiveDefaultHeaders() {
        scenario("plain", body = "hello")

        val answer = send("GET", "/service/plain")

        assertEquals(
            listOf(
                "Access-Control-Allow-Origin: *",
                "Cache-Control: no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0",
                "Pragma: no-cache",
                "Expires: Tue, 03 Jul 2001 06:00:00 GMT",
                "Last-Modified: Fri, 02 Jan 2026 03:04:05 GMT",
            ) + framing(5),
            answer.headers,
        )
    }

    /** The scenario "A scenario header replaces its default". */
    @Test
    fun aHeaderOfAScenarioTakesThePlaceOfItsDefault() {
        scenario("cached", headers = listOf(Header("Cache-Control", "max-age=60")), body = "hello")

        val answer = send("GET", "/service/cached")

        assertEquals(listOf("max-age=60"), answer.headerValues("Cache-Control"))
        assertEquals(
            listOf("Cache-Control: max-age=60") + DEFAULTS.filterNot { it.startsWith("Cache-Control") } + framing(5),
            answer.headers,
        )
    }

    @Test
    fun eachDefaultHeaderGivesWayToAHeaderOfItsNameInAnyLetterCase() {
        val own =
            listOf(
                Header("access-control-allow-origin", "https://app.example"),
                Header("CACHE-CONTROL", "max-age=60"),
                Header("pragma", "x"),
                Header("Expires", "0"),
                Header("last-modified", "Mon, 01 Jan 2024 00:00:00 GMT"),
            )
        own.forEachIndexed { index, header -> scenario("own/$index", headers = listOf(header), body = "x") }
        scenario("own/all", headers = own, body = "x")
        scenario("own/twice", headers = listOf(Header("Cache-Control", "max-age=60"), Header("cache-control", "private")), body = "x")

        val single = own.indices.map { send("GET", "/service/own/$it") }
        val all = send("GET", "/service/own/all")
        val twice = send("GET", "/service/own/twice")

        own.forEachIndexed { index, header ->
            val others = DEFAULTS.filterNot { it.startsWith(header.name, ignoreCase = true) }
            assertEquals(listOf("${header.name}: ${header.value}") + others + framing(1), single[index].headers, header.name)
            assertEquals(4, others.size)
        }
        assertEquals(own.map { "${it.name}: ${it.value}" } + framing(1), all.headers)
        assertEquals(listOf("max-age=60", "private"), twice.headerValues("Cache-Control"))
        assertEquals(6, twice.headers.size - framing(1).size)
    }

    @Test
    fun aHeaderOfARealServiceTakesThePlaceOfItsDefault() {
        val real = upstream(headers = listOf("Cache-Control: public, max-age=3600", "Last-Modified: Mon, 01 Jan 2024 00:00:00 GMT"))

        val answer = send("GET", "/service/${real.origin}/files/1")

        val others = DEFAULTS.filterNot { it.startsWith("Cache-Control") || it.startsWith("Last-Modified") }
        val own = listOf("cache-control: public, max-age=3600", "last-modified: Mon, 01 Jan 2024 00:00:00 GMT")
        assertEquals(own + others + framing(28), answer.headers)
    }

    @Test
    fun everyResponseOfTheMockEndpointCarriesTheDefaultHeaders() {
        scenario("no-content", status = 204)
        scenario("orders", body = "hello")
        val tooLong = "x".repeat(MEBIBYTE.toInt() + 1)

        val answers =
            mapOf(
                "a request that no service takes" to send("GET", "/service/no/such/service"),
                "a HEAD request" to send("HEAD", "/service/orders"),
                "a response without a body" to send("GET", "/service/no-content"),
                "a body that is too long" to send("POST", "/service/orders", tooLong),
            )

        assertEquals(listOf(404, 200, 204, 413), answers.values.map { it.status })
        for ((request, answer) in answers) {
            assertEquals(
                DEFAULTS,
                answer.headers.filter { header ->
                    DEFAULTS.any { it.substringBefore(':') == header.substringBefore(':') }
                },
                request,
            )
        }
    }

    @Test
    fun aResponseThatTheMockEndpointGivesOnItsOwnIsPlainText() {
        val tooLong = send("POST", "/service/orders", "x".repeat(MEBIBYTE.toInt() + 1))

        assertEquals("HTTP/1.1 413 Payload Too Large", tooLong.statusLine)
        assertEquals(listOf("Content-Type: text/plain; charset=utf-8") + DEFAULTS + framing(95), tooLong.headers)
        assertEquals("The body of the request is longer than 1048576 bytes, which is the most that this server takes.", tooLong.body)
    }

    @Test
    fun whatIsNoMockTrafficCarriesNoDefaultHeader() {
        val answer = send("GET", "/home")

        assertEquals("HTTP/1.1 404 Not Found", answer.statusLine)
        assertEquals(framing(0), answer.headers)
    }

    private companion object {
        const val MEBIBYTE = 1_048_576L

        /** The default headers of mock responses, as the endpoint of this test writes them, whose clock stands still. */
        val DEFAULTS =
            listOf(
                "Access-Control-Allow-Origin: *",
                "Cache-Control: no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0",
                "Pragma: no-cache",
                "Expires: Tue, 03 Jul 2001 06:00:00 GMT",
                "Last-Modified: Fri, 02 Jan 2026 03:04:05 GMT",
            )
    }
}
