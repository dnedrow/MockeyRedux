package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.history.scenarioOf
import io.github.dnedrow.mockey.core.history.serviceOf
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Where an engine sends a request: what the tag filter and the setting for unknown targets make of its route,
 * and under which definitions the request is handled. An engine that is broken could make a test wait forever,
 * so each test has a time limit.
 */
@Timeout(60)
class EngineRoutingTest {
    private val customersUrl = "http://api.example.com/v1/customers"

    private fun answering(body: String = "real") = FakeUpstream { MockResponse(200, body = body.toByteArray()) }

    private fun Service.inMode(mode: ResponseMode): Service = copy(responseMode = mode)

    private fun definitionsOf(vararg services: Service) = Definitions(services = services.toList())

    // The tag filter

    @Test
    fun aServiceWithoutATagOfTheFilterIsNoServiceForTheRequest() =
        runTest {
            val orders = serviceOf("Orders", "orders", scenarioOf("ok"), tags = Tags.of("smoke")).inMode(ResponseMode.STATIC)
            val engine = engineOf(definitionsOf(orders)) { tagFilter = Tags.of("regression") }

            val handled = engine.handle(requestFor("orders"))

            assertEquals(404, handled.response.status)
            assertContains(handled.response.bodyText, "No service matches the target 'orders'.")
            assertEquals(Outcome.NO_SERVICE_MATCHED to null, handled.entry.outcome to handled.entry.service)
        }

    @Test
    fun aTagOfAScenarioIsEnoughForTheFilter() =
        runTest {
            val tagged = scenarioOf("tagged", tags = Tags.of("regression"))
            val orders = serviceOf("Orders", "orders", scenarioOf("ok"), tagged).inMode(ResponseMode.STATIC)
            val engine = engineOf(definitionsOf(orders)) { tagFilter = Tags.of("regression nightly") }

            val handled = engine.handle(requestFor("orders"))

            assertEquals(200 to "ok", handled.response.status to handled.response.bodyText)
            assertEquals("Orders", handled.entry.service?.name)
        }

    @Test
    fun theFilterCanBeChangedWhileTheEngineRuns() =
        runTest {
            val orders = serviceOf("Orders", "orders", scenarioOf("ok")).inMode(ResponseMode.STATIC)
            val engine = engineOf(definitionsOf(orders))

            val unfiltered = engine.handle(requestFor("orders"))
            engine.tagFilter = Tags.of("regression")
            val filtered = engine.handle(requestFor("orders"))
            engine.tagFilter = Tags.EMPTY
            val unfilteredAgain = engine.handle(requestFor("orders"))

            assertEquals(listOf(200, 404, 200), listOf(unfiltered, filtered, unfilteredAgain).map { it.response.status })
        }

    @Test
    fun aTargetThatTheFilterHidesIsForwardedWithoutASecondService() =
        runTest {
            val upstream = answering()
            val customers = serviceOf("Customers", customersUrl, scenarioOf("mocked")).inMode(ResponseMode.STATIC)
            val definitions = definitionsOf(customers)
            val engine =
                engineOf(definitions) {
                    upstreamClient = upstream
                    tagFilter = Tags.of("regression")
                }

            val handled = engine.handle(requestFor(customersUrl, query = "active=true"))

            assertEquals(listOf("$customersUrl?active=true"), upstream.urls)
            assertEquals("real", handled.response.bodyText)
            assertEquals(definitions, engine.definitions)
            assertEquals(
                listOf(null, ResponseMode.PROXY, Outcome.PROXIED),
                listOf(handled.entry.service, handled.entry.responseMode, handled.entry.outcome),
            )
        }

    // Unknown absolute targets

    @Test
    fun anUnknownAbsoluteTargetIsRegisteredAndForwarded() =
        runTest {
            val upstream = answering()
            val engine = engineOf { upstreamClient = upstream }

            val handled = engine.handle(requestFor(customersUrl, query = "active=true"))

            val registered = engine.definitions.services.single()
            assertEquals(listOf("$customersUrl?active=true"), upstream.urls)
            assertEquals(200 to "real", handled.response.status to handled.response.bodyText)
            assertEquals("customers (auto generated)", registered.name)
            assertEquals(customersUrl to listOf(customersUrl), registered.mockUrl to registered.realUrls)
            assertEquals(ResponseMode.PROXY to Tags.EMPTY, registered.effectiveResponseMode to registered.tags)
            assertEquals(registered.id to Outcome.PROXIED, handled.entry.service?.id to handled.entry.outcome)
        }

    @Test
    fun aServiceThatIsRegisteredCarriesTheTagsOfTheFilter() =
        runTest {
            val engine =
                engineOf {
                    upstreamClient = answering()
                    tagFilter = Tags.of("regression nightly")
                }

            val first = engine.handle(requestFor(customersUrl))
            val second = engine.handle(requestFor(customersUrl))

            val registered = engine.definitions.services.single()
            assertEquals(Tags.of("nightly", "regression"), registered.tags)
            assertEquals(listOf(registered.id, registered.id), listOf(first, second).map { it.entry.service?.id })
        }

    @Test
    fun twoRequestsForOneUnknownTargetRegisterOneService() =
        runTest {
            val upstream = answering()
            val engine = engineOf { upstreamClient = upstream }

            val first = engine.handle(requestFor(customersUrl, query = "page=1"))
            val second = engine.handle(requestFor("$customersUrl/", query = "page=2"))

            val registered = engine.definitions.services.single()
            assertEquals(listOf("$customersUrl?page=1", "$customersUrl/?page=2"), upstream.urls)
            assertEquals(listOf(registered.id, registered.id), listOf(first, second).map { it.entry.service?.id })
            assertEquals(listOf(Outcome.PROXIED, Outcome.PROXIED), listOf(first, second).map { it.entry.outcome })
        }

