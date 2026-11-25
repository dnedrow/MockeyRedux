package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 12.3: the operations of the admin API on services, over real HTTP: on a listener that runs the admin
 * API with the operations on services and scenarios, for an engine of the test.
 *
 * A test sends its request to the listener, and looks at the answer and at what the engine holds afterwards.
 * What the API makes of a body that is no object of its wire type is the matter of [AdminBodyTest].
 */
class AdminServicesTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminServices(engine).routes + AdminScenarios(engine).routes)
    private val listener = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))

    @AfterTest
    fun stopEverything() {
        listener.stop()
        engine.close()
    }

    private fun send(
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer {
        val headers = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        return RawHttp.exchange(listener.address, RawHttp.request(method, "/api/v1/$target", headers, RawHttp.utf8(body)))
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    private val services: List<Service>
        get() = engine.definitions.services

    /** Defines a service in the engine, with the attributes that [define] gives it. */
    private fun service(
        name: String,
        mockUrl: String,
        define: (Service) -> Service = { it },
    ): Service = runBlocking { engine.services.create { define(it.copy(name = name, mockUrl = mockUrl)) }.getOrThrow() }

    /** Defines a scenario of [service] in the engine, which answers with [body]. */
    private fun scenario(
        service: Service,
        name: String,
        body: String = name,
    ): Scenario = runBlocking { engine.scenarios.create(service.id) { it.copy(name = name, responseBody = body) }.getOrThrow() }

    private fun current(service: Service): Service = services.single { it.id == service.id }

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

    /** [answer] is the problem of a request that is not valid, and it names exactly [errors], which are fields with their messages. */
    private fun assertInvalid(
        answer: RawHttp.Answer,
        vararg errors: Pair<String, String>,
    ) {
        val fields = errors.map { (field, message) -> FieldErrorDto(field, message) }
        val detail = fields.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
        assertProblem(answer, 422, "Unprocessable Entity", detail, fields)
    }

    private fun assertNoService(
        answer: RawHttp.Answer,
        id: String = MISSING,
    ) {
        assertProblem(answer, 404, "Not Found", "No service has the identifier $id.")
    }

    // Listing

    /** The scenario "Find a service by name", and the filter by tag. A service is listed when it carries one of the tags. */
    @Test
    fun theListOfServicesIsFilteredByTagAndByExactNameWithoutRegardToLetterCase() {
        service("Customers", "customer/{id}") { it.copy(tags = Tags.of("team-a", "nightly")) }
        service("Orders", "orders") { it.copy(tags = Tags.of("team-b", "nightly")) }
        service("Billing", "billing")
        service("customers", "eu/customer/{id}") { it.copy(tags = Tags.of("team-b")) }
        val all = listOf("customer/{id}", "orders", "billing", "eu/customer/{id}")
        val filters =
            mapOf(
                "" to all,
                "?tag=team-a" to listOf("customer/{id}"),
                "?tag=nightly" to listOf("customer/{id}", "orders"),
                "?tag=TEAM-A" to listOf("customer/{id}"),
                "?tag=team-a&tag=team-b" to listOf("customer/{id}", "orders", "eu/customer/{id}"),
                "?tag=team-a+team-b" to listOf("customer/{id}", "orders", "eu/customer/{id}"),
                "?tag=team" to emptyList(),
                "?tag=" to all,
                "?name=customers" to listOf("customer/{id}", "eu/customer/{id}"),
                "?name=CUSTOMERS" to listOf("customer/{id}", "eu/customer/{id}"),
                "?name=Billing" to listOf("billing"),
                "?name=custom" to emptyList(),
                "?name=" to emptyList(),
                "?name=orders&tag=nightly" to listOf("orders"),
                "?tag=team-a&name=orders" to emptyList(),
            )

        val listed = filters.keys.associateWith { send("GET", "services$it") }

        assertEquals(List(filters.size) { 200 }, listed.values.map { it.status })
        assertEquals(filters, listed.mapValues { (_, answer) -> answer.decoded<List<ServiceDto>>().map { it.mockUrl } })
    }

    @Test
    fun aListOfServicesTakesOneNameAndNoOtherParameter() {
        service("Customers", "customer/{id}")

        val twoNames = send("GET", "services?name=customers&name=orders")
        val another = send("GET", "services?tags=team-a")

        assertProblem(twoNames, 400, "Bad Request", "The query string of the request has the parameter name more than once.")
        val taken = "The query string of the request has a parameter that the operation does not take. It takes these: tag, name."
        assertProblem(another, 400, "Bad Request", taken)
    }

    // Creation

    /** The scenario "Created service is returned": the answer holds the service with its identifier, and says where it is. */
    @Test
    fun aServiceIsCreatedWithTheAttributesOfTheRequestAndReturnedWithItsIdentifier() {
        val attributes =
            """
            {"name": " Kundenübersicht ✓ ", "mockUrl": "customer/{id}", "description": "The customers of the shop.",
             "realUrls": ["http://qa1.example.com/customers/{id}", "http://qa2.example.com/customers/{id}"], "defaultRealUrlIndex": 1,
             "responseMode": "dynamic", "delayMillis": 250, "tags": ["Nightly team-a", "TEAM-A"],
             "requestRules": "{\"url\":[{\"value_rule_arg\":\"44\",\"value_rule_type\":\"string_required\"}]}",
             "requestRulesEnabled": true, "inspectorName": "audit",
             "responseSchema": "{\"type\":\"object\"}", "responseSchemaEnabled": true}
            """.trimIndent()

        val answer = send("POST", "services", attributes)

        val created = answer.decoded<ServiceDto>()
        assertEquals("HTTP/1.1 201 Created", answer.statusLine)
        assertEquals(listOf("/api/v1/services/${created.id}"), answer.headerValues("Location"))
        assertEquals(
            ServiceDto(
                id = created.id,
                name = "Kundenübersicht ✓",
                mockUrl = "customer/{id}",
                description = "The customers of the shop.",
                realUrls = listOf("http://qa1.example.com/customers/{id}", "http://qa2.example.com/customers/{id}"),
                defaultRealUrlIndex = 1,
                responseMode = ResponseModeDto.DYNAMIC,
                effectiveResponseMode = ResponseModeDto.PROXY,
                delayMillis = 250,
                tags = listOf("nightly", "team-a"),
                requestRules = """{"url":[{"value_rule_arg":"44","value_rule_type":"string_required"}]}""",
                requestRulesEnabled = true,
                inspectorName = "audit",
                responseSchema = """{"type":"object"}""",
                responseSchemaEnabled = true,
            ),
            created,
        )
        assertTrue(created.id.isNotBlank())
        assertEquals(listOf(created), services.map { it.toAdminDto(engine.lastUsedTimes) })
        assertEquals(created, send("GET", "services/${created.id}").decoded<ServiceDto>())
    }

    /** A service has the defaults of `service-definitions` for what a request leaves out, and its real URL names it without a name. */
    @Test
    fun aServiceIsCreatedWithTheDefaultsForWhatTheRequestLeavesOut() {
        val named = send("POST", "services", """{"name":"Customers","mockUrl":"customer/{id}"}""").decoded<ServiceDto>()
        val unnamed = send("POST", "services", """{"realUrls":["http://api.example.com/v1/orders?open=true"]}""").decoded<ServiceDto>()

        assertEquals(ServiceDto(named.id, "Customers", "customer/{id}"), named)
        val realUrls = listOf("http://api.example.com/v1/orders?open=true")
        assertEquals(ServiceDto(unnamed.id, "orders (auto generated)", "", realUrls = realUrls), unnamed)
        assertNotEquals(named.id, unnamed.id)
    }

    /** The scenario "Validation failure": the problem lists the mock URL with a message that names the other service. */
    @Test
    fun aServiceWithTheMockUrlOfAnotherServiceIsNotCreated() {
        service("Customers", "customer/{id}")

        val answer = send("POST", "services", """{"name":"Clients","mockUrl":"Customer/{ID}"}""")

        assertInvalid(answer, "mockUrl" to "The service 'Customers' already uses the mock URL pattern 'Customer/{ID}'.")
        assertEquals(listOf("Customers"), services.map { it.name })
    }

    /** The engine says what a service has to be like. The API passes on every invalid attribute that the engine names. */
    @Test
    fun aServiceThatBreaksTheRulesForServicesIsNotCreatedAndEveryInvalidAttributeIsNamed() {
        service("Customers", "customer/{id}") { it.copy(realUrls = listOf("http://api.example.com/customers")) }
        val before = engine.definitions
        val rules = "The request rules are not a well-formed rule set."

        val withoutAName = send("POST", "services", "{}")
        val name = "n".repeat(251)
        val invalid = """{"name":"$name","mockUrl":"customer/{id}","realUrls":["http://API.example.com/customers"],"requestRules":"{"}"""
        val several = send("POST", "services", invalid)
        val blank = """{"name":"Orders","mockUrl":"orders","defaultScenarioId":"s-1","errorScenarioId":" "}"""
        val designating = send("POST", "services", blank)

        assertInvalid(withoutAName, "name" to "The name of a service must not be empty.")
        val errors = several.decoded<ProblemDto>().errors
        assertEquals(listOf("name", "mockUrl", "realUrls", "requestRules"), errors.map { it.field })
        assertEquals(
            listOf(
                "The name of a service must not be longer than 250 characters.",
                "The service 'Customers' already uses the mock URL pattern 'customer/{id}'.",
                "The service 'Customers' already lists the real URL http://API.example.com/customers.",
            ),
            errors.take(3).map { it.message },
        )
        assertTrue(errors.last().message.startsWith(rules), "${errors.last()}")
        assertEquals(422, several.status)
        assertInvalid(designating, "errorScenarioId" to "An identifier is not blank.")
        assertEquals(before, engine.definitions)
    }

    /** A new service has no scenarios, so it cannot designate one. The scenarios and the identifier are no attributes at all. */
    @Test
    fun aRequestCannotGiveANewServiceScenariosOrAnIdentifier() {
        val designating = send("POST", "services", """{"name":"Orders","mockUrl":"orders","defaultScenarioId":"s-1"}""")
        val withScenarios = send("POST", "services", """{"name":"Orders","mockUrl":"orders","scenarios":[],"id":"svc-1"}""")

        assertInvalid(designating, "defaultScenarioId" to "The service has no scenario with the identifier s-1.")
        val unknown = "The request can have no member of this name here."
        assertInvalid(withScenarios, "scenarios" to unknown, "id" to unknown)
        assertEquals(Definitions.EMPTY, engine.definitions)
    }

    /** The scenario "Unparseable body", for the creation of a service. */
    @Test
    fun aBodyThatIsNoJsonObjectCreatesNoService() {
        val noJson = send("POST", "services", """{"name":"Customers",""")
        val noObject = send("POST", "services", """["Customers"]""")
        val nothing = send("POST", "services")

        assertProblem(noJson, 400, "Bad Request", "The body of the request is no JSON object.")
        assertProblem(noObject, 400, "Bad Request", "The body of the request is no JSON object.")
        assertProblem(nothing, 400, "Bad Request", "The request has no body. The operation takes a JSON object.")
        assertEquals(Definitions.EMPTY, engine.definitions)
    }

    // Update

    /** The scenario "Only the delay is changed". */
    @Test
    fun anUpdateChangesOnlyTheAttributesThatTheRequestStates() {
        val defined =
            service("Customers", "customer/{id}") {
                it.copy(
                    description = "The customers of the shop.",
                    realUrls = listOf("http://qa1.example.com/customers", "http://qa2.example.com/customers"),
                    defaultRealUrlIndex = 1,
                    tags = Tags.of("nightly"),
                )
            }
        scenario(defined, "found")
        val gold = scenario(defined, "gold")
        runBlocking {
            engine.services.update(defined.id) { it.copy(responseMode = ResponseMode.DYNAMIC, defaultScenarioId = gold.id) }.getOrThrow()
        }
        val before = current(defined)

        val answer = send("PATCH", "services/${defined.id.value}", """{"delayMillis":250}""")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals(before.copy(delayMillis = 250), current(defined))
        assertEquals(ResponseMode.DYNAMIC to gold.id, current(defined).let { it.responseMode to it.defaultScenarioId })
        assertEquals(current(defined).toAdminDto(engine.lastUsedTimes), answer.decoded<ServiceDto>())
    }

    @Test
    fun anUpdateCanChangeEveryAttributeAndTakeAwayTheOnesThatAServiceNeedNotHave() {
        val defined =
            service("Customers", "customer/{id}") {
                it.copy(description = "The customers of the shop.", inspectorName = "audit", tags = Tags.of("nightly"), delayMillis = 250)
            }
        val changes =
            """
            {"name": "Clients", "mockUrl": "client/{id}", "description": null, "realUrls": ["http://api.example.com/clients"],
             "responseMode": "static", "delayMillis": 0, "tags": [], "requestRules": "{}", "requestRulesEnabled": true,
             "inspectorName": null, "responseSchema": "{}", "responseSchemaEnabled": true}
            """.trimIndent()

        val answer = send("PATCH", "services/${defined.id.value}", changes)

        val expected =
            defined.copy(
                name = "Clients",
                mockUrl = "client/{id}",
                description = null,
                realUrls = listOf("http://api.example.com/clients"),
                responseMode = ResponseMode.STATIC,
                delayMillis = 0,
                tags = Tags.EMPTY,
                requestRules = "{}",
                requestRulesEnabled = true,
                inspectorName = null,
                responseSchema = "{}",
                responseSchemaEnabled = true,
            )
        assertEquals(200 to expected, answer.status to current(defined))
        assertEquals(expected.toAdminDto(engine.lastUsedTimes), answer.decoded<ServiceDto>())
    }

    /** An update that states nothing changes nothing, and answers with the service as it is. */
    @Test
    fun anUpdateWithoutAttributesLeavesTheServiceAsItIs() {
        val defined = service("Customers", "customer/{id}") { it.copy(delayMillis = 250) }

        val answer = send("PATCH", "services/${defined.id.value}", "{}")

        assertEquals(200 to defined.toAdminDto(engine.lastUsedTimes), answer.status to answer.decoded<ServiceDto>())
        assertEquals(defined, current(defined))
    }

    /** The default scenario and the error scenario of a service are attributes of the service. `null` ends a designation. */
    @Test
    fun anUpdateDesignatesTheDefaultScenarioAndTheErrorScenarioOfAService() {
        val defined = service("Customers", "customer/{id}")
        val found = scenario(defined, "found")
        val failed = scenario(defined, "failed")
        val path = "services/${defined.id.value}"

        val designated = send("PATCH", path, """{"defaultScenarioId":"${failed.id.value}","errorScenarioId":"${failed.id.value}"}""")
        val afterDesignation = current(defined)
        val ended = send("PATCH", path, """{"defaultScenarioId":null,"errorScenarioId":null}""")

        assertEquals(listOf<Int?>(200, 200), listOf(designated.status, ended.status))
        assertEquals(failed.id to failed.id, afterDesignation.defaultScenarioId to afterDesignation.errorScenarioId)
        assertEquals(failed.id.value, designated.decoded<ServiceDto>().errorScenarioId)
        // A service with scenarios has a default scenario: the one that was created first, where none is designated.
        assertEquals(found.id to null, current(defined).let { it.defaultScenarioId to it.errorScenarioId })
    }

    @Test
    fun anUpdateCannotDesignateWhatIsNoScenarioOfTheService() {
        val defined = service("Customers", "customer/{id}")
        scenario(defined, "found")
        val ofAnother = scenario(service("Orders", "orders"), "listed")
        val path = "services/${defined.id.value}"
        val before = engine.definitions

        val unknown = send("PATCH", path, """{"defaultScenarioId":"$MISSING"}""")
        val another = send("PATCH", path, """{"errorScenarioId":"${ofAnother.id.value}"}""")
        val blank = send("PATCH", path, """{"defaultScenarioId":"","errorScenarioId":"  "}""")

        assertInvalid(unknown, "defaultScenarioId" to "The service has no scenario with the identifier $MISSING.")
        assertInvalid(another, "errorScenarioId" to "The service has no scenario with the identifier ${ofAnother.id.value}.")
        assertInvalid(blank, "defaultScenarioId" to "An identifier is not blank.", "errorScenarioId" to "An identifier is not blank.")
        assertEquals(before, engine.definitions)
    }

    /** The whole service is checked after an update, and one that breaks a rule stays as it was. */
    @Test
    fun anUpdateThatBreaksTheRulesForServicesChangesNothing() {
        service("Orders", "orders")
        val defined = service("Customers", "customer/{id}")
        val before = engine.definitions

        val taken = send("PATCH", "services/${defined.id.value}", """{"mockUrl":"ORDERS","delayMillis":250}""")
        val unnamed = send("PATCH", "services/${defined.id.value}", """{"name":"  "}""")
        val others = """{"id":"svc-1","effectiveResponseMode":"static","delayMillis":250}"""
        val noAttribute = send("PATCH", "services/${defined.id.value}", others)

        assertInvalid(taken, "mockUrl" to "The service 'Orders' already uses the mock URL pattern 'ORDERS'.")
        assertInvalid(unnamed, "name" to "The name of a service must not be empty.")
        val unknown = "The request can have no member of this name here."
        assertInvalid(noAttribute, "id" to unknown, "effectiveResponseMode" to unknown)
        assertEquals(before, engine.definitions)
    }

    // Deletion

    @Test
    fun aServiceIsDeletedWithItsScenariosAndTheAnswerHasNoBody() {
        val kept = service("Orders", "orders")
        val defined = service("Customers", "customer/{id}")
        val found = scenario(defined, "found")
        runBlocking { engine.scenarios.designateUniversalError(ScenarioReference(defined.id, found.id)).getOrThrow() }

        val answer = send("DELETE", "services/${defined.id.value}")
        val again = send("DELETE", "services/${defined.id.value}")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(emptyList(), answer.headerValues("Content-Type") + answer.headerValues("Content-Length"))
        assertEquals(listOf(kept), services)
        assertNull(engine.definitions.settings.universalErrorScenario)
        assertNoService(again, defined.id.value)
        assertNoService(send("GET", "services/${defined.id.value}"), defined.id.value)
    }

    // Duplication

    /** The copy is a service of its own: no other service has its identifier, its name, or its mock URL, and its scenarios are copies. */
    @Test
    fun aServiceIsDuplicatedAndTheCopyIsReturnedWithWhereItIs() {
        val defined =
            service("Customers", "customer/{id}") {
                it.copy(realUrls = listOf("http://api.example.com/customers"), tags = Tags.of("nightly"), delayMillis = 250)
            }
        scenario(defined, "found")
        val failed = scenario(defined, "failed")
        runBlocking {
            val designated = engine.services.update(defined.id) { it.copy(responseMode = ResponseMode.STATIC, errorScenarioId = failed.id) }
            designated.getOrThrow()
        }
        val original = current(defined)

        val answer = send("POST", "services/${defined.id.value}/duplicate")
        val second = send("POST", "services/${defined.id.value}/duplicate").decoded<ServiceDto>()

        val copy = answer.decoded<ServiceDto>()
        assertEquals("HTTP/1.1 201 Created", answer.statusLine)
        assertEquals(listOf("/api/v1/services/${copy.id}"), answer.headerValues("Location"))
        assertEquals(Triple("Customers (copy)", "customer/{id}-copy", emptyList<String>()), Triple(copy.name, copy.mockUrl, copy.realUrls))
        assertEquals("Customers (copy 2)" to "customer/{id}-copy-2", second.name to second.mockUrl)
        assertEquals(Triple(ResponseModeDto.STATIC, 250L, listOf("nightly")), Triple(copy.responseMode, copy.delayMillis, copy.tags))
        assertEquals(listOf("found", "failed"), copy.scenarios.map { it.name })
        assertEquals(emptySet(), copy.scenarios.map { it.id }.toSet() intersect original.scenarios.map { it.id.value }.toSet())
        assertEquals(copy.scenarios.first().id to copy.scenarios.last().id, copy.defaultScenarioId to copy.errorScenarioId)
        assertEquals(listOf(original.toAdminDto(engine.lastUsedTimes), copy, second), services.map { it.toAdminDto(engine.lastUsedTimes) })
    }

    // Merging

    /** Every scenario of the sources goes to the service of the request, and with nothing skipped the sources are deleted. */
    @Test
    fun servicesAreMergedIntoTheServiceOfTheRequestAndTheAnswerSaysWhatWasDone() {
        val destination = service("Customers", "customer/{id}") { it.copy(tags = Tags.of("nightly")) }
        scenario(destination, "found")
        val ofTheEu = listOf("http://eu.example.com/customers")
        val eu = service("Customers EU", "eu/customer/{id}") { it.copy(realUrls = ofTheEu, tags = Tags.of("eu")) }
        scenario(eu, "found in the EU")
        val us = service("Customers US", "us/customer/{id}")
        scenario(us, "found in the US")
        scenario(us, "lost in the US")

        val answer = send("POST", "services/${destination.id.value}/merge", """{"sourceIds":["${eu.id.value}","${us.id.value}"]}""")

        val copied =
            listOf(
                MergedScenarioDto("Customers EU", "found in the EU"),
                MergedScenarioDto("Customers US", "found in the US"),
                MergedScenarioDto("Customers US", "lost in the US"),
            )
        val report = MergeReportDto(copied, skipped = emptyList(), sourcesDeleted = true)
        assertEquals(200 to report, answer.status to answer.decoded<MergeReportDto>())
        val merged = services.single()
        assertEquals(destination.id, merged.id)
        assertEquals(listOf("found", "found in the EU", "found in the US", "lost in the US"), merged.scenarios.map { it.name })
        assertEquals(Tags.of("eu", "nightly") to listOf("http://eu.example.com/customers"), merged.tags to merged.realUrls)
    }

    /** A scenario that the service of the request has already is skipped, and then the sources stay. */
    @Test
    fun aMergeThatSkipsAScenarioKeepsItsSources() {
        val destination = service("Customers", "customer/{id}")
        scenario(destination, "found", """{"id":1}""")
        val source = service("Customers EU", "eu/customer/{id}")
        scenario(source, "Found", """{"id":1}""")
        scenario(source, "lost")

        val answer = send("POST", "services/${destination.id.value}/merge", """{"sourceIds":["${source.id.value}"]}""")

        val copied = listOf(MergedScenarioDto("Customers EU", "lost"))
        val report = MergeReportDto(copied, skipped = listOf(MergedScenarioDto("Customers EU", "Found")), sourcesDeleted = false)
        assertEquals(200 to report, answer.status to answer.decoded<MergeReportDto>())
        assertEquals(listOf("Customers", "Customers EU"), services.map { it.name })
        assertEquals(listOf("found", "lost"), current(destination).scenarios.map { it.name })
    }

    /** A source that is no service is a fault of the body, and so it is no resource that was not found. */
    @Test
    fun aMergeNeedsSourcesThatAreServices() {
        val destination = service("Customers", "customer/{id}")
        val source = service("Customers EU", "eu/customer/{id}")
        val path = "services/${destination.id.value}/merge"
        val before = engine.definitions

        val unknown = send("POST", path, """{"sourceIds":["${source.id.value}","$MISSING",""]}""")
        val without = send("POST", path, "{}")
        val noList = send("POST", path, """{"sourceIds":"${source.id.value}"}""")
        val none = send("POST", path, """{"sourceIds":[]}""")

        assertInvalid(unknown, "sourceIds[1]" to "No service has this identifier.", "sourceIds[2]" to "No service has this identifier.")
        assertInvalid(without, "sourceIds" to "The member is missing.")
        assertInvalid(noList, "sourceIds" to "The value has to be a list.")
        assertEquals(200 to MergeReportDto(emptyList(), emptyList(), sourcesDeleted = false), none.status to none.decoded<MergeReportDto>())
        assertEquals(before, engine.definitions)
    }

    // Derivation of real URLs

    /** Every real URL of every service gives one URL for each replacement, and the answer names the ones that were added. */
    @Test
    fun realUrlsAreDerivedForAllServicesAndTheAnswerNamesTheOnesThatWereAdded() {
        val customers = service("Customers", "customer/{id}") { it.copy(realUrls = listOf("http://qa1.example.com/customers")) }
        val ofOrders = listOf("http://qa1.example.com/orders", "http://qa2.example.com/orders")
        val orders = service("Orders", "orders") { it.copy(realUrls = ofOrders) }
        service("Billing", "billing")
        val request = """{"expression":"qa[0-9]","replacements":["qa2","qa3"]}"""

        val answer = send("POST", "services/real-urls/derive", request)
        val again = send("POST", "services/real-urls/derive", request)

        val derived =
            listOf(
                DerivedRealUrlDto(customers.id.value, "Customers", "http://qa2.example.com/customers"),
                DerivedRealUrlDto(customers.id.value, "Customers", "http://qa3.example.com/customers"),
                DerivedRealUrlDto(orders.id.value, "Orders", "http://qa3.example.com/orders"),
            )
        assertEquals(200 to derived, answer.status to answer.decoded<List<DerivedRealUrlDto>>())
        assertEquals(200 to emptyList(), again.status to again.decoded<List<DerivedRealUrlDto>>())
        assertEquals(
            listOf("http://qa1.example.com/customers", "http://qa2.example.com/customers", "http://qa3.example.com/customers"),
            current(customers).realUrls,
        )
        assertEquals(3, current(orders).realUrls.size)
    }

    @Test
    fun aDerivationNeedsAnExpressionAndAReplacement() {
        service("Customers", "customer/{id}") { it.copy(realUrls = listOf("http://qa1.example.com/customers")) }
        val before = engine.definitions
        val path = "services/real-urls/derive"

        val noExpression = send("POST", path, """{"expression":"qa(","replacements":["qa2"]}""")
        val noReplacement = send("POST", path, """{"expression":"qa1","replacements":[" "]}""")
        val incomplete = send("POST", path, """{"expression":"qa1"}""")

        assertInvalid(noExpression, "expression" to "The expression must be a regular expression that is not empty.")
        assertInvalid(noReplacement, "replacements" to "At least one replacement that is not blank is needed.")
        assertInvalid(incomplete, "replacements" to "The member is missing.")
        assertEquals(before, engine.definitions)
    }

    // The schema report

    /**
     * Task 12.7: what the response schema of a service says of the response bodies of its scenarios, one by
     * one and in their order. The report is there whether or not the schema is enabled.
     */
    @Test
    fun theSchemaReportSaysWhichScenariosConformToTheResponseSchemaOfTheirService() {
        val schema = """{"type": "object", "required": ["id"], "properties": {"id": {"type": "integer"}}}"""
        val defined = service("Customers", "customer/{id}") { it.copy(responseSchema = schema) }
        val found = scenario(defined, "found", """{"id": 7}""")
        val lost = scenario(defined, "lost", """{"name": "nobody"}""")
        val broken = scenario(defined, "broken", "no JSON")

        val answer = send("GET", "services/${defined.id.value}/schema-report")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        assertTrue(answer.body.startsWith("""{"type":"checked","scenarios":["""), answer.body)
        val report = answer.decoded<SchemaReportDto>() as SchemaReportDto.Checked
        val conformance = listOf(found.id.value to true, lost.id.value to false, broken.id.value to false)
        assertEquals(conformance, report.scenarios.map { it.scenarioId to it.conforms })
        assertEquals(listOf("found", "lost", "broken"), report.scenarios.map { it.scenarioName })
        assertEquals(listOf(true, false, false), report.scenarios.map { it.violations.isEmpty() })
        assertEquals(services, listOf(current(defined)))
    }

    /** A schema that cannot be applied is reported as such, and no scenario is checked against it. */
    @Test
    fun aSchemaReportSaysWhenTheSchemaCannotBeApplied() {
        val ofAnOldDialect = service("Customers", "customer/{id}") { it.copy(responseSchema = """{"${'$'}schema": "$DRAFT_3#"}""") }
        val ofNoSchema = service("Orders", "orders") { it.copy(responseSchema = "no JSON") }
        scenario(ofAnOldDialect, "found", "{}")
        scenario(ofNoSchema, "listed", "[]")

        val unsupported = send("GET", "services/${ofAnOldDialect.id.value}/schema-report")
        val unusable = send("GET", "services/${ofNoSchema.id.value}/schema-report")

        assertEquals(200 to """{"type":"unsupportedDialect","dialect":"$DRAFT_3"}""", unsupported.status to unsupported.body)
        assertEquals(SchemaReportDto.UnsupportedDialect(DRAFT_3), unsupported.decoded<SchemaReportDto>())
        val notRead = SchemaReportDto.UnusableSchema(listOf("The schema cannot be read as JSON."))
        assertEquals(200 to notRead, unusable.status to unusable.decoded<SchemaReportDto>())
    }

    /** The report is a resource of a service with a response schema. Without a schema there is nothing to report. */
    @Test
    fun aServiceWithoutAResponseSchemaHasNoSchemaReport() {
        val defined = service("Customers", "customer/{id}")
        scenario(defined, "found")

        val answer = send("GET", "services/${defined.id.value}/schema-report")
        val ofNoService = send("GET", "services/$MISSING/schema-report")
        val changing = send("POST", "services/${defined.id.value}/schema-report")

        assertProblem(answer, 404, "Not Found", "The service 'Customers' has no response schema.")
        assertNoService(ofNoService)
        assertEquals(405 to listOf("GET, HEAD"), changing.status to changing.headerValues("Allow"))
    }

    // A service that is not there

    /** The scenario "Unknown resource", for every operation on one service. */
    @Test
    fun anOperationOnAServiceThatIsNotThereGetsTheStatus404() {
        service("Customers", "customer/{id}")
        val before = engine.definitions

        val answers =
            listOf(
                send("GET", "services/$MISSING"),
                send("PATCH", "services/$MISSING", """{"delayMillis":250}"""),
                send("PATCH", "services/$MISSING", """{"delay":"not looked at"}"""),
                send("DELETE", "services/$MISSING"),
                send("POST", "services/$MISSING/duplicate"),
                send("POST", "services/$MISSING/merge", """{"sourceIds":[]}"""),
            )

        answers.forEach { assertNoService(it) }
        assertEquals(before, engine.definitions)
    }

    private companion object {
        const val MISSING = "no-such-identifier"

        /** A dialect of JSON Schema that is older than the ones that the engine reads, as a report names it. */
        const val DRAFT_3 = "http://json-schema.org/draft-03/schema"
    }
}
