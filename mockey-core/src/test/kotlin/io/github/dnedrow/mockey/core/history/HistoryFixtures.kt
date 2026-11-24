package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.definitions.withValidDesignations
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.response.ScenarioAnswer
import io.github.dnedrow.mockey.core.routing.Target
import io.github.dnedrow.mockey.core.upstream.ProxyAnswer
import java.time.Instant

/** The time of the first request of a history test. */
internal val NOON: Instant = Instant.parse("2026-11-24T12:00:00Z")

/** Where a client addresses the mock endpoint in the history tests. */
internal const val MOCK_ENDPOINT = "http://mockey.test:8080/service/"

internal fun headersOf(vararg headers: Pair<String, String>): List<Header> = headers.map { (name, value) -> Header(name, value) }

internal fun scenarioOf(
    name: String,
    body: String = name,
    status: Int = 200,
    headers: List<Header> = emptyList(),
    tags: Tags = Tags.EMPTY,
) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body, statusCode = status, headers = headers, tags = tags)

internal fun serviceOf(
    name: String,
    mockUrl: String,
    vararg scenarios: Scenario,
    tags: Tags = Tags.EMPTY,
) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, scenarios = scenarios.toList(), tags = tags).withValidDesignations()

/** Returns a request of a client for [target] at the mock endpoint. */
internal fun requestFor(
    target: String,
    method: String = "GET",
    query: String? = null,
    headers: List<Header> = emptyList(),
    body: ByteArray = ByteArray(0),
) = MockRequest(method, MOCK_ENDPOINT + target, query, headers, body)

/** The target that [request] asks for: what follows the address of the mock endpoint. */
internal fun targetOf(request: MockRequest): Target = Target(request.url.removePrefix(MOCK_ENDPOINT))

/** How [service] handles a request that [scenario] answers. */
internal fun served(
    service: Service,
    scenario: Scenario,
): Handling = Handling.of(service, ScenarioAnswer.ScenarioServed(scenario))

/** How [service] handles a request that a real service answers with [response]. */
internal fun proxied(
    service: Service?,
    response: MockResponse = MockResponse(200),
): Handling = Handling.of(service, ProxyAnswer.Proxied(response))

/** Returns an exchange with what a test says about it, and with something plain for the rest. */
internal fun exchangeOf(
    handling: Handling,
    request: MockRequest = requestFor("customer/7"),
    time: Instant = NOON,
    clientAddress: String? = "192.0.2.10",
    receivedTarget: Target = targetOf(request),
    target: Target = receivedTarget,
    inspectionMessages: List<String> = emptyList(),
) = Exchange(time, clientAddress, request, receivedTarget, target, handling, inspectionMessages)
