package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.routing.enabledTwist

/**
 * What an import did with the definitions of a document. Every service, scenario, plan, and twist
 * configuration of the document, and every setting that the document has, is among the [additions] or among
 * the [conflicts]: nothing of a document is left out without a word.
 *
 * [appliedDefaultPlan] is the report of the default plan that the import designated and applied, or `null`
 * when it designated none.
 */
public data class ImportReport(
    public val additions: List<ImportItem>,
    public val conflicts: List<ImportItem>,
    public val appliedDefaultPlan: PlanApplyReport? = null,
)

/** What an [ImportItem] is about. */
public enum class ImportItemKind {
    SERVICE,
    SCENARIO,
    PLAN,
    TWIST_CONFIGURATION,

    /** A setting: the outbound proxy, the universal error scenario, the default plan, or the enabled twist configuration. */
    SETTING,
}

/**
 * One part of a document, and what an import made of it. An item among the additions was taken over. An item
 * among the conflicts was not, or not as it is in the document, and its [message] says why.
 *
 * [name] is the name of the definition in the document, or one of the names of a setting, see the companion
 * object. [serviceName] is the name of the service of a scenario, as the document has it.
 */
public data class ImportItem(
    public val kind: ImportItemKind,
    public val name: String,
    public val message: String,
    public val serviceName: String? = null,
) {
    public companion object {
        /** The [name] of the item for the settings of the outbound proxy. */
        public const val OUTBOUND_PROXY: String = "outbound proxy"

        /** The [name] of the item for the designation of the universal error scenario. */
        public const val UNIVERSAL_ERROR_SCENARIO: String = "universal error scenario"

        /** The [name] of the item for the designation of the default plan. */
        public const val DEFAULT_PLAN: String = "default plan"

        /** The [name] of the item for the twist configuration that is enabled. */
        public const val ENABLED_TWIST_CONFIGURATION: String = "enabled twist configuration"
    }
}

/**
 * The change that imports the definitions of a document into the definitions that there are.
 *
 * - A service whose name no service has is added with its scenarios. Names are compared without regard to
 *   letter case and surrounding whitespace. It is not added when it has no name or a name that is too long,
 *   or when another service has its mock URL pattern. A real URL that another service lists is left out.
 * - A service whose name a service has is merged into that service: the service gets the scenarios that it
 *   does not have with the same name and response body, the real URLs that no service lists, and the tags.
 *   Nothing else of the existing service changes, and none of its scenarios does.
 * - A plan and a twist configuration are added when their name is not in use.
 * - The settings of the outbound proxy, the universal error scenario, the default plan, and the enabled
 *   twist configuration are taken from the document where the definitions have none. A default plan that
 *   the import designates is applied.
 *
 * What is added keeps its identifier, unless a definition has that identifier already: then it gets a new
 * one, and what refers to it in the document refers to it under the new one. The tags of the import are
 * added to every service, scenario, and plan that is added, and to every service that is merged into.
 *
 * The import validates no rule set: a rule set that is not well formed is taken over as it is.
 */
internal object DefinitionImport {
    /** Imports [document] with the tags [tags], and yields the report. */
    fun import(
        document: Definitions,
        tags: Tags = Tags.EMPTY,
    ): Change<ImportReport> = { definitions -> Import(definitions, tags).of(document) }
}

