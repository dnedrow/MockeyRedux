package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.storage.DefinitionStorage
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

/**
 * Task 14.2: the endpoint `/config/service` of the legacy shim, over real HTTP. A test starts from the
 * services of the parity fixture `automation.xml`, as the recordings of task 2.8 do, and it holds an answer
 * against the one that a recording states for the new server.
 */
class ShimServicesTest {
    private val started = mutableListOf<ShimUnderTest>()
    private val levelBefore = ServerLogging.level()

    @AfterTest
    fun stopEverything() {
        started.forEach(ShimUnderTest::close)
        ServerLogging.configure(levelBefore)
    }

    /** Starts a mock listener with the legacy shim for an engine that has the services of the fixture. */
    private fun script(): ShimUnderTest = ShimUnderTest.start().also { started += it }.also { ShimFixture.services(it.engine) }

    /** Starts a mock listener with the legacy shim for an engine in writable mode, which stores its definitions in [storage]. */
    private fun writable(storage: DefinitionStorage): ShimUnderTest {
        val engine =
            runBlocking {
                MockeyEngine.create {
                    this.storage = storage
                    readOnly = false
                }
            }
        return ShimUnderTest.start(engine).also { started += it }
    }

    /** Returns the service [name] as the engine of this listener has it now. */
    private fun ShimUnderTest.serviceNamed(name: String): Service = engine.definitions.services.single { it.name == name }

    /** Returns the result of the answer to a `GET` request for `/config/service` with [query]. */
    private fun ShimUnderTest.configured(query: String): JsonObject = document("/config/service?$query").getValue("result").jsonObject

    /**
     * Returns the document of [template], which is the answer that a recording states for the new server,
     * with the identifiers of [service] where the recording leaves a value open.
     */
    private fun stated(
        template: String,
        service: Service,
    ): JsonObject {
        val result = documentOf(template).getValue("result").jsonObject
        assertEquals("<any>" to "<any>", result.text("serviceId") to result.text("scenarioId"), template)
        val scenario = service.scenarios.single { it.name == result.text("scenarioName") }
        val identifiers = mapOf("serviceId" to JsonPrimitive(service.id.value), "scenarioId" to JsonPrimitive(scenario.id.value))
        return JsonObject(mapOf("result" to JsonObject(result + identifiers)))
    }

    /** A recording of task 2.8 whose request changes a service: the request, the answer that it states, and what the request changes. */
    private class Recorded(
        val name: String,
        val service: String,
        val query: String,
        val stated: String,
        val asForm: Boolean = false,
        val change: (Service) -> Service,
    )

    private val recorded =
        listOf(
            Recorded(
                "automation-config-default-url-index",
                "Customers",
                "serviceName=Customers&defaultUrlIndex=2",
                updated("Customers", "silver"),
            ) { it.copy(defaultRealUrlIndex = 1) },
            Recorded(
                "automation-config-inspector",
                "Customers",
                "serviceName=Customers&requestInspectorName=com.mockey.plugin.SampleRequestInspector" +
                    "&requestInspectorJsonRules=$ENCODED_RULES&requestInspectorJsonRulesEnableFlag=true",
                updated("Customers", "silver", inspector = "com.mockey.plugin.SampleRequestInspector", rules = true),
            ) { it.copy(inspectorName = "com.mockey.plugin.SampleRequestInspector", requestRules = RULES, requestRulesEnabled = true) },
            Recorded(
                "automation-config-numeric-type",
                "Customers",
                "serviceName=Customers&serviceResponseType=2&scenarioName=missing",
                updated("Customers", "missing", mode = "dynamic"),
            ) { it.copy(responseMode = ResponseMode.DYNAMIC, defaultScenarioId = it.scenarios.single { s -> s.name == "missing" }.id) },
            Recorded(
                "automation-config-post-form",
                "Customers",
                "serviceName=Customers&hangTime=75",
                updated("Customers", "silver", hangTime = 75),
                asForm = true,
            ) { it.copy(delayMillis = 75) },
            Recorded(
                "automation-config-proxy-type",
                "Orders",
                "serviceName=Orders&serviceResponseType=proxy",
                updated("Orders", "open", mode = "proxy", hangTime = 100),
            ) { it.copy(responseMode = ResponseMode.PROXY) },
            Recorded(
                "automation-config-scenario-by-name",
                "Customers",
                "serviceName=Customers&serviceResponseType=static&scenarioName=gold&hangTime=250",
                updated("Customers", "gold", hangTime = 250),
            ) { it.copy(defaultScenarioId = it.scenarios.single { s -> s.name == "gold" }.id, delayMillis = 250) },
            Recorded(
                "automation-config-schema",
                "Customers",
                "serviceName=Customers&serviceSchema=$ENCODED_SCHEMA&serviceSchemaEnableFlag=true",
                updated("Customers", "silver", schema = true),
            ) { it.copy(responseSchema = SCHEMA, responseSchemaEnabled = true) },
            Recorded(
                "automation-config-transient-state",
                "Customers",
                "serviceName=Customers&transientState=true&hangTime=10",
                updated("Customers", "silver", hangTime = 10),
            ) { it.copy(delayMillis = 10) },
            Recorded(
                "automation-config-unknown-scenario",
                "Customers",
                "serviceName=Customers&scenarioName=platinum",
                updated("Customers", "silver"),
            ) { it },
        )

