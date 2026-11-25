package io.github.dnedrow.mockey.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** The server in the process of the test: what it does from the moment it is started until it is closed. */
class MockeyServerTest {
    private val levelBefore = ServerLogging.level()
    private val servers = mutableListOf<MockeyServer>()
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun closeTheServersAndRestoreTheLevelOfTheLog() {
        servers.forEach(MockeyServer::close)
        directories.forEach { it.toFile().deleteRecursively() }
        ServerLogging.configure(levelBefore)
    }

    /** Returns a definitions location that holds the service `Customers`. */
    private fun definitionsLocation(): Path =
        Files.createTempDirectory("mockey-server-test").also {
            directories.add(it)
            StoredDefinitions.customers(it)
        }

    /** Settings with which a server of a test listens on the loopback address only, and says little in the log. */
    private fun settings(
        mockPort: Int = 0,
        adminPort: Int = 0,
    ) = ServerSettings(mockHost = "127.0.0.1", mockPort = mockPort, adminPort = adminPort, logLevel = LogLevel.WARN)

    private fun start(
        settings: ServerSettings,
        clock: Clock = Clock.systemUTC(),
    ): MockeyServer = MockeyServer.start(settings, clock).also { servers += it }

    @Test
    fun theServerListensWhereItsSettingsSay() {
        val (mockPort, adminPort) = RawHttp.freePorts(2)

        val server = start(settings(mockPort, adminPort))

        assertEquals(
            "127.0.0.1:$mockPort",
            server.listeners.mock.address
                .text(),
        )
        assertEquals(
            "127.0.0.1:$adminPort",
            server.listeners.admin.address
                .text(),
        )
        assertEquals(404, RawHttp.get(RawHttp.loopback(mockPort)).status)
        assertEquals(404, RawHttp.get(RawHttp.loopback(adminPort)).status)
    }

    @Test
    fun theMockListenerServesTheDefinitionsOfTheDefinitionsLocationAndTheAdminListenerDoesNot() {
        val server = start(settings().copy(definitions = definitionsLocation()))

        val ofMock = RawHttp.get(server.listeners.mock.address, "/service/customer/7")
        val ofAdmin = RawHttp.get(server.listeners.admin.address, "/service/customer/7")
        assertEquals(200 to StoredDefinitions.CUSTOMER, ofMock.status to ofMock.body)
        assertEquals("HTTP/1.1 404 Not Found" to "", ofAdmin.statusLine to ofAdmin.body)
        assertEquals(
            listOf("customer/7"),
            server.engine.history
                .entries()
                .map { it.target },
        )
    }

    /** The scenario "Status while running", with the time at which this server started. */
    @Test
    fun theMockListenerSaysSinceWhenTheServerRunsAndTheAdminListenerDoesNot() {
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val server = start(settings())
        val after = Instant.now()

        val ofMock = RawHttp.get(server.listeners.mock.address, "/status")
        val ofAdmin = RawHttp.get(server.listeners.admin.address, "/status")

        val status = Json.parseToJsonElement(ofMock.body) as JsonObject
        val since = Instant.parse((status.getValue("since") as JsonPrimitive).content)
        assertEquals("HTTP/1.1 200 OK", ofMock.statusLine)
        assertEquals(setOf("status", "since"), status.keys)
        assertEquals(JsonPrimitive("running"), status["status"])
        assertEquals(server.since.truncatedTo(ChronoUnit.MILLIS), since)
        assertTrue(since in before..after, "The server runs since $since, and it was started between $before and $after")
        assertEquals("HTTP/1.1 404 Not Found" to "", ofAdmin.statusLine to ofAdmin.body)
    }

    @Test
    fun theServerTellsTheTimeOfItsClock() {
        val clock = Clock.fixed(Instant.parse("2031-05-06T07:08:09.123456Z"), ZoneOffset.UTC)

        val server = start(settings().copy(definitions = definitionsLocation()), clock)

        val status = RawHttp.get(server.listeners.mock.address, "/status")
        val ofService = RawHttp.get(server.listeners.mock.address, "/service/customer/7")
        assertEquals(clock.instant(), server.since)
        assertEquals("""{"status":"running","since":"2031-05-06T07:08:09.123Z"}""", status.body)
        assertEquals(listOf("Tue, 06 May 2031 07:08:09 GMT"), ofService.headerValues("Last-Modified"))
    }

