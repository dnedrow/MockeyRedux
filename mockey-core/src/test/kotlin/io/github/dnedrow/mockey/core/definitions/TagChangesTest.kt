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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TagChangesTest {
    private fun scenario(
        name: String,
        tags: String,
    ) = Scenario(id = ScenarioId.generate(), name = name, tags = Tags.of(tags))

    private fun service(
        name: String,
        tags: String,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = name.lowercase(), tags = Tags.of(tags), scenarios = scenarios.toList())

    /** The tag `smoke` is on two services, three scenarios, and one plan. */
    private val definitions =
        Definitions(
            services =
                listOf(
                    service("Customers", "smoke crm", scenario("gold", "smoke"), scenario("silver", "regression")),
                    service("Orders", "Smoke", scenario("ok", "smoke fast"), scenario("timeout", "smoke")),
                    service("Billing", "", scenario("paid", "")),
                ),
            plans =
                listOf(
                    Plan(id = PlanId.generate(), name = "Gold path", tags = Tags.of("smoke demo")),
                    Plan(id = PlanId.generate(), name = "Empty", tags = Tags.of("nightly")),
                ),
        )

    private fun Definitions.allTags(): List<Tags> =
        services.flatMap { service -> listOf(service.tags) + service.scenarios.map { it.tags } } + plans.map { it.tags }

    @Test
    fun everyTagOfAServiceAScenarioOrAPlanIsListed() {
        assertEquals(listOf("crm", "demo", "fast", "nightly", "regression", "smoke"), definitions.tagsInUse().toList())
        assertSame(Tags.EMPTY, Definitions.EMPTY.tagsInUse())
    }

    @Test
    fun aTagIsRemovedFromEveryServiceScenarioAndPlan() {
        assertEquals(6, definitions.allTags().count { "smoke" in it })

        val removed = definitions.changed(TagChanges.remove("smoke")).definitions

        assertTrue(removed.allTags().none { "smoke" in it })
        assertEquals(listOf("crm", "demo", "fast", "nightly", "regression"), removed.tagsInUse().toList())
    }

    @Test
    fun removingATagLeavesEverythingElseAsItWas() {
        val removed = definitions.changed(TagChanges.remove("smoke")).definitions

        assertEquals(definitions.allTags().map { it - Tags.of("smoke") }, removed.allTags())
        assertEquals(
            definitions.services.map {
                it.copy(tags = Tags.EMPTY, scenarios = emptyList())
            },
            removed.services.map { it.copy(tags = Tags.EMPTY, scenarios = emptyList()) },
        )
        assertEquals(definitions.plans.map { it.name }, removed.plans.map { it.name })
    }

    @Test
    fun theTagToRemoveIsNormalizedLikeEveryTag() {
        val removed = definitions.changed(TagChanges.remove("  SMOKE\tfast ")).definitions

        assertEquals(listOf("crm", "demo", "nightly", "regression"), removed.tagsInUse().toList())
    }

    @Test
    fun removingATagThatIsNotInUseChangesNothing() {
        assertEquals(definitions, definitions.changed(TagChanges.remove("unused")).definitions)
        assertEquals(definitions, definitions.changed(TagChanges.remove("  ")).definitions)
    }
}
