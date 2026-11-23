package io.github.dnedrow.mockey.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelDefaultsTest {
    @Test
    fun aServiceWithOnlyANameAndAMockUrlHasTheDefaults() {
        val service = Service(id = ServiceId.generate(), name = "Customers", mockUrl = "customer/{id}")

        assertEquals(ResponseMode.PROXY, service.responseMode)
        assertEquals(0L, service.delayMillis)
        assertEquals(Tags.EMPTY, service.tags)
        assertEquals(emptyList(), service.realUrls)
        assertEquals(0, service.defaultRealUrlIndex)
        assertEquals(emptyList(), service.scenarios)
        assertFalse(service.requestRulesEnabled)
        assertFalse(service.responseSchemaEnabled)
        assertNull(service.description)
        assertNull(service.defaultScenarioId)
        assertNull(service.errorScenarioId)
        assertNull(service.requestRules)
        assertNull(service.inspectorName)
        assertNull(service.responseSchema)
    }

    @Test
    fun aScenarioWithOnlyANameAndABodyHasTheDefaults() {
        val scenario = Scenario(id = ScenarioId.generate(), name = "gold", responseBody = """{"plan":"gold"}""")

        assertEquals(200, scenario.statusCode)
        assertEquals(emptyList(), scenario.headers)
        assertEquals(MatchCriterion.None, scenario.matchCriterion)
        assertNull(scenario.methodRestriction)
        assertEquals(0L, scenario.delayMillis)
        assertEquals(Tags.EMPTY, scenario.tags)
    }

    @Test
    fun aScenarioWithOnlyANameHasAnEmptyBody() {
        assertEquals("", Scenario(id = ScenarioId.generate(), name = "empty").responseBody)
    }

    @Test
    fun aPlanWithOnlyANameHasTheDefaults() {
        val plan = Plan(id = PlanId.generate(), name = "Gold path")

        assertNull(plan.description)
        assertEquals(Tags.EMPTY, plan.tags)
        assertEquals(emptyList(), plan.items)
    }

    @Test
    fun aPlanItemWithOnlyAServiceNameDescribesANewService() {
        val item = PlanItem(serviceName = "Customers")

        assertNull(item.scenarioName)
        assertEquals(ResponseMode.PROXY, item.responseMode)
        assertEquals(0L, item.delayMillis)
    }

    @Test
    fun aTwistConfigurationWithOnlyANameHasNoPairs() {
        assertEquals(emptyList(), TwistConfiguration(id = TwistId.generate(), name = "to-qa2").pairs)
    }

    @Test
    fun anOutboundProxyWithOnlyAUrlIsDisabledAndHasNoCredentials() {
        val proxy = OutboundProxy(url = "http://proxy.example.com:3128")

        assertFalse(proxy.enabled)
        assertNull(proxy.credentials)
    }

    @Test
    fun emptyDefinitionsHaveNothingDefinedAndNothingSet() {
        val definitions = Definitions.EMPTY

        assertEquals(emptyList(), definitions.services)
        assertEquals(emptyList(), definitions.plans)
        assertEquals(emptyList(), definitions.twists)
        assertNull(definitions.settings.enabledTwistId)
        assertNull(definitions.settings.outboundProxy)
        assertNull(definitions.settings.universalErrorScenario)
        assertNull(definitions.settings.defaultPlanId)
        assertEquals(Definitions(), definitions)
    }

    @Test
    fun aServiceWithoutScenariosAnswersAsAProxy() {
        val service = Service(id = ServiceId.generate(), name = "Customers", mockUrl = "customer/{id}")

        assertEquals(ResponseMode.PROXY, service.copy(responseMode = ResponseMode.STATIC).effectiveResponseMode)
        assertEquals(ResponseMode.PROXY, service.copy(responseMode = ResponseMode.DYNAMIC).effectiveResponseMode)
    }

    @Test
    fun aServiceWithScenariosAnswersInItsConfiguredMode() {
        val service =
            Service(
                id = ServiceId.generate(),
                name = "Customers",
                mockUrl = "customer/{id}",
                scenarios = listOf(Scenario(id = ScenarioId.generate(), name = "gold")),
            )

        ResponseMode.entries.forEach { mode ->
            assertEquals(mode, service.copy(responseMode = mode).effectiveResponseMode)
        }
    }

    @Test
    fun aTextCriterionIsNeverBlank() {
        assertFailsWith<IllegalArgumentException> { MatchCriterion.Text("") }
        assertFailsWith<IllegalArgumentException> { MatchCriterion.Text(" \t") }
        assertEquals(" ABC123 ", MatchCriterion.Text(" ABC123 ").text)
    }
}
