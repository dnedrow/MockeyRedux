package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 14.1: the legacy shim over real HTTP, on a listener that runs the module of the mock listener. The
 * shim is there only when the server is started with it, and its endpoint `/definitions` shows that it
 * answers.
 */
class LegacyShimTest {
    private val started = mutableListOf<ShimUnderTest>()
    private val levelBefore = ServerLogging.level()

    @AfterTest
    fun stopEverything() {
        started.forEach(ShimUnderTest::close)
        ServerLogging.configure(levelBefore)
    }

    /** Starts a mock listener with the legacy shim, or without it. */
    private fun listener(
        withShim: Boolean = true,
        maxRequestBody: Long = ShimUnderTest.MEBIBYTE,
    ): ShimUnderTest = ShimUnderTest.start(withShim = withShim, maxRequestBody = maxRequestBody).also { started += it }

    /** Returns the names of the services that the document [body] of `/definitions` lists, in their order. */
    private fun listed(body: String): List<String> = documentOf(body).objects("serviceDefinitions").map { it.text("serviceName") }

    /** The scenario "Shim disabled": without the shim, the paths of its endpoints are paths that the listener doesn't serve. */
    @Test
    fun withoutTheShimThePathsOfTheEndpointsGetTheStatus404() {
        val script = listener(withShim = false)
        script.service("Customers", "customers", "silver")
        val before = script.definitions

        for (path in ENDPOINTS) {
            val toGet = script.get("$path?serviceName=Customers&hangTime=5&action=deleteAllServices&type=json")
            val toPost = script.post(path, "serviceName=Customers&hangTime=5")

            assertEquals("HTTP/1.1 404 Not Found" to "", toGet.statusLine to toGet.body, path)
            assertEquals("HTTP/1.1 404 Not Found" to "", toPost.statusLine to toPost.body, path)
        }
        assertEquals(before, script.definitions)
    }

    /** The scenario "Shim enabled": with the shim, `/definitions` answers with a JSON document of the services. */
    @Test
    fun withTheShimTheDefinitionsEndpointAnswersWithAJsonDocumentOfTheServices() {
        val script = listener()
        val customers = script.service("Customers", "customers", "silver", "gold") { it.copy(responseMode = ResponseMode.STATIC) }
        val (silver, gold) = customers.scenarios

        val answer = script.get("/definitions")

        val ofGold = """{"scenarioId":"${gold.id.value}","scenarioName":"gold"}"""
        val ofSilver = """{"scenarioId":"${silver.id.value}","scenarioName":"silver"}"""
        val service =
            """{"serviceName":"Customers","serviceId":"${customers.id.value}","defaultScenarioId":"${silver.id.value}",""" +
                """"defaultScenarioName":"silver","serviceResponseType":"static","hangTime":0,"requestInspectorName":"null",""" +
                """"scenarioArray":[$ofGold,$ofSilver]}"""
        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals("""{"serviceDefinitions":[$service]}""", answer.body)
        val length = "Content-Length: ${answer.body.length}"
        assertEquals(listOf("Cache-Control: no-store", length, "Content-Type: application/json", "Connection: close"), answer.headers)
    }

