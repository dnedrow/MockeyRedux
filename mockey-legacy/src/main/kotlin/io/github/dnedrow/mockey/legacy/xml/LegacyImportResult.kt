package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.definitions.DiagnosticKind
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Tags

/**
 * What an import read from a legacy source: the [definitions] of the source as definitions of this system,
 * and the [issues] of the source, which say what of it could not be taken as it is.
 *
 * The definitions have identifiers of their own, and what the source refers to by a number is referred to
 * by them: the default scenario and the error scenario of a service, the universal error scenario, the twist
 * configuration that is enabled, and the default plan. A reference that names nothing in the source is not
 * in the definitions. It is among the issues.
 *
 * Nothing is imported into an engine until [importInto] is called.
 */
public data class LegacyImportResult(
    public val definitions: Definitions,
    public val issues: List<LegacyXmlIssue>,
) {
    /**
     * Imports the [definitions] into [engine] and returns the report. The engine imports them by the rules
     * by which it imports a definitions document of its own, see [MockeyEngine.importDefinitions]: it adds
     * what is new, merges a service into the service that has its name, and takes a setting where it has
     * none. [tags] are added to every service, scenario, and plan that is added, and to every service that
     * is merged into.
     *
     * A rule set that is not well formed is imported with its text as it is. The report names each such
     * rule set that the import added: what the diagnostics of the engine say after the import about the
     * services and the scenarios that the engine did not hold before it.
     *
     * @throws io.github.dnedrow.mockey.core.storage.StorageException when the engine is in writable mode
     *   and the definitions cannot be saved. Nothing is imported then.
     * @throws IllegalStateException when the engine is closed
     */
    public suspend fun importInto(
        engine: MockeyEngine,
        tags: Tags = Tags.EMPTY,
    ): LegacyImportReport {
        val before = engine.definitions
        val imported = engine.importDefinitions(definitions, tags)
        val services = before.services.mapTo(HashSet()) { it.id }
        val scenarios = before.services.flatMapTo(HashSet()) { service -> service.scenarios.map { it.id } }
        val unreadableRuleSets =
            engine.diagnostics().filter { diagnostic ->
                when {
                    diagnostic.kind != DiagnosticKind.UNREADABLE_RULE_SET -> false
                    diagnostic.scenarioIds.isEmpty() -> diagnostic.serviceId !in services
                    else -> diagnostic.scenarioIds.any { it !in scenarios }
                }
            }
        return LegacyImportReport(imported, issues, unreadableRuleSets)
    }
}
