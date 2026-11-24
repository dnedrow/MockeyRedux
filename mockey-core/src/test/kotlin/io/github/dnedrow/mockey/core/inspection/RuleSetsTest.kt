package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.request.MockRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class RuleSetsTest {
    private val goldTier = """{"headers": [{"key": "X-Tier", "value_rule_arg": "gold", "value_rule_type": "string_required"}]}"""
    private val anyUrl = """{"url": [{"value_rule_arg": ".*", "value_rule_type": "regex_required"}]}"""
    private val truncated = """{"parameters": [{"key": "ticker""""

    private fun scenario(
        name: String,
        criterion: MatchCriterion = MatchCriterion.None,
    ) = Scenario(id = ScenarioId.generate(), name = name, matchCriterion = criterion)

    private fun service(
        name: String,
        requestRules: String? = null,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = name, requestRules = requestRules, scenarios = scenarios.toList())

    private fun request(
        target: String,
        vararg headers: Header,
    ): MockRequest {
        val query = if ('?' in target) target.substringAfter('?') else null
        return MockRequest("GET", "http://127.0.0.1:8080/service/${target.substringBefore('?')}", query, headers.toList())
    }

    private fun RuleSetEvaluation.outcome(): Pair<Boolean, Int> = satisfied to satisfiedRuleCount

    @Test
    fun theRuleSetsOfTheDefinitionsAreReadOnce() {
        val byRules = scenario("by rules", MatchCriterion.Rules(anyUrl))
        val customers = service("customers", goldTier, byRules)
        val orders = service("orders", goldTier)
        val ruleSets = RuleSets(Definitions(services = listOf(customers, orders)))

        val ofCustomers = assertIs<RuleSetReading.WellFormed>(ruleSets.requestRules(customers))

        assertEquals(goldTier, ofCustomers.ruleSet.source)
        assertSame(ofCustomers, ruleSets.requestRules(customers))
        assertSame(ofCustomers, ruleSets.requestRules(orders))
        assertSame(ruleSets.criterion(byRules), ruleSets.criterion(byRules))
        assertEquals(anyUrl, assertIs<RuleSetReading.WellFormed>(ruleSets.criterion(byRules)).ruleSet.source)
    }

    @Test
    fun aServiceWithoutRequestRulesAndAScenarioWithoutARuleSetHaveNone() {
        val byText = scenario("by text", MatchCriterion.Text("gold"))
        val plain = scenario("plain")
        val customers = service("customers", null, byText, plain)
        val ruleSets = RuleSets(Definitions(services = listOf(customers)))

        assertNull(ruleSets.requestRules(customers))
        assertNull(ruleSets.criterion(byText))
        assertNull(ruleSets.criterion(plain))
    }

    @Test
    fun requestRulesAreReadWhetherOrNotTheyAreEnabled() {
        val enabled = service("enabled", goldTier).copy(requestRulesEnabled = true)
        val disabled = service("disabled", anyUrl).copy(requestRulesEnabled = false)
        val ruleSets = RuleSets(Definitions(services = listOf(enabled, disabled)))

        assertIs<RuleSetReading.WellFormed>(ruleSets.requestRules(enabled))
        assertIs<RuleSetReading.WellFormed>(ruleSets.requestRules(disabled))
    }

    @Test
    fun aStoredRuleSetThatIsNotWellFormedIsReadAsItsProblems() {
        val byRules = scenario("by rules", MatchCriterion.Rules("not a rule set"))
        val customers = service("customers", truncated, byRules)
        val ruleSets = RuleSets(Definitions(services = listOf(customers)))

        val ofService = assertIs<RuleSetReading.NotWellFormed>(ruleSets.requestRules(customers))
        val ofScenario = assertIs<RuleSetReading.NotWellFormed>(ruleSets.criterion(byRules))

        assertContains(ofService.problems.single(), "not valid JSON")
        assertContains(ofScenario.problems.single(), "not valid JSON")
        assertSame(ofService, ruleSets.requestRules(customers))
    }

    @Test
    fun aRuleSetThatTheDefinitionsDoNotHoldIsReadWhenItIsAskedFor() {
        val ruleSets = RuleSets(Definitions.EMPTY)

        val ofService = assertIs<RuleSetReading.WellFormed>(ruleSets.requestRules(service("elsewhere", goldTier)))
        val ofScenario = ruleSets.criterion(scenario("elsewhere", MatchCriterion.Rules(truncated)))

        assertEquals(goldTier, ofService.ruleSet.source)
        assertIs<RuleSetReading.NotWellFormed>(ofScenario)
    }

    @Test
    fun aValueKeepsTheDefinitionsThatItWasMadeFrom() {
        val definitions = Definitions(services = listOf(service("customers", goldTier)))

        assertSame(definitions, RuleSets(definitions).definitions)
    }

    @Test
    fun aWellFormedRuleSetIsEvaluatedAsItIs() {
        val customers = service("customers", goldTier)
        val reading = RuleSets(Definitions(services = listOf(customers))).requestRules(customers) ?: error("The rules are gone.")

        assertEquals(true to 1, reading.evaluate(request("customers", Header("X-Tier", "Gold"))).outcome())
        assertEquals(false to 0, reading.evaluate(request("customers")).outcome())
    }

    /** An imported scenario can have a rule set that is not valid JSON. It answers no request in dynamic mode. */
    @Test
    fun aStoredRuleSetThatIsNotWellFormedIsNeverSatisfied() {
        val imported = scenario("imported", MatchCriterion.Rules(truncated))
        val ruleSets = RuleSets(Definitions(services = listOf(service("customers", null, imported))))
        val reading = ruleSets.criterion(imported) ?: error("The rule set is gone.")

        val evaluations = listOf(request("customers"), request("customers?ticker=goog", Header("X-Tier", "gold"))).map(reading::evaluate)

        val never = RuleSetEvaluation(satisfied = false, satisfiedRuleCount = 0, failedRules = emptyList())
        assertEquals(listOf(never, never), evaluations)
    }

    /**
     * The rule sets of the parity fixtures that are not well formed, without their descriptions, and the requests
     * of their recordings. The stated new response of each recording is that of the error scenario: the rule set
     * is not satisfied, although a rule or a group of it would be (DEV-45, DEV-54).
     */
    @Test
    fun theRuleSetsOfTheRecordingsThatAreNotWellFormedAreNotSatisfied() {
        val url = { end: String -> """"url": [{"value_rule_arg": "$end$", "value_rule_type": "regex_required"}]""" }
        val keyless = """{"value_rule_arg": "gold", "value_rule_type": "string_required"}"""
        val tier = """{"key": "X-Tier", "value_rule_arg": "gold", "value_rule_type": "string_required"}"""
        val session = """{"key": "session", "value_rule_arg": "abc", "value_rule_type": "string_required"}"""
        val brokenExpression = """{"key": "ticker", "value_rule_arg": "([a-z", "value_rule_type": "regex_required"}"""
        val recordings =
            listOf(
                Triple(
                    "rule-type-unknown",
                    """{"parameters": [{"key": "count", "value_rule_arg": "5", "value_rule_type": "number_required"}]}""",
                    request("types/unknown?count=five"),
                ),
                Triple("rule-type-invalid-regex", """{"parameters": [$brokenExpression]}""", request("types/invalid-regex?ticker=goog")),
                Triple("rule-type-missing-key", """{"headers": [$keyless]}""", request("types/missing-key", Header("X-Tier", "gold"))),
                Triple(
                    "rule-type-missing-argument",
                    """{"parameters": [{"key": "ticker", "value_rule_type": "string_required"}]}""",
                    request("types/missing-argument?ticker=goog"),
                ),
                Triple(
                    "rule-malformed-missing-key",
                    """{"headers": [$keyless], ${url("missing-key")}}""",
                    request("malformed/missing-key"),
                ),
                Triple(
                    "rule-malformed-expression",
                    """{"parameters": [$brokenExpression], ${url("expression")}}""",
                    request("malformed/expression?ticker=goog"),
                ),
                Triple(
                    "rule-malformed-unknown-member",
                    """{"cookies": [$session], ${url("unknown-member")}}""",
                    request("malformed/unknown-member"),
                ),
                Triple(
                    "rule-malformed-group-not-an-array",
                    """{"headers": $tier, ${url("group-not-an-array")}}""",
                    request("malformed/group-not-an-array"),
                ),
                Triple(
                    "rule-malformed-rule-not-an-object",
                    """{"parameters": ["ticker"], ${url("rule-not-an-object")}}""",
                    request("malformed/rule-not-an-object?ticker=goog"),
                ),
                Triple(
                    "rule-malformed-not-json",
                    "{'url': [{'value_rule_arg': 'not-json$', 'value_rule_type': 'regex_required'}]}",
                    request("malformed/not-json"),
                ),
                Triple("response-rules-malformed", """{"parameters": [{"key": "ticker"""", request("rules/malformed?ticker=goog")),
            )

        recordings.forEach { (name, source, request) ->
            val byRules = scenario(name, MatchCriterion.Rules(source))
            val reading = RuleSets(Definitions(services = listOf(service(name, null, byRules)))).criterion(byRules)

            assertIs<RuleSetReading.NotWellFormed>(reading, name)
            assertEquals(false to 0, reading.evaluate(request).outcome(), name)
        }
    }
}
