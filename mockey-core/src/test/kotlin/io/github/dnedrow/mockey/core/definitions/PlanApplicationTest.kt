package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.TwistId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanApplicationTest {
    private fun scenario(name: String) = Scenario(id = ScenarioId.generate(), name = name, responseBody = "$name customer")

    private fun service(
        name: String,
        mockUrl: String,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, scenarios = scenarios.toList()).withValidDesignations()

    private val silver = scenario("silver")
    private val gold = scenario("gold")

    /** In proxy mode with the default scenario `silver` and a delay of 250 ms. */
    private val customers = service("Customers", "customer/{id}", silver, gold).copy(delayMillis = 250)
    private val ok = scenario("ok")
    private val failed = scenario("failed")
    private val orders = service("Orders", "orders", ok, failed).copy(responseMode = ResponseMode.DYNAMIC, delayMillis = 40)
    private val billing = service("Billing", "http://api.example.com/billing")
    private val services = Definitions(services = listOf(customers, orders, billing))

    private val goldItem = PlanItem("Customers", "gold", ResponseMode.STATIC, 0)

    private fun plan(vararg items: PlanItem) = Plan(PlanId.generate(), "Gold path", items = items.toList())

    /** Applies a plan with [items] to these definitions. */
    private fun Definitions.applied(vararg items: PlanItem) =
        plan(*items).let { copy(plans = plans + it).changed(PlanChanges.apply(it.id)) }

    private fun Definitions.named(name: String): Service = services.single { it.name == name }

    // Applying a plan

    @Test
    fun theServicesOfAPlanArePutIntoTheStateOfThePlan() {
        val applied = services.applied(goldItem, PlanItem("Orders", "failed", ResponseMode.STATIC, 900))

        val expected = customers.copy(responseMode = ResponseMode.STATIC, defaultScenarioId = gold.id, delayMillis = 0)
        assertEquals(expected, applied.definitions.named("Customers"))
        assertEquals(
            orders.copy(responseMode = ResponseMode.STATIC, defaultScenarioId = failed.id, delayMillis = 900),
            applied.definitions.named("Orders"),
        )
        assertEquals(emptyList(), applied.value.skippedItems)
        assertEquals(emptyList(), applied.value.unresolvedScenarios)
    }

    @Test
    fun theServicesOutsideAPlanStayAsTheyAre() {
        val applied = services.applied(goldItem)

        assertEquals(listOf(orders, billing), applied.definitions.services.drop(1))
        assertEquals(listOf(customers.id, orders.id, billing.id), applied.definitions.services.map { it.id })
    }

    @Test
    fun namesAreComparedWithoutRegardToLetterCaseAndSurroundingWhitespace() {
        val applied = services.applied(PlanItem("  CUSTOMERS\t", " Gold ", ResponseMode.DYNAMIC, 10))

        assertEquals(
            customers.copy(responseMode = ResponseMode.DYNAMIC, defaultScenarioId = gold.id, delayMillis = 10),
            applied.definitions.named("Customers"),
        )
        assertEquals(emptyList(), applied.value.skippedItems + applied.value.unresolvedScenarios)
    }

    @Test
    fun anItemWhoseServiceDoesNotExistIsSkippedAndTheOthersApply() {
        val deleted = PlanItem("Shipping", "ok", ResponseMode.STATIC, 5)
        val gone = PlanItem("Customer", "gold")

        val applied = services.applied(deleted, goldItem, gone)

        assertEquals(listOf(deleted, gone), applied.value.skippedItems)
        assertEquals(emptyList(), applied.value.unresolvedScenarios)
        assertEquals(gold.id, applied.definitions.named("Customers").defaultScenarioId)
        assertEquals(listOf("Customers", "Orders", "Billing"), applied.definitions.services.map { it.name })
    }

    @Test
    fun anItemWhoseScenarioDoesNotExistSetsTheModeAndTheDelayAndLeavesTheDefaultScenario() {
        val applied = services.applied(PlanItem("customers", "platinum", ResponseMode.DYNAMIC, 70))

        assertEquals(customers.copy(responseMode = ResponseMode.DYNAMIC, delayMillis = 70), applied.definitions.named("Customers"))
        assertEquals(listOf(UnresolvedScenario("Customers", "platinum")), applied.value.unresolvedScenarios)
        assertEquals(emptyList(), applied.value.skippedItems)
    }

    @Test
    fun aScenarioOfAnotherServiceIsNoScenarioOfTheItem() {
        val applied = services.applied(PlanItem("Customers", "ok", ResponseMode.STATIC), PlanItem("Billing", "gold"))

        assertEquals(silver.id, applied.definitions.named("Customers").defaultScenarioId)
        assertNull(applied.definitions.named("Billing").defaultScenarioId)
        assertEquals(
            listOf(UnresolvedScenario("Customers", "ok"), UnresolvedScenario("Billing", "gold")),
            applied.value.unresolvedScenarios,
        )
    }

    @Test
    fun anItemWithoutAScenarioNameLeavesTheDefaultScenarioAndReportsNothing() {
        val applied =
            services.applied(
                PlanItem("Customers", null, ResponseMode.STATIC, 0),
                PlanItem("Billing", null, ResponseMode.DYNAMIC, 3),
            )

        assertEquals(customers.copy(responseMode = ResponseMode.STATIC, delayMillis = 0), applied.definitions.named("Customers"))
        assertEquals(billing.copy(responseMode = ResponseMode.DYNAMIC, delayMillis = 3), applied.definitions.named("Billing"))
        assertEquals(emptyList(), applied.value.skippedItems + applied.value.unresolvedScenarios)
    }

    @Test
    fun ofSeveralItemsForOneServiceTheLastOneCounts() {
        val applied = services.applied(goldItem, PlanItem("customers", "silver", ResponseMode.DYNAMIC, 30), PlanItem("Customers", "none"))

        assertEquals(
            customers.copy(responseMode = ResponseMode.PROXY, defaultScenarioId = silver.id, delayMillis = 0),
            applied.definitions.named("Customers"),
        )
        assertEquals(listOf(UnresolvedScenario("Customers", "none")), applied.value.unresolvedScenarios)
    }

    @Test
    fun ofSeveralServicesWithOneNameTheFirstIsPutIntoTheStateOfThePlan() {
        val twin = service("customers", "clients", scenario("gold"))
        val definitions = Definitions(services = listOf(customers, twin))

        val applied = definitions.applied(goldItem)

        assertEquals(
            gold.id,
            applied.definitions.services
                .first()
                .defaultScenarioId,
        )
        assertEquals(twin, applied.definitions.services.last())
    }

    @Test
    fun ofSeveralScenariosWithOneNameTheFirstBecomesTheDefaultScenario() {
        val first = scenario("Gold")
        val second = scenario("gold")
        val twins = service("Twins", "twins", scenario("silver"), first, second)

        val applied = Definitions(services = listOf(twins)).applied(PlanItem("Twins", "gold", ResponseMode.STATIC))

        assertEquals(first.id, applied.definitions.named("Twins").defaultScenarioId)
    }

    @Test
    fun applyingAPlanChangesNoPlanAndNoSetting() {
        val goldPath = plan(goldItem)
        val settings = Settings(enabledTwistId = TwistId("twist-1"), defaultPlanId = goldPath.id)
        val definitions = services.copy(plans = listOf(goldPath), settings = settings)

        val applied = definitions.changed(PlanChanges.apply(goldPath.id))

        assertEquals(goldPath, applied.value.plan)
        assertEquals(listOf(goldPath), applied.definitions.plans)
        assertEquals(settings, applied.definitions.settings)
    }

    @Test
    fun aPlanWithoutItemsChangesNothing() {
        val applied = services.applied()

        assertEquals(services.services, applied.definitions.services)
        assertEquals(emptyList(), applied.value.skippedItems + applied.value.unresolvedScenarios)
    }

    @Test
    fun aPlanThatDoesNotExistIsNotApplied() {
        assertEquals("No plan has the identifier no-such-plan.", services.notFound(PlanChanges.apply(PlanId("no-such-plan"))))
    }

    @Test
    fun aPlanThatIsSavedAndAppliedLaterPutsTheServicesBackIntoTheirState() {
        val saved = services.changed(PlanChanges.save(listOf(customers.id, orders.id, billing.id), name = "As it was"))
        val changed =
            saved.definitions
                .changed(
                    ServiceChanges.update(
                        customers.id,
                    ) { it.copy(responseMode = ResponseMode.STATIC, defaultScenarioId = gold.id, delayMillis = 9) },
                ).definitions
                .changed(ServiceChanges.update(orders.id) { it.copy(responseMode = ResponseMode.PROXY, defaultScenarioId = failed.id) })
                .definitions

        val applied = changed.changed(PlanChanges.apply(saved.value.id))

        assertEquals(services.services, applied.definitions.services)
    }

    @Test
    fun deletingAPlanThatWasAppliedLeavesEveryServiceWithItsSettings() {
        val goldPath = plan(goldItem)
        val applied = services.copy(plans = listOf(goldPath)).changed(PlanChanges.apply(goldPath.id))

        val deleted = applied.definitions.changed(PlanChanges.delete(goldPath.id))

        assertEquals(applied.definitions.services, deleted.definitions.services)
        assertEquals(emptyList(), deleted.definitions.plans)
    }

    // The default plan after loading

    @Test
    fun theDefaultPlanIsAppliedToDefinitionsThatWereLoaded() {
        val goldPath = plan(goldItem, PlanItem("Shipping", "ok"))
        val stored =
            services.copy(
                plans = listOf(goldPath, plan(PlanItem("Orders", "failed"))),
                settings = Settings(defaultPlanId = goldPath.id),
            )
        val loaded = Json.decodeFromString(Definitions.serializer(), Json.encodeToString(Definitions.serializer(), stored))

        val applied = loaded.changed(PlanChanges.applyDefault())

        assertEquals(
            customers.copy(responseMode = ResponseMode.STATIC, defaultScenarioId = gold.id, delayMillis = 0),
            applied.definitions.named("Customers"),
        )
        assertEquals(orders, applied.definitions.named("Orders"))
        assertEquals(PlanApplyReport(goldPath, listOf(PlanItem("Shipping", "ok")), emptyList()), applied.value)
    }

    @Test
    fun withoutADefaultPlanNothingIsApplied() {
        val withoutDefault = services.copy(plans = listOf(plan(goldItem)))
        val dangling = withoutDefault.copy(settings = Settings(defaultPlanId = PlanId("no-such-plan")))

        val none = withoutDefault.changed(PlanChanges.applyDefault())
        val missing = dangling.changed(PlanChanges.applyDefault())

        assertNull(none.value)
        assertEquals(withoutDefault, none.definitions)
        assertNull(missing.value)
        assertEquals(dangling, missing.definitions)
    }
}
