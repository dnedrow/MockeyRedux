package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.RealUrlDerivationRequestDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.ServiceMergeRequestDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * What lets a stand-in for a server hold something as a server holds it, for the tests that read a page, send
 * a form of it, and read the page again.
 */

/**
 * Makes the stand-in hold [services], report [diagnostics], and list [inspectors]. It returns a live view of
 * the services the stand-in holds, keyed by their identifiers. [universal] is the universal error scenario to
 * start with.
 *
 * The stand-in lists the services that carry one of the tags of a request. It can create, update, delete, and
 * duplicate a service, and it can create, update, and delete the scenarios of a service. It can also set and
 * clear the universal error scenario. Like a real server, it reports proxy mode as the effective mode of a
 * service without a scenario, and it makes the first scenario of a service the default scenario. Apart from
 * that, the stand-in stores every attribute exactly as a request sends it, because it doesn't implement the
 * validation rules of a server.
 *
 * The stand-in also merges services and derives real URLs by the rules of a server. A merge copies the
 * scenarios that the destination doesn't have with the same name and response body, and it deletes the
 * merged services only if no scenario was skipped. A derivation adds no real URL that a service lists
 * already.
 *
 * A service with a response schema has a schema report. The report is the one that [schemaReports] holds for
 * the identifier of the service. Without one, the report says that every scenario conforms.
 *
 * A request names a service and a scenario by their identifiers in its path. [paths] maps an identifier to
 * the path segment that a client writes for it. That's only needed for identifiers that a client has to
 * escape.
 */
internal fun FakeAdminApi.hold(
    services: List<ServiceDto> = listOf(Samples.customers, Samples.orders),
    diagnostics: List<DiagnosticDto> = emptyList(),
    paths: Map<String, String> = emptyMap(),
    inspectors: List<InspectorDto> = listOf(Samples.inspector),
    universal: ScenarioReferenceDto? = null,
    schemaReports: Map<String, SchemaReportDto> = emptyMap(),
): MutableMap<String, ServiceDto> {
    val held = Collections.synchronizedMap(LinkedHashMap<String, ServiceDto>())
    val universalError = AtomicReference(universal)
    val created = AtomicInteger()
    val gone = { id: String -> problem(NOT_FOUND, "No service has the identifier $id.") }
    val noScenario = { id: String -> problem(NOT_FOUND, "The service has no scenario with the identifier $id.") }

    fun takeScenario(
        serviceId: String,
        scenarioId: String,
    ) {
        val path = "services/${paths[serviceId] ?: serviceId}/scenarios/${paths[scenarioId] ?: scenarioId}"
        val find = { held[serviceId]?.scenarios?.firstOrNull { it.id == scenarioId } }
        on("GET", path) { find()?.let { json(it) } ?: noScenario(scenarioId) }
        on("PATCH", path) { request ->
            val service = held[serviceId]
            val after = find()?.with(request.body)
            if (service == null || after == null) {
                noScenario(scenarioId)
            } else {
                held[serviceId] = service.copy(scenarios = service.scenarios.map { if (it.id == scenarioId) after else it })
                json(after)
            }
        }
        on("DELETE", path) {
            val service = held[serviceId]
            if (service == null || find() == null) {
                noScenario(scenarioId)
            } else {
                held[serviceId] = service.without(scenarioId)
                universalError.compareAndSet(ScenarioReferenceDto(serviceId, scenarioId), null)
                done()
            }
        }
    }

    /** Merges the services [sourceIds] into [destination] by the rules of a server, and returns the report. */
    fun merged(
        destination: ServiceDto,
        sourceIds: List<String>,
    ): MergeReportDto {
        val sources = sourceIds.map(held::getValue)
        val copied = mutableListOf<MergedScenarioDto>()
        val skipped = mutableListOf<MergedScenarioDto>()
        var scenarios = destination.scenarios
        for (source in sources) {
            for (scenario in source.scenarios) {
                val item = MergedScenarioDto(source.name, scenario.name)
                if (scenarios.any { it.name == scenario.name && it.responseBody == scenario.responseBody }) {
                    skipped += item
                } else {
                    val copy = scenario.copy(id = "${scenario.id}-merged", lastUsedTime = null)
                    scenarios = scenarios + copy
                    copied += item
                    takeScenario(destination.id, copy.id)
                }
            }
        }
        val deleted = skipped.isEmpty() && sources.isNotEmpty()
        val realUrls = if (deleted) (destination.realUrls + sources.flatMap { it.realUrls }).distinct() else destination.realUrls
        val tags = (destination.tags + sources.flatMap { it.tags }).distinct()
        val default = destination.defaultScenarioId ?: scenarios.firstOrNull()?.id
        val mode = if (scenarios.isEmpty()) ResponseModeDto.PROXY else destination.responseMode
        val after = destination.copy(realUrls = realUrls, tags = tags, scenarios = scenarios, defaultScenarioId = default)
        held[destination.id] = after.copy(effectiveResponseMode = mode)
        if (deleted) sourceIds.forEach(held::remove)
        return MergeReportDto(copied, skipped, deleted)
    }

    fun take(service: ServiceDto) {
        val id = service.id
        val path = "services/${paths[id] ?: id}"
        held[id] = service
        service.scenarios.forEach { takeScenario(id, it.id) }
        on("POST", "$path/merge") { request ->
            val destination = held[id]
            val asked = AdminApiJson.instance.decodeFromString(ServiceMergeRequestDto.serializer(), request.body)
            val unknown = asked.sourceIds.indices.filter { asked.sourceIds[it] !in held }
            when {
                destination == null -> gone(id)
                unknown.isNotEmpty() -> problem(INVALID, NOT_VALID, unknown.map { FieldErrorDto("sourceIds[$it]", NO_SUCH_SERVICE) })
                else -> json(merged(destination, asked.sourceIds.distinct() - id))
            }
        }
        on("GET", "$path/schema-report") {
            val current = held[id]
            val everyOneConforms = current?.scenarios.orEmpty().map { ScenarioConformanceDto(it.id, it.name, conforms = true, emptyList()) }
            val report: SchemaReportDto = schemaReports[id] ?: SchemaReportDto.Checked(everyOneConforms)
            when {
                current == null -> gone(id)
                current.responseSchema == null -> problem(NOT_FOUND, "The service '${current.name}' has no response schema.")
                else -> json(report)
            }
        }
        on("GET", "$path/scenarios") { held[id]?.let { json(it.scenarios) } ?: gone(id) }
        on("POST", "$path/scenarios") { request ->
            val before = held[id]
            if (before == null) {
                gone(id)
            } else {
                val blank = ScenarioDto("sc-new-${created.incrementAndGet()}", "", "", OK, emptyList(), MatchCriterionDto.None)
                val scenario = blank.with(request.body)
                val default = before.defaultScenarioId ?: scenario.id
                val mode = before.responseMode
                held[id] = before.copy(scenarios = before.scenarios + scenario, defaultScenarioId = default, effectiveResponseMode = mode)
                takeScenario(id, scenario.id)
                json(scenario, CREATED)
            }
        }
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
    on("POST", "services/real-urls/derive") { request ->
        val asked = AdminApiJson.instance.decodeFromString(RealUrlDerivationRequestDto.serializer(), request.body)
        val regex = asked.expression.takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it) }.getOrNull() }
        val used = asked.replacements.filter { it.isNotBlank() }
        when {
            regex == null -> problem(INVALID, NOT_VALID, listOf(FieldErrorDto("expression", NO_EXPRESSION)))
            used.isEmpty() -> problem(INVALID, NOT_VALID, listOf(FieldErrorDto("replacements", NO_REPLACEMENT)))
            else -> json(derived(held, regex, used))
        }
    }
    on("GET", "diagnostics") { json(diagnostics) }
    on("GET", "inspectors") { json(inspectors) }
    on("GET", "universal-error-scenario") {
        universalError.get()?.let { json(it) } ?: problem(NOT_FOUND, "No scenario is designated as the universal error scenario.")
    }
    on("PUT", "universal-error-scenario") { request ->
        val designated = AdminApiJson.instance.decodeFromString(ScenarioReferenceDto.serializer(), request.body)
        json(designated.also(universalError::set))
    }
    on("DELETE", "universal-error-scenario") {
        universalError.set(null)
        done()
    }
    return held
}

