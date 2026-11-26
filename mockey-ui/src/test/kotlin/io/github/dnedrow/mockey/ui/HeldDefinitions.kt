package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Lets the stand-in server export, import, and search its definitions, and lets it switch its mode, reload,
 * reset, and clear the last-used times. The tests need this to load a page, press one of its buttons, and
 * load the page again.
 *
 * The stand-in follows the server's rules only where the pages depend on them.
 */

private const val OK = 200
private const val BAD_REQUEST = 400
private const val SERVER_ERROR = 500
private const val NO_TERM = "The query string of the request has to state what to search for, in the parameter term."

/** An import that the stand-in got: the [document] as its bytes, the media type that the request declared, and the [tags]. */
internal class Imported(
    val document: ByteArray,
    val contentType: String?,
    val tags: List<String>,
)

/**
 * Makes the stand-in export, import, and search the definitions that it holds, and returns a live list of
 * the imports that it got. [services] and [plans] are the live views of what the stand-in holds (see [hold]
 * and [holdPlans]).
 *
 * The export is a JSON document with the names of the services and the plans. That's enough to tell that
 * the UI hands on what the server sent. An import changes nothing that the stand-in holds. Its answer is
 * what [answerTo] returns, which is the sample report unless a test says otherwise.
 *
 * Like a real server, the stand-in finds a term without regard to letter case: in the name, the tags, the
 * mock URL, and the real URLs of a service, in the name, the tags, and the response body of a scenario, and
 * in the name and the tags of a plan. The plans come first, and each service is followed by its scenarios.
 * A blank term finds nothing.
 */
internal fun FakeAdminApi.holdDefinitions(
    services: MutableMap<String, ServiceDto>,
    plans: MutableMap<String, PlanDto>,
    answerTo: (Imported) -> FakeAdminApi.Answer = { json(Samples.importReport) },
): MutableList<Imported> {
    val imports = CopyOnWriteArrayList<Imported>()
    on("GET", "definitions/export") {
        val document =
            buildJsonObject {
                put("formatVersion", 1)
                put("services", JsonArray(services.values.map { JsonPrimitive(it.name) }))
                put("plans", JsonArray(plans.values.map { JsonPrimitive(it.name) }))
            }
        FakeAdminApi.Answer(OK, "$document")
    }
    on("POST", "definitions/import") { request ->
        val tags = parseQueryString(request.query.orEmpty()).getAll("tag").orEmpty()
        val imported = Imported(request.bytes, request.header("content-type"), tags)
        imports += imported
        answerTo(imported)
    }
    on("GET", "search") { request ->
        val term = parseQueryString(request.query.orEmpty())["term"]
        if (term == null) problem(BAD_REQUEST, NO_TERM) else json(found(term, services, plans))
    }
    return imports
}

/** Returns what a search for [term] finds in [services] and [plans], in the order of a server. */
private fun found(
    term: String,
    services: Map<String, ServiceDto>,
    plans: Map<String, PlanDto>,
): List<SearchResultDto> {
    val wanted = term.trim()
    if (wanted.isEmpty()) return emptyList()
    val holds = { texts: List<String> -> texts.any { it.contains(wanted, ignoreCase = true) } }
    val ofPlans = plans.values.filter { holds(it.tags + it.name) }.map { SearchResultDto("plan", it.id, it.name) }
    val ofServices =
        services.values.flatMap { service ->
            val itself = SearchResultDto("service", service.id, service.name)
            val matching = service.scenarios.filter { holds(it.tags + it.name + it.responseBody) }
            val ofScenarios = matching.map { SearchResultDto("scenario", it.id, it.name, service.id, service.name) }
            listOf(itself).filter { holds(service.tags + service.name + service.mockUrl + service.realUrls) } + ofScenarios
        }
    return ofPlans + ofServices
}

/**
 * Makes the stand-in switch between read-only mode and writable mode, reload, reset, and clear the last-used
 * times. [services] and [plans] are the live views of what the stand-in holds (see [hold] and [holdPlans]).
 *
 * Like a real server, the stand-in reports its mode in the server status. A reset removes every service and
 * every plan and clears the tag filter and the enabled twist configuration. Clearing the last-used times
 * removes them from every service, scenario, and plan. A reload changes nothing, because the stand-in has no
 * definitions location. It fails with [reloadFailure] if a test sets one, the way a server fails when it
 * can't read a file.
 */
internal fun FakeAdminApi.holdSystem(
    services: MutableMap<String, ServiceDto>,
    plans: MutableMap<String, PlanDto>,
    reloadFailure: String? = null,
) {
    on("PATCH", "system") { request ->
        val stated = Json.parseToJsonElement(request.body).jsonObject["readOnly"]
        if (stated != null) system = system.copy(readOnly = stated.jsonPrimitive.boolean)
        json(system)
    }
    on("POST", "system/reload") { if (reloadFailure == null) done() else problem(SERVER_ERROR, reloadFailure) }
    on("POST", "system/reset") {
        services.clear()
        plans.clear()
        system = system.copy(tagFilter = emptyList(), enabledTwist = null)
        done()
    }
    on("DELETE", "system/last-used-times") {
        for ((id, service) in services.toMap()) {
            services[id] = service.copy(lastUsedTime = null, scenarios = service.scenarios.map { it.copy(lastUsedTime = null) })
        }
        for ((id, plan) in plans.toMap()) plans[id] = plan.copy(lastUsedTime = null)
        done()
    }
}

/**
 * Makes the stand-in hold samples of every kind: services with a diagnostic, a plan, a twist configuration,
 * tags, proxy settings, and a history entry. It also answers everything about the definitions as a whole and
 * about the server itself. Returns the live view of the services.
 */
internal fun FakeAdminApi.holdSamples(): MutableMap<String, ServiceDto> {
    val services = hold(diagnostics = listOf(Samples.diagnostic))
    val plans = holdPlans(services)
    holdTwists()
    holdTags(services, plans)
    holdProxy()
    holdHistory(services = services)
    holdDefinitions(services, plans)
    holdSystem(services, plans)
    return services
}
