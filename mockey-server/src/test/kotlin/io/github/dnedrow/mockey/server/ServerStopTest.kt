package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.server.TestDefinitions.staticService
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * How the server stops: it takes no new connection, it lets the requests that are in progress finish within
 * the grace period, and it completes what it is saving. The server runs in the process of the test, which
 * stops it as the program does on a signal.
 */
class ServerStopTest {
    private val levelBefore = ServerLogging.level()
    private val directory: Path = Files.createTempDirectory("mockey-server-stop-test")
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun endWhatTheTestStarted() {
        // A server that still waits for a request of a test that failed is not waited for.
        closeables.filterIsInstance<HeldService>().forEach(HeldService::release)
        closeables.reversed().forEach(AutoCloseable::close)
        directory.toFile().deleteRecursively()
        ServerLogging.configure(levelBefore)
    }

    /** Starts a server with [settings] on free ports of the loopback address. It says little in the log unless they say otherwise. */
    private fun start(settings: ServerSettings = ServerSettings(logLevel = LogLevel.WARN)): MockeyServer {
        val onLoopback = settings.copy(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0)
        return MockeyServer.start(onLoopback).also { closeables += it }
    }

    private fun connectionTo(address: InetSocketAddress): OpenConnection = OpenConnection(address).also { closeables += it }

    private fun heldService(): HeldService = HeldService().also { closeables += it }

    /** Stops [server] on another thread, and returns when the server has begun: it accepts no connection any more. */
    private fun stopping(
        server: MockeyServer,
        gracePeriod: Duration,
    ): CompletableFuture<Void> {
        val stopping = CompletableFuture.runAsync { server.stop(gracePeriod) }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (RawHttp.getIfReached(server.listeners.mock.address, REFUSAL_MILLIS, "/status") != null) {
            check(System.nanoTime() < end) { "The server went on accepting connections." }
        }
        return stopping
    }

