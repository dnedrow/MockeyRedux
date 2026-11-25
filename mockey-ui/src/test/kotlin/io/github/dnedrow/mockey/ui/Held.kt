package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Collections

/*
 * What lets a stand-in for a server hold something as a server holds it, for the tests that read a page, send
 * a form of it, and read the page again.
 */

/**
 * Makes the stand-in hold [services], report [diagnostics], and list [inspectors], and returns the services
 * that it holds, by their identifiers, as they are at any time.
 *
 * The stand-in lists the services that carry one of the tags of a request. It creates a service with the
 * attributes of a request, gives a service the attributes that a request states, deletes a service, and
 * duplicates one. A service without a scenario answers as in proxy mode, as on a server. The stand-in holds
 * an attribute as it is stated: it is no server, and it has no rules.
 *
 * A request names a service by its identifier as a segment of its path. [paths] says how a client writes the
 * identifiers that it does not write as they are.
 */
internal fun FakeAdminApi.hold(
    services: List<ServiceDto> = listOf(Samples.customers, Samples.orders),
    diagnostics: List<DiagnosticDto> = emptyList(),
    paths: Map<String, String> = emptyMap(),
    inspectors: List<InspectorDto> = listOf(Samples.inspector),
): Map<String, ServiceDto> {
    val held = Collections.synchronizedMap(LinkedHashMap<String, ServiceDto>())
    val gone = { id: String -> problem(NOT_FOUND, "No service has the identifier $id.") }

    fun take(service: ServiceDto) {
        val id = service.id
        val path = "services/${paths[id] ?: id}"
        held[id] = service
        on("GET", path) { held[id]?.let { json(it) } ?: gone(id) }
        on("PATCH", path) { request ->
            val before = held[id]
            if (before == null) gone(id) else json(before.with(request.body).also { held[id] = it })
        }
        on("DELETE", path) { if (held.remove(id) == null) gone(id) else done() }
        on("POST", "$path/duplicate") {
            val original = held[id]
            if (original == null) gone(id) else json(copyOf(original).also(::take), CREATED)
        }
    }
    services.forEach(::take)
    on("GET", "services") { request ->
        val tags = parseQueryString(request.query.orEmpty()).getAll("tag").orEmpty()
        json(held.values.filter { service -> tags.isEmpty() || service.tags.any(tags::contains) })
    }
    on("POST", "services") { request ->
        val created = ServiceDto("svc-new-${held.size + 1}", "", "").with(request.body)
        json(created.also(::take), CREATED)
    }
    on("GET", "diagnostics") { json(diagnostics) }
    on("GET", "inspectors") { json(inspectors) }
    return held
}

private const val CREATED = 201
private const val NOT_FOUND = 404

/** Returns this service with the attributes that [body] states, which is the body of a request as JSON. */
private fun ServiceDto.with(body: String): ServiceDto {
    val json = AdminApiJson.instance
    val stated = json.parseToJsonElement(body).jsonObject
    val now = json.encodeToJsonElement(ServiceDto.serializer(), this).jsonObject
    val after = json.decodeFromJsonElement(ServiceDto.serializer(), JsonObject(now + stated))
    return after.copy(effectiveResponseMode = if (after.scenarios.isEmpty()) ResponseModeDto.PROXY else after.responseMode)
}

/** Returns a copy of [original] as a server makes one: with a name and a mock URL of its own, and without the real URLs. */
private fun copyOf(original: ServiceDto): ServiceDto {
    val scenarios = original.scenarios.map { it.copy(id = "${it.id}-copy", lastUsedTime = null) }
    return original.copy(
        id = "${original.id}-copy",
        name = "${original.name} (copy)",
        mockUrl = "${original.mockUrl}-copy",
        realUrls = emptyList(),
        scenarios = scenarios,
        defaultScenarioId = original.defaultScenarioId?.let { "$it-copy" },
        errorScenarioId = original.errorScenarioId?.let { "$it-copy" },
        lastUsedTime = null,
    )
}