    @Test
    fun requestsForOneUnknownTargetAtTheSameTimeRegisterOneService() {
        val upstream = answering()
        val engine = engineOf { upstreamClient = upstream }

        val handled = runBlocking(Dispatchers.Default) { List(64) { async { engine.handle(requestFor(customersUrl)) } }.awaitAll() }

        val registered = engine.definitions.services.single()
        assertEquals(setOf(registered.id), handled.map { it.entry.service?.id }.toSet())
        assertEquals(64, upstream.requests.size)
    }

    @Test
    fun anUnknownTargetGetsTheStatus404WhenUnknownTargetsAreNotForwarded() =
        runTest {
            val engine = engineOf { forwardUnknownTargets = false }

            val handled = engine.handle(requestFor(customersUrl))

            assertEquals(404, handled.response.status)
            assertEquals("No service matches the target '$customersUrl'. Unknown targets are not forwarded.", handled.response.bodyText)
            assertEquals(Outcome.NO_SERVICE_MATCHED, handled.entry.outcome)
            assertEquals(Definitions.EMPTY, engine.definitions)
        }

    @Test
    fun aTargetIsLookedUpAndForwardedAsItIsAfterTwisting() =
        runTest {
            val upstream = answering()
            val pairs = listOf(TwistPair("qa1", "qa2"), TwistPair("example.com", "example.org"))
            val twist = TwistConfiguration(TwistId.generate(), "to-qa2", pairs)
            val definitions = Definitions(twists = listOf(twist), settings = Settings(enabledTwistId = twist.id))
            val engine = engineOf(definitions) { upstreamClient = upstream }

            val handled = engine.handle(requestFor("http://qa1.example.com/orders"))

            val registered = engine.definitions.services.single()
            assertEquals(listOf("http://qa2.example.com/orders"), upstream.urls)
            assertEquals("http://qa2.example.com/orders", registered.mockUrl)
            assertEquals("http://qa2.example.com/orders", handled.entry.target)
            assertEquals("http://qa1.example.com/orders", handled.entry.targetBeforeTwisting)
        }

    // One value of the definitions

    @Test
    fun oneRoutingTableServesTheRequestsUnderOneValueOfTheDefinitions() =
        runTest {
            val orders = serviceOf("Orders", "orders", scenarioOf("ok")).inMode(ResponseMode.STATIC)
            val engine = engineOf(definitionsOf(orders))
            val table = engine.routingTable

            repeat(3) { engine.handle(requestFor("orders")) }
            val rejected = engine.change(ServiceChanges.create { it.copy(name = "Other orders", mockUrl = "orders") })
            val tableAfterRequests = engine.routingTable
            engine.change(ServiceChanges.update(orders.id) { it.copy(mockUrl = "orders/{id}") })
            val tableAfterChange = engine.routingTable

            assertIs<ChangeResult.Rejected>(rejected)
            assertSame(table, tableAfterRequests)
            assertNotSame(table, tableAfterChange)
            assertSame(engine.definitions, tableAfterChange.definitions)
            assertSame(tableAfterChange, engine.routingTable)
        }

    @Test
    fun aChangeCountsForTheRequestsAfterIt() =
        runTest {
            val ok = scenarioOf("ok")
            val busy = scenarioOf("busy", status = 503)
            val orders = serviceOf("Orders", "orders", ok, busy).inMode(ResponseMode.STATIC)
            val engine = engineOf(definitionsOf(orders))

            val before = engine.handle(requestFor("orders"))
            engine.change(ServiceChanges.update(orders.id) { it.copy(mockUrl = "purchases", defaultScenarioId = busy.id) })
            val afterAtOldUrl = engine.handle(requestFor("orders"))
            val afterAtNewUrl = engine.handle(requestFor("purchases"))

            assertEquals(listOf(200, 404, 503), listOf(before, afterAtOldUrl, afterAtNewUrl).map { it.response.status })
        }

    @Test
    fun aRequestIsHandledUnderTheDefinitionsOfOneMoment() {
        val a = scenarioOf("a", "body a", 200)
        val b = scenarioOf("b", "body b", 201)
        val orders = serviceOf("Orders A", "orders", a, b).inMode(ResponseMode.STATIC)
        val engine = engineOf(definitionsOf(orders))

        /** One change that renames the service and gives it another default scenario. */
        fun switchTo(scenarioB: Boolean) =
            ServiceChanges.update(orders.id) {
                it.copy(name = if (scenarioB) "Orders B" else "Orders A", defaultScenarioId = if (scenarioB) b.id else a.id)
            }

        // The changes and each of the four clients have a thread of their own. On the default dispatcher of a
        // machine with four processors or fewer, the clients take every thread, the changes are never made,
        // and the clients send requests without end.
        val handled =
            Executors.newFixedThreadPool(5).asCoroutineDispatcher().use { threads ->
                runBlocking(threads) {
                    val changes =
                        launch(start = CoroutineStart.LAZY) { repeat(501) { round -> engine.change(switchTo(scenarioB = round % 2 == 0)) } }
                    List(4) {
                        async {
                            val requests = mutableListOf(engine.handle(requestFor("orders")))
                            changes.start()
                            while (changes.isActive) requests += engine.handle(requestFor("orders"))
                            requests + engine.handle(requestFor("orders"))
                        }
                    }.awaitAll().flatten()
                }
            }

        // Every request saw the service before a change or after it, and none saw a part of each.
        val seen = handled.map { listOf(it.entry.service?.name, it.entry.scenario?.name, it.response.status, it.response.bodyText) }.toSet()
        assertEquals(setOf(listOf("Orders A", "a", 200, "body a"), listOf("Orders B", "b", 201, "body b")), seen)
    }
}
