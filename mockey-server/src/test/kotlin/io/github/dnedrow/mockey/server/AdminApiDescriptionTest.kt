package io.github.dnedrow.mockey.server

import dev.harrel.jsonschema.ValidatorFactory
import dev.harrel.jsonschema.providers.KotlinxJsonNode
import io.github.dnedrow.mockey.api.AdminApiDescription
import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task 12.9: the admin API describes itself. It serves a document in OpenAPI format, and that document has
 * the operations that the API has, with what each of them takes and answers.
 *
 * The tests ask a whole server over real HTTP. [everyOperationAnswersAsTheDocumentSays] sends a request to
 * every operation of the document, and holds each request and each answer against what the document says
 * of them, see [Described].
 */
class AdminApiDescriptionTest {
    private val servers = mutableListOf<MockeyServer>()
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun stopEverything() {
        servers.forEach(MockeyServer::close)
        directories.forEach { it.toFile().deleteRecursively() }
    }

    /** Settings with which a server of a test listens on the loopback address, on ports that the system picks, and logs little. */
    private fun settings(): ServerSettings {
        val definitions = Files.createTempDirectory("mockey-admin-api-description-test").also { directories.add(it) }
        return ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0, definitions = definitions, logLevel = LogLevel.WARN)
    }

    private fun start(settings: ServerSettings = settings()): MockeyServer = MockeyServer.start(settings).also { servers += it }

    /** Sends a request to the admin API of this server and returns the answer. */
    private fun MockeyServer.asked(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        body: String = "",
    ): RawHttp.Answer = RawHttp.exchange(listeners.admin.address, RawHttp.request(method, "/api/v1/$target", headers, RawHttp.utf8(body)))

    /** Returns the document that [server] serves as the description of its admin API. */
    private fun documentOf(server: MockeyServer): JsonObject = json(server.asked("GET", "openapi.json").bodyInUtf8).jsonObject

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /**
     * An operation as a document describes it: its [method], its [path] below the prefix of the API, and
     * what the document [says] of it.
     */
    private class Operation(
        val method: String,
        val path: String,
        val says: JsonObject,
    ) {
        val id: String = text(says, "operationId")

        /** The areas of the API that the operation belongs to. */
        val areas: List<String> = says.getValue("tags").jsonArray.map(::text)

        /** The answers of the operation, by their statuses. */
        val responses: JsonObject = says.getValue("responses").jsonObject

        /** The statuses of the answers to a request that was done. */
        val done: List<Int> = responses.keys.filter { it.startsWith("2") }.map(String::toInt)

        /** The names of the parameters that the operation has in [place], which is `path` or `query`, in their order. */
        fun parameters(place: String): List<String> = parametersIn(place).map { text(it, "name") }

        /** What the document says of the parameters that the operation has in [place]. */
        fun parametersIn(place: String): List<JsonObject> {
            val all = says["parameters"]?.jsonArray.orEmpty()
            return all.map { it.jsonObject }.filter { text(it, "in") == place }
        }

        override fun toString(): String = "$method $path"
    }

    /** Returns the operations of [document], which is every member of every path of it. */
    private fun operationsOf(document: JsonObject): List<Operation> =
        document.getValue("paths").jsonObject.flatMap { (path, ofPath) ->
            ofPath.jsonObject.map { (method, says) -> Operation(method.uppercase(), path, says.jsonObject) }
        }

    /** What makes an operation the one that it is: its method, its path, the parameters of its path, and those of its query string. */
    private data class Form(
        val method: String,
        val path: String,
        val pathParameters: List<String>,
        val query: List<String>,
    )

    // The document and the routes

    /**
     * The scenario "Description matches the API", by the routes of the API: every operation that the
     * document describes is an operation of the API, and every operation of the API is described, with the
     * same parameters in its path and in its query string.
     */
    @Test
    fun theDocumentDescribesEveryOperationOfTheApiAndNoOther() {
        val settings = settings()
        val server = start(settings)
        val api = AdminApi.of(server.engine, settings, Instant.now(), server.listeners.mock.address)

        val operations = operationsOf(documentOf(server))
        val described = operations.map { Form(it.method, it.path, it.parameters("path"), it.parameters("query").sorted()) }
        val registered =
            api.routes.map { route ->
                val parameters = PARAMETER.findAll(route.path).map { it.groupValues[1] }.toList()
                Form(route.method.value, "/${route.path}", parameters, route.query.sorted())
            }

        assertEquals(emptyList(), described - registered.toSet(), "What the document describes and the API does not have")
        assertEquals(emptyList(), registered - described.toSet(), "What the API has and the document does not describe")
        assertEquals(registered.size, described.size, "No operation is there twice.")
        assertContains(described, Form("GET", "/services", emptyList(), listOf("name", "tag")))
        val ofAScenario = "/services/{serviceId}/scenarios/{scenarioId}"
        assertContains(described, Form("DELETE", ofAScenario, listOf("serviceId", "scenarioId"), emptyList()))
    }

    /**
     * The document is an answer of the API like any other: JSON that is not to be stored, and no body for a
     * request with the method HEAD. It is the description that stands beside the wire types, which the
     * clients of the API have as well.
     */
    @Test
    fun theApiServesTheDocumentAsJson() {
        val server = start()

        val answer = server.asked("GET", "openapi.json")
        val ofHead = server.asked("HEAD", "openapi.json")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals(listOf("application/json"), answer.headerValues("Content-Type"))
        assertEquals(listOf("no-store"), answer.headerValues("Cache-Control"))
        assertEquals(AdminApiDescription.text, answer.bodyInUtf8)
        val document = json(answer.bodyInUtf8).jsonObject
        assertEquals("3.1.0", text(document, "openapi"))
        assertEquals("Mockey admin API" to "1", document.getValue("info").jsonObject.let { text(it, "title") to text(it, "version") })
        assertEquals(listOf("/api/v1"), document.getValue("servers").jsonArray.map { text(it.jsonObject, "url") })
        assertEquals("HTTP/1.1 200 OK" to "", ofHead.statusLine to ofHead.body)
        assertEquals(answer.headerValues("Content-Length"), ofHead.headerValues("Content-Length"))
    }

    /**
     * The document holds together: what it refers to is there, what it defines is used, and every operation
     * says what a program that reads the document needs: a name of its own, an area that the document has,
     * the parameters of its path, and an answer for a request that was done.
     */
    @Test
    fun theDocumentHoldsTogether() {
        val document = documentOf(start())
        val operations = operationsOf(document)
        val components = document.getValue("components").jsonObject
        val references = referencesIn(document)
        val defined = listOf("schemas", "responses").flatMap { kind -> namesOf(components, kind).map { "#/components/$kind/$it" } }
        val areas = document.getValue("tags").jsonArray.map { text(it.jsonObject, "name") }

        assertEquals(emptySet(), references - defined.toSet(), "What the document refers to and does not define")
        assertEquals(emptySet(), defined.toSet() - references, "What the document defines and does not use")
        val ofSeveral = operations.groupBy { it.id }.filterValues { it.size > 1 }
        assertEquals(emptySet(), ofSeveral.keys, "Names of several operations")
        assertEquals(emptyList(), operations.filterNot { NAME.matches(it.id) }.map { "$it: ${it.id}" }, "Names that are no names")
        for (operation in operations) {
            assertEquals(1, operation.areas.count { it in areas }, "The area of $operation")
            assertTrue(text(operation.says, "summary").endsWith("."), "What $operation does, in a sentence")
            val inThePath = PARAMETER.findAll(operation.path).map { it.groupValues[1] }.toList()
            assertEquals(inThePath, operation.parameters("path"), "The parameters of the path of $operation")
            assertEquals(emptyList(), operation.parametersIn("path").filter { it["required"] != JsonPrimitive(true) }, "$operation")
            assertTrue(operation.responses.keys.any { it.startsWith("2") }, "An answer of $operation to a request that was done")
            for ((status, answer) in operation.responses) {
                val says = resolved(document, answer.jsonObject)
                assertTrue(text(says, "description").isNotBlank(), "What the answer $status of $operation is")
            }
        }
        val presented = document.getValue("security").jsonArray.flatMap { it.jsonObject.keys }
        assertEquals(presented, namesOf(components, "securitySchemes").toList())
    }

    // The document and the answers

    /**
     * The scenario "Description matches the API", by the answers of the API: a request to each operation
     * of the document is answered by that operation, with a status and a body that the document has for it.
     * So an operation that the document describes is one that the API takes, and the wire types that the
     * document names for an operation are the ones that the operation reads and writes.
     */
    @Test
    fun everyOperationAnswersAsTheDocumentSays() {
        val server = start()
        val api = Described(server)
        val mock = server.listeners.mock.address

        api.asked("getDescription")
        api.asked("getSystem")
        api.asked("listInspectors")

        // Services and scenarios
        val ofCustomers =
            buildJsonObject {
                put("name", "Customers")
                put("mockUrl", "customer/{id}")
                put("responseMode", "static")
                put("responseSchema", """{"type":"object","required":["name"]}""")
                putJsonArray("tags") { add("team-a") }
            }
        val customers = api.asked("createService", body = "$ofCustomers").decoded<ServiceDto>()
        val ofOrders = """{"name":"Orders","mockUrl":"orders","realUrls":["http://qa1.example.com/orders"]}"""
        val orders = api.asked("createService", body = ofOrders).decoded<ServiceDto>()
        api.asked("createService", body = """{"name":"Others","mockUrl":"orders"}""", status = 422)
        api.asked("createService", body = "name=Others", contentType = "text/plain", status = 415)
        api.asked("listServices", query = "name=customers&tag=team-a&tag=team-b")
        api.asked("listServices", query = "nothing=1", status = 400)
        api.asked("getService", customers.id)
        api.asked("getService", "no-such-service", status = 404)
        api.asked("updateService", customers.id, body = """{"description":"The customers of the shop","delayMillis":5}""")
        val ofFound =
            buildJsonObject {
                put("name", "found")
                put("responseBody", """{"name":"Ada"}""")
                put("matchCriterion", json("""{"type":"text","text":"Ada"}"""))
            }
        val found = api.asked("createScenario", customers.id, body = "$ofFound").decoded<ScenarioDto>()
        val missing = api.asked("createScenario", customers.id, body = """{"name":"missing","statusCode":404}""").decoded<ScenarioDto>()
        api.asked("listScenarios", customers.id)
        api.asked("getScenario", customers.id, found.id)
        val ofTheUpdate = """{"headers":[{"name":"Content-Type","value":"application/json"}],"methodRestriction":null}"""
        api.asked("updateScenario", customers.id, found.id, body = ofTheUpdate)
        api.asked("getUniversalErrorScenario", status = 404)
        api.asked("setUniversalErrorScenario", body = """{"serviceId":"${customers.id}","scenarioId":"${missing.id}"}""")
        api.asked("getUniversalErrorScenario")
        api.asked("getSchemaReport", customers.id)
        api.asked("getSchemaReport", orders.id, status = 404)
        val copy = api.asked("duplicateService", customers.id).decoded<ServiceDto>()
        api.asked("mergeServices", customers.id, body = """{"sourceIds":["${copy.id}"]}""")
        api.asked("deriveRealUrls", body = """{"expression":"qa1","replacements":["qa2"]}""")

        // Plans
        val saved = """{"serviceIds":["${customers.id}","${orders.id}"],"name":"Night"}"""
        val plan = api.asked("savePlan", body = saved, status = 201).decoded<PlanDto>()
        api.asked("savePlan", body = saved, status = 200)
        api.asked("listPlans")
        api.asked("getPlan", plan.id)
        api.asked("updatePlan", plan.id, body = """{"description":"What the night shift needs","tags":["nightly"]}""")
        api.asked("saveIntoPlan", plan.id, body = """{"serviceIds":["${customers.id}"]}""")
        api.asked("applyPlan", plan.id)
        api.asked("getDefaultPlan", status = 404)
        api.asked("setDefaultPlan", body = """{"planId":"${plan.id}"}""")
        api.asked("getDefaultPlan")

        // Routing
        val pairs = """[{"origination":"kunde","destination":"customer"}]"""
        val twist = api.asked("createTwist", body = """{"name":"German paths","pairs":$pairs}""").decoded<TwistConfigurationDto>()
        api.asked("listTwists")
        api.asked("getTwist", twist.id)
        api.asked("updateTwist", twist.id, body = """{"name":"Paths in German"}""")
        api.asked("enableTwist", twist.id)
        api.asked("getSystem")
        api.asked("disableTwist", twist.id)
        api.asked("setTagFilter", body = """{"tags":["team-a"]}""")
        api.asked("getTagFilter")
        api.asked("clearTagFilter")
        api.asked("listTags")

        // Real services
        api.asked("getUpstreamProxy")
        api.asked("updateUpstreamProxy", body = """{"url":"http://proxy.example.com:3128","username":"mockey","password":"secret"}""")
        api.asked("clearUpstreamSession")

        // The history
        val targets = listOf("customer/7", "customer/8", "nothing")
        assertEquals(listOf<Int?>(200, 200, 404), targets.map { RawHttp.get(mock, "/service/$it").status })
        val entries = api.asked("listHistory", query = "max=10").decoded<List<HistoryEntryDto>>()
        val (ofNothing, ofAnother, ofCustomer) = entries.map { "${it.id}" }
        api.asked("listHistory", query = "token=customer&token=7&serviceId=${customers.id}&after=0")
        api.asked("getHistoryEntry", ofNothing)
        api.asked("getHistoryEntry", "0", status = 404)
        api.asked("updateHistoryEntry", ofCustomer, body = """{"flagged":true}""")
        api.asked("replayHistoryEntry", ofCustomer)
        api.asked("replayHistoryEntry", ofCustomer, body = """{"headers":[{"name":"Accept","value":"*/*"}],"bodyBase64":"e30="}""")
        api.asked("saveHistoryEntryAsScenario", ofCustomer, body = """{"name":"from the history"}""")
        api.asked("saveHistoryEntryAsScenario", ofNothing, status = 422)
        api.asked("getStatistics", query = "from=2000-01-01T00:00:00Z")
        api.asked("getStatistics", query = "format=csv")
        api.asked("deleteHistoryEntry", ofAnother)
        api.asked("deleteHistory", query = "serviceId=${orders.id}")

        // The definitions as a whole
        val exported = api.asked("exportDefinitions").bodyInUtf8
        api.asked("importDefinitions", query = "tag=imported", body = exported)
        api.asked("importDefinitions", body = LegacySources.export(), contentType = "application/xml")
        api.asked("importDefinitions", body = """{"formatVersion":99}""", status = 422)
        api.asked("listDiagnostics")
        api.asked("search", query = "term=customer")

        // What takes something away
        api.asked("removeTag", "team-a")
        api.asked("deleteScenario", customers.id, found.id)
        api.asked("clearUniversalErrorScenario")
        api.asked("clearDefaultPlan")
        api.asked("deletePlan", plan.id)
        api.asked("deleteTwist", twist.id)
        api.asked("deleteService", orders.id)
        api.asked("deleteHistory")
        api.asked("updateSystem", body = """{"readOnly":false}""")
        api.asked("clearLastUsedTimes")
        api.asked("reloadDefinitions")
        api.asked("resetServer")

        assertEquals(emptySet(), api.operations.keys - api.answered, "The operations of the document that the test did not ask")
        assertEquals(emptyList(), server.engine.definitions.services)
    }

    /**
     * The description of a server, and what that server answers to requests for the operations of it. A
     * request names its operation as the document names it. It has the method and the path that the
     * document has for the operation, and its body is as the document says, see [Schemas]. The answer has to
     * have a status that the document has for the operation, and with it the media type and the body that
     * the document says.
     */
    private inner class Described(
        private val server: MockeyServer,
    ) {
        private val document = documentOf(server)
        private val schemas = Schemas(document)

        /** The operations of the document, by their names. */
        val operations: Map<String, Operation> = operationsOf(document).associateBy { it.id }

        /** The names of the operations that answered a request as the document says, with a status that says that the request was done. */
        val answered = mutableSetOf<String>()

        /**
         * Sends a request to the operation with the name [id] and returns the answer, which is as the
         * document says. [parameters] are what the path has at its parameters, in their order, and [query]
         * is the query string. A request with a [body] declares it as [contentType].
         *
         * The answer has the status [status], which is by default the one status that the document has for a
         * request to the operation that was done. The body of a request that is to be done is as the
         * document says.
         */
        fun asked(
            id: String,
            vararg parameters: String,
            query: String = "",
            body: String = "",
            contentType: String = JSON,
            status: Int? = null,
        ): RawHttp.Answer {
            val operation = operations[id] ?: fail("The document has no operation with the name $id.")
            val expected = status ?: operation.done.single()
            val inThePath = parameters.toMutableList()
            val path = PARAMETER.replace(operation.path) { inThePath.removeFirst() }
            check(inThePath.isEmpty()) { "The path of $operation has no parameter for $inThePath." }
            if (body.isNotEmpty() && expected < MULTIPLE_CHOICES) requireDescribed(operation, body, contentType)

            val target = path.removePrefix("/") + if (query.isEmpty()) "" else "?$query"
            val headers = if (body.isEmpty()) emptyList() else listOf("Content-Type: $contentType")
            val answer = server.asked(operation.method, target, headers, body)

            assertEquals(expected, answer.status, "The answer of $operation: $answer")
            assertDescribed(operation, answer)
            if (expected < MULTIPLE_CHOICES) answered += id
            return answer
        }

        /** Checks that a body of [contentType] is one that the document has for a request to [operation], when it has a schema for it. */
        private fun requireDescribed(
            operation: Operation,
            body: String,
            contentType: String,
        ) {
            val taken = operation.says.getValue("requestBody").jsonObject
            val content = taken.getValue("content").jsonObject
            val schema = content[contentType]?.jsonObject?.getValue("schema") ?: return
            if (contentType != JSON) return
            val violations = schemas.violations(schema, json(body))
            check(violations.isEmpty()) { "The test sends $operation a body that is not as the document says: $violations" }
        }

        /**
         * Checks that [answer] is one that the document has for [operation]: with its media type, its body,
         * and the headers that the document names for it.
         */
        private fun assertDescribed(
            operation: Operation,
            answer: RawHttp.Answer,
        ) {
            val described = operation.responses["${answer.status}"]
            assertNotNull(described, "The document has no answer of $operation with the status ${answer.status}: $answer")
            val says = resolved(document, described.jsonObject)
            val content = says["content"]?.jsonObject
            val mediaType = answer.headerValues("Content-Type").singleOrNull()?.substringBefore(';')
            assertEquals(content == null, answer.body.isEmpty(), "Whether the answer of $operation has a body: $answer")
            val named = says["headers"]?.jsonObject?.keys.orEmpty()
            val carried = answer.headers.map { it.substringBefore(':') }.filter { it in NAMED }
            assertEquals(named.toList(), carried, "The headers of the answer of $operation that the document names")
            if (content == null) return
            val schema = content[mediaType]?.jsonObject?.getValue("schema")
            assertNotNull(schema, "The document has no answer of $operation with the media type $mediaType: $answer")
            if (mediaType == JSON || mediaType == PROBLEM) {
                assertEquals(emptyList(), schemas.violations(schema, json(answer.bodyInUtf8)), "The answer of $operation: $answer")
            }
        }
    }

    /**
     * What the schemas of a document have against a value. The schemas are taken more strictly than they
     * are written: an object has no member that its schema does not name, so that a value of another wire
     * type is not taken for one of the wire type that a schema describes.
     */
    private class Schemas(
        document: JsonObject,
    ) {
        private val validator = ValidatorFactory().withJsonNodeFactory(KotlinxJsonNode.Factory()).createValidator()
        private val closed = mapOf("additionalProperties" to JsonPrimitive(false))
        private val defined = strict(document.getValue("components").jsonObject.getValue("schemas"))
        private var registered = 0

        /**
         * Returns what [schema], which is a schema of the document, has against [value]. A value that is as
         * the schema says gives nothing.
         */
        fun violations(
            schema: JsonElement,
            value: JsonElement,
        ): List<String> {
            val whole =
                buildJsonObject {
                    put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                    put("\$defs", defined)
                    putJsonArray("allOf") { add(strict(schema)) }
                }
            val standingAlone = json("$whole".replace("\"#/components/schemas/", "\"#/\$defs/"))
            val uri = validator.registerSchema(URI.create("mockey:/admin-api/${registered++}"), standingAlone)
            return validator.validate(uri, value).errors.map { "${it.instanceLocation}: ${it.error}" }
        }

        /** Returns [schema] with every schema of an object in it closed: the object has the members that the schema names, and no other. */
        private fun strict(schema: JsonElement): JsonElement =
            when (schema) {
                is JsonArray -> JsonArray(schema.map(::strict))
                is JsonObject -> JsonObject(schema.mapValues { strict(it.value) } + closed.filter { "properties" in schema })
                else -> schema
            }
    }

    private companion object {
        const val JSON = "application/json"
        const val PROBLEM = "application/problem+json"

        /** The first status that does not say that a request was done. */
        const val MULTIPLE_CHOICES = 300

        /** A parameter in a path. */
        val PARAMETER = Regex("""\{([^}]+)}""")

        /** The name of an operation: a word that a program can use as the name of a function. */
        val NAME = Regex("[a-z][A-Za-z]+")

        /** The headers of an answer that the document names where an answer has them. */
        val NAMED = setOf("Location", "WWW-Authenticate")

        fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        /** Returns the text that [element] is. */
        fun text(element: JsonElement): String = element.jsonPrimitive.also { check(it.isString) }.content

        /** Returns the text that the member [name] of [holder] is. */
        fun text(
            holder: JsonObject,
            name: String,
        ): String = text(holder.getValue(name))

        /** Returns everything that [element] refers to, anywhere in it. */
        fun referencesIn(element: JsonElement): Set<String> =
            when (element) {
                is JsonArray -> element.flatMap(::referencesIn).toSet()
                is JsonObject -> element.flatMap { (name, value) -> referencesOf(name, value) }.toSet()
                else -> emptySet()
            }

        /** Returns what the member [name] of an object refers to, which has [value]: that value when the member is a reference. */
        private fun referencesOf(
            name: String,
            value: JsonElement,
        ): Set<String> = if (name == "\$ref") setOf(text(value)) else referencesIn(value)

        /** Returns the names of the members that the member [name] of [holder] has. */
        fun namesOf(
            holder: JsonObject,
            name: String,
        ): Set<String> = holder.getValue(name).jsonObject.keys

        /** Returns what [says] is in [document]: itself, or what it refers to among the components of the document. */
        fun resolved(
            document: JsonObject,
            says: JsonObject,
        ): JsonObject {
            val reference = says["\$ref"]?.let(::text) ?: return says
            return reference.removePrefix("#/").split('/').fold(document) { holder, name -> holder.getValue(name).jsonObject }
        }
    }
}
