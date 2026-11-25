package io.github.dnedrow.mockey.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * The two listeners of the server: the addresses that the settings give them, the rule about an admin
 * listener beyond the loopback address, and what happens when a listener cannot be bound.
 *
 * The listeners of a test run two modules that say which listener answered. What the listeners of the
 * server serve is the matter of other tests.
 */
class ListenersTest {
    private val started = mutableListOf<Listeners>()
    private val blockers = mutableListOf<ServerSocket>()

    @AfterTest
    fun stopWhatWasStarted() {
        started.forEach { it.stop(Duration.ZERO) }
        blockers.forEach(ServerSocket::close)
    }

    /** The module of a listener that answers every request with [text]. */
    private fun answering(text: String): suspend Application.() -> Unit =
        { intercept(ApplicationCallPipeline.Call) { call.respondText(text) } }

    /** Starts the listeners that [settings] ask for, as the server does, with the modules of this test. */
    private fun start(settings: ServerSettings): Listeners {
        val addresses = ListenerAddresses.of(settings)
        Listeners.requireFree(addresses)
        return Listeners.start(addresses, answering(MOCK), answering(ADMIN)).also { started += it }
    }

    /** Returns a socket that listens on [host] and a port that the system picks, as another program would. */
    private fun blocker(host: String): ServerSocket =
        ServerSocket().also {
            it.bind(InetSocketAddress(InetAddress.getByName(host), 0))
            blockers += it
        }

    /** Returns what answers a request to [port] on the loopback address. */
    private fun answerOnLoopback(port: Int): String = RawHttp.get(RawHttp.loopback(port)).body

    private fun settingsFor(
        adminHost: String,
        token: String? = null,
        allowUnprotected: Boolean = false,
    ) = ServerSettings(adminHost = adminHost, adminToken = token?.let(::AdminToken), allowUnprotectedAdmin = allowUnprotected)

    // The addresses of the settings

    @Test
    fun theDefaultSettingsPutTheMockListenerOnAllAddressesAndTheAdminListenerOnLoopback() {
        val addresses = ListenerAddresses.of(ServerSettings())

        assertTrue(addresses.mock.address.isAnyLocalAddress)
        assertEquals("0.0.0.0:8080", addresses.mock.text())
        assertTrue(addresses.admin.address.isLoopbackAddress)
        assertEquals("127.0.0.1:8081", addresses.admin.text())
        assertFalse(addresses.adminBeyondLoopback)
    }

    @Test
    fun theAddressAndThePortOfEachListenerAreSetSeparately() {
        val settings = ServerSettings(mockHost = "127.0.0.1", mockPort = 9100, adminHost = "::1", adminPort = 9200)

        val addresses = ListenerAddresses.of(settings)

        assertEquals("127.0.0.1:9100", addresses.mock.text())
        assertEquals("[0:0:0:0:0:0:0:1]:9200", addresses.admin.text())
    }

    @Test
    fun aNameIsAnAddressToo() {
        val addresses = ListenerAddresses.of(ServerSettings(mockHost = "localhost", adminHost = "localhost"))

        assertTrue(addresses.mock.address.isLoopbackAddress)
        assertTrue(addresses.admin.address.isLoopbackAddress)
        assertFalse(addresses.adminBeyondLoopback)
    }

    @Test
    fun anAddressThatIsNotKnownIsRefusedWithItsSetting() {
        val ofMock = assertFailsWith<StartupFailure> { ListenerAddresses.of(ServerSettings(mockHost = "[not-an-address]")) }
        val ofAdmin = assertFailsWith<StartupFailure> { ListenerAddresses.of(ServerSettings(adminHost = "[zz::1]")) }

        assertEquals(
            "The address of the mock listener, [not-an-address], is not known. " +
                "Set --mock-host or MOCKEY_MOCK_HOST to an address of this machine.",
            ofMock.message,
        )
        assertEquals(
            "The address of the admin listener, [zz::1], is not known. " +
                "Set --admin-host or MOCKEY_ADMIN_HOST to an address of this machine.",
            ofAdmin.message,
        )
    }

    // The admin token beyond loopback

    private val beyondLoopback = listOf("0.0.0.0", "::", "192.0.2.1", "10.1.2.3", "169.254.7.7", "2001:db8::1", "fe80::1")
    private val onLoopback = listOf("127.0.0.1", "127.8.9.10", "::1", "localhost")

