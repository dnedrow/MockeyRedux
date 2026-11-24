package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId

/**
 * The changes that create, save, update, and delete a plan, and the change that designates the default plan.
 *
 * A plan is stored in a normal form. Its name has no surrounding whitespace, and a plan without a name gets a
 * generated one. A blank description is absent. The service name and the scenario name of an item have no
 * surrounding whitespace, a blank scenario name is absent, and an item without a service name is left out,
 * because it could never apply.
 *
 * A plan refers to services and to scenarios by their names, so it can name ones that do not exist. No change
 * of a plan changes a service: only applying a plan does.
 */
internal object PlanChanges {
    /** The name of a plan that was given none. */
    const val GENERATED_NAME = "Plan (auto-generated-name)"

    /**
     * Creates a plan. [define] gets a new plan that has an identifier and nothing else, and returns it with its
     * attributes and its items. Creating a plan does not make it the default plan.
     */
    fun create(define: (Plan) -> Plan): Change<Plan> =
        { definitions ->
            val plan = normalized(attributesChanged(Plan(id = PlanId.generate(), name = ""), define))
            ChangeResult.Changed(definitions.copy(plans = definitions.plans + plan), plan)
        }

    /** Updates the plan [id]. [change] gets the plan as it is and returns it with its new attributes and items. */
    fun update(
        id: PlanId,
        change: (Plan) -> Plan,
    ): Change<Plan> =
        { definitions ->
            val current = definitions.plan(id)
            if (current == null) {
                planNotFound(id)
            } else {
                val plan = normalized(attributesChanged(current, change))
                ChangeResult.Changed(definitions.withPlan(plan), plan)
            }
        }

    /**
     * Saves the settings that the services [serviceIds] have now as a plan, and yields that plan. The plan
     * gets one item for each of the services, in their order: the name of the service, the name of its default
     * scenario, its response mode, and its delay. A service that is named more than once gets one item.
     *
     * The save updates a plan that exists: the plan [id] when an identifier is given, and otherwise the plan
     * whose name is [name], without regard to letter case and surrounding whitespace. The items of that plan
     * are replaced, and the plan takes [name] as its name, as it is written. Its other attributes stay as they
     * are. When no plan is identified, the save creates one. A new plan that is given no name gets a generated
     * one, and a plan that exists keeps its name when none is given.
     *
     * [change] gets the plan with its name and its new items, and returns it with further attributes, such as
     * its tags.
     */
    fun save(
        serviceIds: List<ServiceId>,
        name: String? = null,
        id: PlanId? = null,
        change: (Plan) -> Plan = { it },
    ): Change<Plan> =
        { definitions ->
            val ids = serviceIds.distinct()
            val missing = ids.firstOrNull { definitions.service(it) == null }
            val given = name?.trim()?.takeIf { it.isNotEmpty() }
            val existing = if (id != null) definitions.plan(id) else given?.let(definitions::planNamed)
            if (id != null && existing == null) {
                planNotFound(id)
            } else if (missing != null) {
                serviceNotFound(missing)
            } else {
                val current = existing ?: Plan(id = PlanId.generate(), name = "")
                val snapshot = current.copy(name = given ?: current.name, items = ids.mapNotNull(definitions::service).map(::itemOf))
                val plan = normalized(attributesChanged(snapshot, change))
                val plans = if (existing == null) definitions.plans + plan else definitions.withPlan(plan).plans
                ChangeResult.Changed(definitions.copy(plans = plans), plan)
            }
        }

    /**
     * Deletes the plan [id] and yields it. No service changes, also not one that the plan was applied to. When
     * the plan was the default plan, no plan is the default plan.
     */
    fun delete(id: PlanId): Change<Plan> =
        { definitions ->
            val plan = definitions.plan(id)
            if (plan == null) {
                planNotFound(id)
            } else {
                val settings = definitions.settings
                ChangeResult.Changed(
                    definitions.copy(
                        plans = definitions.plans.filter { it.id != id },
                        settings = settings.copy(defaultPlanId = settings.defaultPlanId.takeUnless { it == id }),
                    ),
                    plan,
                )
            }
        }

    /**
     * Designates the plan [id] as the default plan and yields it, or ends the designation when [id] is `null`.
     * At most one plan is the default plan: designating one ends the designation of the one before it.
     */
    fun designateDefault(id: PlanId?): Change<Plan?> =
        { definitions ->
            val plan = id?.let(definitions::plan)
            if (id != null && plan == null) {
                planNotFound(id)
            } else {
                ChangeResult.Changed(definitions.copy(settings = definitions.settings.copy(defaultPlanId = id)), plan)
            }
        }

    /** Returns the item that holds the settings that [service] has now. */
    private fun itemOf(service: Service): PlanItem =
        PlanItem(service.name, service.defaultScenario?.name, service.responseMode, service.delayMillis)

    /** Applies [change], which may set the attributes and the items of the plan but not its identifier. */
    private fun attributesChanged(
        before: Plan,
        change: (Plan) -> Plan,
    ): Plan {
        val after = change(before)
        require(after.id == before.id) { "The identifier of a plan cannot be changed." }
        return after
    }

    private fun normalized(plan: Plan): Plan =
        plan.copy(
            name = plan.name.trim().ifEmpty { GENERATED_NAME },
            description = plan.description?.takeIf { it.isNotBlank() },
            items = plan.items.map(::normalized).filter { it.serviceName.isNotEmpty() },
        )

    private fun normalized(item: PlanItem): PlanItem =
        item.copy(serviceName = item.serviceName.trim(), scenarioName = item.scenarioName?.trim()?.takeIf { it.isNotEmpty() })
}

internal fun Definitions.plan(id: PlanId): Plan? = plans.firstOrNull { it.id == id }

/**
 * Returns the plan whose name is [name], without regard to letter case and surrounding whitespace, or `null`
 * when there is none. Of several plans with that name, it is the one that was created first.
 */
internal fun Definitions.planNamed(name: String): Plan? = plans.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

/** The default plan: the plan that the settings designate, or `null` when they designate none, or one that does not exist. */
internal val Definitions.defaultPlan: Plan?
    get() = settings.defaultPlanId?.let(::plan)

/** Returns the definitions with [plan] in the place of the plan that has its identifier. */
internal fun Definitions.withPlan(plan: Plan): Definitions = copy(plans = plans.map { if (it.id == plan.id) plan else it })

internal fun planNotFound(id: PlanId): ChangeResult.NotFound = ChangeResult.NotFound("No plan has the identifier ${id.value}.")
