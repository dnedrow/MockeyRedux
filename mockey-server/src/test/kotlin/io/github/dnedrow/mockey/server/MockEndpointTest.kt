package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ResponseMode
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mock endpoint over real HTTP: what the engine gets of a request that a client sends to the mock
 * listener, which requests are mock traffic, and what happens to a body that is too long.
 *
 * A test writes its request to a socket, so that the request target and the headers are on the connection
 * exactly as the test says. What the engine got of the request is in the history entry of the request.
 */
class MockEndpointTest {
    private val upstream = StubUpstream()
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val listeners = mutableListOf<Listener>()

    init {
        static("Customers", "customer/{id}", """{"id":1}""")
        static("Users of the real service", "http://api.example.com/users", "users")
        static("A name with a space", "files/a b", "space")
        static("A name with a slash", "files/x/y", "slash")
        static("A name with a percent sign", "files/100%25", "percent")
    }

    @AfterTest
    fun stopEverything() {
        listeners.forEach(Listener::stop)
        engine.close()
        upstream.close()
    }

    /** Defines a service at [mockUrl] that answers every request with [body]. */
    private fun static(
        name: String,
        mockUrl: String,
        body: String,
    ) = runBlocking {
        val service = engine.services.create { it.copy(name = name, mockUrl = mockUrl, responseMode = ResponseMode.STATIC) }.getOrThrow()
        engine.scenarios.create(service.id) { it.copy(name = "answer", responseBody = body) }.getOrThrow()
    }

    /** The mock endpoint of [engine] on a listener, which takes bodies of [maxRequestBody] bytes at most. */
    private fun endpoint(maxRequestBody: Long = MEBIBYTE): Listener =
        Listener.start(ListenerKind.MOCK, RawHttp.loopback(0), MockEndpoint(engine, maxRequestBody).module).also { listeners += it }

    private val listener by lazy { endpoint() }

    private fun send(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        body: String = "",
    ): RawHttp.Answer = RawHttp.exchange(listener.address, RawHttp.request(method, target, headers, body))

    /** The history entries, the oldest first. */
    private fun history(): List<HistoryEntry> = engine.history.entries().reversed()

    /** The history entry of the one request that the engine has handled. */
    private fun handled(): HistoryEntry = history().single()

    // The request target

    @Test
    fun aRequestForARelativeTargetReachesItsService() {
        val answer = send("GET", "/service/customer/7")

        val entry = handled()
        assertEquals("HTTP/1.1 200 OK" to """{"id":1}""", answer.statusLine to answer.body)
        assertEquals("customer/7" to "Customers", entry.target to entry.service?.name)
        assertEquals("http://mockey.test/service/customer/7", entry.request.url)
        assertNull(entry.request.query)
    }

    @Test
    fun anAbsoluteTargetKeepsTheTwoSlashesAfterItsScheme() {
        val answer = send("GET", "/service/http://api.example.com/users?page=2")

        val entry = handled()
        assertEquals("HTTP/1.1 200 OK" to "users", answer.statusLine to answer.body)
        assertEquals("http://api.example.com/users", entry.target)
        assertEquals("http://mockey.test/service/http://api.example.com/users", entry.request.url)
        assertEquals("page=2", entry.request.query)
    }

    @Test
    fun everySlashOfATargetReachesTheEngine() {
        val targets = listOf("/service//customer/7", "/service/customer//7", "/service/customer/7//", "/service///")

        val answers = targets.map { send("GET", it) }

        assertEquals(targets.map { "http://mockey.test$it" }, history().map { it.request.url })
        assertEquals(targets.map { it.removePrefix("/service/") }, history().map { it.target })
        assertEquals(listOf(200, 404, 404, 404), answers.map { it.status })
    }