    /**
     * The recordings of `/config/service` that state a changed service: each request gets the answer that
     * its recording states for the new server, and it changes the service in the way that the answer says,
     * and in no other way.
     */
    @Test
    fun aRecordedRequestGetsTheAnswerThatItsRecordingStatesAndChangesTheServiceThatWay() {
        for (recording in recorded) {
            val script = script()
            val before = script.engine.definitions

            val answer =
                if (recording.asForm) script.post("/config/service", recording.query) else script.get("/config/service?${recording.query}")

            val service = before.services.single { it.name == recording.service }
            assertEquals("HTTP/1.1 200 OK", answer.statusLine, recording.name)
            assertEquals(stated(recording.stated, service), documentOf(answer.body), recording.name)
            val expected = before.copy(services = before.services.map { if (it.id == service.id) recording.change(it) else it })
            assertEquals(expected, script.engine.definitions, recording.name)
        }
    }

    /**
     * The recordings `automation-config-by-id` and `automation-config-unknown-service`, and the scenario
     * "Failure is reported in the envelope": a request that names no service that is there gets a result
     * with the member `fail` and without the member `success`, and it changes nothing. A number of Mockey
     * 2.x names no service (DEV-20), and Mockey 2.x answered an unknown name with the status 500 (DEV-46).
     */
    @Test
    fun aRequestThatNamesNoServiceGetsAResultThatSaysSoAndChangesNothing() {
        val script = script()
        val before = script.engine.definitions
        val text = "Unable to update service configuration. No service has the serviceId or the serviceName of the request."

        for (query in listOf("serviceId=1&scenarioId=3", "serviceName=Nobody&hangTime=5", "hangTime=5", "serviceName=&hangTime=5")) {
            val answer = script.get("/config/service?$query")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals("""{"result":{"fail":"$text"}}""", answer.body, query)
        }
        assertEquals("""{"result":{"fail":"$text"}}""", script.get("/config/service").body)
        assertEquals(before, script.engine.definitions)
    }

    /**
     * The recording `automation-config-effect`, and the scenario "Switch a service to a named scenario": the
     * service answers its next request with the scenario and after the delay that the request named.
     */
    @Test
    fun aServiceAnswersAsTheRequestConfiguredIt() {
        val script = script()

        script.get("/config/service?serviceName=Customers&serviceResponseType=static&scenarioName=gold&hangTime=250")
        val (answer, time) = measureTimedValue { script.get("/service/customers") }

        val customers = script.serviceNamed("Customers")
        assertEquals(200 to "gold customer", answer.status to answer.body)
        assertTrue(time >= 250.milliseconds, "The service answered after $time")
        assertEquals(ResponseMode.STATIC to 250L, customers.responseMode to customers.delayMillis)
        assertEquals("gold", customers.scenarios.single { it.id == customers.defaultScenarioId }.name)
    }

    /** The recording `automation-config-second-path`: the second path of the servlet of Mockey 2.x isn't served (DEV-26). */
    @Test
    fun theSecondPathOfMockey2xGetsTheStatus404() {
        val script = script()
        val before = script.engine.definitions

        val answer = script.get("/service_scenario?serviceName=Customers&hangTime=5")

        assertEquals("HTTP/1.1 404 Not Found" to "", answer.statusLine to answer.body)
        assertEquals(before, script.engine.definitions)
    }

