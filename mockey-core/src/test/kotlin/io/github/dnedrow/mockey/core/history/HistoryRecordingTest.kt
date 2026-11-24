package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.inspection.Inspectors
import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.inspection.inspectionMessages
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.request.Cookie
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.response.dynamicAnswer
import io.github.dnedrow.mockey.core.response.staticAnswer
import io.github.dnedrow.mockey.core.routing.Route
import io.github.dnedrow.mockey.core.routing.RoutingTable
import io.github.dnedrow.mockey.core.routing.Target
import io.github.dnedrow.mockey.core.routing.twisted
import io.github.dnedrow.mockey.core.upstream.ForwardTarget
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamException
import io.github.dnedrow.mockey.core.upstream.UpstreamRequest
import io.github.dnedrow.mockey.core.upstream.UpstreamSession
import io.github.dnedrow.mockey.core.upstream.proxyAnswer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the history records of a request: each kind of answer that a request can get is given by the code that
 * answers requests, and recorded.
 */
class HistoryRecordingTest {
    private val history = HistoryStore()

    private val gold =
        scenarioOf(
            "gold customer",
            """{"tier":"gold"}""",
            201,
            headersOf("Content-Type" to "application/json", "X-Tier" to "gold"),
            Tags.of("vip"),
        ).copy(matchCriterion = MatchCriterion.Text("gold"))
    private val backendDown = scenarioOf("backend down", "backend down", 503, headersOf("Retry-After" to "30"), Tags.of("failure"))
    private val universalError = scenarioOf("universal error", "universal error", 500)
    private val holder = serviceOf("Errors", "errors", universalError).copy(responseMode = ResponseMode.STATIC)

    /** A service in dynamic mode whose scenario `gold customer` matches a request that holds `gold`. */
    private val customers = serviceOf("Customers", "customer/{id}", gold, tags = Tags.of("crm")).copy(responseMode = ResponseMode.DYNAMIC)
    private val orders = serviceOf("Orders", "http://api.example.com/orders", tags = Tags.of("erp"))

    private fun Service.withError() = copy(scenarios = scenarios + backendDown, errorScenarioId = backendDown.id)

    private fun definitions(
        vararg services: Service,
        settings: Settings = Settings(),
    ) = Definitions(services = services.toList() + holder, settings = settings)

    /** Records how [service] answers [request] with its scenarios, in the mode that it has. */
    private fun recordScenarioAnswer(
        service: Service,
        request: MockRequest,
        definitions: Definitions = definitions(service),
    ): HistoryEntry {
        val variables = (RoutingTable(definitions).route(targetOf(request), Tags.EMPTY, true) as Route.ToService).variables
        val answer =
            when (service.effectiveResponseMode) {
                ResponseMode.STATIC -> staticAnswer(service)
                else -> definitions.dynamicAnswer(service, variables, request, RuleSets(definitions))
            }
        return history.record(exchangeOf(Handling.of(service, answer), request))
    }

    /** Records how a request is answered that [service] forwards through [client]. */
    private fun recordProxyAnswer(
        service: Service?,
        client: UpstreamClient,
        definitions: Definitions = definitions(orders),
        target: ForwardTarget = ForwardTarget.To("http://api.example.com/orders"),
    ): HistoryEntry {
        val request = requestFor("http://api.example.com/orders", method = "POST", body = "ordered".toByteArray())
        val answer = runBlocking { definitions.proxyAnswer(service ?: orders, request, target, client, UpstreamSession()) }
        return history.record(exchangeOf(Handling.of(service, answer), request))
    }

    private fun answering(response: MockResponse) =
        object : UpstreamClient {
            override suspend fun send(request: UpstreamRequest): MockResponse = response
        }

    private val failing =
        object : UpstreamClient {
            override suspend fun send(request: UpstreamRequest): MockResponse =
                throw UpstreamException(
                    "The real service at http://api.example.com/orders gave no complete answer: no connection could be made.",
                )
        }

    // Recording handled requests

