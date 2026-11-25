package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Service
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The operations of the admin API on the services of [engine] and on their scenarios. A service is stated
 * with its scenarios, in the order in which they were created.
 */
internal class AdminServices(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, "services") { AdminResponse.json(engine.definitions.services.map(Service::toAdminDto)) },
            AdminRoute(HttpMethod.Get, "services/{serviceId}") { AdminResponse.json(service(parameter("serviceId")).toAdminDto()) },
        )

    /**
     * Returns the service with the identifier [id].
     *
     * @throws AdminProblem with the status 404 when no service has that identifier.
     */
    private fun service(id: String): Service =
        engine.definitions.services.firstOrNull { it.id.value == id }
            ?: throw AdminProblem(HttpStatusCode.NotFound, "No service has the identifier $id.")
}
