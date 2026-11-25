package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.ResponseMode
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The rule that a request names the host that it is for, over real HTTP: on a listener that runs what the
 * mock listener runs, and on a listener that serves nothing, as the admin listener does.
 */
class RequestHostTest {
    private val engine: MockeyEngine =
        runBlocking {
            MockeyEngine.create().also { engine ->
                val service =
                    engine.services
                        .create { it.copy(name = "Customers", mockUrl = "customer/{id}", responseMode = ResponseMode.STATIC) }
                        .getOrThrow()
                engine.scenarios.create(service.id) { it.copy(name = "found", responseBody = CUSTOMER) }.getOrThrow()
            }
        }
    private val serving =
        Listener.start(
            ListenerKind.MOCK,
            RawHttp.loopback(0),
            MockeyServer.mockListenerModule(MockEndpoint(engine, maxRequestBody = MEBIBYTE), StatusEndpoint(Instant.EPOCH)),
        )
    private val servingNothing = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0)) {}

    @AfterTest
    fun stopEverything() {
        serving.stop()
        servingNothing.stop()
        engine.close()
    }

    /** Sends a request that consists of [line], [headers], and [body] to [listener], and returns the answer. */
    private fun answerTo(
        line: String,
        headers: List<String> = emptyList(),
        body: String = "",
        listener: Listener = serving,
    ): RawHttp.Answer {
        val length = if (body.isEmpty()) emptyList() else listOf("Content-Length: ${body.length}")
        val request = listOf(line) + headers + length + listOf("Connection: close", "", body)
        return RawHttp.exchange(listener.address, request.joinToString(RawHttp.CRLF).toByteArray(Charsets.ISO_8859_1))
    }

    private fun requestsThatTheEngineGot(): Int = engine.history.entries().size

    @Test
    fun aRequestOfHttp11ThatNamesNoHostIsRefusedWhateverItIsFor() {
        val targets = listOf("/service/customer/7", "http://mockey.test/service/customer/7", "/status", "/no/such/path", "/")

        for (headers in listOf(emptyList(), listOf("Host:"), listOf("Host:   "))) {
            for (target in targets) {
                for (listener in listOf(serving, servingNothing)) {
                    val answer = answerTo("GET $target HTTP/1.1", headers, listener = listener)

                    assertEquals("HTTP/1.1 400 Bad Request" to REFUSAL, answer.statusLine to answer.body, "$target with $headers")
                }
            }
        }
        assertEquals(0, requestsThatTheEngineGot())
    }

    /** Nothing else of the server sees a request that is refused, so nothing fails over it, and the server logs nothing. */
    @Test
    fun aRequestThatIsRefusedGoesNoFurther() {
        var answers = emptyList<RawHttp.Answer>()
        val log =
            TestLog.of {
                answers = listOf("/service/customer/7", "/status", "/no/such/path").map { answerTo("POST $it HTTP/1.1", body = "a body") }
            }

        assertEquals(List(3) { "HTTP/1.1 400 Bad Request" to REFUSAL }, answers.map { it.statusLine to it.body })
        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(0, requestsThatTheEngineGot())
    }

    /** The refusal comes from the listener and is no mock response, so it carries none of the default headers of those. */
    @Test
    fun theRefusalIsPlainTextAndNothingElse() {
        val answers = listOf("/service/customer/7", "/status", "/no/such/path").map { answerTo("GET $it HTTP/1.1") }

        for (answer in answers) {
            assertEquals(listOf("Content-Length: 43", "Content-Type: text/plain; charset=utf-8", "Connection: close"), answer.headers)
        }
    }

    @Test
    fun aHostHeaderThatHoldsWhatNoNameOfAHostHoldsIsRefused() {
        for (host in listOf("evil.example/service/x", "a?b", "a#b", "user@host", "a b", """a\b""", "a\tb")) {
            for (target in listOf("/service/customer/7", "/status", "/no/such/path")) {
                val ofLaterVersion = answerTo("GET $target HTTP/1.1", listOf("Host: $host"))
                val ofFirstVersion = answerTo("GET $target HTTP/1.0", listOf("Host: $host"))

                assertEquals("HTTP/1.1 400 Bad Request" to REFUSAL, ofLaterVersion.statusLine to ofLaterVersion.body, "$target of $host")
                assertEquals("HTTP/1.0 400 Bad Request" to REFUSAL, ofFirstVersion.statusLine to ofFirstVersion.body, "$target of $host")
            }
        }
        assertEquals(0, requestsThatTheEngineGot())
    }

    @Test
    fun aRequestWithMoreThanOneHostHeaderIsRefused() {
        val several = "The request has more than one Host header."

        for (headers in listOf(listOf("Host: mockey.test", "Host: other.test"), listOf("Host: mockey.test", "host: mockey.test"))) {
            for (target in listOf("/service/customer/7", "/status", "/no/such/path")) {
                val ofLaterVersion = answerTo("GET $target HTTP/1.1", headers)
                val ofFirstVersion = answerTo("GET $target HTTP/1.0", headers)

                assertEquals(
                    "HTTP/1.1 400 Bad Request" to several,
                    ofLaterVersion.statusLine to ofLaterVersion.body,
                    "$target with $headers",
                )
                assertEquals(
                    "HTTP/1.0 400 Bad Request" to several,
                    ofFirstVersion.statusLine to ofFirstVersion.body,
                    "$target with $headers",
                )
            }
        }
        assertEquals(0, requestsThatTheEngineGot())
    }

    @Test
    fun aRequestOfHttp10NeedNotNameAHost() {
        val answers =
            listOf("/service/customer/7", "/status", "/no/such/path").map { answerTo("GET $it HTTP/1.0") } +
                answerTo("GET / HTTP/1.0", listener = servingNothing)

        assertEquals(
            listOf("HTTP/1.0 200 OK", "HTTP/1.0 200 OK", "HTTP/1.0 404 Not Found", "HTTP/1.0 404 Not Found"),
            answers.map { it.statusLine },
        )
        assertEquals(CUSTOMER, answers.first().body)
        assertEquals(1, requestsThatTheEngineGot())
    }

    @Test
    fun aRequestThatNamesAHostIsPassedOn() {
        val hosts = listOf("mockey.test", "MOCKEY.test:8080", "192.0.2.7:80", "[2001:db8::1]:8080", "[::1]", "local_host-1")

        for (host in hosts) {
            val headers = listOf("Host: $host")
            val statuses =
                listOf("/service/customer/7", "/status", "/no/such/path").map { answerTo("GET $it HTTP/1.1", headers).status } +
                    answerTo("GET / HTTP/1.1", headers, listener = servingNothing).status

            assertEquals(listOf<Int?>(200, 200, 404, 404), statuses, host)
        }
        assertEquals(hosts.size, requestsThatTheEngineGot())
    }

    /**
     * The listener refuses the request before it has read the body. The connection ends with the answer,
     * so the answer waits until the client has sent its body: a client that is still sending when the
     * connection closes can lose the answer.
     */
    @Test
    fun aClientThatSendsABodyGetsTheRefusalWhenItHasSentTheBody() {
        val body = "x".repeat(8 * MEBIBYTE.toInt())

        val ofLaterVersion = List(5) { answerTo("POST /service/customer/7 HTTP/1.1", body = body) }
        val ofFirstVersion = List(5) { answerTo("POST /status HTTP/1.0", listOf("Host: a/b"), body) }

        assertEquals(List(5) { "HTTP/1.1 400 Bad Request" to REFUSAL }, ofLaterVersion.map { it.statusLine to it.body })
        assertEquals(List(5) { "HTTP/1.0 400 Bad Request" to REFUSAL }, ofFirstVersion.map { it.statusLine to it.body })
        assertFalse((ofLaterVersion + ofFirstVersion).any { !it.ended }, "Every connection ends with the answer")
    }

    private companion object {
        const val CUSTOMER = """{"id":1}"""
        const val REFUSAL = "The request names no host in a Host header."
        const val MEBIBYTE = 1_048_576L
    }
}