    @Test
    fun theAnswerOfAScenarioIsRecordedWithTheRequestAndTheResponse() {
        val sent =
            headersOf("Content-Type" to "application/x-www-form-urlencoded", "Cookie" to "sid=abc; theme=dark", "X-Request-Id" to "7")
        val request = requestFor("customer/7", "POST", "expand=orders&page=2", sent, "tier=gold&note=two+words".toByteArray())

        val entry = recordScenarioAnswer(customers, request)

        assertEquals(RecordedService(customers.id, "Customers", Tags.of("crm")), entry.service)
        assertEquals(ResponseMode.DYNAMIC, entry.responseMode)
        assertEquals(RecordedScenario(gold.id, "gold customer", Tags.of("vip")), entry.scenario)
        assertEquals(Outcome.SCENARIO_SERVED, entry.outcome)
        assertEquals("POST" to "http://mockey.test:8080/service/customer/7", entry.request.method to entry.request.url)
        assertEquals("expand=orders&page=2", entry.request.query)
        assertEquals(sent, entry.request.headers)
        assertEquals(
            listOf(Parameter("expand", "orders"), Parameter("page", "2"), Parameter("tier", "gold"), Parameter("note", "two words")),
            entry.request.parameters,
        )
        assertEquals(listOf(Cookie("sid", "abc"), Cookie("theme", "dark")), entry.request.cookies)
        assertEquals("tier=gold&note=two+words", entry.request.bodyText)
        assertEquals(201, entry.response.status)
        assertEquals(headersOf("Content-Type" to "application/json", "X-Tier" to "gold"), entry.response.headers)
        assertEquals("""{"tier":"gold"}""", entry.response.bodyText)
        assertEquals("customer/7", entry.target)
        assertNull(entry.explanation)
    }

    @Test
    fun anEntryHasAnIdentifierOfItsOwnTheTimeTheClientAddressAndTheInspectionMessages() {
        val rule = """{"key":"page_id","value_rule_arg":"","value_rule_type":"string_required","desc":"page_id is mandatory"}"""
        val rules = """{"headers":[$rule]}"""
        val inspected = customers.copy(requestRules = rules, requestRulesEnabled = true)
        val definitions = definitions(inspected)
        val request = requestFor("customer/7", query = "tier=gold")
        val messages = inspectionMessages(inspected, request, Inspectors.NONE, RuleSets(definitions))
        val handling = Handling.of(inspected, definitions.dynamicAnswer(inspected, mapOf("id" to "7"), request, RuleSets(definitions)))

        val first = history.record(exchangeOf(handling, request, NOON, "192.0.2.10", inspectionMessages = messages))
        val second = history.record(exchangeOf(handling, request, NOON.plusSeconds(5), clientAddress = null))

        assertEquals(listOf(HistoryEntryId(1), HistoryEntryId(2)), listOf(first.id, second.id))
        assertEquals(NOON to NOON.plusSeconds(5), first.time to second.time)
        assertEquals("192.0.2.10" to null, first.clientAddress to second.clientAddress)
        val failedRule = "The request fails a rule of the group 'headers' for the key 'page_id', which requires a value."
        assertEquals(listOf("$failedRule Description: page_id is mandatory"), first.inspectionMessages)
        assertEquals(emptyList(), second.inspectionMessages)
        assertFalse(first.flagged)
        assertEquals(Outcome.SCENARIO_SERVED, first.outcome)
    }

    @Test
    fun aProxiedExchangeIsRecordedWithTheAnswerOfTheRealService() {
        val body = """{"orders":[1,2]}""".toByteArray()
        val upstream = MockResponse(200, headersOf("content-type" to "application/json", "set-cookie" to "sid=abc; Path=/"), body)

        val entry = recordProxyAnswer(orders, answering(upstream))

        assertEquals(RecordedService(orders.id, "Orders", Tags.of("erp")), entry.service)
        assertEquals(ResponseMode.PROXY, entry.responseMode)
        assertNull(entry.scenario)
        assertEquals(Outcome.PROXIED, entry.outcome)
        assertEquals(200, entry.response.status)
        val relayed = headersOf("content-type" to "application/json", "set-cookie" to "sid=abc; Path=/", "Content-Length" to "16")
        assertEquals(relayed, entry.response.headers)
        assertContentEquals(body, entry.response.body.bytes)
        assertEquals("ordered", entry.request.bodyText)
        assertNull(entry.explanation)
    }

