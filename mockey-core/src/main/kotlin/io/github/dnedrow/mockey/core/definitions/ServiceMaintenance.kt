package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags

/** What a merge of services into a destination did. [sourcesDeleted] is off when a scenario was skipped. */
public data class MergeReport(
    public val copied: List<MergedScenario>,
    public val skipped: List<MergedScenario>,
    public val sourcesDeleted: Boolean,
)

/** A scenario of a source service that a merge copied or skipped, by the names of the service and the scenario. */
public data class MergedScenario(
    public val serviceName: String,
    public val scenarioName: String,
)

/** A real URL that a bulk derivation added to a service. */
public data class DerivedRealUrl(
    public val serviceId: ServiceId,
    public val serviceName: String,
    public val realUrl: String,
)

/** The changes that duplicate a service, merge services, and derive real URLs for all services. */
internal object ServiceMaintenance {
    /**
     * Creates a copy of the service [id] and yields it. The copy has a name and a mock URL pattern that no
     * service has, copies of the scenarios under new identifiers with the same designations, and no real URLs.
     * Everything else is as in the original.
     */
    fun duplicate(id: ServiceId): Change<Service> =
        { definitions ->
            val original = definitions.service(id)
            if (original == null) {
                serviceNotFound(id)
            } else {
                val scenarios = original.scenarios.map { it.copy(id = ScenarioId.generate()) }
                val copies =
                    original.scenarios
                        .map { it.id }
                        .zip(scenarios.map { it.id })
                        .toMap()
                val copy =
                    original
                        .copy(
                            id = ServiceId.generate(),
                            name = copyName(original.name, definitions.services.map { it.name }),
                            mockUrl = copyMockUrl(original.mockUrl, definitions.services.map { it.mockUrl }),
                            realUrls = emptyList(),
                            defaultRealUrlIndex = 0,
                            scenarios = scenarios,
                            defaultScenarioId = original.defaultScenarioId?.let { copies[it] },
                            errorScenarioId = original.errorScenarioId?.let { copies[it] },
                        ).withValidDesignations()
                ChangeResult.Changed(definitions.copy(services = definitions.services + copy), copy)
            }
        }

    /**
     * Merges the services [sourceIds] into the service [destinationId] and yields a report. Every scenario of
     * a source is copied to the destination under a new identifier, unless the destination has a scenario
     * with the same name and response body. The tags of the sources are added to the destination.
     *
     * When no scenario was skipped, the real URLs of the sources move to the destination and the sources are
     * deleted. Otherwise the sources stay as they are.
     */
    fun merge(
        destinationId: ServiceId,
        sourceIds: List<ServiceId>,
    ): Change<MergeReport> =
        { definitions ->
            val destination = definitions.service(destinationId)
            val ids = sourceIds.distinct() - destinationId
            val missing = ids.firstOrNull { definitions.service(it) == null }
            if (destination == null) {
                serviceNotFound(destinationId)
            } else if (missing != null) {
                serviceNotFound(missing)
            } else {
                merged(definitions, destination, sources = ids.mapNotNull { definitions.service(it) })
            }
        }

    /**
     * Adds real URLs to every service and yields the added ones. Each real URL of a service gives one URL for
     * each of the [replacements]: the URL in which the matches of the regular expression [expression] are
     * replaced. A result that some service already lists is not added.
     */
    fun deriveRealUrls(
        expression: String,
        replacements: List<String>,
    ): Change<List<DerivedRealUrl>> =
        { definitions ->
            val regex = expression.takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it) }.getOrNull() }
            val used = replacements.filter { it.isNotBlank() }
            if (regex == null) {
                rejected("expression", "The expression must be a regular expression that is not empty.")
            } else if (used.isEmpty()) {
                rejected("replacements", "At least one replacement that is not blank is needed.")
            } else {
                try {
                    derived(definitions, regex, used)
                } catch (e: IllegalArgumentException) {
                    rejected("replacements", "A replacement cannot be applied: ${e.message}")
                } catch (e: IndexOutOfBoundsException) {
                    rejected("replacements", "A replacement refers to a group that the expression does not have: ${e.message}")
                }
            }
        }

    private fun rejected(
        field: String,
        message: String,
    ) = ChangeResult.Rejected(ValidationFailure(listOf(FieldError(field, message))))

    private fun copyName(
        name: String,
        taken: List<String>,
    ): String =
        firstFree(taken) { number ->
            val suffix = if (number == 1) " (copy)" else " (copy $number)"
            name.trim().take(ServiceChanges.MAX_NAME_LENGTH - suffix.length) + suffix
        }

    private fun copyMockUrl(
        mockUrl: String,
        taken: List<String>,
    ): String = firstFree(taken) { number -> mockUrl.trim() + if (number == 1) "-copy" else "-copy-$number" }

    /** Returns the first of the candidates for the numbers 1, 2, and so on that is not among [taken]. */
    private fun firstFree(
        taken: List<String>,
        candidate: (Int) -> String,
    ): String =
        generateSequence(1) { it + 1 }
            .map(candidate)
            .first { text -> taken.none { it.trim().equals(text, ignoreCase = true) } }

    private fun merged(
        definitions: Definitions,
        destination: Service,
        sources: List<Service>,
    ): ChangeResult<MergeReport> {
        val copied = mutableListOf<MergedScenario>()
        val skipped = mutableListOf<MergedScenario>()
        var scenarios = destination.scenarios
        for (source in sources) {
            for (scenario in source.scenarios) {
                if (scenarios.any { it.hasSameNameAndResponseBodyAs(scenario) }) {
                    skipped += MergedScenario(source.name, scenario.name)
                } else {
                    scenarios = scenarios + scenario.copy(id = ScenarioId.generate())
                    copied += MergedScenario(source.name, scenario.name)
                }
            }
        }
        val sourcesDeleted = skipped.isEmpty() && sources.isNotEmpty()
        val realUrls = if (sourcesDeleted) destination.realUrls + sources.flatMap { it.realUrls } else destination.realUrls
        val changed =
            destination.copy(
                scenarios = scenarios,
                tags = Tags.of(destination.tags + sources.flatMap { it.tags }),
                realUrls = realUrls.distinctBy { it.trim().lowercase() },
            )
        val withDestination = definitions.withService(changed.withValidDesignations())
        val report = MergeReport(copied, skipped, sourcesDeleted)
        return ChangeResult.Changed(if (sourcesDeleted) withDestination.without(sources) else withDestination, report)
    }

    private fun Definitions.without(deleted: List<Service>): Definitions {
        val ids = deleted.map { it.id }
        return copy(
            services = services.filter { it.id !in ids },
            settings = settings.copy(universalErrorScenario = settings.universalErrorScenario?.takeUnless { it.serviceId in ids }),
        )
    }

    private fun derived(
        definitions: Definitions,
        regex: Regex,
        replacements: List<String>,
    ): ChangeResult<List<DerivedRealUrl>> {
        val listed = definitions.services.flatMap { it.realUrls }.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val added = mutableListOf<DerivedRealUrl>()
        val services =
            definitions.services.map { service ->
                val urls =
                    service.realUrls
                        .flatMap { url -> replacements.map { url.replace(regex, it).trim() } }
                        .filter { it.isNotEmpty() && listed.add(it.lowercase()) }
                added += urls.map { DerivedRealUrl(service.id, service.name, it) }
                if (urls.isEmpty()) service else service.copy(realUrls = service.realUrls + urls)
            }
        return ChangeResult.Changed(definitions.copy(services = services), added)
    }
}