    /**
     * Returns whether a client that wants a connection to [address] is refused, at once or a moment from
     * now: the system closes a socket a moment after Netty says that it is closed.
     */
    private fun refuses(address: InetSocketAddress): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < end) {
            try {
                Socket().use { it.connect(address, REFUSAL_MILLIS) }
            } catch (_: ConnectException) {
                return true
            }
        }
        return false
    }

    /** Returns whether [future] is done within [millis]. */
    private fun CompletableFuture<*>.isDoneWithin(millis: Long): Boolean =
        try {
            get(millis, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        }

    private fun <T> CompletableFuture<T>.result(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

    // Requests in progress

    @Test
    fun aRequestInProgressIsAnsweredAndTheServerStopsAsSoonAsItIs() {
        val service = heldService()
        val server = start()
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(server.listeners.mock.address, service.target) }
        service.awaitRequest()

        val stopping = stopping(server, gracePeriod = LONG_GRACE)
        val stoppedWithTheRequestInProgress = stopping.isDoneWithin(STAYING_MILLIS)
        service.release()
        val answer = inProgress.result()
        stopping.result()

        assertFalse(stoppedWithTheRequestInProgress, "The server waits for the request")
        assertEquals(200 to HeldService.ANSWER, answer.status to answer.body)
    }

    @Test
    fun noConnectionIsAcceptedOnceTheServerStops() {
        val service = heldService()
        val server = start()
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(server.listeners.mock.address, service.target) }
        service.awaitRequest()

        val stopping = stopping(server, gracePeriod = LONG_GRACE)
        val onMock = RawHttp.getIfReached(server.listeners.mock.address, REFUSAL_MILLIS, "/status")
        val onAdmin = RawHttp.getIfReached(server.listeners.admin.address, REFUSAL_MILLIS, "/status")
        val mockRefuses = refuses(server.listeners.mock.address)
        val adminRefuses = refuses(server.listeners.admin.address)
        service.release()

        assertNull(onMock, "The mock listener answered a client that came while the server stopped")
        assertNull(onAdmin, "The admin listener answered a client that came while the server stopped")
        assertTrue(mockRefuses, "The mock listener went on taking connections while the server waited for a request")
        assertTrue(adminRefuses, "The admin listener went on taking connections while the server waited for a request")
        assertEquals(200, inProgress.result().status)
        stopping.result()
    }

    @Test
    fun aConnectionWithoutARequestInProgressIsClosedAtOnceAndTheServerDoesNotWaitForTheGracePeriod() {
        val server = start()
        val used = connectionTo(server.listeners.mock.address)
        used.get("/status")
        val answer = used.response()
        val unused = connectionTo(server.listeners.admin.address)

        val took = measureTime { server.stop(LONG_GRACE) }

        assertEquals(200 to false, answer.status to answer.ended)
        assertTrue(took < WAIT_SECONDS.seconds, "The server took $took to stop")
        assertTrue(used.ended(), "The connection that had answered a request stayed open")
        assertTrue(unused.ended(), "The connection that had no request stayed open")
    }

    /** Only a connection with a request in progress is one that the server has to say something about when it stops at once. */
    @Test
    fun aServerThatStopsAtOnceSaysNothingAboutTheConnectionsWithoutARequest() {
        val server = start()
        val idle =
            List(CONNECTIONS) {
                connectionTo(server.listeners.mock.address).also {
                    it.get("/status")
                    it.response()
                }
            }

        val log = TestLog.of { server.close() }

        assertEquals(emptyList(), TestLog.messages(log))
        assertTrue(idle.all { it.ended() }, "A connection stayed open")
    }

    @Test
    fun aRequestThatIsInProgressWhenTheGracePeriodIsOverGetsNoAnswer() {
        val service = heldService()
        val server = start()
        val inProgress = List(2) { CompletableFuture.supplyAsync { RawHttp.get(server.listeners.mock.address, service.target) } }
        service.awaitRequests(2)
        val gracePeriod = 500.milliseconds

        var took = Duration.ZERO
        val log = TestLog.of { took = measureTime { server.stop(gracePeriod) } }
        val answers = inProgress.map { it.result() }
        service.release()

        assertTrue(took >= gracePeriod, "The server stopped after $took")
        assertTrue(took < WAIT_SECONDS.seconds, "The server stopped after $took")
        assertTrue(answers.all { it.nothing && it.ended }, "The clients got $answers")
        assertEquals(listOf("2 connections still had a request in progress after 500ms and got no answer."), TestLog.messages(log))
        assertEquals("", TestLog.of { Thread.sleep(STAYING_MILLIS) }, "The handlers of the requests had ended when the server had stopped")
    }

    /** The grace period is one time for the whole server, and not one for each of its listeners. */
    @Test
    fun theRequestsOfBothListenersHaveTheSameGracePeriod() {
        val service = heldService()
        val server = start()
        val onMock = CompletableFuture.supplyAsync { RawHttp.get(server.listeners.mock.address, service.target) }
        service.awaitRequest()
        val onAdmin = connectionTo(server.listeners.admin.address)
        onAdmin.send(
            listOf("POST /arriving HTTP/1.1", "Host: mockey.test", "Content-Length: $BODY_BYTES", "", "half").joinToString(RawHttp.CRLF),
        )
        val answered = onAdmin.response()
        val gracePeriod = 1.seconds

        val took = measureTime { server.stop(gracePeriod) }

        assertEquals(404, answered.status, "The admin listener answered the request before it had arrived whole")
        assertTrue(took >= gracePeriod, "The server stopped after $took")
        assertTrue(took < gracePeriod * 1.8, "The server stopped after $took")
        assertTrue(onMock.result().nothing, "The client of the mock listener got an answer")
    }

    @Test
    fun aServerThatStopsWithoutAGracePeriodEndsARequestInProgressAtOnce() {
        val service = heldService()
        val server = start()
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(server.listeners.mock.address, service.target) }
        service.awaitRequest()

        val took = measureTime { server.close() }
        val answer = inProgress.result()

        assertTrue(took < WAIT_SECONDS.seconds, "The server stopped after $took")
        assertTrue(answer.nothing && answer.ended, "The client got $answer")
    }

    /**
     * The handler of a request is done before the answer is written. An answer that is larger than what the
     * system and the client take at once stays with the server until the client reads, and the server
     * waits for that.
     */
    @Test
    fun anAnswerThatTheClientTakesSlowlyArrivesWhole() {
        val block = "0123456789abcdef"
        val server = start()
        runBlocking { server.engine.staticService("Large", "large", "only" to block.repeat(LARGE_BODY_BYTES / block.length)) }
        val client = connectionTo(server.listeners.mock.address)
        client.get("/service/large")
        val head = client.head()

        val stopping = stopping(server, gracePeriod = LONG_GRACE)
        val stoppedBeforeTheClientRead = stopping.isDoneWithin(STAYING_MILLIS)
        var position = 0
        var differences = 0
        val arrived =
            client.body(head) { piece, count ->
                for (index in 0 until count) if (piece[index] != block[(position + index) % block.length].code.toByte()) differences++
                position += count
            }
        stopping.result()

        assertFalse(stoppedBeforeTheClientRead, "The server waits until the answer is written")
        assertEquals(200 to false, head.status to head.ended)
        assertEquals(LARGE_BODY_BYTES, arrived, "The bytes of the body that arrived")
        assertEquals(0, differences, "The bytes of the body that are not the ones of the scenario")
        assertTrue(client.ended(), "The connection stayed open")
    }

    /**
     * The server answers some requests before it has read their body. Such a request is in progress until
     * its body has arrived: a client that still sends when its connection is closed can lose the answer.
     */
    @Test
    fun aRequestThatIsAnsweredBeforeItHasArrivedWholeIsInProgressUntilItHasArrived() {
        val server = start()
        val client = connectionTo(server.listeners.mock.address)
        val body = "b".repeat(BODY_BYTES)
        client.send(listOf("POST /status HTTP/1.1", "Host: mockey.test", "Content-Length: $BODY_BYTES", "", "").joinToString(RawHttp.CRLF))
        client.send(body.take(BODY_BYTES / 2))
        val answer = client.response()

        val stopping = stopping(server, gracePeriod = LONG_GRACE)
        val stoppedBeforeTheBodyArrived = stopping.isDoneWithin(STAYING_MILLIS)
        client.send(body.drop(BODY_BYTES / 2))
        val ended = client.ended()
        stopping.result()

        assertEquals(200 to false, answer.status to answer.ended)
        assertFalse(stoppedBeforeTheBodyArrived, "The server waits for the rest of the request")
        assertTrue(ended, "The connection stayed open")
    }

    // What the server is saving

    @Test
    fun aSaveOfTheDefinitionsThatIsUnderWayIsCompletedBeforeTheServerHasStopped() {
        val location = directory.resolve("mockey-definitions")
        val mainFile = location.resolve("mockey.json")
        val server = start(ServerSettings(definitions = location, readOnly = false, logLevel = LogLevel.WARN))
        val document = TestDefinitions.document { repeat(SERVICES) { staticService("Service $it", "service/$it", "only" to "answer $it") } }
        val saving = CompletableFuture.supplyAsync { runBlocking { server.engine.importDocument(document) } }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!Files.isDirectory(location.resolve("services"))) check(System.nanoTime() < end) { "The server stored nothing." }

        val wholeWhenToldToStop = Files.exists(mainFile)
        server.stop(Duration.ZERO)
        val wholeWhenStopped = Files.exists(mainFile)

        assertFalse(wholeWhenToldToStop, "The save was under way when the server was told to stop")
        assertTrue(wholeWhenStopped, "The save was complete when the server had stopped")
        assertTrue(saving.result() is ChangeResult.Changed, "The change that was being saved took effect")
        assertEquals(SERVICES, runBlocking { FileDefinitionStorage(location).load() }.services.size)
    }

    // A server that has stopped

    @Test
    fun aServerThatHasStoppedStaysStoppedAndSaysSoOnce() {
        val server = start(ServerSettings(logLevel = LogLevel.INFO))
        val mock = server.listeners.mock.address

        val log =
            TestLog.of {
                server.stop(2.seconds)
                server.stop(2.seconds)
                server.close()
            }

        val said =
            listOf(
                "The server stops. It accepts no more connections, and the requests in progress have 2s to finish.",
                "The server has stopped.",
            )
        assertEquals(said, TestLog.messages(log))
        assertNull(RawHttp.getIfReached(mock, REFUSAL_MILLIS, "/status"))
    }

    // The connection of the last request

    @Test
    fun theAnswerToTheLastRequestOfAConnectionSaysThatTheConnectionCloses() {
        val service = heldService()
        val server = start()
        val client = connectionTo(server.listeners.mock.address)
        client.get("/status")
        val whileRunning = client.response()
        client.get(service.target)
        service.awaitRequest()

        val stopping = stopping(server, gracePeriod = LONG_GRACE)
        service.release()
        val last = client.response()
        stopping.result()

        assertEquals(emptyList(), whileRunning.headerValues("Connection"), "A server that runs keeps the connection")
        assertEquals(200 to HeldService.ANSWER, last.status to last.body)
        assertEquals(listOf("close"), last.headerValues("Connection"))
        assertTrue(client.ended(), "The connection stayed open")
    }

    /** A client can send a request before it has the answer to the one before. The answers come in the order of the requests. */
    @Test
    fun ofTheRequestsThatAConnectionHasInProgressOnlyTheAnswerToTheLastSaysThatTheConnectionCloses() {
        val service = heldService()
        val server = start()
        val client = connectionTo(server.listeners.mock.address)
        val requests =
            listOf("GET ${service.target} HTTP/1.1", "Host: mockey.test", "", "GET /status HTTP/1.1", "Host: mockey.test", "", "")
        client.send(requests.joinToString(RawHttp.CRLF))
        service.awaitRequest()

        val stopping = stopping(server, gracePeriod = LONG_GRACE)
        service.release()
        val first = client.response()
        val second = client.response()
        stopping.result()

        assertEquals(200 to HeldService.ANSWER, first.status to first.body)
        assertEquals(emptyList(), first.headerValues("Connection"))
        assertEquals(200, second.status)
        assertContains(second.body, "\"status\":\"running\"")
        assertEquals(listOf("close"), second.headerValues("Connection"))
        assertTrue(client.ended(), "The connection stayed open")
    }

    private companion object {
        const val WAIT_SECONDS = 10L

        /** The time within which a client that the server does not serve finds that out. */
        const val REFUSAL_MILLIS = 500

        /** The time for which a server that waits is watched. */
        const val STAYING_MILLIS = 400L
        const val LARGE_BODY_BYTES = 32 * 1024 * 1024
        const val BODY_BYTES = 1_000
        const val CONNECTIONS = 30

        /**
         * So many services that the server takes a while to store them: it writes each one for good before it
         * goes on, which takes some milliseconds.
         */
        const val SERVICES = 200

        /** A grace period that no test waits for: a server that stops with it stops because nothing is in progress. */
        val LONG_GRACE: Duration = 60.seconds
    }
}
