package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import io.github.dnedrow.mockey.api.RecordedResponseDto
import io.github.dnedrow.mockey.api.ReplayRequestDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.ServiceStatisticsDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/*
 * Lets the stand-in server hold a history. The tests need this to load the history pages, press one of their
 * buttons, and load the pages again.
 *
 * The stand-in follows the server's rules only where the pages depend on them.
 */

private const val CREATED = 201
private const val NOT_FOUND = 404
private const val INVALID = 422
private const val NOT_VALID = "The request is not valid."

/** The time of every entry that a replay records. */
internal const val REPLAYED_AT = "2026-05-06T07:08:09Z"

/** The response of every replay. */
internal val REPLAYED_RESPONSE = RecordedResponseDto(202, listOf(HeaderDto("Content-Type", "text/plain")), Samples.body("replayed"))

/** Returns the body that [base64] holds, which is a complete body in Base64. */
private fun bodyOf(base64: String): RecordedBodyDto {
    val size = Base64.getDecoder().decode(base64).size
    return RecordedBodyDto(base64, size.toLong(), isCut = false)
}

/** Returns what [body] holds as text in UTF-8. */
private fun textOf(body: RecordedBodyDto): String = String(Base64.getDecoder().decode(body.base64), Charsets.UTF_8)

/** Returns everything of [entry] that a filter token can occur in, as one text. */
private fun searchable(entry: HistoryEntryDto): String {
    val ofService = entry.service?.let { listOf(it.id, it.name) + it.tags }.orEmpty()
    val headers = (entry.request.headers + entry.response.headers).map { "${it.name}: ${it.value}" }
    val parameters = entry.request.parameters.map { "${it.name}=${it.value}" }
    val bodies = listOf(textOf(entry.request.body), textOf(entry.response.body))
    val ofRequest = listOf(entry.clientAddress.orEmpty(), entry.request.url)
    return (ofService + entry.scenario?.tags.orEmpty() + ofRequest + headers + parameters + bodies).joinToString("\u0000")
}

/** Returns whether [entry] satisfies [token]: the token occurs in it, or after a `!`, the rest of the token doesn't. */
private fun satisfies(
    entry: HistoryEntryDto,
    token: String,
): Boolean = if (token.startsWith("!")) token.drop(1) !in searchable(entry) else token in searchable(entry)

/**
 * Makes the stand-in hold the history [entries], and returns a live view of them, keyed by their identifiers.
 * [services] is the live view of the services the stand-in holds (see [hold]): the statistics count the
 * entries of each of them, and saving an entry as a scenario adds the scenario to its service.
 *
 * Like a real server, the stand-in lists the newest entry first, and it limits the list by filter tokens, by
 * a service, by the entry that the list starts after, and by a maximum. A replay records a new entry. Its
 * request holds what the replay sent, its time is [REPLAYED_AT], and its response is [REPLAYED_RESPONSE]. The
 * stand-in refuses to replay an entry with a cut request body unless the replay supplies a body, and it
 * refuses to make a scenario from an entry without a service or with a cut response body.
 */
