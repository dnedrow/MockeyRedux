package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service

/**
 * The default scenario of the service: the scenario that [Service.defaultScenarioId] designates. When that is
 * none of the scenarios of the service, the scenario that was created first is the default scenario. Only a
 * service without scenarios has none.
 *
 * The changes to definitions keep the designation valid, see [withValidDesignations]. Definitions also come
 * from elsewhere, such as an import, so the designation of a stored service need not be.
 */
internal val Service.defaultScenario: Scenario?
    get() = scenarios.firstOrNull { it.id == defaultScenarioId } ?: scenarios.firstOrNull()

/**
 * The error scenario of the service: the scenario that [Service.errorScenarioId] designates, or `null` when the
 * service designates none, or one that is none of its scenarios.
 */
internal val Service.errorScenario: Scenario?
    get() = errorScenarioId?.let { id -> scenarios.firstOrNull { it.id == id } }

/**
 * The universal error scenario: the scenario that the settings designate, or `null` when they designate none,
 * or a scenario or a service that does not exist.
 */
internal val Definitions.universalErrorScenario: Scenario?
    get() =
        settings.universalErrorScenario?.let { designated ->
            service(designated.serviceId)?.scenarios?.firstOrNull { it.id == designated.scenarioId }
        }

/**
 * Returns the scenario that answers a request to [service] when the service cannot answer it as its mode has
 * it: the error scenario of the service, or else the universal error scenario. Returns `null` when neither is
 * designated.
 */
internal fun Definitions.errorScenarioFor(service: Service): Scenario? = service.errorScenario ?: universalErrorScenario

/**
 * The default real URL of the service: the real URL at [Service.defaultRealUrlIndex], or the first real URL
 * when there is none at that place. Only a service without real URLs has none.
 */
internal val Service.defaultRealUrl: String?
    get() = realUrls.getOrNull(defaultRealUrlIndex) ?: realUrls.firstOrNull()