    /**
     * The recordings of `/definitions` that task 2.8 made: the answers that they state for the new server,
     * with the identifiers of this server where a recording leaves a value open. They are the recordings
     * `automation-definitions-all`, `-by-name`, `-unknown-name`, `-by-id`, and `-empty`.
     */
    @Test
    fun theAnswersAreTheOnesThatTheRecordingsStateForTheNewServer() {
        val script = listener()
        val ofNoService = script.get("/definitions")
        val (customers, orders, billing) = ShimFixture.services(script.engine)

        fun scenario(
            service: Service,
            name: String,
        ) = """{"scenarioId":"${service.scenarios.single { it.name == name }.id.value}","scenarioName":"$name"}"""

        fun default(service: Service) = service.defaultScenarioId?.value

        val ofCustomers =
            """{"serviceName":"Customers","serviceId":"${customers.id.value}","serviceResponseType":"static","hangTime":0,""" +
                """"requestInspectorName":"null","defaultScenarioId":"${default(customers)}","defaultScenarioName":"silver",""" +
                """"scenarioArray":[${scenario(customers, "gold")},${scenario(customers, "missing")},${scenario(customers, "silver")}]}"""
        val ofOrders =
            """{"serviceName":"Orders","serviceId":"${orders.id.value}","serviceResponseType":"dynamic","hangTime":100,""" +
                """"requestInspectorName":"null","defaultScenarioId":"${default(orders)}","defaultScenarioName":"open",""" +
                """"scenarioArray":[${scenario(orders, "closed")},${scenario(orders, "open")}]}"""
        val ofBilling =
            """{"serviceName":"Billing","serviceId":"${billing.id.value}","serviceResponseType":"proxy","hangTime":0,""" +
                """"requestInspectorName":"null","defaultScenarioId":null,"defaultScenarioName":null,"scenarioArray":[]}"""
        val all = documentOf("""{"serviceDefinitions":[$ofCustomers,$ofOrders,$ofBilling]}""")
        assertEquals(all, script.document("/definitions"))
        assertEquals(documentOf("""{"serviceDefinitions":[$ofCustomers]}"""), script.document("/definitions?serviceName=customers"))
        assertEquals(all, script.document("/definitions?serviceName=Nobody"))
        assertEquals(all, script.document("/definitions?serviceId=2"))
        assertEquals(200 to """{"serviceDefinitions":[]}""", ofNoService.status to ofNoService.body)
        assertEquals("silver" to "open", customers.scenarios.first().name to orders.scenarios.first().name)
    }

    /** The scenario "One service by name". */
    @Test
    fun aRequestThatNamesAServiceWithTwoScenariosGetsOneElementWithTwoScenarios() {
        val script = listener()
        script.service("Orders", "orders", "open")
        script.service("Customers", "customers", "silver", "gold")

        val document = script.document("/definitions?serviceName=Customers")

        val element = document.objects("serviceDefinitions").single()
        assertEquals(setOf("serviceDefinitions"), document.keys)
        assertEquals("Customers", element.text("serviceName"))
        assertEquals(2, element.objects("scenarioArray").size)
    }

    /**
     * Mockey 2.x leaves out what has no value (DEV-47). The shim states every member: a server without
     * services has an empty list, and a service without scenarios has an empty list of them and no default
     * scenario. Such a service answers as a proxy, whatever mode it's set to.
     */
    @Test
    fun everyMemberIsThereAlsoWhenThereIsNothingToState() {
        val script = listener()

        val ofNoService = script.get("/definitions").body
        val billing =
            script.service("Billing", "http://billing.example.com/v1") { it.copy(responseMode = ResponseMode.STATIC, delayMillis = 40) }
        val ofBilling = script.document("/definitions")

        val stated: Map<String, JsonElement> =
            mapOf(
                "serviceName" to JsonPrimitive("Billing"),
                "serviceId" to JsonPrimitive(billing.id.value),
                "defaultScenarioId" to JsonNull,
                "defaultScenarioName" to JsonNull,
                "serviceResponseType" to JsonPrimitive("proxy"),
                "hangTime" to JsonPrimitive(40),
                "requestInspectorName" to JsonPrimitive("null"),
                "scenarioArray" to JsonArray(emptyList()),
            )
        assertEquals("""{"serviceDefinitions":[]}""", ofNoService)
        assertEquals(JsonObject(mapOf("serviceDefinitions" to JsonArray(listOf(JsonObject(stated))))), ofBilling)
    }

