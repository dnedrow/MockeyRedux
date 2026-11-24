package io.github.dnedrow.mockey.core.definitions

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
