package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.PlanAttributesDto
import io.github.dnedrow.mockey.api.PlanReferenceDto
import io.github.dnedrow.mockey.api.PlanSaveRequestDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.Tags
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The operations of the admin API on the plans of [engine], and on the default plan, which is one of them.
 *
 * A plan holds settings of several services: the response mode, the delay, and the default scenario of
 * each. A save takes them from the services as they are now. An update states the attributes of the plan
 * that change, see [PlanAttributesDto], as an update of a service does, see [AdminServices]. A plan names
 * its services and their scenarios by their names, so it can name ones that are not there. No operation
 * but the one that applies a plan changes a service.
 *
 * The default plan is a resource of its own, which is there while a plan is designated.
 */
internal class AdminPlans(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, PLANS) { AdminResponse.json(engine.definitions.plans.map(Plan::toAdminDto)) },
            AdminRoute(HttpMethod.Post, PLANS) { save(into = null) },
            AdminRoute(HttpMethod.Get, PLAN) { AdminResponse.json(plan().toAdminDto()) },
            AdminRoute(HttpMethod.Patch, PLAN) { update() },
            AdminRoute(HttpMethod.Delete, PLAN) {
                engine.plans.delete(plan().id).orProblem()
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Post, "$PLAN/save") { save(into = plan()) },
            AdminRoute(HttpMethod.Post, "$PLAN/apply") {
                val report = engine.plans.apply(plan().id).orProblem()
                AdminResponse.json(report.toAdminDto())
            },
            AdminRoute(HttpMethod.Get, DEFAULT_PLAN) {
                val designated = defaultPlan() ?: throw AdminProblem(HttpStatusCode.NotFound, NOT_DESIGNATED)
                AdminResponse.json(PlanReferenceDto(designated.id.value))
            },
            AdminRoute(HttpMethod.Put, DEFAULT_PLAN) { designate() },
            AdminRoute(HttpMethod.Delete, DEFAULT_PLAN) {
                engine.plans.designateDefault(null).orProblem()
                AdminResponse.noContent()
            },
        )

    /**
     * Saves the settings that the services of the request have now as the items of a plan, and answers with
     * the plan. The plan is [into] where the request addresses a plan. Otherwise it is the first plan with
     * the name of the request, and a new plan when there is none, or when the request states no name. A new
     * plan is answered as a creation, with where it is.
     *
     * A service that is none is an invalid member of the request, and not a resource that the request
     * addresses.
     */
    private suspend fun AdminRequest.save(into: Plan?): AdminResponse {
        val request = body(PlanSaveRequestDto.serializer())
        val services = engine.definitions.services
        val named = request.serviceIds.map { id -> services.firstOrNull { it.id.value == id } }
        val unknown = named.indices.filter { named[it] == null }.map { FieldErrorDto("serviceIds[$it]", NO_SUCH_SERVICE) }
        if (unknown.isNotEmpty()) throw AdminProblem.invalid(unknown)
        var isNew = false
        val result =
            engine.plans.save(named.filterNotNull().map { it.id }, request.name, into?.id) { plan ->
                // The engine makes one change at a time, and this function is part of one. So the definitions
                // are the ones that the plan is saved into, and they hold the plan unless the save creates it.
                isNew = engine.definitions.plans.none { it.id == plan.id }
                plan.copy(description = request.description ?: plan.description, tags = request.tags?.let(Tags::of) ?: plan.tags)
            }
        val saved = result.orProblem()
        return if (isNew) AdminResponse.created(saved.toAdminDto(), PLANS, saved.id.value) else AdminResponse.json(saved.toAdminDto())
    }

    /** Gives the plan of the request the attributes that the request states, and answers with the plan. */
    private suspend fun AdminRequest.update(): AdminResponse {
        val id = plan().id
        val stated = attributes(PlanAttributesDto.serializer())
        val plan = engine.plans.update(id) { current -> current.with(stated.on(current.attributes())) }.orProblem()
        return AdminResponse.json(plan.toAdminDto())
    }

    /**
     * Designates the plan that the request names as the default plan, and answers with it. A plan that is
     * none is an invalid member of the request, and not a resource that the request addresses.
     */
    private suspend fun AdminRequest.designate(): AdminResponse {
        val named = body(PlanReferenceDto.serializer())
        val plan =
            engine.definitions.plans.firstOrNull { it.id.value == named.planId }
                ?: throw AdminProblem.invalid(listOf(FieldErrorDto("planId", NO_SUCH_PLAN)))
        engine.plans.designateDefault(plan.id).orProblem()
        return AdminResponse.json(PlanReferenceDto(plan.id.value))
    }

    /** Returns the default plan, or `null` while no plan is designated, and while the designated one is not there. */
    private fun defaultPlan(): Plan? {
        val current = engine.definitions
        return current.settings.defaultPlanId?.let { id -> current.plans.firstOrNull { it.id == id } }
    }

    /**
     * Returns the plan that the path of the request names.
     *
     * @throws AdminProblem with the status 404 when there is no such plan.
     */
    private fun AdminRequest.plan(): Plan {
        val id = parameter("planId")
        return engine.definitions.plans.firstOrNull { it.id.value == id }
            ?: throw AdminProblem(HttpStatusCode.NotFound, "No plan has the identifier $id.")
    }

    private companion object {
        const val PLANS = "plans"
        const val PLAN = "plans/{planId}"
        const val DEFAULT_PLAN = "default-plan"
        const val NOT_DESIGNATED = "No plan is designated as the default plan."
        const val NO_SUCH_SERVICE = "No service has this identifier."
        const val NO_SUCH_PLAN = "No plan has this identifier."
    }
}
