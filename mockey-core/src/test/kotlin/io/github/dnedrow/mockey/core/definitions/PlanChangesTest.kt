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
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PlanChangesTest {
    private fun scenario(name: String) = Scenario(id = ScenarioId.generate(), name = name, responseBody = "$name customer")

    private fun service(
        name: String,
        mockUrl: String,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, scenarios = scenarios.toList()).withValidDesignations()

    private val silver = scenario("silver")
    private val gold = scenario("gold")
    private val customers =
        service("Customers", "customer/{id}", silver, gold).copy(
            responseMode = ResponseMode.DYNAMIC,
            defaultScenarioId = gold.id,
            delayMillis = 250,
        )
    private val orders = service("Orders", "orders", scenario("ok")).copy(responseMode = ResponseMode.STATIC)
    private val billing = service("Billing", "http://api.example.com/billing")
    private val services = Definitions(services = listOf(customers, orders, billing))

    private val goldItem = PlanItem("Customers", "gold", ResponseMode.DYNAMIC, 250)
    private val ordersItem = PlanItem("Orders", "ok", ResponseMode.STATIC, 0)
    private val billingItem = PlanItem("Billing", null, ResponseMode.PROXY, 0)

    private val goldPath = Plan(PlanId("plan-gold"), "Gold path", "Everything gold", Tags.of("demo"), listOf(goldItem))
    private val silverPath = Plan(PlanId("plan-silver"), "Silver path", items = listOf(PlanItem("Customers", "silver")))
    private val withPlans = services.copy(plans = listOf(goldPath, silverPath))
    private val unknownPlan = PlanId("no-such-plan")

    // Saving a plan from the current settings

    @Test
    fun aSavedPlanHoldsTheSettingsThatTheServiceHasNow() {
        val saved = services.changed(PlanChanges.save(listOf(customers.id), name = "Gold path"))

        assertEquals("Gold path", saved.value.name)
        assertEquals(listOf(goldItem), saved.value.items)
        assertEquals(listOf(saved.value), saved.definitions.plans)
    }

    @Test
    fun aPlanForTwoServicesHasAnItemForEachOfThem() {
        val saved = services.changed(PlanChanges.save(listOf(orders.id, customers.id), name = "Evening"))

        assertEquals(listOf(ordersItem, goldItem), saved.value.items)
    }

    @Test
    fun aSavedPlanNamesTheDefaultScenarioAlsoWhereItIsNotDesignated() {
        val undesignated = customers.copy(defaultScenarioId = null)
        val definitions = Definitions(services = listOf(undesignated, billing))

        val saved = definitions.changed(PlanChanges.save(listOf(undesignated.id, billing.id)))

        assertEquals(listOf(goldItem.copy(scenarioName = "silver"), billingItem), saved.value.items)
    }

    @Test
    fun aSavedPlanHoldsTheModeThatIsConfiguredAlsoForAServiceWithoutScenarios() {
        val static = billing.copy(responseMode = ResponseMode.STATIC, delayMillis = 40)

        val saved = Definitions(services = listOf(static)).changed(PlanChanges.save(listOf(static.id)))

        assertEquals(ResponseMode.PROXY, static.effectiveResponseMode)
        assertEquals(listOf(PlanItem("Billing", null, ResponseMode.STATIC, 40)), saved.value.items)
    }

    @Test
    fun aPlanThatIsSavedWithoutANameGetsAGeneratedOne() {
        val unnamed = services.changed(PlanChanges.save(listOf(customers.id)))
        val blank = services.changed(PlanChanges.save(listOf(customers.id), name = "  \t"))

        assertEquals("Plan (auto-generated-name)", unnamed.value.name)
        assertEquals("Plan (auto-generated-name)", blank.value.name)
    }

    @Test
    fun aSaveWithoutANameAlwaysCreatesAPlan() {
        val first = services.changed(PlanChanges.save(listOf(customers.id)))

        val second = first.definitions.changed(PlanChanges.save(listOf(orders.id)))

        assertEquals(listOf(first.value, second.value), second.definitions.plans)
        assertNotEquals(first.value.id, second.value.id)
    }

    @Test
    fun aNewPlanHasAnIdentifierOfItsOwnAndIsNotTheDefaultPlan() {
        val saved = withPlans.changed(PlanChanges.save(listOf(customers.id), name = "Evening"))

        assertEquals(listOf(goldPath, silverPath, saved.value), saved.definitions.plans)
        assertNotEquals(goldPath.id, saved.value.id)
        assertNull(saved.definitions.settings.defaultPlanId)
    }

    @Test
    fun aServiceThatIsNamedTwiceGetsOneItem() {
        val saved = services.changed(PlanChanges.save(listOf(customers.id, orders.id, customers.id)))

        assertEquals(listOf(goldItem, ordersItem), saved.value.items)
    }

    @Test
    fun aPlanCanBeSavedForNoService() {
        val saved = services.changed(PlanChanges.save(emptyList(), name = "Nothing"))

        assertEquals(Plan(saved.value.id, "Nothing"), saved.value)
    }

    @Test
    fun aPlanIsNotSavedForAServiceThatDoesNotExist() {
        val unknown = ServiceId("no-such-service")

        val message = withPlans.notFound(PlanChanges.save(listOf(customers.id, unknown), name = "Gold path"))

        assertEquals("No service has the identifier no-such-service.", message)
    }

    @Test
    fun savingAPlanChangesNoService() {
        val saved = services.changed(PlanChanges.save(listOf(customers.id, orders.id, billing.id), name = "Everything"))

        assertEquals(services.services, saved.definitions.services)
        assertEquals(services.settings, saved.definitions.settings)
    }

    // Updating an existing plan

    @Test
    fun savingUnderAnExistingNameReplacesTheItemsOfThatPlan() {
        val saved = withPlans.changed(PlanChanges.save(listOf(customers.id, orders.id, billing.id), name = "gold path"))

        assertEquals(goldPath.id, saved.value.id)
        assertEquals(listOf(goldItem, ordersItem, billingItem), saved.value.items)
        assertEquals(listOf(saved.value, silverPath), saved.definitions.plans)
    }

    @Test
    fun aPlanThatIsSavedUnderItsNameTakesTheNameAsItIsWrittenAndKeepsItsOtherAttributes() {
        val saved = withPlans.changed(PlanChanges.save(listOf(orders.id), name = "  GOLD path "))

        assertEquals(goldPath.copy(name = "GOLD path", items = listOf(ordersItem)), saved.value)
    }

    @Test
    fun aSaveByIdentifierUpdatesThatPlanWhateverItsName() {
        val renamed = withPlans.changed(PlanChanges.save(listOf(orders.id), name = "Silver path", id = goldPath.id))
        val unnamed = withPlans.changed(PlanChanges.save(listOf(orders.id), id = goldPath.id))

        assertEquals(goldPath.copy(name = "Silver path", items = listOf(ordersItem)), renamed.value)
        assertEquals(listOf(renamed.value, silverPath), renamed.definitions.plans)
        assertEquals(goldPath.copy(items = listOf(ordersItem)), unnamed.value)
    }

    @Test
    fun aSaveForAnIdentifierThatNoPlanHasCreatesNoPlan() {
        val message = withPlans.notFound(PlanChanges.save(listOf(customers.id), name = "Gold path", id = unknownPlan))

        assertEquals("No plan has the identifier no-such-plan.", message)
    }

    @Test
    fun ofSeveralPlansWithOneNameTheFirstIsSaved() {
        val twin = goldPath.copy(id = PlanId("plan-twin"), name = "GOLD PATH")
        val definitions = services.copy(plans = listOf(silverPath, goldPath, twin))

        val saved = definitions.changed(PlanChanges.save(listOf(orders.id), name = "Gold Path"))

        assertEquals(goldPath.id, saved.value.id)
        assertEquals(listOf(silverPath, saved.value, twin), saved.definitions.plans)
    }

    @Test
    fun aSaveCanSetFurtherAttributesOfThePlan() {
        val created =
            services.changed(
                PlanChanges.save(
                    listOf(customers.id),
                    name = "Evening",
                ) { it.copy(description = "After hours", tags = Tags.of("Night shift")) },
            )
        var given: Plan? = null
        val updated =
            withPlans.changed(
                PlanChanges.save(listOf(orders.id), name = "gold path") { plan ->
                    given = plan
                    plan.copy(tags = plan.tags + Tags.of("night"))
                },
            )

        assertEquals(Plan(created.value.id, "Evening", "After hours", Tags.of("night", "shift"), listOf(goldItem)), created.value)
        assertEquals(goldPath.copy(name = "gold path", items = listOf(ordersItem)), given)
        assertEquals(Tags.of("demo", "night"), updated.value.tags)
    }

    // Creating and updating a plan with its items

    @Test
    fun aPlanIsCreatedWithItsAttributesAndItsItems() {
        val items = listOf(PlanItem("Customers", "gold", ResponseMode.STATIC, 0), PlanItem("No such service", "none"))

        val created = withPlans.changed(PlanChanges.create { it.copy(name = "By hand", tags = Tags.of("demo"), items = items) })

        assertEquals(Plan(created.value.id, "By hand", null, Tags.of("demo"), items), created.value)
        assertEquals(listOf(goldPath, silverPath, created.value), created.definitions.plans)
    }

    @Test
    fun aPlanIsStoredInItsNormalForm() {
        val items =
            listOf(
                PlanItem("  Customers ", " gold\t"),
                PlanItem("Orders", "  "),
                PlanItem("  ", "ok"),
                PlanItem("", null),
            )

        val created = services.changed(PlanChanges.create { it.copy(name = "  By hand \n", description = "  ", items = items) })
        val unnamed = services.changed(PlanChanges.create { it })
        val renamed = withPlans.changed(PlanChanges.update(goldPath.id) { it.copy(name = " ") })

        assertEquals(
            Plan(created.value.id, "By hand", null, Tags.EMPTY, listOf(PlanItem("Customers", "gold"), PlanItem("Orders"))),
            created.value,
        )
        assertEquals("Plan (auto-generated-name)", unnamed.value.name)
        assertEquals("Plan (auto-generated-name)", renamed.value.name)
    }

    @Test
    fun aPlanIsUpdatedWithoutChangingTheOtherPlans() {
        val updated = withPlans.changed(PlanChanges.update(silverPath.id) { it.copy(name = "Bronze path", description = "Third place") })

        assertEquals(silverPath.copy(name = "Bronze path", description = "Third place"), updated.value)
        assertEquals(listOf(goldPath, updated.value), updated.definitions.plans)
        assertEquals(withPlans.services, updated.definitions.services)
    }

    @Test
    fun aPlanThatDoesNotExistIsNotUpdated() {
        assertEquals(
            "No plan has the identifier no-such-plan.",
            withPlans.notFound(PlanChanges.update(unknownPlan) { it.copy(name = "x") }),
        )
    }

    @Test
    fun theIdentifierOfAPlanCannotBeChanged() {
        val other = PlanId("another-plan")

        assertFailsWith<IllegalArgumentException> { withPlans.changed(PlanChanges.update(goldPath.id) { it.copy(id = other) }) }
        assertFailsWith<IllegalArgumentException> { withPlans.changed(PlanChanges.create { it.copy(id = other) }) }
        assertFailsWith<IllegalArgumentException> { withPlans.changed(PlanChanges.save(emptyList()) { it.copy(id = other) }) }
    }

    // Deleting a plan

    @Test
    fun deletingAPlanRemovesItAndChangesNoService() {
        val deleted = withPlans.changed(PlanChanges.delete(goldPath.id))

        assertEquals(goldPath, deleted.value)
        assertEquals(listOf(silverPath), deleted.definitions.plans)
        assertEquals(withPlans.services, deleted.definitions.services)
    }

    @Test
    fun aPlanThatDoesNotExistIsNotDeleted() {
        assertEquals("No plan has the identifier no-such-plan.", withPlans.notFound(PlanChanges.delete(unknownPlan)))
    }

    // The default plan

    @Test
    fun aPlanIsDesignatedAsTheDefaultPlan() {
        val designated = withPlans.changed(PlanChanges.designateDefault(goldPath.id))

        assertEquals(goldPath, designated.value)
        assertEquals(goldPath.id, designated.definitions.settings.defaultPlanId)
        assertEquals(goldPath, designated.definitions.defaultPlan)
        assertEquals(withPlans.plans, designated.definitions.plans)
    }

    @Test
    fun atMostOnePlanIsTheDefaultPlan() {
        val first = withPlans.changed(PlanChanges.designateDefault(goldPath.id))

        val second = first.definitions.changed(PlanChanges.designateDefault(silverPath.id))

        assertEquals(silverPath.id, second.definitions.settings.defaultPlanId)
    }

    @Test
    fun theDesignationOfTheDefaultPlanCanBeRemoved() {
        val others = Settings(enabledTwistId = TwistId("twist-1"), defaultPlanId = goldPath.id)
        val designated = withPlans.copy(settings = others)

        val cleared = designated.changed(PlanChanges.designateDefault(null))
        val clearedAgain = cleared.definitions.changed(PlanChanges.designateDefault(null))

        assertNull(cleared.value)
        assertEquals(others.copy(defaultPlanId = null), cleared.definitions.settings)
        assertNull(cleared.definitions.defaultPlan)
        assertEquals(cleared.definitions, clearedAgain.definitions)
    }

    @Test
    fun aPlanThatDoesNotExistIsNotDesignated() {
        val designated = withPlans.copy(settings = Settings(defaultPlanId = goldPath.id))

        val message = designated.notFound(PlanChanges.designateDefault(unknownPlan))

        assertEquals("No plan has the identifier no-such-plan.", message)
    }

    @Test
    fun deletingTheDefaultPlanRemovesTheDesignation() {
        val designated = withPlans.copy(settings = Settings(enabledTwistId = TwistId("twist-1"), defaultPlanId = goldPath.id))

        val deleted = designated.changed(PlanChanges.delete(goldPath.id))

        assertEquals(Settings(enabledTwistId = TwistId("twist-1")), deleted.definitions.settings)
    }

    @Test
    fun deletingAnotherPlanKeepsTheDesignation() {
        val designated = withPlans.copy(settings = Settings(defaultPlanId = goldPath.id))

        val deleted = designated.changed(PlanChanges.delete(silverPath.id))

        assertEquals(goldPath.id, deleted.definitions.settings.defaultPlanId)
    }

    @Test
    fun aDesignationOfAPlanThatDoesNotExistIsNoDefaultPlan() {
        val dangling = withPlans.copy(settings = Settings(defaultPlanId = unknownPlan))

        assertNull(dangling.defaultPlan)
    }

    // Finding a plan by its name

    @Test
    fun aPlanIsFoundByItsNameWithoutRegardToLetterCaseAndSurroundingWhitespace() {
        assertEquals(goldPath, withPlans.planNamed("gold PATH"))
        assertEquals(silverPath, withPlans.planNamed("  Silver path\n"))
        assertNull(withPlans.planNamed("Gold"))
        assertNull(withPlans.planNamed(""))
    }
}
