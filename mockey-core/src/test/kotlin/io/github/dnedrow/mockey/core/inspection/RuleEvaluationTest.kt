package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class RuleEvaluationTest {
    private fun rule(
        type: String,
        argument: String,
        key: String?,
    ): JsonObject =
        buildJsonObject {
            key?.let { put("key", it) }
            put("value_rule_arg", argument)
            put("value_rule_type", type)
        }

    private fun string(
        argument: String,
        key: String? = null,
    ) = rule("string_required", argument, key)

    private fun required(
        expression: String,
        key: String? = null,
    ) = rule("regex_required", expression, key)

    private fun optional(
        expression: String,
        key: String? = null,
    ) = rule("regex_optional", expression, key)

    private fun parameters(vararg rules: JsonObject) = "parameters" to rules.toList()

    private fun headers(vararg rules: JsonObject) = "headers" to rules.toList()

    private fun body(vararg rules: JsonObject) = "body" to rules.toList()

    private fun url(vararg rules: JsonObject) = "url" to rules.toList()

    private fun read(source: String): RuleSet =
        when (val reading = readRuleSet(source)) {
            is RuleSetReading.WellFormed -> reading.ruleSet
            is RuleSetReading.NotWellFormed -> fail("The rule set is not well formed: ${reading.problems}")
        }

    private fun ruleSet(vararg groups: Pair<String, List<JsonObject>>): RuleSet =
        read(buildJsonObject { groups.forEach { (name, rules) -> put(name, JsonArray(rules)) } }.toString())

    private fun request(
        query: String? = null,
        headers: List<Header> = emptyList(),
        body: String? = null,
        url: String = "http://mockey.test:8080/service/customer/123",
    ) = MockRequest(if (body == null) "GET" else "POST", url, query, headers, body?.toByteArray() ?: ByteArray(0))

    private fun form(fields: String) = request(headers = listOf(Header("Content-Type", "application/x-www-form-urlencoded")), body = fields)

    private fun RuleSet.isSatisfiedBy(request: MockRequest): Boolean = evaluate(request).satisfied

    @Test
    fun aUrlRuleSeesTheUrlOfTheMockEndpoint() {
        val rules = ruleSet(url(required("""\b123\b""")))

        assertTrue(rules.isSatisfiedBy(request(url = "http://mockey.test:8080/service/customer/123")))
        assertFalse(rules.isSatisfiedBy(request(url = "http://mockey.test:8080/service/customer/1234")))
    }

    @Test
    fun aUrlRuleDoesNotSeeTheQueryString() {
        val rules = ruleSet(url(required("x=1")))

        assertFalse(rules.isSatisfiedBy(request(query = "x=1")))
    }

    @Test
    fun aUrlRuleSeesTheUrlAsTheClientWroteIt() {
        val request = request(url = "http://mockey.test:8080/service/rules-url/%7Bemea%7D")

        assertTrue(ruleSet(url(required("rules-url/.*emea"))).isSatisfiedBy(request))
        assertTrue(ruleSet(url(string("%7BEMEA%7D"))).isSatisfiedBy(request))
        assertFalse(ruleSet(url(string("{emea}"))).isSatisfiedBy(request))
    }

    @Test
    fun aHeaderRuleSeesEveryHeader() {
        val sent =
            listOf(
                Header("Host", "mockey.test:8080"),
                Header("Cookie", "session=abc"),
                Header("Content-Length", "7"),
                Header("Accept-Encoding", "gzip"),
                Header("If-None-Match", "\"v1\""),
                Header("If-Modified-Since", "Mon, 23 Nov 2026 00:00:00 GMT"),
            )
        val request = request(headers = sent)

        assertTrue(ruleSet(headers(string("session=", key = "Cookie"))).isSatisfiedBy(request))
        sent.forEach { header ->
            assertTrue(ruleSet(headers(string(header.value, key = header.name))).isSatisfiedBy(request), header.name)
        }
    }

    @Test
    fun aParameterRuleSeesFormFields() {
        val keyed = ruleSet(parameters(string("goog", key = "ticker")))
        val any = ruleSet(parameters(string("goog", key = "*")))

        assertTrue(keyed.isSatisfiedBy(form("ticker=GOOG")))
        assertTrue(any.isSatisfiedBy(form("ticker=GOOG")))
        assertFalse(keyed.isSatisfiedBy(form("ticker=FB")))
    }

    @Test
    fun aParameterRuleSeesTheQueryStringAndTheFormTogether() {
        val rules = ruleSet(parameters(string("goog", key = "ticker"), string("nasdaq", key = "exchange")))
        val request =
            MockRequest(
                "POST",
                "http://mockey.test:8080/service/quote",
                "ticker=goog",
                listOf(Header("Content-Type", "application/x-www-form-urlencoded")),
                "exchange=nasdaq".toByteArray(),
            )

        assertTrue(rules.isSatisfiedBy(request))
    }

    @Test
    fun theKeyOfARuleIsComparedWithoutRegardToLetterCase() {
        val rules = ruleSet(headers(string("PUT", key = "x-http-method-override")))
        val parameter = ruleSet(parameters(string("goog", key = "TICKER")))

        assertTrue(rules.isSatisfiedBy(request(headers = listOf(Header("X-HTTP-Method-Override", "PUT")))))
        assertTrue(parameter.isSatisfiedBy(request(query = "ticker=goog")))
    }

    @Test
    fun theKeyStarEvaluatesTheValuesOfAllParameters() {
        val rules = ruleSet(parameters(string("888", key = "*")))

        assertTrue(rules.isSatisfiedBy(request(query = "a=1&b=888")))
        assertFalse(rules.isSatisfiedBy(request(query = "a=1&b=2")))
        assertFalse(rules.isSatisfiedBy(request()))
    }

    @Test
    fun theKeyStarEvaluatesTheValuesOfAllHeaders() {
        val rules = ruleSet(headers(string("gold", key = "*")))

        assertTrue(rules.isSatisfiedBy(request(headers = listOf(Header("Accept", "*/*"), Header("X-Tier", "Gold")))))
        assertFalse(rules.isSatisfiedBy(request(headers = listOf(Header("Accept", "*/*"), Header("X-Tier", "silver")))))
    }

    /** DEV-44: legacy took every key that holds a `*` for the wildcard. */
    @Test
    fun onlyTheKeyStarStandsForAllParameters() {
        val rules = ruleSet(parameters(string("888", key = "tick*")))

        assertFalse(rules.isSatisfiedBy(request(query = "other=888")))
        assertFalse(rules.isSatisfiedBy(request(query = "ticker=888")))
        assertTrue(rules.isSatisfiedBy(request(query = "tick*=888")))
    }

    @Test
    fun aStringRuleIsSatisfiedByAValueThatContainsItsArgument() {
        val rules = ruleSet(parameters(string("g", key = "ticker")))

        assertTrue(rules.isSatisfiedBy(request(query = "ticker=GOOG")))
        assertFalse(rules.isSatisfiedBy(request(query = "ticker=FB")))
    }

    @Test
    fun aStringRuleIgnoresTheLetterCaseAndTheWhitespaceAroundItsArgument() {
        val rules = ruleSet(parameters(string(" \tGOOG\n ", key = "ticker")))

        assertTrue(rules.isSatisfiedBy(request(query = "ticker=xgoogx")))
        assertFalse(rules.isSatisfiedBy(request(query = "ticker=go+og")))
    }

    @Test
    fun aStringRuleKeepsTheWhitespaceInsideItsArgument() {
        val rules = ruleSet(body(string("jane doe")))

        assertTrue(rules.isSatisfiedBy(request(body = "name: Jane Doe")))
        assertFalse(rules.isSatisfiedBy(request(body = "name: JaneDoe")))
    }

    @Test
    fun oneValueOfSeveralIsEnough() {
        val request = request(query = "id=1&id=22", headers = listOf(Header("Accept", "text/plain"), Header("Accept", "application/json")))

        assertTrue(ruleSet(parameters(required("^22$", key = "id"))).isSatisfiedBy(request))
        assertTrue(ruleSet(headers(string("json", key = "accept"))).isSatisfiedBy(request))
        assertFalse(ruleSet(parameters(required("^122$", key = "id"))).isSatisfiedBy(request))
    }

    @Test
    fun anEmptyArgumentAsksOnlyForAValue() {
        val rules = ruleSet(headers(string("", key = "page_id")))

        assertTrue(rules.isSatisfiedBy(request(headers = listOf(Header("page_id", "home")))))
        assertTrue(rules.isSatisfiedBy(request(headers = listOf(Header("page_id", "")))))
        assertFalse(rules.isSatisfiedBy(request(headers = listOf(Header("page", "home")))))
    }

    /** DEV-15: legacy skipped a rule whose key the request does not have. */
    @Test
    fun aRequiredRuleFailsWhenItsKeyIsAbsent() {
        val text = ruleSet(headers(string("home", key = "page_id")))
        val presence = ruleSet(headers(string("", key = "page_id")))
        val expression = ruleSet(parameters(required(".*", key = "ticker")))

        assertFalse(text.isSatisfiedBy(request()))
        assertFalse(presence.isSatisfiedBy(request()))
        assertFalse(expression.isSatisfiedBy(request()))
    }

    @Test
    fun aRegexRuleIsSatisfiedWhenItsExpressionIsFound() {
        val rules = ruleSet(body(required(""""amount":\s*\d+""")))

        assertTrue(rules.isSatisfiedBy(request(body = """{"amount": 25}""")))
        assertFalse(rules.isSatisfiedBy(request(body = """{"amount": "none"}""")))
    }

    @Test
    fun anExpressionIsSearchedForInAValue() {
        val anywhere = ruleSet(body(required("""\d+""")))
        val whole = ruleSet(parameters(required("""^\d{3}$""", key = "id")))

        assertTrue(anywhere.isSatisfiedBy(request(body = "order 66")))
        assertTrue(whole.isSatisfiedBy(request(query = "id=123")))
        assertFalse(whole.isSatisfiedBy(request(query = "id=12a")))
        assertFalse(whole.isSatisfiedBy(request(query = "id=1234")))
    }

    @Test
    fun anExpressionRegardsLetterCase() {
        val rules = ruleSet(parameters(required("goog", key = "ticker")))

        assertTrue(rules.isSatisfiedBy(request(query = "ticker=goog")))
        assertFalse(rules.isSatisfiedBy(request(query = "ticker=GOOG")))
    }

    @Test
    fun aRequiredRegexRuleFailsWithoutABody() {
        val rules = ruleSet(body(required(".*")))

        assertFalse(rules.isSatisfiedBy(request()))
    }

    /** DEV-15: legacy did not count an optional rule whose key the request does not have. */
    @Test
    fun anOptionalRuleIsSatisfiedWhenThereIsNothingToEvaluate() {
        val parameter = ruleSet(parameters(optional("""^\d{2}/\d{2}/\d{4}$""", key = "date")))
        val body = ruleSet(body(optional("""\d+""")))

        assertEquals(RuleSetEvaluation(satisfied = true, satisfiedRuleCount = 1, failedRules = emptyList()), parameter.evaluate(request()))
        assertTrue(parameter.isSatisfiedBy(request(query = "ticker=goog")))
        assertTrue(body.isSatisfiedBy(request()))
    }

    @Test
    fun anOptionalRuleFailsForAValueThatDoesNotMatch() {
        val rules = ruleSet(parameters(optional("""^\d{2}/\d{2}/\d{4}$""", key = "date")))

        assertFalse(rules.isSatisfiedBy(request(query = "date=tomorrow")))
        assertTrue(rules.isSatisfiedBy(request(query = "date=11%2F23%2F2026")))
        assertFalse(ruleSet(body(optional("""\d+"""))).isSatisfiedBy(request(body = "no digits")))
    }

    /** DEV-55: legacy let the rule fail when the request has no parameter at all. */
    @Test
    fun anOptionalRuleWithTheKeyStarIsSatisfiedWithoutParameters() {
        val rules = ruleSet(parameters(optional("""^\d+$""", key = "*")))

        assertTrue(rules.isSatisfiedBy(request()))
        assertTrue(rules.isSatisfiedBy(request(query = "n=12")))
        assertFalse(rules.isSatisfiedBy(request(query = "n=x")))
    }

    /** DEV-56: legacy evaluated the rule once for each spelling, and each had to satisfy it. */
    @Test
    fun aRuleEvaluatesTheValuesOfAllSpellingsOfItsKeyTogether() {
        val rules = ruleSet(parameters(string("goog", key = "ticker")))

        assertEquals(1, rules.evaluate(request(query = "Ticker=fb&ticker=goog")).satisfiedRuleCount)
        assertTrue(rules.isSatisfiedBy(request(query = "Ticker=fb&ticker=goog")))
        assertFalse(rules.isSatisfiedBy(request(query = "Ticker=fb&ticker=msft")))
    }

    /** DEV-57: legacy read the body in the default charset of its JVM. */
    @Test
    fun aBodyRuleSeesTheBodyInTheCharsetThatTheRequestDeclares() {
        val rules = ruleSet(body(string("caf\u00e9")))
        val url = "http://mockey.test:8080/service/menu"
        val latin1 = listOf(Header("Content-Type", "text/plain; charset=ISO-8859-1"))

        assertTrue(rules.isSatisfiedBy(MockRequest("POST", url, null, latin1, "Caf\u00e9".toByteArray(Charsets.ISO_8859_1))))
        assertTrue(rules.isSatisfiedBy(MockRequest("POST", url, null, emptyList(), "Caf\u00e9".toByteArray(Charsets.UTF_8))))
        assertFalse(rules.isSatisfiedBy(MockRequest("POST", url, null, emptyList(), "Caf\u00e9".toByteArray(Charsets.ISO_8859_1))))
    }

    @Test
    fun aBodyOfNothingButWhitespaceIsNoValue() {
        val blank = request(body = " \t\r\n ")

        assertTrue(ruleSet(body(optional("""\d+"""))).isSatisfiedBy(blank))
        assertFalse(ruleSet(body(required("""\s"""))).isSatisfiedBy(blank))
        assertFalse(ruleSet(body(string(""))).isSatisfiedBy(blank))
        assertTrue(ruleSet(body(string(""))).isSatisfiedBy(request(body = " x ")))
    }

    @Test
    fun oneSatisfiedGroupIsEnough() {
        val rules = ruleSet(headers(string("", key = "page_id")), url(required("""\b123\b""")))

        val evaluation = rules.evaluate(request())

        assertTrue(evaluation.satisfied)
        assertEquals(1, evaluation.satisfiedRuleCount)
        assertEquals(listOf(Rule(RuleGroup.HEADERS, "page_id", RuleType.STRING_REQUIRED, "")), evaluation.failedRules)
    }

    @Test
    fun allRulesOfAGroupMustHold() {
        val rules = ruleSet(parameters(string("goog", key = "ticker"), string("nasdaq", key = "exchange")))

        val evaluation = rules.evaluate(request(query = "ticker=goog&exchange=nyse"))

        assertFalse(evaluation.satisfied)
        assertEquals(1, evaluation.satisfiedRuleCount)
        assertEquals(listOf(Rule(RuleGroup.PARAMETERS, "exchange", RuleType.STRING_REQUIRED, "nasdaq")), evaluation.failedRules)
        assertTrue(rules.isSatisfiedBy(request(query = "ticker=goog&exchange=nasdaq")))
    }

    @Test
    fun aRuleSetIsNotSatisfiedWhenNoneOfItsGroupsIs() {
        val rules = ruleSet(parameters(string("goog", key = "ticker")), body(string("username")))

        val evaluation = rules.evaluate(request(query = "ticker=fb"))

        assertEquals(RuleSetEvaluation(satisfied = false, satisfiedRuleCount = 0, failedRules = rules.rules), evaluation)
    }

    @Test
    fun aRuleSetWithoutRulesIsNeverSatisfied() {
        val request = request(query = "ticker=goog", body = "username")

        assertEquals(RuleSetEvaluation(satisfied = false, satisfiedRuleCount = 0, failedRules = emptyList()), read("{}").evaluate(request))
        assertFalse(read("""{"parameters": [], "headers": [], "body": [], "url": []}""").isSatisfiedBy(request))
    }

    @Test
    fun aGroupWithoutRulesDoesNotSatisfyARuleSet() {
        val rules = read("""{"parameters": [], "body": [{"value_rule_arg": "username", "value_rule_type": "string_required"}]}""")

        assertFalse(rules.isSatisfiedBy(request(query = "ticker=goog")))
        assertTrue(rules.isSatisfiedBy(request(body = "username")))
    }

    @Test
    fun theCountCoversTheSatisfiedRulesOfAllGroups() {
        val rules =
            ruleSet(
                parameters(string("goog", key = "ticker"), string("nasdaq", key = "exchange")),
                headers(optional("^gold$", key = "X-Tier")),
                body(string("username")),
            )

        val evaluation = rules.evaluate(request(query = "ticker=goog", body = "username=jane"))

        assertTrue(evaluation.satisfied)
        assertEquals(3, evaluation.satisfiedRuleCount)
        assertEquals(listOf("exchange"), evaluation.failedRules.map { it.key })
    }

    @Test
    fun aRuleSetWithOneUrlRuleThatIsSatisfiedCountsOne() {
        val rules = ruleSet(url(required("""\b123\b""")))

        assertEquals(RuleSetEvaluation(satisfied = true, satisfiedRuleCount = 1, failedRules = emptyList()), rules.evaluate(request()))
    }

    /**
     * The matcher of the runtime calls itself for every character that a repeated choice takes, so that a long
     * value overflows the stack. Should a runtime search such a value without that, the expression is found.
     */
    @Test
    fun aSearchThatOverflowsTheStackFindsNothing() {
        val expression = "(a|b)*c"
        val value = "a".repeat(1_000_000) + "c"
        val rules = ruleSet(body(required(expression)))
        val overflows =
            try {
                !Regex(expression).containsMatchIn(value)
            } catch (e: StackOverflowError) {
                true
            }

        assertEquals(!overflows, rules.isSatisfiedBy(request(body = value)))
        assertTrue(rules.isSatisfiedBy(request(body = "abc")))
    }

    /**
     * A request of a parity recording as the server receives it: [target] is what follows `/service/`, with the
     * query string, and the headers that the harness adds on its own come first.
     */
    private fun recorded(
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): MockRequest {
        val sent =
            listOf(Header("Host", "127.0.0.1:8080"), Header("User-Agent", "mockey-parity")) +
                headers.map { Header(it.key, it.value) } +
                listOfNotNull(body?.let { Header("Content-Length", it.size.toString()) })
        val query = if ('?' in target) target.substringAfter('?') else null
        val url = "http://127.0.0.1:8080/service/${target.substringBefore('?')}"
        return MockRequest(if (body == null) "GET" else "POST", url, query, sent, body ?: ByteArray(0))
    }

    private fun recorded(
        target: String,
        contentType: String,
        body: String,
    ) = recorded(target, mapOf("Content-Type" to contentType), body.toByteArray())

    /** A recording: the rule sets of the scenarios of its service, its request, and whether each is satisfied, with its count. */
    private class Recording(
        val name: String,
        val ruleSets: List<RuleSet>,
        val request: MockRequest,
        vararg val expected: Pair<Boolean, Int>,
    )

    private fun assertEvaluatedAsStated(recordings: List<Recording>) {
        recordings.forEach { recording ->
            val evaluated = recording.ruleSets.map { it.evaluate(recording.request) }.map { it.satisfied to it.satisfiedRuleCount }
            assertEquals(recording.expected.toList(), evaluated, recording.name)
        }
    }

    /**
     * The rule sets of the parity fixtures `rules-keys` and `rules-inputs`, without their descriptions, and the
     * requests of their recordings. A rule set is satisfied when the stated new response of the recording is
     * that of its scenario, and where two rule sets are satisfied, the one with the higher count answers.
     */
    @Test
    fun theRuleSetsOfTheKeyAndInputRecordingsAreSatisfiedAsTheRecordingsState() {
        val pageId = listOf(ruleSet(headers(string("", key = "page_id"))))
        val ticker = string("goog", key = "ticker")
        val optionalDate =
            listOf(ruleSet(parameters(ticker)), ruleSet(parameters(ticker, optional("""^\d{2}/\d{2}/\d{4}$""", key = "date"))))
        val anyParameter = listOf(ruleSet(parameters(string("888", key = "*"))))
        val bodyRequired = listOf(ruleSet(body(required("""\d+"""))))
        val bodyOptional = listOf(ruleSet(body(optional("""\d+"""))))
        val anyHeader = { argument: String -> listOf(ruleSet(headers(string(argument, key = "*")))) }
        val form = "application/x-www-form-urlencoded"
        val override = listOf(ruleSet(headers(string("PUT", key = "x-http-method-override"))))
        val wholeUrl = listOf(ruleSet(url(required("""^http://127\.0\.0\.1:\d+/service/inputs/url$"""))))
        val modifiedSince = mapOf("If-Modified-Since" to "Mon, 23 Nov 2026 00:00:00 GMT")

        assertEvaluatedAsStated(
            listOf(
                Recording("rule-key-absent-required", pageId, recorded("keys/required"), false to 0),
                Recording("rule-key-present-required", pageId, recorded("keys/required", mapOf("page_id" to "home")), true to 1),
                Recording("rule-key-absent-optional", optionalDate, recorded("keys/optional?ticker=goog"), true to 1, true to 2),
                Recording(
                    "rule-key-optional-mismatch",
                    optionalDate,
                    recorded("keys/optional?ticker=goog&date=tomorrow"),
                    true to 1,
                    false to 1,
                ),
                Recording(
                    "rule-key-optional-match",
                    optionalDate,
                    recorded("keys/optional?ticker=goog&date=11%2F23%2F2026"),
                    true to 1,
                    true to 2,
                ),
                Recording("rule-key-letter-case", override, recorded("keys/case", mapOf("X-HTTP-Method-Override" to "PUT")), true to 1),
                Recording("rule-key-wildcard-parameter", anyParameter, recorded("keys/any-parameter?a=1&b=888"), true to 1),
                Recording("rule-key-wildcard-parameter-no-match", anyParameter, recorded("keys/any-parameter?a=1&b=2"), false to 0),
                Recording("rule-key-wildcard-without-parameters", anyParameter, recorded("keys/any-parameter"), false to 0),
                Recording("rule-key-wildcard-header", anyHeader("gold"), recorded("keys/any-header", mapOf("X-Tier" to "Gold")), true to 1),
                Recording(
                    "rule-key-partial-wildcard",
                    listOf(ruleSet(parameters(string("888", key = "tick*")))),
                    recorded("keys/partial-wildcard?other=888"),
                    false to 0,
                ),
                Recording(
                    "rule-input-form-parameter",
                    listOf(ruleSet(parameters(string("goog", key = "*")))),
                    recorded("inputs/form-any", form, "ticker=GOOG"),
                    true to 1,
                ),
                Recording(
                    "rule-input-form-parameter-keyed",
                    listOf(ruleSet(parameters(ticker))),
                    recorded("inputs/form-keyed", form, "ticker=FB"),
                    false to 0,
                ),
                Recording("rule-input-header-host", anyHeader("127.0.0.1"), recorded("inputs/header-host"), true to 1),
                Recording(
                    "rule-input-header-cookie",
                    anyHeader("session="),
                    recorded("inputs/header-cookie", mapOf("Cookie" to "session=abc")),
                    true to 1,
                ),
                Recording(
                    "rule-input-header-content-length",
                    listOf(ruleSet(headers(required("^7$", key = "*")))),
                    recorded("inputs/header-content-length", "text/plain", "7 bytes"),
                    true to 1,
                ),
                Recording(
                    "rule-input-header-accept-encoding",
                    anyHeader("gzip"),
                    recorded("inputs/header-accept-encoding", mapOf("Accept-Encoding" to "gzip")),
                    true to 1,
                ),
                Recording(
                    "rule-input-header-if-none-match",
                    anyHeader("\"v1\""),
                    recorded("inputs/header-if-none-match", mapOf("If-None-Match" to "\"v1\"")),
                    true to 1,
                ),
                Recording(
                    "rule-input-header-if-modified-since",
                    anyHeader("Nov 2026"),
                    recorded("inputs/header-if-modified-since", modifiedSince),
                    true to 1,
                ),
                Recording("rule-input-body-absent-required", bodyRequired, recorded("inputs/body-required"), false to 0),
                Recording(
                    "rule-input-body-present-required",
                    bodyRequired,
                    recorded("inputs/body-required", "text/plain", "order 66"),
                    true to 1,
                ),
                Recording("rule-input-body-absent-optional", bodyOptional, recorded("inputs/body-optional"), true to 1),
                Recording(
                    "rule-input-body-present-optional-mismatch",
                    bodyOptional,
                    recorded("inputs/body-optional", "text/plain", "no digits"),
                    false to 0,
                ),
                Recording("rule-input-url-whole", wholeUrl, recorded("inputs/url?x=1"), true to 1),
                Recording(
                    "rule-input-url-without-query",
                    listOf(ruleSet(url(required("x=1")))),
                    recorded("inputs/url-query?x=1"),
                    false to 0,
                ),
            ),
        )
    }

    /**
     * The well-formed rule sets of the parity fixtures `rules-types`, `rules-malformed`, and `rules-values`, and
     * the requests of their recordings.
     */
    @Test
    fun theRuleSetsOfTheTypeAndValueRecordingsAreSatisfiedAsTheRecordingsState() {
        val threeDigits = listOf(ruleSet(parameters(required("""^\d{3}$""", key = "id"))))
        val optionalAny = listOf(ruleSet(parameters(optional("""^\d+$""", key = "*"))))
        val accented = listOf(ruleSet(body(string("caf\u00e9"))))
        val upperCaseType = """{"parameters": [{"key": "ticker", "value_rule_arg": "goog", "value_rule_type": "STRING_REQUIRED"}]}"""
        val numberArgument = """{"parameters": [{"key": "id", "value_rule_arg": 44, "value_rule_type": "string_required"}]}"""
        val latin1 = mapOf("Content-Type" to "text/plain; charset=ISO-8859-1")

        assertEvaluatedAsStated(
            listOf(
                Recording(
                    "rule-type-string-empty-argument",
                    listOf(ruleSet(headers(string("", key = "page_id")))),
                    recorded("types/string-empty", mapOf("page_id" to "home")),
                    true to 1,
                ),
                Recording(
                    "rule-type-string-trimmed",
                    listOf(ruleSet(parameters(string(" GOOG ", key = "ticker")))),
                    recorded("types/string-trimmed?ticker=xgoogx"),
                    true to 1,
                ),
                Recording("rule-type-regex-found", threeDigits, recorded("types/regex-required?id=123"), true to 1),
                Recording("rule-type-regex-not-found", threeDigits, recorded("types/regex-required?id=12a"), false to 0),
                Recording("rule-type-letter-case", listOf(read(upperCaseType)), recorded("types/letter-case?ticker=goog"), true to 1),
                Recording("rule-set-empty", listOf(read("""{"parameters": []}""")), recorded("types/empty?ticker=goog"), false to 0),
                Recording("rule-number-argument", listOf(read(numberArgument)), recorded("malformed/number-argument?id=1445"), true to 1),
                Recording("rule-key-wildcard-optional-without-parameters", optionalAny, recorded("values/optional-any"), true to 1),
                Recording("rule-key-wildcard-optional-match", optionalAny, recorded("values/optional-any?n=12"), true to 1),
                Recording(
                    "rule-key-two-letter-cases",
                    listOf(ruleSet(parameters(string("goog", key = "ticker")))),
                    recorded("values/two-cases?Ticker=fb&ticker=goog"),
                    true to 1,
                ),
                Recording(
                    "rule-input-body-declared-charset",
                    accented,
                    recorded("values/body-charset", latin1, "caf\u00e9".toByteArray(Charsets.ISO_8859_1)),
                    true to 1,
                ),
                Recording(
                    "rule-input-body-utf8",
                    accented,
                    recorded("values/body-charset", "text/plain; charset=UTF-8", "caf\u00e9"),
                    true to 1,
                ),
                Recording(
                    "rule-input-body-blank",
                    listOf(ruleSet(body(optional("""\d+""")))),
                    recorded("values/blank-body", "text/plain", "   "),
                    true to 1,
                ),
            ),
        )
    }

    /** The well-formed rule sets of the parity fixture `responses-rules` and the requests of its recordings. */
    @Test
    fun theRuleSetsOfTheResponseRecordingsAreSatisfiedAsTheRecordingsState() {
        val ticker = string("goog", key = "ticker")
        val parameter = listOf(ruleSet(parameters(string("g", key = "ticker"))))
        val header = listOf(ruleSet(headers(string("gold", key = "X-Tier"))))
        val body = listOf(ruleSet(body(string("username"))))
        val url = listOf(ruleSet(url(required("rules-url/.*emea"))))
        val score = listOf(ruleSet(parameters(ticker)), ruleSet(parameters(ticker, string("nasdaq", key = "exchange"))))
        val groups = listOf(ruleSet(parameters(ticker), body(string("username"))))
        val json = "application/json"

        assertEvaluatedAsStated(
            listOf(
                Recording("response-rules-parameter-satisfied", parameter, recorded("rules/parameter?ticker=GOOG"), true to 1),
                Recording("response-rules-parameter-unsatisfied", parameter, recorded("rules/parameter?ticker=FB"), false to 0),
                Recording("response-rules-header-satisfied", header, recorded("rules/header", mapOf("X-Tier" to "Gold")), true to 1),
                Recording("response-rules-header-unsatisfied", header, recorded("rules/header", mapOf("X-Tier" to "silver")), false to 0),
                Recording("response-rules-body-satisfied", body, recorded("rules/body", json, """{"username":"jane"}"""), true to 1),
                Recording("response-rules-body-unsatisfied", body, recorded("rules/body", json, """{"user":"jane"}"""), false to 0),
                Recording("response-rules-url-satisfied", url, recorded("rules-url/%7Bemea%7D"), true to 1),
                Recording("response-rules-url-unsatisfied", url, recorded("rules-url/%7Bapac%7D"), false to 0),
                Recording("response-rules-score-highest", score, recorded("rules/score?ticker=goog&exchange=nasdaq"), true to 1, true to 2),
                Recording("response-rules-score-partial", score, recorded("rules/score?ticker=goog&exchange=nyse"), true to 1, false to 1),
                Recording("response-rules-groups-or", groups, recorded("rules/groups?ticker=fb", "text/plain", "username=jane"), true to 1),
                Recording("response-rules-groups-none", groups, recorded("rules/groups?ticker=fb"), false to 0),
            ),
        )
    }
}
