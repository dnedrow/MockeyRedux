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

/** The cases of the legacy `com.mockey.SearchResultBuilderTest`, with the names of its test methods. */
class LegacySearchResultBuilderTest {
    private fun service(
        name: String,
        tags: String = "",
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = "", tags = Tags.of(tags), scenarios = scenarios.toList())

    private fun scenario(
        name: String,
        responseBody: String = "",
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = responseBody)

    @Test
    fun validateSearchResultByName() {
        val definitions = Definitions(services = listOf(service("Service A", "", scenario("Service Scenario A"))))

        assertEquals(listOf(SearchResultType.SCENARIO), definitions.search("scenario").map { it.type })
        assertEquals(listOf(SearchResultType.SERVICE, SearchResultType.SCENARIO), definitions.search("service").map { it.type })
    }

    @Test
    fun validateSearchResultScenarioContent() {
        val one = Definitions(services = listOf(service("Service A", "", scenario("Scenario A", "lorem ipsum"))))
        val two =
            Definitions(
                services = listOf(service("Service A", "", scenario("Scenario A", "lorem ipsum"), scenario("Scenario B", "lorem ipsula"))),
            )

        assertEquals(emptyList(), one.search("cats"))
        assertEquals(listOf(SearchResultType.SCENARIO), one.search("  lorem   ").map { it.type })
        assertEquals(listOf("Scenario A", "Scenario B"), two.search("  lorem   ").map { it.name })
    }

    @Test
    fun validateSearchResultByServiceTag() {
        val definitions = Definitions(services = listOf(service("Service A", "   tagCC")))

        assertEquals(emptyList(), definitions.search("cats"))
        assertEquals(listOf(SearchResultType.SERVICE), definitions.search("tagC").map { it.type })
    }

    @Test
    fun validateSearchResultByServicePlanTag() {
        val plan = Plan(id = PlanId.generate(), name = "ServicePlan A", tags = Tags.of("service_plan_tag_123"))
        val definitions = Definitions(services = listOf(service("Service A", "   tagCC")), plans = listOf(plan))

        assertEquals(emptyList(), definitions.search("cats"))
        assertEquals(listOf(SearchResultType.PLAN), definitions.search("service_plan_tag_123").map { it.type })
    }
}
