package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 14.3: the endpoint `/plan/setup` of the legacy shim, over real HTTP. A test starts from the services
 * and the plans of the parity fixture `automation.xml`, as the recordings of task 2.8 do, and it holds an
 * answer against the one that a recording states for the new server.
 */
class ShimPlansTest {
    private val started = mutableListOf<ShimUnderTest>()
    private val levelBefore = ServerLogging.level()

    @AfterTest
    fun stopEverything() {
        started.forEach(ShimUnderTest::close)
        ServerLogging.configure(levelBefore)
    }

    /** Starts a mock listener with the legacy shim for an engine that has the services and the plans of the fixture. */
    private fun script(): ShimUnderTest {
        val script = ShimUnderTest.start().also { started += it }
        ShimFixture.services(script.engine)
        ShimFixture.plans(script.engine)
        return script
    }

    private fun ShimUnderTest.planNamed(name: String): Plan = definitions.plans.single { it.name == name }

    private fun ShimUnderTest.serviceNamed(name: String): Service = definitions.services.single { it.name == name }

    /** Returns the identifier of the default plan, or `null` when there is none. */
    private fun ShimUnderTest.defaultPlan(): String? = definitions.settings.defaultPlanId?.value

    private fun ShimUnderTest.idOf(service: String): String = serviceNamed(service).id.value

    /** Returns the result of the answer to a `GET` request for `/plan/setup` with [query]. */
    private fun ShimUnderTest.setUp(query: String): JsonObject = document("/plan/setup?$query").getValue("result").jsonObject

    /** Returns the result that states an action on [plan] that succeeded, as the recordings state it for the new server. */
    private fun succeeded(
        text: String,
        plan: Plan,
    ): JsonObject {
        val id = JsonPrimitive(plan.id.value)
        return JsonObject(mapOf("success" to JsonPrimitive(text), "planName" to JsonPrimitive(plan.name), "planid" to id, "planId" to id))
    }

    private fun failed(text: String): JsonObject = JsonObject(mapOf("fail" to JsonPrimitive(text)))

    /**
     * The recordings `automation-plan-set-by-name` and `automation-plan-set-transient-state`, and the
     * scenario "Apply a plan by name": the plan is applied, and the result names it under both spellings
     * of its identifier (DEV-48). The parameter `transient_state` changes nothing (DEV-22).
     */
    @Test
    fun aPlanIsAppliedByItsName() {
        val byName = "action=set_plan&service_plan_name=Gold%20path"
        for (query in listOf(byName, "$byName&transient_state=true")) {
            val script = script()
            val before = script.definitions
            val gold = script.planNamed("Gold path")

            val answer = script.get("/plan/setup?$query")

            val customers = script.serviceNamed("Customers")
            val orders = script.serviceNamed("Orders")
            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals(succeeded("Service plan Gold path set", gold), documentOf(answer.body).getValue("result"), query)
            assertEquals(setOf("result"), documentOf(answer.body).keys, query)
            assertEquals("gold", customers.scenarios.single { it.id == customers.defaultScenarioId }.name, query)
            assertEquals("closed", orders.scenarios.single { it.id == orders.defaultScenarioId }.name, query)
            assertEquals(ResponseMode.STATIC to 0L, orders.responseMode to orders.delayMillis, query)
            assertEquals(before.plans to before.settings, script.definitions.let { it.plans to it.settings }, query)
            assertTrue(script.engine.readOnly, query)
        }
    }

    /** The recording `automation-plan-set-effect`: after a plan was applied, a service answers as the plan has it. */
    @Test
    fun aServiceAnswersAsTheAppliedPlanHasIt() {
        val script = script()

        val before = script.get("/service/customers").body
        script.get("/plan/setup?action=set_plan&service_plan_name=Gold%20path")
        val after = script.get("/service/customers").body

        assertEquals("silver customer" to "gold customer", before to after)
    }

    /**
     * The recordings `automation-plan-set-by-id` and `automation-plan-set-unknown`: a number of Mockey 2.x
     * names no plan (DEV-20), and a request to apply a plan that isn't there gets a result with `fail`,
     * where Mockey 2.x answered with its home page (DEV-50). Nothing is changed.
     */
    @Test
    fun aRequestToApplyAPlanThatIsNotThereGetsAResultThatSaysSo() {
        val script = script()
        val before = script.definitions
        val text = "Service plan not set. No plan has the plan_id or the service_plan_name of the request."

        for (query in listOf("plan_id=2", "plan_id=99", "service_plan_name=Bronze%20path", "service_plan_name=", "transient_state=true")) {
            val answer = script.get("/plan/setup?action=set_plan&$query")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals("""{"result":{"fail":"$text"}}""", answer.body, query)
        }
        assertEquals("""{"result":{"fail":"$text"}}""", script.get("/plan/setup?action=set_plan").body)
        assertEquals(before, script.definitions)
    }

