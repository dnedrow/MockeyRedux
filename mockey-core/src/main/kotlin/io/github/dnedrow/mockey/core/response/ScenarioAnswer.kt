package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.definitions.defaultScenario
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service

/** How a service answers a request with its scenarios. */
internal sealed interface ScenarioAnswer {
    /** Returns the response that the client gets. */
    fun response(): MockResponse

    /** [scenario] answers the request, as the mode of its service has it. In static mode that is the default scenario. */
    data class ScenarioServed(
        val scenario: Scenario,
    ) : ScenarioAnswer {
        override fun response(): MockResponse = scenario.response()
    }
}

/**
 * Returns how [service] answers a request in static mode: with its default scenario, whatever the request
 * holds, and whatever match criteria and method restrictions the scenarios of the service have.
 *
 * Only a service with a scenario answers in static mode. A service without one forwards its requests, see
 * [Service.effectiveResponseMode].
 */
internal fun staticAnswer(service: Service): ScenarioAnswer {
    val scenario = requireNotNull(service.defaultScenario) { "The service '${service.name}' has no scenario to answer with." }
    return ScenarioAnswer.ScenarioServed(scenario)
}
