package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId

/** What applying a plan did: the [plan], the items that were skipped, and the scenario names that named no scenario. */
public data class PlanApplyReport(
    public val plan: Plan,
    public val skippedItems: List<PlanItem>,
    public val unresolvedScenarios: List<UnresolvedScenario>,
)

/**
 * A scenario name of a plan item that names no scenario of the service of that item. [serviceName] is the name
 * of the service as the service has it, and [scenarioName] is the name as the item has it.
 */
public data class UnresolvedScenario(
    public val serviceName: String,
    public val scenarioName: String,
)

/**
 * The changes that create, save, update, apply, and delete a plan, and the change that designates the default
 * plan.
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

    /**
     * Applies the plan [id] and yields a report.
     *
     * Each item of the plan puts the service that it refers to into the state of the item, see
     * [serviceNamed]: the service gets the delay and the response mode of the item, and the scenario that the
     * item refers to becomes its default scenario, see [scenarioNamed]. The items apply in their order, so of
     * several items for one service, the last one counts. Every service that no item refers to stays as it is.
     *
     * An item that refers to no service is skipped. An item that refers to no scenario of its service leaves
     * the default scenario of that service as it is, and sets its delay and its response mode all the same. The
     * report names both. An item without a scenario name leaves the default scenario as it is as well, and
     * there is nothing to report about it.
     */
    fun apply(id: PlanId): Change<PlanApplyReport> =
        { definitions ->
            val plan = definitions.plan(id)
            if (plan == null) planNotFound(id) else applied(definitions, plan)
        }

    /**
     * Applies the default plan, as [apply] does, and yields its report. Without a default plan, nothing changes
     * and the change yields `null`. Definitions get their default plan applied after they are loaded.
     */
    fun applyDefault(): Change<PlanApplyReport?> =
        { definitions ->
            val plan = definitions.defaultPlan
            if (plan == null) ChangeResult.Changed(definitions, null) else applied(definitions, plan)
        }

    private fun applied(
        definitions: Definitions,
        plan: Plan,
    ): ChangeResult.Changed<PlanApplyReport> {
        val skipped = mutableListOf<PlanItem>()
        val unresolved = mutableListOf<UnresolvedScenario>()
        var result = definitions
        for (item in plan.items) {
            val service = result.serviceNamed(item.serviceName)
            if (service == null) {
                skipped += item
                continue
            }
            val scenario = item.scenarioName?.let(service::scenarioNamed)
            if (item.scenarioName != null && scenario == null) unresolved += UnresolvedScenario(service.name, item.scenarioName)
            result =
                result.withService(
                    service.copy(
                        delayMillis = item.delayMillis,
                        responseMode = item.responseMode,
                        defaultScenarioId = scenario?.id ?: service.defaultScenarioId,
                    ),
                )
        }
        return ChangeResult.Changed(result, PlanApplyReport(plan, skipped, unresolved))
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

/**
 * Returns the definitions in which every plan item that refers to [service] names it [newName], see
 * [serviceNamed]. [service] is the service as these definitions have it, before it is renamed.
 */
internal fun Definitions.withServiceRenamedInPlans(
    service: Service,
    newName: String,
): Definitions = withPlanItems { item -> if (refersTo(item, service)) item.copy(serviceName = newName) else item }

/**
 * Returns the definitions in which every plan item that refers to [scenario] of [service] names the scenario
 * [newName], see [serviceNamed] and [scenarioNamed]. [service] and [scenario] are as these definitions have
 * them, before the scenario is renamed.
 */
internal fun Definitions.withScenarioRenamedInPlans(
    service: Service,
    scenario: Scenario,
    newName: String,
): Definitions =
    withPlanItems { item ->
        val named = item.scenarioName?.let(service::scenarioNamed)
        if (refersTo(item, service) && named?.id == scenario.id) item.copy(scenarioName = newName) else item
    }

private fun Definitions.refersTo(
    item: PlanItem,
    service: Service,
): Boolean = serviceNamed(item.serviceName)?.id == service.id

/** Returns the definitions with every item of every plan as [change] returns it. A plan without a changed item stays the same value. */
private fun Definitions.withPlanItems(change: (PlanItem) -> PlanItem): Definitions {
    val changed =
        plans.map { plan ->
            val items = plan.items.map(change)
            if (items == plan.items) plan else plan.copy(items = items)
        }
    return if (changed == plans) this else copy(plans = changed)
}