    @Test
    fun anAdminListenerBeyondLoopbackWithoutATokenIsRefusedWithBothWaysToProceed() {
        for (host in beyondLoopback) {
            val refusal = assertFailsWith<StartupFailure>(host) { ListenerAddresses.of(settingsFor(host)) }

            val address = InetAddress.getByName(host).hostAddress
            assertTrue(
                refusal.message.startsWith("The admin listener is set to the address $address, which other machines can reach"),
                refusal.message,
            )
            assertContains(refusal.message, "there is no admin token")
            assertContains(refusal.message, "Set a token with --admin-token or MOCKEY_ADMIN_TOKEN")
            assertContains(
                refusal.message,
                "allow the admin API without one with --allow-unprotected-admin or MOCKEY_ALLOW_UNPROTECTED_ADMIN=true",
            )
        }
    }

    @Test
    fun anAdminListenerBeyondLoopbackStartsWithATokenOrWithTheOverride() {
        for (host in beyondLoopback) {
            assertTrue(ListenerAddresses.of(settingsFor(host, token = "tk-1")).adminBeyondLoopback, host)
            assertTrue(ListenerAddresses.of(settingsFor(host, allowUnprotected = true)).adminBeyondLoopback, host)
            assertTrue(ListenerAddresses.of(settingsFor(host, token = "tk-1", allowUnprotected = true)).adminBeyondLoopback, host)
        }
    }

    @Test
    fun anAdminListenerOnLoopbackNeedsNeitherATokenNorTheOverride() {
        for (host in onLoopback) {
            assertFalse(ListenerAddresses.of(settingsFor(host)).adminBeyondLoopback, host)
            assertFalse(ListenerAddresses.of(settingsFor(host, token = "tk-1")).adminBeyondLoopback, host)
        }
    }

    @Test
    fun theMockListenerNeedsNoTokenOnAnyAddress() {
        for (host in beyondLoopback + onLoopback) {
            assertEquals(InetAddress.getByName(host), ListenerAddresses.of(ServerSettings(mockHost = host)).mock.address, host)
        }
    }

    // Listeners that accept connections

    @Test
    fun eachListenerAnswersOnItsOwnAddressAndPort() {
        val listeners = start(ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminHost = "127.0.0.1", adminPort = 0))