    @Test
    fun theDefaultScenarioOfAServiceInStaticModeIsRecordedAsServed() {
        val static = customers.copy(responseMode = ResponseMode.STATIC)

        val entry = recordScenarioAnswer(static, requestFor("customer/7"))

        assertEquals(ResponseMode.STATIC, entry.responseMode)
        assertEquals("gold customer", entry.scenario?.name)
        assertEquals(Outcome.SCENARIO_SERVED, entry.outcome)
    }

    // The outcome of a handled request

    @Test
    fun theErrorScenarioThatAnswersForNoMatchIsRecordedWithItsName() {
        val entry = recordScenarioAnswer(customers.withError(), requestFor("customer/7", query = "tier=silver"))

        assertEquals(Outcome.ERROR_SCENARIO_SERVED, entry.outcome)
        assertEquals(RecordedScenario(backendDown.id, "backend down", Tags.of("failure")), entry.scenario)
        assertEquals(ResponseMode.DYNAMIC, entry.responseMode)
        assertEquals(503 to "backend down", entry.response.status to entry.response.bodyText)
    }

    @Test
    fun theUniversalErrorScenarioThatAnswersForNoMatchIsRecordedWithItsName() {
        val settings = Settings(universalErrorScenario = ScenarioReference(holder.id, universalError.id))

        val entry = recordScenarioAnswer(customers, requestFor("customer/7"), definitions(customers, settings = settings))

        assertEquals(Outcome.ERROR_SCENARIO_SERVED, entry.outcome)
        assertEquals("universal error", entry.scenario?.name)
        assertEquals("Customers", entry.service?.name)
    }

    @Test
    fun aRequestThatNoScenarioMatchesAndNoErrorScenarioAnswersIsRecordedAsSuch() {
        val entry = recordScenarioAnswer(customers, requestFor("customer/7", query = "tier=silver"))

        assertEquals(Outcome.NO_SCENARIO_MATCHED, entry.outcome)
        assertNull(entry.scenario)
        assertEquals("Customers" to ResponseMode.DYNAMIC, entry.service?.name to entry.responseMode)
        assertEquals(404, entry.response.status)
        assertEquals(entry.response.bodyText, entry.explanation)
        assertTrue(entry.explanation.orEmpty().startsWith("No scenario of the service 'Customers' matches the request"))
    }

    @Test
    fun aRequestThatNoServiceHandlesIsRecordedWithoutAService() {
        val request = requestFor("foo/bar")
        val route = assertIs<Route.NotFound>(RoutingTable(definitions(customers)).route(targetOf(request), Tags.EMPTY, true))

        val entry = history.record(exchangeOf(Handling.noServiceMatched(route.explanation), request))

        assertEquals(Outcome.NO_SERVICE_MATCHED, entry.outcome)
        assertNull(entry.service)
        assertNull(entry.responseMode)
        assertNull(entry.scenario)
        assertEquals(404 to "No service matches the target 'foo/bar'.", entry.response.status to entry.response.bodyText)
        assertEquals("No service matches the target 'foo/bar'.", entry.explanation)
        assertEquals("foo/bar", entry.target)
    }

    @Test
    fun anUpstreamFailureIsRecordedWithWhatItWas() {
        val entry = recordProxyAnswer(orders, failing)

        assertEquals(Outcome.UPSTREAM_FAILED, entry.outcome)
        assertNull(entry.scenario)
        assertEquals("Orders" to ResponseMode.PROXY, entry.service?.name to entry.responseMode)
        assertEquals(502, entry.response.status)
        assertEquals(
            "The real service at http://api.example.com/orders gave no complete answer: no connection could be made.",
            entry.explanation,
        )
    }