    /**
     * The requirement "Legacy service configuration result": the result has the member `success` with the
     * value `updated` and nine members that describe the service after the change, and it has no member
     * `fail`. An answer of the endpoint is like every answer of the shim.
     */
    @Test
    fun theResultDescribesTheServiceAfterTheChange() {
        val script = script()
        val customers = script.serviceNamed("Customers")
        val gold = customers.scenarios.single { it.name == "gold" }

        val answer = script.get("/config/service?serviceName=Customers&serviceResponseType=dynamic&scenarioName=gold&hangTime=40")

        val expected =
            """{"result":{"success":"updated","serviceName":"Customers","serviceId":"${customers.id.value}",""" +
                """"scenarioId":"${gold.id.value}","scenarioName":"gold","serviceResponseType":"dynamic","hangTime":40,""" +
                """"requestInspectorName":"null","serviceSchemaEnableFlag":false,"requestInspectorJsonRulesEnableFlag":false}}"""
        assertEquals(expected, answer.body)
        val length = "Content-Length: ${answer.body.length}"
        assertEquals(listOf("Cache-Control: no-store", length, "Content-Type: application/json", "Connection: close"), answer.headers)
        assertEquals(emptyList(), script.engine.history.entries())
    }

    /** The scenario "Result describes the updated service". */
    @Test
    fun aSwitchToStaticModeIsStatedInTheResult() {
        val script = script()
        script.get("/config/service?serviceName=Customers&serviceResponseType=dynamic")

        val result = script.configured("serviceName=Customers&serviceResponseType=static")

        assertEquals(listOf("updated", "Customers", "static"), listOf("success", "serviceName", "serviceResponseType").map(result::text))
        assertFalse("fail" in result)
        assertEquals(ResponseMode.STATIC, script.serviceNamed("Customers").responseMode)
    }

    /**
     * The scenario "Numeric response types", and the names and numbers of Mockey 2.x in full: a response
     * type is named by `proxy`, `static`, or `dynamic`, or by `0`, `1`, or `2`, in any letter case and with
     * spaces around it. Anything else names no response type and changes nothing.
     */
    @Test
    fun aResponseTypeIsNamedByItsNameOrByItsNumber() {
        val script = script()

        fun modeAfter(value: String): Pair<String, ResponseMode> {
            val result = script.configured("serviceName=Orders&serviceResponseType=$value")
            return result.text("serviceResponseType") to script.serviceNamed("Orders").responseMode
        }

        assertEquals("dynamic" to ResponseMode.DYNAMIC, modeAfter("2"))
        assertEquals("proxy" to ResponseMode.PROXY, modeAfter("0"))
        assertEquals("static" to ResponseMode.STATIC, modeAfter("1"))
        assertEquals("dynamic" to ResponseMode.DYNAMIC, modeAfter("DYNAMIC"))
        assertEquals("proxy" to ResponseMode.PROXY, modeAfter("+Proxy+"))
        assertEquals("static" to ResponseMode.STATIC, modeAfter("%20static"))
        val unchanged = "static" to ResponseMode.STATIC
        for (none in listOf("3", "mock", "", "-1", "01", "static,dynamic")) assertEquals(unchanged, modeAfter(none), none)
    }

    /**
     * The default scenario is named by `scenarioId` or by `scenarioName`. An identifier counts before a
     * name, a name is compared as Mockey 2.x compared it, and a scenario that the service doesn't have
     * leaves the default scenario as it is.
     */
    @Test
    fun theDefaultScenarioIsNamedByItsIdentifierOrByItsName() {
        val script = script()
        val customers = script.serviceNamed("Customers")
        val (silver, gold, missing) = customers.scenarios
        val open = script.serviceNamed("Orders").scenarios.first()

        fun defaultAfter(query: String): String {
            val result = script.configured("serviceId=${customers.id.value}&$query")
            val service = script.serviceNamed("Customers")
            assertEquals(service.defaultScenarioId?.value, result.text("scenarioId"), query)
            return result.text("scenarioName")
        }

        assertEquals(listOf("silver", "gold", "missing"), listOf(silver, gold, missing).map { it.name })
        assertEquals("gold", defaultAfter("scenarioId=${gold.id.value}"))
        assertEquals("missing", defaultAfter("scenarioName=missing"))
        assertEquals("gold", defaultAfter("scenarioName=+GOLD+"))
        assertEquals("silver", defaultAfter("scenarioId=${silver.id.value}&scenarioName=missing"))
        // An identifier that identifies no scenario of the service leaves it to the name.
        assertEquals("missing", defaultAfter("scenarioId=3&scenarioName=missing"))
        assertEquals("gold", defaultAfter("scenarioId=${open.id.value}&scenarioName=gold"))
        // A scenario that the service doesn't have changes nothing.
        for (none in listOf("scenarioId=3", "scenarioId=${open.id.value}", "scenarioName=open", "scenarioName=gol", "scenarioName=")) {
            assertEquals("gold", defaultAfter(none), none)
        }
        // An identifier counts as it's written. One without a letter is the same in upper case.
        val shouted = silver.id.value.uppercase()
        if (shouted != silver.id.value) assertEquals("gold", defaultAfter("scenarioId=$shouted"))
    }

