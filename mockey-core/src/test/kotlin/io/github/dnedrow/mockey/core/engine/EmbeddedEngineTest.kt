package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.inspection.InspectorInfo
import io.github.dnedrow.mockey.core.inspection.SessionCheckInspector
import io.github.dnedrow.mockey.core.inspection.TraceHeaderInspector
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.upstream.StubResponse
import io.github.dnedrow.mockey.core.upstream.StubUpstream
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An engine as a program uses it: created with [MockeyEngine.create], given its definitions in code, and
 * handed its requests as values. The tests use what a program can use of the library, and nothing else.
 */
class EmbeddedEngineTest {
    /** An upstream client that answers every request with what [answer] says, without a network, and keeps the requests. */
    private class SubstituteClient(
        private val answer: (UpstreamRequest) -> MockResponse,
    ) : UpstreamClient {
        val requests = CopyOnWriteArrayList<UpstreamRequest>()

        /** The value of the `Cookie` header of each request, or `null` for a request without one. */
        val cookies: List<String?>
            get() = requests.map { request -> request.headers.firstOrNull { it.name == "Cookie" }?.value }

        override suspend fun send(request: UpstreamRequest): MockResponse {
            requests += request
            return answer(request)
        }
    }

    private val customer = MockRequest.forTarget("GET", "customer/1")

    /** Defines a static-mode service with the mock URL pattern `customer/{id}` and the scenario `found`. */
    private suspend fun MockeyEngine.withCustomers(): Service {
        val created = services.create { it.copy(name = "Customers", mockUrl = "customer/{id}", responseMode = ResponseMode.STATIC) }
        val service = created.getOrThrow()
        scenarios.create(service.id) { it.copy(name = "found", responseBody = "found") }.getOrThrow()
        return service
    }

    /** Defines a proxy-mode service with the mock URL pattern `orders/{id}` that forwards to [realUrl]. */
    private suspend fun MockeyEngine.withOrders(realUrl: String): Service =
        services.create { it.copy(name = "Orders", mockUrl = "orders/{id}", realUrls = listOf(realUrl)) }.getOrThrow()

    @Test
    fun aStaticScenarioIsAnsweredInProcess() =
        runTest {
            val engine = MockeyEngine.create()
            val headers = listOf(Header("Content-Type", "application/json"), Header("X-Tier", "gold"))
            val body = """{"id": 1}"""
            val service =
                engine.services
                    .create { it.copy(name = "Customers", mockUrl = "customer/{id}", responseMode = ResponseMode.STATIC) }
                    .getOrThrow()
            engine.scenarios
                .create(service.id) { it.copy(name = "found", statusCode = 201, headers = headers, responseBody = body) }
                .getOrThrow()

            val handled = engine.handle(MockRequest.forTarget("GET", "customer/1"))

            assertEquals(201, handled.response.status)
            assertEquals(headers, handled.response.headers)
            assertEquals(body, handled.response.bodyText)
            assertEquals("Customers" to "found", handled.entry.service?.name to handled.entry.scenario?.name)
            engine.close()
        }

    @Test
    fun aDynamicServiceAnswersWithTheScenarioThatMatchesTheRequest() =
        runTest {
            val engine = MockeyEngine.create()
            val service =
                engine.services
                    .create { it.copy(name = "Orders", mockUrl = "orders", responseMode = ResponseMode.DYNAMIC) }
                    .getOrThrow()
            for (tier in listOf("silver", "gold")) {
                engine.scenarios
                    .create(service.id) { it.copy(name = tier, responseBody = "$tier order", matchCriterion = MatchCriterion.Text(tier)) }
                    .getOrThrow()
            }

            val gold = engine.handle(MockRequest.forTarget("POST", "orders", body = "tier=gold".toByteArray()))
            val silver = engine.handle(MockRequest.forTarget("POST", "orders", body = "tier=silver".toByteArray()))

            assertEquals("gold order" to "silver order", gold.response.bodyText to silver.response.bodyText)
            assertEquals(listOf("silver", "gold"), engine.history.entries().map { it.scenario?.name })
            engine.close()
        }

