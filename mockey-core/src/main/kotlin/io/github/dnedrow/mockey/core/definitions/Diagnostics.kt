package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId

/** What a [Diagnostic] found. */
public enum class DiagnosticKind {
    /** Another service has the same mock URL pattern. */
    SHARED_MOCK_URL,

    /** Another service lists the same real URL. */
    SHARED_REAL_URL,

    /** Scenarios of the service have the same name and the same response body. */
    DUPLICATE_SCENARIOS,

    /** The service names an inspector that is not installed. */
    UNKNOWN_INSPECTOR,

    /** A rule set of the service or of one of its scenarios is not well formed. */
    UNREADABLE_RULE_SET,
}

/**
 * Something about a service that deserves attention but blocks nothing. [otherServiceId] is the service that
 * shares a URL, and [scenarioIds] are the scenarios that the diagnostic is about, where it is about scenarios.
 */
public data class Diagnostic(
    public val kind: DiagnosticKind,
    public val serviceId: ServiceId,
    public val serviceName: String,
    public val message: String,
    public val otherServiceId: ServiceId? = null,
    public val scenarioIds: List<ScenarioId> = emptyList(),
)

/**
 * Returns the diagnostics for these definitions, service by service. [installedInspectors] are the names of
 * the inspectors that a service can name, and [isWellFormedRuleSet] tells whether the text of a rule set is a
 * well-formed rule set. URLs, names, and response bodies are compared as the changes to services and the merge
 * of services compare them.
 */
internal fun Definitions.diagnostics(
    installedInspectors: Set<String>,
    isWellFormedRuleSet: (String) -> Boolean,
): List<Diagnostic> =
    services.flatMap { service ->
        val others = services.filter { it.id != service.id }
        sharedMockUrls(service, others) +
            sharedRealUrls(service, others) +
            duplicateScenarios(service) +
            listOfNotNull(unknownInspector(service, installedInspectors)) +
            unreadableRuleSets(service, isWellFormedRuleSet)
    }

private fun sharedMockUrls(
    service: Service,
    others: List<Service>,
): List<Diagnostic> =
    others
        .filter { it.mockUrl.trim().equals(service.mockUrl.trim(), ignoreCase = true) }
        .map { other ->
            val message = "The service '${other.name}' has the same mock URL pattern '${service.mockUrl.trim()}'."
            Diagnostic(DiagnosticKind.SHARED_MOCK_URL, service.id, service.name, message, otherServiceId = other.id)
        }

private fun sharedRealUrls(
    service: Service,
    others: List<Service>,
): List<Diagnostic> =
    others.flatMap { other ->
        service.realUrls
            .filter { url -> other.realUrls.any { it.trim().equals(url.trim(), ignoreCase = true) } }
            .map { url ->
                val message = "The service '${other.name}' lists the same real URL ${url.trim()}."
                Diagnostic(DiagnosticKind.SHARED_REAL_URL, service.id, service.name, message, otherServiceId = other.id)
            }
    }

/** Reports each group of scenarios of [service] that count as the same scenario, see [hasSameNameAndResponseBodyAs]. */
private fun duplicateScenarios(service: Service): List<Diagnostic> {
    val groups = mutableListOf<MutableList<Scenario>>()
    for (scenario in service.scenarios) {
        val group = groups.firstOrNull { it.first().hasSameNameAndResponseBodyAs(scenario) }
        if (group == null) groups += mutableListOf(scenario) else group += scenario
    }
    return groups.filter { it.size > 1 }.map { group ->
        val message = "${group.size} scenarios with the name '${group.first().name.trim()}' have the same response body."
        Diagnostic(DiagnosticKind.DUPLICATE_SCENARIOS, service.id, service.name, message, scenarioIds = group.map { it.id })
    }
}

private fun unknownInspector(
    service: Service,
    installedInspectors: Set<String>,
): Diagnostic? =
    service.inspectorName?.takeIf { it !in installedInspectors }?.let { name ->
        Diagnostic(DiagnosticKind.UNKNOWN_INSPECTOR, service.id, service.name, "The inspector '$name' is not installed.")
    }

private fun unreadableRuleSets(
    service: Service,
    isWellFormedRuleSet: (String) -> Boolean,
): List<Diagnostic> {
    val ofService =
        service.requestRules?.takeUnless(isWellFormedRuleSet)?.let {
            Diagnostic(DiagnosticKind.UNREADABLE_RULE_SET, service.id, service.name, "The request rules of the service cannot be read.")
        }
    val ofScenarios =
        service.scenarios
            .filter { scenario -> (scenario.matchCriterion as? MatchCriterion.Rules)?.let { !isWellFormedRuleSet(it.source) } ?: false }
            .map { scenario ->
                val message = "The rules of the scenario '${scenario.name}' cannot be read."
                Diagnostic(DiagnosticKind.UNREADABLE_RULE_SET, service.id, service.name, message, scenarioIds = listOf(scenario.id))
            }
    return listOfNotNull(ofService) + ofScenarios
}
