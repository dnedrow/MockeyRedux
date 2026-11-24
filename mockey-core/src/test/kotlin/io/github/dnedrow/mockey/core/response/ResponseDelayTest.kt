package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.request.MockRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tests run in virtual time: a wait of five seconds takes no time, and the clock of the test tells how long it was. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResponseDelayTest {
    private fun scenario(
        name: String,
        delayMillis: Long = 0,
        criterion: String? = null,
        status: Int = 200,
    ) = Scenario(
        id = ScenarioId.generate(),
        name = name,
        responseBody = name,
        statusCode = status,
        matchCriterion = criterion?.let(MatchCriterion::Text) ?: MatchCriterion.None,
        delayMillis = delayMillis,
    )

    private fun service(
        mode: ResponseMode,
        delayMillis: Long,
        error: Scenario?,
        vararg scenarios: Scenario,
    ) = Service(
        id = ServiceId.generate(),
        name = "Orders",
        mockUrl = "orders",
        responseMode = mode,
        delayMillis = delayMillis,
        scenarios = scenarios.toList(),
        defaultScenarioId = scenarios.firstOrNull()?.id,
        errorScenarioId = error?.id,
    )

    private fun static(
        delayMillis: Long,
        scenario: Scenario,
    ) = service(ResponseMode.STATIC, delayMillis, error = null, scenario)

    private fun dynamic(
        delayMillis: Long,
        error: Scenario?,
        vararg scenarios: Scenario,
    ) = service(ResponseMode.DYNAMIC, delayMillis, error, *scenarios)

    /**
     * Handles a request as the request pipeline does: the service answers with a scenario, and the response is
     * held back for its delay.
     */
    private suspend fun handle(
        service: Service,
        query: String? = null,
    ): MockResponse {
        val definitions = Definitions(services = listOf(service))
        val request = MockRequest("GET", "http://mockey.test:8080/service/orders", query)
        val answer =
            when (service.responseMode) {
                ResponseMode.STATIC -> staticAnswer(service)
                else -> definitions.dynamicAnswer(service, emptyMap(), request, RuleSets(definitions))
            }
        awaitResponseDelay(service, answer.scenario)
        return answer.response()
    }

    @Test
    fun theDelayOfTheScenarioTakesThePlaceOfAShorterDelayOfTheService() =
        runTest {
            val response = handle(static(delayMillis = 100, scenario("slow scenario", delayMillis = 500)))

            assertEquals(500, currentTime)
            assertEquals("slow scenario", response.bodyText)
        }

    @Test
    fun theDelayOfTheScenarioTakesThePlaceOfALongerDelayOfTheService() =
        runTest {
            handle(static(delayMillis = 1_500, scenario("quick scenario", delayMillis = 200)))

            assertEquals(200, currentTime)
        }

    @Test
    fun theDelayOfTheServiceAppliesWhenTheScenarioHasNone() =
        runTest {
            val response = handle(static(delayMillis = 100, scenario("no scenario delay")))

            assertEquals(100, currentTime)
            assertEquals("no scenario delay", response.bodyText)
        }

    @Test
    fun aResponseWithoutADelayIsNotHeldBack() =
        runTest {
            handle(static(delayMillis = 0, scenario("no delay")))

            assertEquals(0, currentTime)
        }

    @Test
    fun theScenarioThatMatchesInDynamicModeWaitsForItsDelay() =
        runTest {
            val slow = scenario("slow", delayMillis = 400, criterion = "slow")
            val service = dynamic(delayMillis = 0, error = null, scenario("quick", criterion = "quick"), slow)

            handle(service, "q=slow")
            val afterSlow = currentTime
            handle(service, "q=quick")

            assertEquals(400, afterSlow)
            assertEquals(400, currentTime)
        }

    @Test
    fun anErrorScenarioWaitsForItsDelayAsAnyScenarioDoes() =
        runTest {
            val error = scenario("delayed error", delayMillis = 1_500, status = 500)
            val service = dynamic(delayMillis = 100, error, scenario("known", criterion = "known"), error)

            val response = handle(service, "q=other")

            assertEquals(1_500, currentTime)
            assertEquals(500, response.status)
        }

    @Test
    fun anErrorScenarioWithoutADelayWaitsForTheDelayOfTheService() =
        runTest {
            val error = scenario("service error", status = 500)

            handle(dynamic(delayMillis = 300, error, scenario("known", criterion = "known"), error), "q=other")

            assertEquals(300, currentTime)
        }

    @Test
    fun aResponseThatNoScenarioGivesWaitsForTheDelayOfTheService() =
        runTest {
            val service = dynamic(delayMillis = 300, error = null, scenario("known", delayMillis = 2_000, criterion = "known"))

            val response = handle(service, "q=other")

            assertEquals(300, currentTime)
            assertEquals(404, response.status)
            assertEquals(300, responseDelayMillis(service, scenario = null))
        }

    @Test
    fun aDelayThatIsNotGreaterThanZeroIsNone() {
        val service = static(delayMillis = -5, scenario("negative", delayMillis = -200))
        val scenario = service.scenarios.single()

        assertEquals(0, responseDelayMillis(service, scenario))
        assertEquals(0, responseDelayMillis(service, scenario = null))
        assertEquals(250, responseDelayMillis(service.copy(delayMillis = 250), scenario))
        assertEquals(0, responseDelayMillis(service.copy(delayMillis = 0), scenario.copy(delayMillis = 0)))
    }

    @Test
    fun aDelayedRequestDoesNotHoldUpAnother() =
        runTest {
            val slow = static(delayMillis = 0, scenario("slow", delayMillis = 5_000))
            val quick = static(delayMillis = 0, scenario("quick"))
            val answered = mutableListOf<Pair<String, Long>>()

            launch { answered += handle(slow).bodyText to currentTime }
            launch { answered += handle(quick).bodyText to currentTime }
            runCurrent()

            assertEquals(listOf("quick" to 0L), answered)

            advanceTimeBy(4_999)
            runCurrent()

            assertEquals(listOf("quick" to 0L), answered)

            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf("quick" to 0L, "slow" to 5_000L), answered)
        }

    @Test
    fun aRequestThatArrivesWhileAnotherWaitsIsAnsweredFirst() =
        runTest {
            val slow = static(delayMillis = 5_000, scenario("slow"))
            val quick = static(delayMillis = 100, scenario("quick"))
            val answered = mutableListOf<Pair<String, Long>>()

            launch { answered += handle(slow).bodyText to currentTime }
            advanceTimeBy(3_000)
            launch { answered += handle(quick).bodyText to currentTime }
            advanceTimeBy(3_000)

            assertEquals(listOf("quick" to 3_100L, "slow" to 5_000L), answered)
        }

    @Test
    fun requestsWaitAtTheSameTimeAndNotOneAfterTheOther() =
        runTest {
            val service = static(delayMillis = 1_000, scenario("delayed"))
            var answered = 0

            repeat(1_000) { launch { handle(service).also { answered++ } } }
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1_000, answered)
            assertEquals(1_000, currentTime)
        }

    /**
     * The services of the parity fixtures `responses-delays` and `responses-builtin-delay`, and the requests of
     * their recordings. A recording states the least time that its response takes, and some state a time that it
     * stays under. The delay of an error scenario applies, which `response-delay-error-scenario` states as at
     * least 1,500 ms (DEV-37), and the built-in response waits for the delay of the service.
     */
    @Test
    fun theResponsesOfTheDelayRecordingsAreHeldBackAsTheRecordingsState() =
        runTest {
            val slow = scenario("slow", delayMillis = 400, criterion = "slow")
            val delayedError = scenario("delayed error", delayMillis = 1_500, status = 500)
            val known = scenario("known", criterion = "known")
            val serviceError = scenario("service error", status = 500)
            val dynamicService = dynamic(delayMillis = 0, delayedError, slow, delayedError)
            val recordings =
                listOf(
                    Recording("response-delay-service", static(300, scenario("no scenario delay")), null, 300..Long.MAX_VALUE),
                    Recording(
                        "response-delay-scenario-overrides",
                        static(100, scenario("longer scenario delay", 500)),
                        null,
                        500..Long.MAX_VALUE,
                    ),
                    Recording("response-delay-scenario-shorter", static(1_500, scenario("shorter scenario delay", 200)), null, 200L..999L),
                    Recording("response-delay-dynamic-match", dynamicService, "q=slow", 400..Long.MAX_VALUE),
                    Recording("response-delay-error-scenario", dynamicService, "q=other", 1_500..Long.MAX_VALUE),
                    Recording(
                        "response-delay-fallback-service",
                        dynamic(300, serviceError, known, serviceError),
                        "q=other",
                        300..Long.MAX_VALUE,
                    ),
                    Recording("response-delay-builtin", dynamic(300, error = null, known), "q=other", 300..Long.MAX_VALUE),
                )

            recordings.forEach { recording ->
                val start = currentTime

                handle(recording.service, recording.query)

                assertTrue(currentTime - start in recording.time, "${recording.name}: ${currentTime - start} ms")
            }
        }

    private class Recording(
        val name: String,
        val service: Service,
        val query: String?,
        val time: LongRange,
    )
}