    /**
     * No class of the library names a class of the Java runtime that listens for connections, so nothing
     * that the library does opens a network listener. The build checks that it brings no HTTP server either.
     */
    @Test
    fun theLibraryHoldsNoCodeThatListensOnTheNetwork() {
        val listeners = listOf("java/net/ServerSocket", "ServerSocketChannel", "com/sun/net/httpserver")
        val location = MockeyEngine::class.java.protectionDomain.codeSource.location
        val classes = Path.of(location.toURI())

        val classFiles = Files.walk(classes).use { files -> files.filter { it.toString().endsWith(".class") }.toList() }
        val listening =
            classFiles.filter { file ->
                val constants = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
                listeners.any { it in constants }
            }

        assertTrue(classFiles.any { it.endsWith("MockeyEngine.class") }, "The classes of the library are not in $classes.")
        assertEquals(emptyList(), listening)
    }

    @Test
    fun aServiceWithAMockUrlPatternThatIsInUseIsRejected() =
        runTest {
            val engine = MockeyEngine.create()
            engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }.getOrThrow()

            val result = engine.services.create { it.copy(name = "Clients", mockUrl = "customer/{id}") }

            val error = assertIs<ChangeResult.Rejected>(result).failure.errors.single()
            assertEquals("mockUrl", error.field)
            assertContains(error.message, "Customers")
            assertEquals(listOf("Customers"), engine.definitions.services.map { it.name })
            engine.close()
        }

    @Test
    fun twoEnginesShareNoDefinitionsNoHistoryAndNoSettings() =
        runTest {
            val first = MockeyEngine.create()
            val second = MockeyEngine.create()
            first.withCustomers()
            val inFirst = first.handle(customer)
            first.tagFilter = Tags.of("checkout")
            first.updateOutboundProxy { OutboundProxy("http://proxy.example.com:3128", enabled = true) }.getOrThrow()
            first.setReadOnly(false)

            val inSecond = second.handle(customer)

            assertEquals(200 to Outcome.SCENARIO_SERVED, inFirst.response.status to inFirst.entry.outcome)
            assertEquals(404 to Outcome.NO_SERVICE_MATCHED, inSecond.response.status to inSecond.entry.outcome)
            assertNull(inSecond.entry.service)
            assertEquals(listOf(inFirst.entry), first.history.entries())
            assertEquals(listOf(inSecond.entry), second.history.entries())
            assertEquals(emptyList(), second.definitions.services)
            assertNull(second.definitions.settings.outboundProxy)
            assertEquals(Tags.EMPTY to true, second.tagFilter to second.readOnly)
            first.close()
            second.close()
        }

    @Test
    fun twoEnginesKeepTheCookiesOfRealServicesApart() =
        runTest {
            val clients = List(2) { SubstituteClient { MockResponse(200, listOf(Header("Set-Cookie", "session=abc; Path=/"))) } }
            val engines = clients.map { client -> MockeyEngine.create { upstreamClient = client } }
            engines.forEach { it.withOrders("http://orders.example.com/api/{id}") }
            val order = MockRequest.forTarget("GET", "orders/7")

            engines[0].handle(order)
            engines[1].handle(order)
            engines[0].handle(order)

            assertEquals(listOf(null, "session=abc"), clients[0].cookies)
            assertEquals(listOf(null), clients[1].cookies)
            engines.forEach { it.close() }
        }

    @Test
    fun aSubstituteUpstreamClientAnswersInPlaceOfARealService() =
        runTest {
            val client = SubstituteClient { MockResponse(203, listOf(Header("X-Answered-By", "substitute")), "fixed".toByteArray()) }
            val engine = MockeyEngine.create { upstreamClient = client }
            // No name service knows a host that ends in .invalid, so a request that went to the network would fail.
            engine.withOrders("http://orders.invalid/api/{id}")

            val handled = engine.handle(MockRequest.forTarget("GET", "orders/7"))

            assertEquals(203 to "fixed", handled.response.status to handled.response.bodyText)
            assertEquals("substitute", handled.response.header("X-Answered-By"))
            assertEquals(Outcome.PROXIED, handled.entry.outcome)
            assertEquals(listOf("http://orders.invalid/api/7"), client.requests.map { it.url })
            engine.close()
        }

    /** The request goes over the network, so the test runs in real time: the virtual time of a test would run out at once. */
    @Test
    fun anEngineThatIsGivenNoUpstreamClientForwardsOverHttp() =
        runBlocking {
            StubUpstream { StubResponse(200, "from the real service", "Content-Type" to "text/plain") }.use { real ->
                val engine = MockeyEngine.create()
                engine.withOrders("${real.origin}/api/{id}")

                val handled = engine.handle(MockRequest.forTarget("GET", "orders/7", "expand=items"))

                assertEquals(200 to "from the real service", handled.response.status to handled.response.bodyText)
                assertEquals(listOf("GET /api/7?expand=items"), real.requests.map { "${it.method} ${it.target}" })
                engine.close()
            }
        }

    @Test
    fun aSubstituteClockTellsTheTimeOfAHistoryEntry() =
        runTest {
            val time = Instant.parse("2031-05-17T08:30:00Z")
            val engine = MockeyEngine.create { clock = Clock.fixed(time, ZoneOffset.UTC) }
            val service = engine.withCustomers()

            val handled = engine.handle(customer)

            assertEquals(time, handled.entry.time)
            assertEquals(listOf(time), engine.history.entries().map { it.time })
            assertEquals(time, engine.lastUsedTimes.ofService(service.id))
            engine.close()
        }

    @Test
    fun theInspectorsThatAProgramGivesAreTheInspectorsOfTheEngine() =
        runTest {
            val given: List<Inspector> = listOf(TraceHeaderInspector())
            val engine = MockeyEngine.create { inspectors = given }
            engine.withCustomers()

            val handled = engine.handle(customer)

            assertEquals(listOf(InspectorInfo("trace-header", isGlobal = true)), engine.inspectors)
            assertEquals(listOf("missing trace header"), handled.entry.inspectionMessages)
            engine.close()
        }

    @Test
    fun anEngineThatIsGivenNoInspectorsHasThoseThatAreInstalled(
        @TempDir directory: Path,
    ) = runTest {
        val services = directory.resolve("META-INF/services").createDirectories()
        services.resolve(Inspector::class.java.name).writeLines(listOf(SessionCheckInspector::class.java.name))
        val thread = Thread.currentThread()
        val before = thread.contextClassLoader

        val engine =
            URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
                thread.contextClassLoader = loader
                try {
                    MockeyEngine.create()
                } finally {
                    thread.contextClassLoader = before
                }
            }

        assertEquals(listOf(InspectorInfo("session-check", isGlobal = false)), engine.inspectors)
        assertEquals(emptyList(), engine.inspectorProblems)
        engine.close()
    }

    @Test
    fun anEngineThatIsGivenNoStorageKeepsItsDefinitionsToItself() =
        runTest {
            val engine = MockeyEngine.create { readOnly = false }

            engine.withCustomers()

            assertEquals(listOf("Customers"), engine.definitions.services.map { it.name })
            assertEquals(emptyList(), MockeyEngine.create().use { it.definitions.services })
            engine.close()
        }

    @Test
    fun anEngineReadsTheDefinitionsThatAnotherEngineSaved(
        @TempDir directory: Path,
    ) = runTest {
        val outage = PlanItem("Customers", "missing", ResponseMode.STATIC)
        val writer = MockeyEngine.create { storage = FileDefinitionStorage(directory) }
        writer.setReadOnly(false)
        val service = writer.withCustomers()
        writer.scenarios.create(service.id) { it.copy(name = "missing", statusCode = 404, responseBody = "no such customer") }.getOrThrow()
        writer.plans.create { it.copy(name = "Outage", items = listOf(outage)) }.getOrThrow()
        writer.twists.create { it.copy(name = "to-qa", pairs = listOf(TwistPair("prod", "qa"))) }.getOrThrow()
        writer.close()

        val reader = MockeyEngine.create { storage = FileDefinitionStorage(directory) }
        val handled = reader.handle(customer)

        val read = reader.definitions
        val scenarios = read.services.single().scenarios
        assertEquals(writer.definitions, read)
        assertEquals(listOf("found", "missing"), scenarios.map { it.name })
        assertEquals(listOf("Outage") to listOf("to-qa"), read.plans.map { it.name } to read.twists.map { it.name })
        assertEquals(200 to "found", handled.response.status to handled.response.bodyText)
        reader.close()
    }
}
