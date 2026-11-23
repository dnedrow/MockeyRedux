package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DiagnosticsTest {
    private fun scenario(
        name: String,
        body: String = "",
        criterion: MatchCriterion = MatchCriterion.None,
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body, matchCriterion = criterion)

    private fun service(
        name: String,
        mockUrl: String,
        vararg realUrls: String,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, realUrls = realUrls.toList())

    private fun Definitions.diagnostics(): List<Diagnostic> =
        diagnostics(installedInspectors = setOf("session-check")) { it.startsWith("{") }

    @Test
    fun definitionsWithoutProblemsHaveNoDiagnostics() {
        val customers =
            service("Customers", "customer/{id}", "http://api.example.com/customers/{id}").copy(
                inspectorName = "session-check",
                requestRules = """{"headers":[]}""",
                scenarios =
                    listOf(
                        scenario("gold", "a"),
                        scenario("gold", "b"),
                        scenario("rules", criterion = MatchCriterion.Rules("""{"url":[]}""")),
                    ),
            )
        val orders = service("Orders", "orders", "http://api.example.com/orders")

        assertEquals(emptyList(), Definitions(services = listOf(customers, orders)).diagnostics())
        assertEquals(emptyList(), Definitions.EMPTY.diagnostics())
    }

    @Test
    fun servicesThatShareAMockUrlPatternAreReported() {
        val customers = service("Customers", "customer/{id}")
        val clients = service("Clients", " Customer/{ID} ")
        val orders = service("Orders", "orders")

        val diagnostics = Definitions(services = listOf(customers, clients, orders)).diagnostics()

        assertEquals(listOf(DiagnosticKind.SHARED_MOCK_URL, DiagnosticKind.SHARED_MOCK_URL), diagnostics.map { it.kind })
        assertEquals(listOf(customers.id to clients.id, clients.id to customers.id), diagnostics.map { it.serviceId to it.otherServiceId })
        assertEquals("Customers", diagnostics.first().serviceName)
        assertContains(diagnostics.first().message, "'Clients'")
        assertContains(diagnostics.first().message, "customer/{id}")
    }

    @Test
    fun servicesThatShareARealUrlAreReported() {
        val orders = service("Orders", "orders", "http://api.example.com/orders", "http://qa.example.com/orders")
        val billing = service("Billing", "billing", "http://api.example.com/billing", " HTTP://QA.example.com/orders ")

        val diagnostics = Definitions(services = listOf(orders, billing)).diagnostics()

        assertEquals(listOf(DiagnosticKind.SHARED_REAL_URL, DiagnosticKind.SHARED_REAL_URL), diagnostics.map { it.kind })
        assertEquals(listOf(orders.id to billing.id, billing.id to orders.id), diagnostics.map { it.serviceId to it.otherServiceId })
        assertContains(diagnostics.first().message, "'Billing'")
        assertContains(diagnostics.first().message, "http://qa.example.com/orders")
    }

    @Test
    fun scenariosOfOneServiceWithTheSameNameAndResponseBodyAreReported() {
        val first = scenario("ok", "{\"status\":\"ok\"}")
        val second = scenario(" OK ", "{\"status\":\"OK\"}\n")
        val third = scenario("ok", "{\"status\":\"ok\"}")
        val customers = service("Customers", "customer/{id}").copy(scenarios = listOf(first, scenario("timeout"), second, third))
        val orders = service("Orders", "orders").copy(scenarios = listOf(scenario("ok", "{\"status\":\"ok\"}")))

        val diagnostics = Definitions(services = listOf(customers, orders)).diagnostics()

        val diagnostic = diagnostics.single()
        assertEquals(DiagnosticKind.DUPLICATE_SCENARIOS, diagnostic.kind)
        assertEquals(customers.id, diagnostic.serviceId)
        assertEquals("Customers", diagnostic.serviceName)
        assertEquals(listOf(first.id, second.id, third.id), diagnostic.scenarioIds)
        assertContains(diagnostic.message, "'ok'")
    }

    @Test
    fun aServiceThatNamesAnInspectorThatIsNotInstalledIsReported() {
        val customers = service("Customers", "customer/{id}").copy(inspectorName = "com.example.LegacyInspector")
        val orders = service("Orders", "orders").copy(inspectorName = "session-check")

        val diagnostic = Definitions(services = listOf(customers, orders)).diagnostics().single()

        assertEquals(DiagnosticKind.UNKNOWN_INSPECTOR, diagnostic.kind)
        assertEquals(customers.id, diagnostic.serviceId)
        assertEquals("Customers", diagnostic.serviceName)
        assertContains(diagnostic.message, "'com.example.LegacyInspector'")
    }

    @Test
    fun ruleSetsThatAreNotWellFormedAreReported() {
        val broken = scenario("by rules", criterion = MatchCriterion.Rules("not a rule set"))
        val customers =
            service("Customers", "customer/{id}").copy(
                requestRules = "also not a rule set",
                scenarios = listOf(scenario("text", criterion = MatchCriterion.Text("not a rule set")), broken),
            )

        val diagnostics = Definitions(services = listOf(customers)).diagnostics()

        assertEquals(listOf(DiagnosticKind.UNREADABLE_RULE_SET, DiagnosticKind.UNREADABLE_RULE_SET), diagnostics.map { it.kind })
        assertEquals(listOf(emptyList(), listOf(broken.id)), diagnostics.map { it.scenarioIds })
        assertContains(diagnostics.last().message, "'by rules'")
    }

    @Test
    fun aRuleSetIsReportedWhetherOrNotItIsEnabled() {
        val customers = service("Customers", "customer/{id}").copy(requestRules = "not a rule set", requestRulesEnabled = false)

        assertEquals(listOf(DiagnosticKind.UNREADABLE_RULE_SET), Definitions(services = listOf(customers)).diagnostics().map { it.kind })
    }
}
