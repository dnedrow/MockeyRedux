package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private val gold =
        Scenario(
            id = ScenarioId("scn-gold"),
            name = "gold customer",
            responseBody = """{"level":"PlatinumMember"}""",
            tags = Tags.of("vip"),
        )
    private val silver = Scenario(id = ScenarioId("scn-silver"), name = "silver customer", responseBody = """{"level":"basic"}""")
    private val customers =
        Service(
            id = ServiceId("svc-customers"),
            name = "Customers",
            mockUrl = "customer/{id}",
            realUrls = listOf("http://api.example.com/directory/{id}"),
            tags = Tags.of("crm"),
            scenarios = listOf(gold, silver),
        )
    private val orders = Service(id = ServiceId("svc-orders"), name = "Orders", mockUrl = "http://shop.example.com/orders")
    private val plan = Plan(id = PlanId("plan-gold"), name = "Gold path", tags = Tags.of("demo"))
    private val definitions = Definitions(services = listOf(customers, orders), plans = listOf(plan))

    private val customersResult = SearchResult(SearchResultType.SERVICE, "svc-customers", "Customers")
    private val ordersResult = SearchResult(SearchResultType.SERVICE, "svc-orders", "Orders")
    private val goldResult = SearchResult(SearchResultType.SCENARIO, "scn-gold", "gold customer", customers.id, "Customers")
    private val silverResult = SearchResult(SearchResultType.SCENARIO, "scn-silver", "silver customer", customers.id, "Customers")
    private val planResult = SearchResult(SearchResultType.PLAN, "plan-gold", "Gold path")

    @Test
    fun aTermInAResponseBodyFindsTheScenarioWithItsService() {
        assertEquals(listOf(goldResult), definitions.search("platinummember"))
    }

    @Test
    fun aServiceIsFoundByItsNameItsTagsItsRealUrlsAndItsMockUrlPattern() {
        assertEquals(listOf(ordersResult), definitions.search("ORDERS"))
        assertEquals(listOf(customersResult), definitions.search("CRM"))
        assertEquals(listOf(customersResult), definitions.search("api.example.com/Directory"))
        assertEquals(listOf(customersResult), definitions.search("customer/{ID}"))
    }

    @Test
    fun aScenarioIsFoundByItsNameItsTagsAndItsResponseBody() {
        assertEquals(listOf(silverResult), definitions.search("Silver"))
        assertEquals(listOf(goldResult), definitions.search("VIP"))
        assertEquals(listOf(silverResult), definitions.search("\"BASIC\""))
    }

    @Test
    fun aPlanIsFoundByItsNameAndItsTags() {
        assertEquals(listOf(planResult), definitions.search("path"))
        assertEquals(listOf(planResult), definitions.search("Demo"))
    }

    @Test
    fun plansComeFirstAndEachServiceIsFollowedByItsScenarios() {
        assertEquals(listOf(planResult, goldResult), definitions.search("gold"))
        assertEquals(listOf(customersResult, goldResult, silverResult), definitions.search("customer"))
        assertEquals(listOf(customersResult, ordersResult), definitions.search("example.com"))
    }

    @Test
    fun whitespaceAroundTheTermDoesNotCount() {
        assertEquals(listOf(goldResult), definitions.search("  PlatinumMember\n"))
    }

    @Test
    fun aBlankTermFindsNothing() {
        assertEquals(emptyList(), definitions.search(""))
        assertEquals(emptyList(), definitions.search("   "))
    }

    @Test
    fun aTermThatNothingContainsFindsNothing() {
        assertEquals(emptyList(), definitions.search("cats"))
        assertEquals(emptyList(), definitions.search("scn-gold"))
    }
}
