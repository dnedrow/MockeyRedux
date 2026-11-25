package io.github.dnedrow.mockey.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.localPort
import io.ktor.server.routing.port
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.epoll.Epoll
import io.netty.channel.kqueue.KQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The spike of task 11.0: what a request is when it reaches a handler of Ktor, on the Netty engine and on the
 * CIO engine, and what else the two engines do that the server has to know. The tests state the facts that
 * `docs/decisions/http-stack.md` rests on.
 *
 * The tests talk to servers that listen on the loopback address: over HTTP with the HTTP client of the JDK,
 * and over a socket where that client would not send a request as a test needs it. The Netty server of a test
 * is the one that the server program runs, see [HttpEngine], unless the test says otherwise.
 */
class HttpStackSpikeTest {
    /** The request targets that the handler of [echo] got, in their order. */
    private val received = ConcurrentLinkedQueue<String>()

    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterTest
    fun closeTheClient() {
        client.close()
    }

    /** The two engines of Ktor that the spike compares. */
    private enum class Engine {
        NETTY,
        CIO,
    }

    /** A server that runs for a test. It is stopped when the test is done with it. */
    private class Running(
        private val server: EmbeddedServer<*, *>,
    ) : AutoCloseable {
        /** The ports on which the server listens, one for each of its connectors. */
        val ports: List<Int> = runBlocking { server.engine.resolvedConnectors().map { it.port } }

        val port: Int
            get() = ports.first()

        private var stopped = false

        /** Stops the server as Ktor stops it, with [graceMillis] for what is under way. */
        fun stop(graceMillis: Long) {
            stopped = true
            server.stop(graceMillis, STOP_TIMEOUT_MILLIS)
        }

        override fun close() {
            if (!stopped) stop(graceMillis = 0)
        }
    }

    /** Returns the module of a server with one handler and no routing. The handler answers every request with [answer]. */
    private fun handler(answer: suspend (PipelineCall) -> Unit): suspend Application.() -> Unit =
        { intercept(ApplicationCallPipeline.Call) { answer(call) } }

    /** A handler that notes the request target as the engine gives it, and answers with that target. */
    private val echo =
        handler { call ->
            val target = call.request.local.uri
            received += target
            call.respondText(target)
        }

    /** A handler that answers every request with [text]. */
    private fun answering(text: String) = handler { call -> call.respondText(text) }

    /** A handler that tells [entered] that it has a request, and answers `done` after [millis]. */
    private fun slow(
        entered: CountDownLatch,
        millis: Long,
    ) = handler { call ->
        entered.countDown()
        delay(millis)
        call.respondText("done")
    }

    /** Starts a server of [factory] that runs [module] on the loopback address, with one connector for each of [ports]. */
    private fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> startOn(
        factory: ApplicationEngineFactory<E, C>,
        ports: List<Int> = listOf(0),
        module: suspend Application.() -> Unit = echo,
        configure: C.() -> Unit = {},
    ): Running {
        val server =
            embeddedServer(
                factory,
                serverConfig {
                    developmentMode = false
                    module(body = module)
                },
            ) {
                ports.forEach { port ->
                    connector {
                        host = LOOPBACK
                        this.port = port
                    }
                }
                configure()
            }
        return Running(server.start(wait = false))
    }

    /**
     * Starts a server on [engine] that runs [module], on a port that the system picks. On Netty it is the
     * server of the program, with the settings of [HttpEngine].
     */
    private fun start(
        engine: Engine,
        module: suspend Application.() -> Unit = echo,
    ): Running =
        when (engine) {
            Engine.NETTY -> Running(HttpEngine.server(LOOPBACK, 0, module).start(wait = false))
            Engine.CIO -> startOn(CIO, module = module)
        }

    /** Starts a server on [engine] as Ktor sets it up when it is told nothing, with one connector for each of [ports]. */
    private fun startPlain(
        engine: Engine,
        ports: List<Int> = listOf(0),
        module: suspend Application.() -> Unit = echo,
    ): Running =
        when (engine) {
            Engine.NETTY -> startOn(Netty, ports, module)
            Engine.CIO -> startOn(CIO, ports, module)
        }

