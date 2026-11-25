package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.RealUrlDerivationRequestDto
import io.github.dnedrow.mockey.api.ServiceAttributesDto
import io.github.dnedrow.mockey.api.ServiceMergeRequestDto
import io.github.dnedrow.mockey.core.definitions.DerivedRealUrl
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The operations of the admin API on the services of [engine]. A service is stated with its scenarios, in
 * the order in which they were created. The operations on a single scenario are those of [AdminScenarios].
 *
 * A request that creates or updates a service states attributes of the service, see
 * [ServiceAttributesDto]. A creation starts from a service without a name and takes the attributes that
 * the request states. An update changes the attributes that the request states and leaves every other one
 * as it is. The engine says whether the service is valid then, and a service that is not gets the status
 * 422 with every invalid attribute.
 */
internal class AdminServices(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, "services", query = setOf(TAG, NAME)) {
                AdminResponse.json(listed(Tags.of(queryValues(TAG)), queryValue(NAME)).map { it.toAdminDto(engine.lastUsedTimes) })
            },
            AdminRoute(HttpMethod.Post, "services") { create() },
            AdminRoute(HttpMethod.Get, "services/{serviceId}") { read() },
            AdminRoute(HttpMethod.Patch, "services/{serviceId}") { update() },
            AdminRoute(HttpMethod.Delete, "services/{serviceId}") {
                engine.services.delete(service(parameter("serviceId")).id).orProblem()
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Post, "services/{serviceId}/duplicate") {
                val copy = engine.services.duplicate(service(parameter("serviceId")).id).orProblem()
                AdminResponse.created(copy.toAdminDto(engine.lastUsedTimes), "services", copy.id.value)
            },
            AdminRoute(HttpMethod.Post, "services/{serviceId}/merge") { merge() },
            AdminRoute(HttpMethod.Get, "services/{serviceId}/schema-report") { schemaReport() },
            AdminRoute(HttpMethod.Post, "services/real-urls/derive") {
                val request = body(RealUrlDerivationRequestDto.serializer())
                val derived = engine.services.deriveRealUrls(request.expression, request.replacements).orProblem()
                AdminResponse.json(derived.map(DerivedRealUrl::toAdminDto))
            },
        )

    /**
     * Returns the services that carry one of [tags], and all services where there are no tags, and of them
     * the ones with the name [name], without regard to letter case, where there is a name.
     */
    private fun listed(
        tags: Tags,
        name: String?,
    ): List<Service> =
        engine.definitions.services.filter { service ->
            val tagged = tags.isEmpty() || service.tags.any { it in tags }
            tagged && (name == null || service.name.trim().equals(name.trim(), ignoreCase = true))
        }

    /** Answers with the service of the request. */
    private fun AdminRequest.read(): AdminResponse = AdminResponse.json(service(parameter("serviceId")).toAdminDto(engine.lastUsedTimes))

    /** Creates a service with the attributes that the request states, and answers with the service and where it is. */
    private suspend fun AdminRequest.create(): AdminResponse {
        val stated = statedAttributes()
        val service = engine.services.create { blank -> blank.with(stated.on(blank.attributes())) }.orProblem()
        return AdminResponse.created(service.toAdminDto(engine.lastUsedTimes), "services", service.id.value)
    }

    /** Gives the service of the request the attributes that the request states, and answers with the service. */
    private suspend fun AdminRequest.update(): AdminResponse {
        val id = service(parameter("serviceId")).id
        val stated = statedAttributes()
        val service = engine.services.update(id) { current -> current.with(stated.on(current.attributes())) }.orProblem()
        return AdminResponse.json(service.toAdminDto(engine.lastUsedTimes))
    }

    /**
     * Returns the attributes of a service that the request states.
     *
     * @throws AdminProblem with the status 422 when the request designates a scenario with an identifier
     *   that is blank, which no scenario has. Besides, it is thrown as [attributes] says.
     */
    private suspend fun AdminRequest.statedAttributes(): StatedAttributes<ServiceAttributesDto> {
        val stated = attributes(ServiceAttributesDto.serializer())
        val asStated = stated.on(ServiceAttributesDto())
        val designated = mapOf("defaultScenarioId" to asStated.defaultScenarioId, "errorScenarioId" to asStated.errorScenarioId)
        val blank = designated.filterValues { it != null && it.isBlank() }.keys.map { FieldErrorDto(it, BLANK_IDENTIFIER) }
        if (blank.isNotEmpty()) throw AdminProblem.invalid(blank)
        return stated
    }

    /**
     * Merges the services that the request names as sources into the service of the request, and answers
     * with what the merge did. A source that is no service is an invalid member of the request, and not a
     * resource that the request addresses.
     */
    private suspend fun AdminRequest.merge(): AdminResponse {
        val destination = service(parameter("serviceId"))
        val request = body(ServiceMergeRequestDto.serializer())
        val services = engine.definitions.services
        val sources = request.sourceIds.map { id -> services.firstOrNull { it.id.value == id } }
        val unknown = sources.indices.filter { sources[it] == null }.map { FieldErrorDto("sourceIds[$it]", NO_SUCH_SERVICE) }
        if (unknown.isNotEmpty()) throw AdminProblem.invalid(unknown)
        val report = engine.services.merge(destination.id, sources.filterNotNull().map { it.id }).orProblem()
        return AdminResponse.json(report.toAdminDto())
    }

    /**
     * Answers with what the response schema of the service of the request says of the response bodies of
     * its scenarios. The report is there while the service has a response schema, whether or not the
     * schema is enabled.
     *
     * @throws AdminProblem with the status 404 when the service has no response schema.
     */
    private fun AdminRequest.schemaReport(): AdminResponse {
        val service = service(parameter("serviceId"))
        val report =
            engine.services.schemaReport(service.id)
                ?: throw AdminProblem(HttpStatusCode.NotFound, "The service '${service.name}' has no response schema.")
        return AdminResponse.json(report.toAdminDto())
    }

    /**
     * Returns the service with the identifier [id].
     *
     * @throws AdminProblem with the status 404 when no service has that identifier.
     */
    private fun service(id: String): Service = engine.service(id)

    private companion object {
        const val TAG = "tag"
        const val NAME = "name"
        const val BLANK_IDENTIFIER = "An identifier is not blank."
        const val NO_SUCH_SERVICE = "No service has this identifier."
    }
}

/**
 * Returns the service of this engine with the identifier [id].
 *
 * @throws AdminProblem with the status 404 when no service has that identifier.
 */
internal fun MockeyEngine.service(id: String): Service =
    definitions.services.firstOrNull { it.id.value == id }
        ?: throw AdminProblem(HttpStatusCode.NotFound, "No service has the identifier $id.")
