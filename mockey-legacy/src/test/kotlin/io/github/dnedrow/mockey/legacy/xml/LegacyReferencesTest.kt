package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistPair
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What becomes of the numbers with which a legacy document refers to its scenarios, services, plans, and
 * twist configurations, and of the plans, twist configurations, and settings that such numbers name.
 */
class LegacyReferencesTest {
    @TempDir
    lateinit var directory: Path

    private fun imported(
        content: String,
        attributes: String = "",
    ): LegacyImportResult = LegacyDocumentImporter.import("""<mockservice version="1.0" $attributes>$content</mockservice>""")

    /** Returns the definitions of [this], of which nothing is an issue. */
    private fun LegacyImportResult.clean(): Definitions {
        assertEquals(emptyList(), issues)
        return definitions
    }

    /** Returns the message of the one issue of [this], which is a reference that names nothing. */
    private fun LegacyImportResult.dangling(): String {
        assertEquals(listOf(LegacyXmlIssueKind.DANGLING_REFERENCE), issues.map { it.kind })
        return issues.single().message
    }

    private fun service(
        name: String,
        attributes: String = "",
        scenarios: String = SCENARIOS,
    ) = """<service name="$name" url="${name.lowercase()}" $attributes>$scenarios</service>"""

    private fun Service.scenario(id: Any?): Scenario? = scenarios.firstOrNull { it.id == id }

    private fun Definitions.service(name: String): Service = services.single { it.name == name }

    // The default scenario and the error scenario of a service

