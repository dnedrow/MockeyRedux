package io.github.dnedrow.mockey.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * The description of the admin API, which a server serves in OpenAPI format at [PATH] below [PREFIX], see
 * [OpenApiDocument]. It stands beside the wire types, so that a client of the API has it as well, and can
 * hold its requests against it.
 *
 * The description is written apart from the routes of the server. It says of every operation what a client
 * has to know and a route does not say: what the operation does, what its parameters mean, and which wire
 * types its bodies are. A test of the server compares the operations of the description with the routes of
 * the server, in both directions, so that neither has an operation that the other lacks.
 *
 * The schemas of the bodies are not written. They are made from the wire types, see [WireSchemas], so they
 * state what the API reads and writes.
 */
public object AdminApiDescription {
    /** The path prefix of the admin API. It states the version of the API. */
    public const val PREFIX: String = "/api/v1"

    /** The path of the description below the prefix of the API. */
    public const val PATH: String = "openapi.json"

    /** The schema of a parameter whose value is any text, and of one that a request can have several times, with a text each. */
    private val anyText = buildJsonObject { put("type", "string") }
    private val severalTexts =
        buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }

    /** The schema of a parameter whose value is a number of things, and of one whose value is the identifier of a history entry. */
    private val aNumberOfThings =
        buildJsonObject {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", Int.MAX_VALUE)
        }
    private val anEntryId =
        buildJsonObject {
            put("type", "integer")
            put("format", "int64")
            put("minimum", 0)
        }

    /** The schema of a parameter whose value is a time, as ISO 8601 writes one with an offset from UTC. */
    private val aTime =
        buildJsonObject {
            put("type", "string")
            put("format", "date-time")
        }

    /** The schema of the parameter that says in which format the statistics are. */
    private val aFormat =
        buildJsonObject {
            put("type", "string")
            putJsonArray("enum") {
                add("json")
                add("csv")
            }
            put("default", "json")
        }

    /** What the description says of the parameters that the paths of operations have, by their names. Every request has them. */
    internal val pathParameters: Map<String, DescribedParameter> =
        listOf(
            DescribedParameter("serviceId", "The identifier of a service.", anyText, isRequired = true),
            DescribedParameter("scenarioId", "The identifier of a scenario of the service.", anyText, isRequired = true),
            DescribedParameter("planId", "The identifier of a plan.", anyText, isRequired = true),
            DescribedParameter("twistId", "The identifier of a twist configuration.", anyText, isRequired = true),
            DescribedParameter("entryId", "The identifier of a history entry.", anEntryId, isRequired = true),
            DescribedParameter("tag", "A tag.", anyText, isRequired = true),
        ).associateBy { it.name }

    /** The areas of the API with the operations of each, in the order in which the description has them. */
    internal val areas: List<DescribedArea> =
        listOf(services(), scenarios(), plans(), routing(), upstream(), history(), definitions(), system())

    /** The description as a document of OpenAPI. It is the same for every server of this version, so it is made once. */
    public val document: JsonObject by lazy { OpenApiDocument.of(areas) }

    /** The description as a JSON text, which is what a server answers with. */
    public val text: String by lazy { Json.encodeToString(JsonObject.serializer(), document) }

    private fun services() =
        DescribedArea(
            "Services",
            "The services that the server answers for.",
            get("services", "listServices", "Lists the services with their scenarios.") {
                query("tag", "A tag. With tags, the list holds the services that carry one of them.", severalTexts)
                query("name", "A name. With a name, the list holds the services that have it, without regard to letter case.")
                gives<List<ServiceDto>>("The services.")
            },
            post("services", "createService", "Creates a service.") {
                details = "The request states the attributes of the service that differ from their defaults."
                takes<ServiceAttributesDto>()
                creates<ServiceDto>("The service that was created.")
            },
            get("services/{serviceId}", "getService", "Reads a service with its scenarios.") {
                gives<ServiceDto>("The service.")
            },
            patch("services/{serviceId}", "updateService", "Updates a service.") {
                details =
                    "The request states the attributes that change, and the others stay as they are. " +
                    "The attributes defaultScenarioId and errorScenarioId designate scenarios of the service."
                takes<ServiceAttributesDto>()
                gives<ServiceDto>("The service as it is now.")
            },
            delete("services/{serviceId}", "deleteService", "Deletes a service with its scenarios.") {
                givesNothing()
            },
            post("services/{serviceId}/duplicate", "duplicateService", "Creates a copy of a service.") {
                creates<ServiceDto>("The copy.")
            },
            post("services/{serviceId}/merge", "mergeServices", "Merges the services that the request names into the service.") {
                takes<ServiceMergeRequestDto>()
                gives<MergeReportDto>("What the merge did.")
            },
            get("services/{serviceId}/schema-report", "getSchemaReport", "Checks the scenarios of a service against its response schema.") {
                details =
                    "The report states which scenarios conform to the schema. " +
                    "A service without a response schema has no report, and the request gets the status 404."
                gives<SchemaReportDto>("The report.")
            },
            post("services/real-urls/derive", "deriveRealUrls", "Derives real URLs for all services.") {
                details =
                    "Each real URL gives one URL for each replacement of the request: " +
                    "the URL in which what the regular expression finds is replaced."
                takes<RealUrlDerivationRequestDto>()
                gives<List<DerivedRealUrlDto>>("The real URLs that were added.")
            },
        )

    private fun scenarios() =
        DescribedArea(
            "Scenarios",
            "The scenarios of a service, which are the answers that it can give, and the universal error scenario.",
            get("services/{serviceId}/scenarios", "listScenarios", "Lists the scenarios of a service.") {
                gives<List<ScenarioDto>>("The scenarios.")
            },
            post("services/{serviceId}/scenarios", "createScenario", "Creates a scenario of a service.") {
                details = "The request states the attributes of the scenario that differ from their defaults."
                takes<ScenarioAttributesDto>()
                creates<ScenarioDto>("The scenario that was created.")
            },
            get("services/{serviceId}/scenarios/{scenarioId}", "getScenario", "Reads a scenario.") {
                gives<ScenarioDto>("The scenario.")
            },
            patch("services/{serviceId}/scenarios/{scenarioId}", "updateScenario", "Updates a scenario.") {
                details = "The request states the attributes that change, and the others stay as they are."
                takes<ScenarioAttributesDto>()
                gives<ScenarioDto>("The scenario as it is now.")
            },
            delete("services/{serviceId}/scenarios/{scenarioId}", "deleteScenario", "Deletes a scenario.") {
                givesNothing()
            },
            get("universal-error-scenario", "getUniversalErrorScenario", "States which scenario is the universal error scenario.") {
                details = "While no scenario is designated, the request gets the status 404."
                problem(Status.NOT_FOUND)
                gives<ScenarioReferenceDto>("The scenario that is designated.")
            },
            put("universal-error-scenario", "setUniversalErrorScenario", "Designates a scenario as the universal error scenario.") {
                takes<ScenarioReferenceDto>()
                gives<ScenarioReferenceDto>("The scenario that is designated now.")
            },
            delete("universal-error-scenario", "clearUniversalErrorScenario", "Ends the designation of the universal error scenario.") {
                givesNothing()
            },
        )

    private fun plans() =
        DescribedArea(
            "Plans",
            "The plans, each of which holds settings of services and gives them back to the services when it is applied.",
            get("plans", "listPlans", "Lists the plans.") {
                gives<List<PlanDto>>("The plans.")
            },
            post("plans", "savePlan", "Saves the settings that services have now as a plan.") {
                details =
                    "The plan is the first one with the name of the request. " +
                    "It is a new plan when no plan has that name, and when the request states no name."
                takes<PlanSaveRequestDto>()
                gives<PlanDto>("The plan that was there, with its new items.")
                creates<PlanDto>("The plan that was created.")
            },
            get("plans/{planId}", "getPlan", "Reads a plan.") {
                gives<PlanDto>("The plan.")
            },
            patch("plans/{planId}", "updatePlan", "Updates a plan.") {
                details =
                    "The request states the attributes that change, and the others stay as they are. " +
                    "A list of items takes the place of all items of the plan."
                takes<PlanAttributesDto>()
                gives<PlanDto>("The plan as it is now.")
            },
            delete("plans/{planId}", "deletePlan", "Deletes a plan.") {
                givesNothing()
            },
            post("plans/{planId}/save", "saveIntoPlan", "Saves the settings that services have now as the items of a plan.") {
                takes<PlanSaveRequestDto>()
                gives<PlanDto>("The plan with its new items.")
            },
            post("plans/{planId}/apply", "applyPlan", "Applies a plan.") {
                details =
                    "Every service that an item of the plan names gets the response mode, the delay, " +
                    "and the default scenario of that item."
                gives<PlanApplyReportDto>("The plan, its items that found no service, and the scenario names that named no scenario.")
            },
            get("default-plan", "getDefaultPlan", "States which plan is the default plan.") {
                details = "While no plan is designated, the request gets the status 404."
                problem(Status.NOT_FOUND)
                gives<PlanReferenceDto>("The plan that is designated.")
            },
            put("default-plan", "setDefaultPlan", "Designates a plan as the default plan.") {
                details = "A server applies its default plan when it loads its definitions."
                takes<PlanReferenceDto>()
                gives<PlanReferenceDto>("The plan that is designated now.")
            },
            delete("default-plan", "clearDefaultPlan", "Ends the designation of the default plan.") {
                givesNothing()
            },
        )

    private fun routing() =
        DescribedArea(
            "Routing",
            "What decides which service a request goes to: the twist configurations, the tag filter, and the tags.",
            get("twists", "listTwists", "Lists the twist configurations.") {
                gives<List<TwistConfigurationDto>>("The twist configurations.")
            },
            post("twists", "createTwist", "Creates a twist configuration.") {
                takes<TwistAttributesDto>()
                creates<TwistConfigurationDto>("The twist configuration that was created.")
            },
            get("twists/{twistId}", "getTwist", "Reads a twist configuration.") {
                gives<TwistConfigurationDto>("The twist configuration.")
            },
            patch("twists/{twistId}", "updateTwist", "Updates a twist configuration.") {
                details =
                    "The request states the attributes that change, and the others stay as they are. " +
                    "A list of pairs takes the place of all pairs of the configuration."
                takes<TwistAttributesDto>()
                gives<TwistConfigurationDto>("The twist configuration as it is now.")
            },
            delete("twists/{twistId}", "deleteTwist", "Deletes a twist configuration.") {
                givesNothing()
            },
            post("twists/{twistId}/enable", "enableTwist", "Enables a twist configuration, in the place of the one that was enabled.") {
                gives<TwistConfigurationDto>("The twist configuration.")
            },
            post("twists/{twistId}/disable", "disableTwist", "Disables a twist configuration.") {
                details = "A configuration that is not enabled stays as it is, and so does the one that is."
                gives<TwistConfigurationDto>("The twist configuration.")
            },
            get("tag-filter", "getTagFilter", "Reads the tag filter.") {
                gives<TagFilterDto>("The tags of the filter. A filter without tags lets a request go to any service.")
            },
            put("tag-filter", "setTagFilter", "Sets the tag filter.") {
                takes<TagFilterDto>()
                gives<TagFilterDto>("The tags of the filter as they are now.")
            },
            delete("tag-filter", "clearTagFilter", "Clears the tag filter.") {
                givesNothing()
            },
            get("tags", "listTags", "Lists the tags that services, scenarios, and plans carry.") {
                gives<List<String>>("The tags.")
            },
            delete("tags/{tag}", "removeTag", "Removes a tag from every service, scenario, and plan.") {
                givesNothing()
            },
        )

    private fun upstream() =
        DescribedArea(
            "Upstream",
            "How the server reaches real services: the outbound proxy and the upstream session.",
            get("upstream-proxy", "getUpstreamProxy", "Reads the settings of the outbound proxy.") {
                details = "No answer holds the password of the proxy. An answer says whether there is one."
                gives<OutboundProxyDto>("The settings.")
            },
            patch("upstream-proxy", "updateUpstreamProxy", "Sets the outbound proxy.") {
                details =
                    "The request states the attributes that change, and the others stay as they are: " +
                    "the URL of the proxy, whether it is enabled, and its credentials."
                takes<OutboundProxyAttributesDto>()
                gives<OutboundProxyDto>("The settings as they are now.")
            },
            delete("system/upstream-session", "clearUpstreamSession", "Empties the upstream session.") {
                details = "The session holds the cookies that real services set, which the server sends with the requests that it forwards."
                givesNothing()
            },
        )

    private fun history() =
        DescribedArea(
            "History",
            "The exchanges at the mock endpoint that the server keeps.",
            get("history", "listHistory", "Lists the history entries, the newest one first.") {
                val token =
                    "A filter token: a text that an entry holds, or after `!` a text that an entry does not hold. " +
                        "The list holds the entries that satisfy every token."
                query("token", token, severalTexts)
                query("serviceId", "The identifier of a service. The list holds the entries of that service.")
                query("after", "The identifier of a history entry. The list holds the entries that were recorded after it.", anEntryId)
                query("max", "The greatest number of entries in the list, which holds the newest ones.", aNumberOfThings)
                gives<List<HistoryEntryDto>>("The entries.")
            },
            delete("history", "deleteHistory", "Deletes all history entries, or the ones of one service.") {
                query("serviceId", "The identifier of a service. Only the entries of that service are deleted.")
                givesNothing()
            },
            get("history/{entryId}", "getHistoryEntry", "Reads a history entry.") {
                gives<HistoryEntryDto>("The entry.")
            },
            patch("history/{entryId}", "updateHistoryEntry", "Sets and removes the mark of a history entry.") {
                takes<HistoryEntryAttributesDto>()
                gives<HistoryEntryDto>("The entry as it is now.")
            },
            delete("history/{entryId}", "deleteHistoryEntry", "Deletes a history entry.") {
                givesNothing()
            },
            post("history/{entryId}/replay", "replayHistoryEntry", "Lets the server handle the request of a history entry again.") {
                details =
                    "The headers, the parameters, and the body that the request states take the place of those of the entry. " +
                    "A request without a body replays the entry as it is."
                takes<ReplayRequestDto>(required = false)
                creates<HistoryEntryDto>("The history entry of the replay.")
            },
            post("history/{entryId}/scenario", "saveHistoryEntryAsScenario", "Creates a scenario with the response of a history entry.") {
                details =
                    "The scenario belongs to the service that handled the request of the entry. " +
                    "The attributes that the request states take the place of what the entry gives, and the request needs no body."
                takes<ScenarioAttributesDto>(required = false)
                creates<ScenarioDto>("The scenario that was created.")
            },
            get("stats", "getStatistics", "States for every service how many entries the history keeps of it.") {
                query("from", "A time. Only the entries from that time on count.", aTime)
                query("to", "A time. Only the entries up to that time count.", aTime)
                query("format", "The format of the answer.", aFormat)
                gives<HistoryStatisticsDto>("The statistics.", DescribedContent(DescribedContent.CSV))
            },
        )

    private fun definitions() =
        DescribedArea(
            "Definitions",
            "The definitions as a whole: their export, the import, the diagnostics, and the search.",
            get("definitions/export", "exportDefinitions", "Gives all definitions as one document.") {
                details = "A server takes the document as its initial definitions, and the import takes it."
                givesDocument("The definitions document.", DescribedContent(DescribedContent.JSON))
            },
            post("definitions/import", "importDefinitions", "Imports a definitions document, or an export document of Mockey 2.x.") {
                details =
                    "The request says which document its body is: a definitions document is JSON, " +
                    "and an export document of Mockey 2.x is XML."
                val tag = "A tag for every service, scenario, and plan that the import adds, and for every service that it merges into."
                query("tag", tag, severalTexts)
                takesDocument(DescribedContent(DescribedContent.JSON), DescribedContent(DescribedContent.XML))
                gives<ImportReportDto>("The report of the import.")
            },
            get("diagnostics", "listDiagnostics", "States what deserves attention in the definitions and blocks nothing.") {
                gives<List<DiagnosticDto>>("The diagnostics.")
            },
            get("search", "search", "Finds the services, scenarios, and plans that hold a text, without regard to letter case.") {
                query("term", "The text to search for.", required = true)
                gives<List<SearchResultDto>>("What was found.")
            },
        )

    private fun system() =
        DescribedArea(
            "System",
            "The server itself.",
            get("system", "getSystem", "States the status of the server.") {
                gives<SystemStatusDto>("The status.")
            },
            patch("system", "updateSystem", "Switches the read-only mode on and off.") {
                details = "Switching the mode off stores the definitions as they are now."
                takes<SystemAttributesDto>()
                gives<SystemStatusDto>("The status as it is now.")
            },
            post("system/reload", "reloadDefinitions", "Reads the definitions location again.") {
                details = "The definitions of the definitions location take the place of those in memory."
                givesNothing()
            },
            post("system/reset", "resetServer", "Removes all definitions, the tag filter, the history, and the last-used times.") {
                givesNothing()
            },
            delete("system/last-used-times", "clearLastUsedTimes", "Clears the last-used times of services, scenarios, and plans.") {
                givesNothing()
            },
            get("inspectors", "listInspectors", "Lists the installed inspectors.") {
                gives<List<InspectorDto>>("The inspectors.")
            },
            get(PATH, "getDescription", "Gives this description of the API.") {
                givesDocument("The description, in OpenAPI format.", DescribedContent(DescribedContent.JSON))
            },
        )

    private fun get(
        path: String,
        id: String,
        summary: String,
        describe: OperationDraft.() -> Unit,
    ) = OperationDraft("GET", path, id, summary).apply(describe).described()

    private fun post(
        path: String,
        id: String,
        summary: String,
        describe: OperationDraft.() -> Unit,
    ) = OperationDraft("POST", path, id, summary).apply(describe).described()

    private fun put(
        path: String,
        id: String,
        summary: String,
        describe: OperationDraft.() -> Unit,
    ) = OperationDraft("PUT", path, id, summary).apply(describe).described()

    private fun patch(
        path: String,
        id: String,
        summary: String,
        describe: OperationDraft.() -> Unit,
    ) = OperationDraft("PATCH", path, id, summary).apply(describe).described()

    private fun delete(
        path: String,
        id: String,
        summary: String,
        describe: OperationDraft.() -> Unit,
    ) = OperationDraft("DELETE", path, id, summary).apply(describe).described()

    /** What is written of an operation, until it is [described]. */
    internal class OperationDraft(
        private val method: String,
        private val path: String,
        private val id: String,
        private val summary: String,
    ) {
        private val query = mutableListOf<DescribedParameter>()
        private var body: DescribedBody? = null
        private val answers = mutableListOf<DescribedAnswer>()
        private val problems = mutableSetOf<Int>()

        /** What a sentence does not say of the operation. */
        var details: String? = null

        /**
         * The operation takes the parameter [name] in the query string. A value of it is as [schema] says,
         * which is any text by default.
         */
        fun query(
            name: String,
            description: String,
            schema: JsonObject = anyText,
            required: Boolean = false,
        ) {
            query += DescribedParameter(name, description, schema, required)
        }

        /** The operation takes a body, which is JSON of the wire type [T]. */
        inline fun <reified T> takes(required: Boolean = true) = takes(serializer<T>(), required)

        /** The operation takes a body, which is JSON of the wire type that [wireType] serializes. */
        fun takes(
            wireType: KSerializer<*>,
            required: Boolean,
        ) {
            body = DescribedBody(listOf(DescribedContent(DescribedContent.JSON, wireType)), required)
        }

        /** The operation takes a body, which is a document of one of [content]. */
        fun takesDocument(vararg content: DescribedContent) {
            body = DescribedBody(content.toList(), isRequired = true)
        }

        /** The operation answers with the status 200 and with JSON of the wire type [T], or with one of [others]. */
        inline fun <reified T> gives(
            description: String,
            vararg others: DescribedContent,
        ) = gives(Status.OK, description, serializer<T>(), others.toList())

        /** The operation answers with the status 201, with JSON of the wire type [T], and with where the new resource is. */
        inline fun <reified T> creates(description: String) = gives(Status.CREATED, description, serializer<T>(), emptyList())

        /**
         * The operation answers with [status] and with JSON of the wire type that [wireType] serializes, or
         * with one of [others]. An answer with the status 201 says where the new resource is.
         */
        fun gives(
            status: Int,
            description: String,
            wireType: KSerializer<*>,
            others: List<DescribedContent>,
        ) {
            val content = listOf(DescribedContent(DescribedContent.JSON, wireType)) + others
            answers += DescribedAnswer(status, description, content, isLocated = status == Status.CREATED)
        }

        /** The operation answers with the status 200 and with a document of one of [content]. */
        fun givesDocument(
            description: String,
            vararg content: DescribedContent,
        ) {
            answers += DescribedAnswer(Status.OK, description, content.toList())
        }

        /** The operation answers with the status 204, without a body. */
        fun givesNothing() {
            answers += DescribedAnswer(Status.NO_CONTENT, "The operation was done.")
        }

        /** The operation reports a failure with [status], which does not follow from the form of the operation. */
        fun problem(status: Int) {
            problems += status
        }

        /** Returns the operation as it was written. */
        fun described(): DescribedOperation {
            val inTheirOrder = answers.sortedBy { it.status }
            return DescribedOperation(method, path, id, summary, details, query.toList(), body, inTheirOrder, problems.toSet())
        }
    }
}

