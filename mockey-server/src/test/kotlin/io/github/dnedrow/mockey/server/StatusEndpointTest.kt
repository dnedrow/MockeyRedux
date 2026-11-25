package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.ResponseMode.STATIC
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The liveness endpoint over real HTTP, on a listener that runs the module of the mock listener. */
class StatusEndpointTest {
    private val since = Instant.parse("2026-01-02T03:04:05.678912Z")
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val module = MockeyServer.mockListenerModule(MockEndpoint(engine, maxRequestBody = 1_024), StatusEndpoint(since))
    private val listener = Listener.start(ListenerKind.MOCK, RawHttp.loopback(0), module)

    @AfterTest
    fun stopEverything() {
        listener.stop()
        engine.close()
    }

    private fun send(
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer = RawHttp.exchange(listener.address, RawHttp.request(method, target, body = body))

    /** The scenario "Status while running". */
    @Test
    fun theStatusSaysThatTheServerIsRunningAndSinceWhenAndNothingElse() {
        val answer = send("GET", "/status")

        val document = Json.parseToJsonElement(answer.body) as JsonObject
        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals(setOf("status", "since"), document.keys)
        assertEquals(JsonPrimitive("running"), document["status"])
        assertEquals(JsonPrimitive("2026-01-02T03:04:05.678Z"), document["since"])
        assertEquals(since.toEpochMilli(), Instant.parse((document.getValue("since") as JsonPrimitive).content).toEpochMilli())
        assertEquals("""{"status":"running","since":"2026-01-02T03:04:05.678Z"}""", answer.body)
    }

    @Test
    fun theStatusIsAJsonDocumentThatIsNotToBeKept() {
        val answer = send("GET", "/status")

        assertEquals(
            listOf("Cache-Control: no-store", "Content-Length: 55", "Content-Type: application/json", "Connection: close"),
            answer.headers,
        )
    }

    /** The status holds nothing that separates the parts of a path, so it holds no path of the file system. */
    @Test
    fun theStatusDisclosesNoPath() {
        val answer = send("GET", "/status")

        assertFalse(answer.body.any { it in "/\\" }, answer.body)
    }

    @Test
    fun theStatusIsTheSameForEveryRequest() {
        val answers = List(3) { send("GET", "/status").body } + send("GET", "/status?verbose=true&x=1").body

        assertEquals(1, answers.distinct().size, "$answers")
    }

    @Test
    fun aHeadRequestGetsTheHeadersOfTheStatus() {
        val toHead = send("HEAD", "/status")
        val toGet = send("GET", "/status")

        assertEquals("HTTP/1.1 200 OK" to "", toHead.statusLine to toHead.body)
        assertEquals(toGet.headers, toHead.headers)
    }

    /** Mockey 2.x answers a request of every method for `/status` with the status, and so does the server. */
    @Test
    fun theStatusIsTheSameForEveryMethod() {
        val toGet = send("GET", "/status")

        for (method in listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "FOO")) {
            val answer = send(method, "/status", body = if (method == "POST") "since=never&status=stopped" else "")

            assertEquals(toGet.statusLine to toGet.body, answer.statusLine to answer.body, method)
            assertEquals(toGet.headers, answer.headers, method)
        }
    }

    @Test
    fun onlyThePathOfTheStatusIsTheStatus() {
        for (target in listOf("/status/", "/Status", "/status/x", "/statuses", "/x/status", "/service", "/")) {
            val answer = send("GET", target)

            assertEquals("HTTP/1.1 404 Not Found" to "", answer.statusLine to answer.body, target)
        }
        assertEquals(200, send("GET", "http://mockey.test:8080/status").status)
    }

    @Test
    fun theStatusIsNoMockTrafficAndMockTrafficIsNoStatus() {
        runBlocking {
            val service = engine.services.create { it.copy(name = "Status", mockUrl = "status", responseMode = STATIC) }.getOrThrow()
            engine.scenarios.create(service.id) { it.copy(name = "answer", responseBody = "the service named status") }.getOrThrow()
        }

        val ofStatus = send("GET", "/status")
        val ofService = send("GET", "/service/status")

        assertTrue(ofStatus.body.startsWith("""{"status":"running","""), ofStatus.body)
        assertEquals("the service named status", ofService.body)
        assertEquals(listOf("status"), engine.history.entries().map { it.target })
        assertEquals(emptyList(), ofStatus.headerValues("Access-Control-Allow-Origin"))
        assertEquals(listOf("*"), ofService.headerValues("Access-Control-Allow-Origin"))
    }
}
