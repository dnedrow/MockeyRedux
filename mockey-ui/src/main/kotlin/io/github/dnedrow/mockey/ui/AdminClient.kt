package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiDescription
import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanReferenceDto
import io.github.dnedrow.mockey.api.PlanSaveRequestDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.RealUrlDerivationRequestDto
import io.github.dnedrow.mockey.api.ReplayRequestDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.ServiceMergeRequestDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TagFilterDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.io.IOException
import java.net.ConnectException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration

/**
 * Why a request to the admin API gave nothing that the UI can show as what it asked for. The message says
 * it in a sentence, and it holds nothing that the UI has to keep to itself.
 */
internal sealed class ApiFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No answer came: no connection to the server was made, or the server did not answer in time. */
    class Unreachable(
        cause: Exception,
    ) : ApiFailure(reasonOf(cause), cause) {
        /** Whether the request can have reached the server, which did what it asks for then, for all that the UI knows. */
        val mayHaveArrived: Boolean = cause !is ConnectException && cause !is HttpConnectTimeoutException
    }

    /** The server asks for an admin token that the UI does not have, or it does not take the token of the UI. */
    class TokenRejected : ApiFailure("The server does not take the request without its admin token.")

    /**
     * What answers at the address is no admin API of the version that the UI needs: it has no such API, or
     * it answered with what that API does not answer with. [reason] says which, as a sentence.
     */
    class Incompatible(
        val reason: String,
    ) : ApiFailure(reason)

    /**
     * The API does not do what the request asks for, and says why in [problem]: the request names what is
     * not there, or it is not valid.
     */
    class Refused(
        val problem: ProblemDto,
    ) : ApiFailure(problem.detail)

    /** The server failed to handle the request. [problem] is what it says of that. */
    class Failed(
        val problem: ProblemDto,
    ) : ApiFailure(problem.detail)

    private companion object {
        /**
         * Returns why no answer came, as a sentence. It goes by the kind of [cause] first: for a connection
         * that is refused, the Java runtime has a message on one system and none on another.
         */
        fun reasonOf(cause: Exception): String {
            val said = cause.message
            val unresolved = generateSequence<Throwable>(cause) { it.cause }.any { it is UnresolvedAddressException }
            return when {
                cause is HttpConnectTimeoutException -> "No connection to the server was made in time."
                cause is HttpTimeoutException -> "The server did not answer in time."
                unresolved -> "The name of its host is not known."
                cause !is ConnectException -> "The connection failed: ${sentenceOf(said ?: cause.javaClass.simpleName)}"
                said == null || REFUSED in said -> "Nothing accepts connections there."
                else -> "No connection to the server could be made: ${sentenceOf(said)}"
            }
        }

        /** What the message of the system for a refused connection holds. */
        const val REFUSED = "refused"

        /** Returns [text] with a period at its end, unless it ends a sentence already. */
        fun sentenceOf(text: String): String = if (text.isNotEmpty() && text.last() in ENDS) text else "$text."

        const val ENDS = ".?!"
    }
}

/**
 * The client of the admin API of one server: every operation of the API that the UI uses, as a function
 * that sends the request and returns what the server answered with, as its wire type.
 *
 * The server is at [url], and the path of every request begins with the prefix of the API. The client
 * presents [token] with every request, when there is one. Nothing else of the UI holds the token.
 *
 * A function returns when the server did what it asked for. Otherwise it throws:
 *
 * @throws ApiFailure when the server gave no answer, did not take the token, is no server that the UI can
 *   manage, refused the request, or failed. A function says where it returns something in the place of a
 *   refusal.
 */
