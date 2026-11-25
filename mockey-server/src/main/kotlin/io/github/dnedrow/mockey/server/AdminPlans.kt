package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Plan
import io.ktor.http.HttpMethod

/** The operations of the admin API on the plans of [engine]. */
internal class AdminPlans(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, "plans") { AdminResponse.json(engine.definitions.plans.map(Plan::toAdminDto)) },
        )
}
