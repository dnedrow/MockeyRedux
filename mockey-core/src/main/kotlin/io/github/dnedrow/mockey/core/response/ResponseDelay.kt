package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import kotlinx.coroutines.delay

/**
 * Returns how long the response to a request of [service] is held back before it is sent, in milliseconds.
 * [scenario] is the scenario that gives the response, or `null` for a response that no scenario gives, such
 * as the response of a real service or of the system itself.
 *
 * The response of a scenario waits for the delay of that scenario when it is greater than 0. That delay takes
 * the place of the delay of the service, whether it is the longer or the shorter one, and it counts for an
 * error scenario as for any other. Every other response waits for the delay of the service. A delay that is
 * not greater than 0 is none.
 */
internal fun responseDelayMillis(
    service: Service,
    scenario: Scenario?,
): Long {
    val ofScenario = scenario?.delayMillis ?: 0
    return if (ofScenario > 0) ofScenario else service.delayMillis.coerceAtLeast(0)
}

/**
 * Waits for the delay of the response to a request of [service], see [responseDelayMillis].
 *
 * The wait suspends the coroutine that handles the request and holds no thread. It therefore does not postpone
 * the handling of any other request, however many requests wait at the same time.
 */
internal suspend fun awaitResponseDelay(
    service: Service,
    scenario: Scenario?,
) {
    val millis = responseDelayMillis(service, scenario)
    if (millis > 0) delay(millis)
}
