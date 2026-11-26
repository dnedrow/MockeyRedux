package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The endpoint `/definitions` of the legacy shim. It lists the services of [engine] with their scenarios,
 * as far as a script needs them to name a service or a scenario in a request to another endpoint.
 *
 * The answer is a JSON object with the member `serviceDefinitions`, which lists every service in the order
 * in which the services were created. A request that names a service gets that service alone, see
 * [serviceNamedBy]. As in Mockey 2.x, a request that names a service which isn't there gets every service.
 *
 * Each service states its name and its identifier, its default scenario by identifier and by name, the mode
 * in which it answers, its delay, the name of its inspector, and its scenarios by identifier and by name,
 * in the order of their names. Mockey 2.x left out what a service doesn't have, and the list itself when
 * there was no service. Here every member is always there: a service without scenarios has an empty list of
 * them and `null` for its default scenario.
 */
internal class ShimDefinitions(
    private val engine: MockeyEngine,
) : ShimEndpoint {
    override suspend fun answer(parameters: ShimParameters): JsonObject {
        val definitions = engine.definitions
        val listed = definitions.serviceNamedBy(parameters)?.let(::listOf) ?: definitions.services
        return buildJsonObject {
            putJsonArray("serviceDefinitions") { listed.forEach { add(stated(it)) } }
        }
    }

    /** Returns what the answer states of [service]. */
    private fun stated(service: Service): JsonObject {
        val default = defaultScenarioOf(service)
        return buildJsonObject {
            put(SERVICE_NAME, service.name)
            put(SERVICE_ID, service.id.value)
            put("defaultScenarioId", default?.id?.value)
            put("defaultScenarioName", default?.name)
            put("serviceResponseType", modeNameOf(service))
            put("hangTime", service.delayMillis)
            put("requestInspectorName", inspectorNameOf(service))
            putJsonArray("scenarioArray") {
                for (scenario in service.scenarios.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })) {
                    add(
                        buildJsonObject {
                            put("scenarioId", scenario.id.value)
                            put("scenarioName", scenario.name)
                        },
                    )
                }
            }
        }
    }

    internal companion object {
        /** The path of the endpoint. */
        const val PATH = "/definitions"
    }
}

/**
 * Returns the default scenario of [service], which is the one that answers in static mode, or `null` for a
 * service without scenarios. It's the scenario that the service designates. A service that was stored by
 * hand can designate none, or one that it doesn't have, and then its first scenario answers.
 */
internal fun defaultScenarioOf(service: Service): Scenario? =
    service.scenarios.firstOrNull { it.id == service.defaultScenarioId } ?: service.scenarios.firstOrNull()

/**
 * Returns the name of the mode in which [service] answers, as Mockey 2.x named it: `proxy`, `static`, or
 * `dynamic`. A service without scenarios answers as a proxy, whatever mode it's set to.
 */
internal fun modeNameOf(service: Service): String = service.effectiveResponseMode.name.lowercase()

/**
 * Returns the name of the inspector of [service], as Mockey 2.x stated it. Mockey 2.x wrote the text `null`
 * for a service without an inspector, and scripts compare with that text.
 */
internal fun inspectorNameOf(service: Service): String = service.inspectorName ?: "null"
