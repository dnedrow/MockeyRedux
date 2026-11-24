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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** How a new name of a service or of a scenario reaches the plan items that refer to it. */
class PlanRenameTest {
    private fun scenario(name: String) = Scenario(id = ScenarioId.generate(), name = name, responseBody = "$name customer")

    private fun service(
        name: String,
        mockUrl: String,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, scenarios = scenarios.toList()).withValidDesignations()

    private val silver = scenario("silver")
    private val gold = scenario("gold")
    private val customers = service("Customers", "customer/{id}", silver, gold)
    private val orders = service("Orders", "orders", scenario("gold"), scenario("failed"))

    private val goldPath =
        Plan(
            PlanId("plan-gold"),
            "Gold path",
            items = listOf(PlanItem("Customers", "gold", ResponseMode.STATIC, 5), PlanItem("Orders", "gold"), PlanItem("Shipping", "gold")),
        )
    private val silverPath = Plan(PlanId("plan-silver"), "Silver path", items = listOf(PlanItem("  CUSTOMERS ", " Silver")))
    private val ordersOnly = Plan(PlanId("plan-orders"), "Orders only", items = listOf(PlanItem("Orders", "failed")))
    private val definitions = Definitions(services = listOf(customers, orders), plans = listOf(goldPath, silverPath, ordersOnly))

    private fun Definitions.items(plan: Plan): List<PlanItem> = plans.single { it.id == plan.id }.items

    // A service is renamed

    @Test
    fun aNewNameOfAServiceReplacesTheOldNameInEveryPlanItemThatRefersToIt() {
        val renamed = definitions.changed(ServiceChanges.update(customers.id) { it.copy(name = "Clients") }).definitions

        val expected = listOf(PlanItem("Clients", "gold", ResponseMode.STATIC, 5), PlanItem("Orders", "gold"), PlanItem("Shipping", "gold"))
        assertEquals(expected, renamed.items(goldPath))
        assertEquals(listOf(PlanItem("Clients", " Silver")), renamed.items(silverPath))
    }

    @Test
    fun aPlanWithoutAnItemForTheServiceStaysAsItIs() {
        val renamed = definitions.changed(ServiceChanges.update(customers.id) { it.copy(name = "Clients") }).definitions

        assertSame(ordersOnly, renamed.plans.last())
        assertEquals(listOf(goldPath.id, silverPath.id, ordersOnly.id), renamed.plans.map { it.id })
    }

    @Test
    fun aRenamedServiceIsStillPutIntoTheStateOfItsPlan() {
        val renamed = definitions.changed(ServiceChanges.update(customers.id) { it.copy(name = " Clients ") }).definitions

        val applied = renamed.changed(PlanChanges.apply(goldPath.id))

        val clients = applied.definitions.services.first()
        assertEquals("Clients" to gold.id, clients.name to clients.defaultScenarioId)
        assertEquals(ResponseMode.STATIC to 5L, clients.responseMode to clients.delayMillis)
        assertEquals(listOf(PlanItem("Shipping", "gold")), applied.value.skippedItems)
    }

    @Test
    fun aNameThatChangesOnlyInItsLetterCaseReachesThePlanItems() {
        val renamed = definitions.changed(ServiceChanges.update(customers.id) { it.copy(name = "CUSTOMERS") }).definitions

        assertEquals("CUSTOMERS", renamed.items(goldPath).first().serviceName)
        assertEquals("CUSTOMERS", renamed.items(silverPath).single().serviceName)
    }

    @Test
    fun anUpdateThatLeavesTheNameLeavesThePlans() {
        val updated =
            definitions
                .changed(
                    ServiceChanges.update(customers.id) { it.copy(name = " Customers ", delayMillis = 70) },
                ).definitions

        assertSame(definitions.plans, updated.plans)
    }

    @Test
    fun aRenameThatIsRejectedLeavesThePlans() {
        val errors = definitions.rejected(ServiceChanges.update(customers.id) { it.copy(name = "  ") })

        assertEquals(listOf("name"), errors.map { it.field })
        assertEquals(listOf(goldPath, silverPath, ordersOnly), definitions.plans)
    }

