package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.PlanApplyReport
import io.github.dnedrow.mockey.core.definitions.PlanChanges
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.ServiceId

/**
 * What a program does with the plans of an engine. A plan holds the settings of several services: the
 * response mode, the delay, and the default scenario of each. Applying it puts these services back into that
 * state. The operations return their results as those of [ServiceOperations] do.
 */
public class PlanOperations internal constructor(
    private val engine: MockeyEngine,
) {
    /**
     * Creates a plan. [define] gets a new plan that has an identifier and nothing else, and returns it with
     * its name, its other attributes, and its items. It must leave the identifier as it is.
     */
    public suspend fun create(define: (Plan) -> Plan): ChangeResult<Plan> = engine.change(PlanChanges.create(define))

    /** Updates the plan [id]. [change] gets the plan as it is and returns it with its new attributes and items. */
    public suspend fun update(
        id: PlanId,
        change: (Plan) -> Plan,
    ): ChangeResult<Plan> = engine.change(PlanChanges.update(id, change))

    /**
     * Saves the settings that the services [serviceIds] have now as the items of a plan, and returns the
     * plan. The plan is the one with the identifier [id] when that is given, or else the first plan with
     * the name [name], or else a new plan. [change] sets what else the plan is to have, such as its tags.
     */
    public suspend fun save(
        serviceIds: List<ServiceId>,
        name: String? = null,
        id: PlanId? = null,
        change: (Plan) -> Plan = { it },
    ): ChangeResult<Plan> = engine.change(PlanChanges.save(serviceIds, name, id, change))

    /** Deletes the plan [id], and returns it. When it was the default plan, there is no default plan after that. */
    public suspend fun delete(id: PlanId): ChangeResult<Plan> = engine.change(PlanChanges.delete(id))

    /**
     * Applies the plan [id]: every service that an item of the plan names gets the response mode and the
     * delay of that item, and the scenario that the item names becomes its default scenario. The report
     * names the items that found no service and the scenarios that their services do not have.
     */
    public suspend fun apply(id: PlanId): ChangeResult<PlanApplyReport> {
        val result = engine.change(PlanChanges.apply(id))
        if (result is ChangeResult.Changed) engine.lastUsedTimes.planApplied(id, engine.now())
        return result
    }

    /**
     * Designates the plan [id] as the default plan, which is applied when the definitions are loaded, and
     * returns it. `null` ends the designation.
     */
    public suspend fun designateDefault(id: PlanId?): ChangeResult<Plan?> = engine.change(PlanChanges.designateDefault(id))
}