/** An area of the admin API in its description: its [name], what it is about, and its [operations]. */
internal class DescribedArea(
    val name: String,
    val description: String,
    vararg operations: DescribedOperation,
) {
    val operations: List<DescribedOperation> = operations.toList()
}

/**
 * What the description of the admin API says of one operation: the one with [method] for [path], which is
 * the path below the prefix of the API as the route of the operation has it.
 *
 * [id] names the operation for a program that reads the description. [summary] says in a sentence what the
 * operation does, and [details] what the sentence leaves out.
 *
 * [query] are the parameters that the operation takes in the query string of a request. [body] is the body
 * of a request, and `null` for an operation that takes none. [answers] are the answers of the operation
 * when it does what the request asks for, each with another status.
 *
 * [problems] are the statuses with which the operation reports a failure, besides the ones that follow
 * from its form, see [OpenApiDocument].
 */
internal class DescribedOperation(
    val method: String,
    val path: String,
    val id: String,
    val summary: String,
    val details: String?,
    val query: List<DescribedParameter>,
    val body: DescribedBody?,
    val answers: List<DescribedAnswer>,
    val problems: Set<Int>,
) {
    /** The names of the parameters of the path, in their order. */
    val pathParameters: List<String> = path.split('/').filter { it.startsWith("{") }.map { it.removeSurrounding("{", "}") }

    /** Returns the method and the path, as a route names its operation. */
    override fun toString(): String = "$method $path"
}