    /**
     * The delay is a whole number of milliseconds, as Mockey 2.x read it. Anything else changes nothing. A
     * negative delay is taken as it is, as the admin API takes it, and it delays nothing.
     */
    @Test
    fun theDelayIsAWholeNumberOfMilliseconds() {
        val script = script()

        fun delayAfter(value: String): Long {
            val result = script.configured("serviceName=Orders&hangTime=$value")
            val delay = script.serviceNamed("Orders").delayMillis
            assertEquals(JsonPrimitive(delay), result["hangTime"], value)
            return delay
        }

        assertEquals(250L, delayAfter("250"))
        assertEquals(0L, delayAfter("0"))
        assertEquals(2_147_483_647L, delayAfter("2147483647"))
        assertEquals(-5L, delayAfter("-5"))
        assertEquals(7L, delayAfter("%2B7"))
        for (none in listOf("abc", "", "1.5", "%2012", "12ms", "2147483648", "0x10")) assertEquals(7L, delayAfter(none), none)
    }

    /**
     * The default real URL is named by its place in the list, counted from 1. A place that the list doesn't
     * have gives the first URL, which is what Mockey 2.x forwarded to in that case.
     */
    @Test
    fun theDefaultRealUrlIsNamedByItsPlaceCountedFromOne() {
        val script = script()

        fun indexAfter(value: String): Int {
            script.configured("serviceName=Customers&defaultUrlIndex=$value")
            return script.serviceNamed("Customers").defaultRealUrlIndex
        }

        assertEquals(1, indexAfter("2"))
        assertEquals(0, indexAfter("1"))
        assertEquals(1, indexAfter("2"))
        for (none in listOf("x", "", "1.0", "%201", "1+")) assertEquals(1, indexAfter(none), none)
        for (beyond in listOf("3", "0", "-1", "2147483647", "-2147483648")) {
            assertEquals(1, indexAfter("2"))
            assertEquals(0, indexAfter(beyond), beyond)
        }
    }

    /**
     * The inspector is named as the request gives it. Mockey 2.x wrote the text `null` for no inspector,
     * and so `null` and a name of nothing but spaces remove the inspector.
     */
    @Test
    fun theInspectorIsNamedOrRemoved() {
        val script = script()

        fun inspectorAfter(query: String): Pair<String, String?> {
            val result = script.configured("serviceName=Orders$query")
            return result.text("requestInspectorName") to script.serviceNamed("Orders").inspectorName
        }

        assertEquals("com.example.Audit" to "com.example.Audit", inspectorAfter("&requestInspectorName=com.example.Audit"))
        assertEquals("com.example.Audit" to "com.example.Audit", inspectorAfter("&hangTime=5"))
        assertEquals("null" to null, inspectorAfter("&requestInspectorName=null"))
        assertEquals("com.example.Other" to "com.example.Other", inspectorAfter("&requestInspectorName=+com.example.Other+"))
        assertEquals("null" to null, inspectorAfter("&requestInspectorName="))
        assertEquals("Null" to "Null", inspectorAfter("&requestInspectorName=Null"))
        assertEquals("null" to null, inspectorAfter("&requestInspectorName=+null+"))
        assertEquals("null" to null, inspectorAfter("&requestInspectorName=++"))
    }

