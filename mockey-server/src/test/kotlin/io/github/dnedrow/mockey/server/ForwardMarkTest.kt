package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamRequest
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Task 12.10: the server keeps the requests that it forwards as mock traffic from its own admin API, see
 * [ForwardMark] and `docs/decisions/forwarding-to-the-admin-listener.md`.
 *
 * The tests of the first part send their requests over real HTTP to a server in the process of the test.
 * The admin API of that server only reads, so the tests of the second part put operations of their own
 * behind an admin listener, which tell whether they were performed. The tests of the third part ask the
 * mark which requests it is for.
 */
class ForwardMarkTest {
    private val levelBefore = ServerLogging.level()
    private val closeables = mutableListOf<AutoCloseable>()
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun closeEverythingAndRestoreTheLevelOfTheLog() {
        closeables.reversed().forEach(AutoCloseable::close)
        directories.forEach { it.toFile().deleteRecursively() }
        ServerLogging.configure(levelBefore)
    }

    private fun <T : AutoCloseable> T.closedAfterTheTest(): T = also { closeables += it }

    // A server

    /** A server with the service `Customers`, on the loopback address and on ports that the system picks. [token] is its admin token. */
    private fun server(token: String? = null): MockeyServer {
        val definitions = Files.createTempDirectory("mockey-forward-mark-test").also { directories.add(it) }
        StoredDefinitions.customers(definitions)
        val onFreePorts = ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0, logLevel = LogLevel.WARN)
        val settings = onFreePorts.copy(definitions = definitions, adminToken = token?.let(::AdminToken))
        return MockeyServer.start(settings).closedAfterTheTest()
    }

    /**
     * A server without an admin token, and one with an admin token. Each comes with the headers of a client
     * that has the power of the admin API of that server: such a client of the second server presents the
     * token.
     */
    private fun serversWithAndWithoutAToken(): List<Pair<MockeyServer, List<String>>> =
        listOf(server() to emptyList(), server(TOKEN) to listOf("Authorization: Bearer $TOKEN"))

    private val MockeyServer.adminPort: Int
        get() = listeners.admin.address.port

    /** Sends a request for [url] to the mock endpoint of this server, which forwards it, and returns the answer. */
    private fun MockeyServer.forwarding(
        url: String,
        headers: List<String>,
    ): RawHttp.Answer = RawHttp.get(listeners.mock.address, "/service/$url", headers)

    /** The admin API of this server lists its services for a client that sends [headers] to the admin listener. */
    private fun MockeyServer.assertListsItsServices(headers: List<String>) {
        val answer = RawHttp.get(listeners.admin.address, SERVICES, headers)

        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertContains(answer.body, "Customers")
    }

    /** [answer] is the refusal of the admin listener, and it holds nothing of what the admin API states. */
    private fun assertRefused(answer: RawHttp.Answer) {
        assertEquals("HTTP/1.1 403 Forbidden", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"), "$answer")
        assertEquals(listOf("no-store"), answer.headerValues("Cache-Control"), "$answer")
        val problem = AdminApiJson.instance.decodeFromString<ProblemDto>(answer.bodyInUtf8)
        assertEquals(ProblemDto("about:blank", "Forbidden", 403, REFUSAL), problem)
        assertFalse("Customers" in answer.body, "$answer")
    }

    /** The scenario "Mock request for the admin listener", also for a server with an admin token whose client presents it. */
    @Test
    fun aMockRequestThatNamesTheAdminListenerByItsAddressIsRefused() {
        serversWithAndWithoutAToken().forEach { (server, credentials) ->
            server.assertListsItsServices(credentials)

            val answer = server.forwarding("http://127.0.0.1:${server.adminPort}$SERVICES", credentials)

            assertRefused(answer)
        }
    }

    /** The refusal is the answer of a real service to the mock endpoint, and the request is mock traffic like any other. */
    @Test
    fun theRefusalIsRelayedAndRecordedLikeTheAnswerOfARealService() {
        val server = server()
        val url = "http://127.0.0.1:${server.adminPort}$SERVICES"

        val answer = server.forwarding(url, emptyList())

        assertRefused(answer)
        assertEquals(listOf("*"), answer.headerValues("Access-Control-Allow-Origin"))
        val engine = server.engine
        val entry = engine.history.entries().single()
        assertEquals(Triple(Outcome.PROXIED, 403, url), Triple(entry.outcome, entry.response.status, entry.target))
        assertEquals(emptyList(), entry.request.headers.filter { it.name.equals(ForwardMark.HEADER, ignoreCase = true) })
        val services = engine.definitions.services.map { it.mockUrl to it.responseMode }
        assertEquals(listOf("customer/{id}" to ResponseMode.STATIC, url to ResponseMode.PROXY), services)
    }

    @Test
    fun aMockRequestThatNamesTheAdminListenerByAHostNameIsRefused() {
        serversWithAndWithoutAToken().forEach { (server, credentials) ->
            server.assertListsItsServices(credentials)

            val answer = server.forwarding("http://localhost:${server.adminPort}$SERVICES", credentials)

            assertRefused(answer)
        }
    }

    /**
     * An address can be written in many ways, and a URL can hold more than a host before its port. Each of
     * these URLs leads the upstream client to the admin listener, and none of them names it as a text that
     * could be looked for.
     */
    @Test
    fun aMockRequestIsRefusedHoweverItsUrlWritesTheAddressOfTheAdminListener() {
        val server = server()
        val hosts = listOf("LOCALHOST", "2130706433", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "api.example.com:80@127.0.0.1")

        val answers = hosts.map { server.forwarding("http://$it:${server.adminPort}$SERVICES", emptyList()) }

        answers.forEach(::assertRefused)
    }

    /**
     * Other ways to write the address lead to the admin listener on one system and nowhere on another: the
     * address of no machine stands for the machine itself on Linux and on macOS. Wherever a request gets,
     * it gets no answer of the admin API.
     */
    @Test
    fun noWayOfWritingTheAddressGetsAnAnswerOfTheAdminApi() {
        val server = server()

        fun answerFor(host: String) = server.forwarding("http://$host:${server.adminPort}$SERVICES", emptyList())

        val ofNoMachine = listOf("0.0.0.0", "0").map(::answerFor)
        val ofNoUri = listOf("127.1", "0x7f.0.0.1").map(::answerFor)
        val ofIpv6 = listOf("[::1]", "[0:0:0:0:0:0:0:1]").map(::answerFor)
        val others = listOf("localhost.", "017700000001").map(::answerFor)

        (ofNoMachine + ofNoUri + ofIpv6 + others).forEach { answer ->
            assertTrue(answer.status == 403 || answer.status == 502, "$answer")
            assertFalse("Customers" in answer.body, "$answer")
        }
        if (LINUX_OR_MACOS) ofNoMachine.forEach(::assertRefused)
        // Neither of these is a host for a URI, so the upstream client sends no request for them.
        ofNoUri.forEach { assertContains(it.body, "cannot be asked") }
        // The admin listener is bound to the address of IPv4.
        assertEquals(listOf<Int?>(502, 502), ofIpv6.map { it.status })
    }

    /** The admin listener speaks no TLS, so a request for an `https` URL fails before anything of it is sent. */
    @Test
    fun aMockRequestForAnHttpsUrlOfTheAdminListenerGetsNowhere() {
        val server = server()

        val answer = server.forwarding("https://127.0.0.1:${server.adminPort}$SERVICES", emptyList())

        assertEquals("HTTP/1.1 502 Bad Gateway", answer.statusLine)
        assertContains(answer.body, "the secure connection could not be set up")
        assertFalse("Customers" in answer.body, "$answer")
    }

    /** The scenario "Redirect to the admin listener". The real service gets its request without the mark. */
    @Test
    fun aMockRequestThatARedirectLeadsToTheAdminListenerIsRefused() {
        serversWithAndWithoutAToken().forEach { (server, credentials) ->
            server.assertListsItsServices(credentials)
            val location = "Location: http://127.0.0.1:${server.adminPort}$SERVICES"
            val redirecting = StubUpstream(status = "302 Found", headers = listOf(location), body = "").closedAfterTheTest()

            val answer = server.forwarding("${redirecting.origin}/moved", credentials)

            assertRefused(answer)
            assertEquals(listOf("GET /moved"), redirecting.requests.map { "${it.method} ${it.target}" })
            assertEquals(emptyList(), redirecting.requests.single().headerValues(ForwardMark.HEADER))
        }
    }

    /**
     * The scenario "Real service on a port with the number of the admin listener". The real service of the
     * test is on another address of this machine, where the admin listener is not.
     */
    @Test
    fun aRealServiceOnAPortWithTheNumberOfTheAdminListenerIsReachedAndGetsTheMark() {
        val server = server()
        val upstream = realServiceOnThePortOf(server)

        val answer = server.forwarding("${upstream.origin}/orders", listOf("X-Request-Id: 7"))

        assertEquals("HTTP/1.1 200 OK" to "answered by the real service", answer.statusLine to answer.body)
        val forwarded = upstream.requests.single()
        assertEquals("GET /orders", "${forwarded.method} ${forwarded.target}")
        assertEquals(listOf("7") to listOf("true"), forwarded.headerValues("X-Request-Id") to forwarded.headerValues("Mockey-Forwarded"))
    }

    /**
     * Returns a real service on the port of the admin listener of [server], on an address of this machine
     * other than the one of that listener: the loopback address of IPv6, or else an address beyond loopback.
     */
    private fun realServiceOnThePortOf(server: MockeyServer): StubUpstream {
        val addresses = listOf(InetAddress.getByName("::1")) + OutsideAddresses.usable
        val upstream =
            addresses.firstNotNullOfOrNull { address ->
                try {
                    StubUpstream(address = InetSocketAddress(address, server.adminPort)).closedAfterTheTest()
                } catch (_: IOException) {
                    null
                }
            }
        TestEnvironment.needs(upstream != null, "a second address with the port of the admin listener free: [::1], or one beyond loopback")
        return upstream!!
    }

    /** "No admin operation is performed", for a request that would change the definitions of the server. */
    @Test
    fun aForwardedRequestChangesNothingThroughTheAdminApi() {
        val server = server()
        val mock = server.listeners.mock.address
        val admin = "http://127.0.0.1:${server.adminPort}/api/v1"
        val engine = server.engine
        val customers = engine.definitions.services.single()
        val json = listOf("Content-Type: application/json")
        val orders = """{"name":"Orders","mockUrl":"orders"}"""

        val creating = RawHttp.exchange(mock, RawHttp.request("POST", "/service/$admin/services", json, orders))
        val deleting = RawHttp.exchange(mock, RawHttp.request("DELETE", "/service/$admin/services/${customers.id.value}"))
        val directly = RawHttp.exchange(server.listeners.admin.address, RawHttp.request("POST", "/api/v1/services", json, orders))

        assertRefused(creating)
        assertRefused(deleting)
        assertEquals(201, directly.status)
        // The two targets were unknown, and each left a service in proxy mode behind, as every unknown target does.
        val defined = engine.definitions.services.filterNot { it.mockUrl.startsWith(admin) }
        assertEquals(listOf(customers.name to customers.mockUrl, "Orders" to "orders"), defined.map { it.name to it.mockUrl })
    }

    /** A request that goes elsewhere is forwarded as the client sent it: the server adds no mark, and it takes none away. */
    @Test
    fun aRequestThatTheServerForwardsToAnotherPortIsAsItWas() {
        val server = server()
        val upstream = StubUpstream().closedAfterTheTest()

        val plain = server.forwarding("${upstream.origin}/orders", emptyList())
        val withTheHeaderOfTheClient = server.forwarding("${upstream.origin}/orders", listOf("${ForwardMark.HEADER}: of the client"))

        assertEquals(listOf<Int?>(200, 200), listOf(plain.status, withTheHeaderOfTheClient.status))
        assertEquals(listOf(emptyList(), listOf("of the client")), upstream.requests.map { it.headerValues(ForwardMark.HEADER) })
    }

    // An admin listener whose operations tell that they were performed

    /** What the operations of the test were asked for, in the order of the requests. */
    private val asked = CopyOnWriteArrayList<String>()

    private val routes =
        listOf(
            AdminRoute(HttpMethod.Get, "items") {
                asked += "list"
                AdminResponse.json(listOf("first"))
            },
            AdminRoute(HttpMethod.Post, "items") {
                asked += "create ${jsonObject()}"
                AdminResponse.json("created", HttpStatusCode.Created)
            },
            AdminRoute(HttpMethod.Put, "items/{itemId}") {
                asked += "change ${parameter("itemId")} to ${jsonObject()}"
                AdminResponse.json("changed")
            },
            AdminRoute(HttpMethod.Delete, "items/{itemId}") {
                asked += "delete ${parameter("itemId")}"
                AdminResponse.json("deleted")
            },
        )

    /**
     * The two listeners as a server has them: the mock endpoint of an engine that forwards with the marking
     * client, and an admin listener with the operations of the test, on a server with [token] as its admin
     * token. The mark knows the port of the admin listener when [portIsKnown] says so.
     */
    private inner class TwoListeners(
        token: String? = null,
        portIsKnown: Boolean = true,
    ) : AutoCloseable {
        val mark = ForwardMark(adminPort = null)
        private val engine = runBlocking { MockeyEngine.create { upstreamClient = mark.client(UpstreamClient.overHttp()) } }
        private val listeners =
            Listeners.start(
                ListenerAddresses(mock = RawHttp.loopback(0), admin = RawHttp.loopback(0)),
                MockeyServer.mockListenerModule(MockEndpoint(engine, maxRequestBody = 1_024), StatusEndpoint(Instant.EPOCH)),
            ) { MockeyServer.adminListenerModule(mark, AdminApi(token?.let(::AdminToken), maxRequestBody = 1_024, routes)) }

        val adminPort: Int = listeners.admin.address.port

        init {
            if (portIsKnown) mark.adminListensOn(adminPort)
        }

        /** Sends a request to the mock endpoint, which forwards it to [url]. */
        fun forwarding(
            method: String,
            url: String,
            headers: List<String> = emptyList(),
            body: String = "",
        ): RawHttp.Answer = RawHttp.exchange(listeners.mock.address, RawHttp.request(method, "/service/$url", headers, body))

        /** Sends a request to the admin listener. */
        fun directly(
            method: String,
            target: String,
            headers: List<String> = emptyList(),
            body: String = "",
        ): RawHttp.Answer = RawHttp.exchange(listeners.admin.address, RawHttp.request(method, target, headers, body))

        override fun close() {
            listeners.stop(Duration.ZERO)
            engine.close()
        }
    }

    private val json = listOf("Content-Type: application/json")

    /** The requests of the test: the method, the path, the headers, and the body of each, and what its operation is asked for. */
    private val operations =
        listOf(
            Operation("GET", "/api/v1/items", performed = "list"),
            Operation("HEAD", "/api/v1/items", performed = "list"),
            Operation("POST", "/api/v1/items", json, """{"name":"new"}""", performed = """create {"name":"new"}"""),
            Operation("PUT", "/api/v1/items/7", json, """{"name":"other"}""", performed = """change 7 to {"name":"other"}"""),
            Operation("DELETE", "/api/v1/items/7", performed = "delete 7"),
        )

    private class Operation(
        val method: String,
        val path: String,
        val headers: List<String> = emptyList(),
        val body: String = "",
        val performed: String,
    )

    /** "No admin operation is performed": not one that reads, and not one that changes something, with a body or without one. */
    @Test
    fun noOperationIsPerformedForARequestThatTheServerForwardedWhateverItsMethodIs() {
        val server = TwoListeners().closedAfterTheTest()

        val admin = "http://127.0.0.1:${server.adminPort}"

        val forwarded = operations.map { server.forwarding(it.method, admin + it.path, it.headers, it.body) }

        assertEquals(List(operations.size) { 403 }, forwarded.map { it.status })
        assertEquals(emptyList(), asked.toList())
        // The answer to a HEAD request has no body.
        forwarded.filterIndexed { index, _ -> operations[index].method != "HEAD" }.forEach(::assertRefusal)

        val direct = operations.map { server.directly(it.method, it.path, it.headers, it.body) }

        assertEquals(listOf<Int?>(200, 200, 201, 200, 200), direct.map { it.status })
        assertEquals(operations.map { it.performed }, asked.toList())
    }

    /** [answer] is the problem document of the refusal. */
    private fun assertRefusal(answer: RawHttp.Answer) {
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"), "$answer")
        val problem = AdminApiJson.instance.decodeFromString<ProblemDto>(answer.bodyInUtf8)
        assertEquals(ProblemDto("about:blank", "Forbidden", 403, REFUSAL), problem)
    }

    /** The refusal comes first: a forwarded request gets it with the token, with another token, and without one. */
    @Test
    fun aForwardedRequestIsRefusedWhateverItPresentsAsAToken() {
        val server = TwoListeners(TOKEN).closedAfterTheTest()
        val url = "http://127.0.0.1:${server.adminPort}/api/v1/items"
        val presented = listOf(listOf("Authorization: Bearer $TOKEN"), listOf("Authorization: Bearer another-token"), emptyList())

        val forwarded = presented.map { server.forwarding("GET", url, it) }
        val direct = presented.map { server.directly("GET", "/api/v1/items", it) }

        assertEquals(List(3) { 403 }, forwarded.map { it.status })
        forwarded.forEach(::assertRefusal)
        assertEquals(listOf<Int?>(200, 401, 401), direct.map { it.status })
        assertEquals(listOf("list"), asked.toList())
    }

    /** The admin listener takes no forwarded request at all: also one for a path that it answers with the status 404. */
    @Test
    fun aForwardedRequestIsRefusedWhateverItsPathIs() {
        val server = TwoListeners().closedAfterTheTest()
        val paths = listOf("/api/v1/nothing", "/api/v1", "/", "/status", "/service/customer/7")

        val forwarded = paths.map { server.forwarding("GET", "http://127.0.0.1:${server.adminPort}$it") }
        val direct = paths.map { server.directly("GET", it) }

        assertEquals(List(paths.size) { 403 }, forwarded.map { it.status })
        forwarded.forEach(::assertRefusal)
        assertEquals(List(paths.size) { 404 }, direct.map { it.status })
    }

    /** The name of the header is all that the admin listener looks at, so a client that sends it there is refused as well. */
    @Test
    fun aRequestThatCarriesTheHeaderOfTheMarkIsRefusedWhoeverSentIt() {
        val server = TwoListeners().closedAfterTheTest()
        val headers = listOf("Mockey-Forwarded: true", "mockey-forwarded: no", "MOCKEY-FORWARDED:")

        val answers = headers.map { server.directly("POST", "/api/v1/items", json + it, """{"name":"new"}""") }

        assertEquals(List(3) { 403 }, answers.map { it.status })
        answers.forEach(::assertRefusal)
        assertEquals(emptyList(), asked.toList())
    }

    /**
     * A server that is set to the port 0 for its admin listener learns the port when that listener has
     * started. The mock listener accepts connections before. A request that it is to forward then waits for
     * the port, so that it is sent with the mark or without it as its port says.
     */
    @Test
    fun aRequestThatIsForwardedBeforeThePortOfTheAdminListenerIsKnownWaitsForIt() {
        val server = TwoListeners(portIsKnown = false).closedAfterTheTest()
        val upstream = StubUpstream().closedAfterTheTest()
        val answers = CopyOnWriteArrayList<Pair<String, RawHttp.Answer>>()
        val urls = listOf("${upstream.origin}/early", "http://127.0.0.1:${server.adminPort}/api/v1/items")
        val early = urls.map { url -> thread { answers += url to server.forwarding("GET", url) } }

        Thread.sleep(WAIT_MILLIS)
        val before = upstream.requests.size to answers.size
        server.mark.adminListensOn(server.adminPort)
        early.forEach { it.join() }
        val late = server.forwarding("GET", "${upstream.origin}/late")

        assertEquals(0 to 0, before)
        assertEquals(listOf<Int?>(200, 403), urls.map { url -> answers.single { it.first == url }.second.status })
        assertEquals(200, late.status)
        assertEquals(listOf("/early", "/late"), upstream.requests.map { it.target })
        assertEquals(listOf(emptyList(), emptyList()), upstream.requests.map { it.headerValues(ForwardMark.HEADER) })
        assertEquals(emptyList(), asked.toList())
    }

    /** The port of a listener does not change, so the first port that the mark is told stays. */
    @Test
    fun thePortThatTheMarkKnowsStays() {
        val mark = ForwardMark(adminPort = 8081)

        mark.adminListensOn(9000)

        assertEquals(listOf(true, false), listOf("http://127.0.0.1:8081/", "http://127.0.0.1:9000/").map { mark.isFor(it) })
    }

    // Which requests the mark is for

    /** Returns whether a request for [url] gets this mark. */
    private fun ForwardMark.isFor(url: String): Boolean = runBlocking { marks(UpstreamRequest("GET", url)) }

    /** Only the port counts. So it does not matter how a host is written, what a name stands for, or whose name it is. */
    @Test
    fun aRequestGetsTheMarkWhenItsUrlHasThePortOfTheAdminListenerWhateverItsHostIs() {
        val mark = ForwardMark(adminPort = 8081)
        val forThePort =
            listOf(
                "http://127.0.0.1:8081/api/v1/services",
                "http://localhost:8081",
                "http://LOCALHOST:8081/api/v1/services?tag=a",
                "http://127.1:8081/",
                "http://2130706433:8081/",
                "http://0x7f.0.0.1:8081/",
                "http://0.0.0.0:8081/",
                "http://[::1]:8081/",
                "http://[::ffff:127.0.0.1]:8081/",
                "http://mockey.example.com:8081/",
                "http://rebound.attacker.example:8081/",
                "http://api.example.com:80@127.0.0.1:8081/",
                "HTTP://127.0.0.1:8081/",
                "https://127.0.0.1:8081/",
                "http://127.0.0.1:08081/",
                "http://127.0.0.1:8081/a|b?q={x}",
            )
        val forAnotherPort =
            listOf(
                "http://127.0.0.1:8080/api/v1/services",
                "http://localhost/api/v1/services",
                "http://api.example.com:18081/",
                "http://api.example.com:808/1",
                "http://127.0.0.1:8081@api.example.com:9000/",
                "http://api.example.com/:8081",
                "http://api.example.com/a|b?next=http://127.0.0.1:8081/",
                "https://api.example.com/#:8081",
            )

        assertEquals(forThePort, forThePort.filter { mark.isFor(it) })
        assertEquals(emptyList(), forAnotherPort.filter { mark.isFor(it) })
    }

    @Test
    fun aUrlWithoutAPortGoesToThePortOfItsScheme() {
        val http = listOf("http://127.0.0.1/api/v1/services", "HTTP://localhost", "http://api.example.com:/x", "http://127.0.0.1:80/x")
        val https = listOf("https://127.0.0.1/api/v1/services", "HTTPS://localhost", "https://api.example.com:443/x")

        assertEquals(http, (http + https).filter { ForwardMark(adminPort = 80).isFor(it) })
        assertEquals(https, (http + https).filter { ForwardMark(adminPort = 443).isFor(it) })
    }

    /** The upstream client of the library sends none of these requests. Were they sent, they would carry the mark. */
    @Test
    fun aRequestOfWhichNothingTellsWhereItGoesGetsTheMark() {
        val mark = ForwardMark(adminPort = 8081)
        val urls = listOf("http://[::1/x", "http://a b:9000/x", "http://my_host:9000/x", "ftp://127.0.0.1/x", "orders", "")

        assertEquals(urls, urls.filter { mark.isFor(it) })
    }

    /** An upstream client that keeps what it is asked to send, and answers nothing of interest. */
    private class Recording : UpstreamClient {
        val sent = mutableListOf<UpstreamRequest>()
        var closed = false

        override suspend fun send(request: UpstreamRequest): MockResponse {
            sent += request
            return MockResponse(200, emptyList(), ByteArray(0))
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun theMarkingClientHandsOnEveryRequestAndAddsOnlyTheMark() {
        val recording = Recording()
        val client = ForwardMark(adminPort = 8081).client(recording)
        val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true)
        val headers = listOf(Header("Accept", "text/plain"), Header("mockey-forwarded", "of the client"), Header("Accept", "text/html"))
        val elsewhere = UpstreamRequest("POST", "http://api.example.com/orders", headers, "ordered".toByteArray(), proxy)
        val toThePort = UpstreamRequest("POST", "http://api.example.com:8081/orders?x=1", headers, "ordered".toByteArray(), proxy)

        runBlocking {
            client.send(elsewhere)
            client.send(toThePort)
        }

        val (first, second) = recording.sent
        assertSame(elsewhere, first)
        assertEquals("POST" to "http://api.example.com:8081/orders?x=1", second.method to second.url)
        assertEquals(headers + Header("Mockey-Forwarded", "true"), second.headers)
        assertContentEquals("ordered".toByteArray(), second.body)
        assertSame(proxy, second.outboundProxy)
        assertEquals(headers, toThePort.headers)
    }

    @Test
    fun closingTheMarkingClientClosesTheClientThatItSendsWith() {
        val recording = Recording()

        ForwardMark(adminPort = 8081).client(recording).close()

        assertTrue(recording.closed)
    }

    private companion object {
        const val TOKEN = "tk-forward-mark"

        /** The time that a test gives a request to arrive that is not to arrive. */
        const val WAIT_MILLIS = 300L
        val LINUX_OR_MACOS = System.getProperty("os.name").let { it.startsWith("Linux") || it.startsWith("Mac") }
        const val SERVICES = "/api/v1/services"
        const val REFUSAL =
            "The request carries the header Mockey-Forwarded, which says that the mock endpoint of a Mockey server forwarded it. " +
                "The admin listener takes no request that was forwarded."
    }
}