    /**
     * The recording `automation-plan-delete`: the plan is gone, and the result names it under both
     * spellings of its identifier (DEV-48), with the text of Mockey 2.x.
     */
    @Test
    fun aPlanIsDeletedByItsName() {
        val script = script()
        val before = script.definitions
        val silver = script.planNamed("Silver path")

        val answer = script.get("/plan/setup?action=delete_plan&service_plan_name=Silver%20path")

        assertEquals(succeeded("Service plan 'Silver path' deleted", silver), documentOf(answer.body).getValue("result"))
        assertEquals(before.copy(plans = before.plans.filter { it.id != silver.id }), script.definitions)
    }

    /**
     * The recording `automation-plan-delete-unknown`: the result has the text of Mockey 2.x, which sends
     * its reader to the log, and so the log says what happened.
     */
    @Test
    fun aRequestToDeleteAPlanThatIsNotThereGetsTheTextOfMockey2xAndALineInTheLog() {
        val script = script()
        val before = script.definitions
        ServerLogging.configure(LogLevel.INFO)
        val answers = mutableListOf<String>()

        val log =
            TestLog.of {
                for (query in listOf("plan_id=99", "service_plan_name=Bronze%20path", "transient_state=true")) {
                    answers += script.get("/plan/setup?action=delete_plan&$query").body
                }
            }

        val text = "Service plan not deleted. Please check your logs for insight."
        assertEquals(List(3) { """{"result":{"fail":"$text"}}""" }, answers)
        assertEquals(before, script.definitions)
        val line = "A request for /plan/setup of the legacy shim deleted no plan, because it names no plan that is there."
        assertEquals(List(3) { line }, TestLog.messages(log, "INFO"))
        assertFalse("Bronze" in log, log)
    }

    /**
     * The recording `automation-plan-default`: the plan is the default plan, and the result names it under
     * both spellings of its identifier (DEV-48), with the text of Mockey 2.x.
     */
    @Test
    fun aPlanIsDesignatedAsTheDefaultPlanByItsName() {
        val script = script()
        val before = script.definitions
        val gold = script.planNamed("Gold path")

        val answer = script.get("/plan/setup?action=set_as_default_plan&service_plan_name=Gold%20path")

        assertEquals(succeeded("Set as Default Service Plan", gold), documentOf(answer.body).getValue("result"))
        assertEquals(before.copy(settings = before.settings.copy(defaultPlanId = gold.id)), script.definitions)
    }

    /**
     * The recording `automation-plan-default-none`, and the scenario "Remove the default plan": with `none`
     * for `plan_id`, there is no default plan, and the result has the text of Mockey 2.x and nothing else.
     * That holds whatever else the request names, and when there was no default plan before.
     */
    @Test
    fun noneForThePlanEndsTheDesignationOfTheDefaultPlan() {
        val script = script()
        val before = script.definitions
        val gold = script.planNamed("Gold path")

        for (query in listOf("plan_id=none", "plan_id=NONE&service_plan_name=Gold%20path", "plan_id=None&transient_state=true")) {
            runBlocking { script.engine.plans.designateDefault(gold.id) }

            val answer = script.get("/plan/setup?action=set_as_default_plan&$query")

            assertEquals("""{"result":{"success":"Removed default plan."}}""", answer.body, query)
            assertNull(script.defaultPlan(), query)
        }
        val withoutADefaultPlan = script.get("/plan/setup?action=set_as_default_plan&plan_id=none")
        assertEquals("""{"result":{"success":"Removed default plan."}}""", withoutADefaultPlan.body)
        assertEquals(before, script.definitions)
    }

