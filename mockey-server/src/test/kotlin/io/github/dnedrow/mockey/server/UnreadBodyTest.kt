package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a client gets that sends a body with a request which is answered before the body is read: a request
 * for a path that nothing serves, and a request for `/status`. The tests of the mock endpoint and of the
 * rule about the host show the same for the requests that those refuse.
 */
class UnreadBodyTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val module = MockeyServer.mockListenerModule(MockEndpoint(engine, maxRequestBody = MEBIBYTE), StatusEndpoint(Instant.EPOCH))
    private val listener = Listener.start(ListenerKind.MOCK, RawHttp.loopback(0), module)

    @AfterTest
    fun stopEverything() {
        listener.stop()
        engine.close()
    }

    /**
     * Sends a request that consists of [line], [headers], a `Content-Length` header that states [stated]
     * bytes, and [body], and returns what the server writes until it ends the connection, or until it has
     * written nothing for [waitMillis].
     */
    private fun answerTo(
        line: String,
        headers: List<String>,
        stated: Int,
        body: String,
        waitMillis: Int = ANSWER_MILLIS,
    ): RawHttp.Answer {
        val request = listOf(line, "Host: mockey.test") + headers + listOf("Content-Length: $stated", "", body)
        return RawHttp.exchange(listener.address, request.joinToString(RawHttp.CRLF).toByteArray(Charsets.ISO_8859_1), waitMillis)
    }

    /** Returns the status line of each of [answers], and whether the server ended the connection after it. */
    private fun outcomes(answers: List<RawHttp.Answer>): List<Pair<String, Boolean>> = answers.map { it.statusLine to it.ended }

    /**
     * The connection ends with the answer, and Netty closes it as soon as the answer is written. So the
     * answer waits until the client has sent its body: a client that is still sending then gets no answer.
     */
    @Test
    fun aClientThatEndsItsConnectionGetsTheAnswerWhenItHasSentItsBody() {
        val body = "x".repeat(8 * MEBIBYTE.toInt())

        for (asking in listOf("Connection: close", "Connection: TE, Close")) {
            val ofNothing = List(3) { answerTo("POST /no/such/path HTTP/1.1", listOf(asking), body.length, body) }
            val ofStatus = List(3) { answerTo("POST /status HTTP/1.1", listOf(asking), body.length, body) }

            assertEquals(List(3) { "HTTP/1.1 404 Not Found" to true }, outcomes(ofNothing), asking)
            assertEquals(List(3) { "HTTP/1.1 200 OK" to true }, outcomes(ofStatus), asking)
            assertEquals(List(3) { STATUS }, ofStatus.map { it.body }, asking)
        }
    }

    /** A client of HTTP/1.0 does not keep its connection unless it asks for that. */
    @Test
    fun aClientOfHttp10GetsTheAnswerWhenItHasSentItsBody() {
        val body = "x".repeat(8 * MEBIBYTE.toInt())

        val ofNothing = List(3) { answerTo("POST /no/such/path HTTP/1.0", emptyList(), body.length, body) }
        val ofStatus = List(3) { answerTo("POST /status HTTP/1.0", emptyList(), body.length, body) }

        assertEquals(List(3) { "HTTP/1.0 404 Not Found" to true }, outcomes(ofNothing))
        assertEquals(List(3) { "HTTP/1.0 200 OK" to true }, outcomes(ofStatus))
    }

    /** The client states a body of 5 MiB and sends nothing of it. On a connection that stays it has its answer all the same. */
    @Test
    fun onAConnectionThatStaysTheAnswerGoesOutAtOnce() {
        val stated = 5 * MEBIBYTE.toInt()

        val unasked = answerTo("POST /no/such/path HTTP/1.1", emptyList(), stated, body = "", STAYING_MILLIS)
        val unaskedForStatus = answerTo("POST /status HTTP/1.1", emptyList(), stated, body = "", STAYING_MILLIS)
        val asked = answerTo("POST /status HTTP/1.1", listOf("Connection: keep-alive"), stated, body = "", STAYING_MILLIS)
        val askedInHttp10 = answerTo("POST /no/such/path HTTP/1.0", listOf("Connection: Keep-Alive"), stated, body = "", STAYING_MILLIS)

        assertEquals(
            listOf(
                "HTTP/1.1 404 Not Found" to false,
                "HTTP/1.1 200 OK" to false,
                "HTTP/1.1 200 OK" to false,
                "HTTP/1.0 404 Not Found" to false,
            ),
            outcomes(listOf(unasked, unaskedForStatus, asked, askedInHttp10)),
        )
        assertEquals(STATUS, unaskedForStatus.body)
    }

    private companion object {
        const val MEBIBYTE = 1_048_576L

        /** How long a client waits for an answer after which the server ends the connection. */
        const val ANSWER_MILLIS = 5_000

        /** How long a client waits to see that the server keeps the connection after its answer. */
        const val STAYING_MILLIS = 1_000

        const val STATUS = """{"status":"running","since":"1970-01-01T00:00:00Z"}"""
    }
}