    /**
     * The default scenario that the shim states is the scenario that answers in static mode. A service that
     * was stored by hand can designate no scenario, or one that it doesn't have. Then its first scenario
     * answers, and that's the one that the shim states.
     */
    @Test
    fun theDefaultScenarioThatIsStatedIsTheOneThatAnswers() {
        val scenarios = listOf("first", "second").map { Scenario(ScenarioId.generate(), it, responseBody = "the $it scenario") }
        val undesignated =
            Service(ServiceId.generate(), "Undesignated", "undesignated", responseMode = ResponseMode.STATIC, scenarios = scenarios)
        val noScenarioOfIt = ScenarioId.generate()
        val stray = undesignated.copy(id = ServiceId.generate(), name = "Stray", mockUrl = "stray", defaultScenarioId = noScenarioOfIt)
        val stored = InMemoryDefinitionStorage(Definitions(services = listOf(undesignated, stray)))
        val script = ShimUnderTest.start(runBlocking { MockeyEngine.create { storage = stored } }).also { started += it }

        val services = script.document("/definitions").objects("serviceDefinitions")

        for ((target, service) in listOf("undesignated", "stray").zip(services)) {
            assertEquals("the first scenario", script.get("/service/$target").body, target)
            assertEquals("first", service.text("defaultScenarioName"), target)
            assertEquals(scenarios.first().id.value, service.text("defaultScenarioId"), target)
        }
    }

    /**
     * A service states the mode in which it answers, its delay, and the name of its inspector. The scenarios
     * are in the order of their names, without regard to letter case, as Mockey 2.x has them, and the
     * services are in the order in which they were created.
     */
    @Test
    fun theServicesAreListedAsTheyWereCreatedAndTheirScenariosByName() {
        val script = listener()
        val created =
            script.service("Zebra", "zebra", "silver", "Gold", "bronze", "amber") {
                it.copy(responseMode = ResponseMode.DYNAMIC, delayMillis = 250, inspectorName = "com.example.Audit")
            }
        val bronze = created.scenarios.single { it.name == "bronze" }
        val services = script.engine.services
        runBlocking { services.update(created.id) { it.copy(defaultScenarioId = bronze.id) }.getOrThrow() }
        script.service("alpha", "alpha", "only") { it.copy(responseMode = ResponseMode.PROXY) }
        script.service("Middle", "middle")

        val listed = script.document("/definitions").objects("serviceDefinitions")

        val zebra = listed.first()
        assertEquals(listOf("Zebra", "alpha", "Middle"), listed.map { it.text("serviceName") })
        assertEquals(listOf("dynamic", "proxy", "proxy"), listed.map { it.text("serviceResponseType") })
        assertEquals(JsonPrimitive(250) to JsonPrimitive("com.example.Audit"), zebra["hangTime"] to zebra["requestInspectorName"])
        assertEquals(listOf("amber", "bronze", "Gold", "silver"), zebra.objects("scenarioArray").map { it.text("scenarioName") })
        assertEquals("bronze" to bronze.id.value, zebra.text("defaultScenarioName") to zebra.text("defaultScenarioId"))
        assertEquals(setOf("scenarioId", "scenarioName"), zebra.objects("scenarioArray").flatMap { it.keys }.toSet())
    }