    /**
     * The recording `automation-plan-default-unknown`: a request to designate a plan that isn't there gets
     * the text of Mockey 2.x, and the default plan stays the one that it was.
     */
    @Test
    fun aRequestToDesignateAPlanThatIsNotThereGetsTheTextOfMockey2x() {
        val script = script()
        val gold = script.planNamed("Gold path")
        runBlocking { script.engine.plans.designateDefault(gold.id) }
        val before = script.definitions
        val text = "Unable to set Default Service Plan. Unknown Service Plan ID."

        for (query in listOf("plan_id=99", "service_plan_name=Bronze%20path", "plan_id=+none", "plan_id=nothing", "transient_state=true")) {
            val answer = script.get("/plan/setup?action=set_as_default_plan&$query")

            assertEquals("""{"result":{"fail":"$text"}}""", answer.body, query)
        }
        assertEquals(before, script.definitions)
    }

    /**
     * The three recordings of `save_plan` name their services by the numbers of Mockey 2.x, which name no
     * service here (DEV-20). So they state no more than the envelope, and the shim answers them with a
     * result that says which service isn't there. Nothing is saved, also not for the services that are
     * there.
     */
    @Test
    fun aRequestToSaveAPlanForAServiceThatIsNotThereGetsAResultThatSaysSo() {
        val script = script()
        val before = script.definitions
        val orders = script.idOf("Orders")
        val save = "action=save_plan&type=json"
        val missing =
            mapOf(
                "$save&service_plan_name=gold%20path&service_ids%5B%5D=3" to "No service has the identifier 3.",
                "$save&service_plan_name=Evening&service_plan_tag=night&service_ids%5B%5D=1&service_ids%5B%5D=2" to
                    "No service has the identifier 1.",
                "$save&service_ids%5B%5D=1" to "No service has the identifier 1.",
                "$save&service_plan_name=Evening&service_ids%5B%5D=$orders&service_ids%5B%5D=7" to "No service has the identifier 7.",
                "$save&service_plan_name=Evening&service_ids%5B%5D=$orders&service_ids%5B%5D=" to "A value of service_ids[] is empty.",
                "$save&service_plan_name=Evening&service_ids%5B%5D=++" to "A value of service_ids[] is empty.",
            )

        for ((query, reason) in missing) {
            val answer = script.get("/plan/setup?$query")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals("""{"result":{"fail":"Service plan not saved. $reason"}}""", answer.body, query)
        }
        // An identifier counts as it's written. One without a letter is the same in upper case.
        val shouted = orders.uppercase()
        if (shouted != orders) assertEquals(setOf("fail"), script.setUp("$save&service_ids%5B%5D=$shouted").keys)
        assertEquals(before, script.definitions)
    }

    /**
     * What `automation-plan-save-new` asks for, with the identifiers of this server, and the scenario "Both
     * identifier spellings are present": a new plan has the name and the tags of the request, and one item
     * for each service, which holds the settings that the service has now.
     */
    @Test
    fun aNewPlanHoldsTheSettingsThatTheServicesHaveNow() {
        val script = script()
        val before = script.definitions
        script.get("/config/service?serviceName=Customers&scenarioName=gold&hangTime=30")
        val configured = script.definitions
        val services = "service_ids%5B%5D=${script.idOf("Customers")}&service_ids%5B%5D=${script.idOf("Orders")}"

        val result = script.setUp("action=save_plan&type=json&service_plan_name=Evening&service_plan_tag=night+Shift&$services")

        val evening = script.planNamed("Evening")
        val items = listOf(PlanItem("Customers", "gold", ResponseMode.STATIC, 30), PlanItem("Orders", "open", ResponseMode.DYNAMIC, 100))
        assertEquals(succeeded("Service plan Evening saved", evening), result)
        assertEquals(Plan(evening.id, "Evening", tags = Tags.of("night", "shift"), items = items), evening)
        assertEquals(configured.copy(plans = configured.plans + evening), script.definitions)
        assertEquals(before.plans, script.definitions.plans.dropLast(1))
    }

    /**
     * What `automation-plan-save-existing-name` asks for, with an identifier of this server: a save under
     * the name of a plan, in any letter case, replaces the items of that plan and takes the spelling of the
     * request. The plan keeps its identifier and what the request doesn't mention.
     */
    @Test
    fun aSaveUnderTheNameOfAPlanUpdatesThatPlan() {
        val script = script()
        val before = script.definitions
        val gold = script.planNamed("Gold path")

        val result = script.setUp("action=save_plan&type=json&service_plan_name=gold%20path&service_ids%5B%5D=${script.idOf("Billing")}")

        val saved = script.definitions.plans.single { it.id == gold.id }
        val items = listOf(PlanItem("Billing", null, ResponseMode.PROXY, 0))
        assertEquals(succeeded("Service plan gold path saved", saved), result)
        assertEquals(gold.copy(name = "gold path", items = items), saved)
        assertEquals(before.copy(plans = listOf(saved) + before.plans.drop(1)), script.definitions)
    }