    @Test
    fun ofSeveralServicesWithOneNameOnlyTheFirstIsFollowedByThePlanItems() {
        val twin = service("customers", "clients", scenario("gold"))
        val withTwin = definitions.copy(services = definitions.services + twin)

        val twinRenamed = withTwin.changed(ServiceChanges.update(twin.id) { it.copy(name = "Clients") }).definitions
        val firstRenamed = withTwin.changed(ServiceChanges.update(customers.id) { it.copy(name = "Clients") }).definitions

        assertSame(withTwin.plans, twinRenamed.plans)
        assertEquals("Clients", firstRenamed.items(goldPath).first().serviceName)
    }

    // A scenario is renamed

    @Test
    fun aNewNameOfAScenarioReplacesTheOldNameInEveryPlanItemThatRefersToIt() {
        val renamed = definitions.changed(ScenarioChanges.update(customers.id, gold.id) { it.copy(name = "premium") }).definitions

        val expected =
            listOf(PlanItem("Customers", "premium", ResponseMode.STATIC, 5), PlanItem("Orders", "gold"), PlanItem("Shipping", "gold"))
        assertEquals(expected, renamed.items(goldPath))
        assertEquals(silverPath.items, renamed.items(silverPath))
        assertSame(ordersOnly, renamed.plans.last())
    }

    @Test
    fun anItemThatNamesTheScenarioInAnotherLetterCaseFollowsIt() {
        val renamed = definitions.changed(ScenarioChanges.update(customers.id, silver.id) { it.copy(name = "bronze") }).definitions

        assertEquals(listOf(PlanItem("  CUSTOMERS ", "bronze")), renamed.items(silverPath))
        assertEquals(goldPath.items, renamed.items(goldPath))
    }

    @Test
    fun aScenarioThatLosesItsNameIsFollowedUnderItsGeneratedName() {
        val renamed = definitions.changed(ScenarioChanges.update(customers.id, gold.id) { it.copy(name = " ") })

        assertEquals("Scenario for Customers (name auto-generated)", renamed.value.name)
        assertEquals(
            "Scenario for Customers (name auto-generated)",
            renamed.definitions
                .items(goldPath)
                .first()
                .scenarioName,
        )
    }

    @Test
    fun anUpdateThatLeavesTheNameOfAScenarioLeavesThePlans() {
        val updated =
            definitions
                .changed(
                    ScenarioChanges.update(customers.id, gold.id) { it.copy(name = "gold ", statusCode = 201) },
                ).definitions

        assertSame(definitions.plans, updated.plans)
    }

    @Test
    fun aRenameOfAScenarioThatIsRejectedLeavesThePlans() {
        val errors = definitions.rejected(ScenarioChanges.update(customers.id, gold.id) { it.copy(name = "x".repeat(251)) })

        assertEquals(listOf("name"), errors.map { it.field })
        assertEquals(listOf(goldPath, silverPath, ordersOnly), definitions.plans)
    }

    @Test
    fun ofSeveralScenariosWithOneNameOnlyTheFirstIsFollowedByThePlanItems() {
        val second = scenario("GOLD")
        val twins = customers.copy(scenarios = customers.scenarios + second)
        val withTwins = definitions.withService(twins)

        val secondRenamed = withTwins.changed(ScenarioChanges.update(customers.id, second.id) { it.copy(name = "premium") }).definitions
        val firstRenamed = withTwins.changed(ScenarioChanges.update(customers.id, gold.id) { it.copy(name = "premium") }).definitions

        assertSame(withTwins.plans, secondRenamed.plans)
        assertEquals("premium", firstRenamed.items(goldPath).first().scenarioName)
    }

    @Test
    fun aRenamedScenarioStillBecomesTheDefaultScenarioOfItsPlan() {
        val renamed = definitions.changed(ScenarioChanges.update(customers.id, gold.id) { it.copy(name = "premium") }).definitions

        val applied = renamed.changed(PlanChanges.apply(goldPath.id))

        assertEquals(
            gold.id,
            applied.definitions.services
                .first()
                .defaultScenarioId,
        )
        assertEquals(emptyList(), applied.value.unresolvedScenarios)
    }

    @Test
    fun deletingAServiceOrAScenarioLeavesThePlanItemsThatNameIt() {
        val withoutScenario = definitions.changed(ScenarioChanges.delete(customers.id, gold.id)).definitions
        val withoutService = definitions.changed(ServiceChanges.delete(customers.id)).definitions

        assertSame(definitions.plans, withoutScenario.plans)
        assertSame(definitions.plans, withoutService.plans)
    }
}
