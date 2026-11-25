package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ImportItemDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 12.7: the operations of the admin API on the definitions as a whole, over real HTTP: the export and
 * the import of a definitions document, the diagnostics, and the search, on a listener that runs the admin
 * API with these operations, for an engine of the test.
 *
 * A test sends its request to the listener, and looks at the answer and at what the engine holds afterwards.
 */
class AdminDefinitionsTest {
    private val engines = mutableListOf<MockeyEngine>()
    private val listeners = mutableListOf<Listener>()
    private val engine: MockeyEngine = engine()
    private val listener = listenerOf(engine)

    @AfterTest
    fun stopEverything() {
        listeners.forEach(Listener::stop)
        engines.forEach(MockeyEngine::close)
    }

    /** Returns an engine without definitions and without inspectors. */
    private fun engine(): MockeyEngine {
        val created = runBlocking { MockeyEngine.create { inspectors = emptyList() } }
        engines += created
        return created
    }

    /** Starts a listener that runs the admin API with the operations on the definitions of [engine]. */
    private fun listenerOf(engine: MockeyEngine): Listener {
        val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminDefinitions(engine).routes)
        val started = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))
        listeners += started
        return started
    }

    /** Sends a request whose body is [body], declared as [contentType] when there is one, and returns the answer. */
    private fun send(
        method: String,
        target: String,
        body: String = "",
        contentType: String? = "application/json",
        listener: Listener = this.listener,
    ): RawHttp.Answer {
        val headers = if (body.isEmpty() || contentType == null) emptyList() else listOf("Content-Type: $contentType")
        return RawHttp.exchange(listener.address, RawHttp.request(method, "/api/v1/$target", headers, RawHttp.utf8(body)))
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /**
     * Gives [engine] definitions of every kind: the service `Customers` in static mode with the scenarios
     * `ok` and `error`, the service `Orders`, the plan `Night`, which is the default plan and holds the
     * settings of `Customers` as they are, the twist configuration `to-qa2`, which is enabled, the universal
     * error scenario, and an outbound proxy with credentials.
     */
    private fun defineEverything(engine: MockeyEngine = this.engine) {
        runBlocking {
            val realUrls = listOf("http://api.example.com/customers")
            val customers =
                engine.services
                    .create { it.copy(name = "Customers", mockUrl = "customer/{id}", tags = Tags.of("team-a"), realUrls = realUrls) }
                    .getOrThrow()
            engine.scenarios.create(customers.id) { it.copy(name = "ok", responseBody = """{"tier":"PlatinumMember"}""") }.getOrThrow()
            val error = engine.scenarios.create(customers.id) { it.copy(name = "error", statusCode = 500) }.getOrThrow()
            engine.services.update(customers.id) { it.copy(responseMode = ResponseMode.STATIC, errorScenarioId = error.id) }.getOrThrow()
            engine.services.create { it.copy(name = "Orders", mockUrl = "orders") }.getOrThrow()
            val plan = engine.plans.save(listOf(customers.id), "Night").getOrThrow()
            engine.plans.designateDefault(plan.id).getOrThrow()
            val twist = engine.twists.create { it.copy(name = "to-qa2", pairs = listOf(TwistPair("qa1", "qa2"))) }.getOrThrow()
            engine.twists.enable(twist.id).getOrThrow()
            engine.scenarios.designateUniversalError(ScenarioReference(customers.id, error.id)).getOrThrow()
            val credentials = ProxyCredentials("mockey-user", PASSWORD)
            engine.updateOutboundProxy { OutboundProxy(PROXY, enabled = true, credentials = credentials) }.getOrThrow()
        }
    }

    /** Returns [definitions] as a document holds them: without the credentials of the outbound proxy. */
    private fun asADocumentHolds(definitions: Definitions): Definitions {
        val proxy = definitions.settings.outboundProxy?.copy(credentials = null)
        return definitions.copy(settings = definitions.settings.copy(outboundProxy = proxy))
    }

    private fun service(name: String): Service = engine.definitions.services.single { it.name == name }

    private fun assertProblem(
        answer: RawHttp.Answer,
        status: Int,
        title: String,
        detail: String,
        errors: List<FieldErrorDto> = emptyList(),
    ) {
        assertEquals("HTTP/1.1 $status $title", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
        assertEquals(ProblemDto("about:blank", title, status, detail, errors), answer.decoded<ProblemDto>())
    }

    /** [answer] is the problem of a body that is no document that can be imported, and it says [why]. */
    private fun assertNotImported(
        answer: RawHttp.Answer,
        why: String,
    ) {
        val message = "The document cannot be imported: $why"
        val detail = "The request is not valid. document: $message"
        assertProblem(answer, 422, "Unprocessable Entity", detail, listOf(FieldErrorDto("document", message)))
    }

    // Export

    /** The document is the one that the engine exports, which is what a server takes as its initial definitions. */
    @Test
    fun theDefinitionsAreExportedAsTheDocumentOfTheEngineWithoutTheCredentialsOfTheProxy() {
        val ofNothing = send("GET", "definitions/export")
        defineEverything()

        val answer = send("GET", "definitions/export")

        assertEquals(200 to setOf("formatVersion"), ofNothing.status to (Json.parseToJsonElement(ofNothing.body) as JsonObject).keys)
        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        assertEquals(listOf("application/json"), answer.headerValues("Content-Type"))
        assertEquals(engine.exportDefinitions(), answer.bodyInUtf8)
        val document = Json.parseToJsonElement(answer.bodyInUtf8) as JsonObject
        assertEquals(setOf("formatVersion", "services", "plans", "twists", "settings"), document.keys)
        assertTrue(PROXY in answer.text)
        assertFalse(PASSWORD in answer.text || "mockey-user" in answer.text, "The document holds the credentials: $answer")
    }

    /**
     * The scenario "Export and import into an empty server" of the storage, through the API. What is
     * imported keeps its identifier, so the second server has the definitions of the first, except for the
     * credentials of the outbound proxy, which no document holds.
     */
    @Test
    fun theDocumentOfAnExportIsImportedIntoAServerWithoutDefinitions() {
        defineEverything()
        val second = engine()
        val document = send("GET", "definitions/export").bodyInUtf8

        val answer = send("POST", "definitions/import", document, listener = listenerOf(second))

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        val report = answer.decoded<ImportReportDto>()
        val additions =
            listOf(
                "service" to "Customers",
                "scenario" to "ok",
                "scenario" to "error",
                "service" to "Orders",
                "plan" to "Night",
                "twistConfiguration" to "to-qa2",
                "setting" to "outbound proxy",
                "setting" to "universal error scenario",
                "setting" to "enabled twist configuration",
                "setting" to "default plan",
            )
        assertEquals(additions, report.additions.map { it.kind to it.name })
        assertEquals(emptyList(), report.conflicts)
        assertEquals(emptyList(), report.issues)
        val applied = checkNotNull(report.appliedDefaultPlan)
        assertEquals("Night" to 0, applied.plan.name to applied.skippedItems.size + applied.unresolvedScenarios.size)
        assertEquals(asADocumentHolds(engine.definitions), second.definitions)
    }

    // Import of a definitions document of this system

    /**
     * The scenario "Import with a tag" of the storage, through the API, with an import that adds and merges.
     * The report names every part of the document as an addition or as a conflict, and the tags go to what
     * was added and to the service that was merged into.
     */
    @Test
    fun anImportAddsWhatIsNewMergesAServiceOfTheSameNameAndTagsBoth() {
        runBlocking {
            val orders = engine.services.create { it.copy(name = "Orders", mockUrl = "orders", tags = Tags.of("team-b")) }.getOrThrow()
            engine.scenarios.create(orders.id) { it.copy(name = "ok", responseBody = "all orders") }.getOrThrow()
            engine.plans.create { it.copy(name = "Night") }.getOrThrow()
        }
        val document =
            TestDefinitions.document {
                val real = listOf(REAL_ORDERS)
                val orders = services.create { it.copy(name = " orders ", mockUrl = "orders/v2", realUrls = real) }.getOrThrow()
                scenarios.create(orders.id) { it.copy(name = "ok", responseBody = "all orders") }.getOrThrow()
                scenarios.create(orders.id) { it.copy(name = "timeout", responseBody = "too late", statusCode = 504) }.getOrThrow()
                val customers = services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }.getOrThrow()
                scenarios.create(customers.id) { it.copy(name = "found") }.getOrThrow()
                plans.create { it.copy(name = "night") }.getOrThrow()
                plans.create { it.copy(name = "Day") }.getOrThrow()
            }

        val answer = send("POST", "definitions/import?tag=Sprint42+imported&tag=sprint42", document)

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        val additions =
            listOf(
                ImportItemDto("scenario", "timeout", "The scenario 'timeout' was added to the service 'Orders'.", "orders"),
                ImportItemDto("service", "Customers", "The service 'Customers' was added with 1 scenario."),
                ImportItemDto("scenario", "found", "The scenario 'found' was added with its service 'Customers'.", "Customers"),
                ImportItemDto("plan", "Day", "The plan 'Day' was added."),
            )
        val sameScenario = "The scenario 'ok' was not added: the service 'Orders' has a scenario with that name and response body."
        val sameService =
            "The service 'orders' was not added: the service 'Orders' has that name. What that service did not have of it was added to it."
        val conflicts =
            listOf(
                ImportItemDto("scenario", "ok", sameScenario, "orders"),
                ImportItemDto("service", "orders", sameService),
                ImportItemDto("plan", "night", "The plan 'night' was not added: a plan has that name."),
            )
        assertEquals(ImportReportDto(additions, conflicts), answer.decoded<ImportReportDto>())
        val tags = listOf("imported", "sprint42")
        val orders = service("Orders")
        assertEquals(listOf("imported", "sprint42", "team-b") to listOf(REAL_ORDERS), orders.tags.toList() to orders.realUrls)
        assertEquals(listOf("ok" to emptyList(), "timeout" to tags), orders.scenarios.map { it.name to it.tags.toList() })
        assertEquals(tags to listOf(tags), service("Customers").run { this.tags.toList() to scenarios.map { it.tags.toList() } })
        assertEquals(listOf("Night" to emptyList(), "Day" to tags), engine.definitions.plans.map { it.name to it.tags.toList() })
    }

    /** A document without anything is a document, and its import adds nothing. */
    @Test
    fun aDocumentWithoutDefinitionsIsImportedAndAddsNothing() {
        defineEverything()
        val before = engine.definitions

        val answer = send("POST", "definitions/import", """{"formatVersion": 1}""")

        assertEquals(200 to ImportReportDto(emptyList(), emptyList()), answer.status to answer.decoded<ImportReportDto>())
        assertEquals(before, engine.definitions)
    }

    /** The engine says what a definitions document is, and the problem says why a body is none. Nothing is imported of it. */
    @Test
    fun aBodyThatIsNoDefinitionsDocumentIsNotImported() {
        defineEverything()
        val before = engine.definitions
        val versions = "it has the format version 99, and this version of Mockey reads the format version 1 only."

        val ofAnotherShape = send("POST", "definitions/import", """{"services": "none"}""")
        val ofAList = send("POST", "definitions/import", "[]")
        val ofALaterVersion = send("POST", "definitions/import", """{"formatVersion": 99}""")
        val ofTheApi = send("POST", "definitions/import", """{"services": [{"name": "Clients", "mockUrl": "clients"}]}""")

        assertEquals(422 to listOf("document"), ofAnotherShape.status to ofAnotherShape.decoded<ProblemDto>().errors.map { it.field })
        assertNotImported(ofAList, "it is not a JSON object.")
        assertNotImported(ofALaterVersion, versions)
        assertEquals(422 to listOf("document"), ofTheApi.status to ofTheApi.decoded<ProblemDto>().errors.map { it.field })
        assertEquals(before, engine.definitions)
    }

    /**
     * A request says which document its body is. A body that it declares as something else is not read, and
     * neither is one that it does not declare.
     */
    @Test
    fun anImportTakesABodyThatIsDeclaredAsJsonOrAsXml() {
        val document = TestDefinitions.document { services.create { it.copy(name = "Orders", mockUrl = "orders") }.getOrThrow() }
        val declaring =
            "The body of the request is a document, and the request has to say which: JSON in UTF-8, with the header " +
                "Content-Type: application/json, or XML, with the header Content-Type: application/xml."

        val forms = listOf("application/x-www-form-urlencoded", "multipart/form-data; boundary=x")
        for (type in forms + listOf("text/plain", "application/octet-stream", "application/json; charset=ISO-8859-1", null)) {
            assertProblem(send("POST", "definitions/import", document, type), 415, "Unsupported Media Type", declaring)
            assertProblem(send("POST", "definitions/import", LegacySources.export(), type), 415, "Unsupported Media Type", declaring)
        }
        val withoutABody = send("POST", "definitions/import")
        val inLatin1 = RawHttp.request("POST", "/api/v1/definitions/import", listOf("Content-Type: application/json"), LATIN_1)
        val notUtf8 = RawHttp.exchange(listener.address, inLatin1)
        val otherParameter = send("POST", "definitions/import?tags=imported", document)
        val read = send("GET", "definitions/import")

        assertProblem(withoutABody, 400, "Bad Request", "The request has no body. The operation takes a document.")
        assertProblem(notUtf8, 400, "Bad Request", "The body of the request is not encoded as UTF-8.")
        val taken = "The query string of the request has a parameter that the operation does not take. It takes these: tag."
        assertProblem(otherParameter, 400, "Bad Request", taken)
        assertEquals(405 to listOf("POST"), read.status to read.headerValues("Allow"))
        assertEquals(Definitions.EMPTY, engine.definitions)
    }

    // Import of an export document of Mockey 2.x

    /**
     * The scenario "Import a legacy export document": the answer is the report, and what was added carries
     * the tag. The default plan of the document is designated and applied, as with every import.
     */
    @Test
    fun anExportDocumentOfMockey2IsImportedWithItsTagAndTheAnswerIsTheReport() {
        val document = LegacySources.export(withDefaultPlan = true)

        val answer = send("POST", "definitions/import?tag=migrated", document, contentType = "application/xml")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        val report = answer.decoded<ImportReportDto>()
        val additions =
            listOf(
                ImportItemDto("service", "Legacy Orders", "The service 'Legacy Orders' was added with 2 scenarios."),
                ImportItemDto("scenario", "silver", "The scenario 'silver' was added with its service 'Legacy Orders'.", "Legacy Orders"),
                ImportItemDto("scenario", "gold", "The scenario 'gold' was added with its service 'Legacy Orders'.", "Legacy Orders"),
                ImportItemDto("plan", "Gold path", "The plan 'Gold path' was added."),
                ImportItemDto("setting", "default plan", "The plan 'Gold path' was designated as the default plan and applied."),
            )
        assertEquals(additions, report.additions)
        assertEquals(emptyList(), report.conflicts.map { it.message } + report.issues + report.unreadableRuleSets.map { it.message })
        val plan = engine.definitions.plans.single()
        assertEquals(plan.toAdminDto(), report.appliedDefaultPlan?.plan)
        val service = engine.definitions.services.single()
        val migrated = listOf("migrated")
        val tags = listOf(service.tags.toList(), plan.tags.toList()) + service.scenarios.map { it.tags.toList() }
        assertEquals(List(4) { migrated }, tags)
        val default = service.scenarios.single { it.id == service.defaultScenarioId }
        assertEquals("gold" to plan.id, default.name to engine.definitions.settings.defaultPlanId)
    }

    /**
     * A document of Mockey 2.x is imported without what cannot be read of it, and with a rule set that is not
     * well formed as it is. The report names both. `text/xml` declares XML as well, and the document says in
     * which charset it is written, whatever the request says.
     */
    @Test
    fun theReportOfADocumentOfMockey2NamesWhatWasNotReadAndTheRuleSetsThatAreNotWellFormed() {
        val scenario =
            """<scenario id="1" name="ruled"><scenario_match scenario_match_evaluation_rules_flag="true">no rules</scenario_match>""" +
                "<scenario_response>Grüße ✓</scenario_response></scenario>"
        val document =
            """<?xml version="1.0" encoding="UTF-8"?><mockservice version="1.0">""" +
                """<service name="Orders" url="orders" default_scenario_id="99">$scenario</service></mockservice>"""

        val answer = send("POST", "definitions/import", document, contentType = "text/xml; charset=ISO-8859-1")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        val report = answer.decoded<ImportReportDto>()
        val orders = engine.definitions.services.single()
        val ruled = orders.scenarios.single()
        val dangling =
            "The service 'Orders' names the scenario 99 as its default scenario (default_scenario_id), but it has no scenario with " +
                "that number. The earliest scenario of the service is its default scenario."
        assertEquals(listOf(dangling), report.issues)
        val notWellFormed = report.unreadableRuleSets.single()
        val ofRuled = listOf(ruled.id.value)
        assertEquals(DiagnosticDto("unreadableRuleSet", orders.id.value, "Orders", notWellFormed.message, null, ofRuled), notWellFormed)
        assertTrue(notWellFormed.message.startsWith("The rules of the scenario 'ruled' cannot be read. "), notWellFormed.message)
        assertEquals(listOf("service" to "Orders", "scenario" to "ruled"), report.additions.map { it.kind to it.name })
        assertEquals(MatchCriterion.Rules("no rules") to "Grüße ✓", ruled.matchCriterion to ruled.responseBody)
    }

    /**
     * A body of which nothing can be read as a document of Mockey 2.x is not imported, and the problem says
     * why. A document is read without what it refers to: the content of a file that it names is neither
     * imported nor part of the answer.
     */
    @Test
    fun aDocumentThatIsNoDocumentOfMockey2IsNotImportedAndNoFileIsReadForIt() {
        defineEverything()
        val before = engine.definitions
        val secret = Files.createTempFile("mockey-admin-definitions-test", ".txt")
        Files.writeString(secret, SECRET)
        val withAnEntity =
            """<?xml version="1.0"?><!DOCTYPE mockservice [<!ENTITY secret SYSTEM "${secret.toUri()}">]>""" +
                """<mockservice><service name="&secret;" url="x"/></mockservice>"""
        val withAnInclusion =
            """<mockservice xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="${secret.toUri()}" parse="text"/></mockservice>"""
        val withAReference = """<mockservice version="1.0"><serviceref file="$secret"/></mockservice>"""

        val answers =
            try {
                listOf("<mockservice><service", "<html/>", withAnEntity, withAnInclusion, withAReference).map {
                    send("POST", "definitions/import", it, contentType = "application/xml")
                }
            } finally {
                Files.delete(secret)
            }

        val notWellFormed = "XML document structures must start and end within the same entity."
        assertNotImported(answers[0], "The document is not well-formed XML in line 1: $notWellFormed")
        assertNotImported(answers[1], "The document holds no definitions of Mockey 2.x: its root element is 'html', not 'mockservice'.")
        val notProcessed = "The document was not read: it declares a document type, and with it entities, which an import does not process."
        assertNotImported(answers[2], notProcessed)
        val notRead = "no directory is imported, so no file is read."
        assertNotImported(answers[3], "The document includes '${secret.toUri()}', which was not included: $notRead")
        assertNotImported(answers[4], "The file $secret was not read: $notRead")
        assertFalse(answers.any { SECRET in it.text }, "An answer holds the content of the file: $answers")
        assertEquals(before, engine.definitions)
    }

    /** A document of Mockey 2.x without definitions is a document, and its import adds nothing. */
    @Test
    fun aDocumentOfMockey2WithoutDefinitionsIsImportedAndAddsNothing() {
        val answer = send("POST", "definitions/import", """<mockservice version="1.0"/>""", contentType = "application/xml")

        assertEquals(200 to ImportReportDto(emptyList(), emptyList()), answer.status to answer.decoded<ImportReportDto>())
        assertEquals(Definitions.EMPTY, engine.definitions)
    }

    // Diagnostics

    /**
     * A diagnostic names its service and, where it is about scenarios, the scenarios. Definitions without
     * one give an empty list. The definitions of the test come from an import, which takes what a change
     * through the API does not: scenarios that are the same, and request rules that are not well formed.
     */
    @Test
    fun theDiagnosticsNameWhatDeservesAttentionInTheDefinitions() {
        val none = send("GET", "diagnostics")
        val twins = listOf("twin-1", "twin-2").map { Scenario(ScenarioId(it), "twin", "the same") }
        val customers = Service(ServiceId("customers"), "Customers", "customer/{id}", scenarios = twins)
        val clients =
            Service(
                id = ServiceId("clients"),
                name = "Clients",
                mockUrl = "clients",
                scenarios = listOf(Scenario(ScenarioId("empty"), "empty", "{}")),
                requestRules = "no rules",
                inspectorName = "session-check",
                responseSchema = """{"type": "object", "required": ["id"]}""",
                responseSchemaEnabled = true,
            )
        runBlocking { engine.importDefinitions(Definitions(listOf(customers, clients))) }

        val answer = send("GET", "diagnostics")

        assertEquals(200 to "[]", none.status to none.body)
        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        val diagnostics = answer.decoded<List<DiagnosticDto>>()
        val messages = diagnostics.map { it.message }
        val expected =
            listOf(
                DiagnosticDto("duplicateScenarios", "customers", "Customers", messages[0], null, listOf("twin-1", "twin-2")),
                DiagnosticDto("unknownInspector", "clients", "Clients", messages[1]),
                DiagnosticDto("unreadableRuleSet", "clients", "Clients", messages[2]),
                DiagnosticDto("schemaViolation", "clients", "Clients", messages[3], null, listOf("empty")),
            )
        assertEquals(expected, diagnostics)
        assertEquals("2 scenarios with the name 'twin' have the same response body.", messages[0])
        assertEquals("The inspector 'session-check' is not installed.", messages[1])
        assertTrue(messages[2].startsWith("The request rules of the service cannot be read. "), messages[2])
        val violation = "The response body of the scenario 'empty' does not conform to the response schema: "
        assertTrue(messages[3].startsWith(violation), messages[3])
    }

    // Search

    /**
     * The scenario "Term found in a scenario body", and a term in a name, a tag, and a URL. The plans come
     * first, and each service is followed by its scenarios. A scenario is stated with its service.
     */
    @Test
    fun theSearchFindsPlansServicesAndScenariosWithoutRegardToLetterCase() {
        defineEverything()
        runBlocking { engine.plans.create { it.copy(name = "Platinum night", tags = Tags.of("customers")) }.getOrThrow() }
        val customers = service("Customers")
        val ok = customers.scenarios.first()
        val ofCustomers = SearchResultDto("service", customers.id.value, "Customers")
        val ofOk = SearchResultDto("scenario", ok.id.value, "ok", customers.id.value, "Customers")
        val plans = engine.definitions.plans.associateBy(Plan::name)
        val ofPlatinumNight = SearchResultDto("plan", plans.getValue("Platinum night").id.value, "Platinum night")
        val ofNight = SearchResultDto("plan", plans.getValue("Night").id.value, "Night")
        val searches =
            mapOf(
                "platinummember" to listOf(ofOk),
                "PLATINUM" to listOf(ofPlatinumNight, ofOk),
                "night" to listOf(ofNight, ofPlatinumNight),
                "customers" to listOf(ofPlatinumNight, ofCustomers),
                "team-A" to listOf(ofCustomers),
                "api.example.com" to listOf(ofCustomers),
                "customer/%7Bid%7D" to listOf(ofCustomers),
                "+OK+" to listOf(ofOk),
                "nothing-like-it" to emptyList(),
                "" to emptyList(),
                "+" to emptyList(),
            )

        val found = searches.mapValues { (term, _) -> send("GET", "search?term=$term") }

        assertEquals(List(searches.size) { 200 }, found.values.map { it.status })
        assertEquals(searches, found.mapValues { (_, answer) -> answer.decoded<List<SearchResultDto>>() })
    }

    @Test
    fun aSearchTakesOneTerm() {
        defineEverything()

        val withoutATerm = send("GET", "search")
        val withTwo = send("GET", "search?term=customers&term=orders")
        val withAnother = send("GET", "search?q=customers")

        val noTerm = "The query string of the request has to state what to search for, in the parameter term."
        assertProblem(withoutATerm, 400, "Bad Request", noTerm)
        assertProblem(withTwo, 400, "Bad Request", "The query string of the request has the parameter term more than once.")
        val taken = "The query string of the request has a parameter that the operation does not take. It takes these: term."
        assertProblem(withAnother, 400, "Bad Request", taken)
    }

    private companion object {
        const val PROXY = "http://proxy.example:3128"
        const val PASSWORD = "pw-7c1a9e3f5d20"
        const val REAL_ORDERS = "http://api.example.com/orders"

        /** A JSON object whose text is no UTF-8: its bytes are those of ISO 8859-1. */
        const val LATIN_1 = "{\"note\": \"\u00ff\"}"

        /** The content of a file of the machine of the server, which no import is to read. */
        const val SECRET = "sk-2f8b6d0a4c9e"
    }
}