    @Test
    fun encodedCharactersOfATargetReachTheEngineAsTheClientWroteThem() {
        val targets =
            listOf("/service/files/a%20b", "/service/files/x%2Fy", "/service/files/x%2fy", "/service/files/100%25", "/service/customer/%37")

        val answers = targets.map { send("GET", it) }

        assertEquals(listOf("space", "slash", "slash", "percent", """{"id":1}"""), answers.map { it.body })
        assertEquals(targets.map { "http://mockey.test$it" }, history().map { it.request.url })
        assertEquals(targets.map { it.removePrefix("/service/") }, history().map { it.target })
    }

    @Test
    fun aTargetWithAnEscapeThatIsNoneReachesTheEngine() {
        val targets = listOf("/service/files/a%zzb", "/service/files/50%", "/service/files/%4")

        val answers = targets.map { send("GET", it) }

        assertEquals(targets.map { it.removePrefix("/service/") }, history().map { it.target })
        assertEquals(listOf(404, 404, 404), answers.map { it.status })
    }

    @Test
    fun charactersThatAUrlDoesNotAllowReachTheEngineInATarget() {
        val targets = listOf("/service/customer/{id}", "/service/a|b^c`d", """/service/a\b""", "/service/a\"b<c>d", "/service/caf\u00e9")

        targets.forEach { send("GET", it) }

        assertEquals(targets.map { "http://mockey.test$it" }, history().map { it.request.url })
    }

    @Test
    fun theQueryStringReachesTheEngineAsTheClientWroteIt() {
        val queries = listOf("a=1&b=%20x&c", "", "a=b?c=d", "x=%2F&y=a+b&z=%zz", "next=http://example.org/?q=1")

        queries.forEach { send("GET", "/service/customer/7?$it") }

        assertEquals(queries, history().map { it.request.query })
        assertEquals(List(queries.size) { "http://mockey.test/service/customer/7" }, history().map { it.request.url })
        assertEquals(
            listOf("a" to "1", "b" to " x", "c" to ""),
            history()
                .first()
                .request.parameters
                .map { it.name to it.value },
        )
    }

    @Test
    fun aRequestTargetWithASchemeAndAHostIsTheUrlThatTheClientAddressed() {
        val answer = send("GET", "http://Mockey.Example:8080/service/customer/7?x=1")
        val elsewhere = send("GET", "http://api.example.com/users")

        val entry = handled()
        assertEquals(200 to """{"id":1}""", answer.status to answer.body)
        assertEquals("http://Mockey.Example:8080/service/customer/7" to "x=1", entry.request.url to entry.request.query)
        assertEquals(404 to "", elsewhere.status to elsewhere.body)
    }

    // The host

    @Test
    fun theUrlOfARequestNamesTheHostOfItsHostHeader() {
        val head = listOf("GET /service/customer/7 HTTP/1.1", "Host: Mockey.Example:8080", "Connection: close", "", "")

        RawHttp.exchange(listener.address, head.joinToString(RawHttp.CRLF).toByteArray())

        assertEquals("http://Mockey.Example:8080/service/customer/7", handled().request.url)
    }

    @Test
    fun aRequestOfHttp10WithoutAHostHeaderIsAddressedToTheListener() {
        val head = listOf("GET /service/customer/7 HTTP/1.0", "", "")

        val answer = RawHttp.exchange(listener.address, head.joinToString(RawHttp.CRLF).toByteArray())

        assertEquals(200, answer.status)
        assertEquals("http://127.0.0.1:${listener.address.port}/service/customer/7", handled().request.url)
    }

    /** Answers the request line [line] with [headers], and returns the status line and the body of the answer. */
    private fun answerTo(
        line: String,
        headers: List<String>,
    ): Pair<String, String> {
        val head = listOf(line) + headers + listOf("Connection: close", "", "")
        val answer = RawHttp.exchange(listener.address, head.joinToString(RawHttp.CRLF).toByteArray())
        return answer.statusLine to answer.body
    }