    /** Sends a GET request for [target] over HTTP, and returns the status and the body of the response. */
    private fun get(
        port: Int,
        target: String,
    ): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://$LOOPBACK:$port").resolve(target)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /**
     * Returns the bytes of a request as a client writes them to a connection. Every character of the request
     * is one byte. A request with a [body] says how long the body is.
     */
    private fun request(
        method: String,
        target: String,
        headers: List<String> = listOf("Host: mockey.test", "Connection: close"),
        body: String = "",
    ): ByteArray {
        val length = if (body.isEmpty()) emptyList() else listOf("Content-Length: ${body.length}")
        return (
            listOf(
                "$method $target HTTP/1.1",
            ) + headers + length + listOf("", body)
        ).joinToString(CRLF).toByteArray(StandardCharsets.ISO_8859_1)
    }

    /** What a server wrote to a connection, and whether it [ended] the connection. Every byte is one character. */
    private class Answer(
        bytes: ByteArray,
        val ended: Boolean,
    ) {
        private val text = String(bytes, StandardCharsets.ISO_8859_1)
        private val head = text.substringBefore(CRLF + CRLF).split(CRLF)

        /** Whether the server wrote nothing at all. */
        val nothing: Boolean = bytes.isEmpty()
        val statusLine: String = head.first()
        val headers: List<String> = head.drop(1)
        val body: String = text.substringAfter(CRLF + CRLF, missingDelimiterValue = "")
    }

