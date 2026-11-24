package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** What the services and the scenarios of a legacy document become: what each attribute is mapped to. */
class LegacyXmlDefinitionsTest {
    /** Imports a document that holds [services], and returns what they became. Nothing of them is an issue. */
    private fun imported(vararg services: String): List<Service> {
        val document = """<mockservice version="1.0" xml:lang="en-US">${services.joinToString("")}</mockservice>"""
        val result = LegacyDocumentImporter.import(document)
        assertEquals(emptyList(), result.issues)
        return result.definitions.services
    }

    /** Imports the service `Orders` with [attributes] and with [content], and returns what it became. */
    private fun service(
        attributes: String,
        content: String = "",
    ): Service = imported("""<service name="Orders" url="orders" $attributes>$content</service>""").single()

    /** Imports a scenario with [attributes] and with [content], and returns what it became. */
    private fun scenario(
        attributes: String,
        content: String = "",
    ): Scenario = service("", "<scenario $attributes>$content</scenario>").scenarios.single()

    /** Returns the criterion of a scenario with the match text [text], which the rules flag [flag] says how to read. */
    private fun matching(
        flag: String,
        text: String,
    ): MatchCriterion {
        val match = """<scenario_match scenario_match_evaluation_rules_flag="$flag"><![CDATA[$text]]></scenario_match>"""
        return scenario("", match).matchCriterion
    }

    // Services

    @Test
    fun everyAttributeOfAServiceIsCarriedOver() {
        val service =
            imported(
                """
                <service name="Customers" description="The customer directory" url="customer/{id}" tag="Team-A team-b"
                    hang_time="500" service_response_type="2" default_real_url_index="1"
                    request_inspector_name="com.example.SessionInspector">
                  <request_inspector_json_rules enable_flag="true"><![CDATA[{"url": []}]]></request_inspector_json_rules>
                  <response_schema enable_flag="true"><![CDATA[{"type": "object"}]]></response_schema>
                  <real_url url="https://one.example/customers"/>
                  <real_url url="https://two.example/customers"/>
                  <real_url url="https://three.example/customers"/>
                </service>
                """,
            ).single()

        val expected =
            Service(
                id = service.id,
                name = "Customers",
                mockUrl = "customer/{id}",
                description = "The customer directory",
                realUrls = listOf("one", "two", "three").map { "https://$it.example/customers" },
                defaultRealUrlIndex = 1,
                responseMode = ResponseMode.DYNAMIC,
                delayMillis = 500,
                tags = Tags.of("team-a", "team-b"),
                requestRules = """{"url": []}""",
                requestRulesEnabled = true,
                inspectorName = "com.example.SessionInspector",
                responseSchema = """{"type": "object"}""",
                responseSchemaEnabled = true,
            )
        assertEquals(expected, service)
    }

    @Test
    fun aServiceWithNothingButANameAndAUrlHasTheDefaults() {
        val service = imported("""<service name="Orders" url="orders"/>""").single()

        assertEquals(Service(id = service.id, name = "Orders", mockUrl = "orders"), service)
    }

