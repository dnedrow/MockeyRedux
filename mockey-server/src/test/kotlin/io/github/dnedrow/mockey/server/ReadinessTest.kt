package io.github.dnedrow.mockey.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The server is ready before it serves: it answers nothing before it has its definitions, the stored ones
 * and the initial ones, so that the first status that a client gets means that the services answer.
 */
class ReadinessTest {
    private val levelBefore = ServerLogging.level()
    private val servers = mutableListOf<MockeyServer>()
    private val directories = mutableListOf<Path>()
    private val clients = mutableListOf<CompletableFuture<FirstAnswers>>()

    @AfterTest
    fun endWhatTheTestStarted() {
        clients.forEach { it.cancel(false) }
        servers.forEach(MockeyServer::close)
        directories.forEach { it.toFile().deleteRecursively() }
        ServerLogging.configure(levelBefore)
    }

    /** Returns a definitions location that holds the service `Customers`. */
    private fun definitionsLocation(): Path =
        Files.createTempDirectory("mockey-readiness-test").also {
            directories.add(it)
            StoredDefinitions.customers(it)
        }

    private fun settings(
        mockPort: Int,
        adminPort: Int,
        definitions: Path,
    ) = ServerSettings(
        mockHost = "127.0.0.1",
        mockPort = mockPort,
        adminPort = adminPort,
        definitions = definitions,
        logLevel = LogLevel.WARN,
    )

    /** What a client got that asked a server for its status from before the server started. */
    private class FirstAnswers(
        /** How often the client asked before the server gave an answer. */
        val inVain: Int,
        /** The first answer that the server gave to the question for its status. */
        val status: RawHttp.Answer,
        /** The answer to the request that the client sent as soon as it had the status. */
        val next: RawHttp.Answer,
    )

