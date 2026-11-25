package io.github.dnedrow.mockey.server

import java.net.ServerSocket
import java.nio.file.Files
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

    @AfterTest
    fun closeTheServersAndRestoreTheLevelOfTheLog() {
        servers.forEach(MockeyServer::close)
        ServerLogging.configure(levelBefore)
    }

    /** Settings with which a server of a test listens on the loopback address only, and says little in the log. */
    private fun settings(
        mockPort: Int = 0,
        adminPort: Int = 0,
    ) = ServerSettings(mockHost = "127.0.0.1", mockPort = mockPort, adminPort = adminPort, logLevel = LogLevel.WARN)

    private fun start(settings: ServerSettings): MockeyServer = MockeyServer.start(settings).also { servers += it }

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
        val directory = Files.createTempDirectory("mockey-server-test")
        try {
            StoredDefinitions.customers(directory)

            val server = start(settings().copy(definitions = directory))

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
        } finally {
            servers.forEach(MockeyServer::close)
            servers.clear()
            directory.toFile().deleteRecursively()
        }
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
