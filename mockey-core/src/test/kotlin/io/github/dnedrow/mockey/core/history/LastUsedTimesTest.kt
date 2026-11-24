package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.response.ScenarioAnswer
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastUsedTimesTest {
    private val gold = scenarioOf("gold")
    private val silver = scenarioOf("silver")
    private val customers = serviceOf("Customers", "customer/{id}", gold, silver)
    private val orders = serviceOf("Orders", "orders")
    private val goldPath = PlanId("plan-gold")
    private val lastUsed = LastUsedTimes()
    private val later = NOON.plusSeconds(60)

    @Test
    fun aScenarioThatAnswersARequestIsNotedWithItsService() {
        lastUsed.used(served(customers, gold), NOON)

        assertEquals(NOON, lastUsed.ofScenario(gold.id))
        assertEquals(NOON, lastUsed.ofService(customers.id))
        assertNull(lastUsed.ofScenario(silver.id))
        assertNull(lastUsed.ofService(orders.id))
    }

    @Test
    fun theLastUseIsTheOneThatCounts() {
        lastUsed.used(served(customers, gold), NOON)
        lastUsed.used(served(customers, silver), later)

        assertEquals(later, lastUsed.ofService(customers.id))
        assertEquals(NOON to later, lastUsed.ofScenario(gold.id) to lastUsed.ofScenario(silver.id))
    }

    @Test
    fun aRequestThatArrivedEarlierAndIsNotedLaterDoesNotSetTheTimeBack() {
        lastUsed.used(served(customers, gold), later)
        lastUsed.used(served(customers, gold), NOON)
        lastUsed.planApplied(goldPath, later)
        lastUsed.planApplied(goldPath, NOON)

        assertEquals(
            listOf(later, later, later),
            listOf(lastUsed.ofService(customers.id), lastUsed.ofScenario(gold.id), lastUsed.ofPlan(goldPath)),
        )
    }

    @Test
    fun aServiceThatForwardsARequestIsNotedAndNoScenario() {
        lastUsed.used(proxied(orders), NOON)

        assertEquals(mapOf(orders.id to NOON), lastUsed.services)
        assertEquals(emptyMap(), lastUsed.scenarios)
    }

    @Test
    fun aRequestThatNoServiceHandlesNotesNothing() {
        lastUsed.used(Handling.noServiceMatched("No service matches the target 'foo'."), NOON)
        lastUsed.used(proxied(null), NOON)

        assertEquals(emptyMap(), lastUsed.services)
        assertEquals(emptyMap(), lastUsed.scenarios)
    }

    @Test
    fun anErrorScenarioOfAnotherServiceIsNotedWithoutThatService() {
        val universalError = scenarioOf("universal error")
        val holder = serviceOf("Errors", "errors", universalError)

        lastUsed.used(Handling.of(customers, ScenarioAnswer.ErrorScenarioServed(universalError)), NOON)

        assertEquals(mapOf(customers.id to NOON), lastUsed.services)
        assertEquals(mapOf(universalError.id to NOON), lastUsed.scenarios)
        assertNull(lastUsed.ofService(holder.id))
    }

    @Test
    fun aPlanThatIsAppliedIsNoted() {
        lastUsed.planApplied(goldPath, NOON)

        assertEquals(NOON, lastUsed.ofPlan(goldPath))
        assertEquals(mapOf(goldPath to NOON), lastUsed.plans)
        assertNull(lastUsed.ofPlan(PlanId("plan-silver")))
    }

    @Test
    fun clearingTheTimesLeavesNoServiceNoScenarioAndNoPlanWithOne() {
        lastUsed.used(served(customers, gold), NOON)
        lastUsed.planApplied(goldPath, NOON)

        lastUsed.clear()

        assertEquals(emptyMap(), lastUsed.services)
        assertEquals(emptyMap(), lastUsed.scenarios)
        assertEquals(emptyMap(), lastUsed.plans)
        assertEquals(
            listOf(null, null, null),
            listOf(lastUsed.ofService(customers.id), lastUsed.ofScenario(gold.id), lastUsed.ofPlan(goldPath)),
        )
    }

    @Test
    fun whatIsUsedAfterTheTimesWereClearedHasATimeAgain() {
        lastUsed.used(served(customers, gold), NOON)
        lastUsed.clear()

        lastUsed.used(served(customers, silver), later)

        assertEquals(mapOf(customers.id to later), lastUsed.services)
        assertEquals(mapOf(silver.id to later), lastUsed.scenarios)
    }

    @Test
    fun newTimesAreEmptyWhateverOthersHold() {
        lastUsed.used(served(customers, gold), NOON)

        val afterRestart = LastUsedTimes()

        assertEquals(emptyMap(), afterRestart.services)
        assertEquals(emptyMap(), afterRestart.scenarios)
        assertEquals(emptyMap(), afterRestart.plans)
    }

    @Test
    fun theMapsOfTheTimesAreValuesOfTheirOwn() {
        lastUsed.used(served(customers, gold), NOON)

        val services = lastUsed.services
        lastUsed.clear()

        assertEquals(mapOf(customers.id to NOON), services)
    }

    @Test
    fun usesThatAreNotedAtTheSameTimeLeaveTheLatestTime() {
        val start = CountDownLatch(1)
        val scenarios = (1..8).map { ScenarioId("scn-$it") }

        val threads =
            (1..8).map { number ->
                thread {
                    start.await()
                    (1..200L).forEach { second ->
                        lastUsed.serviceUsed(customers.id, NOON.plusSeconds(second * number))
                        lastUsed.scenarioUsed(scenarios[number - 1], NOON.plusSeconds(second))
                    }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(NOON.plusSeconds(1_600), lastUsed.ofService(customers.id))
        assertEquals(scenarios.associateWith { NOON.plusSeconds(200) }, lastUsed.scenarios)
    }
}
