package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.definitions.defaultScenario
import io.github.dnedrow.mockey.core.definitions.errorScenarioFor
import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.request.MockRequest

/** How a service answers a request with its scenarios. */
internal sealed interface ScenarioAnswer {
    /** The scenario that answers the request, or `null` when none does. */
    val scenario: Scenario?

    /** Returns the response that the client gets. */
    fun response(): MockResponse

    /**
     * [scenario] answers the request, as the mode of its service has it: the default scenario in static mode, and
     * the scenario that matches the request best in dynamic mode.
     */
    data class ScenarioServed(
        override val scenario: Scenario,
    ) : ScenarioAnswer {
        override fun response(): MockResponse = scenario.response()
    }

    /**
     * No scenario matches the request, and [scenario] answers in the place of one: the error scenario of the
     * service, or else the universal error scenario.
     */
    data class ErrorScenarioServed(
        override val scenario: Scenario,
    ) : ScenarioAnswer {
        override fun response(): MockResponse = scenario.response()
    }

    /**
     * No scenario matches the request, and no error scenario is designated. The request gets the status 404 and
     * the [explanation] as plain text.
     */
    data class NoScenarioMatched(
        val explanation: String,
    ) : ScenarioAnswer {
        override val scenario: Scenario?
            get() = null

        override fun response(): MockResponse = builtInResponse(NOT_FOUND, explanation)
    }
}

private const val NOT_FOUND = 404

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

/**
 * Returns how [service] answers [request] in dynamic mode. [variables] are the values that routing captured
 * for the variables of the URL pattern through which the request reaches the service.
 *
 * The scenario that matches the request best answers, see [bestMatch]. When no scenario matches, these answer
 * in its place, in this order:
 *
 * 1. The error scenario of the service.
 * 2. The universal error scenario, which can be a scenario of another service.
 * 3. The system itself, with the status 404 and an explanation.
 *
 * An error scenario answers as any scenario does: with its status code, its headers, and its body.
 */
internal fun Definitions.dynamicAnswer(
    service: Service,
    variables: Map<String, String>,
    request: MockRequest,
    ruleSets: RuleSets,
): ScenarioAnswer {
    val matched = bestMatch(service, variables, request, ruleSets)
    val error = if (matched == null) errorScenarioFor(service) else null
    return when {
        matched != null -> ScenarioAnswer.ScenarioServed(matched)
        error != null -> ScenarioAnswer.ErrorScenarioServed(error)
        else -> ScenarioAnswer.NoScenarioMatched(noScenarioMatched(service))
    }
}

private fun noScenarioMatched(service: Service): String =
    "No scenario of the service '${service.name}' matches the request, and no error scenario is defined: " +
        "the service has none, and there is no universal error scenario."