        assertEquals(MOCK, answerOnLoopback(listeners.mock.address.port))
        assertEquals(ADMIN, answerOnLoopback(listeners.admin.address.port))
        assertNotEquals(listeners.mock.address.port, listeners.admin.address.port)
        assertEquals(ListenerKind.MOCK to ListenerKind.ADMIN, listeners.mock.kind to listeners.admin.kind)
        assertEquals(listeners.mock.address, listeners.mock.socketAddress)
        assertEquals(listeners.admin.address, listeners.admin.socketAddress)
        assertTrue(listeners.mock.address.address.isLoopbackAddress && listeners.admin.address.address.isLoopbackAddress)
    }

    @Test
    fun theListenersTakeThePortsOfTheSettings() {
        val (mockPort, adminPort) = RawHttp.freePorts(2)

        val listeners = start(ServerSettings(mockHost = "127.0.0.1", mockPort = mockPort, adminPort = adminPort))

        assertEquals(mockPort to adminPort, listeners.mock.address.port to listeners.admin.address.port)
        assertEquals(MOCK to ADMIN, answerOnLoopback(mockPort) to answerOnLoopback(adminPort))
    }

    @Test
    fun byDefaultTheMockListenerIsBoundToAllAddressesAndTheAdminListenerToLoopback() {
        val listeners = start(ServerSettings(mockPort = 0, adminPort = 0))

        assertTrue(listeners.mock.socketAddress.address.isAnyLocalAddress, "${listeners.mock.socketAddress}")
        assertEquals(InetAddress.getByName("127.0.0.1"), listeners.admin.socketAddress.address)
        assertEquals("0.0.0.0:${listeners.mock.socketAddress.port}", listeners.mock.address.text())
        assertEquals("127.0.0.1:${listeners.admin.socketAddress.port}", listeners.admin.address.text())
        assertEquals(MOCK, answerOnLoopback(listeners.mock.address.port))
        assertEquals(ADMIN, answerOnLoopback(listeners.admin.address.port))
    }

    @Test
    fun byDefaultOnlyTheMockListenerAnswersThroughAnAddressBeyondLoopback() {
        val outside = OutsideAddresses.usable
        TestEnvironment.needs(outside.isNotEmpty(), "an address beyond loopback through which a program reaches itself")

        val listeners = start(ServerSettings(mockPort = 0, adminPort = 0))

        for (address in outside) {
            val ofMock = RawHttp.getIfReached(InetSocketAddress(address, listeners.mock.address.port), WAIT_MILLIS)
            val ofAdmin = RawHttp.getIfReached(InetSocketAddress(address, listeners.admin.address.port), WAIT_MILLIS)

            assertEquals(MOCK, ofMock?.body, "The mock listener through $address")
            assertNull(ofAdmin, "The admin listener through $address")
        }
    }

    @Test
    fun anAdminListenerOnAllAddressesAnswersThroughAnAddressBeyondLoopback() {
        val outside = OutsideAddresses.usable
        TestEnvironment.needs(outside.isNotEmpty(), "an address beyond loopback through which a program reaches itself")

        val listeners = start(ServerSettings(mockPort = 0, adminHost = "0.0.0.0", adminPort = 0, allowUnprotectedAdmin = true))

        for (address in outside) {
            assertEquals(
                ADMIN,
                RawHttp.getIfReached(InetSocketAddress(address, listeners.admin.address.port), WAIT_MILLIS)?.body,
                "$address",
            )
        }
    }

    @Test
    fun theListenersAreStartedAndStoppedAgainAndAgainOnTheSamePorts() {
        val (mockPort, adminPort) = RawHttp.freePorts(2)
        val settings = ServerSettings(mockHost = "127.0.0.1", mockPort = mockPort, adminPort = adminPort)

        repeat(8) { round ->
            val addresses = ListenerAddresses.of(settings)
            Listeners.requireFree(addresses)
            val listeners = Listeners.start(addresses, answering(MOCK), answering(ADMIN))
            try {
                assertEquals(MOCK to ADMIN, answerOnLoopback(mockPort) to answerOnLoopback(adminPort), "Round $round")
            } finally {
                listeners.stop(Duration.ZERO)
            }
            assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS), "The mock listener after round $round")
            assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener after round $round")
        }
    }

    // A listener that cannot be bound

    @Test
    fun aPortThatAnotherProgramListensOnIsRefusedAtOnceWithTheAddressAndThePort() {
        val inUse = blocker("127.0.0.1").localPort
        val ofMock = ListenerAddresses.of(ServerSettings(mockHost = "127.0.0.1", mockPort = inUse, adminPort = 0))
        val ofAdmin = ListenerAddresses.of(ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = inUse))

        val (mockRefusal, mockTime) = measureTimedValue { assertFailsWith<StartupFailure> { Listeners.requireFree(ofMock) } }
        val (adminRefusal, adminTime) = measureTimedValue { assertFailsWith<StartupFailure> { Listeners.requireFree(ofAdmin) } }

        assertEquals(
            "The mock listener cannot accept connections on 127.0.0.1:$inUse: Address already in use. " +
                "The address is the setting --mock-host or MOCKEY_MOCK_HOST, and the port is --mock-port or MOCKEY_MOCK_PORT.",
            mockRefusal.message,
        )
        assertEquals(
            "The admin listener cannot accept connections on 127.0.0.1:$inUse: Address already in use. " +
                "The address is the setting --admin-host or MOCKEY_ADMIN_HOST, and the port is --admin-port or MOCKEY_ADMIN_PORT.",
            adminRefusal.message,
        )
        assertTrue(
            mockTime.inWholeMilliseconds < AT_ONCE_MILLIS && adminTime.inWholeMilliseconds < AT_ONCE_MILLIS,
            "$mockTime and $adminTime",
        )
    }

    @Test
    fun aPortOfTheDefaultAddressThatIsInUseIsRefusedWithThatAddress() {
        val inUse = blocker("0.0.0.0").localPort

        val refusal = assertFailsWith<StartupFailure> { start(ServerSettings(mockPort = inUse, adminPort = 0)) }

        assertTrue(refusal.message.startsWith("The mock listener cannot accept connections on 0.0.0.0:$inUse: "), refusal.message)
    }

    @Test
    fun theTwoListenersCannotHaveOneAddressAndPort() {
        val (port) = RawHttp.freePorts(1)
        val settings = ServerSettings(mockHost = "127.0.0.1", mockPort = port, adminHost = "127.0.0.1", adminPort = port)

        val refusal = assertFailsWith<StartupFailure> { start(settings) }

        assertTrue(
            refusal.message.startsWith(
                "The admin listener cannot accept connections on 127.0.0.1:$port: Address already in use. " +
                    "The mock listener is set to the same port. ",
            ),
            refusal.message,
        )
        assertNull(RawHttp.getIfReached(RawHttp.loopback(port), WAIT_MILLIS), "No listener was started")
    }

    @Test
    fun anAddressThatIsNotOfThisMachineCannotBeBound() {
        val refusal = assertFailsWith<StartupFailure> { start(ServerSettings(mockHost = "192.0.2.1", mockPort = 0, adminPort = 0)) }

        assertTrue(refusal.message.startsWith("The mock listener cannot accept connections on 192.0.2.1:0: "), refusal.message)
        assertContains(refusal.message, "assign requested address")
    }

    @Test
    fun aCheckOfFreePortsLeavesThePortsFree() {
        val (mockPort, adminPort) = RawHttp.freePorts(2)
        val addresses = ListenerAddresses.of(ServerSettings(mockHost = "127.0.0.1", mockPort = mockPort, adminPort = adminPort))

        repeat(3) { Listeners.requireFree(addresses) }

        ServerSocket().use { it.bind(RawHttp.loopback(mockPort)) }
        ServerSocket().use { it.bind(RawHttp.loopback(adminPort)) }
    }

    /**
     * The listeners are started without the check of free ports, as when another program takes a port after
     * the check. The engine finds the port in use then. It takes seconds to say so, which is one reason for
     * the check.
     */
    @Test
    fun aListenerThatTheEngineCannotBindIsRefusedWithTheAddressAndThePortAndNoListenerStays() {
        val (mockPort) = RawHttp.freePorts(1)
        val inUse = blocker("127.0.0.1").localPort
        val addresses = ListenerAddresses.of(ServerSettings(mockHost = "127.0.0.1", mockPort = mockPort, adminPort = inUse))

        val refusal = assertFailsWith<StartupFailure> { Listeners.start(addresses, answering(MOCK), answering(ADMIN)) }

        assertEquals(
            "The admin listener cannot accept connections on 127.0.0.1:$inUse: Address already in use. " +
                "The address is the setting --admin-host or MOCKEY_ADMIN_HOST, and the port is --admin-port or MOCKEY_ADMIN_PORT.",
            refusal.message,
        )
        assertNull(
            RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS),
            "The mock listener, which had been started, was stopped again",
        )
        ServerSocket().use { it.bind(RawHttp.loopback(mockPort)) }
    }

    // Stopping

    /**
     * The listeners end the handler of a request that they do not wait for any longer. A handler can take
     * a while to end, and the listeners have stopped only when it has: a handler that went on would find
     * nothing left to answer with.
     */
    @Test
    fun theListenersHaveStoppedOnlyWhenTheHandlersOfTheRequestsInProgressHaveEnded() {
        val atWork = CountDownLatch(1)
        val ended = AtomicBoolean(false)
        val slowToEnd: suspend Application.() -> Unit = {
            intercept(ApplicationCallPipeline.Call) {
                try {
                    atWork.countDown()
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { delay(ENDING_MILLIS) }
                    ended.set(true)
                }
            }
        }
        val addresses = ListenerAddresses.of(ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0))
        val listeners = Listeners.start(addresses, slowToEnd, answering(ADMIN))
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(listeners.mock.address) }
        val handlerAtWork = atWork.await(AT_ONCE_MILLIS, TimeUnit.MILLISECONDS)

        val abandoned = listeners.stop(Duration.ZERO)
        val endedWhenStopped = ended.get()

        assertTrue(handlerAtWork, "The handler got the request")
        assertEquals(1, abandoned, "The connections with a request in progress")
        assertTrue(endedWhenStopped, "The handler was at work when the listeners had stopped")
        assertTrue(inProgress.get(AT_ONCE_MILLIS, TimeUnit.MILLISECONDS).nothing, "The client got an answer")
    }

    private companion object {
        const val MOCK = "the mock listener"
        const val ADMIN = "the admin listener"

        /** The time that the handler of a test takes to end when it is told to. */
        const val ENDING_MILLIS = 300L

        /** How long a test waits for an answer that is not to come. */
        const val WAIT_MILLIS = 500

        /** The longest time that something takes which happens at once. */
        const val AT_ONCE_MILLIS = 1_000L
    }
}
