package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.NOON
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.RecordedScenario
import io.github.dnedrow.mockey.core.history.RecordedService
import io.github.dnedrow.mockey.core.history.headersOf
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.history.scenarioOf
import io.github.dnedrow.mockey.core.history.serviceOf
import io.github.dnedrow.mockey.core.inspection.FailingInspector
import io.github.dnedrow.mockey.core.inspection.SessionCheckInspector
import io.github.dnedrow.mockey.core.inspection.TraceHeaderInspector
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.upstream.UpstreamException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * How an engine handles a request: the steps that the request takes from its target to its history entry. The
 * tests run in virtual time: a delay takes no time, and the clock of the test tells how long it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestHandlingTest {
    private val time = TestClock()

    private val pageIdRules =
        """{"headers": [{"key": "page_id", "desc": "page_id is mandatory", "value_rule_arg": "", "value_rule_type": "string_required"}]}"""
    private val pageIdMissing =
        "The request fails a rule of the group 'headers' for the key 'page_id', which requires a value. Description: page_id is mandatory"

    private fun Scenario.matching(text: String): Scenario = copy(matchCriterion = MatchCriterion.Text(text))

    private fun Service.inMode(mode: ResponseMode): Service = copy(responseMode = mode)

    private fun definitionsOf(vararg services: Service) = Definitions(services = services.toList())

    /** What a client sees of a response. */
    private fun MockResponse.parts() = Triple(status, headers, String(body))

    // The steps together

    @Test
    fun aRequestTakesEveryStepFromItsTargetToItsHistoryEntry() =
        runTest {
            val goldHeaders = headersOf("Content-Type" to "application/json", "X-Tier" to "gold")
            val gold =
                scenarioOf(
                    "gold customer",
                    """{"tier":"gold"}""",
                    201,
                    goldHeaders,
                    Tags.of("vip"),
                ).matching("gold").copy(delayMillis = 300)
            val standard = scenarioOf("standard customer").matching("standard")
            val customers =
                serviceOf("Customers", "http://qa2.example.com/customers/{id}", standard, gold, tags = Tags.of("crm"))
                    .inMode(ResponseMode.DYNAMIC)
                    .copy(delayMillis = 5_000, requestRules = pageIdRules, requestRulesEnabled = true)
            val twist = TwistConfiguration(TwistId.generate(), "to-qa2", listOf(TwistPair("qa1", "qa2")))
            val definitions = Definitions(listOf(customers), twists = listOf(twist), settings = Settings(enabledTwistId = twist.id))
            val engine =
                engineOf(definitions) {
                    clock = time
                    inspectors = listOf(TraceHeaderInspector())
                }
            val request = requestFor("http://qa1.example.com/customers/gold", query = "expand=orders")

            val handled = engine.handle(request, "192.0.2.10")

            // Twisting and routing lead to the service, and the scenario that matches best answers after its delay.
            assertEquals(Triple(201, goldHeaders, """{"tier":"gold"}"""), handled.response.parts())
            assertEquals(300, currentTime)
            // The history entry says all of it, with what inspection found.
            val entry = handled.entry
            assertEquals(NOON to "192.0.2.10", entry.time to entry.clientAddress)
            assertEquals(
                "http://qa2.example.com/customers/gold" to "http://qa1.example.com/customers/gold",
                entry.target to entry.targetBeforeTwisting,
            )
            assertEquals(RecordedService(customers.id, "Customers", Tags.of("crm")), entry.service)
            assertEquals(ResponseMode.DYNAMIC to Outcome.SCENARIO_SERVED, entry.responseMode to entry.outcome)
            assertEquals(RecordedScenario(gold.id, "gold customer", Tags.of("vip")), entry.scenario)
            assertEquals(listOf("missing trace header", pageIdMissing), entry.inspectionMessages)
            assertEquals(request.url to listOf(Parameter("expand", "orders")), entry.request.url to entry.request.parameters)
            assertEquals(201 to """{"tier":"gold"}""", entry.response.status to entry.response.bodyText)
            assertEquals(listOf(entry), engine.history.entries())
            assertEquals(NOON to NOON, engine.lastUsedTimes.ofService(customers.id) to engine.lastUsedTimes.ofScenario(gold.id))
        }

    // Responding

    @Test
    fun aServiceInStaticModeAnswersWithItsDefaultScenario() =
        runTest {
            val headers = headersOf("Content-Type" to "text/plain", "X-Source" to "mock")
            val found = scenarioOf("found", "customer 1", 200, headers)
            val customers = serviceOf("Customers", "customer/{id}", found, scenarioOf("missing", status = 404)).inMode(ResponseMode.STATIC)
            val engine = engineOf(definitionsOf(customers))

            val handled = engine.handle(requestFor("customer/1"))

            assertEquals(Triple(200, headers, "customer 1"), handled.response.parts())
            assertEquals(ResponseMode.STATIC to Outcome.SCENARIO_SERVED, handled.entry.responseMode to handled.entry.outcome)
            assertEquals("found", handled.entry.scenario?.name)
        }

    @Test
    fun aServiceInDynamicModeAnswersWithTheScenarioThatMatchesBest() =
        runTest {
            val gold = scenarioOf("gold customer").matching("tier=gold")
            val anyTier = scenarioOf("any customer").matching("tier")
            val customers = serviceOf("Customers", "customers", anyTier, gold).inMode(ResponseMode.DYNAMIC)
            val engine = engineOf(definitionsOf(customers))

            val handled =
                engine.handle(
                    requestFor("customers", "POST", headers = headersOf("Content-Type" to "text/plain"), body = "tier=gold".toByteArray()),
                )

            assertEquals("gold customer", handled.response.bodyText)
            assertEquals(ResponseMode.DYNAMIC to "gold customer", handled.entry.responseMode to handled.entry.scenario?.name)
        }

    @Test
    fun theErrorScenarioAnswersARequestThatNoScenarioMatches() =
        runTest {
            val gold = scenarioOf("gold customer").matching("gold")
            val unknown = scenarioOf("unknown customer", status = 422)
            val customers =
                serviceOf(
                    "Customers",
                    "customers/{id}",
                    gold,
                    unknown,
                ).inMode(ResponseMode.DYNAMIC).copy(errorScenarioId = unknown.id)
            val engine = engineOf(definitionsOf(customers))

            val handled = engine.handle(requestFor("customers/silver"))

            assertEquals(422 to "unknown customer", handled.response.status to handled.response.bodyText)
            assertEquals(Outcome.ERROR_SCENARIO_SERVED to "unknown customer", handled.entry.outcome to handled.entry.scenario?.name)
        }

    @Test
    fun aRequestThatNoScenarioMatchesGetsTheStatus404AfterTheDelayOfTheService() =
        runTest {
            val gold = scenarioOf("gold customer").matching("gold")
            val customers = serviceOf("Customers", "customers/{id}", gold).inMode(ResponseMode.DYNAMIC).copy(delayMillis = 200)
            val engine = engineOf(definitionsOf(customers))

            val handled = engine.handle(requestFor("customers/silver"))

            assertEquals(404, handled.response.status)
            assertContains(handled.response.bodyText, "No scenario of the service 'Customers' matches the request")
            assertEquals(200, currentTime)
            assertEquals(Outcome.NO_SCENARIO_MATCHED to null, handled.entry.outcome to handled.entry.scenario)
            assertEquals(handled.response.bodyText, handled.entry.explanation)
        }

    @Test
    fun aServiceInProxyModeForwardsTheRequestToItsRealUrl() =
        runTest {
            val relayedHeaders = headersOf("Content-Type" to "application/json", "Set-Cookie" to "session=abc; Path=/")
            val upstream = FakeUpstream { MockResponse(202, relayedHeaders, """{"accepted":true}""".toByteArray()) }
            val orders =
                serviceOf(
                    "Orders",
                    "orders/{id}",
                ).copy(realUrls = listOf("http://api.example.com/v2/orders/{id}"), delayMillis = 300)
            val engine = engineOf(definitionsOf(orders)) { upstreamClient = upstream }
            val sent = headersOf("Content-Type" to "application/json", "X-Request-Id" to "7")

            val handled = engine.handle(requestFor("orders/42", "PUT", "notify=true", sent, """{"paid":true}""".toByteArray()))

            val forwarded = upstream.requests.single()
            assertEquals("PUT" to "http://api.example.com/v2/orders/42?notify=true", forwarded.method to forwarded.url)
            assertEquals(sent to """{"paid":true}""", forwarded.headers to String(forwarded.body))
            val relayed = relayedHeaders + Header("Content-Length", "17")
            assertEquals(Triple(202, relayed, """{"accepted":true}"""), handled.response.parts())
            assertEquals(300, currentTime)
            assertEquals(ResponseMode.PROXY to Outcome.PROXIED, handled.entry.responseMode to handled.entry.outcome)
            assertEquals("Orders" to null, handled.entry.service?.name to handled.entry.scenario)
            assertEquals(202 to relayed, handled.entry.response.status to handled.entry.response.headers)
        }

    @Test
    fun aServiceWithoutScenariosForwardsWhateverItsModeSays() =
        runTest {
            val upstream = FakeUpstream { MockResponse(200, body = "real".toByteArray()) }
            val orders = serviceOf("Orders", "orders").inMode(ResponseMode.STATIC).copy(realUrls = listOf("http://api.example.com/orders"))
            val engine = engineOf(definitionsOf(orders)) { upstreamClient = upstream }

            val handled = engine.handle(requestFor("orders"))

            assertEquals("real", handled.response.bodyText)
            assertEquals(ResponseMode.PROXY to Outcome.PROXIED, handled.entry.responseMode to handled.entry.outcome)
        }

    @Test
    fun theErrorScenarioAnswersForARealServiceThatFails() =
        runTest {
            val upstream =
                FakeUpstream { throw UpstreamException("The real service at http://api.example.com/orders could not be reached.") }
            val down = scenarioOf("backend down", "backend down", 503)
            val orders =
                serviceOf(
                    "Orders",
                    "orders",
                    down,
                ).copy(realUrls = listOf("http://api.example.com/orders"), errorScenarioId = down.id)
            val engine = engineOf(definitionsOf(orders)) { upstreamClient = upstream }

            val handled = engine.handle(requestFor("orders"))

            assertEquals(503 to "backend down", handled.response.status to handled.response.bodyText)
            assertEquals(Outcome.UPSTREAM_FAILED to "backend down", handled.entry.outcome to handled.entry.scenario?.name)
            assertEquals("The real service at http://api.example.com/orders could not be reached.", handled.entry.explanation)
        }

    @Test
    fun aRealServiceThatFailsWithoutAnErrorScenarioGetsTheStatus502() =
        runTest {
            val upstream =
                FakeUpstream { throw UpstreamException("The real service at http://api.example.com/orders could not be reached.") }
            val orders = serviceOf("Orders", "orders").copy(realUrls = listOf("http://api.example.com/orders"))
            val engine = engineOf(definitionsOf(orders)) { upstreamClient = upstream }

            val handled = engine.handle(requestFor("orders"))

            val body = Json.parseToJsonElement(handled.response.bodyText).jsonObject
            assertEquals(502, handled.response.status)
            assertEquals(
                "The real service at http://api.example.com/orders could not be reached.",
                body.getValue("fail").jsonPrimitive.content,
            )
            assertEquals("No outbound proxy is enabled.", body.getValue("proxyInfo").jsonPrimitive.content)
            assertEquals(Outcome.UPSTREAM_FAILED to null, handled.entry.outcome to handled.entry.scenario)
        }

    @Test
    fun aServiceWithoutARealUrlCannotForwardARelativeTarget() =
        runTest {
            val engine = engineOf(definitionsOf(serviceOf("Orders", "orders")))

            val handled = engine.handle(requestFor("orders"))

            assertEquals(502, handled.response.status)
            assertContains(handled.response.bodyText, "the service has no real URL")
            assertEquals(Outcome.UPSTREAM_FAILED, handled.entry.outcome)
        }

    @Test
    fun aRelativeTargetThatNoServiceKnowsGetsTheStatus404() =
        runTest {
            val definitions = definitionsOf(serviceOf("Orders", "orders", scenarioOf("ok")))
            val engine = engineOf(definitions)

            val handled = engine.handle(requestFor("foo/bar"))

            assertEquals(404, handled.response.status)
            assertContains(handled.response.bodyText, "foo/bar")
            assertEquals(Outcome.NO_SERVICE_MATCHED, handled.entry.outcome)
            assertEquals(listOf(null, null, null), listOf(handled.entry.service, handled.entry.responseMode, handled.entry.scenario))
            assertEquals(definitions, engine.definitions)
        }

    // Inspection

    @Test
    fun inspectionHasNoSayInTheResponse() =
        runTest {
            val ok = scenarioOf("ok", "fine", 200, headersOf("X-Answer" to "ok"))
            val plain = serviceOf("Orders", "orders", ok).inMode(ResponseMode.STATIC)
            val inspected = plain.copy(requestRules = pageIdRules, requestRulesEnabled = true, inspectorName = "session-check")
            val withoutInspection = engineOf(definitionsOf(plain))
            val withInspection = engineOf(definitionsOf(inspected)) { inspectors = listOf(FailingInspector(), SessionCheckInspector()) }

            val uninspected = withoutInspection.handle(requestFor("orders"))
            val handled = withInspection.handle(requestFor("orders"))

            assertEquals(uninspected.response.parts(), handled.response.parts())
            assertEquals(emptyList(), uninspected.entry.inspectionMessages)
            val failed = "The inspector 'always-failing' failed: java.lang.IllegalStateException: no luck with GET"
            assertEquals(
                listOf(failed, "session-check: the request has no session cookie", pageIdMissing),
                handled.entry.inspectionMessages,
            )
        }

    @Test
    fun aRequestThatSatisfiesItsInspectionHasNoMessages() =
        runTest {
            val orders =
                serviceOf(
                    "Orders",
                    "orders",
                    scenarioOf("ok"),
                ).inMode(ResponseMode.STATIC).copy(requestRules = pageIdRules, requestRulesEnabled = true)
            val engine = engineOf(definitionsOf(orders)) { inspectors = listOf(TraceHeaderInspector()) }

            val handled = engine.handle(requestFor("orders", headers = headersOf("page_id" to "home", "X-Trace-Id" to "7")))

            assertEquals(emptyList(), handled.entry.inspectionMessages)
        }

    @Test
    fun onlyARequestThatAServiceHandlesIsInspected() =
        runTest {
            val engine = engineOf { inspectors = listOf(TraceHeaderInspector()) }

            val handled = engine.handle(requestFor("foo/bar"))

            assertEquals(emptyList(), handled.entry.inspectionMessages)
        }

    // The delay

    @Test
    fun requestsThatWaitDoNotPostponeOneAnother() =
        runTest {
            val slow = scenarioOf("slow").copy(delayMillis = 300)
            val engine = engineOf(definitionsOf(serviceOf("Orders", "orders", slow).inMode(ResponseMode.STATIC)))

            val handled = List(10) { async { engine.handle(requestFor("orders")) } }.awaitAll()

            assertEquals(300, currentTime)
            assertEquals(10, handled.map { it.entry.id }.distinct().size)
        }

    // The history

    @Test
    fun theTimeOfAnEntryIsWhenItsRequestArrived() =
        runTest {
            val slow = scenarioOf("slow").copy(delayMillis = 300)
            val engine = engineOf(definitionsOf(serviceOf("Orders", "orders", slow).inMode(ResponseMode.STATIC))) { clock = time }

            val first = engine.handle(requestFor("orders"))
            time.now = NOON + Duration.ofMinutes(5)
            val second = engine.handle(requestFor("orders"))

            assertEquals(listOf(NOON, NOON + Duration.ofMinutes(5)), listOf(first.entry.time, second.entry.time))
            assertEquals(listOf(second.entry, first.entry), engine.history.entries())
        }

    @Test
    fun theCaptureLimitCutsTheEntryAndNotTheResponse() =
        runTest {
            val large = scenarioOf("large", "x".repeat(5_000))
            val definitions = definitionsOf(serviceOf("Orders", "orders", large).inMode(ResponseMode.STATIC))
            val engine = engineOf(definitions) { bodyCaptureLimit = 1_024 }

            val handled = engine.handle(requestFor("orders", "POST", body = ByteArray(2_000) { 'y'.code.toByte() }))

            val entry: HistoryEntry = handled.entry
            assertEquals(5_000, handled.response.body.size)
            assertEquals(listOf(1_024, 5_000L, true), entry.response.body.let { listOf(it.bytes.size, it.size, it.isCut) })
            assertEquals(listOf(1_024, 2_000L, true), entry.request.body.let { listOf(it.bytes.size, it.size, it.isCut) })
        }

    @Test
    fun theHistoryKeepsAsManyEntriesAsTheEngineWasToldTo() =
        runTest {
            val definitions = definitionsOf(serviceOf("Orders", "orders", scenarioOf("ok")).inMode(ResponseMode.STATIC))
            val engine = engineOf(definitions) { historyLimit = 3 }

            val handled = List(4) { engine.handle(requestFor("orders")) }

            assertEquals(handled.drop(1).map { it.entry }.reversed(), engine.history.entries())
        }

    @Test
    fun aRequestWithoutAClientAddressIsHandledAllTheSame() =
        runTest {
            val engine = engineOf(definitionsOf(serviceOf("Orders", "orders", scenarioOf("ok")).inMode(ResponseMode.STATIC)))

            val handled = engine.handle(requestFor("orders"))

            assertNull(handled.entry.clientAddress)
            assertEquals("HandledRequest(entry=1, outcome=SCENARIO_SERVED, status=200)", handled.toString())
        }

    @Test
    fun anEngineIsNotCreatedWithOptionsThatMakeNoSense() {
        val failures =
            listOf<EngineOptions.() -> Unit>(
                { historyLimit = -1 },
                { bodyCaptureLimit = -1 },
                { responseTimeout = 0.seconds },
            ).map { options -> assertFailsWith<IllegalArgumentException> { engineOf(configure = options) } }

        assertEquals(
            listOf(
                "The number of history entries to keep must not be negative.",
                "The number of bytes of a body to hold must not be negative.",
                "The response timeout must be a time that passes.",
            ),
            failures.map { it.message },
        )
    }

    // The target

    @Test
    fun theTargetIsWhatFollowsTheServicePathOfTheUrl() =
        runTest {
            val engine = engineOf(definitionsOf(serviceOf("Orders", "orders/{id}", scenarioOf("ok")).inMode(ResponseMode.STATIC)))
            val urls = listOf("http://mockey.test:8080/service/orders/7", "https://[::1]/service/Orders/7/", "/service/orders/7")

            val handled = urls.map { engine.handle(MockRequest("GET", it)) }

            assertEquals(listOf("orders/7", "Orders/7/", "orders/7"), handled.map { it.entry.target })
            assertTrue(handled.all { it.entry.outcome == Outcome.SCENARIO_SERVED })
        }

    @Test
    fun aRequestThatIsNoMockTrafficIsRefused() =
        runTest {
            val engine = engineOf(definitionsOf(serviceOf("Orders", "orders", scenarioOf("ok")).inMode(ResponseMode.STATIC)))
            val urls =
                listOf(
                    "http://mockey.test:8080/status",
                    "http://mockey.test:8080",
                    "http://mockey.test:8080/services/orders",
                    "service/orders",
                )

            val failures = urls.map { url -> assertFailsWith<IllegalArgumentException> { engine.handle(MockRequest("GET", url)) } }

            val noMockTraffic = "is no request to the mock endpoint: the path of its URL does not begin with /service/."
            assertEquals("MockRequest(GET http://mockey.test:8080/status) $noMockTraffic", failures.first().message)
            assertEquals(emptyList(), engine.history.entries())
        }

    @Test
    fun theBodyOfAResponseIsTheBodyOfItsScenarioByteForByte() =
        runTest {
            val headers = listOf(Header("Content-Type", "text/plain; charset=ISO-8859-1"))
            val accented = scenarioOf("accented", "caf\u00e9", headers = headers)
            val engine = engineOf(definitionsOf(serviceOf("Menu", "menu", accented).inMode(ResponseMode.STATIC)))

            val handled = engine.handle(requestFor("menu"))

            assertContentEquals("caf\u00e9".toByteArray(Charsets.ISO_8859_1), handled.response.body)
        }
}
