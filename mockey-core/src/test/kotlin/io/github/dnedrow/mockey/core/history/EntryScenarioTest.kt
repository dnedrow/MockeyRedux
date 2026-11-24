package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.definitions.changed
import io.github.dnedrow.mockey.core.definitions.notFound
import io.github.dnedrow.mockey.core.definitions.rejected
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.response.response
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Saving the response of a history entry as a scenario of its service. */
class EntryScenarioTest {
    private val orders = serviceOf("Orders", "http://api.example.com/orders")
    private val definitions = Definitions(services = listOf(orders))
    private val history = HistoryStore()

    private val json = """{"orders":[1,2]}"""
    private val upstream =
        MockResponse(200, headersOf("content-type" to "application/json", "Content-Length" to "16"), json.toByteArray())

    private fun recorded(
        response: MockResponse,
        store: HistoryStore = history,
    ): HistoryEntry = store.record(exchangeOf(proxied(orders, response), requestFor("http://api.example.com/orders")))

    private fun Definitions.scenarios(): List<Scenario> = services.single { it.id == orders.id }.scenarios

    @Test
    fun theRecordedAnswerOfARealServiceBecomesAScenarioOfTheServiceOfTheEntry() {
        val entry = recorded(upstream)

        val saved = definitions.changed(entry.asScenario { it.copy(name = "recorded order") })

        val scenario = saved.value
        assertEquals("recorded order", scenario.name)
        assertEquals(200, scenario.statusCode)
        assertEquals(headersOf("content-type" to "application/json"), scenario.headers)
        assertEquals(json, scenario.responseBody)
        assertEquals(listOf(scenario), saved.definitions.scenarios())
    }

    @Test
    fun theScenarioGivesTheResponseThatWasRecorded() {
        val latin1 =
            MockResponse(404, headersOf("Content-Type" to "text/plain; charset=ISO-8859-1"), byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))
        val entry = recorded(latin1)

        val scenario = definitions.changed(entry.asScenario()).value