    /**
     * The request rules and the response schema are texts, and each has a flag that turns it on. A flag is
     * on for `true` in any letter case and off for everything else, as Mockey 2.x read it. An empty text
     * removes the rules or the schema.
     */
    @Test
    fun theRequestRulesAndTheResponseSchemaAreSetWithTheirFlags() {
        val script = script()

        fun after(query: String): Service {
            val result = script.configured("serviceName=Orders&$query")
            val service = script.serviceNamed("Orders")
            assertEquals(JsonPrimitive(service.requestRulesEnabled), result["requestInspectorJsonRulesEnableFlag"], query)
            assertEquals(JsonPrimitive(service.responseSchemaEnabled), result["serviceSchemaEnableFlag"], query)
            return service
        }

        fun rulesFlagAfter(value: String) = after("requestInspectorJsonRulesEnableFlag=$value").requestRulesEnabled

        fun schemaFlagAfter(value: String) = after("serviceSchemaEnableFlag=$value").responseSchemaEnabled

        val withRules = after("requestInspectorJsonRules=$ENCODED_RULES")
        assertEquals(RULES to false, withRules.requestRules to withRules.requestRulesEnabled)
        assertEquals(listOf(true, true, false), listOf("true", "TRUE", "false").map(::rulesFlagAfter))
        assertEquals(listOf(true, false, true, false), listOf("True", "yes", "true", "").map(::rulesFlagAfter))
        assertEquals(listOf(true, false, true, false), listOf("tRUE", "+true", "true", "true+").map(::rulesFlagAfter))
        assertEquals(listOf(true, false, true, false), listOf("TRUE", "1", "true", "+true").map(::schemaFlagAfter))
        val withSchema = after("serviceSchema=$ENCODED_SCHEMA&requestInspectorJsonRulesEnableFlag=true")
        assertEquals(SCHEMA to RULES, withSchema.responseSchema to withSchema.requestRules)
        val without = after("serviceSchema=&requestInspectorJsonRules=")
        assertEquals(null to null, without.responseSchema to without.requestRules)
        // The flags stay as they are when the texts go.
        assertEquals(true to false, without.requestRulesEnabled to without.responseSchemaEnabled)
    }

    /**
     * Mockey 2.x took every text as request rules. The server takes only a well-formed rule set, so the
     * result says what is wrong with another text, and nothing of the request is applied.
     */
    @Test
    fun requestRulesThatAreNotWellFormedAreRefusedWithEverythingElseOfTheRequest() {
        val script = script()
        val before = script.engine.definitions

        val result = script.configured("serviceName=Orders&hangTime=5&serviceResponseType=static&requestInspectorJsonRules=%7Bnot+json")

        val text = result.text("fail")
        assertEquals(setOf("fail"), result.keys)
        assertTrue(text.startsWith("Unable to update service configuration. The request rules are not a well-formed rule set. "), text)
        assertEquals(before, script.engine.definitions)
    }

    /**
     * A service without scenarios answers as a proxy, whatever mode it's set to, and it has no default
     * scenario. The result states both as `/definitions` does.
     */
    @Test
    fun aServiceWithoutScenariosStatesTheModeInWhichItAnswersAndNoScenario() {
        val script = script()

        val result = script.configured("serviceName=Billing&serviceResponseType=static&scenarioName=silver")

        assertEquals("updated" to "proxy", result.text("success") to result.text("serviceResponseType"))
        assertEquals(JsonNull to JsonNull, result["scenarioId"] to result["scenarioName"])
        assertEquals(10, result.size)
        assertEquals(ResponseMode.STATIC, script.serviceNamed("Billing").responseMode)
    }

    /**
     * The scenario "Identifier round trip": the identifiers that `/definitions` states are the ones that
     * `/config/service` takes, for a service and for a scenario. A number of Mockey 2.x names nothing, but
     * a request that also names the service by its name still finds it.
     */
    @Test
    fun theIdentifiersOfTheDefinitionsEndpointNameTheServiceAndTheScenario() {
        val script = script()
        val listed = script.document("/definitions").objects("serviceDefinitions")
        val orders = listed.single { it.text("serviceName") == "Orders" }
        val closed = orders.objects("scenarioArray").single { it.text("scenarioName") == "closed" }
        val before = script.engine.definitions

        val result = script.configured("serviceId=${orders.text("serviceId")}&scenarioId=${closed.text("scenarioId")}&hangTime=30")
        val byName = script.configured("serviceId=2&serviceName=Orders&scenarioId=1&scenarioName=open")

        assertEquals(listOf("updated", "Orders", "closed"), listOf("success", "serviceName", "scenarioName").map(result::text))
        assertEquals(orders.text("serviceId") to closed.text("scenarioId"), result.text("serviceId") to result.text("scenarioId"))
        assertEquals(listOf("updated", "Orders", "open"), listOf("success", "serviceName", "scenarioName").map(byName::text))
        val after = script.engine.definitions
        assertEquals(before.services.filter { it.name != "Orders" }, after.services.filter { it.name != "Orders" })
        assertEquals(30L, script.serviceNamed("Orders").delayMillis)
    }

