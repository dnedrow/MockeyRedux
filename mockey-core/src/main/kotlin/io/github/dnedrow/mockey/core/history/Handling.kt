package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.response.ScenarioAnswer
import io.github.dnedrow.mockey.core.response.builtInResponse
import io.github.dnedrow.mockey.core.upstream.ProxyAnswer

/**
 * How a request to the mock endpoint was handled: the [response] that the client gets, and what the history
 * entry of the request says about where it came from.
 *
 * [service] is the service that handled the request, and [responseMode] is the mode in which it did.
 * [scenario] is the scenario that gave the response. [explanation] says why the system answered in the place
 * of a scenario or a real service.
 */
internal class Handling(
    val service: Service?,
    val responseMode: ResponseMode?,
    val scenario: Scenario?,
    val outcome: Outcome,
    val response: MockResponse,
    val explanation: String? = null,
) {
    companion object {
        private const val NOT_FOUND = 404

        /** Returns how [service] handled a request that it answered with its scenarios, in static or in dynamic mode. */
        fun of(
            service: Service,
            answer: ScenarioAnswer,
        ): Handling {
            val (outcome, explanation) =
                when (answer) {
                    is ScenarioAnswer.ScenarioServed -> Outcome.SCENARIO_SERVED to null
                    is ScenarioAnswer.ErrorScenarioServed -> Outcome.ERROR_SCENARIO_SERVED to null
                    is ScenarioAnswer.NoScenarioMatched -> Outcome.NO_SCENARIO_MATCHED to answer.explanation
                }
            return Handling(service, service.effectiveResponseMode, answer.scenario, outcome, answer.response(), explanation)
        }

        /**
         * Returns how a request was handled that was to be forwarded to a real service. [service] is the
         * service that forwarded it, or `null` for a request that was forwarded without a service.
         *
         * The outcome is an upstream failure also where an error scenario answered in the place of the real
         * service. The entry then names that scenario, and it says what the failure was.
         */
        fun of(
            service: Service?,
            answer: ProxyAnswer,
        ): Handling =
            when (answer) {
                is ProxyAnswer.Proxied -> {
                    Handling(service, ResponseMode.PROXY, null, Outcome.PROXIED, answer.response())
                }

                is ProxyAnswer.UpstreamFailed -> {
                    Handling(service, ResponseMode.PROXY, answer.scenario, Outcome.UPSTREAM_FAILED, answer.response(), answer.failure)
                }

                is ProxyAnswer.NotForwarded -> {
                    Handling(service, ResponseMode.PROXY, null, Outcome.UPSTREAM_FAILED, answer.response(), answer.explanation)
                }
            }

        /**
         * Returns how a request was handled that no service matched and that was not forwarded: it gets the
         * status 404 and the [explanation] as plain text.
         */
        fun noServiceMatched(explanation: String): Handling =
            Handling(null, null, null, Outcome.NO_SERVICE_MATCHED, builtInResponse(NOT_FOUND, explanation), explanation)
    }
}
