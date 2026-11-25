package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.PlanReferenceDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.UnresolvedPlanScenarioDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 12.4: the operations of the admin API on plans and on the default plan, over real HTTP: on a
 * listener that runs the admin API with the operations on plans, for an engine of the test.
 *
 * A test sends its request to the listener, and looks at the answer and at what the engine holds afterwards.
 */
class AdminPlansTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminPlans(engine).routes)
    private val listener = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))

    /** The service `Customers`: in dynamic mode, with a delay of 250 ms, and with the scenarios `silver` and `gold`, the default one. */
    private val customers: Service =
        runBlocking {
            val service = engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }.getOrThrow()
            engine.scenarios.create(service.id) { it.copy(name = "silver") }.getOrThrow()
            val gold = engine.scenarios.create(service.id) { it.copy(name = "gold") }.getOrThrow()
            val dynamic = { current: Service ->
                current.copy(responseMode = ResponseMode.DYNAMIC, delayMillis = 250, defaultScenarioId = gold.id)
            }
            engine.services.update(service.id, dynamic).getOrThrow()
        }

    /** The service `Orders`: in proxy mode, without a delay, and without a scenario. */
    private val orders: Service = runBlocking { engine.services.create { it.copy(name = "Orders", mockUrl = "orders") }.getOrThrow() }

    private val itemOfCustomers = PlanItemDto("Customers", "gold", ResponseModeDto.DYNAMIC, 250)
    private val itemOfOrders = PlanItemDto("Orders")

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

    private val plans: List<Plan>
        get() = engine.definitions.plans

    private val services: List<Service>
        get() = engine.definitions.services

    /** Defines a plan in the engine, with the attributes that [define] gives it. */
    private fun plan(
        name: String,
        define: (Plan) -> Plan = { it },
    ): Plan = runBlocking { engine.plans.create { define(it.copy(name = name)) }.getOrThrow() }

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

    // Listing and reading

    @Test
    fun thePlansAreListedInTheOrderInWhichTheyWereCreatedAndOneIsReadByItsIdentifier() {
        val items = listOf(PlanItem("Customers", "gold", ResponseMode.STATIC, 250))
        val night = plan("Night") { it.copy(description = "For the night.", tags = Tags.of("nightly"), items = items) }
        val day = plan("Day")

        val listed = send("GET", "plans")
        val read = send("GET", "plans/${day.id.value}")

        assertEquals(200 to 200, listed.status to read.status)
        val ofTheNight = listOf(PlanItemDto("Customers", "gold", ResponseModeDto.STATIC, 250))
        assertEquals(
            listOf(PlanDto(night.id.value, "Night", "For the night.", listOf("nightly"), ofTheNight), PlanDto(day.id.value, "Day")),
            listed.decoded<List<PlanDto>>(),
        )
        assertEquals(PlanDto(day.id.value, "Day"), read.decoded<PlanDto>())
    }

    // Saving

    /**
     * The scenarios "Snapshot of the current settings" and "Plan with two items" of the plans, through the
     * API. The answer holds the plan with its identifier and says where it is. No service changes.
     */
    @Test
    fun aPlanIsSavedFromTheSettingsThatServicesHaveNowAndReturnedWithItsIdentifier() {
        val before = services
        val request =
            """
            {"name": " Gold path ", "serviceIds": ["${customers.id.value}", "${orders.id.value}"],
             "description": "The best customers.", "tags": ["Nightly team-a", "TEAM-A"]}
            """.trimIndent()

        val answer = send("POST", "plans", request)

        assertEquals("HTTP/1.1 201 Created", answer.statusLine, "$answer")
        val saved = answer.decoded<PlanDto>()
        assertEquals(listOf("/api/v1/plans/${saved.id}"), answer.headerValues("Location"))
        val expected =
            PlanDto(saved.id, "Gold path", "The best customers.", listOf("nightly", "team-a"), listOf(itemOfCustomers, itemOfOrders))
        assertEquals(expected, saved)
        assertEquals(listOf(saved), plans.map(Plan::toAdminDto))
        assertEquals(before, services)
    }

    /** The scenario "Name is generated when missing" of the plans. Each save without a name makes a plan of its own. */
    @Test
    fun aPlanThatIsSavedWithoutANameGetsAGeneratedOne() {
        val requests =
            listOf(
                """{"serviceIds": ["${customers.id.value}"]}""",
                """{"serviceIds": [], "name": "  "}""",
                """{"serviceIds": [], "name": null}""",
            )

        val answers = requests.map { send("POST", "plans", it) }

        assertEquals(List(3) { 201 }, answers.map { it.status })
        assertEquals(List(3) { "Plan (auto-generated-name)" }, plans.map { it.name })
        assertEquals(answers.map { it.decoded<PlanDto>().id }, plans.map { it.id.value })
        assertEquals(3, plans.map { it.id }.distinct().size)
        assertEquals(listOf(1, 0, 0), plans.map { it.items.size })
    }

    /**
     * The scenario "Saving under an existing name replaces the items" of the plans. The answer is no
     * creation. The plan takes the name as the request writes it, and it keeps what the request does not
     * state. A service that the request names twice gets one item.
     */
    @Test
    fun aSaveUnderTheNameOfAPlanUpdatesThatPlanAndCreatesNone() {
        val items = listOf(PlanItem("Billing", "paid", ResponseMode.STATIC, 5))
        val existing = plan("Gold path") { it.copy(description = "The best customers.", tags = Tags.of("nightly"), items = items) }
        val other = plan("Night")
        val twice = """{"name": "gold PATH", "serviceIds": ["${orders.id.value}", "${customers.id.value}", "${orders.id.value}"]}"""

        val saved = send("POST", "plans", twice)
        val withAttributes = send("POST", "plans", """{"name": " GOLD path ", "serviceIds": [], "description": " ", "tags": ["team-b"]}""")

        assertEquals("HTTP/1.1 200 OK", saved.statusLine, "$saved")
        assertEquals(emptyList(), saved.headerValues("Location"))
        val ofBoth = listOf(itemOfOrders, itemOfCustomers)
        assertEquals(PlanDto(existing.id.value, "gold PATH", "The best customers.", listOf("nightly"), ofBoth), saved.decoded<PlanDto>())
        assertEquals(200, withAttributes.status)
        assertEquals(PlanDto(existing.id.value, "GOLD path", null, listOf("team-b")), withAttributes.decoded<PlanDto>())
        assertEquals(listOf(withAttributes.decoded(), other.toAdminDto()), plans.map(Plan::toAdminDto))
    }

    /** A save that addresses a plan updates that plan, whatever it is called, and gives it the name of the request when there is one. */
    @Test
    fun aSaveIntoAPlanReplacesItsItemsAndRenamesItWhenTheRequestStatesAName() {
        val night = plan("Night") { it.copy(tags = Tags.of("nightly"), items = listOf(PlanItem("Billing"))) }
        val day = plan("Day")

        val saved = send("POST", "plans/${night.id.value}/save", """{"serviceIds": ["${customers.id.value}"]}""")
        val renamed = send("POST", "plans/${night.id.value}/save", """{"serviceIds": [], "name": "Day"}""")

        assertEquals("HTTP/1.1 200 OK", saved.statusLine, "$saved")
        assertEquals(emptyList(), saved.headerValues("Location"))
        assertEquals(PlanDto(night.id.value, "Night", null, listOf("nightly"), listOf(itemOfCustomers)), saved.decoded<PlanDto>())
        assertEquals(200, renamed.status)
        assertEquals(PlanDto(night.id.value, "Day", null, listOf("nightly")), renamed.decoded<PlanDto>())
        assertEquals(listOf(renamed.decoded(), day.toAdminDto()), plans.map(Plan::toAdminDto))
    }

    /** A service that is not there is an invalid member of the request, with a plan in the path and without one. */
    @Test
    fun aSaveNeedsServicesThatAreThere() {
        val night = plan("Night") { it.copy(items = listOf(PlanItem("Billing"))) }
        val ofUnknown = """{"name": "Gold path", "serviceIds": ["$MISSING", "${customers.id.value}", "Customers"]}"""
        val noService = "No service has this identifier."

        val underAName = send("POST", "plans", ofUnknown)
        val intoAPlan = send("POST", "plans/${night.id.value}/save", ofUnknown)
        val withoutServices = send("POST", "plans", """{"name": "Gold path"}""")
        val ofAnotherKind = send("POST", "plans", """{"serviceIds": "${customers.id.value}", "tags": "nightly", "items": []}""")

        assertInvalid(underAName, "serviceIds[0]" to noService, "serviceIds[2]" to noService)
        assertInvalid(intoAPlan, "serviceIds[0]" to noService, "serviceIds[2]" to noService)
        assertInvalid(withoutServices, "serviceIds" to "The member is missing.")
        assertInvalid(
            ofAnotherKind,
            "items" to "The request can have no member of this name here.",
            "serviceIds" to "The value has to be a list.",
            "tags" to "The value has to be a list.",
        )
        assertEquals(listOf(night), plans)
    }

    /** Of several saves of one new name at the same time, one creates the plan and says so, and the others update it. */
    @Test
    fun savesOfOneNewNameAtTheSameTimeCreateOnePlan() {
        val request = """{"name": "Gold path", "serviceIds": ["${customers.id.value}"]}"""
        val start = CountDownLatch(1)
        val clients = Executors.newFixedThreadPool(SAVES)
        val answers =
            try {
                val saves =
                    List(SAVES) {
                        clients.submit(
                            Callable {
                                start.await()
                                send("POST", "plans", request)
                            },
                        )
                    }
                start.countDown()
                saves.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                clients.shutdownNow()
            }

        assertEquals(listOf<Int?>(201) + List(SAVES - 1) { 200 }, answers.map { it.status }.sortedByDescending { it })
        assertEquals(listOf(PlanDto(plans.single().id.value, "Gold path", items = listOf(itemOfCustomers))), plans.map(Plan::toAdminDto))
        assertEquals(setOf(plans.single().toAdminDto()), answers.map { it.decoded<PlanDto>() }.toSet())
    }

    // Updating

    /** The requirement "Partial updates", for a plan. */
    @Test
    fun anUpdateChangesOnlyTheAttributesThatTheRequestStates() {
        val items = listOf(PlanItem("Customers", "gold", ResponseMode.STATIC, 250))
        val night = plan("Night") { it.copy(description = "For the night.", tags = Tags.of("nightly"), items = items) }

        val renamed = send("PATCH", "plans/${night.id.value}", """{"name": "Late night"}""")
        val afterTheName = plans.single()
        val tagged = send("PATCH", "plans/${night.id.value}", """{"tags": ["team-a"]}""")
        val unchanged = send("PATCH", "plans/${night.id.value}", "{}")

        assertEquals(listOf<Int?>(200, 200, 200), listOf(renamed.status, tagged.status, unchanged.status))
        assertEquals(night.copy(name = "Late night"), afterTheName)
        assertEquals(afterTheName.toAdminDto(), renamed.decoded<PlanDto>())
        assertEquals(night.copy(name = "Late night", tags = Tags.of("team-a")), plans.single())
        assertEquals(plans.single().toAdminDto(), unchanged.decoded<PlanDto>())
    }

    /**
     * A stated list of items takes the place of all items. An item states its service and takes a default
     * for what it leaves out, and it can name a service that is not there. A plan is stored in the form
     * that the engine gives it: without a name it gets a generated one, and an item without a service name
     * is left out. An update of a plan changes no service.
     */
    @Test
    fun anUpdateReplacesTheItemsOfAPlanAndTakesAwayWhatAPlanNeedNotHave() {
        val items = listOf(PlanItem("Billing", "paid", ResponseMode.STATIC, 5))
        val night = plan("Night") { it.copy(description = "For the night.", tags = Tags.of("nightly"), items = items) }
        val before = services
        val attributes =
            """
            {"name": "  ", "description": null, "tags": [],
             "items": [{"serviceName": " Customers ", "scenarioName": " silver ", "responseMode": "static", "delayMillis": 30},
                       {"serviceName": "No such service"},
                       {"serviceName": " ", "scenarioName": "gold"}]}
            """.trimIndent()

        val answer = send("PATCH", "plans/${night.id.value}", attributes)

        assertEquals(200, answer.status, "$answer")
        val stored = listOf(PlanItemDto("Customers", "silver", ResponseModeDto.STATIC, 30), PlanItemDto("No such service"))
        assertEquals(PlanDto(night.id.value, "Plan (auto-generated-name)", items = stored), answer.decoded<PlanDto>())
        assertEquals(listOf(answer.decoded()), plans.map(Plan::toAdminDto))
        assertEquals(before, services)
    }

    @Test
    fun anUpdateWithAnItemThatIsNotAsAnItemIsChangesNothing() {
        val night = plan("Night")
        val attributes =
            """
            {"name": "Late night", "id": "p1",
             "items": [{"scenarioName": "gold"}, {"serviceName": "Customers", "responseMode": "fixed", "delayMillis": "250"}, "Orders"]}
            """.trimIndent()

        val answer = send("PATCH", "plans/${night.id.value}", attributes)

        assertInvalid(
            answer,
            "id" to "The request can have no member of this name here.",
            "items[0].serviceName" to "The member is missing.",
            "items[1].responseMode" to "The value has to be one of proxy, static, dynamic.",
            "items[1].delayMillis" to "The value has to be a whole number.",
            "items[2]" to "The value has to be an object.",
        )
        assertEquals(listOf(night), plans)
    }

    // Applying

    /**
     * The scenario "Apply a plan", with the scenarios "Missing service" and "Missing scenario" of the plans:
     * the answer is the report. An item finds its service and its scenario without regard to letter case.
     */
    @Test
    fun aPlanIsAppliedAndTheAnswerNamesWhatCouldNotBeApplied() {
        val items =
            listOf(
                PlanItem("customers", "SILVER", ResponseMode.STATIC, 30),
                PlanItem("Billing", "paid", ResponseMode.STATIC, 5),
                PlanItem("Orders", "platinum", ResponseMode.DYNAMIC, 40),
            )
        val night = plan("Night") { it.copy(items = items) }

        val answer = send("POST", "plans/${night.id.value}/apply")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        val skipped = listOf(PlanItemDto("Billing", "paid", ResponseModeDto.STATIC, 5))
        val unresolved = listOf(UnresolvedPlanScenarioDto("Orders", "platinum"))
        assertEquals(PlanApplyReportDto(night.toAdminDto(), skipped, unresolved), answer.decoded<PlanApplyReportDto>())
        val silver = customers.scenarios.single { it.name == "silver" }
        val applied =
            listOf(
                customers.copy(responseMode = ResponseMode.STATIC, delayMillis = 30, defaultScenarioId = silver.id),
                orders.copy(responseMode = ResponseMode.DYNAMIC, delayMillis = 40),
            )
        assertEquals(applied, services)
        assertEquals(listOf(night), plans)
    }

    // Deletion

    /** The scenarios "Services keep their settings" and "Deleting the default plan" of the plans. */
    @Test
    fun aPlanIsDeletedWithItsDesignationAndNoServiceChanges() {
        val night = plan("Night") { it.copy(items = listOf(PlanItem("Customers", "silver", ResponseMode.STATIC, 30))) }
        val day = plan("Day")
        val prepared =
            listOf(send("POST", "plans/${night.id.value}/apply"), send("PUT", "default-plan", """{"planId": "${night.id.value}"}"""))
        val before = services

        val answer = send("DELETE", "plans/${night.id.value}")
        val again = send("DELETE", "plans/${night.id.value}")

        assertEquals(listOf<Int?>(200, 200), prepared.map { it.status })
        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(emptyList(), answer.headerValues("Content-Type") + answer.headerValues("Content-Length"))
        assertEquals(listOf(day), plans)
        assertNull(engine.definitions.settings.defaultPlanId)
        assertEquals(404, send("GET", "default-plan").status)
        assertEquals(ResponseMode.STATIC, before.first().responseMode)
        assertEquals(before, services)
        assertProblem(again, 404, "Not Found", "No plan has the identifier ${night.id.value}.")
    }

    // The default plan

    /** The default plan is a resource that is there while a plan is designated. Clearing it when there is none changes nothing. */
    @Test
    fun theDefaultPlanIsDesignatedReadAndCleared() {
        val night = plan("Night")
        val day = plan("Day")

        val before = send("GET", "default-plan")
        val designated = send("PUT", "default-plan", """{"planId": "${night.id.value}"}""")
        val read = send("GET", "default-plan")
        val another = send("PUT", "default-plan", """{"planId": "${day.id.value}"}""")
        val ofAnother = engine.definitions.settings.defaultPlanId
        val cleared = send("DELETE", "default-plan")
        val after = send("GET", "default-plan")
        val clearedAgain = send("DELETE", "default-plan")

        val notDesignated = "No plan is designated as the default plan."
        assertProblem(before, 404, "Not Found", notDesignated)
        assertEquals("HTTP/1.1 200 OK", designated.statusLine, "$designated")
        assertEquals(PlanReferenceDto(night.id.value), designated.decoded<PlanReferenceDto>())
        assertEquals(200 to PlanReferenceDto(night.id.value), read.status to read.decoded<PlanReferenceDto>())
        assertEquals(200 to PlanReferenceDto(day.id.value), another.status to another.decoded<PlanReferenceDto>())
        assertEquals(day.id, ofAnother)
        assertEquals("HTTP/1.1 204 No Content" to "", cleared.statusLine to cleared.body)
        assertProblem(after, 404, "Not Found", notDesignated)
        assertEquals(204, clearedAgain.status)
        assertNull(engine.definitions.settings.defaultPlanId)
        assertEquals(listOf(night, day), plans)
    }

    /** A plan that is not there is an invalid member of the request, and the designation stays as it is. */
    @Test
    fun aDesignationNeedsAPlanThatIsThere() {
        val night = plan("Night")
        val designated = send("PUT", "default-plan", """{"planId": "${night.id.value}"}""")

        val ofNoPlan = send("PUT", "default-plan", """{"planId": "$MISSING"}""")
        val ofTheName = send("PUT", "default-plan", """{"planId": "Night"}""")
        val withoutAPlan = send("PUT", "default-plan", "{}")
        val ofNull = send("PUT", "default-plan", """{"planId": null}""")

        assertEquals(200, designated.status)
        assertInvalid(ofNoPlan, "planId" to "No plan has this identifier.")
        assertInvalid(ofTheName, "planId" to "No plan has this identifier.")
        assertInvalid(withoutAPlan, "planId" to "The member is missing.")
        assertInvalid(ofNull, "planId" to "The member must have a value, and null is none.")
        assertEquals(night.id, engine.definitions.settings.defaultPlanId)
    }

    // A plan that is not there

    /** The scenario "Unknown resource", for every operation on one plan. A plan is found by its identifier, and not by its name. */
    @Test
    fun anOperationOnAPlanThatIsNotThereGetsTheStatus404() {
        plan("Night") { it.copy(items = listOf(PlanItem("Customers", "silver", ResponseMode.STATIC, 30))) }
        val before = engine.definitions

        val answers =
            listOf(
                send("GET", "plans/$MISSING"),
                send("PATCH", "plans/$MISSING", """{"name": "Late night"}"""),
                send("PATCH", "plans/$MISSING", """{"items": "not looked at"}"""),
                send("DELETE", "plans/$MISSING"),
                send("POST", "plans/$MISSING/save", """{"serviceIds": []}"""),
                send("POST", "plans/$MISSING/apply"),
            )
        val ofTheName = send("POST", "plans/Night/apply")

        answers.forEach { assertProblem(it, 404, "Not Found", "No plan has the identifier $MISSING.") }
        assertProblem(ofTheName, 404, "Not Found", "No plan has the identifier Night.")
        assertEquals(before, engine.definitions)
    }

    private companion object {
        const val MISSING = "no-such-identifier"

        /** The number of saves that a test sends at the same time. */
        const val SAVES = 8
    }
}