    @Test
    fun theResponseModeIsReadFromItsNumber() {
        assertEquals(ResponseMode.PROXY, service("""service_response_type="0"""").responseMode)
        assertEquals(ResponseMode.STATIC, service("""service_response_type="1"""").responseMode)
        assertEquals(ResponseMode.DYNAMIC, service("""service_response_type="2"""").responseMode)
        assertEquals(ResponseMode.PROXY, service("""service_response_type="3"""").responseMode)
        assertEquals(ResponseMode.PROXY, service("""service_response_type="dynamic"""").responseMode)
        assertEquals(ResponseMode.PROXY, service("").responseMode)
    }

    @Test
    fun theDefaultRealUrlIsTheOneAtItsIndex() {
        fun withIndex(index: String): Service = service("""default_real_url_index="$index"""", """<real_url url="a"/><real_url url="b"/>""")

        assertEquals(0, withIndex("0").defaultRealUrlIndex)
        assertEquals(1, withIndex("1").defaultRealUrlIndex)
        assertEquals(0, withIndex("2").defaultRealUrlIndex)
        assertEquals(0, withIndex("-1").defaultRealUrlIndex)
        assertEquals(0, withIndex("second").defaultRealUrlIndex)
    }

    @Test
    fun aRealUrlIsListedOnce() {
        val service =
            imported(
                """
                <service name="Orders" url="orders" proxyurl="http://old.example/orders" default_real_url_index="2">
                  <real_url url="http://one.example/orders"/>
                  <real_url url=" "/>
                  <real_url url="HTTP://ONE.EXAMPLE/orders"/>
                  <real_url url="http://two.example/orders"/>
                </service>
                """,
            ).single()

        assertEquals(listOf("http://old.example/orders", "http://one.example/orders", "http://two.example/orders"), service.realUrls)
        assertEquals(2, service.defaultRealUrlIndex)
    }

    @Test
    fun whatMockey2WritesForNoValueIsNoInspectorName() {
        assertNull(service("""request_inspector_name="null"""").inspectorName)
        assertNull(service("""request_inspector_name=""""").inspectorName)
        assertNull(service("""request_inspector_name="0"""").inspectorName)
        assertNull(service("").inspectorName)
        assertEquals("com.example.Null", service("""request_inspector_name="com.example.Null"""").inspectorName)
    }

    @Test
    fun aTextOfAServiceThatIsBlankIsAbsent() {
        val service =
            imported(
                """
                <service name="Orders" url="orders" description="  " tag="">
                  <request_inspector_json_rules enable_flag="true"> </request_inspector_json_rules>
                  <response_schema enable_flag="false"/>
                </service>
                """,
            ).single()

        assertEquals(Service(id = service.id, name = "Orders", mockUrl = "orders", requestRulesEnabled = true), service)
    }

    // Scenarios

    @Test
    fun everyAttributeOfAScenarioIsCarriedOver() {
        val scenario =
            scenario(
                """id="3" name="gold" tag="Gold VIP" hang_time="12" http_resp_status_code="201" http_method_type="POST"""",
                """
                <scenario_match scenario_match_evaluation_rules_flag="false"><![CDATA[ABC123]]></scenario_match>
                <scenario_response><![CDATA[{"tier": "gold"}]]></scenario_response>
                <scenario_response_header><![CDATA[Content-Type: application/json; charset=utf-8 | X-Rate: 10]]></scenario_response_header>
                """,
            )

        val expected =
            Scenario(
                id = scenario.id,
                name = "gold",
                responseBody = """{"tier": "gold"}""",
                statusCode = 201,
                headers = listOf(Header("Content-Type", "application/json; charset=utf-8"), Header("X-Rate", "10")),
                matchCriterion = MatchCriterion.Text("ABC123"),
                methodRestriction = "POST",
                delayMillis = 12,
                tags = Tags.of("gold", "vip"),
            )
        assertEquals(expected, scenario)
    }

    @Test
    fun aScenarioWithNothingButANameHasTheDefaults() {
        val scenario = scenario("""name="plain"""")

        assertEquals(Scenario(id = scenario.id, name = "plain"), scenario)
    }

    @Test
    fun theRulesFlagSelectsTheCriterionType() {
        val rules = """{"url": [{"value_rule_arg": "\\b123\\b", "value_rule_type": "regex_required"}]}"""

        assertEquals(MatchCriterion.Rules(rules), matching("true", rules))
        assertEquals(MatchCriterion.Text(rules), matching("false", rules))
        assertEquals(MatchCriterion.Text("ABC123"), matching("false", "ABC123"))
        assertEquals(MatchCriterion.Rules("not a rule set"), matching("TRUE", "not a rule set"))
        assertEquals(MatchCriterion.Text("ABC123"), matching("", "ABC123"))
    }

    @Test
    fun aScenarioWithoutMatchTextAndWithoutTheRulesFlagHasNoCriterion() {
        assertEquals(MatchCriterion.None, matching("false", ""))
        assertEquals(MatchCriterion.None, matching("false", " \n\t "))
        assertEquals(MatchCriterion.None, matching("", ""))
        assertEquals(MatchCriterion.None, scenario("", "<scenario_match/>").matchCriterion)
        assertEquals(MatchCriterion.None, scenario("""name="plain"""").matchCriterion)
    }

    /** The rules flag alone says that the criterion of a scenario is a rule set. Without a match text, the rule set has no text. */
    @Test
    fun theRulesFlagMakesARuleSetOfAMatchTextThatIsBlank() {
        val asMockey2WritesIt = """<scenario_match scenario_match_evaluation_rules_flag="true"/>"""

        assertEquals(MatchCriterion.Rules(""), matching("true", ""))
        assertEquals(MatchCriterion.Rules(""), matching("true", " \n\t "))
        assertEquals(MatchCriterion.Rules(""), scenario("", asMockey2WritesIt).matchCriterion)
    }

    @Test
    fun headerTextIsSplitIntoHeaders() {
        fun headersOf(text: String): List<Header> =
            scenario("", "<scenario_response_header><![CDATA[$text]]></scenario_response_header>").headers

        assertEquals(
            listOf(Header("Content-Type", "application/json; charset=utf-8"), Header("X-Rate", "10")),
            headersOf("Content-Type: application/json; charset=utf-8 | X-Rate: 10"),
        )
        assertEquals(listOf(Header("Location", "http://example.com/a:b")), headersOf("no colon | Location: http://example.com/a:b |"))
        assertEquals(listOf(Header("Set-Cookie", "a=1"), Header("Set-Cookie", "b=2")), headersOf("Set-Cookie: a=1|Set-Cookie: b=2"))
        assertEquals(emptyList(), headersOf(""))
        assertEquals(emptyList(), scenario("""name="plain"""").headers)
    }

    @Test
    fun whatMockey2WritesForNoValueIsNoMethod() {
        assertNull(scenario("""http_method_type="null"""").methodRestriction)
        assertNull(scenario("""http_method_type=""""").methodRestriction)
        assertEquals("get", scenario("""http_method_type="get"""").methodRestriction)
        assertEquals("*", scenario("""http_method_type="*"""").methodRestriction)
    }

    // How Mockey 2.x reads a document

    @Test
    fun theTextOfAnElementCountsWithoutTheWhitespaceAroundIt() {
        val scenario =
            scenario(
                "",
                "<scenario_match scenario_match_evaluation_rules_flag=\"false\">\n ABC 123\t</scenario_match>" +
                    "<scenario_response><![CDATA[\n\n  first line\n\n\tthird line\nlast line\n\n]]></scenario_response>" +
                    "<scenario_response_header>\n  X-Rate: 10\n</scenario_response_header>",
            )

        assertEquals("first line\n\n\tthird line\nlast line", scenario.responseBody)
        assertEquals(MatchCriterion.Text("ABC 123"), scenario.matchCriterion)
        assertEquals(listOf(Header("X-Rate", "10")), scenario.headers)
    }

    @Test
    fun aNumberThatCannotBeReadCountsAsItsDefault() {
        val scenario = scenario("""hang_time="soon" http_resp_status_code="OK"""")
        val service = service("""hang_time="-5"""")

        assertEquals(0, scenario.delayMillis)
        assertEquals(200, scenario.statusCode)
        assertEquals(0, service.delayMillis)
        assertEquals(250, scenario("""hang_time=" 250 """").delayMillis)
    }

    @Test
    fun aFlagIsOnForTheTextsThatMockey2TakesAsTrue() {
        fun rulesEnabled(flag: String): Boolean = service("", """<request_inspector_json_rules enable_flag="$flag"/>""").requestRulesEnabled

        assertEquals(listOf(true, true, true, true, true), listOf("true", "True", "yes", "on", "1").map(::rulesEnabled))
        assertEquals(listOf(false, false, false, false), listOf("false", "no", "0", "").map(::rulesEnabled))
    }

    @Test
    fun whatIsImportedGetsIdentifiersOfItsOwn() {
        val service = """<service name="%s" url="%s"><scenario id="1" name="first"/><scenario id="2" name="second"/></service>"""
        val services = imported(service.format("Orders", "orders"), service.format("Customers", "customers"))
        val again = imported(service.format("Orders", "orders"))

        val scenarioIds = services.flatMap { it.scenarios }.map { it.id }
        assertEquals(4, scenarioIds.toSet().size)
        assertEquals(2, services.map { it.id }.toSet().size)
        assertNotEquals(services.first().id, again.single().id)
        assertEquals(emptyList(), again.flatMap { it.scenarios }.filter { it.id in scenarioIds })
    }
}