internal fun FakeAdminApi.holdHistory(
    entries: List<HistoryEntryDto> = listOf(Samples.entry),
    services: MutableMap<String, ServiceDto> = Collections.synchronizedMap(LinkedHashMap()),
): MutableMap<Long, HistoryEntryDto> {
    val held = Collections.synchronizedMap(LinkedHashMap<Long, HistoryEntryDto>())
    val latest = AtomicLong(entries.maxOfOrNull { it.id } ?: 0)
    val gone = { id: Long -> problem(NOT_FOUND, "The history keeps no entry with the identifier $id.") }
    val json = AdminApiJson.instance

    fun replayOf(
        entry: HistoryEntryDto,
        edits: ReplayRequestDto?,
    ): HistoryEntryDto {
        val escaped = { text: String -> URLEncoder.encode(text, Charsets.UTF_8) }
        val query = edits?.parameters?.joinToString("&") { "${escaped(it.name)}=${escaped(it.value)}" }
        val body = edits?.bodyBase64?.let(::bodyOf)
        val request =
            entry.request.copy(
                headers = edits?.headers ?: entry.request.headers,
                query = if (edits?.parameters == null) entry.request.query else query?.takeIf { it.isNotEmpty() },
                parameters = edits?.parameters ?: entry.request.parameters,
                body = body ?: entry.request.body,
            )
        val replayed = entry.copy(id = latest.incrementAndGet(), time = REPLAYED_AT, flagged = false)
        return replayed.copy(request = request, response = REPLAYED_RESPONSE)
    }

    /** Adds a scenario with the response of [entry] to [service], with the name that [body] gives, and returns it. */
    fun scenarioOf(
        entry: HistoryEntryDto,
        service: ServiceDto,
        body: String,
    ): ScenarioDto {
        val stated = body.takeIf { it.isNotEmpty() }?.let { json.parseToJsonElement(it).jsonObject }
        val name = stated?.get("name")?.jsonPrimitive?.content ?: "Scenario (auto-generated)"
        val response = entry.response
        val headers = response.headers.filterNot { it.name.equals("Content-Length", ignoreCase = true) }
        val scenario = ScenarioDto("sc-of-entry-${entry.id}", name, textOf(response.body), response.status, headers, MatchCriterionDto.None)
        services[service.id] = service.copy(scenarios = service.scenarios + scenario)
        return scenario
    }

    fun take(entry: HistoryEntryDto) {
        val id = entry.id
        val path = "history/$id"
        held[id] = entry
        on("GET", path) { held[id]?.let { json(it) } ?: gone(id) }
        on("PATCH", path) { request ->
            val stated = json.parseToJsonElement(request.body).jsonObject
            val flagged = stated["flagged"]?.jsonPrimitive?.booleanOrNull
            val before = held[id]
            if (before == null) gone(id) else json(before.copy(flagged = flagged ?: before.flagged).also { held[id] = it })
        }
        on("DELETE", path) { if (held.remove(id) == null) gone(id) else done() }
        on("POST", "$path/replay") { request ->
            val before = held[id]
            val edits = request.body.takeIf { it.isNotEmpty() }?.let { json.decodeFromString(ReplayRequestDto.serializer(), it) }
            val needsBody = before?.request?.body?.isCut == true && edits?.bodyBase64 == null
            val cut = listOf(FieldErrorDto("bodyBase64", "The request body of the history entry $id was cut."))
            when {
                before == null -> gone(id)
                needsBody -> problem(INVALID, NOT_VALID, cut)
                else -> json(replayOf(before, edits).also(::take), CREATED)
            }
        }
        on("POST", "$path/scenario") { request ->
            val before = held[id]
            val service = before?.service?.let { services[it.id] }
            val refusal =
                when {
                    before == null -> null
                    before.service == null -> "The history entry $id has no service."
                    before.response.body.isCut -> "The response body of the history entry $id was cut."
                    else -> null
                }
            when {
                before == null -> gone(id)
                refusal != null -> problem(INVALID, NOT_VALID, listOf(FieldErrorDto("entry", refusal)))
                service == null -> problem(NOT_FOUND, "The service of the history entry $id no longer exists.")
                else -> json(scenarioOf(before, service, request.body), CREATED)
            }
        }
    }
    entries.forEach(::take)
    on("GET", "history") { request ->
        val asked = parseQueryString(request.query.orEmpty())
        val tokens = asked.getAll("token").orEmpty()
        val newestFirst = held.values.sortedByDescending { it.id }
        val ofService = newestFirst.filter { entry -> asked["serviceId"]?.let { entry.service?.id == it } ?: true }
        val after = ofService.filter { entry -> asked["after"]?.let { entry.id > it.toLong() } ?: true }
        val matching = after.filter { entry -> tokens.all { satisfies(entry, it) } }
        json(asked["max"]?.let { matching.take(it.toInt()) } ?: matching)
    }
    on("DELETE", "history") { request ->
        val serviceId = parseQueryString(request.query.orEmpty())["serviceId"]
        if (serviceId == null) held.clear() else held.values.removeIf { it.service?.id == serviceId }
        done()
    }
    on("GET", "stats") { request ->
        val asked = parseQueryString(request.query.orEmpty())
        val from = asked["from"]?.let(Instant::parse)
        val to = asked["to"]?.let(Instant::parse)
        // Without a range, every entry counts, whatever its time is. So an entry whose time can't be read doesn't matter then.
        val isOpen = from == null && to == null
        val within = { time: Instant -> (from == null || time >= from) && (to == null || time <= to) }
        val counted = held.values.filter { isOpen || within(Instant.parse(it.time)) }
        val ofServices =
            services.values.map { service ->
                val its = counted.filter { it.service?.id == service.id }
                ServiceStatisticsDto(service.id, service.name, its.size, its.minOfOrNull { it.time })
            }
        val statistics = HistoryStatisticsDto(ofServices, held.values.minOfOrNull { it.time }, asked["from"], asked["to"])
        if (asked["format"] == "csv") {
            val rows = ofServices.map { "${it.serviceId},${it.serviceName},${it.count},${it.earliestEntryTime.orEmpty()}" }
            val lines = listOf("Service identifier,Service name,Entries,Earliest entry") + rows
            FakeAdminApi.Answer(200, lines.joinToString("\r\n"), "text/csv")
        } else {
            json(statistics)
        }
    }
    return held
}