    @Test
    fun theDefaultScenarioIsTheScenarioWithItsNumber() {
        val orders = imported(service("Orders", """default_scenario_id="3"""")).clean().service("Orders")

        assertEquals("third", orders.scenario(orders.defaultScenarioId)?.name)
        assertNull(orders.errorScenarioId)
    }

    @Test
    fun theErrorScenarioIsTheScenarioWithItsNumber() {
        val orders = imported(service("Orders", """default_scenario_id="1" error_scenario_id="2"""")).clean().service("Orders")

        assertEquals("first", orders.scenario(orders.defaultScenarioId)?.name)
        assertEquals("second", orders.scenario(orders.errorScenarioId)?.name)
    }

    @Test
    fun aNumberNamesAScenarioOfItsOwnServiceOnly() {
        val services = service("Orders", """error_scenario_id="2"""") + service("Customers", """error_scenario_id="2"""", OTHER_SCENARIOS)

        val definitions = imported(services).clean()

        val (orders, customers) = definitions.services
        assertEquals("second", orders.scenario(orders.errorScenarioId)?.name)
        assertEquals("other second", customers.scenario(customers.errorScenarioId)?.name)
    }

    @Test
    fun whatMockey2WritesForNoValueIsNoReference() {
        for (placeholder in listOf("null", "0", "", " ")) {
            val attributes = """default_scenario_id="$placeholder" error_scenario_id="$placeholder""""
            val root =
                """universal_error_service_id="$placeholder" universal_error_scenario_id="$placeholder" """ +
                    """universal_twist_info_id="$placeholder" default_service_plan_id="$placeholder""""

            val definitions = imported(service("Orders", attributes) + PLANS + TWISTS, root).clean()

            assertNull(definitions.service("Orders").defaultScenarioId, "default scenario '$placeholder'")
            assertNull(definitions.service("Orders").errorScenarioId, "error scenario '$placeholder'")
            assertEquals(Definitions.EMPTY.settings, definitions.settings, "settings '$placeholder'")
        }
        assertNull(imported(service("Orders")).clean().service("Orders").defaultScenarioId)
    }

    @Test
    fun aDefaultScenarioThatTheServiceLacksIsDroppedAndReported() {
        val result = imported(service("Orders", """default_scenario_id="99" error_scenario_id="1""""))

        val orders = result.definitions.service("Orders")
        assertNull(orders.defaultScenarioId)
        assertEquals("first", orders.scenario(orders.errorScenarioId)?.name)
        assertEquals(
            "The service 'Orders' names the scenario 99 as its default scenario (default_scenario_id), but it has no scenario " +
                "with that number. The earliest scenario of the service is its default scenario.",
            result.dangling(),
        )
    }

    @Test
    fun anErrorScenarioThatTheServiceLacksIsDroppedAndReported() {
        val result = imported(service("Orders", """default_scenario_id="1" error_scenario_id="9""""))

        val orders = result.definitions.service("Orders")
        assertNull(orders.errorScenarioId)
        assertEquals("first", orders.scenario(orders.defaultScenarioId)?.name)
        assertEquals(
            "The service 'Orders' names the scenario 9 as its error scenario (error_scenario_id), but it has no scenario " +
                "with that number. The service has no error scenario.",
            result.dangling(),
        )
    }

    @Test
    fun aReferenceThatIsNoNumberNamesNothing() {
        val result = imported(service("Orders", """error_scenario_id="second""""))

        assertNull(result.definitions.service("Orders").errorScenarioId)
        assertTrue("names the scenario second as its error scenario" in result.dangling(), result.dangling())
    }

    // The numbers of scenarios

    @Test
    fun scenariosAreInTheOrderOfTheirNumbers() {
        val byName = """<scenario id="2" name="alpha"/><scenario id="3" name="beta"/><scenario id="1" name="gamma"/>"""

        val orders = imported(service("Orders", """default_scenario_id="1"""", byName)).clean().service("Orders")

        assertEquals(listOf("gamma", "alpha", "beta"), orders.scenarios.map { it.name })
        assertEquals("gamma", orders.scenario(orders.defaultScenarioId)?.name)
    }

    @Test
    fun aScenarioWithoutANumberGetsTheNumberAfterTheHighestSoFar() {
        val scenarios = """<scenario id="5" name="five"/><scenario name="six"/><scenario id="2" name="two"/><scenario name="seven"/>"""

        val document = service("Orders", """default_scenario_id="6" error_scenario_id="7"""", scenarios)
        val orders = imported(document).clean().service("Orders")

        assertEquals(listOf("two", "five", "six", "seven"), orders.scenarios.map { it.name })
        assertEquals("six", orders.scenario(orders.defaultScenarioId)?.name)
        assertEquals("seven", orders.scenario(orders.errorScenarioId)?.name)
    }

    @Test
    fun aNumberThatTwoScenariosHaveNamesTheLaterOne() {
        val scenarios = """<scenario id="1" name="earlier"/><scenario id="1" name="later"/>"""

        val orders = imported(service("Orders", """default_scenario_id="1"""", scenarios)).clean().service("Orders")

        assertEquals(listOf("earlier", "later"), orders.scenarios.map { it.name })
        assertEquals("later", orders.scenario(orders.defaultScenarioId)?.name)
    }

    // The universal error scenario

    @Test
    fun theUniversalErrorScenarioIsAScenarioOfTheServiceAtItsPlace() {
        val services = service("Orders") + service("Customers", scenarios = OTHER_SCENARIOS) + service("Billing")

        val definitions = imported(services, """universal_error_service_id="2" universal_error_scenario_id="5"""").clean()

        val customers = definitions.service("Customers")
        val designated = definitions.settings.universalErrorScenario
        assertEquals(customers.id, designated?.serviceId)
        assertEquals("other fifth", customers.scenario(designated?.scenarioId)?.name)
    }

    @Test
    fun aUniversalErrorScenarioThatTheDocumentLacksIsDroppedAndReported() {
        val services = service("Orders") + service("Customers", scenarios = OTHER_SCENARIOS)

        fun dangling(
            service: String,
            scenario: String,
        ): String {
            val result = imported(services, """universal_error_service_id="$service" universal_error_scenario_id="$scenario"""")
            assertNull(result.definitions.settings.universalErrorScenario)
            return result.dangling()
        }

        assertEquals(
            "The document names the scenario 5 of the service 1 as the universal error scenario, but the service 'Orders' at " +
                "the place 1 has no scenario with the number 5. No universal error scenario is designated.",
            dangling("1", "5"),
        )
        assertTrue("but it holds no service at the place 3. It holds 2 of them." in dangling("3", "1"), dangling("3", "1"))
        assertTrue("but it holds no service at the place -1." in dangling("-1", "1"), dangling("-1", "1"))
        assertTrue("but it holds no service at the place first." in dangling("first", "1"), dangling("first", "1"))
        assertTrue("but it names no scenario (universal_error_scenario_id)." in dangling("1", "0"), dangling("1", "0"))
        assertTrue("but it names no service (universal_error_service_id)." in dangling("null", "1"), dangling("null", "1"))
    }

    @Test
    fun aServiceThatCouldNotBeReadKeepsItsPlace() {
        fun serviceFile(name: String) {
            val file = directory.resolve("mockey_def_depot/$name/$name.xml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "<mockservice>${service(name)}</mockservice>")
        }
        serviceFile("second")
        serviceFile("third")
        val references = listOf("first", "second", "third").joinToString("") { """<serviceref file="mockey_def_depot/$it/$it.xml"/>""" }

        fun designating(place: Int): LegacyImportResult {
            val attributes = """universal_error_service_id="$place" universal_error_scenario_id="2""""
            Files.writeString(directory.resolve("mock_service_definitions.xml"), "<mockservice $attributes>$references</mockservice>")
            return LegacyDirectoryImporter(directory).import()
        }

        val second = designating(2)
        val first = designating(1)

        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), second.issues.map { it.kind })
        assertEquals(listOf("second", "third"), second.definitions.services.map { it.name })
        val designated = second.definitions.settings.universalErrorScenario
        val holder = second.definitions.service("second")
        assertEquals(holder.id, designated?.serviceId)
        assertEquals("second", holder.scenario(designated?.scenarioId)?.name)
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE, LegacyXmlIssueKind.DANGLING_REFERENCE), first.issues.map { it.kind })
        assertTrue("but the service at the place 1 could not be read." in first.issues.last().message, first.issues.toString())
        assertNull(first.definitions.settings.universalErrorScenario)
    }

    // Twist configurations

    @Test
    fun aTwistConfigurationIsCarriedOver() {
        val twists = imported(TWISTS).clean().twists

        assertEquals(
            listOf(
                TwistConfiguration(twists[0].id, "to qa2", listOf(TwistPair("qa1.example.com", "qa2.example.com"), TwistPair("a.b", "c"))),
                TwistConfiguration(twists[1].id, "to sandbox", listOf(TwistPair("example.com", "example.org"))),
            ),
            twists,
        )
    }

    @Test
    fun theEnabledTwistConfigurationIsTheOneWithItsNumber() {
        val definitions = imported(TWISTS, """universal_twist_info_id="7"""").clean()

        assertEquals("to sandbox", definitions.twists.single { it.id == definitions.settings.enabledTwistId }.name)
    }

    @Test
    fun anEnabledTwistConfigurationThatTheDocumentLacksIsDroppedAndReported() {
        val result = imported(TWISTS, """universal_twist_info_id="2"""")

        assertNull(result.definitions.settings.enabledTwistId)
        assertEquals(listOf("to qa2", "to sandbox"), result.definitions.twists.map { it.name })
        assertEquals(
            "The document enables the twist configuration 2 (universal_twist_info_id), but it holds no twist configuration " +
                "with that number. No twist configuration is enabled.",
            result.dangling(),
        )
    }

    // Plans

    @Test
    fun aPlanIsCarriedOver() {
        val plans = imported(PLANS).clean().plans

        val gold =
            Plan(
                id = plans[0].id,
                name = "Gold path",
                description = "Every customer is a gold customer.",
                tags = Tags.of("premium", "smoke"),
                items =
                    listOf(
                        PlanItem("Customers", "gold", ResponseMode.STATIC, 0),
                        PlanItem("Orders", null, ResponseMode.PROXY, 250),
                        PlanItem("Billing", "paid", ResponseMode.DYNAMIC, 0),
                    ),
            )
        assertEquals(listOf(gold, Plan(id = plans[1].id, name = "Silver path")), plans)
    }

    @Test
    fun theDefaultPlanIsThePlanWithItsNumber() {
        val definitions = imported(PLANS, """default_service_plan_id="4"""").clean()

        assertEquals("Silver path", definitions.plans.single { it.id == definitions.settings.defaultPlanId }.name)
    }

    @Test
    fun aDefaultPlanThatTheDocumentLacksIsDroppedAndReported() {
        val result = imported(PLANS, """default_service_plan_id="2"""")

        assertNull(result.definitions.settings.defaultPlanId)
        assertEquals(listOf("Gold path", "Silver path"), result.definitions.plans.map { it.name })
        assertEquals(
            "The document names the plan 2 as the default plan (default_service_plan_id), but it holds no plan with that " +
                "number. No plan is the default plan.",
            result.dangling(),
        )
    }

    @Test
    fun plansAndTwistConfigurationsAreInTheOrderOfTheirNumbers() {
        val plans = """<service_plan id="2" name="second"/><service_plan id="1" name="first"/>"""
        val twists = """<twist_config id="9" name="ninth"/><twist_config id="3" name="third"/>"""

        val definitions = imported(plans + twists, """default_service_plan_id="1" universal_twist_info_id="9"""").clean()

        assertEquals(listOf("first", "second"), definitions.plans.map { it.name })
        assertEquals(listOf("third", "ninth"), definitions.twists.map { it.name })
        assertEquals(definitions.plans.first().id, definitions.settings.defaultPlanId)
        assertEquals(definitions.twists.last().id, definitions.settings.enabledTwistId)
    }

    // The outbound proxy

    @Test
    fun theSettingsOfTheOutboundProxyAreCarriedOver() {
        fun proxy(
            enabled: Boolean,
            url: String,
        ): OutboundProxy? = imported("""<proxy_settings proxy_enabled="$enabled" proxy_url="$url"/>""").clean().settings.outboundProxy

        assertEquals(OutboundProxy(PROXY, enabled = true), proxy(enabled = true, PROXY))
        assertEquals(OutboundProxy(PROXY, enabled = false), proxy(enabled = false, " $PROXY "))
        assertNull(proxy(enabled = false, ""))
        assertNull(proxy(enabled = true, " "))
        assertNull(imported("").clean().settings.outboundProxy)
    }

    // The definitions that Mockey 2.x wrote

    @Test
    fun theReferencesOfADirectoryThatMockey2WroteAreResolved() {
        val definitions = LegacyDirectoryImporter(LegacyFixtures.directory).import().clean()

        val customers = definitions.service("Customer Directory")
        val orders = definitions.service("Orders")
        assertEquals(listOf("silver", "gold", "Directory down"), customers.scenarios.map { it.name })
        assertEquals("gold", customers.scenario(customers.defaultScenarioId)?.name)
        assertEquals("Directory down", customers.scenario(customers.errorScenarioId)?.name)
        assertEquals(listOf("order list", "no orders"), orders.scenarios.map { it.name })
        assertEquals("no orders", orders.scenario(orders.defaultScenarioId)?.name)
        assertNull(orders.errorScenarioId)
        assertNull(definitions.service("Billing").defaultScenarioId)

        val settings = definitions.settings
        assertEquals(customers.id, settings.universalErrorScenario?.serviceId)
        assertEquals("Directory down", customers.scenario(settings.universalErrorScenario?.scenarioId)?.name)
        assertEquals(OutboundProxy(PROXY), settings.outboundProxy)
        assertEquals(listOf("Gold path", "Slow day"), definitions.plans.map { it.name })
        assertEquals("Slow day", definitions.plans.single { it.id == settings.defaultPlanId }.name)
        assertEquals(listOf(PlanItem("Orders", "no orders", ResponseMode.STATIC, 50)), definitions.plans.last().items)
        assertEquals("to qa2", definitions.twists.single { it.id == settings.enabledTwistId }.name)
        assertEquals(listOf(TwistPair("qa1.example.com", "qa2.example.com")), definitions.twists.single().pairs)
    }

    private companion object {
        const val PROXY = "http://proxy.example.com:3128"
        const val SCENARIOS = """<scenario id="1" name="first"/><scenario id="2" name="second"/><scenario id="3" name="third"/>"""
        const val OTHER_SCENARIOS = """<scenario id="2" name="other second"/><scenario id="5" name="other fifth"/>"""

        const val TWISTS =
            """<twist_config id="1" name="to qa2">""" +
                """<twist_pattern origination="qa1.example.com" destination="qa2.example.com"/>""" +
                """<twist_pattern origination="a.b" destination="c"/>""" +
                """</twist_config>""" +
                """<twist_config id="7" name="to sandbox"><twist_pattern origination="example.com" destination="example.org"/></twist_config>"""

        const val PLANS =
            """<service_plan id="1" name="Gold path" description="Every customer is a gold customer." tag="Premium smoke">""" +
                """<plan_item hang_time="0" scenario_name="gold" service_name="Customers" service_response_type="1"/>""" +
                """<plan_item hang_time="250" scenario_name="" service_name="Orders" service_response_type="0"/>""" +
                """<plan_item hang_time="0" scenario_name="paid" service_name="Billing" service_response_type="2"/>""" +
                """</service_plan>""" +
                """<service_plan id="4" name="Silver path" description="" tag=""/>"""
    }
}