    /**
     * The requirement "Legacy definitions endpoint": a request that names a service by `serviceId` or by
     * `serviceName` gets that service alone. An identifier counts before a name. A name is compared as
     * Mockey 2.x compared it, and a request that names no service that is there gets every service.
     */
    @Test
    fun aRequestThatNamesAServiceGetsThatServiceAlone() {
        val script = listener()
        val customers = script.service("Customers", "customers", "silver")
        val orders = script.service("Order Book", "orders", "open")

        fun namedBy(query: String) = listed(script.get("/definitions?$query").body)

        val all = listOf("Customers", "Order Book")
        assertEquals(listOf("Order Book"), namedBy("serviceId=${orders.id.value}"))
        assertEquals(listOf("Customers"), namedBy("serviceName=Customers"))
        assertEquals(listOf("Customers"), namedBy("serviceName=cUSTOMERS"))
        assertEquals(listOf("Order Book"), namedBy("serviceName=+order%20book++"))
        assertEquals(listOf("Order Book"), namedBy("serviceId=${orders.id.value}&serviceName=Customers"))
        assertEquals(listOf("Order Book"), namedBy("serviceName=Customers&serviceId=${orders.id.value}"))
        // An identifier that identifies nothing leaves it to the name.
        assertEquals(listOf("Customers"), namedBy("serviceId=${customers.id.value}x&serviceName=Customers"))
        assertEquals(listOf("Customers"), namedBy("serviceId=2&serviceName=Customers"))
        assertEquals(all, namedBy("serviceId=${customers.id.value}x"))
        // An identifier counts as it's written. One without a letter is the same in upper case.
        val shouted = customers.id.value.uppercase()
        if (shouted != customers.id.value) assertEquals(all, namedBy("serviceId=$shouted"))
        assertEquals(all, namedBy("serviceName=Nobody"))
        assertEquals(all, namedBy("serviceName=Custom"))
        assertEquals(all, namedBy("serviceName="))
        assertEquals(all, namedBy("servicename=Customers"))
        // A number of Mockey 2.x identifies nothing (DEV-20).
        assertEquals(all, namedBy("serviceId=2"))
        assertEquals(all, namedBy("unknown=parameter"))
        // Of two services with the name, the one that was created first counts.
        script.service("order book", "orders/archive", "closed")
        assertEquals(listOf("Order Book"), namedBy("serviceName=ORDER+BOOK"))
        assertEquals(listOf("Order Book"), namedBy("serviceName=order+book"))
    }

    /** The requirement "Identifiers in legacy endpoints": the identifiers that the endpoint states are the ones that it takes. */
    @Test
    fun theIdentifiersThatTheEndpointStatesAreTheOnesOfTheServer() {
        val script = listener()
        val customers = script.service("Customers", "customers", "silver", "gold")
        script.service("Orders", "orders", "open")

        val first = script.document("/definitions").objects("serviceDefinitions").first()
        val stated = first.getValue("serviceId") as JsonPrimitive
        val again = script.get("/definitions?serviceId=${stated.content}")

        assertTrue(stated.isString, "An identifier is a text: $stated")
        assertEquals(customers.id.value, stated.content)
        assertEquals(listOf("Customers"), listed(again.body))
        val scenarioIds = first.objects("scenarioArray").map { it.text("scenarioId") }
        assertEquals(customers.scenarios.map { it.id.value }.toSet(), scenarioIds.toSet())
        assertEquals(customers.defaultScenarioId?.value, first.text("defaultScenarioId"))
    }

    /**
     * An endpoint reads its parameters as Mockey 2.x did: from the query string and from the fields of a
     * URL-encoded form, and of a parameter that occurs twice, it takes the first one. A body of another
     * kind holds no parameters.
     */
    @Test
    fun theParametersComeFromTheQueryStringAndFromAUrlEncodedForm() {
        val script = listener()
        script.service("Customers", "customers", "silver")
        script.service("Orders", "orders", "open")
        val form = listOf("Content-Type: application/x-www-form-urlencoded; charset=UTF-8")

        val fromForm = script.send("POST", "/definitions", form, "serviceName=Orders")
        val fromBoth = script.send("POST", "/definitions?serviceName=Customers", form, "serviceName=Orders")
        val twice = script.get("/definitions?serviceName=Orders&serviceName=Customers")
        val ofJson = script.send("POST", "/definitions", listOf("Content-Type: application/json"), """{"serviceName":"Orders"}""")
        val ofText = script.send("POST", "/definitions", listOf("Content-Type: text/plain"), "serviceName=Orders")
        val undeclared = script.send("POST", "/definitions", body = "serviceName=Orders")

        assertEquals(listOf("Orders"), listed(fromForm.body))
        assertEquals(listOf("Customers"), listed(fromBoth.body))
        assertEquals(listOf("Orders"), listed(twice.body))
        for (answer in listOf(ofJson, ofText, undeclared)) assertEquals(listOf("Customers", "Orders"), listed(answer.body))
    }