    @Test
    fun aRequestOfHttp11ThatNamesNoHostIsRefused() {
        val refused = "HTTP/1.1 400 Bad Request" to "The request names no host in a Host header."

        for (headers in listOf(emptyList(), listOf("Host:"), listOf("Host:   "))) {
            assertEquals(refused, answerTo("GET /service/customer/7 HTTP/1.1", headers), "$headers")
            assertEquals(refused, answerTo("GET http://mockey.test/service/customer/7 HTTP/1.1", headers), "$headers")
        }
        assertEquals(emptyList(), history())
    }

    @Test
    fun aHostHeaderThatHoldsWhatNoNameOfAHostHoldsIsRefused() {
        val explanation = "The request names no host in a Host header."

        for (host in listOf("evil.example/service/x", "a?b", "a#b", "user@host", "a b", """a\b""")) {
            assertEquals(
                "HTTP/1.1 400 Bad Request" to explanation,
                answerTo("GET /service/customer/7 HTTP/1.1", listOf("Host: $host")),
                host,
            )
            assertEquals(
                "HTTP/1.0 400 Bad Request" to explanation,
                answerTo("GET /service/customer/7 HTTP/1.0", listOf("Host: $host")),
                host,
            )
        }
        assertEquals(emptyList(), history())
    }

    @Test
    fun aHostCanBeANameOrAnAddressWithOrWithoutAPort() {
        val hosts = listOf("mockey.test", "MOCKEY.test:8080", "192.0.2.7:80", "[2001:db8::1]:8080", "[::1]", "local_host-1")

        val answers = hosts.map { answerTo("GET /service/customer/7 HTTP/1.1", listOf("Host: $it")) }

        assertEquals(List(hosts.size) { "HTTP/1.1 200 OK" to """{"id":1}""" }, answers)
        assertEquals(hosts.map { "http://$it/service/customer/7" }, history().map { it.request.url })
    }

    // The method, the headers, the body, and the client

    @Test
    fun aRequestWithAnyMethodReachesTheEngine() {
        val methods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "HEAD", "PURGE", "get", "M-SEARCH")

        val answers = methods.map { send(it, "/service/customer/7") }