/** One import into [definitions], which become the definitions after the import step by step. */
private class Import(
    private var definitions: Definitions,
    private val tags: Tags,
) {
    private val additions = ArrayList<ImportItem>()
    private val conflicts = ArrayList<ImportItem>()

    private val serviceIds = definitions.services.mapTo(HashSet()) { it.id }
    private val scenarioIds = definitions.services.flatMapTo(HashSet()) { service -> service.scenarios.map { it.id } }
    private val planIds = definitions.plans.mapTo(HashSet()) { it.id }
    private val twistIds = definitions.twists.mapTo(HashSet()) { it.id }

    /** Where each scenario of the document is after the import, by what the document calls it. */
    private val scenarioPlaces = HashMap<ScenarioReference, ScenarioReference>()

    /** The plans and the twist configurations that were added, by the identifiers that the document gives them. */
    private val addedPlans = HashMap<PlanId, Plan>()
    private val addedTwists = HashMap<TwistId, TwistConfiguration>()

    fun of(document: Definitions): ChangeResult.Changed<ImportReport> {
        document.services.forEach(::importService)
        document.plans.forEach(::importPlan)
        document.twists.forEach(::importTwist)
        document.settings.outboundProxy?.let(::importOutboundProxy)
        document.settings.universalErrorScenario?.let { importUniversalErrorScenario(it, document) }
        document.settings.enabledTwistId?.let { importEnabledTwist(it, document) }
        val applied = document.settings.defaultPlanId?.let { importDefaultPlan(it, document) }
        return ChangeResult.Changed(definitions, ImportReport(additions.toList(), conflicts.toList(), applied))
    }

    // Services and scenarios

    private fun importService(incoming: Service) {
        val normal = ServiceChanges.normalized(incoming)
        val service = normal.copy(scenarios = normal.scenarios.map { ScenarioChanges.normalized(it, normal) })
        val sameName = definitions.serviceNamed(service.name)
        val mockUrlOwner = ServiceChanges.ownerOfMockUrl(service.mockUrl, definitions.services)
        when {
            service.name.isEmpty() -> notAdded(service, "A service without a name was not added.")
            service.name.length > ServiceChanges.MAX_NAME_LENGTH -> notAdded(service, "${quoted(service)} was not added: $NAME_TOO_LONG")
            sameName != null -> merge(service, into = sameName)
            mockUrlOwner != null -> notAdded(service, "${quoted(service)} was not added: ${mockUrlInUse(service, mockUrlOwner)}")
            else -> add(service)
        }
    }

    private fun quoted(service: Service) = "The service '${service.name}'"

    private fun mockUrlInUse(
        service: Service,
        owner: Service,
    ) = "the service '${owner.name}' already uses its mock URL pattern '${service.mockUrl}'."

    private fun notAdded(
        service: Service,
        message: String,
    ) {
        conflicts += ImportItem(ImportItemKind.SERVICE, service.name, message)
        val because = if (service.name.isEmpty()) "because its service has no name." else "because its service '${service.name}' was not."
        service.scenarios.forEach { conflicts += scenarioItem(it, service, "The scenario '${it.name}' was not added, $because") }
    }

    private fun scenarioItem(
        scenario: Scenario,
        service: Service,
        message: String,
    ) = ImportItem(ImportItemKind.SCENARIO, scenario.name, message, service.name)

    private fun add(service: Service) {
        val id = free(service.id, serviceIds, ServiceId::generate)
        val (realUrls, listedElsewhere) = service.realUrls.partition { url -> definitions.services.none { it.lists(url) } }
        val defaultRealUrl = service.realUrls.getOrNull(service.defaultRealUrlIndex)
        val scenarios = service.scenarios.map { added(it, from = service, to = id).copy(tags = it.tags + tags) }
        val added =
            service
                .copy(
                    id = id,
                    realUrls = realUrls,
                    defaultRealUrlIndex = realUrls.indexOf(defaultRealUrl).coerceAtLeast(0),
                    tags = service.tags + tags,
                    scenarios = scenarios,
                    defaultScenarioId = service.defaultScenarioId?.let { placeOf(service.id, it) },
                    errorScenarioId = service.errorScenarioId?.let { placeOf(service.id, it) },
                ).withValidDesignations()
        definitions = definitions.copy(services = definitions.services + added)
        val with = if (scenarios.size == 1) "with 1 scenario" else "with ${scenarios.size} scenarios"
        additions += ImportItem(ImportItemKind.SERVICE, service.name, "${quoted(service)} was added $with.${leftOut(listedElsewhere)}")
        val withService = "was added with its service '${service.name}'."
        service.scenarios.forEach { additions += scenarioItem(it, service, "The scenario '${it.name}' $withService") }
    }

    /** Says which real URLs of a service were not taken over, when there are some. */
    private fun leftOut(realUrls: List<String>): String =
        when (realUrls.size) {
            0 -> ""
            1 -> " Its real URL ${realUrls.single()} was left out, because another service lists it."
            else -> " Its real URLs ${realUrls.joinToString(", ")} were left out, because other services list them."
        }

    private fun merge(
        service: Service,
        into: Service,
    ) {
        var existing = into
        for (scenario in service.scenarios) {
            val present = existing.scenarios.firstOrNull { it.hasSameNameAndResponseBodyAs(scenario) }
            if (present == null) {
                val added = added(scenario, from = service, to = existing.id).copy(tags = scenario.tags + tags)
                existing = existing.copy(scenarios = existing.scenarios + added)
                additions += scenarioItem(scenario, service, "The scenario '${scenario.name}' was added to the service '${existing.name}'.")
            } else {
                scenarioPlaces[ScenarioReference(service.id, scenario.id)] = ScenarioReference(existing.id, present.id)
                val because = "the service '${existing.name}' has a scenario with that name and response body."
                conflicts += scenarioItem(scenario, service, "The scenario '${scenario.name}' was not added: $because")
            }
        }
        val unlisted = service.realUrls.filterNot(existing::lists)
        val (realUrls, listedElsewhere) = unlisted.partition { url -> definitions.services.none { it.lists(url) } }
        val merged = existing.copy(realUrls = existing.realUrls + realUrls, tags = existing.tags + service.tags + tags)
        definitions = definitions.withService(merged.withValidDesignations())
        val because = "the service '${existing.name}' has that name. What that service did not have of it was added to it."
        conflicts +=
            ImportItem(ImportItemKind.SERVICE, service.name, "${quoted(service)} was not added: $because${leftOut(listedElsewhere)}")
    }

    /**
     * Returns [scenario] of the document's service [from] as it is added to the service [to]: under its
     * identifier, or under a new one when a scenario has that identifier already.
     */
    private fun added(
        scenario: Scenario,
        from: Service,
        to: ServiceId,
    ): Scenario {
        val id = free(scenario.id, scenarioIds, ScenarioId::generate)
        scenarioPlaces[ScenarioReference(from.id, scenario.id)] = ScenarioReference(to, id)
        return scenario.copy(id = id)
    }

    /** Returns the identifier that the scenario [scenarioId] of the document's service [serviceId] has after the import. */
    private fun placeOf(
        serviceId: ServiceId,
        scenarioId: ScenarioId,
    ): ScenarioId? = scenarioPlaces[ScenarioReference(serviceId, scenarioId)]?.scenarioId

    // Plans and twist configurations

    private fun importPlan(incoming: Plan) {
        val plan = PlanChanges.normalized(incoming)
        if (definitions.planNamed(plan.name) != null) {
            conflicts += ImportItem(ImportItemKind.PLAN, plan.name, "The plan '${plan.name}' was not added: a plan has that name.")
            return
        }
        val added = plan.copy(id = free(plan.id, planIds, PlanId::generate), tags = plan.tags + tags)
        addedPlans[incoming.id] = added
        definitions = definitions.copy(plans = definitions.plans + added)
        additions += ImportItem(ImportItemKind.PLAN, plan.name, "The plan '${plan.name}' was added.")
    }

    private fun importTwist(incoming: TwistConfiguration) {
        val twist = TwistChanges.normalized(incoming)
        if (definitions.twistNamed(twist.name) != null) {
            val message = "The twist configuration '${twist.name}' was not added: a twist configuration has that name."
            conflicts += ImportItem(ImportItemKind.TWIST_CONFIGURATION, twist.name, message)
            return
        }
        val added = twist.copy(id = free(twist.id, twistIds, TwistId::generate))
        addedTwists[incoming.id] = added
        definitions = definitions.copy(twists = definitions.twists + added)
        additions += ImportItem(ImportItemKind.TWIST_CONFIGURATION, twist.name, "The twist configuration '${twist.name}' was added.")
    }

    // Settings

    private fun setting(
        name: String,
        message: String,
    ) = ImportItem(ImportItemKind.SETTING, name, message)

    private fun importOutboundProxy(proxy: OutboundProxy) {
        if (definitions.settings.outboundProxy != null) {
            val message = "The settings of the outbound proxy were not taken from the document: the outbound proxy has settings already."
            conflicts += setting(ImportItem.OUTBOUND_PROXY, message)
            return
        }
        definitions = definitions.copy(settings = definitions.settings.copy(outboundProxy = proxy))
        additions += setting(ImportItem.OUTBOUND_PROXY, "The settings of the outbound proxy were taken from the document.")
    }

    private fun importUniversalErrorScenario(
        designated: ScenarioReference,
        document: Definitions,
    ) {
        fun notDesignated(reason: String) {
            conflicts +=
                setting(ImportItem.UNIVERSAL_ERROR_SCENARIO, "The universal error scenario of the document was not designated: $reason")
        }
        if (definitions.universalErrorScenario != null) return notDesignated("a universal error scenario is designated already.")
        val inDocument =
            document.services.firstOrNull { it.id == designated.serviceId }?.scenarios?.firstOrNull {
                it.id ==
                    designated.scenarioId
            }
        if (inDocument == null) return notDesignated("the document does not hold the scenario that it designates.")
        val place = scenarioPlaces[designated] ?: return notDesignated("its scenario '${inDocument.name}' was not added.")
        definitions = definitions.copy(settings = definitions.settings.copy(universalErrorScenario = place))
        val service = definitions.service(place.serviceId)
        val scenario = definitions.universalErrorScenario
        val message = "The scenario '${scenario?.name}' of the service '${service?.name}' was designated as the universal error scenario."
        additions += setting(ImportItem.UNIVERSAL_ERROR_SCENARIO, message)
    }

    private fun importEnabledTwist(
        enabled: TwistId,
        document: Definitions,
    ) {
        fun notEnabled(reason: String) {
            conflicts +=
                setting(
                    ImportItem.ENABLED_TWIST_CONFIGURATION,
                    "The twist configuration that the document enables was not enabled: $reason",
                )
        }
        if (definitions.enabledTwist != null) return notEnabled("a twist configuration is enabled already.")
        val inDocument = document.twists.firstOrNull { it.id == enabled } ?: return notEnabled("the document does not hold it.")
        val added = addedTwists[enabled] ?: return notEnabled("the twist configuration '${inDocument.name.trim()}' was not added.")
        definitions = definitions.copy(settings = definitions.settings.copy(enabledTwistId = added.id))
        additions += setting(ImportItem.ENABLED_TWIST_CONFIGURATION, "The twist configuration '${added.name}' was enabled.")
    }

    /** Designates the default plan of the document where there is none, applies it, and returns the report of that. */
    private fun importDefaultPlan(
        default: PlanId,
        document: Definitions,
    ): PlanApplyReport? {
        fun notDesignated(reason: String): PlanApplyReport? {
            conflicts += setting(ImportItem.DEFAULT_PLAN, "The default plan of the document was not designated: $reason")
            return null
        }
        if (definitions.defaultPlan != null) return notDesignated("a default plan is designated already.")
        val inDocument =
            document.plans.firstOrNull { it.id == default }
                ?: return notDesignated("the document does not hold the plan that it designates.")
        val added = addedPlans[default] ?: return notDesignated("the plan '${inDocument.name.trim()}' was not added.")
        val applied = PlanChanges.withDefaultPlanApplied(definitions.copy(settings = definitions.settings.copy(defaultPlanId = added.id)))
        definitions = applied.definitions
        additions += setting(ImportItem.DEFAULT_PLAN, "The plan '${added.name}' was designated as the default plan and applied.")
        return applied.value
    }

    /** Returns [id] when nothing has it, and otherwise a new identifier. The identifier that is returned is taken from then on. */
    private fun <T> free(
        id: T,
        taken: MutableSet<T>,
        generate: () -> T,
    ): T {
        val free = if (id in taken) generate() else id
        taken += free
        return free
    }

    private companion object {
        const val NAME_TOO_LONG = "its name is longer than ${ServiceChanges.MAX_NAME_LENGTH} characters."
    }
}
