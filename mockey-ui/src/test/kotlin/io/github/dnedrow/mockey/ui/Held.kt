package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/*
 * What lets a stand-in for a server hold something as a server holds it, for the tests that read a page, send
 * a form of it, and read the page again.
 */

/**
 * Makes the stand-in hold [services] and report [diagnostics]. It lists the services that carry one of the
 * tags of a request, and it gives a service the response mode or the default scenario that a request states.
 * A service without a scenario answers as in proxy mode, as on a server.
 *
 * A request names a service by its identifier as a segment of its path. [paths] says how a client writes the
 * identifiers that it does not write as they are.
 */
internal fun FakeAdminApi.hold(
    services: List<ServiceDto> = listOf(Samples.customers, Samples.orders),
    diagnostics: List<DiagnosticDto> = emptyList(),
    paths: Map<String, String> = emptyMap(),
) {
    val held = Collections.synchronizedMap(LinkedHashMap(services.associateBy { it.id }))
    on("GET", "services") { request ->
        val tags = parseQueryString(request.query.orEmpty()).getAll("tag").orEmpty()
        json(held.values.filter { service -> tags.isEmpty() || service.tags.any(tags::contains) })
    }
    on("GET", "diagnostics") { json(diagnostics) }
    for (id in services.map { it.id }) {
        on("PATCH", "services/${paths[id] ?: id}") { request ->
            val stated = AdminApiJson.instance.parseToJsonElement(request.body).jsonObject
            val before = held.getValue(id)
            val mode = stated["responseMode"]?.let { Modes.named(it.jsonPrimitive.content) } ?: before.responseMode
            val effective = if (before.scenarios.isEmpty()) ResponseModeDto.PROXY else mode
            val default = stated["defaultScenarioId"]?.jsonPrimitive?.content ?: before.defaultScenarioId
            val after = before.copy(responseMode = mode, effectiveResponseMode = effective, defaultScenarioId = default)
            json(after.also { held[id] = it })
        }
    }
}
