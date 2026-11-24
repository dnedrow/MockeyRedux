package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignationsTest {
    private val silver = Scenario(id = ScenarioId.generate(), name = "silver")
    private val gold = Scenario(id = ScenarioId.generate(), name = "gold")
    private val service = Service(id = ServiceId.generate(), name = "Plans", mockUrl = "plan", scenarios = listOf(silver, gold))

    @Test
    fun theDefaultScenarioIsTheDesignatedOne() {
        assertEquals(gold, service.copy(defaultScenarioId = gold.id).defaultScenario)
        assertEquals(silver, service.copy(defaultScenarioId = silver.id).defaultScenario)
    }

    @Test
    fun theScenarioCreatedFirstStandsInForADesignationThatIsMissing() {
        assertEquals(silver, service.copy(defaultScenarioId = null).defaultScenario)
        assertEquals(silver, service.copy(defaultScenarioId = ScenarioId.generate()).defaultScenario)
    }

    @Test
    fun aServiceWithoutScenariosHasNoDefaultScenario() {
        assertNull(service.copy(scenarios = emptyList(), defaultScenarioId = gold.id).defaultScenario)
    }
}
