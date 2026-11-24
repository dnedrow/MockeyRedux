package io.github.dnedrow.mockey.core.inspection

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RuleSetTest {
    private fun wellFormed(source: String): RuleSet =
        when (val reading = readRuleSet(source)) {
            is RuleSetReading.WellFormed -> reading.ruleSet
            is RuleSetReading.NotWellFormed -> fail("The rule set is not well formed: ${reading.problems}")
        }

    private fun problems(source: String): List<String> =
        when (val reading = readRuleSet(source)) {
            is RuleSetReading.WellFormed -> fail("The rule set is well formed: $source")
            is RuleSetReading.NotWellFormed -> reading.problems
        }

    /** The text of a rule set with one rule in [group], which has the [members]. */
    private fun rule(
        group: String,
        members: String,
    ): String = """{"$group": [{$members}]}"""

    /** The members of a rule with the type `string_required`, the [argument], and the [others]. */
    private fun string(
        argument: String = "\"a\"",
        others: String = "",
    ): String = """$others"value_rule_arg": $argument, "value_rule_type": "string_required""""

    @Test
    fun aRuleSetWithAHeadersRuleAndAUrlRuleIsWellFormed() {
        val ruleSet =
            wellFormed(
                """
                {
                    "headers": [{"key": "page_id", "value_rule_arg": "", "value_rule_type": "string_required"}],
                    "url": [{"value_rule_arg": "\\b123\\b", "value_rule_type": "regex_required"}]
                }
                """,
            )

        assertEquals(
            listOf(
                Rule(RuleGroup.HEADERS, "page_id", RuleType.STRING_REQUIRED, ""),
                Rule(RuleGroup.URL, null, RuleType.REGEX_REQUIRED, """\b123\b"""),
            ),
            ruleSet.rules,
        )
    }

    @Test
    fun everyGroupAndEveryMemberOfARuleIsRead() {
        val ruleSet =
            wellFormed(
                """
                {
                    "parameters": [
                        {"key": "ticker", "desc": "Needs g.", "value_rule_arg": "g", "value_rule_type": "string_required"},
                        {"key": "date", "desc": "A date.", "value_rule_arg": "^\\d{2}/\\d{2}/\\d{4}$", "value_rule_type": "regex_optional"}
                    ],
                    "headers": [{"key": "*", "value_rule_arg": "gold", "value_rule_type": "string_required"}],
                    "body": [{"desc": "Needs an amount.", "value_rule_arg": "\"amount\":\\s*\\d+", "value_rule_type": "regex_required"}],
                    "url": [{"value_rule_arg": "checked$", "value_rule_type": "regex_required"}]
                }
                """,
            )

        assertEquals(
            listOf(
                Rule(RuleGroup.PARAMETERS, "ticker", RuleType.STRING_REQUIRED, "g", "Needs g."),
                Rule(RuleGroup.PARAMETERS, "date", RuleType.REGEX_OPTIONAL, """^\d{2}/\d{2}/\d{4}$""", "A date."),
                Rule(RuleGroup.HEADERS, "*", RuleType.STRING_REQUIRED, "gold"),
                Rule(RuleGroup.BODY, null, RuleType.REGEX_REQUIRED, """"amount":\s*\d+""", "Needs an amount."),
                Rule(RuleGroup.URL, null, RuleType.REGEX_REQUIRED, "checked$"),
            ),
            ruleSet.rules,
        )
    }

    @Test
    fun theSourceTextIsKeptExactlyAsItWasWritten() {
        val source = " \n{\"url\" :  [ {\"value_rule_type\":\"REGEX_required\",\t\"value_rule_arg\":\"a\"} ]}\n"

        assertEquals(source, wellFormed(source).source)
    }

    @Test
    fun rulesAreInTheOrderOfTheText() {
        val ruleSet =
            wellFormed(
                """
                {
                    "url": [{"value_rule_arg": "1", "value_rule_type": "string_required"}],
                    "body": [
                        {"value_rule_arg": "2", "value_rule_type": "string_required"},
                        {"value_rule_arg": "3", "value_rule_type": "string_required"}
                    ],
                    "parameters": [{"key": "p", "value_rule_arg": "4", "value_rule_type": "string_required"}]
                }
                """,
            )

        assertEquals(listOf("1", "2", "3", "4"), ruleSet.rules.map { it.argument })
        assertEquals(
            listOf(RuleGroup.URL, RuleGroup.BODY, RuleGroup.BODY, RuleGroup.PARAMETERS),
            ruleSet.rules.map { it.group },
        )
    }

    @Test
    fun aRuleSetWithoutRulesIsWellFormed() {
        assertEquals(emptyList(), wellFormed("{}").rules)
        assertEquals(emptyList(), wellFormed("""{"parameters": []}""").rules)
        assertEquals(emptyList(), wellFormed("""{"parameters": [], "headers": [], "body": [], "url": []}""").rules)
    }

    @Test
    fun aHeaderRuleWithoutAKeyIsNotWellFormedAndTheReasonNamesTheKey() {
        val problem = problems(rule("headers", string())).single()

        assertEquals("Rule 1 of the group 'headers' has no 'key'.", problem)
    }

    @Test
    fun aParameterRuleWithoutAKeyIsNotWellFormed() {
        val source =
            """
            {"parameters": [
                {"key": "ticker", "value_rule_arg": "goog", "value_rule_type": "string_required"},
                {"value_rule_arg": "goog", "value_rule_type": "string_required"}
            ]}
            """

        assertEquals(listOf("Rule 2 of the group 'parameters' has no 'key'."), problems(source))
    }

    @Test
    fun aKeyMeansNothingInARuleOfTheBodyOrTheUrl() {
        val withKey = string(others = """"key": "*", """)
        val withOddKey = string(others = """"key": {"odd": true}, """)

        assertEquals(listOf(Rule(RuleGroup.URL, null, RuleType.STRING_REQUIRED, "a")), wellFormed(rule("url", withKey)).rules)
        assertEquals(listOf(Rule(RuleGroup.BODY, null, RuleType.STRING_REQUIRED, "a")), wellFormed(rule("body", withKey)).rules)
        assertEquals(listOf(Rule(RuleGroup.BODY, null, RuleType.STRING_REQUIRED, "a")), wellFormed(rule("body", withOddKey)).rules)
    }

    @Test
    fun aRuleOfAnUnknownTypeIsNotWellFormed() {
        val members = """"key": "count", "value_rule_arg": "5", "value_rule_type": "number_required""""

        val problem = problems(rule("parameters", members)).single()

        assertContains(problem, "Rule 1 of the group 'parameters'")
        assertContains(problem, "'number_required'")
        assertContains(problem, "'string_required', 'regex_required', and 'regex_optional'")
    }

    @Test
    fun theTypeOfARuleIsReadInAnyLetterCase() {
        val types =
            listOf("STRING_REQUIRED", "Regex_Required", "regex_OPTIONAL").map { type ->
                wellFormed(rule("body", """"value_rule_arg": "a", "value_rule_type": "$type""""))
                    .rules
                    .single()
                    .type
            }

        assertEquals(listOf(RuleType.STRING_REQUIRED, RuleType.REGEX_REQUIRED, RuleType.REGEX_OPTIONAL), types)
    }

    @Test
    fun aTypeIsNotTrimmedAndIsNoOtherKindOfValue() {
        val padded = problems(rule("body", """"value_rule_arg": "a", "value_rule_type": " string_required"""")).single()
        val number = problems(rule("body", """"value_rule_arg": "a", "value_rule_type": 5""")).single()
        val list = problems(rule("body", """"value_rule_arg": "a", "value_rule_type": ["string_required"]""")).single()

        assertContains(padded, "' string_required'")
        assertContains(number, "'5'")
        assertEquals("Rule 1 of the group 'body' has a 'value_rule_type' that is not a text.", list)
    }

    @Test
    fun anExpressionThatDoesNotCompileIsNotWellFormedAndTheReasonNamesIt() {
        val broken = """"key": "ticker", "value_rule_arg": "([a-z", "value_rule_type": "regex_required""""

        val required = problems(rule("parameters", broken)).single()
        val optional = problems(rule("body", """"value_rule_arg": "a{2,1}", "value_rule_type": "regex_optional"""")).single()

        assertContains(required, "Rule 1 of the group 'parameters'")
        assertContains(required, "'([a-z'")
        assertContains(required, "not a regular expression")
        assertContains(optional, "'a{2,1}'")
    }

    @Test
    fun theArgumentOfAStringRuleNeedNotBeAnExpression() {
        val ruleSet = wellFormed(rule("body", string("\"([a-z\"")))

        assertEquals("([a-z", ruleSet.rules.single().argument)
        assertNull(ruleSet.rules.single().expression)
    }

    @Test
    fun theExpressionOfARegexRuleIsCompiledWhenTheRuleSetIsRead() {
        val rules = wellFormed(rule("url", """"value_rule_arg": "\\b123\\b", "value_rule_type": "regex_required"""")).rules

        assertTrue(assertNotNull(rules.single().expression).containsMatchIn("customer/123/orders"))
    }

    @Test
    fun aRuleWithoutAnArgumentOrWithoutATypeIsNotWellFormed() {
        val noArgument = problems(rule("parameters", """"key": "ticker", "value_rule_type": "string_required""""))
        val noType = problems(rule("parameters", """"key": "ticker", "value_rule_arg": "goog""""))
        val nothing = problems(rule("headers", ""))

        assertEquals(listOf("Rule 1 of the group 'parameters' has no 'value_rule_arg'."), noArgument)
        assertEquals(listOf("Rule 1 of the group 'parameters' has no 'value_rule_type'."), noType)
        assertEquals(
            listOf(
                "Rule 1 of the group 'headers' has no 'key'.",
                "Rule 1 of the group 'headers' has no 'value_rule_arg'.",
                "Rule 1 of the group 'headers' has no 'value_rule_type'.",
            ),
            nothing,
        )
    }

    @Test
    fun anArgumentCanBeEmpty() {
        val ruleSet = wellFormed(rule("headers", string("\"\"", others = """"key": "page_id", """)))

        assertEquals("", ruleSet.rules.single().argument)
    }

    @Test
    fun aNumberOrABooleanStandsForTheTextThatItIsWrittenAs() {
        val ruleSet = wellFormed(rule("parameters", string("44", others = """"key": 7, "desc": true, """)))
        val decimal = wellFormed(rule("body", string("1.50")))

        assertEquals(listOf(Rule(RuleGroup.PARAMETERS, "7", RuleType.STRING_REQUIRED, "44", "true")), ruleSet.rules)
        assertEquals("1.50", decimal.rules.single().argument)
    }

    @Test
    fun aMemberThatIsNullCountsAsAbsent() {
        val description = wellFormed(rule("body", string(others = """"desc": null, """)))
        val argument = problems(rule("body", string("null")))
        val key = problems(rule("headers", string(others = """"key": null, """)))

        assertNull(description.rules.single().description)
        assertEquals(listOf("Rule 1 of the group 'body' has no 'value_rule_arg'."), argument)
        assertEquals(listOf("Rule 1 of the group 'headers' has no 'key'."), key)
    }

    @Test
    fun anObjectOrAnArrayIsNoText() {
        val argument = problems(rule("body", string("""{"a": 1}""")))
        val key = problems(rule("headers", string(others = """"key": ["a"], """)))
        val description = problems(rule("body", string(others = """"desc": [], """)))

        assertEquals(listOf("Rule 1 of the group 'body' has a 'value_rule_arg' that is not a text."), argument)
        assertEquals(listOf("Rule 1 of the group 'headers' has a 'key' that is not a text."), key)
        assertEquals(listOf("Rule 1 of the group 'body' has a 'desc' that is not a text."), description)
    }

    @Test
    fun otherMembersOfARuleMeanNothing() {
        val ruleSet = wellFormed(rule("body", string(others = """"comment": {"by": "me"}, """)))

        assertEquals(listOf(Rule(RuleGroup.BODY, null, RuleType.STRING_REQUIRED, "a")), ruleSet.rules)
    }

    @Test
    fun aTextThatIsNotJsonIsNotWellFormed() {
        val texts =
            listOf(
                """{"parameters": [{"key": "ticker"""",
                "{'url': [{'value_rule_arg': 'a', 'value_rule_type': 'string_required'}]}",
                """{"url": [{"value_rule_arg": 'a', "value_rule_type": "string_required"}]}""",
                """{"url": [{"value_rule_arg": "a", "value_rule_type": "string_required"},]}""",
                """{"url": []} and more""",
                """{url: []}""",
                "not a rule set",
                "",
                "   ",
            )

        texts.forEach { text ->
            val problem = problems(text).single()
            assertTrue(problem.startsWith("The rule set is not valid JSON: "), "$text: $problem")
            assertTrue(problem.endsWith("."), "$text: $problem")
        }
    }

    @Test
    fun aRuleSetThatIsNoJsonObjectIsNotWellFormed() {
        listOf("[]", "[{${string()}}]", "\"url\"", "7", "true", "null").forEach { text ->
            assertEquals(listOf("The rule set is not a JSON object."), problems(text), text)
        }
    }

    @Test
    fun aMemberThatIsNoGroupIsNotWellFormed() {
        val unknown = problems("""{"cookies": [], "url": []}""").single()
        val letterCase = problems("""{"Parameters": []}""").single()

        assertContains(unknown, "'cookies'")
        assertContains(unknown, "'parameters', 'headers', 'body', and 'url'")
        assertContains(letterCase, "'Parameters'")
    }

    @Test
    fun aGroupThatIsNoArrayIsNotWellFormed() {
        val objectGroup = problems("""{"headers": {${string(others = """"key": "X-Tier", """)}}}""")
        val textGroup = problems("""{"url": "checked$"}""")
        val nullGroup = problems("""{"body": null}""")

        assertEquals(listOf("The group 'headers' is not an array of rules."), objectGroup)
        assertEquals(listOf("The group 'url' is not an array of rules."), textGroup)
        assertEquals(listOf("The group 'body' is not an array of rules."), nullGroup)
    }

    @Test
    fun anEntryOfAGroupThatIsNoObjectIsNotWellFormed() {
        val source = """{"parameters": [{${string(others = """"key": "a", """)}}, "ticker", null, []]}"""

        assertEquals(
            listOf(
                "Rule 2 of the group 'parameters' is not a JSON object.",
                "Rule 3 of the group 'parameters' is not a JSON object.",
                "Rule 4 of the group 'parameters' is not a JSON object.",
            ),
            problems(source),
        )
    }

    @Test
    fun everyFaultIsReported() {
        val source =
            """
            {
                "cookies": [],
                "headers": [{"value_rule_arg": "gold", "value_rule_type": "string_required"}],
                "parameters": [
                    {"key": "ticker", "value_rule_arg": "goog", "value_rule_type": "string_required"},
                    {"key": "count", "value_rule_arg": "5", "value_rule_type": "number_required"},
                    {"key": "id", "value_rule_arg": "([a-z", "value_rule_type": "regex_required"}
                ],
                "url": {}
            }
            """

        val problems = problems(source)

        assertEquals(5, problems.size)
        assertContains(problems[0], "'cookies'")
        assertEquals("Rule 1 of the group 'headers' has no 'key'.", problems[1])
        assertContains(problems[2], "Rule 2 of the group 'parameters' has the 'value_rule_type' 'number_required'.")
        assertContains(problems[3], "Rule 3 of the group 'parameters' has the 'value_rule_arg' '([a-z'")
        assertEquals("The group 'url' is not an array of rules.", problems[4])
    }

    @Test
    fun theProblemsOfARuleSetAreOneText() {
        val source = """{"cookies": [], "url": {}}"""

        assertNull(ruleSetProblems("""{"url": []}"""))
        assertEquals(problems(source).joinToString(" "), ruleSetProblems(source))
    }

    @Test
    fun aRuleHasAKeyExactlyWhenItsGroupIsKeyed() {
        assertFailsWith<IllegalArgumentException> { Rule(RuleGroup.HEADERS, null, RuleType.STRING_REQUIRED, "a") }
        assertFailsWith<IllegalArgumentException> { Rule(RuleGroup.URL, "key", RuleType.STRING_REQUIRED, "a") }
    }

    @Test
    fun aRuleSetThatIsNotWellFormedHasAtLeastOneProblem() {
        assertFailsWith<IllegalArgumentException> { RuleSetReading.NotWellFormed(emptyList()) }
    }
}
