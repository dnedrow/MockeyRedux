package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The endpoint `/plan/setup` of the legacy shim. A request says in its parameter `action` what to do with a
 * plan of [engine]:
 *
 * - `save_plan` saves the settings that the services of `service_ids[]` have now as the items of a plan. The
 *   plan is the one that the request names, or a new one. It gets the name of `service_plan_name` and the
 *   tags of `service_plan_tag`, where the request has them.
 * - `set_plan` applies the plan.
 * - `delete_plan` deletes the plan.
 * - `set_as_default_plan` designates the plan as the default plan. With `none` for `plan_id`, it ends the
 *   designation.
 *
 * A request names a plan by `plan_id` or by `service_plan_name`. The identifier counts first, and when it
 * names no plan, the name counts, as Mockey 2.x compared names. The endpoint takes the parameters
 * `transient_state` and `type` of Mockey 2.x and doesn't use them.
 *
 * The answer is the result envelope of Mockey 2.x, see [ShimResult], with the texts of Mockey 2.x. After an
 * action on a plan, the result states the name of the plan as `planName`, and its identifier as `planid` and
 * as `planId`: Mockey 2.x used the one spelling for some actions and the other for the rest.
 */
internal class ShimPlans(
    private val engine: MockeyEngine,
) : ShimEndpoint {
    override suspend fun answer(parameters: ShimParameters): JsonObject =
        when (parameters[ACTION]) {
            SAVE -> saved(parameters)
            SET -> applied(parameters)
            DELETE -> deleted(parameters)
            SET_AS_DEFAULT -> designated(parameters)
            else -> ShimResult.fail(NO_ACTION)
        }

    /**
     * Saves the settings of the services that [parameters] name as the plan that they name, or as a new plan.
     * The core says which service isn't there, and then it saves nothing.
     */
    private suspend fun saved(parameters: ShimParameters): JsonObject {
        val services = parameters.all(SERVICE_IDS)
        // An identifier is never blank, so a blank value needs no look at the services.
        if (services.any { it.isBlank() }) return ShimResult.fail("$NOT_SAVED $NO_SERVICE")
        val tags = parameters[PLAN_TAG]
        val into = engine.definitions.planNamedBy(parameters)
        val result =
            engine.plans.save(services.map(::ServiceId), parameters[PLAN_NAME], into?.id) { plan ->
                if (tags == null) plan else plan.copy(tags = Tags.of(tags))
            }
        return when (result) {
            is ChangeResult.Changed -> stated("Service plan ${result.value.name} saved", result.value)
            else -> ShimResult.fail("$NOT_SAVED ${reasonOf(result)}")
        }
    }

    /** Applies the plan that [parameters] name. */
    private suspend fun applied(parameters: ShimParameters): JsonObject {
        val result = engine.definitions.planNamedBy(parameters)?.let { engine.plans.apply(it.id) }
        if (result !is ChangeResult.Changed) return ShimResult.fail("$NOT_SET $NO_PLAN")
        val plan = result.value.plan
        return stated("Service plan ${plan.name} set", plan)
    }

    /** Deletes the plan that [parameters] name. */
    private suspend fun deleted(parameters: ShimParameters): JsonObject {
        val result = engine.definitions.planNamedBy(parameters)?.let { engine.plans.delete(it.id) }
        if (result !is ChangeResult.Changed) {
            // The text of Mockey 2.x sends its reader to the log, so the log has to say something.
            log.info("A request for {} of the legacy shim deleted no plan, because it names no plan that is there.", PATH)
            return ShimResult.fail(NOT_DELETED)
        }
        return stated("Service plan '${result.value.name}' deleted", result.value)
    }

    /** Designates the plan that [parameters] name as the default plan, or ends the designation when they name the plan `none`. */
    private suspend fun designated(parameters: ShimParameters): JsonObject {
        if (parameters[PLAN_ID].equals(NO_PLAN_ID, ignoreCase = true)) {
            engine.plans.designateDefault(null)
            return ShimResult.success("Removed default plan.")
        }
        val result = engine.definitions.planNamedBy(parameters)?.let { engine.plans.designateDefault(it.id) }
        val plan = (result as? ChangeResult.Changed)?.value ?: return ShimResult.fail(NO_DEFAULT)
        return stated("Set as Default Service Plan", plan)
    }

    /** Returns the answer to an action on [plan] that succeeded, which says so in [text]. */
    private fun stated(
        text: String,
        plan: Plan,
    ): JsonObject =
        ShimResult.success(text) {
            put("planid", plan.id.value)
            put("planId", plan.id.value)
            put("planName", plan.name)
        }

    /**
     * Returns the plan that [parameters] name, or `null` when they name none that is there. The parameter
     * `plan_id` names a plan by its identifier. When it names none, the parameter `service_plan_name` names
     * one by its name, see [sameName]. Of several plans with the name, it's the one that was created first.
     */
    private fun Definitions.planNamedBy(parameters: ShimParameters): Plan? {
        val byIdentifier = parameters[PLAN_ID]?.let { id -> plans.firstOrNull { it.id.value == id } }
        return byIdentifier ?: parameters[PLAN_NAME]?.let { name -> plans.firstOrNull { sameName(it.name, name) } }
    }

    internal companion object {
        /** The path of the endpoint. */
        const val PATH = "/plan/setup"

        private val log = LoggerFactory.getLogger(ShimPlans::class.java)

        private const val ACTION = "action"
        private const val SAVE = "save_plan"
        private const val SET = "set_plan"
        private const val DELETE = "delete_plan"
        private const val SET_AS_DEFAULT = "set_as_default_plan"

        private const val PLAN_ID = "plan_id"
        private const val PLAN_NAME = "service_plan_name"
        private const val PLAN_TAG = "service_plan_tag"
        private const val SERVICE_IDS = "service_ids[]"

        /** What a request gives as `plan_id` to end the designation of the default plan. */
        private const val NO_PLAN_ID = "none"

        private const val NO_ACTION =
            "The request names no action. The actions are $SAVE, $SET, $DELETE, and $SET_AS_DEFAULT."
        private const val NO_PLAN = "No plan has the $PLAN_ID or the $PLAN_NAME of the request."
        private const val NO_SERVICE = "A value of $SERVICE_IDS is empty."
        private const val NOT_SAVED = "Service plan not saved."
        private const val NOT_SET = "Service plan not set."
        private const val NOT_DELETED = "Service plan not deleted. Please check your logs for insight."
        private const val NO_DEFAULT = "Unable to set Default Service Plan. Unknown Service Plan ID."
    }
}