    /**
     * The scenario "No cross-origin header on the admin API", for everything that the admin listener
     * answers: a page of another origin gets no permission from it, whatever it asks for. The mock listener
     * gives the permission with its mock responses, and only with them.
     */
    @Test
    fun theAdminListenerGivesAPageOfAnotherOriginNoPermission() {
        val server = start(settings().copy(definitions = definitionsLocation()))
        val origin = listOf("Origin: https://pages.example")
        val asking = origin + listOf("Access-Control-Request-Method: PUT", "Access-Control-Request-Headers: authorization, content-type")
        val requests =
            mapOf(
                "GET /" to RawHttp.request("GET", "/", origin),
                "GET /api/v1/services" to RawHttp.request("GET", "/api/v1/services", origin),
                "POST /api/v1/services" to RawHttp.request("POST", "/api/v1/services", origin, body = "{}"),
                "OPTIONS /api/v1/services" to RawHttp.request("OPTIONS", "/api/v1/services", asking),
                "GET /status" to RawHttp.request("GET", "/status", origin),
                "GET /service/customer/7" to RawHttp.request("GET", "/service/customer/7", origin),
                "OPTIONS /service/customer/7" to RawHttp.request("OPTIONS", "/service/customer/7", asking),
            )

        fun permissions(answer: RawHttp.Answer) = answer.headers.filter { it.startsWith("access-control-", ignoreCase = true) }

        val ofAdmin = requests.mapValues { (_, bytes) -> RawHttp.exchange(server.listeners.admin.address, bytes) }
        assertEquals(
            mapOf<String, Int?>(
                "GET /" to 404,
                "GET /api/v1/services" to 200,
                "POST /api/v1/services" to 405,
                "OPTIONS /api/v1/services" to 405,
                "GET /status" to 404,
                "GET /service/customer/7" to 404,
                "OPTIONS /service/customer/7" to 404,
            ),
            ofAdmin.mapValues { (_, answer) -> answer.status },
        )
        assertEquals(requests.mapValues { emptyList<String>() }, ofAdmin.mapValues { (_, answer) -> permissions(answer) })
        val ofMock = requests.mapValues { (_, bytes) -> permissions(RawHttp.exchange(server.listeners.mock.address, bytes)) }
        val forMockTraffic = listOf("Access-Control-Allow-Origin: *")
        assertEquals(
            mapOf(
                "GET /" to emptyList(),
                "GET /api/v1/services" to emptyList(),
                "POST /api/v1/services" to emptyList(),
                "OPTIONS /api/v1/services" to emptyList(),
                "GET /status" to emptyList(),
                "GET /service/customer/7" to forMockTraffic,
                "OPTIONS /service/customer/7" to forMockTraffic,
            ),
            ofMock,
        )
    }

    @Test
    fun theServerTakesBodiesAsLongAsItsSettingsSay() {
        val server = start(settings().copy(maxRequestBody = 10))

        val atTheLimit = RawHttp.exchange(server.listeners.mock.address, RawHttp.request("POST", "/service/x", body = "0123456789"))
        val oneMore = RawHttp.exchange(server.listeners.mock.address, RawHttp.request("POST", "/service/x", body = "0123456789a"))

        assertEquals(404 to 413, atTheLimit.status to oneMore.status)
        assertEquals(
            listOf(10L),
            server.engine.history
                .entries()
                .map { it.request.body.size },
        )
    }

    @Test
    fun aListenerAnswersWhatItDoesNotServeWithTheStatus404AndNothingElse() {
        val server = start(settings())

        for (listener in listOf(server.listeners.mock, server.listeners.admin)) {
            val answer = RawHttp.get(listener.address, "/no/such/path")

            assertEquals("HTTP/1.1 404 Not Found", answer.statusLine)
            assertEquals(listOf("Content-Length: 0", "Connection: close"), answer.headers)
            assertEquals("", answer.body)
        }
    }

    @Test
    fun bothListenersRefuseARequestThatNamesNoHost() {
        val server = start(settings())
        val request = "GET /status HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray()

        for (listener in listOf(server.listeners.mock, server.listeners.admin)) {
            val answer = RawHttp.exchange(listener.address, request)

            assertEquals(
                "HTTP/1.1 400 Bad Request" to "The request names no host in a Host header.",
                answer.statusLine to answer.body,
                listener.kind.title,
            )
        }
    }

    @Test
    fun aServerThatIsClosedListensNoLonger() {
        val server = MockeyServer.start(settings())
        val mock = server.listeners.mock.address
        val admin = server.listeners.admin.address

        server.close()

        assertNull(RawHttp.getIfReached(mock, WAIT_MILLIS))
        assertNull(RawHttp.getIfReached(admin, WAIT_MILLIS))
    }

    /**
     * The engine takes seconds to say that a port is in use, and by then the server would have done what
     * comes before its listeners. So the server looks at the ports itself, first of all.
     */
    @Test
    fun aServerWithAPortThatIsInUseDoesNotStartAndSaysSoAtOnce() {
        val (mockPort) = RawHttp.freePorts(1)
        ServerSocket().use { other ->
            other.bind(RawHttp.loopback(0))

            val (refusal, time) =
                measureTimedValue {
                    assertFailsWith<StartupFailure> {
                        MockeyServer.start(
                            settings(mockPort, other.localPort),
                        )
                    }
                }

            assertTrue(
                refusal.message.startsWith("The admin listener cannot accept connections on 127.0.0.1:${other.localPort}: "),
                refusal.message,
            )
            assertTrue(time.inWholeMilliseconds < AT_ONCE_MILLIS, "The server took $time to say that the port is in use")
            assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS), "The mock listener")
        }
    }

    @Test
    fun aServerWithAnAdminListenerBeyondLoopbackAndNoTokenDoesNotStart() {
        val (mockPort, adminPort) = RawHttp.freePorts(2)

        val refusal = assertFailsWith<StartupFailure> { MockeyServer.start(settings(mockPort, adminPort).copy(adminHost = "0.0.0.0")) }

        assertTrue(
            refusal.message.startsWith("The admin listener is set to the address 0.0.0.0, which other machines can reach"),
            refusal.message,
        )
        assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS), "The mock listener")
        assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener")
    }

    private companion object {
        /** How long a test waits for an answer that is not to come. */
        const val WAIT_MILLIS = 500

        /** The longest time that something takes which happens at once. The engine takes twice as long to give up a port. */
        const val AT_ONCE_MILLIS = 2_000L
    }
}
