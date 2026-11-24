package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StaticModeTest {
    private fun scenario(
        name: String,
        body: String,
        criterion: MatchCriterion = MatchCriterion.None,
        method: String? = null,
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body, matchCriterion = criterion, methodRestriction = method)

    private fun service(
        default: Scenario?,
        vararg scenarios: Scenario,
    ) = Service(
        id = ServiceId.generate(),
        name = "Plans",
        mockUrl = "static/plan",
        responseMode = ResponseMode.STATIC,
        scenarios = scenarios.toList(),
        defaultScenarioId = default?.id,
    )

    @Test
    fun theDefaultScenarioAnswers() {
        val silver = scenario("silver", """{"plan":"silver"}""")
        val gold = scenario("gold", """{"plan":"gold"}""")

        val answer = staticAnswer(service(default = gold, silver, gold))

        assertEquals(ScenarioAnswer.ScenarioServed(gold), answer)
        assertEquals(200, answer.response().status)
        assertContentEquals("""{"plan":"gold"}""".toByteArray(Charsets.US_ASCII), answer.response().body)
    }

    @Test
    fun theResponseIsThatOfTheDefaultScenario() {
        val created =
            scenario(
                "created",
                """{"id":9}""",
            ).copy(statusCode = 201, headers = listOf(Header("Location", "/orders/9"), Header("X-Trace", "a")))

        val response = staticAnswer(service(default = created, scenario("other", "other"), created)).response()

        assertEquals(201, response.status)
        assertEquals(listOf(Header("Location", "/orders/9"), Header("X-Trace", "a")), response.headers)
        assertContentEquals("""{"id":9}""".toByteArray(Charsets.US_ASCII), response.body)
    }

    @Test
    fun matchCriteriaAndMethodRestrictionsHaveNoSay() {
        val restricted = scenario("restricted default", "default despite criteria", MatchCriterion.Text("never-sent"), method = "POST")
        val matching = scenario("matching", "matching scenario is ignored in static mode", MatchCriterion.Text("ticker"), method = "GET")
        val rules =
            scenario("rules", "rules", MatchCriterion.Rules("""{"url": [{"value_rule_arg": ".*", "value_rule_type": "regex_required"}]}"""))

        val answer = staticAnswer(service(default = restricted, restricted, matching, rules))

        assertEquals(ScenarioAnswer.ScenarioServed(restricted), answer)
    }

    @Test
    fun theScenarioCreatedFirstAnswersForADefaultScenarioThatIsMissing() {
        val only = scenario("only", "only scenario")
        val later = scenario("later", "later scenario")

        assertEquals(ScenarioAnswer.ScenarioServed(only), staticAnswer(service(default = null, only, later)))
        assertEquals(ScenarioAnswer.ScenarioServed(only), staticAnswer(service(default = scenario("deleted", ""), only, later)))
    }

    @Test
    fun aServiceWithoutAScenarioHasNothingToAnswerWith() {
        assertFailsWith<IllegalArgumentException> { staticAnswer(service(default = null)) }
    }

    /**
     * The services of the parity fixture `responses-static` and the stated new responses of its recordings. Static
     * mode takes no request, so the two requests for `static/plan` get one answer. `response-static-dangling-default`
     * has a default scenario that does not exist (DEV-33).
     */
    @Test
    fun theServicesOfTheStaticRecordingsAnswerAsTheRecordingsState() {
        val silver = scenario("silver", """{"plan":"silver"}""")
        val gold = scenario("gold", """{"plan":"gold"}""")
        val restricted = scenario("restricted default", "default despite criteria", MatchCriterion.Text("never-sent"), method = "POST")
        val matching = scenario("matching", "matching scenario is ignored in static mode", MatchCriterion.Text("ticker"), method = "GET")
        val only = scenario("only", "only scenario")
        val recordings =
            listOf(
                Triple("response-static-default", service(default = gold, silver, gold), """{"plan":"gold"}"""),
                Triple("response-static-any-request", service(default = gold, silver, gold), """{"plan":"gold"}"""),
                Triple("response-static-ignores-criteria", service(default = restricted, restricted, matching), "default despite criteria"),
                Triple("response-static-dangling-default", service(default = scenario("99", ""), only), "only scenario"),
            )

        recordings.forEach { (name, service, body) ->
            val response = staticAnswer(service).response()

            assertEquals(200, response.status, name)
            assertEquals(emptyList(), response.headers, name)
            assertContentEquals(body.toByteArray(Charsets.US_ASCII), response.body, name)
        }
    }
}
