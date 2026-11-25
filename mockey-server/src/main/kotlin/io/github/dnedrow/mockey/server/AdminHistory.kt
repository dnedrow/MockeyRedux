package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HistoryEntryAttributesDto
import io.github.dnedrow.mockey.api.ReplayRequestDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.engine.ReplayResult
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.history.HistoryQuery
import io.github.dnedrow.mockey.core.history.ReplayEdits
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.request.Parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/**
 * The operations of the admin API on the history of [engine]: the entries of the requests that the mock
 * endpoint handled, and the statistics that the history gives of the services.
 *
 * The history is held in memory, and it keeps as many entries as the server was started to keep. So an
 * entry that is there for one request can be gone for the next one, and an operation on an entry that is
 * gone gets the status 404. An entry is stated with everything that it holds, and its bodies in Base64.
 *
 * A request that lists entries, deletes several, or reads the statistics says which ones it means in its
 * query string. What is no value of a parameter gets the status 400, as a parameter does that the operation
 * does not take. A problem repeats no value: a filter token can be a secret that the client looks for.
 */
internal class AdminHistory(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, HISTORY, query = setOf(TOKEN, SERVICE_ID, AFTER, MAX)) { list() },
            AdminRoute(HttpMethod.Delete, HISTORY, query = setOf(SERVICE_ID)) { deleteSeveral() },
            AdminRoute(HttpMethod.Get, ENTRY) { AdminResponse.json(entry().toAdminDto()) },
            AdminRoute(HttpMethod.Patch, ENTRY) { update() },
            AdminRoute(HttpMethod.Delete, ENTRY) {
                if (!engine.history.delete(entry().id)) throw notKept()
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Post, "$ENTRY/replay") { replay() },
            AdminRoute(HttpMethod.Post, "$ENTRY/scenario") { saveAsScenario() },
            AdminRoute(HttpMethod.Get, "stats", query = setOf(FROM, TO, FORMAT)) { statistics() },
        )

    /**
     * Answers with the entries that the query string asks for, with the newest one first: the ones that
     * satisfy every token, that the service handled, and that were recorded after the entry with the
     * identifier of the request, and of them as many of the newest as the request takes at most. A request
     * without a parameter asks for every entry that the history keeps.
     *
     * A token is taken as the request has it, with its letter case and its spaces. The service and the
     * entry that a request names need not be there: an entry stays when its service is deleted, and an
     * entry that the history has dropped still says what came after it.
     */
    private fun AdminRequest.list(): AdminResponse {
        val after = queryValue(AFTER)?.let { entryId(it) ?: throw noValue(AFTER, "the identifier of a history entry") }
        val query = HistoryQuery(queryValues(TOKEN), serviceIdOfTheQuery(), after, maximum())
        return AdminResponse.json(engine.history.entries(query).map(HistoryEntry::toAdminDto))
    }

    /** Deletes the entries of the service that the query string names, and every entry when it names none. */
    private fun AdminRequest.deleteSeveral(): AdminResponse {
        val serviceId = serviceIdOfTheQuery()
        if (serviceId == null) engine.history.clear() else engine.history.deleteOfService(serviceId)
        return AdminResponse.noContent()
    }

    /** Sets and removes the mark of the entry of the request as the request states it, and answers with the entry. */
    private suspend fun AdminRequest.update(): AdminResponse {
        val entry = entry()
        val stated = attributes(HistoryEntryAttributesDto.serializer()).on(HistoryEntryAttributesDto(entry.flagged))
        val marked = engine.history.flag(entry.id, stated.flagged) ?: throw notKept()
        return AdminResponse.json(marked.toAdminDto())
    }

    /**
     * Replays the entry of the request, with what the body of the request changes of it, and answers with
     * the entry of the replay: the response in it is what the replay was answered with. A request without a
     * body replays the entry as it is. The entry of the replay states the client of this request as its
     * client.
     *
     * The answer is that of a creation, with where the new entry is.
     *
     * @throws AdminProblem with the status 422 when the entry cannot be replayed without another body,
     *   because its own was cut, and when the body of the request states one that is no Base64.
     */
    private suspend fun AdminRequest.replay(): AdminResponse {
        val id = entry().id
        val edits = bodyOrNull(ReplayRequestDto.serializer())?.let(::editsOf) ?: ReplayEdits()
        val replayed =
            when (val result = engine.history.replay(id, edits, clientAddress)) {
                is ReplayResult.Replayed -> result.handled.entry
                is ReplayResult.Refused -> throw AdminProblem.invalid(listOf(FieldErrorDto(BODY, result.explanation)))
                is ReplayResult.NotFound -> throw notKept()
            }
        return AdminResponse.created(replayed.toAdminDto(), HISTORY, "${replayed.id}")
    }

    /** Returns what [stated], the body of a request for a replay, changes of the request that is replayed. */
    private fun editsOf(stated: ReplayRequestDto): ReplayEdits {
        val body =
            try {
                stated.bodyBase64?.let(Base64.getDecoder()::decode)
            } catch (_: IllegalArgumentException) {
                throw AdminProblem.invalid(listOf(FieldErrorDto(BODY, "The value has to be a text in Base64.")))
            }
        return ReplayEdits(stated.headers?.map { Header(it.name, it.value) }, stated.parameters?.map { Parameter(it.name, it.value) }, body)
    }

    /**
     * Creates a scenario that gives the response of the entry of the request, for the service that handled
     * the request of the entry, and answers with the scenario and where it is. The body of the request
     * states attributes of the scenario, such as its name, see [AdminScenarios]. They take the place of what
     * the entry gives. A request without a body leaves the scenario as the entry gives it.
     *
     * The engine refuses an entry without a service and one whose response body was cut. The problem names
     * the entry as what is not valid then.
     */
    private suspend fun AdminRequest.saveAsScenario(): AdminResponse {
        val entry = entry()
        val stated = if (hasBody) statedScenarioAttributes() else null
        val saved = engine.history.saveAsScenario(entry.id) { given -> stated?.let { given.with(it.on(given.attributes())) } ?: given }
        val scenario = saved.orProblem()
        val service = checkNotNull(entry.service) { "The engine makes a scenario only of an entry with a service." }
        return AdminResponse.created(scenario.toAdminDto(), "services", service.id.value, "scenarios", scenario.id.value)
    }

    /**
     * Answers with the statistics of the history: for every service, how many entries the history keeps of
     * it, and when the earliest of them arrived. The query string limits the entries that count to those
     * from a time on and to those up to a time, and it says in which format the answer is: JSON, which is
     * the default, or CSV.
     */
    private fun AdminRequest.statistics(): AdminResponse {
        val format = queryValue(FORMAT) ?: JSON
        if (format != JSON && format != CSV) throw noValue(FORMAT, "$JSON or $CSV")
        val statistics = engine.history.statistics(timeOf(FROM), timeOf(TO))
        if (format == JSON) return AdminResponse.json(statistics.toAdminDto())
        return AdminResponse(HttpStatusCode.OK, statistics.toCsv(), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
    }

    /**
     * Returns the entry that the path of the request names.
     *
     * @throws AdminProblem with the status 404 when the history keeps no such entry.
     */
    private fun AdminRequest.entry(): HistoryEntry = entryId(parameter(ENTRY_ID))?.let(engine.history::entry) ?: throw notKept()

    /** Returns the problem of a request for an entry that the history does not keep, or keeps no longer. */
    private fun AdminRequest.notKept(): AdminProblem =
        AdminProblem(HttpStatusCode.NotFound, "The history keeps no entry with the identifier ${parameter(ENTRY_ID)}.")

    /**
     * Returns the service that the query string names, or `null` when it names none.
     *
     * @throws AdminProblem with the status 400 when the parameter is blank, which no identifier is.
     */
    private fun AdminRequest.serviceIdOfTheQuery(): ServiceId? {
        val text = queryValue(SERVICE_ID) ?: return null
        return if (text.isBlank()) throw noValue(SERVICE_ID, "the identifier of a service") else ServiceId(text)
    }

    /** Returns the identifier of an entry that [text] is, or `null` when it is no whole number as the history writes one. */
    private fun entryId(text: String): HistoryEntryId? = text.takeIf(WHOLE_NUMBER::matches)?.toLongOrNull()?.let(::HistoryEntryId)

    /** Returns the greatest number of entries that the request takes, or `null` when it takes any number. */
    private fun AdminRequest.maximum(): Int? {
        val text = queryValue(MAX) ?: return null
        return text.takeIf(WHOLE_NUMBER::matches)?.toIntOrNull() ?: throw noValue(MAX, "a whole number from 0 to ${Int.MAX_VALUE}")
    }

    /**
     * Returns the time that the query string has at the parameter [name], or `null` when it has none there.
     * A time is written as ISO 8601 has it, with a date, a time of day, and `Z` or an offset from UTC.
     */
    private fun AdminRequest.timeOf(name: String): Instant? {
        val text = queryValue(name) ?: return null
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeException) {
            throw noValue(name, "a time in ISO 8601 with an offset, such as 2026-01-02T03:04:05Z")
        }
    }

    /** Returns the problem of a request whose query string has something at the parameter [name] that is not [what] the parameter takes. */
    private fun noValue(
        name: String,
        what: String,
    ): AdminProblem = AdminProblem(HttpStatusCode.BadRequest, "The parameter $name of the query string has to be $what.")

    private companion object {
        const val HISTORY = "history"
        const val ENTRY_ID = "entryId"
        const val ENTRY = "history/{entryId}"
        const val TOKEN = "token"
        const val SERVICE_ID = "serviceId"
        const val AFTER = "after"
        const val MAX = "max"
        const val FROM = "from"
        const val TO = "to"
        const val FORMAT = "format"
        const val JSON = "json"
        const val CSV = "csv"

        /** The member of the body of a replay that states another body. */
        const val BODY = "bodyBase64"

        /** A whole number that is not negative, as an identifier of an entry is written. */
        val WHOLE_NUMBER = Regex("0|[1-9][0-9]*")
    }
}