        assertEquals("caf\u00e9", scenario.responseBody)
        assertEquals(404, scenario.response().status)
        assertEquals(latin1.headers, scenario.response().headers)
        assertContentEquals(latin1.body, scenario.response().body)
    }

    @Test
    fun theScenarioTakesEveryHeaderOfTheResponseInItsOrderButTheContentLength() {
        val headers =
            headersOf(
                "set-cookie" to "a=1",
                "content-length" to "2",
                "x-trace" to "7",
                "set-cookie" to "b=2; Path=/",
                "CONTENT-LENGTH" to "2",
            )
        val entry = recorded(MockResponse(201, headers, "ok".toByteArray()))

        val scenario = definitions.changed(entry.asScenario()).value

        assertEquals(headersOf("set-cookie" to "a=1", "x-trace" to "7", "set-cookie" to "b=2; Path=/"), scenario.headers)
        assertEquals(201, scenario.statusCode)
    }

    @Test
    fun theResponseOfAScenarioCanBeSavedAsAScenarioAsWell() {
        val gold = scenarioOf("gold", "gold customer", 202, headersOf("X-Tier" to "gold"), Tags.of("vip"))
        val customers = serviceOf("Customers", "customer/{id}", gold)
        val entry = history.record(exchangeOf(served(customers, gold)))

        val saved = Definitions(services = listOf(customers)).changed(entry.asScenario { it.copy(name = "gold again") })

        assertEquals(gold.copy(id = saved.value.id, name = "gold again", tags = Tags.EMPTY), saved.value)
        assertEquals(
            listOf(gold, saved.value),
            saved.definitions.services
                .single()
                .scenarios,
        )
        assertEquals(
            gold.id,
            saved.definitions.services
                .single()
                .defaultScenarioId,
        )
    }

    @Test
    fun aScenarioThatIsGivenNoNameGetsAGeneratedOneAndTheFirstScenarioIsTheDefaultScenario() {
        val entry = recorded(upstream)

        val saved = definitions.changed(entry.asScenario())

        assertEquals("Scenario for Orders (name auto-generated)", saved.value.name)
        assertEquals(
            saved.value.id,
            saved.definitions.services
                .single()
                .defaultScenarioId,
        )
    }

    @Test
    fun theScenarioCanBeGivenFurtherAttributes() {
        val entry = recorded(upstream)
        var given: Scenario? = null

        val saved =
            definitions.changed(
                entry.asScenario { scenario ->
                    given = scenario
                    scenario.copy(
                        name = "by criterion",
                        tags = Tags.of("recorded"),
                        matchCriterion = MatchCriterion.Text("orders"),
                        delayMillis = 20,
                    )
                },
            )

        assertEquals(json to 200, given?.responseBody to given?.statusCode)
        assertEquals(Tags.of("recorded") to MatchCriterion.Text("orders"), saved.value.tags to saved.value.matchCriterion)
        assertEquals(20, saved.value.delayMillis)
    }

    @Test
    fun anEntryWhoseResponseBodyWasCutIsNotSavedAsAScenario() {
        val limited = HistoryStore(maxBodyBytes = 8)
        val entry = recorded(upstream, limited)

        val errors = definitions.rejected(entry.asScenario { it.copy(name = "recorded order") })

        val cut = "The response body of the history entry 1 was cut when it was recorded"
        val explanation = "$cut, so a scenario of it would not give that response."
        assertEquals(listOf(FieldError("entry", explanation)), errors)
        assertEquals(emptyList(), definitions.scenarios())
    }

    @Test
    fun anEntryWhoseRequestBodyWasCutIsSavedAsAScenario() {
        val limited = HistoryStore(maxBodyBytes = 16)
        val request = requestFor("http://api.example.com/orders", "POST", body = ByteArray(17))
        val entry = limited.record(exchangeOf(proxied(orders, upstream), request))

        val saved = definitions.changed(entry.asScenario())

        assertEquals(true to false, entry.request.body.isCut to entry.response.body.isCut)
        assertEquals(json, saved.value.responseBody)
    }

    @Test
    fun anEntryWithoutAServiceIsNotSavedAsAScenario() {
        val unmatched = history.record(exchangeOf(Handling.noServiceMatched("No service matches the target 'foo'."), requestFor("foo")))
        val forwarded = history.record(exchangeOf(proxied(null, upstream), requestFor("http://api.example.com/orders")))

        val errors = definitions.rejected(unmatched.asScenario()) + definitions.rejected(forwarded.asScenario())

        assertEquals(
            listOf(
                FieldError("entry", "The history entry 1 has no service, so there is no service to give a scenario to."),
                FieldError("entry", "The history entry 2 has no service, so there is no service to give a scenario to."),
            ),
            errors,
        )
    }

    @Test
    fun anEntryWhoseServiceNoLongerExistsIsNotSavedAsAScenario() {
        val entry = recorded(upstream)

        val message = Definitions.EMPTY.notFound(entry.asScenario())

        assertEquals("No service has the identifier ${orders.id.value}.", message)
    }

    @Test
    fun aScenarioWithANameThatIsTooLongIsNotCreated() {
        val entry = recorded(upstream)

        val errors = definitions.rejected(entry.asScenario { it.copy(name = "x".repeat(251)) })

        assertEquals(listOf("name"), errors.map { it.field })
    }

    @Test
    fun aBodyThatIsNoTextIsSavedAsTheTextThatTheEntryShows() {
        val entry =
            recorded(MockResponse(200, headersOf("Content-Type" to "application/octet-stream"), byteArrayOf(0x41, 0xFF.toByte(), 0x42)))

        val scenario = definitions.changed(entry.asScenario()).value

        assertEquals("A\ufffdB", scenario.responseBody)
        assertEquals(entry.response.bodyText, scenario.responseBody)
    }
}