    /**
     * The parameter `transientState` of Mockey 2.x kept a change out of the store. The server has no such
     * state for a service (DEV-22): it takes the parameter and doesn't use it. So a server in writable mode
     * stores the change, and a server in read-only mode stays in read-only mode.
     */
    @Test
    fun theTransientStateIsTakenAndNotUsed() {
        val storage = InMemoryDefinitionStorage()
        val script = writable(storage)
        ShimFixture.services(script.engine)
        val readOnly = script()

        val stored = script.configured("serviceName=Customers&transientState=true&hangTime=10")
        val held = readOnly.configured("serviceName=Customers&transientState=false&hangTime=10")

        assertEquals("updated" to "updated", stored.text("success") to held.text("success"))
        assertEquals(10L, runBlocking { storage.load() }.services.single { it.name == "Customers" }.delayMillis)
        assertFalse(script.engine.readOnly)
        assertTrue(readOnly.engine.readOnly)
        assertEquals(10L, readOnly.serviceNamed("Customers").delayMillis)
    }

    /**
     * A server in writable mode stores a change before the change takes effect. When it can't, the result
     * says that nothing was changed, and the log says why. The answer says nothing of the definitions
     * location, because the mock listener answers everyone.
     */
    @Test
    fun aChangeThatCannotBeStoredGetsAResultThatSaysSoAndChangesNothing() {
        val fixture = runBlocking { MockeyEngine.create() }.use { ShimFixture.services(it).let { _ -> it.definitions } }
        val script = writable(FullDisk(fixture))
        ServerLogging.configure(LogLevel.INFO)
        lateinit var answer: RawHttp.Answer

        val log = TestLog.of { answer = script.get("/config/service?serviceName=Customers&hangTime=10") }

        val text = "The server could not write its definitions location, and nothing was changed. The log of the server says why."
        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals("""{"result":{"fail":"$text"}}""", answer.body)
        assertEquals(fixture, script.engine.definitions)
        val errors = TestLog.messages(log, "ERROR")
        assertEquals("The request for /config/service of the legacy shim failed at the definitions location.", errors.first())
        assertTrue("StorageException: No space is left on /var/lib/mockey." in log, log)
        assertFalse("Customers" in log, log)
    }

    /** A storage that holds [stored] and takes nothing more. */
    private class FullDisk(
        private val stored: Definitions,
    ) : DefinitionStorage {
        override suspend fun load(): Definitions = stored

        override suspend fun save(definitions: Definitions): Unit = throw StorageException("No space is left on /var/lib/mockey.")
    }

    private companion object {
        /** The request rules of the recording `automation-config-inspector`, as its request encodes them and as they are. */
        const val ENCODED_RULES =
            "%7B%22parameters%22%3A+%5B%7B%22key%22%3A+%22ticker%22%2C+%22value_rule_arg%22%3A+%22goog%22%2C+" +
                "%22value_rule_type%22%3A+%22string_required%22%7D%5D%7D"
        const val RULES = """{"parameters": [{"key": "ticker", "value_rule_arg": "goog", "value_rule_type": "string_required"}]}"""

        /** The response schema of the recording `automation-config-schema`, as its request encodes it and as it is. */
        const val ENCODED_SCHEMA = "%7B%22type%22%3A+%22object%22%2C+%22required%22%3A+%5B%22id%22%5D%7D"
        const val SCHEMA = """{"type": "object", "required": ["id"]}"""

        /**
         * Returns the answer that the recordings of `/config/service` state for the new server after a change
         * to [service], with the member order of the recordings. What a recording leaves open is `<any>`.
         */
        fun updated(
            service: String,
            scenario: String,
            mode: String = "static",
            hangTime: Int = 0,
            inspector: String = "null",
            rules: Boolean = false,
            schema: Boolean = false,
        ): String =
            """{"result":{"hangTime":$hangTime,"serviceResponseType":"$mode","serviceSchemaEnableFlag":$schema,"success":"updated",""" +
                """"requestInspectorName":"$inspector","serviceName":"$service","serviceId":"<any>",""" +
                """"requestInspectorJsonRulesEnableFlag":$rules,"scenarioId":"<any>","scenarioName":"$scenario"}}"""
    }
}