        assertEquals(methods, history().map { it.request.method })
        assertEquals(List(methods.size) { 200 }, answers.map { it.status })
        assertEquals(methods.map { if (it == "HEAD") "" else """{"id":1}""" }, answers.map { it.body })
    }

    /**
     * The names keep the order in which the client sent them. The values of a name that the client sent more
     * than once stand together, under the first spelling of the name, each of them once.
     */
    @Test
    fun theHeadersOfARequestReachTheEngineWithEveryValueOnce() {
        val headers =
            listOf("X-One: 1", "x-two: a", "Accept: text/plain", "X-Two: b", "Cookie: session=abc; theme=dark", "X-Empty:", "X-TWO: c")

        send("GET", "/service/customer/7", headers)

        val expected =
            listOf(
                Header("Host", "mockey.test"),
                Header("Connection", "close"),
                Header("X-One", "1"),
                Header("x-two", "a"),
                Header("x-two", "b"),
                Header("x-two", "c"),
                Header("Accept", "text/plain"),
                Header("Cookie", "session=abc; theme=dark"),
                Header("X-Empty", ""),
            )
        assertEquals(expected, handled().request.headers)
        assertEquals(listOf("session" to "abc", "theme" to "dark"), handled().request.cookies.map { it.name to it.value })
    }

    @Test
    fun theBodyOfARequestReachesTheEngineByteForByte() {
        val everyByte = String(ByteArray(256) { it.toByte() }, StandardCharsets.ISO_8859_1)

        val answer = send("POST", "/service/customer/7", listOf("Content-Type: application/octet-stream"), everyByte)

        assertEquals(200, answer.status)
        assertContentEquals(ByteArray(256) { it.toByte() }, handled().request.body.bytes)
        assertEquals("POST" to "application/octet-stream", handled().request.method to handled().request.contentType)
    }

    @Test
    fun aBodyThatIsSentInChunksReachesTheEngineInOnePiece() {
        val head = listOf("PUT /service/customer/7 HTTP/1.1", "Host: mockey.test", "Connection: close", "Transfer-Encoding: chunked", "")
        val chunks = listOf("5", "hello", "1", ",", "6", " world", "0", "", "")

        val answer = RawHttp.exchange(listener.address, (head + chunks).joinToString(RawHttp.CRLF).toByteArray())

        assertEquals(200, answer.status)
        assertEquals("hello, world", handled().request.bodyText)
    }

    @Test
    fun aFormReachesTheEngineWithItsFields() {
        val form = listOf("Content-Type: application/x-www-form-urlencoded")

        send("POST", "/service/customer/7?id=7", form, "name=Jane+Doe&city=K%C3%B6ln")

        assertEquals(
            listOf("id" to "7", "name" to "Jane Doe", "city" to "K\u00f6ln"),
            handled().request.parameters.map {
                it.name to
                    it.value
            },
        )
    }

    @Test
    fun theAddressOfTheClientReachesTheEngine() {
        send("GET", "/service/customer/7")

        assertEquals("127.0.0.1", handled().clientAddress)
    }

    // What is no mock traffic

    @Test
    fun aRequestWhosePathDoesNotBeginWithTheServicePathIsNoMockTraffic() {
        val targets =
            listOf("/", "/service", "/Service/customer/7", "/services/customer/7", "/x/service/customer/7", "/?/service/customer/7", "*")

        val answers = targets.map { send("GET", it) } + send("POST", "/home", body = "x") + send("OPTIONS", "*")

        assertEquals(List(answers.size) { "HTTP/1.1 404 Not Found" to "" }, answers.map { it.statusLine to it.body })
        assertEquals(emptyList(), history())
    }

    // A request that the engine forwards

    @Test
    fun aRequestForAnAbsoluteTargetIsForwardedAsItWasSentAndTheAnswerIsRelayed() {
        val target = "${upstream.origin}/orders//2024/a%20b%2Fc?x=%2F&y=a+b&z"
        val headers = listOf("Content-Type: text/plain", "X-Request-Id: 7")

        val answer = send("POST", "/service/$target", headers, "ordered")

        val forwarded = upstream.requests.single()
        assertEquals("POST" to "/orders//2024/a%20b%2Fc?x=%2F&y=a+b&z", forwarded.method to forwarded.target)
        assertEquals("ordered", String(forwarded.body))
        assertEquals(listOf("7") to listOf("text/plain"), forwarded.headerValues("X-Request-Id") to forwarded.headerValues("Content-Type"))
        assertEquals("HTTP/1.1 200 OK" to "answered by the real service", answer.statusLine to answer.body)
        assertEquals(listOf("text/plain"), answer.headerValues("Content-Type"))
        assertEquals(listOf("28"), answer.headerValues("Content-Length"))
        assertEquals("${upstream.origin}/orders//2024/a%20b%2Fc", handled().target)
    }

    // The size of a body

    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    /** A body of [size] bytes that a request reads piece by piece. */
    private fun bodyOf(size: Long): () -> InputStream =
        {
            object : InputStream() {
                private var left = size

                override fun read(): Int = if (left-- > 0) 'x'.code else -1

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (left <= 0) return -1
                    val count = minOf(length.toLong(), left).toInt()
                    bytes.fill('x'.code.toByte(), offset, offset + count)
                    left -= count
                    return count
                }
            }
        }

    /** Posts a body of [size] bytes to [target], with its length stated or in chunks, and returns the status and the body of the answer. */
    private fun post(
        target: String,
        size: Long,
        to: Listener = listener,
        chunked: Boolean = false,
    ): Pair<Int, String> {
        val stream = HttpRequest.BodyPublishers.ofInputStream(bodyOf(size))
        val publisher = if (chunked) stream else HttpRequest.BodyPublishers.fromPublisher(stream, size)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${to.address.port}$target")).POST(publisher).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** The scenario "Oversized body". */
    @Test
    fun aBodyOfTwiceTheMostThatTheServerTakesGetsTheStatus413AndIsNeitherMatchedNorForwarded() {
        val refused = "The body of the request is longer than 1048576 bytes, which is the most that this server takes."

        val ofService = post("/service/customer/7", 2 * MEBIBYTE)
        val ofRealService = post("/service/${upstream.origin}/orders", 2 * MEBIBYTE)

        assertEquals(413 to refused, ofService)
        assertEquals(413 to refused, ofRealService)
        assertEquals(emptyList(), history(), "No request was matched")
        assertEquals(emptyList(), upstream.requests.toList(), "No request was forwarded")
    }

    @Test
    fun aBodyOfExactlyTheMostThatTheServerTakesIsHandled() {
        val atTheLimit = post("/service/customer/7", MEBIBYTE)
        val oneMore = post("/service/customer/7", MEBIBYTE + 1)

        assertEquals(200 to """{"id":1}""", atTheLimit)
        assertEquals(413, oneMore.first)
        assertEquals(listOf(MEBIBYTE), history().map { it.request.body.size })
    }

    @Test
    fun aBodyInChunksIsCountedAsItArrives() {
        val atTheLimit = post("/service/customer/7", MEBIBYTE, chunked = true)
        val oneMore = post("/service/customer/7", MEBIBYTE + 1, chunked = true)
        val twice = post("/service/customer/7", 2 * MEBIBYTE, chunked = true)

        assertEquals(listOf(200, 413, 413), listOf(atTheLimit, oneMore, twice).map { it.first })
        assertEquals(listOf(MEBIBYTE), history().map { it.request.body.size })
    }

    @Test
    fun aServerThatTakesNoBodyStillTakesRequestsWithoutOne() {
        val strict = endpoint(maxRequestBody = 0)

        val withBody = post("/service/customer/7", 1, to = strict)
        val without = RawHttp.get(strict.address, "/service/customer/7")

        assertEquals(413 to "The body of the request is longer than 0 bytes, which is the most that this server takes.", withBody)
        assertEquals(200, without.status)
    }

    @Test
    fun theAnswerToABodyThatIsTooLongIsPlainText() {
        val head =
            listOf("POST /service/customer/7 HTTP/1.1", "Host: mockey.test", "Connection: close", "Content-Length: ${5 * MEBIBYTE}", "", "")

        val answer = RawHttp.exchange(listener.address, head.joinToString(RawHttp.CRLF).toByteArray(), waitMillis = 1_000)

        assertEquals("HTTP/1.1 413 Payload Too Large", answer.statusLine)
        assertEquals(listOf("text/plain; charset=UTF-8"), answer.headerValues("Content-Type"))
        assertContains(answer.body, "longer than 1048576 bytes")
    }

    @Test
    fun aBodyThatIsTooLongDoesNotCountForWhatIsNoMockTraffic() {
        assertEquals(404 to "", post("/home", 2 * MEBIBYTE))
    }

    // A request that the engine does not handle

    @Test
    fun aRequestThatFailsGetsTheStatus500AndTheLogSaysWhyWithoutTheQueryString() {
        val before = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        val answer =
            try {
                listener
                engine.close()
                send("GET", "/service/customer/7?key=secret-4f1a")
            } finally {
                System.setOut(before)
            }

        val log = captured.toString()
        assertEquals("HTTP/1.1 500 Internal Server Error", answer.statusLine)
        assertEquals("The server failed to handle the request. Its log says why.", answer.body)
        assertContains(log, " ERROR [")
        assertContains(log, "MockEndpoint - The request GET /service/customer/7 failed.")
        assertContains(log, "java.lang.IllegalStateException: The engine is closed.")
        assertFalse("secret-4f1a" in log, log)
        assertTrue("secret-4f1a" !in answer.body)
    }

    private companion object {
        const val MEBIBYTE = 1_048_576L
    }
}