    /** An answer is written in UTF-8, and a parameter is read in UTF-8, from a percent escape and from a form as it is. */
    @Test
    fun aNameBeyondAsciiIsWrittenAndReadInUtf8() {
        val name = "Café \"Größe\" 日本"
        val script = listener()
        script.service(name, "cafe", "crème")
        script.service("Other", "other")

        val all = script.get("/definitions")
        val byQuery = script.get("/definitions?serviceName=caf%C3%A9+%22gr%C3%B6%C3%9Fe%22+%E6%97%A5%E6%9C%AC")
        val byForm = script.post("/definitions", RawHttp.utf8("serviceName=CAFÉ \"GRÖßE\" 日本"))

        assertEquals(listOf(name, "Other"), listed(all.bodyInUtf8))
        assertTrue(""""serviceName":"Café \"Größe\" 日本"""" in all.bodyInUtf8, all.bodyInUtf8)
        assertTrue(""""scenarioName":"crème"""" in all.bodyInUtf8, all.bodyInUtf8)
        assertEquals(listOf("${all.body.length}"), all.headerValues("Content-Length"))
        assertEquals(listOf(name), listed(byQuery.bodyInUtf8))
        assertEquals(listOf(name), listed(byForm.bodyInUtf8))
    }

    /** Mockey 2.x answers a request of every method, and so does the shim. A `HEAD` request gets the headers of the answer. */
    @Test
    fun anEndpointAnswersARequestOfEveryMethod() {
        val script = listener()
        script.service("Customers", "customers", "silver")
        val toGet = script.get("/definitions")

        for (method in listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS", "FOO")) {
            val answer = script.send(method, "/definitions")

            assertEquals(toGet.statusLine to toGet.body, answer.statusLine to answer.body, method)
            assertEquals(toGet.headers, answer.headers, method)
        }
        val toHead = script.send("HEAD", "/definitions")
        assertEquals("HTTP/1.1 200 OK" to "", toHead.statusLine to toHead.body)
        assertEquals(toGet.headers, toHead.headers)
    }

    @Test
    fun onlyThePathOfAnEndpointIsTheEndpoint() {
        val script = listener()
        script.service("Customers", "customers", "silver")

        for (target in listOf("/definitions/", "/Definitions", "/definitions/x", "/x/definitions", "/definitions.json", "//definitions")) {
            val answer = script.get(target)

            assertEquals("HTTP/1.1 404 Not Found" to "", answer.statusLine to answer.body, target)
        }
        assertEquals(200, script.get("http://mockey.test:8080/definitions").status)
        assertEquals(200, script.get("/definitions?").status)
    }

    /**
     * The mock endpoint lets a page of any origin read its answers, as a mock has to. An answer of the shim
     * carries no such header (DEV-26), and it isn't to be stored.
     */
    @Test
    fun anAnswerOfTheShimLetsNoPageOfAnotherOriginReadIt() {
        val script = listener()
        script.service("Customers", "customers", "silver")
        val origin = listOf("Origin: https://app.example.com")

        val ofShim = script.send("GET", "/definitions", origin)
        val ofMock = script.send("GET", "/service/customers", origin)

        assertEquals(emptyList(), ofShim.headers.filter { it.startsWith("Access-Control-", ignoreCase = true) })
        assertEquals(listOf("no-store"), ofShim.headerValues("Cache-Control"))
        assertEquals(listOf("*"), ofMock.headerValues("Access-Control-Allow-Origin"))
    }

    /**
     * A request to the shim is no mock traffic, so it leaves no history entry. And mock traffic is no
     * request to the shim: a service can have the path of an endpoint as its mock URL.
     */
    @Test
    fun aRequestToTheShimIsNoMockTrafficAndMockTrafficIsNoRequestToTheShim() {
        val script = listener()
        script.service("Definitions", "definitions", "the service named definitions") { it.copy(responseMode = ResponseMode.STATIC) }

        val ofShim = script.get("/definitions")
        val ofService = script.get("/service/definitions")

        assertEquals(listOf("Definitions"), listed(ofShim.body))
        assertEquals("the service named definitions", ofService.body)
        val history = script.engine.history
        assertEquals(listOf("definitions"), history.entries().map { it.target })
    }

    /** The server takes a body of a limited length, also for the shim. */
    @Test
    fun aRequestWithABodyThatIsTooLongGetsTheStatus413() {
        val script = listener(maxRequestBody = 32)
        script.service("Customers", "customers", "silver")

        val allowed = script.post("/definitions", "serviceName=Customers&x=" + "y".repeat(7))
        val tooLong = script.post("/definitions", "serviceName=Customers&x=" + "y".repeat(9))

        assertEquals(200 to listOf("Customers"), allowed.status to listed(allowed.body))
        assertEquals("HTTP/1.1 413 Payload Too Large", tooLong.statusLine)
        assertEquals("The body of the request is longer than 32 bytes, which is the most that this server takes.", tooLong.body)
        assertEquals(listOf("text/plain; charset=utf-8"), tooLong.headerValues("Content-Type"))
        assertEquals(listOf("no-store"), tooLong.headerValues("Cache-Control"))
    }

    /**
     * An endpoint that fails gets the status 500. The log says which endpoint failed and why, and it holds
     * nothing of the query string, which can hold a secret.
     */
    @Test
    fun anEndpointThatFailsGetsTheStatus500AndTheLogSaysWhy() {
        val failing = LegacyShim(ShimUnderTest.MEBIBYTE, mapOf("/definitions" to ShimEndpoint { error("The endpoint is broken.") }))
        val script = ShimUnderTest.start(runBlocking { MockeyEngine.create() }, failing).also { started += it }
        ServerLogging.configure(LogLevel.INFO)
        lateinit var answer: RawHttp.Answer

        val log = TestLog.of { answer = script.get("/definitions?serviceName=tk-71c0") }

        assertEquals("HTTP/1.1 500 Internal Server Error", answer.statusLine)
        assertEquals("The server failed to handle the request. Its log says why.", answer.body)
        assertEquals(listOf("no-store"), answer.headerValues("Cache-Control"))
        val errors = TestLog.messages(log, "ERROR")
        assertEquals("The request for /definitions of the legacy shim failed.", errors.first())
        assertTrue("java.lang.IllegalStateException: The endpoint is broken." in log, log)
        assertFalse("tk-71c0" in log, log)
    }

    /**
     * A change that the core doesn't make comes with its reason: what is wrong with each invalid field, or
     * what the change found missing. The endpoints that change something state that reason in their answer.
     */
    @Test
    fun theReasonOfAResultThatIsNoChangeIsWhatTheCoreSays() {
        val errors = listOf(FieldError("name", "The name of a plan must not be empty."), FieldError("items", "An item names no service."))
        val rejected = ChangeResult.Rejected(ValidationFailure(errors))

        assertEquals("The name of a plan must not be empty. An item names no service.", reasonOf(rejected))
        assertEquals("No plan has the identifier 7.", reasonOf(ChangeResult.NotFound("No plan has the identifier 7.")))
        assertEquals("", reasonOf(ChangeResult.Changed(Definitions.EMPTY, Unit)))
    }

    private companion object {
        /** The paths of the automation endpoints of Mockey 2.x that the shim is for. */
        val ENDPOINTS = listOf("/definitions", "/config/service", "/plan/setup", "/home")
    }
}