/**
 * A parameter of a query string: its [name], what it means, and what a value of it is, in [schema]. A
 * parameter with the schema of a list can be there several times. A request has to have a parameter that
 * [isRequired].
 */
internal class DescribedParameter(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val isRequired: Boolean,
)

/** The body of a request: what it can be, in [content], and whether a request has to have it. */
internal class DescribedBody(
    val content: List<DescribedContent>,
    val isRequired: Boolean,
)

/**
 * An answer of an operation that did what the request asked for: its [status], what it holds, and its body,
 * which is one of [content]. An answer without content has no body. An answer that [isLocated] names the
 * resource that the operation created in its `Location` header.
 */
internal class DescribedAnswer(
    val status: Int,
    val description: String,
    val content: List<DescribedContent> = emptyList(),
    val isLocated: Boolean = false,
)

/**
 * A body of the media type [mediaType]. It is a value of the wire type that [wireType] serializes. Where
 * there is none, the body is a document that no wire type describes: any JSON object, or a text.
 */
internal class DescribedContent(
    val mediaType: String,
    val wireType: KSerializer<*>? = null,
) {
    internal companion object {
        const val JSON = "application/json"
        const val XML = "application/xml"
        const val CSV = "text/csv"
    }
}

/** The statuses of the answers that the description names. */
internal object Status {
    const val OK = 200
    const val CREATED = 201
    const val NO_CONTENT = 204
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val PAYLOAD_TOO_LARGE = 413
    const val UNSUPPORTED_MEDIA_TYPE = 415
    const val UNPROCESSABLE = 422
    const val SERVER_ERROR = 500
}