    /**
     * Starts a client that asks the server at [address] for its status, again and again, until the server
     * answers, and that sends [request] as soon as it has that answer. Returns when the client has asked
     * for the first time, which is before the server is started.
     */
    private fun askFromNowOn(
        address: InetSocketAddress,
        request: ByteArray,
    ): CompletableFuture<FirstAnswers> {
        val answers = CompletableFuture<FirstAnswers>().also { clients += it }
        val asked = CountDownLatch(1)
        thread(isDaemon = true, name = "status-client") {
            try {
                var inVain = 0
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS)
                while (!answers.isDone) {
                    val status = RawHttp.getIfReached(address, ANSWER_MILLIS, StatusEndpoint.PATH)
                    if (status != null) {
                        answers.complete(FirstAnswers(inVain, status, RawHttp.exchange(address, request)))
                        break
                    }
                    inVain++
                    asked.countDown()
                    check(System.nanoTime() < deadline) { "The server gave no status within $TIME_LIMIT_SECONDS seconds." }
                    Thread.sleep(PAUSE_MILLIS)
                }
            } catch (failure: Exception) {
                answers.completeExceptionally(failure)
            } finally {
                asked.countDown()
            }
        }
        assertTrue(asked.await(TIME_LIMIT_SECONDS, TimeUnit.SECONDS), "The client asked for the status")
        return answers
    }

    /** Waits for what [client] got. [context] says what the server did, for the case that the client got nothing. */
    private fun whatItGot(
        client: CompletableFuture<FirstAnswers>,
        context: () -> String = { "" },
    ): FirstAnswers =
        try {
            client.get(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            fail("The client got no status within $TIME_LIMIT_SECONDS seconds. ${context()}")
        }

    /**
     * Checks that [answers] are the status of a running server, started between [before] and now, and the
     * answer of a service with the body [ofService].
     */
    private fun assertReady(
        answers: FirstAnswers,
        before: Instant,
        ofService: String = StoredDefinitions.CUSTOMER,
    ) {
        assertEquals("HTTP/1.1 200 OK", answers.status.statusLine, "The first answer to the question for the status")
        val status = Json.parseToJsonElement(answers.status.body) as JsonObject
        assertEquals(JsonPrimitive("running"), status["status"])
        val since = Instant.parse((status.getValue("since") as JsonPrimitive).content)
        assertTrue(since in before.truncatedTo(ChronoUnit.MILLIS)..Instant.now(), "The server runs since $since, started after $before")
        assertEquals(200 to ofService, answers.next.status to answers.next.body, "The answer of the service")
        assertTrue(answers.inVain > 0, "The client asked before the server answered")
    }

    /** The scenario "Status means ready", for a server in the process of the test. */
    @Test
    fun aClientThatGetsTheFirstStatusGetsTheAnswerOfAStoredService() {
        val (mockPort, adminPort) = RawHttp.freePortsThatNoClientGets(2)
        val definitions = definitionsLocation()
        val client = askFromNowOn(RawHttp.loopback(mockPort), RawHttp.request("GET", "/service/customer/7"))
        val before = Instant.now()

        servers += MockeyServer.start(settings(mockPort, adminPort, definitions))

        assertReady(whatItGot(client), before)
    }

    /** The scenario "Status means ready", for the program. */
    @Test
    fun aClientThatGetsTheFirstStatusOfTheProgramGetsTheAnswerOfAStoredService() {
        val (mockPort, adminPort) = RawHttp.freePortsThatNoClientGets(2)
        val arguments = arrayOf("--mock-host", "127.0.0.1", "--mock-port", "$mockPort", "--admin-port", "$adminPort")
        val client = askFromNowOn(RawHttp.loopback(mockPort), RawHttp.request("GET", "/service/customer/7"))
        val before = Instant.now()

        ServerProgram.start(*arguments, prepare = { StoredDefinitions.customers(it.resolve("mockey-definitions")) }).use { program ->
            assertReady(whatItGot(client) { "The program wrote: ${program.output}${program.errors}" }, before)
            assertEquals("", program.errors)
        }
    }

    /** The scenario "Status means ready", for a server that starts with initial definitions, and with their default plan. */
    @Test
    fun aClientThatGetsTheFirstStatusGetsTheAnswerOfAServiceOfTheInitialDefinitions() {
        val (mockPort, adminPort) = RawHttp.freePortsThatNoClientGets(2)
        val definitions = definitionsLocation()
        val source =
            Files.writeString(
                definitions.resolveSibling("${definitions.fileName}.xml"),
                LegacySources.export(withDefaultPlan = true),
            )
        directories.add(source)
        val client = askFromNowOn(RawHttp.loopback(mockPort), RawHttp.request("GET", "/service/legacy/orders"))
        val before = Instant.now()

        servers += MockeyServer.start(settings(mockPort, adminPort, definitions).copy(initialDefinitions = source.toString()))

        assertReady(whatItGot(client), before, LegacySources.GOLD_ORDERS)
    }

    /**
     * The server asks another server for its initial definitions, and that server holds its answer back. So
     * the server is held at the moment at which it has loaded its stored definitions and does not have the
     * initial ones. In that moment nothing accepts a connection, and no stored service answers.
     */
    @Test
    fun noListenerAcceptsAConnectionWhileTheInitialDefinitionsAreRead() {
        val (mockPort, adminPort) = RawHttp.freePortsThatNoClientGets(2)
        val asked = CountDownLatch(1)
        val released = CountDownLatch(1)
        val source =
            StubUpstream(body = LegacySources.export()) {
                asked.countDown()
                released.await()
            }
        val settings = settings(mockPort, adminPort, definitionsLocation()).copy(initialDefinitions = "${source.origin}/export")
        val starting = CompletableFuture<MockeyServer>()
        thread(isDaemon = true, name = "starting-server") {
            try {
                starting.complete(MockeyServer.start(settings))
            } catch (failure: Exception) {
                starting.completeExceptionally(failure)
            }
        }

        try {
            assertTrue(asked.await(TIME_LIMIT_SECONDS, TimeUnit.SECONDS), "The server asked for its initial definitions")
            assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS, "/status"), "The status")
            assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS, "/service/customer/7"), "Mock traffic")
            assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener")
            assertFalse(starting.isDone, "The server has started without its initial definitions")
            released.countDown()
            val server = starting.get(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)

            val ofService = RawHttp.get(server.listeners.mock.address, "/service/legacy/orders")
            assertEquals(200, RawHttp.get(server.listeners.mock.address, "/status").status)
            assertEquals(200 to LegacySources.SILVER_ORDERS, ofService.status to ofService.body)
            assertEquals(404, RawHttp.get(server.listeners.mock.address, "/service/customer/7").status)
        } finally {
            released.countDown()
            source.close()
            starting.whenComplete { server, _ -> server?.close() }
        }
    }

    /**
     * The server reads the main file of its definitions from a named pipe, and so it is held at the moment
     * at which it loads its definitions. In that moment nothing accepts a connection: not for the status,
     * not for mock traffic, and not on the admin listener.
     */
    @Test
    fun noListenerAcceptsAConnectionWhileTheDefinitionsAreLoaded() {
        val (mockPort, adminPort) = RawHttp.freePortsThatNoClientGets(2)
        val definitions = definitionsLocation()
        val mainFile = definitions.resolve("mockey.json")
        val main = Files.readAllBytes(mainFile)
        Files.delete(mainFile)
        TestEnvironment.needs(NamedPipe.create(mainFile), "named pipes, which the command mkfifo makes")
        val starting = CompletableFuture<MockeyServer>()
        thread(isDaemon = true, name = "starting-server") {
            try {
                starting.complete(MockeyServer.start(settings(mockPort, adminPort, definitions)))
            } catch (failure: Exception) {
                starting.completeExceptionally(failure)
            }
        }

        try {
            NamedPipe.awaitReader(mainFile).use { pipe ->
                assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS, "/status"), "The status")
                assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS, "/service/customer/7"), "Mock traffic")
                assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener")
                assertFalse(starting.isDone, "The server has started without its definitions")
                pipe.write(main)
            }
            val server = starting.get(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)

            val ofService = RawHttp.get(server.listeners.mock.address, "/service/customer/7")
            assertEquals(200, RawHttp.get(server.listeners.mock.address, "/status").status)
            assertEquals(200 to StoredDefinitions.CUSTOMER, ofService.status to ofService.body)
            assertEquals(404, RawHttp.get(server.listeners.admin.address).status)
        } finally {
            NamedPipe.release(mainFile)
            starting.whenComplete { server, _ -> server?.close() }
        }
    }

    private companion object {
        /** How long a test waits for something that has to come. */
        const val TIME_LIMIT_SECONDS = 60L

        /** How long a client waits for the answer of a server that it has reached. */
        const val ANSWER_MILLIS = 5_000

        /** How long a test waits for an answer that is not to come. */
        const val WAIT_MILLIS = 500

        /** How long a client waits before it asks again. A machine answers only so many connections that nothing takes. */
        const val PAUSE_MILLIS = 5L
    }
}
