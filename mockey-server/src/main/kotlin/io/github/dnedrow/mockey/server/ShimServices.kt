package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * The endpoint `/config/service` of the legacy shim. It changes how a service of [engine] answers: its
 * response mode, its default scenario, its delay, its default real URL, its inspector, its request rules,
 * and its response schema.
 *
 * A request names the service, see [serviceNamedBy], and each of its other parameters says one thing about
 * the service. What a request doesn't mention stays as it is. As in Mockey 2.x, a parameter that says
 * nothing usable changes nothing either: a response type that is none, a number that is no number, and a
 * scenario that the service doesn't have. The endpoint takes the parameter `transientState` of Mockey 2.x
 * and doesn't use it.
 *
 * The answer is the result envelope of Mockey 2.x, see [ShimResult]. After a change, it says `updated` and
 * states the service as it is now, so a script can see what came of its request. When the request names no
 * service that is there, or when the service can't be as the request wants it, the answer says so, and
 * nothing is changed.
 */
internal class ShimServices(
    private val engine: MockeyEngine,
) : ShimEndpoint {
    override suspend fun answer(parameters: ShimParameters): JsonObject {
        val named = engine.definitions.serviceNamedBy(parameters) ?: return ShimResult.fail("$UNABLE $NO_SERVICE")
        return when (val result = engine.services.update(named.id) { changed(it, parameters) }) {
            is ChangeResult.Changed -> updated(result.value)
            else -> ShimResult.fail("$UNABLE ${reasonOf(result)}")
        }
    }

    /** Returns [service] as [parameters] want it. */
    private fun changed(
        service: Service,
        parameters: ShimParameters,
    ): Service {
        val inspector = parameters[INSPECTOR_NAME]
        return service.copy(
            responseMode = parameters[RESPONSE_TYPE]?.let(::modeNamed) ?: service.responseMode,
            defaultScenarioId = scenarioNamedBy(service, parameters)?.id ?: service.defaultScenarioId,
            delayMillis = parameters[HANG_TIME]?.toIntOrNull()?.toLong() ?: service.delayMillis,
            // A script counts the real URLs from 1. The core takes the first URL for an index that is out of range.
            defaultRealUrlIndex = parameters[DEFAULT_URL_INDEX]?.toIntOrNull()?.minus(1) ?: service.defaultRealUrlIndex,
            inspectorName = if (inspector == null) service.inspectorName else inspector.takeUnless { it.trim() == NO_INSPECTOR },
            requestRules = parameters[REQUEST_RULES] ?: service.requestRules,
            requestRulesEnabled = parameters[REQUEST_RULES_FLAG]?.toBoolean() ?: service.requestRulesEnabled,
            responseSchema = parameters[SCHEMA] ?: service.responseSchema,
            responseSchemaEnabled = parameters[SCHEMA_FLAG]?.toBoolean() ?: service.responseSchemaEnabled,
        )
    }

    /**
     * Returns the scenario of [service] that [parameters] name as its default scenario, or `null` when they
     * name none that the service has. The parameter `scenarioId` names a scenario by its identifier. When it
     * names none, the parameter `scenarioName` names one by its name, as Mockey 2.x compared names.
     */
    private fun scenarioNamedBy(
        service: Service,
        parameters: ShimParameters,
    ): Scenario? {
        val byIdentifier = parameters[SCENARIO_ID]?.let { id -> service.scenarios.firstOrNull { it.id.value == id } }
        return byIdentifier ?: parameters[SCENARIO_NAME]?.let { name -> service.scenarios.firstOrNull { sameName(it.name, name) } }
    }

    /** Returns the response mode that [text] names, by its name or by its number in Mockey 2.x, or `null` when it names none. */
    private fun modeNamed(text: String): ResponseMode? =
        when (text.trim().lowercase()) {
            "proxy", "0" -> ResponseMode.PROXY
            "static", "1" -> ResponseMode.STATIC
            "dynamic", "2" -> ResponseMode.DYNAMIC
            else -> null
        }

    /** Returns the answer that states [service] after a change. */
    private fun updated(service: Service): JsonObject {
        val default = defaultScenarioOf(service)
        return ShimResult.success("updated") {
            put(SERVICE_NAME, service.name)
            put(SERVICE_ID, service.id.value)
            put(SCENARIO_ID, default?.id?.value)
            put(SCENARIO_NAME, default?.name)
            put(RESPONSE_TYPE, modeNameOf(service))
            put(HANG_TIME, service.delayMillis)
            put(INSPECTOR_NAME, inspectorNameOf(service))
            put(SCHEMA_FLAG, service.responseSchemaEnabled)
            put(REQUEST_RULES_FLAG, service.requestRulesEnabled)
        }
    }

    internal companion object {
        /** The path of the endpoint. */
        const val PATH = "/config/service"

        private const val UNABLE = "Unable to update service configuration."
        private const val NO_SERVICE = "No service has the serviceId or the serviceName of the request."
    }
}

/** The parameter that names a scenario by its identifier, and the member of an answer that states the identifier. */
internal const val SCENARIO_ID = "scenarioId"

/** The parameter that names a scenario by its name, and the member of an answer that states the name. */
internal const val SCENARIO_NAME = "scenarioName"

/** The parameter that names a response mode, and the member of an answer that states the mode of a service. */
internal const val RESPONSE_TYPE = "serviceResponseType"

/** The parameter that gives a delay in milliseconds, and the member of an answer that states the delay of a service. */
internal const val HANG_TIME = "hangTime"

/** The parameter that names an inspector, and the member of an answer that states the inspector of a service. */
internal const val INSPECTOR_NAME = "requestInspectorName"

private const val DEFAULT_URL_INDEX = "defaultUrlIndex"
private const val REQUEST_RULES = "requestInspectorJsonRules"
private const val REQUEST_RULES_FLAG = "requestInspectorJsonRulesEnableFlag"
private const val SCHEMA = "serviceSchema"
private const val SCHEMA_FLAG = "serviceSchemaEnableFlag"