    @Test
    fun anUpstreamFailureThatAnErrorScenarioAnswersStaysAnUpstreamFailureAndNamesTheScenario() {
        val withError = orders.withError()

        val entry = recordProxyAnswer(withError, failing, definitions(withError))

        assertEquals(Outcome.UPSTREAM_FAILED, entry.outcome)
        assertEquals(RecordedScenario(backendDown.id, "backend down", Tags.of("failure")), entry.scenario)
        assertEquals(503 to "backend down", entry.response.status to entry.response.bodyText)
        assertEquals(
            "The real service at http://api.example.com/orders gave no complete answer: no connection could be made.",
            entry.explanation,
        )
    }

    @Test
    fun aRequestWithNowhereToGoIsRecordedAsAnUpstreamFailure() {
        val explanation = "The service 'Orders' cannot forward the request for 'orders': the service has no real URL."

        val entry = recordProxyAnswer(orders, answering(MockResponse(200)), target = ForwardTarget.Nowhere(explanation))

        assertEquals(Outcome.UPSTREAM_FAILED, entry.outcome)
        assertEquals(502 to explanation, entry.response.status to entry.response.bodyText)
        assertEquals(explanation, entry.explanation)
        assertEquals(ResponseMode.PROXY, entry.responseMode)
    }

    @Test
    fun aRequestThatIsForwardedWithoutAServiceIsRecordedInProxyModeWithoutAService() {
        val entry = recordProxyAnswer(service = null, answering(MockResponse(204)))

        assertEquals(Outcome.PROXIED, entry.outcome)
        assertNull(entry.service)
        assertEquals(ResponseMode.PROXY, entry.responseMode)
        assertEquals(204, entry.response.status)
    }

    @Test
    fun everyAnswerHasExactlyOneOutcome() {
        assertEquals(
            listOf("SCENARIO_SERVED", "ERROR_SCENARIO_SERVED", "NO_SCENARIO_MATCHED", "PROXIED", "UPSTREAM_FAILED", "NO_SERVICE_MATCHED"),
            Outcome.entries.map { it.name },
        )
    }

    // Twisted targets

    @Test
    fun aTargetThatTwistingChangedIsRecordedAsItWasAndAsItWasUsed() {
        val toQa2 = TwistConfiguration(TwistId("twist-1"), "to-qa2", listOf(TwistPair("qa1", "qa2")))
        val definitions = Definitions(twists = listOf(toQa2), settings = Settings(enabledTwistId = toQa2.id))
        val request = requestFor("http://qa1.example.com/orders")
        val received = targetOf(request)

        val entry = history.record(exchangeOf(proxied(null), request, receivedTarget = received, target = definitions.twisted(received)))

        assertEquals("http://qa1.example.com/orders", entry.targetBeforeTwisting)
        assertEquals("http://qa2.example.com/orders", entry.target)
        assertEquals("http://mockey.test:8080/service/http://qa1.example.com/orders", entry.request.url)
    }

    @Test
    fun aTargetThatTwistingLeftAloneIsRecordedOnce() {
        val toQa2 = TwistConfiguration(TwistId("twist-1"), "to-qa2", listOf(TwistPair("qa1", "qa2")))
        val definitions = Definitions(twists = listOf(toQa2), settings = Settings(enabledTwistId = toQa2.id))
        val request = requestFor("http://qa3.example.com/orders")
        val received = targetOf(request)

        val twisted = history.record(exchangeOf(proxied(null), request, receivedTarget = received, target = definitions.twisted(received)))
        val untwisted =
            history.record(
                exchangeOf(proxied(null), request, receivedTarget = received, target = Definitions.EMPTY.twisted(received)),
            )

        assertEquals("http://qa3.example.com/orders" to null, twisted.target to twisted.targetBeforeTwisting)
        assertEquals("http://qa3.example.com/orders" to null, untwisted.target to untwisted.targetBeforeTwisting)
        assertEquals(Target("http://qa3.example.com/orders"), received)
    }
}
