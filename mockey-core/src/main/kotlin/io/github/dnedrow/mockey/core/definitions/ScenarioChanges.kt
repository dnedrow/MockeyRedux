package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId

/**
 * The changes that create, update, and delete a scenario of a service, and the change that designates the
 * universal error scenario. The default scenario and the error scenario of a service are designated with
 * [ServiceChanges.update].
 *
 * A scenario is stored in a normal form. Its name has no surrounding whitespace, and a scenario without a name
 * gets a generated one that contains the name of its service. A blank method restriction is absent. Header
 * names and values are trimmed, and a header without a name is left out. The response body stays exactly as
 * it was given.
 *
 * A scenario is rejected when its name is longer than [MAX_NAME_LENGTH] characters.
 *
 * Every change keeps the designations of the service valid, see [withValidDesignations]. No change alters a
 * designation otherwise: only deleting the designated scenario ends a designation.
 */
internal object ScenarioChanges {
    const val MAX_NAME_LENGTH = 250

    /**
     * Creates a scenario for the service [serviceId]. [define] gets a new scenario that has an identifier and
     * nothing else, and returns it with its attributes. The first scenario of a service is its default scenario.
     */
    fun create(
        serviceId: ServiceId,
        define: (Scenario) -> Scenario,
    ): Change<Scenario> =
        { definitions ->
            val service = definitions.service(serviceId)
            if (service == null) {
                serviceNotFound(serviceId)
            } else {
                val blank = Scenario(id = ScenarioId.generate(), name = "")
                val scenario = normalized(attributesChanged(blank, define), service)
                saved(definitions, service, scenario) { scenarios -> scenarios + scenario }
            }
        }

    /** Updates the scenario [scenarioId] of the service [serviceId]. [change] gets the scenario as it is. */
    fun update(
        serviceId: ServiceId,
        scenarioId: ScenarioId,
        change: (Scenario) -> Scenario,
    ): Change<Scenario> =
        { definitions ->
            val service = definitions.service(serviceId)
            val current = service?.scenarios?.firstOrNull { it.id == scenarioId }
            if (service == null) {
                serviceNotFound(serviceId)
            } else if (current == null) {
                scenarioNotFound(service, scenarioId)
            } else {
                val scenario = normalized(attributesChanged(current, change), service)
                saved(definitions, service, scenario) { scenarios -> scenarios.map { if (it.id == scenarioId) scenario else it } }
            }
        }

    /**
     * Deletes the scenario [scenarioId] of the service [serviceId] and yields the deleted scenario. When it was
     * the default scenario, the scenario that was created first takes its place. When it was the error scenario
     * of the service or the universal error scenario, that designation ends.
     */
    fun delete(
        serviceId: ServiceId,
        scenarioId: ScenarioId,
    ): Change<Scenario> =
        { definitions ->
            val service = definitions.service(serviceId)
            val scenario = service?.scenarios?.firstOrNull { it.id == scenarioId }
            if (service == null) {
                serviceNotFound(serviceId)
            } else if (scenario == null) {
                scenarioNotFound(service, scenarioId)
            } else {
                val remaining = service.copy(scenarios = service.scenarios.filter { it.id != scenarioId })
                val settings = definitions.settings
                val deleted = ScenarioReference(serviceId, scenarioId)
                ChangeResult.Changed(
                    definitions.withService(remaining.withValidDesignations()).copy(
                        settings = settings.copy(universalErrorScenario = settings.universalErrorScenario?.takeUnless { it == deleted }),
                    ),
                    scenario,
                )
            }
        }

    /** Designates [scenario] as the universal error scenario, or ends the designation when it is `null`. */
    fun designateUniversalError(scenario: ScenarioReference?): Change<Unit> =
        { definitions ->
            val service = scenario?.let { definitions.service(it.serviceId) }
            if (scenario != null && service == null) {
                serviceNotFound(scenario.serviceId)
            } else if (scenario != null && service != null && service.scenarios.none { it.id == scenario.scenarioId }) {
                scenarioNotFound(service, scenario.scenarioId)
            } else {
                ChangeResult.Changed(definitions.copy(settings = definitions.settings.copy(universalErrorScenario = scenario)), Unit)
            }
        }

    /** Returns the name for a scenario of [service] that was given none. */
    fun generatedName(service: Service): String = "Scenario for ${service.name} (name auto-generated)"

    private fun scenarioNotFound(
        service: Service,
        id: ScenarioId,
    ) = ChangeResult.NotFound("The service '${service.name}' has no scenario with the identifier ${id.value}.")

    private fun attributesChanged(
        before: Scenario,
        change: (Scenario) -> Scenario,
    ): Scenario {
        val after = change(before)
        require(after.id == before.id) { "The identifier of a scenario cannot be changed." }
        return after
    }

    private fun normalized(
        scenario: Scenario,
        service: Service,
    ): Scenario =
        scenario.copy(
            name = scenario.name.trim().ifEmpty { generatedName(service) },
            headers = scenario.headers.map { Header(it.name.trim(), it.value.trim()) }.filter { it.name.isNotEmpty() },
            methodRestriction = scenario.methodRestriction?.trim()?.takeIf { it.isNotEmpty() },
        )

    /** Puts [scenario] among the scenarios of [service] with [replace] when it is valid, and rejects it otherwise. */
    private fun saved(
        definitions: Definitions,
        service: Service,
        scenario: Scenario,
        replace: (List<Scenario>) -> List<Scenario>,
    ): ChangeResult<Scenario> =
        if (scenario.name.length > MAX_NAME_LENGTH) {
            val error = FieldError("name", "The name of a scenario must not be longer than $MAX_NAME_LENGTH characters.")
            ChangeResult.Rejected(ValidationFailure(listOf(error)))
        } else {
            val changed = service.copy(scenarios = replace(service.scenarios)).withValidDesignations()
            ChangeResult.Changed(definitions.withService(changed), scenario)
        }
}

/**
 * Returns the service with designations that its scenarios allow. A service with scenarios has a default
 * scenario that is one of them: when the designated one is missing, the scenario that was created first takes
 * its place. A service without scenarios has none. An error scenario that is not one of the scenarios is no
 * longer designated.
 */
internal fun Service.withValidDesignations(): Service {
    val ids = scenarios.map { it.id }
    return copy(
        defaultScenarioId = defaultScenarioId?.takeIf { it in ids } ?: ids.firstOrNull(),
        errorScenarioId = errorScenarioId?.takeIf { it in ids },
    )
}

/**
 * Tells whether this scenario and [other] count as the same scenario when services are merged or definitions
 * are imported: their names and their response bodies are equal, without regard to letter case and
 * surrounding whitespace.
 */
internal fun Scenario.hasSameNameAndResponseBodyAs(other: Scenario): Boolean =
    name.trim().equals(other.name.trim(), ignoreCase = true) &&
        responseBody.trim().equals(other.responseBody.trim(), ignoreCase = true)