private const val OK = 200
private const val CREATED = 201
private const val NOT_FOUND = 404
private const val INVALID = 422
private const val NOT_VALID = "The request is not valid."
private const val NO_SUCH_SERVICE = "No service has this identifier."
private const val NO_EXPRESSION = "The expression must be a regular expression that is not empty."
private const val NO_REPLACEMENT = "At least one replacement that is not blank is needed."

/**
 * Adds real URLs to the services in [held] by the rules of a server, and returns the ones that were added.
 * Each real URL gives one URL for each of [replacements]: the URL in which the matches of [regex] are
 * replaced. A URL that a service already lists isn't added.
 */
private fun derived(
    held: MutableMap<String, ServiceDto>,
    regex: Regex,
    replacements: List<String>,
): List<DerivedRealUrlDto> {
    val listed = held.values.flatMap { it.realUrls }.toMutableSet()
    val added = mutableListOf<DerivedRealUrlDto>()
    for ((id, service) in held.toMap()) {
        val urls = service.realUrls.flatMap { url -> replacements.map { url.replace(regex, it) } }.filter(listed::add)
        added += urls.map { DerivedRealUrlDto(id, service.name, it) }
        if (urls.isNotEmpty()) held[id] = service.copy(realUrls = service.realUrls + urls)
    }
    return added
}

/** Returns this scenario with the attributes that [body] states. [body] is the JSON body of a request. */
private fun ScenarioDto.with(body: String): ScenarioDto {
    val json = AdminApiJson.instance
    val stated = json.parseToJsonElement(body).jsonObject
    val now = json.encodeToJsonElement(ScenarioDto.serializer(), this).jsonObject
    return json.decodeFromJsonElement(ScenarioDto.serializer(), JsonObject(now + stated))
}

/**
 * Returns this service without its scenario [scenarioId], the way a server leaves the service after the
 * deletion. If the deleted scenario was the default scenario, the oldest remaining scenario takes over. If it
 * was the error scenario, the service ends up without one.
 */
private fun ServiceDto.without(scenarioId: String): ServiceDto {
    val left = scenarios.filter { it.id != scenarioId }
    val default = if (defaultScenarioId == scenarioId) left.firstOrNull()?.id else defaultScenarioId
    val error = errorScenarioId.takeIf { it != scenarioId }
    val mode = if (left.isEmpty()) ResponseModeDto.PROXY else responseMode
    return copy(scenarios = left, defaultScenarioId = default, errorScenarioId = error, effectiveResponseMode = mode)
}

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
