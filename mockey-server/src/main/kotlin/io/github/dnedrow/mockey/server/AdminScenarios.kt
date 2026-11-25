package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ScenarioAttributesDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The operations of the admin API on the scenarios of the services of [engine], and on the universal error
 * scenario, which is one of them.
 *
 * A scenario is a resource below its service. A request that creates or updates a scenario states
 * attributes of the scenario, see [ScenarioAttributesDto], as a request for a service does, see
 * [AdminServices]. The default scenario and the error scenario of a service are attributes of that service.
 *
 * The universal error scenario is a resource of its own, which is there while a scenario is designated.
 */
internal class AdminScenarios(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, SCENARIOS) { AdminResponse.json(service().scenarios.map(Scenario::toAdminDto)) },
            AdminRoute(HttpMethod.Post, SCENARIOS) { create() },
            AdminRoute(HttpMethod.Get, SCENARIO) { AdminResponse.json(scenario(service()).toAdminDto()) },
            AdminRoute(HttpMethod.Patch, SCENARIO) { update() },
            AdminRoute(HttpMethod.Delete, SCENARIO) {
                val service = service()
                engine.scenarios.delete(service.id, scenario(service).id).orProblem()
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Get, UNIVERSAL_ERROR_SCENARIO) {
                val designated = engine.definitions.settings.universalErrorScenario
                AdminResponse.json(designated?.toAdminDto() ?: throw AdminProblem(HttpStatusCode.NotFound, NOT_DESIGNATED))
            },
            AdminRoute(HttpMethod.Put, UNIVERSAL_ERROR_SCENARIO) { designate() },
            AdminRoute(HttpMethod.Delete, UNIVERSAL_ERROR_SCENARIO) {
                engine.scenarios.designateUniversalError(null).orProblem()
                AdminResponse.noContent()
            },
        )

    /** Creates a scenario of the service of the request with the attributes that the request states, and answers with it. */
    private suspend fun AdminRequest.create(): AdminResponse {
        val service = service()
        val stated = statedScenarioAttributes()
        val scenario = engine.scenarios.create(service.id) { blank -> blank.with(stated.on(blank.attributes())) }.orProblem()
        return AdminResponse.created(scenario.toAdminDto(), "services", service.id.value, "scenarios", scenario.id.value)
    }

    /** Gives the scenario of the request the attributes that the request states, and answers with the scenario. */
    private suspend fun AdminRequest.update(): AdminResponse {
        val service = service()
        val id = scenario(service).id
        val stated = statedScenarioAttributes()
        val scenario = engine.scenarios.update(service.id, id) { current -> current.with(stated.on(current.attributes())) }.orProblem()
        return AdminResponse.json(scenario.toAdminDto())
    }

    /**
     * Designates the scenario that the request names as the universal error scenario, and answers with it.
     * A service or a scenario that is none is an invalid member of the request, and not a resource that the
     * request addresses.
     */
    private suspend fun AdminRequest.designate(): AdminResponse {
        val named = body(ScenarioReferenceDto.serializer())
        val service = engine.definitions.services.firstOrNull { it.id.value == named.serviceId }
        val scenario = service?.scenarios?.firstOrNull { it.id.value == named.scenarioId }
        val error =
            when {
                service == null -> FieldErrorDto("serviceId", "No service has this identifier.")
                scenario == null -> FieldErrorDto("scenarioId", "The service '${service.name}' has no scenario with this identifier.")
                else -> null
            }
        if (service == null || scenario == null) throw AdminProblem.invalid(listOfNotNull(error))
        val designated = ScenarioReference(service.id, scenario.id)
        engine.scenarios.designateUniversalError(designated).orProblem()
        return AdminResponse.json(designated.toAdminDto())
    }

    /**
     * Returns the service that the path of the request names.
     *
     * @throws AdminProblem with the status 404 when there is no such service.
     */
    private fun AdminRequest.service(): Service = engine.service(parameter("serviceId"))

    /**
     * Returns the scenario of [service] that the path of the request names.
     *
     * @throws AdminProblem with the status 404 when the service has no such scenario.
     */
    private fun AdminRequest.scenario(service: Service): Scenario {
        val id = parameter("scenarioId")
        return service.scenarios.firstOrNull { it.id.value == id }
            ?: throw AdminProblem(HttpStatusCode.NotFound, "The service '${service.name}' has no scenario with the identifier $id.")
    }

    private companion object {
        const val SCENARIOS = "services/{serviceId}/scenarios"
        const val SCENARIO = "services/{serviceId}/scenarios/{scenarioId}"
        const val UNIVERSAL_ERROR_SCENARIO = "universal-error-scenario"
        const val NOT_DESIGNATED = "No scenario is designated as the universal error scenario."
    }
}

/**
 * Returns the attributes of a scenario that the request states.
 *
 * @throws AdminProblem with the status 422 when the request states a text criterion whose text is blank,
 *   which no request contains. Besides, it is thrown as [attributes] says.
 */
internal suspend fun AdminRequest.statedScenarioAttributes(): StatedAttributes<ScenarioAttributesDto> {
    val stated = attributes(ScenarioAttributesDto.serializer())
    val criterion = stated.on(ScenarioAttributesDto()).matchCriterion
    if (criterion is MatchCriterionDto.Text && criterion.text.isBlank()) {
        throw AdminProblem.invalid(listOf(FieldErrorDto("matchCriterion.text", "The text of a text criterion is not blank.")))
    }
    return stated
}