    /**
     * What `automation-plan-save-without-name` asks for, with an identifier of this server: a new plan
     * without a name gets the name that Mockey 2.x gave it. As in Mockey 2.x, every save without a name makes
     * a plan of its own.
     */
    @Test
    fun aNewPlanWithoutANameGetsTheNameOfMockey2x() {
        val script = script()

        val result = script.setUp("action=save_plan&type=json&service_ids%5B%5D=${script.idOf("Customers")}")
        val blank = script.setUp("action=save_plan&service_plan_name=++&service_ids%5B%5D=${script.idOf("Orders")}")

        val plans = script.definitions.plans
        val generated = "Plan (auto-generated-name)"
        assertEquals(listOf("Gold path", "Silver path", generated, generated), plans.map { it.name })
        assertEquals(succeeded("Service plan $generated saved", plans[2]), result)
        assertEquals(succeeded("Service plan $generated saved", plans[3]), blank)
        assertEquals(listOf(listOf("Customers"), listOf("Orders")), plans.drop(2).map { plan -> plan.items.map { it.serviceName } })
    }

    /**
     * The recording `automation-plan-saved-plan-applies`, as it is and with an identifier of this server.
     * As it is, the save names its service by a number of Mockey 2.x, so no plan is saved and none is
     * applied, and the service answers as it was configured last. With the identifier, the plan puts the
     * service back into the state that it had when the plan was saved, as in Mockey 2.x.
     */
    @Test
    fun aSavedPlanPutsItsServicesBackIntoTheStateThatTheyHad() {
        fun answerAfter(
            script: ShimUnderTest,
            service: String,
        ): String {
            script.get("/config/service?serviceName=Customers&scenarioName=gold")
            script.get("/plan/setup?action=save_plan&type=json&service_plan_name=Evening&service_plan_tag=night&service_ids%5B%5D=$service")
            script.get("/config/service?serviceName=Customers&scenarioName=silver")
            script.get("/plan/setup?action=set_plan&service_plan_name=Evening")
            return script.get("/service/customers").body
        }

        val recorded = script()
        val withIdentifier = script()

        assertEquals("silver customer", answerAfter(recorded, "1"))
        assertEquals("gold customer", answerAfter(withIdentifier, withIdentifier.idOf("Customers")))
    }

    /**
     * The requirement "Identifiers in legacy endpoints", for plans: the identifier that a result states is
     * the one that a request takes as `plan_id`, for every action. An identifier counts before a name, and
     * when it names no plan, the name counts.
     */
    @Test
    fun aPlanIsNamedByTheIdentifierThatAResultStates() {
        val script = script()
        val saved = script.setUp("action=save_plan&service_plan_name=Evening&service_ids%5B%5D=${script.idOf("Orders")}")
        val id = saved.text("planId")

        assertEquals(saved.text("planid"), id)
        assertEquals(script.planNamed("Evening").id.value, id)
        assertEquals("Service plan Evening set", script.setUp("action=set_plan&plan_id=$id").text("success"))
        assertEquals("Service plan Evening set", script.setUp("action=set_plan&plan_id=$id&service_plan_name=Gold%20path").text("success"))
        assertEquals("Service plan Gold path set", script.setUp("action=set_plan&plan_id=2&service_plan_name=Gold%20path").text("success"))
        assertEquals("Set as Default Service Plan", script.setUp("action=set_as_default_plan&plan_id=$id").text("success"))
        assertEquals(id, script.defaultPlan())
        val renamed = script.setUp("action=save_plan&plan_id=$id&service_plan_name=Night")
        assertEquals(listOf("Service plan Night saved", id, "Night"), listOf("success", "planId", "planName").map(renamed::text))
        assertEquals(emptyList(), script.planNamed("Night").items)
        assertEquals("Service plan 'Night' deleted", script.setUp("action=delete_plan&plan_id=$id").text("success"))
        assertEquals(listOf("Gold path", "Silver path"), script.definitions.plans.map { it.name })
        // The default plan is gone with its designation.
        assertNull(script.defaultPlan())
        // An identifier counts as it's written. One without a letter is the same in upper case.
        val gold = script.planNamed("Gold path").id.value
        if (gold.uppercase() != gold) assertTrue("fail" in script.setUp("action=set_plan&plan_id=${gold.uppercase()}"))
    }