    /**
     * Writes [request] to a new connection to [port], and returns what the server writes until it ends the
     * connection, or until it has written nothing for [waitMillis].
     */
    private fun exchange(
        port: Int,
        request: ByteArray,
        waitMillis: Int = WAIT_MILLIS,
    ): Answer =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), WAIT_MILLIS)
            socket.soTimeout = waitMillis
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            val ended =
                try {
                    generateSequence { socket.getInputStream().read(buffer).takeIf { it >= 0 } }.forEach { bytes.write(buffer, 0, it) }
                    true
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: SocketException) {
                    true
                }
            Answer(bytes.toByteArray(), ended)
        }

    /** Sends a GET request for each of [targets] over HTTP, and checks that the handler got each target as it was sent. */
    private fun assertArriveOverHttp(
        engine: Engine,
        targets: List<String>,
    ) {
        start(engine).use { server ->
            val answers = targets.map { get(server.port, it) }
            assertEquals(targets.map { 200 to it }, answers, "$engine")
        }
        assertEquals(targets, received.toList(), "$engine")
        received.clear()
    }

    /** Writes a GET request for each of [targets] to a connection, and checks that the handler got each target as it was sent. */
    private fun assertArriveOverASocket(
        engine: Engine,
        targets: List<String>,
    ) {
        start(engine).use { server ->
            val answers = targets.map { exchange(server.port, request("GET", it)) }
            assertEquals(targets.map { OK to it }, answers.map { it.statusLine to it.body }, "$engine")
        }
        assertEquals(targets, received.toList(), "$engine")
        received.clear()
    }

    /** Returns a target of [length] characters. */
    private fun targetOf(length: Int): String = SERVICE + "a".repeat(length - SERVICE.length)

    /**
     * Returns the request target of every request of the recorded routing cases: of the request whose
     * response is recorded, and of the requests that are sent before it.
     */
    private fun recordedRoutingTargets(): List<String> {
        val cases = Files.list(RECORDINGS).use { all -> all.filter { it.fileName.toString().startsWith("routing-") }.sorted().toList() }
        val requests =
            cases.flatMap { case ->
                Files.list(case).use { files -> files.filter { it.fileName.toString().endsWith("request.properties") }.sorted().toList() }
            }
        return requests.map { file ->
            val properties = Properties()
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use(properties::load)
            checkNotNull(properties.getProperty("target")) { "$file names no target." }
        }
    }

    // The request target

    @Test
    fun theTargetsOfTheRecordedRoutingCasesReachTheHandlerAsTheyWereSent() {
        val targets = recordedRoutingTargets()

        assertTrue(targets.size > 40, "The recorded routing cases hold ${targets.size} requests.")
        assertContains(targets, "/service/http://127.0.0.1:18091/hello")
        assertContains(targets, "/service//norm.invalid/plain")
        Engine.entries.forEach { engine -> assertArriveOverHttp(engine, targets) }
    }

    @Test
    fun encodedSlashesAndEncodedPercentSignsReachTheHandlerAsTheyWereSent() {
        val targets =
            listOf(
                "/service/norm.invalid%2Fplain",
                "/service/http:%2F%2Fapi.example.com%2Fusers",
                "/service/a%25b",
                "/service/a%252Fb",
                "/service/100%25?discount=5%25&path=a%2Fb&plus=a+b",
                "/service//two//slashes//",
            )

        Engine.entries.forEach { engine -> assertArriveOverHttp(engine, targets) }
    }

    @Test
    fun aMalformedPercentEscapeAndUnencodedBracesReachTheHandlerAsTheyWereSent() {
        val targets =
            listOf(
                "/service/a%zzb",
                "/service/100%",
                "/service/a%2",
                "/service/customer/{customerId}",
                "/service/http://api.example.com/hotels/{hotel}/bookings/{booking}?id={id}",
                "/service/a|b\\c^d`e\"f<g>h",
            )

        Engine.entries.forEach { engine -> assertArriveOverASocket(engine, targets) }
    }

    @Test
    fun aTargetLongerThan4096CharactersReachesTheHandlerOfTheNettyServerOfTheProgram() {
        val targets = listOf(4_097, 10_000, 60_000).map(::targetOf)

        assertArriveOverHttp(Engine.NETTY, targets)

        val refused = start(Engine.NETTY).use { exchange(it.port, request("GET", targetOf(HttpEngine.MAX_REQUEST_LINE_LENGTH))) }
        assertContains(refused.statusLine, " 400 ")
        assertEquals(emptyList(), received.toList())
    }

    @Test
    fun nettyTakesNoRequestLineLongerThan4096CharactersUnlessItIsToldTo() {
        val (taken, refused) =
            startPlain(Engine.NETTY).use { server ->
                exchange(server.port, request("GET", targetOf(4_000))) to exchange(server.port, request("GET", targetOf(4_097)))
            }

        assertEquals(OK, taken.statusLine)
        assertContains(refused.statusLine, " 400 ")
        assertEquals(listOf(targetOf(4_000)), received.toList())
    }

    @Test
    fun cioTakesNoRequestLineLongerThan8192Characters() {
        val (taken, refused) =
            start(Engine.CIO).use { server ->
                exchange(server.port, request("GET", targetOf(8_000))) to exchange(server.port, request("GET", targetOf(8_193)))
            }

        assertEquals(OK, taken.statusLine)
        assertContains(refused.statusLine, " 400 ")
        assertEquals(listOf(targetOf(8_000)), received.toList())
    }

    /**
     * A byte that is no ASCII character must not be in a request target, and clients send such bytes all the
     * same. Netty gives each byte as the character with its number, so nothing of the target is lost. CIO
     * reads the bytes as UTF-8, and a byte that is no UTF-8 is gone.
     */
    @Test
    fun bytesOfATargetThatAreNoAsciiArriveAsTheyAreOnNettyAndAsUtf8OnCio() {
        val inUtf8 = "/service/caf\u00C3\u00A9"
        val inLatin1 = "/service/caf\u00E9"

        start(Engine.NETTY).use { server -> listOf(inUtf8, inLatin1).forEach { exchange(server.port, request("GET", it)) } }
        val onNetty = received.toList()
        received.clear()
        start(Engine.CIO).use { server -> listOf(inUtf8, inLatin1).forEach { exchange(server.port, request("GET", it)) } }
        val onCio = received.toList()

        assertEquals(listOf(inUtf8, inLatin1), onNetty)
        assertEquals(listOf("/service/caf\u00E9", "/service/caf\uFFFD"), onCio)
    }

    // The routing of Ktor, which the mock endpoint does without

    @Test
    fun theRoutingOfKtorDropsEmptyPathSegmentsAndRefusesAMalformedPercentEscape() {
        val routed = ConcurrentLinkedQueue<String>()
        val module: suspend Application.() -> Unit = {
            routing {
                get("/a/b") {
                    routed += "a/b"
                    call.respondText("routed")
                }
                get("/p/{x}") {
                    routed += "x=${call.parameters["x"]}"
                    call.respondText("routed")
                }
            }
        }

        Engine.entries.forEach { engine ->
            val statusLines =
                start(engine, module).use { server ->
                    listOf("/a//b", "/p/a%2Fb", "/p/%zz").map { exchange(server.port, request("GET", it)).statusLine }
                }

            assertEquals(listOf(OK, OK, "HTTP/1.1 400 Bad Request"), statusLines, "$engine")
            assertEquals(listOf("a/b", "x=a/b"), routed.toList(), "$engine")
            routed.clear()
        }
    }

    // Listeners

    @Test
    fun aForgedHostHeaderReachesARouteGuardedByPortButNotARouteGuardedByLocalPort() {
        Engine.entries.forEach { engine ->
            val (mock, admin) = RawHttp.freePorts(2)
            val module: suspend Application.() -> Unit = {
                routing {
                    port(admin) { get("/by-port") { call.respondText("admin") } }
                    localPort(admin) { get("/by-local-port") { call.respondText("admin") } }
                }
            }

            fun statusLine(
                connector: Int,
                path: String,
                hostPort: Int,
            ) = exchange(connector, request("GET", path, listOf("Host: $LOOPBACK:$hostPort", "Connection: close"))).statusLine

            startPlain(engine, listOf(mock, admin), module).use {
                assertEquals(OK, statusLine(admin, "/by-port", hostPort = admin), "$engine")
                assertEquals(OK, statusLine(admin, "/by-local-port", hostPort = admin), "$engine")
                assertEquals(NOT_FOUND, statusLine(mock, "/by-port", hostPort = mock), "$engine")
                assertEquals(NOT_FOUND, statusLine(mock, "/by-local-port", hostPort = mock), "$engine")

                assertEquals(OK, statusLine(mock, "/by-port", hostPort = admin), "$engine")
                assertEquals(NOT_FOUND, statusLine(mock, "/by-local-port", hostPort = admin), "$engine")
            }
        }
    }

    @Test
    fun twoServersInOneProcessStartOnPortsThatTheSystemPicks() {
        Engine.entries.forEach { engine ->
            start(engine, answering("mock")).use { mock ->
                start(engine, answering("admin")).use { admin ->
                    assertTrue(mock.port > 0 && admin.port > 0, "$engine")
                    assertNotEquals(mock.port, admin.port, "$engine")
                    assertEquals(200 to "mock", get(mock.port, "/api/v1/services"), "$engine")
                    assertEquals(200 to "admin", get(admin.port, "/api/v1/services"), "$engine")
                }
            }
        }
    }

    @Test
    fun aPortThatIsTakenFailsTheStartWithABindException() {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { taken ->
            val onNetty = assertFails { HttpEngine.server(LOOPBACK, taken.localPort, echo).start(wait = false) }
            val onCio = assertFails { startOn(CIO, listOf(taken.localPort)) }

            assertIs<BindException>(onNetty)
            assertTrue(generateSequence(onCio) { it.cause }.any { it is BindException }, "$onCio")
        }
    }

    // Requests

    @Test
    fun everyMethodReachesTheHandlerAsItWasWrittenWithItsBody() {
        val seen = ConcurrentLinkedQueue<Pair<String, String>>()
        val module =
            handler { call ->
                seen += call.request.local.method.value to String(call.receiveChannel().toByteArray(), StandardCharsets.UTF_8)
                call.respondText("ok")
            }
        val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "PURGE", "PROPFIND", "M-SEARCH", "get")

        Engine.entries.forEach { engine ->
            val statusLines =
                start(engine, module).use { server ->
                    methods.map { exchange(server.port, request(it, "/service/m", body = "abc")).statusLine }
                }

            assertEquals(methods.map { OK }, statusLines, "$engine")
            assertEquals(methods.map { it to "abc" }, seen.toList(), "$engine")
            seen.clear()
        }
    }

    @Test
    fun aBodyInChunksReachesTheHandlerWhole() {
        val bodies = ConcurrentLinkedQueue<String>()
        val module =
            handler { call ->
                bodies += String(call.receiveChannel().toByteArray(), StandardCharsets.UTF_8)
                call.respondText("ok")
            }
        val chunked =
            request("PUT", "/service/c", listOf("Host: mockey.test", "Transfer-Encoding: chunked", "Connection: close")) +
                "3${CRLF}abc${CRLF}4${CRLF}defg${CRLF}0$CRLF$CRLF".toByteArray(StandardCharsets.ISO_8859_1)

        Engine.entries.forEach { engine ->
            val statusLine = start(engine, module).use { exchange(it.port, chunked).statusLine }

            assertEquals(OK, statusLine, "$engine")
        }
        assertEquals(listOf("abcdefg", "abcdefg"), bodies.toList())
    }

    /**
     * A request without a `Content-Length` and without a `Transfer-Encoding` has no body. CIO takes such a
     * request for one with a body that ends with the connection, when the request says that it is the last
     * one of its connection and its method is not `GET`, `HEAD`, or `OPTIONS`. A handler that reads the body,
     * as the mock endpoint does, then waits until the client closes its side, and a client that waits for the
     * answer first waits in vain.
     */
    @Test
    fun aRequestWithoutABodyThatIsTheLastOfItsConnectionIsAnsweredOnNettyAndIsNotAnsweredOnCio() {
        val readingTheBody = handler { call -> call.respondText("${call.receiveChannel().toByteArray().size} bytes") }
        val delete = request("DELETE", "/service/orders/7")

        val onNetty = start(Engine.NETTY, readingTheBody).use { exchange(it.port, delete) }
        val onCio = start(Engine.CIO, readingTheBody).use { exchange(it.port, delete, waitMillis = 1_500) }

        assertEquals(OK to "0 bytes", onNetty.statusLine to onNetty.body)
        assertTrue(onNetty.ended)
        assertTrue(onCio.nothing, "CIO answered: ${onCio.statusLine}")
        assertFalse(onCio.ended)
    }

    /**
     * The lines of a request end with a carriage return and a line feed. Netty refuses a request whose lines
     * end with a line feed alone, as a script can write them by hand. CIO takes such a request.
     */
    @Test
    fun aRequestWhoseLinesEndWithALineFeedAloneIsRefusedOnNettyAndTakenOnCio() {
        val request = "GET /service/x HTTP/1.1\nHost: mockey.test\nConnection: close\n\n".toByteArray(StandardCharsets.ISO_8859_1)

        val onNetty = start(Engine.NETTY).use { exchange(it.port, request) }
        val onCio = start(Engine.CIO).use { exchange(it.port, request) }

        assertContains(onNetty.statusLine, " 400 ")
        assertEquals(OK, onCio.statusLine)
        assertEquals(listOf("/service/x"), received.toList())
    }

    /**
     * The server leaves this limit of Netty as it is. Legacy refuses a request with a header of 9,000
     * characters as well. Netty answers such a request and leaves it to the client to end the connection.
     */
    @Test
    fun nettyTakesNoHeadersLongerThan8192CharactersTogether() {
        fun withAHeaderOf(length: Int) =
            request("GET", "/service/x", listOf("Host: mockey.test", "X-Long: ${"a".repeat(length)}", "Connection: close"))

        val (taken, refused) =
            start(Engine.NETTY).use { server ->
                exchange(server.port, withAHeaderOf(8_000)) to exchange(server.port, withAHeaderOf(9_000), waitMillis = 1_000)
            }

        assertEquals(OK, taken.statusLine)
        assertContains(refused.statusLine, " 400 ")
        assertEquals(listOf("/service/x"), received.toList())
    }

    @Test
    fun theHeadersOfARequestArriveInTheOrderOfTheirNamesOnNettyAndInAnotherOrderOnCio() {
        val seen = ConcurrentLinkedQueue<List<String>>()
        val module =
            handler { call ->
                seen +=
                    call.request.headers
                        .entries()
                        .flatMap { (name, values) -> values.map { "$name: $it" } }
                call.respondText("ok")
            }
        val sent = listOf("Host: mockey.test", "X-B: 1", "x-a: 2", "X-B: 3", "Cookie: a=1", "Cookie: b=2", "Connection: close")

        val (onNetty, onCio) =
            Engine.entries.map { engine ->
                start(engine, module).use { exchange(it.port, request("GET", "/service/h", sent)) }
                seen.single().also { seen.clear() }
            }

        // The headers of one name are together, at the place of the first of them.
        assertEquals(listOf("Host: mockey.test", "X-B: 1", "X-B: 3", "x-a: 2", "Cookie: a=1", "Cookie: b=2", "Connection: close"), onNetty)
        assertEquals(onNetty.sorted(), onCio.sorted())
        assertNotEquals(onNetty, onCio)
    }

    @Test
    fun aClientThatAsksToChangeToHttp2IsAnsweredInHttp11() {
        val upgrades = ConcurrentLinkedQueue<String>()
        val module =
            handler { call ->
                upgrades += call.request.headers["Upgrade"].orEmpty()
                call.respondText("ok")
            }

        Engine.entries.forEach { engine ->
            start(engine, module).use { server ->
                HttpClient.newHttpClient().use { askingForHttp2 ->
                    val request = HttpRequest.newBuilder(URI("http://$LOOPBACK:${server.port}/service/x")).build()
                    val response = askingForHttp2.send(request, HttpResponse.BodyHandlers.ofString())

                    assertEquals(200 to HttpClient.Version.HTTP_1_1, response.statusCode() to response.version(), "$engine")
                }
            }
        }
        assertEquals(listOf("h2c", "h2c"), upgrades.toList())
    }

    // Responses

    @Test
    fun nettyWritesTheHeadersOfAResponseInTheOrderInWhichTheyWereSetAndCioPutsTheHeadersOfOneNameTogether() {
        val set =
            listOf(
                "X-Trace" to "a",
                "Set-Cookie" to "a=1",
                "x-lower" to "b",
                "Set-Cookie" to "b=2; Path=/",
                "Content-Type" to "application/x-test; charset=ISO-8859-1",
            )
        val bytes = byteArrayOf(0xE9.toByte(), 0x00, 0xFF.toByte())
        val module =
            handler { call ->
                set.forEach { (name, value) -> call.response.headers.append(name, value, safeOnly = false) }
                call.respond(ByteArrayContent(bytes, status = HttpStatusCode(299, "Odd Status")))
            }

        val (onNetty, onCio) =
            Engine.entries.map { engine ->
                start(
                    engine,
                    module,
                ).use { exchange(it.port, request("GET", "/service/x")) }
            }

        // Each engine adds the length of the body and what the request said about the connection, and nothing else.
        val ofTheEngine = listOf("Content-Length: 3", "Connection: close")
        assertEquals("HTTP/1.1 299 Odd Status", onNetty.statusLine)
        assertEquals(set.map { (name, value) -> "$name: $value" } + ofTheEngine, onNetty.headers)
        assertEquals(String(bytes, StandardCharsets.ISO_8859_1), onNetty.body)
        assertEquals("HTTP/1.1 299 Odd Status", onCio.statusLine)
        assertEquals(
            listOf(
                "X-Trace: a",
                "Set-Cookie: a=1",
                "Set-Cookie: b=2; Path=/",
                "x-lower: b",
                "Content-Type: application/x-test; charset=ISO-8859-1",
            ) +
                ofTheEngine,
            onCio.headers,
        )
        assertEquals(String(bytes, StandardCharsets.ISO_8859_1), onCio.body)
    }

    @Test
    fun aHeadRequestGetsNoBodyOnNettyAndGetsTheBodyOnCio() {
        val head = request("HEAD", "/service/x")

        val onNetty = start(Engine.NETTY, answering("hello")).use { exchange(it.port, head) }
        val onCio = start(Engine.CIO, answering("hello")).use { exchange(it.port, head) }

        assertEquals(OK to "", onNetty.statusLine to onNetty.body)
        assertContains(onNetty.headers, "Content-Length: 5")
        assertEquals(OK to "hello", onCio.statusLine to onCio.body)
    }

    /**
     * Netty gives up a response that is not written within a time, which is 10 seconds unless it is told
     * otherwise. That time starts when the response is written: a handler that waits before it answers, as
     * for the delay of a scenario, can take longer.
     */
    @Test
    fun theWriteTimeoutOfNettyDoesNotCountTheTimeThatAHandlerTakes() {
        val entered = CountDownLatch(1)

        val answer = startOn(Netty, module = slow(entered, 1_500)) { responseWriteTimeoutSeconds = 1 }.use { get(it.port, "/service/slow") }

        assertEquals(200 to "done", answer)
    }

    // Stopping

    /**
     * Stops a server on [engine] with a grace period of a second while its handler has a request. Returns
     * what the client of that request got, and how long the stop took in milliseconds.
     */
    private fun stoppedWithARequestInProgress(engine: Engine): Pair<Answer, Long> {
        val entered = CountDownLatch(1)
        return start(engine, slow(entered, millis = 200)).use { server ->
            val inProgress = CompletableFuture.supplyAsync { exchange(server.port, request("GET", "/service/slow")) }
            assertTrue(entered.await(WAIT_MILLIS.toLong(), TimeUnit.MILLISECONDS), "$engine")
            val started = System.nanoTime()
            server.stop(graceMillis = 1_000)
            val stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            inProgress.get(WAIT_MILLIS.toLong(), TimeUnit.MILLISECONDS) to stopMillis
        }
    }

    /**
     * Both engines are stopped with a grace period that is longer than the handler takes. CIO waits for the
     * request and then stops. Netty ends every connection at once: the grace period is the time for which
     * its threads go on, and they have no connection to write the response to.
     */
    @Test
    fun nettyEndsTheConnectionOfARequestInProgressWhenItStopsAndCioLetsTheRequestFinish() {
        val (onNetty, nettyStopMillis) = stoppedWithARequestInProgress(Engine.NETTY)
        val (onCio, _) = stoppedWithARequestInProgress(Engine.CIO)

        assertTrue(onNetty.nothing, "Netty answered: ${onNetty.statusLine}")
        assertTrue(onNetty.ended)
        assertTrue(nettyStopMillis >= 1_000, "Netty stopped after $nettyStopMillis ms.")
        assertEquals(OK to "done", onCio.statusLine to onCio.body)
    }

    /** Returns a setting of Netty that notes in [channels] each channel on which a server listens. */
    private fun noting(channels: MutableList<Channel>): NettyApplicationEngine.Configuration.() -> Unit =
        {
            configureBootstrap = {
                handler(
                    object : ChannelInitializer<Channel>() {
                        override fun initChannel(channel: Channel) {
                            channels += channel
                        }
                    },
                )
            }
        }

    /**
     * How a server on Netty lets its requests finish: it closes the socket on which it listens, waits for the
     * requests that it has, and stops the engine after that. The engine gives no way to the socket, so the
     * server takes it from the bootstrap of Netty, which the engine lets it configure.
     *
     * A client that comes after that gets no answer. Most often it is refused. It can also be connected for
     * a moment and find its connection reset, see the next test.
     */
    @Test
    fun aRequestInProgressFinishesOnNettyWhenTheListeningSocketIsClosedBeforeTheEngineIsStopped() {
        val listening = CopyOnWriteArrayList<Channel>()
        val entered = CountDownLatch(1)
        val server = startOn(Netty, module = slow(entered, millis = 300), configure = noting(listening))

        server.use {
            val inProgress = CompletableFuture.supplyAsync { exchange(server.port, request("GET", "/service/slow")) }
            assertTrue(entered.await(WAIT_MILLIS.toLong(), TimeUnit.MILLISECONDS))

            listening.single().close().sync()

            val late = runCatching { exchange(server.port, request("GET", "/service/late")) }
            assertTrue(late.fold({ it.nothing }, { it is SocketException }), "A client that came late got ${late.map { it.statusLine }}")
            val answer = inProgress.get(WAIT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            assertEquals(OK to "done", answer.statusLine to answer.body)
        }
    }

    /**
     * Netty says that a listening channel is closed before the system has closed the socket: the system
     * closes it when the event loop of the channel selects again. That is a moment later, and longer when
     * the thread of the event loop has to wait for a processor. A client that comes in between is
     * connected. It is not served, and it finds its connection ended when the socket is closed.
     *
     * Here the event loop closes the channel and is held from then on, until the client is connected.
     */
    @Test
    fun aClientThatComesBeforeTheEventLoopSelectsAgainIsConnectedButNotServed() {
        val listening = CopyOnWriteArrayList<Channel>()
        val closed = CountDownLatch(1)
        val connected = CountDownLatch(1)

        startOn(Netty, configure = noting(listening)).use { server ->
            val channel = listening.single()
            channel.eventLoop().execute {
                channel.close()
                closed.countDown()
                connected.await(WAIT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            }
            assertTrue(closed.await(WAIT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
            assertFalse(channel.isOpen, "Netty says that the channel is closed")

            val got =
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(LOOPBACK, server.port), WAIT_MILLIS)
                    connected.countDown()
                    socket.soTimeout = WAIT_MILLIS
                    try {
                        socket.getOutputStream().write(request("GET", "/service/late"))
                        socket.getInputStream().readAllBytes().size
                    } catch (_: SocketException) {
                        0
                    }
                }

            assertEquals(0, got, "The bytes that the client got")
            assertEquals(emptyList(), received.toList())
            assertFailsWith<ConnectException> { exchange(server.port, request("GET", "/service/later")) }
        }
    }

    // The class path

    /**
     * The server runs on a Java runtime and nothing else. Netty has transports that need a native library,
     * and Ktor uses one of them when it is there. HTTP/3 needs native libraries of QUIC, which the Netty
     * engine of Ktor brings along for five platforms and which the build of the server leaves out.
     */
    @Test
    fun nettyRunsWithoutANativeLibrary() {
        val classPath = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).name }

        assertTrue(classPath.any { it.startsWith("netty-codec-http-") }, "The class path of the test holds $classPath.")
        assertEquals(emptyList(), classPath.filter { "native-quic" in it })
        assertFalse(Epoll.isAvailable())
        assertFalse(KQueue.isAvailable())
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val SERVICE = "/service/"
        const val CRLF = "\r\n"
        const val OK = "HTTP/1.1 200 OK"
        const val NOT_FOUND = "HTTP/1.1 404 Not Found"

        /** How long a test waits for a server, in milliseconds. */
        const val WAIT_MILLIS = 5_000
        const val STOP_TIMEOUT_MILLIS = 2_000L
        const val BUFFER_SIZE = 8_192

        /** The recordings of the parity cases, from the directory of this module, in which its tests run. */
        val RECORDINGS: Path = Path.of("..", "mockey-parity-tests", "src", "test", "resources", "parity")
    }
}
