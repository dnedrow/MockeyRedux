package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.ScenarioChanges
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.ServiceId

/**
 * What a program does with the scenarios of the services of an engine. The operations return their results
 * as those of [ServiceOperations] do.
 */
public class ScenarioOperations internal constructor(
    private val engine: MockeyEngine,
) {
    /**
     * Creates a scenario for the service [serviceId]. [define] gets a new scenario that has an identifier
     * and nothing else, and returns it with its attributes, such as `it.copy(name = "ok", responseBody = "{}")`.
     * It must leave the identifier as it is.
     *
     * The first scenario of a service is its default scenario. A scenario without a name gets a generated
     * one. A name of more than 250 characters is rejected, and so is a match criterion that is a rule set
     * which is not well formed.
     */
    public suspend fun create(
        serviceId: ServiceId,
        define: (Scenario) -> Scenario,
    ): ChangeResult<Scenario> = engine.change(ScenarioChanges.create(serviceId, define))

    /**
     * Updates the scenario [scenarioId] of the service [serviceId]. [change] gets the scenario as it is and
     * returns it with its new attributes. The rules of [create] hold. A new name goes to the plans that name
     * the scenario as well.
     */
    public suspend fun update(
        serviceId: ServiceId,
        scenarioId: ScenarioId,
        change: (Scenario) -> Scenario,
    ): ChangeResult<Scenario> = engine.change(ScenarioChanges.update(serviceId, scenarioId, change))

    /**
     * Deletes the scenario [scenarioId] of the service [serviceId], and returns it. What designated the
     * scenario designates it no longer: when it was the default scenario, the scenario that was created first
     * takes its place.
     */
    public suspend fun delete(
        serviceId: ServiceId,
        scenarioId: ScenarioId,
    ): ChangeResult<Scenario> = engine.change(ScenarioChanges.delete(serviceId, scenarioId))

    /**
     * Designates [scenario] as the universal error scenario, which answers where a service has no error
     * scenario of its own. `null` ends the designation.
     */
    public suspend fun designateUniversalError(scenario: ScenarioReference?): ChangeResult<Unit> =
        engine.change(ScenarioChanges.designateUniversalError(scenario))
}