internal class AdminClient(
    private val url: AdminUrl,
    private val token: AdminToken?,
    connectTimeout: Duration = CONNECT_TIMEOUT,
    private val answerTimeout: Duration = ANSWER_TIMEOUT,
) : AutoCloseable {
    /** The client follows no redirect: an admin API answers itself, and the token is for no other place. */
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout)
            .build()

    /** A server of a later version can state more than this UI knows, which the UI leaves aside. */
    private val json = Json(AdminApiJson.instance) { ignoreUnknownKeys = true }

    // Services

    /** Returns the services that carry one of [tags], and of them the ones with [name]. Without either, it returns every service. */
    suspend fun listServices(
        tags: List<String> = emptyList(),
        name: String? = null,
    ): List<ServiceDto> = read(get(path("services"), tags.map { "tag" to it } + listOfNotNull(name?.let { "name" to it })))

    suspend fun createService(attributes: JsonObject): ServiceDto = read(send("POST", path("services"), body = attributes))

    suspend fun getService(id: String): ServiceDto = read(get(path("services", id)))

    suspend fun updateService(
        id: String,
        attributes: JsonObject,
    ): ServiceDto = read(send("PATCH", path("services", id), body = attributes))

    suspend fun deleteService(id: String) {
        send("DELETE", path("services", id))
    }

    suspend fun duplicateService(id: String): ServiceDto = read(send("POST", path("services", id, "duplicate")))

    suspend fun mergeServices(
        id: String,
        sourceIds: List<String>,
    ): MergeReportDto = read(send("POST", path("services", id, "merge"), body = written(ServiceMergeRequestDto(sourceIds))))

    /**
     * Returns the report of the response schema of the service [id]. A service without a response schema is
     * refused with the status 404.
     */
    suspend fun getSchemaReport(id: String): SchemaReportDto = read(get(path("services", id, "schema-report")))

    suspend fun deriveRealUrls(
        expression: String,
        replacements: List<String>,
    ): List<DerivedRealUrlDto> {
        val request = RealUrlDerivationRequestDto(expression, replacements)
        return read(send("POST", path("services", "real-urls", "derive"), body = written(request)))
    }

    // Scenarios

    suspend fun listScenarios(serviceId: String): List<ScenarioDto> = read(get(path("services", serviceId, "scenarios")))

    suspend fun createScenario(
        serviceId: String,
        attributes: JsonObject,
    ): ScenarioDto = read(send("POST", path("services", serviceId, "scenarios"), body = attributes))

    suspend fun getScenario(
        serviceId: String,
        scenarioId: String,
    ): ScenarioDto = read(get(path("services", serviceId, "scenarios", scenarioId)))

    suspend fun updateScenario(
        serviceId: String,
        scenarioId: String,
        attributes: JsonObject,
    ): ScenarioDto = read(send("PATCH", path("services", serviceId, "scenarios", scenarioId), body = attributes))

    suspend fun deleteScenario(
        serviceId: String,
        scenarioId: String,
    ) {
        send("DELETE", path("services", serviceId, "scenarios", scenarioId))
    }

    /** Returns the scenario that is designated as the universal error scenario, or `null` while none is. */
    suspend fun getUniversalErrorScenario(): ScenarioReferenceDto? = orNoneDesignated { read(get(path("universal-error-scenario"))) }

    suspend fun setUniversalErrorScenario(
        serviceId: String,
        scenarioId: String,
    ): ScenarioReferenceDto =
        read(send("PUT", path("universal-error-scenario"), body = written(ScenarioReferenceDto(serviceId, scenarioId))))

    suspend fun clearUniversalErrorScenario() {
        send("DELETE", path("universal-error-scenario"))
    }

    // Plans

    suspend fun listPlans(): List<PlanDto> = read(get(path("plans")))

    /** Saves the settings that the services of [request] have now as the plan with the name of the request, or as a new plan. */
    suspend fun savePlan(request: PlanSaveRequestDto): PlanDto = read(send("POST", path("plans"), body = written(request)))

    suspend fun getPlan(id: String): PlanDto = read(get(path("plans", id)))

    suspend fun updatePlan(
        id: String,
        attributes: JsonObject,
    ): PlanDto = read(send("PATCH", path("plans", id), body = attributes))

    suspend fun deletePlan(id: String) {
        send("DELETE", path("plans", id))
    }

    suspend fun saveIntoPlan(
        id: String,
        request: PlanSaveRequestDto,
    ): PlanDto = read(send("POST", path("plans", id, "save"), body = written(request)))

    suspend fun applyPlan(id: String): PlanApplyReportDto = read(send("POST", path("plans", id, "apply")))

    /** Returns the plan that is designated as the default plan, or `null` while none is. */
    suspend fun getDefaultPlan(): PlanReferenceDto? = orNoneDesignated { read(get(path("default-plan"))) }

    suspend fun setDefaultPlan(planId: String): PlanReferenceDto =
        read(send("PUT", path("default-plan"), body = written(PlanReferenceDto(planId))))

    suspend fun clearDefaultPlan() {
        send("DELETE", path("default-plan"))
    }

    // Routing

    suspend fun listTwists(): List<TwistConfigurationDto> = read(get(path("twists")))

    suspend fun createTwist(attributes: JsonObject): TwistConfigurationDto = read(send("POST", path("twists"), body = attributes))

    suspend fun getTwist(id: String): TwistConfigurationDto = read(get(path("twists", id)))

    suspend fun updateTwist(
        id: String,
        attributes: JsonObject,
    ): TwistConfigurationDto = read(send("PATCH", path("twists", id), body = attributes))

    suspend fun deleteTwist(id: String) {
        send("DELETE", path("twists", id))
    }

    suspend fun enableTwist(id: String): TwistConfigurationDto = read(send("POST", path("twists", id, "enable")))

    suspend fun disableTwist(id: String): TwistConfigurationDto = read(send("POST", path("twists", id, "disable")))

    suspend fun getTagFilter(): TagFilterDto = read(get(path("tag-filter")))

    suspend fun setTagFilter(tags: List<String>): TagFilterDto = read(send("PUT", path("tag-filter"), body = written(TagFilterDto(tags))))

    suspend fun clearTagFilter() {
        send("DELETE", path("tag-filter"))
    }

    suspend fun listTags(): List<String> = read(get(path("tags")))

    suspend fun removeTag(tag: String) {
        send("DELETE", path("tags", tag))
    }

    // How the server reaches real services

    suspend fun getUpstreamProxy(): OutboundProxyDto = read(get(path("upstream-proxy")))

    suspend fun updateUpstreamProxy(attributes: JsonObject): OutboundProxyDto =
        read(send("PATCH", path("upstream-proxy"), body = attributes))

    suspend fun clearUpstreamSession() {
        send("DELETE", path("system", "upstream-session"))
    }

    // The history

    /**
     * Returns the history entries that satisfy every one of [tokens], that the service [serviceId] handled,
     * and that were recorded after the entry [after], and of them the newest ones up to [max]. Without any
     * of these, it returns every entry that the history keeps. The newest entry comes first.
     */
    suspend fun listHistory(
        tokens: List<String> = emptyList(),
        serviceId: String? = null,
        after: Long? = null,
        max: Int? = null,
    ): List<HistoryEntryDto> {
        val asked = listOfNotNull(serviceId?.let { "serviceId" to it }, after?.let { "after" to "$it" }, max?.let { "max" to "$it" })
        return read(get(path("history"), tokens.map { "token" to it } + asked))
    }

    /** Deletes the history entries of the service [serviceId], and every entry when it names none. */
    suspend fun deleteHistory(serviceId: String? = null) {
        send("DELETE", path("history"), listOfNotNull(serviceId?.let { "serviceId" to it }))
    }

    suspend fun getHistoryEntry(id: Long): HistoryEntryDto = read(get(path("history", "$id")))

    suspend fun flagHistoryEntry(
        id: Long,
        flagged: Boolean,
    ): HistoryEntryDto = read(send("PATCH", path("history", "$id"), body = buildJsonObject { put("flagged", flagged) }))

    suspend fun deleteHistoryEntry(id: Long) {
        send("DELETE", path("history", "$id"))
    }

    /**
     * Lets the server handle the request of the entry [id] again, with what [edits] state in the place of
     * what the entry has, and returns the entry of the replay.
     */
    suspend fun replayHistoryEntry(
        id: Long,
        edits: ReplayRequestDto? = null,
    ): HistoryEntryDto = read(send("POST", path("history", "$id", "replay"), body = edits?.let(::written)))

    /** Creates a scenario with the response of the entry [id], with [attributes] in the place of what the entry gives, and returns it. */
    suspend fun saveHistoryEntryAsScenario(
        id: Long,
        attributes: JsonObject? = null,
    ): ScenarioDto = read(send("POST", path("history", "$id", "scenario"), body = attributes))

    /**
     * Returns the statistics of the history, for the entries from the time [from] on and up to the time
     * [to], each as ISO 8601 writes it.
     */
    suspend fun getStatistics(
        from: String? = null,
        to: String? = null,
    ): HistoryStatisticsDto = read(get(path("stats"), listOfNotNull(from?.let { "from" to it }, to?.let { "to" to it })))

    /** Returns the statistics of the history as CSV, see [getStatistics]. */
    suspend fun getStatisticsAsCsv(
        from: String? = null,
        to: String? = null,
    ): String = get(path("stats"), listOfNotNull(from?.let { "from" to it }, to?.let { "to" to it }) + ("format" to "csv")).body

    // The definitions as a whole

    /** Returns all definitions as one document, which is a JSON text. */
    suspend fun exportDefinitions(): String = get(path("definitions", "export")).body

    /**
     * Imports [document], and gives [tags] to what the import adds. The document is a definitions document
     * of a server, which is JSON, or an export document of Mockey 2.x, when it [isOfMockey2], which is XML.
     * It goes to the server as the bytes that it is.
     */
    suspend fun importDefinitions(
        document: ByteArray,
        isOfMockey2: Boolean,
        tags: List<String> = emptyList(),
    ): ImportReportDto {
        val query = tags.map { "tag" to it }
        return read(exchange("POST", path("definitions", "import"), query, document, if (isOfMockey2) XML else JSON))
    }

    suspend fun listDiagnostics(): List<DiagnosticDto> = read(get(path("diagnostics")))

    suspend fun search(term: String): List<SearchResultDto> = read(get(path("search"), listOf("term" to term)))

    // The server

    /**
     * Returns the status of the server. A server without an admin API of the version of this UI, and
     * something else that answers at the address, is no server that the UI can manage.
     */
    suspend fun getSystem(): SystemStatusDto =
        try {
            read(get(path("system")))
        } catch (_: ApiFailure.Refused) {
            throw ApiFailure.Incompatible("What answers at $url has no admin API of the version $VERSION.")
        }

    suspend fun setReadOnly(readOnly: Boolean): SystemStatusDto =
        read(send("PATCH", path("system"), body = buildJsonObject { put("readOnly", readOnly) }))

    suspend fun reloadDefinitions() {
        send("POST", path("system", "reload"))
    }

    suspend fun resetServer() {
        send("POST", path("system", "reset"))
    }

    suspend fun clearLastUsedTimes() {
        send("DELETE", path("system", "last-used-times"))
    }

    suspend fun listInspectors(): List<InspectorDto> = read(get(path("inspectors")))

    /** Ends the connections to the server. A request that is under way fails. */
    override fun close() {
        http.shutdownNow()
    }

    // Requests and answers

    /** The answer to a request that the server did: its [status] and its [body], which is a text in UTF-8. */
    private class Answer(
        val status: Int,
        val body: String,
    )

    /**
     * Returns the path of the resource with [segments] below the prefix of the API. Each segment is
     * written as a URL has it, so that an identifier with a slash or a space in it stays one segment.
     */
    private fun path(vararg segments: String): String = segments.joinToString("/", "${AdminApiDescription.PREFIX}/") { asSegment(it) }

    private suspend fun get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): Answer = exchange("GET", path, query, body = null, contentType = JSON)

    /** Sends a request with [body] as JSON, or without a body where there is none. */
    private suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
    ): Answer = exchange(method, path, query, body?.toString()?.toByteArray(Charsets.UTF_8), JSON)

    /**
     * Sends a request for [path] with the parameters [query] in its query string, and returns the answer of
     * a server that did what the request asks for. A request with a [body] declares it as [contentType].
     */
    private suspend fun exchange(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: ByteArray?,
        contentType: String,
    ): Answer {
        val asked = if (query.isEmpty()) "" else query.joinToString("&", "?") { (name, value) -> "${inQuery(name)}=${inQuery(value)}" }
        val request = HttpRequest.newBuilder(url.resolve(path + asked)).timeout(answerTimeout).header("Accept", JSON)
        token?.presentTo(request)
        if (body != null) request.header("Content-Type", contentType)
        request.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        val response =
            try {
                http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
            } catch (noAnswer: IOException) {
                throw ApiFailure.Unreachable(noAnswer)
            }
        return when (response.statusCode()) {
            in SUCCESSES -> Answer(response.statusCode(), response.body())
            UNAUTHORIZED -> throw ApiFailure.TokenRejected()
            else -> throw failureOf(response)
        }
    }

    /** Returns the failure that [response] reports, which is an answer with a problem document when it is one of the admin API. */
    private fun failureOf(response: HttpResponse<String>): ApiFailure {
        val status = response.statusCode()
        val problem =
            try {
                json.decodeFromString<ProblemDto>(response.body()).takeIf { it.status == status }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        val foreign = "What answers at $url answered with the status $status, and not as an admin API does."
        return when {
            problem == null -> ApiFailure.Incompatible(foreign)
            status >= SERVER_ERRORS -> ApiFailure.Failed(problem)
            else -> ApiFailure.Refused(problem)
        }
    }

    /** Returns what [answer] holds as the wire type [T]. */
    private inline fun <reified T> read(answer: Answer): T =
        try {
            json.decodeFromString<T>(answer.body)
        } catch (_: SerializationException) {
            throw unreadable(answer)
        } catch (_: IllegalArgumentException) {
            throw unreadable(answer)
        }

    private fun unreadable(answer: Answer): ApiFailure =
        ApiFailure.Incompatible("What answers at $url answered with the status ${answer.status} and with what no admin API answers with.")

    /** Returns [value] as the JSON object that the API reads it from. */
    private inline fun <reified T> written(value: T): JsonObject = json.encodeToJsonElement(serializer<T>(), value) as JsonObject

    /** Returns what [read] returns, or `null` when the API answers that what is asked for is not there. */
    private inline fun <T> orNoneDesignated(read: () -> T): T? =
        try {
            read()
        } catch (refused: ApiFailure.Refused) {
            if (refused.problem.status == NOT_FOUND) null else throw refused
        }

    internal companion object {
        /** The version of the admin API that the UI is a client of, as the prefix of the API names it. */
        val VERSION = AdminApiDescription.PREFIX.substringAfterLast('/')

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * The time within which the complete answer of the server has to arrive. A replay lasts as long as
         * the real service takes that the server forwards the request to, so the time is generous.
         */
        private val ANSWER_TIMEOUT: Duration = Duration.ofMinutes(2)

        private const val JSON = "application/json"
        private const val XML = "application/xml"
        private val SUCCESSES = 200..299
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val SERVER_ERRORS = 500

        /**
         * The characters that a segment of a path can hold as they are. Every other character is written as
         * the escapes of its bytes in UTF-8.
         */
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** Returns [text] as one segment of a path. */
        private fun asSegment(text: String): String =
            buildString {
                for (byte in text.toByteArray(Charsets.UTF_8)) {
                    val character = byte.toInt().toChar()
                    if (byte >= 0 && character in UNRESERVED) append(character) else append("%%%02X".format(byte))
                }
            }

        /** Returns [text] as a name or a value of a query string, as a form writes it. */
        private fun inQuery(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
    }
}
