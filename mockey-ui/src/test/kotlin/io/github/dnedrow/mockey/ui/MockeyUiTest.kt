package io.github.dnedrow.mockey.ui

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Task 13.1: how the UI starts and stops, and where it accepts connections. The UI of a test listens on a
 * port that the system picks, unless the test is about a port.
 */
class MockeyUiTest {
    private val uis = mutableListOf<MockeyUi>()
    private val sockets = mutableListOf<ServerSocket>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        sockets.forEach(ServerSocket::close)
    }

    private fun start(settings: UiSettings): MockeyUi = MockeyUi.start(settings).also { uis += it }

    /** The settings of a UI that is told nothing but to take a free port and to log little. */
    private val told = UiSettings(port = 0, logLevel = LogLevel.WARN)

    /** Returns a socket that listens on a port of the loopback address, which nothing else can listen on then. */
    private fun taken(): ServerSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { sockets += it }

    /**
     * The scenario "Default bind": a UI that is started without a bind address accepts connections on the
     * loopback address, and on no other address of the machine. A UI that is told to listen on all
     * addresses is reached through the same address, so the test can tell the two apart.
     */
    @Test
    fun aUiWithoutABindAddressAcceptsConnectionsOnTheLoopbackAddressOnly() {
        TestEnvironment.needs(OutsideAddresses.usable.isNotEmpty(), "an address beyond the loopback address that reaches this machine")
        val outside = OutsideAddresses.usable.first()

        val byDefault = start(told)
        val onAllAddresses = start(told.copy(host = "0.0.0.0"))

        assertEquals("127.0.0.1", UiSettings().host)
        assertTrue(byDefault.address.address.isLoopbackAddress, "${byDefault.address}")
        assertTrue(OutsideAddresses.reaches(byDefault.address))
        assertFalse(OutsideAddresses.reaches(InetSocketAddress(outside, byDefault.address.port)), "$outside")
        assertTrue(OutsideAddresses.reaches(InetSocketAddress(outside, onAllAddresses.address.port)), "$outside")
    }

    /** The UI says in its log which settings it runs with and where it listens, and it warns of an address that other machines reach. */
    @Test
    fun theUiWritesItsSettingsAndItsAddressToTheLog() {
        val token = AdminToken("the-token-of-the-server")
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of("http://mockey.test:9081"), adminToken = token)
        var port = 0

        val log = TestLog.of { port = start(settings).address.port }
        val beyond = TestLog.of { start(told.copy(host = "0.0.0.0", logLevel = LogLevel.INFO)) }

        val expected =
            listOf(
                "Mockey UI ${System.getProperty("mockey.version")} starts with these settings:",
                "  host = 127.0.0.1",
                "  port = 0",
                "  admin-url = http://mockey.test:9081",
                "  admin-token = set",
                "  log-level = info",
                "The UI accepts connections at http://127.0.0.1:$port. It manages the server at http://mockey.test:9081.",
            )
        assertEquals(expected, TestLog.messages(log))
        assertFalse("the-token-of-the-server" in log)
        assertEquals(emptyList(), TestLog.messages(log, "WARN"))
        val warning = TestLog.messages(beyond, "WARN").single()
        assertContains(warning, "The UI accepts connections on the address 0.0.0.0, which other machines can reach.")
    }

    /** The UI asks the server for nothing before a page is loaded, so it starts whether or not the server runs. */
    @Test
    fun theUiStartsWithoutTheServerThatItManages() {
        val nothing = taken().also { it.close() }

        val ui = start(told.copy(adminUrl = AdminUrl.of("http://127.0.0.1:${nothing.localPort}")))

        assertTrue(OutsideAddresses.reaches(ui.address))
    }

    @Test
    fun aUiThatHasStoppedAcceptsNoConnectionAndStaysStopped() {
        val ui = start(told)
        val address = ui.address

        ui.stop(1.seconds)
        ui.stop(1.seconds)
        ui.close()

        assertFalse(OutsideAddresses.reaches(address))
    }

    /** A UI that cannot listen says on which address and port, and which settings those are. Nothing of it is left behind. */
    @Test
    fun aUiThatCannotListenSaysWhereAndWithWhichSettings() {
        val taken = taken()

        val inUse = assertFailsWith<StartupFailure> { MockeyUi.start(told.copy(port = taken.localPort)) }
        val unknown = assertFailsWith<StartupFailure> { MockeyUi.start(told.copy(host = "no-such-host.invalid")) }
        val notOfThisMachine = assertFailsWith<StartupFailure> { MockeyUi.start(told.copy(host = "192.0.2.1")) }

        val settings = "The address is the setting --host or MOCKEY_UI_HOST, and the port is --port or MOCKEY_UI_PORT."
        assertTrue(inUse.message.startsWith("The UI cannot accept connections on 127.0.0.1:${taken.localPort}: "), inUse.message)
        assertTrue(inUse.message.endsWith(settings), inUse.message)
        assertEquals(
            "The address of the UI, no-such-host.invalid, is not known. Set --host or MOCKEY_UI_HOST to an address of this machine.",
            unknown.message,
        )
        assertTrue(notOfThisMachine.message.startsWith("The UI cannot accept connections on 192.0.2.1:0: "), notOfThisMachine.message)
    }
}
