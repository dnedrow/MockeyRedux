package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 12.3: the operations of the admin API on the scenarios of a service and on the universal error
 * scenario, over real HTTP: on a listener that runs the admin API with the operations on services and
 * scenarios, for an engine of the test.
 */
class AdminScenariosTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminServices(engine).routes + AdminScenarios(engine).routes)
    private val listener = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))

    /** The service `Customers`, which the scenarios of most tests belong to. */
    private val customers: Service = service("Customers", "customer/{id}")

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

    private fun service(
        name: String,
        mockUrl: String,
    ): Service = runBlocking { engine.services.create { it.copy(name = name, mockUrl = mockUrl) }.getOrThrow() }

    /** Defines a scenario of [service] in the engine, with the attributes that [define] gives it. */
    private fun scenario(
        name: String,
        service: Service = customers,
        define: (Scenario) -> Scenario = { it },
    ): Scenario = runBlocking { engine.scenarios.create(service.id) { define(it.copy(name = name, responseBody = name)) }.getOrThrow() }

    private fun current(service: Service = customers): Service = engine.definitions.services.single { it.id == service.id }

    /** The path of the scenarios of [service], and of the one with the identifier [scenarioId]. */
    private fun scenarios(
        service: Service = customers,
        scenarioId: String? = null,
    ): String = "services/${service.id.value}/scenarios" + scenarioId?.let { "/$it" }.orEmpty()

    private fun path(scenario: Scenario): String = scenarios(scenarioId = scenario.id.value)

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

    private fun assertInvalid(
        answer: RawHttp.Answer,
        vararg errors: Pair<String, String>,
    ) {
        val fields = errors.map { (field, message) -> FieldErrorDto(field, message) }
        val detail = fields.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
        assertProblem(answer, 422, "Unprocessable Entity", detail, fields)
    }

    // Creation

    /** The answer holds the scenario with its identifier and says where it is. The first scenario of a service is its default one. */
    @Test
    fun aScenarioIsCreatedWithTheAttributesOfTheRequestAndReturnedWithItsIdentifier() {
        val attributes =
            """
            {"name": " gefunden ✓ ", "responseBody": " {\"id\": 1}\n", "statusCode": 201,
             "headers": [{"name": "Set-Cookie", "value": "a=1"}, {"name": " X-Trace ", "value": " 7 "},
                         {"name": "Set-Cookie", "value": "b=2"}],
             "matchCriterion": {"type": "text", "text": "gold"}, "methodRestriction": "POST", "delayMillis": 250,
             "tags": ["Nightly", "team-a nightly"]}
            """.trimIndent()

        val answer = send("POST", scenarios(), attributes)
        val second = send("POST", scenarios(), """{"name":"lost","statusCode":404}""").decoded<ScenarioDto>()

        val created = answer.decoded<ScenarioDto>()
        assertEquals("HTTP/1.1 201 Created", answer.statusLine)
        assertEquals(listOf("/api/v1/services/${customers.id.value}/scenarios/${created.id}"), answer.headerValues("Location"))
        assertEquals(
            ScenarioDto(
                id = created.id,
                name = "gefunden ✓",
                responseBody = " {\"id\": 1}\n",
                statusCode = 201,
                headers = listOf(HeaderDto("Set-Cookie", "a=1"), HeaderDto("X-Trace", "7"), HeaderDto("Set-Cookie", "b=2")),
                matchCriterion = MatchCriterionDto.Text("gold"),
                methodRestriction = "POST",
                delayMillis = 250,
                tags = listOf("nightly", "team-a"),
            ),
            created,
        )
        assertEquals(listOf(created, second), current().scenarios.map { it.toAdminDto(engine.lastUsedTimes) })
        assertEquals(created.id, current().defaultScenarioId?.value)
        assertEquals(created, send("GET", scenarios(scenarioId = created.id)).decoded<ScenarioDto>())
    }

    /** A scenario has the defaults of `service-definitions` for what the request leaves out, and a name that is made for it. */
    @Test
    fun aScenarioIsCreatedWithTheDefaultsForWhatTheRequestLeavesOut() {
        val answer = send("POST", scenarios(), "{}")

        val created = answer.decoded<ScenarioDto>()
        val expected = ScenarioDto(created.id, "Scenario for Customers (name auto-generated)", "", 200, emptyList(), MatchCriterionDto.None)
        assertEquals(201 to expected, answer.status to created)
    }

    @Test
    fun aScenarioIsCreatedWithACriterionOfEachKind() {
        val rules = """{"url":[{"value_rule_arg":"44","value_rule_type":"string_required"}]}"""
        val criteria =
            listOf(
                """{"type":"none"}""" to MatchCriterionDto.None,
                """{"type":"text","text":"gold"}""" to MatchCriterionDto.Text("gold"),
                """{"type":"rules","source":${AdminApiJson.instance.encodeToString(rules)}}""" to MatchCriterionDto.Rules(rules),
            )

        val created = criteria.map { (criterion, _) -> send("POST", scenarios(), """{"matchCriterion":$criterion}""") }

        assertEquals(List(3) { 201 }, created.map { it.status })
        assertEquals(criteria.map { it.second }, created.map { it.decoded<ScenarioDto>().matchCriterion })
        assertEquals(MatchCriterion.Rules(rules), current().scenarios.last().matchCriterion)
    }

    /** The engine says what a scenario has to be like, and a text criterion cannot be blank at all. */
    @Test
    fun aScenarioThatBreaksTheRulesForScenariosIsNotCreated() {
        val before = engine.definitions

        val longName = send("POST", scenarios(), """{"name":"${"n".repeat(251)}"}""")
        val noRules = send("POST", scenarios(), """{"matchCriterion":{"type":"rules","source":"{"}}""")
        val blank = send("POST", scenarios(), """{"matchCriterion":{"type":"text","text":"  "}}""")
        val noAttribute = send("POST", scenarios(), """{"id":"s-1","serviceId":"${customers.id.value}"}""")

        assertInvalid(longName, "name" to "The name of a scenario must not be longer than 250 characters.")
        val error = noRules.decoded<ProblemDto>().errors.single()
        assertEquals(422 to "matchCriterion", noRules.status to error.field)
        assertTrue(error.message.startsWith("The match criterion is not a well-formed rule set."), "$error")
        assertInvalid(blank, "matchCriterion.text" to "The text of a text criterion is not blank.")
        val unknown = "The request can have no member of this name here."
        assertInvalid(noAttribute, "id" to unknown, "serviceId" to unknown)
        assertEquals(before, engine.definitions)
    }

    // Reading

    @Test
    fun theScenariosOfAServiceAreListedInTheOrderInWhichTheyWereCreated() {
        val found = scenario("found")
        val lost = scenario("lost") { it.copy(statusCode = 404, headers = listOf(Header("X-Trace", "7"))) }
        scenario("listed", service("Orders", "orders"))

        val listed = send("GET", scenarios())
        val one = send("GET", path(lost))
        val none = send("GET", scenarios(service("Billing", "billing")))

        val times = engine.lastUsedTimes
        assertEquals(200 to listOf(found, lost).map { it.toAdminDto(times) }, listed.status to listed.decoded<List<ScenarioDto>>())
        assertEquals(200 to lost.toAdminDto(times), one.status to one.decoded<ScenarioDto>())
        assertEquals(200 to "[]", none.status to none.body)
    }

    // Update

    /** The requirement "Partial updates", for a scenario. */
    @Test
    fun anUpdateChangesOnlyTheAttributesThatTheRequestStates() {
        val defined =
            scenario("found") {
                it.copy(
                    statusCode = 201,
                    headers = listOf(Header("Content-Type", "application/json"), Header("X-Trace", "7")),
                    matchCriterion = MatchCriterion.Text("gold"),
                    methodRestriction = "POST",
                    delayMillis = 250,
                    tags = Tags.of("nightly"),
                )
            }

        val body = send("PATCH", path(defined), """{"responseBody":"{\"id\": 2}"}""")
        val afterBody = current().scenarios.single()
        val changes = """{"methodRestriction":null,"headers":[],"matchCriterion":{"type":"none"},"name":"lost"}"""
        val several = send("PATCH", path(defined), changes)

        assertEquals(200 to defined.copy(responseBody = """{"id": 2}"""), body.status to afterBody)
        assertEquals(afterBody.toAdminDto(engine.lastUsedTimes), body.decoded<ScenarioDto>())
        val expected = afterBody.copy(methodRestriction = null, headers = emptyList(), matchCriterion = MatchCriterion.None, name = "lost")
        assertEquals(200 to expected, several.status to current().scenarios.single())
        assertEquals(expected.toAdminDto(engine.lastUsedTimes), several.decoded<ScenarioDto>())
    }

    /** The whole scenario is checked after an update, and one that breaks a rule stays as it was. */
    @Test
    fun anUpdateThatBreaksTheRulesForScenariosChangesNothing() {
        val defined = scenario("found")
        val before = engine.definitions

        val noRules = send("PATCH", path(defined), """{"matchCriterion":{"type":"rules","source":"[]"},"statusCode":404}""")
        val blank = send("PATCH", path(defined), """{"matchCriterion":{"type":"text","text":""}}""")
        val noAttribute = send("PATCH", path(defined), """{"id":"s-1","statusCode":404}""")

        assertEquals(422 to listOf("matchCriterion"), noRules.status to noRules.decoded<ProblemDto>().errors.map { it.field })
        assertInvalid(blank, "matchCriterion.text" to "The text of a text criterion is not blank.")
        assertInvalid(noAttribute, "id" to "The request can have no member of this name here.")
        assertEquals(before, engine.definitions)
    }

    // Deletion

    /** What designated the scenario designates it no longer, and the first of the remaining scenarios is the default scenario. */
    @Test
    fun aScenarioIsDeletedAndItsDesignationsEnd() {
        val found = scenario("found")
        val failed = scenario("failed")
        val lost = scenario("lost")
        runBlocking {
            engine.services.update(customers.id) { it.copy(defaultScenarioId = failed.id, errorScenarioId = failed.id) }.getOrThrow()
            engine.scenarios.designateUniversalError(ScenarioReference(customers.id, failed.id)).getOrThrow()
        }

        val answer = send("DELETE", path(failed))
        val again = send("DELETE", path(failed))

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(listOf(found, lost), current().scenarios)
        assertEquals(found.id to null, current().let { it.defaultScenarioId to it.errorScenarioId })
        assertNull(engine.definitions.settings.universalErrorScenario)
        assertProblem(again, 404, "Not Found", "The service 'Customers' has no scenario with the identifier ${failed.id.value}.")
    }

    // What is not there

    /** The scenario "Unknown resource", for every operation on a scenario: a scenario is one of its service, and of no other. */
    @Test
    fun anOperationOnAScenarioThatIsNotThereGetsTheStatus404() {
        val ofOrders = scenario("listed", service("Orders", "orders"))
        scenario("found")
        val before = engine.definitions
        val noService = "No service has the identifier $MISSING."
        val noScenario = "The service 'Customers' has no scenario with the identifier $MISSING."
        val ofAnother = "The service 'Customers' has no scenario with the identifier ${ofOrders.id.value}."
        val requests =
            listOf(
                Triple("GET", "services/$MISSING/scenarios", "") to noService,
                Triple("POST", "services/$MISSING/scenarios", """{"name":"found"}""") to noService,
                Triple("GET", "services/$MISSING/scenarios/${ofOrders.id.value}", "") to noService,
                Triple("GET", scenarios(scenarioId = MISSING), "") to noScenario,
                Triple("PATCH", scenarios(scenarioId = MISSING), """{"statusCode":404}""") to noScenario,
                Triple("PATCH", scenarios(scenarioId = MISSING), """{"status":"not looked at"}""") to noScenario,
                Triple("DELETE", scenarios(scenarioId = MISSING), "") to noScenario,
                Triple("GET", scenarios(scenarioId = ofOrders.id.value), "") to ofAnother,
                Triple("PATCH", scenarios(scenarioId = ofOrders.id.value), """{"statusCode":404}""") to ofAnother,
                Triple("DELETE", scenarios(scenarioId = ofOrders.id.value), "") to ofAnother,
            )

        for ((request, detail) in requests) {
            assertProblem(send(request.first, request.second, request.third), 404, "Not Found", detail)
        }
        assertEquals(before, engine.definitions)
    }

    // A service with a scenario

    /** The scenario "Create a service with a scenario and select it". */
    @Test
    fun aClientCreatesAServiceWithAScenarioAndSelectsStaticMode() {
        val service = send("POST", "services", """{"name":"Orders","mockUrl":"orders"}""")
        val id = service.decoded<ServiceDto>().id
        val scenario = send("POST", "services/$id/scenarios", """{"name":"listed","responseBody":"[]"}""")
        val updated = send("PATCH", "services/$id", """{"responseMode":"static"}""")

        val read = send("GET", "services/$id").decoded<ServiceDto>()

        assertEquals(listOf<Int?>(201, 201, 200), listOf(service.status, scenario.status, updated.status))
        assertEquals(scenario.decoded<ScenarioDto>().id, read.defaultScenarioId)
        assertEquals("static" to "static", read.responseMode.name.lowercase() to read.effectiveResponseMode.name.lowercase())
        assertEquals(ResponseMode.STATIC, current(engine.definitions.services.last()).effectiveResponseMode)
    }

    // The universal error scenario

    /** The universal error scenario is a resource that is there while a scenario is designated. */
    @Test
    fun theUniversalErrorScenarioIsDesignatedReadAndCleared() {
        val failed = scenario("failed")
        val reference = """{"serviceId":"${customers.id.value}","scenarioId":"${failed.id.value}"}"""
        val expected = ScenarioReferenceDto(customers.id.value, failed.id.value)

        val before = send("GET", UNIVERSAL)
        val designated = send("PUT", UNIVERSAL, reference)
        val read = send("GET", UNIVERSAL)
        val inTheEngine = engine.definitions.settings.universalErrorScenario
        val cleared = send("DELETE", UNIVERSAL)
        val clearedAgain = send("DELETE", UNIVERSAL)
        val after = send("GET", UNIVERSAL)

        assertProblem(before, 404, "Not Found", NOT_DESIGNATED)
        assertEquals(200 to expected, designated.status to designated.decoded<ScenarioReferenceDto>())
        assertEquals(200 to expected, read.status to read.decoded<ScenarioReferenceDto>())
        assertEquals(ScenarioReference(customers.id, failed.id), inTheEngine)
        assertEquals(List(2) { "HTTP/1.1 204 No Content" to "" }, listOf(cleared, clearedAgain).map { it.statusLine to it.body })
        assertNull(engine.definitions.settings.universalErrorScenario)
        assertProblem(after, 404, "Not Found", NOT_DESIGNATED)
    }

    /** Another scenario takes the place of the one that was designated: there is one universal error scenario at most. */
    @Test
    fun aDesignationTakesThePlaceOfTheOneBefore() {
        val failed = scenario("failed")
        val down = scenario("down", service("Orders", "orders"))
        val orders = engine.definitions.services.last()

        send("PUT", UNIVERSAL, """{"serviceId":"${customers.id.value}","scenarioId":"${failed.id.value}"}""")
        val second = send("PUT", UNIVERSAL, """{"scenarioId":"${down.id.value}","serviceId":"${orders.id.value}"}""")

        assertEquals(200, second.status)
        assertEquals(ScenarioReference(orders.id, down.id), engine.definitions.settings.universalErrorScenario)
    }

    /** What the request names is in its body. So a service or a scenario that is not there is a fault of the body. */
    @Test
    fun aDesignationNeedsAScenarioOfAService() {
        val failed = scenario("failed")
        val ofOrders = scenario("down", service("Orders", "orders"))

        val noService = send("PUT", UNIVERSAL, """{"serviceId":"$MISSING","scenarioId":"${failed.id.value}"}""")
        val noScenario = send("PUT", UNIVERSAL, """{"serviceId":"${customers.id.value}","scenarioId":"$MISSING"}""")
        val ofAnother = send("PUT", UNIVERSAL, """{"serviceId":"${customers.id.value}","scenarioId":"${ofOrders.id.value}"}""")
        val blank = send("PUT", UNIVERSAL, """{"serviceId":"","scenarioId":" "}""")
        val incomplete = send("PUT", UNIVERSAL, """{"scenarioId":"${failed.id.value}"}""")
        val nothing = send("PUT", UNIVERSAL)

        assertInvalid(noService, "serviceId" to "No service has this identifier.")
        assertInvalid(noScenario, "scenarioId" to "The service 'Customers' has no scenario with this identifier.")
        assertInvalid(ofAnother, "scenarioId" to "The service 'Customers' has no scenario with this identifier.")
        assertInvalid(blank, "serviceId" to "No service has this identifier.")
        assertInvalid(incomplete, "serviceId" to "The member is missing.")
        assertProblem(nothing, 400, "Bad Request", "The request has no body. The operation takes a JSON object.")
        assertNull(engine.definitions.settings.universalErrorScenario)
    }

    private companion object {
        const val MISSING = "no-such-identifier"
        const val UNIVERSAL = "universal-error-scenario"
        const val NOT_DESIGNATED = "No scenario is designated as the universal error scenario."
    }
}