    /**
     * A plan is found by its name as Mockey 2.x found it: without regard to letter case and to the spaces
     * around the name. Of two plans with the name, the one that was created first counts.
     */
    @Test
    fun aPlanIsFoundByItsNameWithoutRegardToLetterCase() {
        val script = script()
        val plans = script.engine.plans
        val twin = runBlocking { plans.create { it.copy(name = "gold PATH") }.getOrThrow() }
        val gold = script.planNamed("Gold path")

        for (name in listOf("Gold%20path", "GOLD+PATH", "++gold+path+", "gold%20PATH")) {
            assertEquals(succeeded("Service plan Gold path set", gold), script.setUp("action=set_plan&service_plan_name=$name"), name)
        }
        assertTrue("fail" in script.setUp("action=set_plan&service_plan_name=Gold"))
        assertEquals(succeeded("Service plan 'Gold path' deleted", gold), script.setUp("action=delete_plan&service_plan_name=gold+path"))
        assertEquals(succeeded("Service plan gold PATH set", twin), script.setUp("action=set_plan&service_plan_name=gold+path"))
    }

    /**
     * The tags of a plan are the words of `service_plan_tag`. A save without the parameter leaves the tags
     * of a plan as they are, and an empty one removes them. The services are also named in a form, and with
     * the brackets of the parameter name as they are.
     */
    @Test
    fun theTagsAndTheServicesOfASaveAreReadAsTheRequestGivesThem() {
        val script = script()
        val customers = script.idOf("Customers")
        val orders = script.idOf("Orders")

        fun goldAfter(answer: RawHttp.Answer): Plan {
            assertEquals("Service plan Gold path saved", documentOf(answer.body).getValue("result").jsonObject.text("success"))
            return script.planNamed("Gold path")
        }

        val kept = goldAfter(script.get("/plan/setup?action=save_plan&service_plan_name=Gold%20path&service_ids[]=$orders"))
        assertEquals(Tags.of("premium") to listOf("Orders"), kept.tags to kept.items.map { it.serviceName })
        val save = "action=save_plan&service_plan_name=Gold+path"
        val both = "service_ids%5B%5D=$customers&service_ids[]=$orders"
        val tagged = goldAfter(script.post("/plan/setup", "$save&service_plan_tag=Fast+lane&$both"))
        assertEquals(Tags.of("fast", "lane") to listOf("Customers", "Orders"), tagged.tags to tagged.items.map { it.serviceName })
        val twice = goldAfter(script.get("/plan/setup?$save&service_ids%5B%5D=$orders&service_ids%5B%5D=$orders"))
        assertEquals(Tags.of("fast", "lane") to listOf("Orders"), twice.tags to twice.items.map { it.serviceName })
        val untagged = goldAfter(script.get("/plan/setup?action=save_plan&service_plan_name=Gold%20path&service_plan_tag="))
        assertEquals(Tags.EMPTY to emptyList(), untagged.tags to untagged.items)
    }

    /**
     * A request that names none of the four actions gets a result that lists them. Mockey 2.x answered it
     * with its home page (DEV-50). An action is named as it's written.
     */
    @Test
    fun aRequestThatNamesNoActionGetsAResultThatListsTheActions() {
        val script = script()
        val before = script.definitions
        val text = "The request names no action. The actions are save_plan, set_plan, delete_plan, and set_as_default_plan."

        for (query in listOf("", "action=", "action=apply_plan", "action=SET_PLAN&service_plan_name=Gold%20path", "action=+delete_plan")) {
            val answer = script.get("/plan/setup?$query")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals("""{"result":{"fail":"$text"}}""", answer.body, query)
        }
        assertEquals(before, script.definitions)
    }

    /** An answer of the endpoint is like every answer of the shim, and a request to it leaves no history entry. */
    @Test
    fun anAnswerOfTheEndpointIsAnAnswerOfTheShim() {
        val script = script()

        val answer = script.post("/plan/setup", "action=set_plan&service_plan_name=Gold+path")

        val length = "Content-Length: ${answer.body.length}"
        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals(listOf("Cache-Control: no-store", length, "Content-Type: application/json", "Connection: close"), answer.headers)
        assertTrue(answer.body.startsWith("""{"result":{"success":"Service plan Gold path set","planid":""""), answer.body)
        assertEquals(emptyList(), script.engine.history.entries())
    }
}
