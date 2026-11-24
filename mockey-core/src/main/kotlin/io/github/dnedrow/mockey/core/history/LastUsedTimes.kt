package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ServiceId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * When the services, the scenarios, and the plans of one engine were last used: a service when it last handled
 * a request, a scenario when it last gave the response to a request, and a plan when it was last applied.
 *
 * The times are held in memory and nowhere else, so they end with their engine. Any number of threads can use
 * them at the same time.
 */
public class LastUsedTimes internal constructor() {
    private val ofServices = ConcurrentHashMap<ServiceId, Instant>()
    private val ofScenarios = ConcurrentHashMap<ScenarioId, Instant>()
    private val ofPlans = ConcurrentHashMap<PlanId, Instant>()

    /** When each service last handled a request. A service that has handled none since the times were cleared is left out. */
    public val services: Map<ServiceId, Instant>
        get() = ofServices.toMap()

    /** When each scenario last gave the response to a request. A scenario that has given none is left out. */
    public val scenarios: Map<ScenarioId, Instant>
        get() = ofScenarios.toMap()

    /** When each plan was last applied. A plan that has not been applied is left out. */
    public val plans: Map<PlanId, Instant>
        get() = ofPlans.toMap()

    /** Returns when the service [id] last handled a request, or `null` when it has handled none. */
    public fun ofService(id: ServiceId): Instant? = ofServices[id]

    /** Returns when the scenario [id] last gave the response to a request, or `null` when it has given none. */
    public fun ofScenario(id: ScenarioId): Instant? = ofScenarios[id]

    /** Returns when the plan [id] was last applied, or `null` when it has not been applied. */
    public fun ofPlan(id: PlanId): Instant? = ofPlans[id]

    /** Clears every time: no service, no scenario, and no plan has a last-used time until it is used again. */
    public fun clear() {
        ofServices.clear()
        ofScenarios.clear()
        ofPlans.clear()
    }

    /** Notes that the service [id] handled a request that arrived at [time]. */
    internal fun serviceUsed(
        id: ServiceId,
        time: Instant,
    ) {
        ofServices.merge(id, time, ::maxOf)
    }

    /** Notes that the scenario [id] gave the response to a request that arrived at [time]. */
    internal fun scenarioUsed(
        id: ScenarioId,
        time: Instant,
    ) {
        ofScenarios.merge(id, time, ::maxOf)
    }

    /** Notes that the plan [id] was applied at [time]. */
    internal fun planApplied(
        id: PlanId,
        time: Instant,
    ) {
        ofPlans.merge(id, time, ::maxOf)
    }

    /**
     * Notes what [handling] of a request that arrived at [time] used: the service that handled the request,
     * and the scenario that gave the response. A scenario is noted by itself: the universal error scenario
     * that answers for another service leaves the time of its own service as it is.
     */
    internal fun used(
        handling: Handling,
        time: Instant,
    ) {
        handling.service?.let { serviceUsed(it.id, time) }
        handling.scenario?.let { scenarioUsed(it.id, time) }
    }
}
