package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.routing.Route
import io.github.dnedrow.mockey.core.routing.RoutingTable
import io.github.dnedrow.mockey.core.routing.Target
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioMatchingTest {
    private fun scenario(
        name: String,
        criterion: MatchCriterion,
        method: String? = null,
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = name, matchCriterion = criterion, methodRestriction = method)

    private fun text(
        name: String,
        criterion: String,
        method: String? = null,
    ) = scenario(name, MatchCriterion.Text(criterion), method)

    private fun rules(
        name: String,
        source: String,
    ) = scenario(name, MatchCriterion.Rules(source))

    private fun plain(name: String) = scenario(name, MatchCriterion.None)

    private fun rule(
        argument: String,
        key: String? = null,
        type: String = "string_required",
    ): JsonObject =
        buildJsonObject {
            key?.let { put("key", it) }
            put("value_rule_arg", argument)
            put("value_rule_type", type)
        }

    private fun group(
        name: String,
        vararg rules: JsonObject,
    ): Pair<String, List<JsonObject>> = name to rules.toList()

    private fun ruleSet(vararg groups: Pair<String, List<JsonObject>>): String =
        buildJsonObject { groups.forEach { (name, rules) -> put(name, JsonArray(rules)) } }.toString()

    private fun service(
        mockUrl: String,
        vararg scenarios: Scenario,
    ) = Service(
        id = ServiceId.generate(),
        name = mockUrl,
        mockUrl = mockUrl,
        responseMode = ResponseMode.DYNAMIC,
        scenarios = scenarios.toList(),
    )

    /** A request for [target], which is what follows `/service/`, with the query string. */
    private fun request(
        target: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "text/plain",
        headers: List<Header> = emptyList(),
    ): MockRequest {
        val query = if ('?' in target) target.substringAfter('?') else null
        val sent = headers + listOfNotNull(body?.let { Header("Content-Type", contentType) })
        val url = "http://mockey.test:8080/service/${target.substringBefore('?')}"
        return MockRequest(method, url, query, sent, body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
    }

    /** Routes a request for [target] to a service of these definitions. */
    private fun Definitions.routed(target: String): Route.ToService {
        val route = RoutingTable(this).route(Target(target.substringBefore('?')), Tags.EMPTY, forwardUnknownTargets = false)
        return assertIs<Route.ToService>(route, target)
    }

    /** Returns the scenarios that match [request] for [target] in the service that the request reaches. */
    private fun Definitions.matches(
        target: String,
        request: MockRequest,
    ): List<ScenarioMatch> = routed(target).let { scenarioMatches(it.service, it.variables, request, RuleSets(this)) }

    /** Returns the scenario that answers [request] for [target] in dynamic mode, or `null` when none matches. */
    private fun Definitions.selected(
        target: String,
        request: MockRequest,
    ): Scenario? = routed(target).let { bestMatch(it.service, it.variables, request, RuleSets(this)) }

    private fun Service.matches(
        target: String,
        method: String = "GET",
        body: String? = null,
    ): List<ScenarioMatch> = Definitions(services = listOf(this)).matches(target, request(target, method, body))

    /** Returns the name of the scenario that answers a request for [target] in dynamic mode, or `null` when none matches. */
    private fun Service.selected(
        target: String,
        method: String = "GET",
        body: String? = null,
    ): String? = Definitions(services = listOf(this)).selected(target, request(target, method, body))?.name

    private fun evaluationTextOf(
        query: String? = null,
        body: String? = null,
        variables: Map<String, String> = emptyMap(),
    ): String = evaluationText(variables, request(if (query == null) "quotes" else "quotes?$query", body = body))

    @Test
    fun aScenarioRestrictedToAnotherMethodIsSkipped() {
        val service = service("orders", text("post orders", "order", method = "POST"))

        assertNull(service.selected("orders?q=order", method = "GET"))
        assertEquals("post orders", service.selected("orders?q=order", method = "POST"))
    }

    @Test
    fun theRestrictionStarAdmitsEveryMethod() {
        val service = service("orders", text("wildcard", "order", method = "*"))

        listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").forEach { method ->
            assertEquals("wildcard", service.selected("orders?q=order", method), method)
        }
    }

    @Test
    fun aScenarioWithoutARestrictionAdmitsEveryMethod() {
        val unrestricted = text("unrestricted", "order")

        assertTrue(unrestricted.admits("DELETE"))
        assertTrue(unrestricted.copy(methodRestriction = "").admits("DELETE"))
        assertTrue(unrestricted.copy(methodRestriction = "  ").admits("DELETE"))
        assertEquals("unrestricted", service("orders", unrestricted).selected("orders?q=order", method = "DELETE"))
    }

    @Test
    fun aRestrictionIsComparedWithoutRegardToLetterCase() {
        val lowercase = text("lowercase post", "order", method = "post")

        assertTrue(lowercase.admits("POST"))
        assertTrue(lowercase.admits("Post"))
        assertTrue(lowercase.copy(methodRestriction = " POST ").admits("post"))
        assertFalse(lowercase.admits("PUT"))
        assertFalse(lowercase.copy(methodRestriction = "GET, POST").admits("POST"))
    }

    @Test
    fun aSkippedScenarioLeavesTheAnswerToAnother() {
        val service = service("orders", text("long for post", "q=order", method = "POST"), text("short for all", "order"))

        assertEquals("short for all", service.selected("orders?q=order", method = "GET"))
        assertEquals("long for post", service.selected("orders?q=order", method = "POST"))
    }

    @Test
    fun theValueOfAVariableIsEvaluated() {
        val service = service("customer/{id}", text("customer 123", "123"))

        assertEquals("customer 123", service.selected("customer/123"))
        assertNull(service.selected("customer/456"))
    }

    @Test
    fun aQueryParameterIsEvaluatedAfterDecoding() {
        val service = service("quotes", text("jane", "name=jane doe"))

        assertEquals("jane", service.selected("quotes?name=jane%20doe"))
        assertEquals("jane", service.selected("quotes?name=jane+doe"))
    }

    @Test
    fun theBodyIsEvaluated() {
        val service = service("quotes", text("gold", "<tier>gold</tier>"))

        assertEquals("gold", service.selected("quotes", method = "POST", body = "<order><tier>gold</tier></order>"))
        assertNull(service.selected("quotes", method = "POST", body = "<order><tier>silver</tier></order>"))
    }

    @Test
    fun literalTextOfThePathIsNotEvaluated() {
        val literal = service("orders/list", text("orders", "orders"))
        val withVariable = service("orders/{id}/items", text("items", "items"), text("orders", "orders"))

        assertNull(literal.selected("orders/list"))
        assertNull(withVariable.selected("orders/7/items"))
    }

    @Test
    fun theEvaluationTextHoldsTheVariablesTheParametersAndTheBodyWithNothingBetweenThem() {
        val variables = linkedMapOf("hotel" to "12", "booking" to "34")

        assertEquals("1234p=1&q=2body", evaluationTextOf("p=1&q=2", "body", variables))
        assertEquals("1234", evaluationTextOf(variables = variables))
        assertEquals("p=1&q=2", evaluationTextOf("p=1&q=2"))
        assertEquals("body", evaluationTextOf(body = "body"))
        assertEquals("", evaluationTextOf())
    }

    @Test
    fun theVariablesAreInTheOrderOfThePattern() {
        val service = service("hotels/{hotel}/bookings/{booking}", text("joined", "1242"), text("reversed", "4212"))

        assertEquals("joined", service.selected("hotels/12/bookings/42"))
    }

    @Test
    fun theParametersAreInTheOrderOfTheRequest() {
        assertEquals("b=2&a=1", evaluationTextOf("b=2&a=1"))
        assertEquals("a=1&b=2&a=3", evaluationTextOf("a=1&b=2&a=3"))
    }

    @Test
    fun aParameterIsWrittenAsTheRequestHasIt() {
        assertEquals("debug=", evaluationTextOf("debug="))
        assertEquals("wsdl", evaluationTextOf("wsdl"))
        assertEquals("a=1&a=2", evaluationTextOf("a=1&a=2"))
        assertEquals("=5&a=b=c", evaluationTextOf("=5&a=b=c"))
    }

    @Test
    fun anEmptyParameterIsLeftOut() {
        assertEquals("a=1&b=2", evaluationTextOf("a=1&&b=2"))
        assertEquals("a=1", evaluationTextOf("&a=1&"))
        assertEquals("", evaluationTextOf("&&"))
        assertEquals("", evaluationTextOf(""))
    }

    @Test
    fun theTextIsDecodedAsFormDataIs() {
        assertEquals("name=jane doe", evaluationTextOf("name=jane%20doe"))
        assertEquals("first last", evaluationTextOf(body = "first+last"))
        assertEquals("phone=+15551234", evaluationTextOf("phone=%2B15551234"))
        assertEquals("caf\u00e9 \u20ac", evaluationTextOf(body = "caf%C3%A9+%E2%82%AC"))
        assertEquals("q=%41", evaluationTextOf("q=%2541"))
    }

    @Test
    fun aPercentSignThatStartsNoEscapeStaysAsItIs() {
        assertEquals("discount=100% off", evaluationTextOf(body = "discount=100% off"))
        assertEquals("q=%zz&r=%4", evaluationTextOf("q=%zz&r=%4"))
        assertEquals("100%", evaluationTextOf(body = "100%"))
    }

    @Test
    fun theValuesOfTheVariablesAreDecodedWithTheRestOfTheText() {
        assertEquals("a b", evaluationTextOf(variables = mapOf("id" to "a+b")))
        assertEquals("A", evaluationTextOf(variables = mapOf("id" to "%41")))
    }

    @Test
    fun aBodyOfNothingButWhitespaceIsLeftOut() {
        assertEquals("q=1", evaluationTextOf("q=1", body = " \r\n\t"))
        assertEquals("q=1 x ", evaluationTextOf("q=1", body = " x "))
    }

    @Test
    fun theFieldsOfAFormAreEvaluatedAsPartOfTheBody() {
        val form =
            request("quotes?a=1", method = "POST", body = "ticker=goog&name=jane+doe", contentType = "application/x-www-form-urlencoded")

        assertEquals("a=1ticker=goog&name=jane doe", evaluationText(emptyMap(), form))
    }

    @Test
    fun theBodyIsReadInTheCharsetThatTheRequestDeclares() {
        val latin1 = listOf(Header("Content-Type", "text/plain; charset=ISO-8859-1"))
        val url = "http://mockey.test:8080/service/quotes"
        val request = MockRequest("POST", url, null, latin1, "caf\u00e9".toByteArray(Charsets.ISO_8859_1))

        assertEquals("caf\u00e9", evaluationText(emptyMap(), request))
    }

    @Test
    fun theHeadersAreNotEvaluated() {
        val service = service("quotes", text("gold", "gold"))
        val request = request("quotes", headers = listOf(Header("X-Tier", "gold"), Header("Cookie", "tier=gold")))

        assertEquals("", evaluationText(emptyMap(), request))
        assertEquals(emptyList(), Definitions(services = listOf(service)).matches("quotes", request))
    }

    @Test
    fun aTextCriterionIsFoundWithoutRegardToLetterCase() {
        val goog = text("goog", "GOOG")

        assertEquals(listOf(ScenarioMatch(goog, score = 4)), service("quotes", goog).matches("quotes?ticker=goog"))
        assertEquals(listOf(ScenarioMatch(goog, score = 4)), service("quotes", goog).matches("quotes?ticker=GooG"))
        assertEquals(emptyList(), service("quotes", goog).matches("quotes?ticker=msft"))
    }

    @Test
    fun theScoreOfATextCriterionCountsNoWhitespaceAroundIt() {
        val padded = text("padded", " goog\t")

        assertEquals(listOf(ScenarioMatch(padded, score = 4)), service("quotes", padded).matches("quotes?ticker=+goog%09"))
    }

    @Test
    fun aTextCriterionIsLookedForWithTheWhitespaceAroundIt() {
        val padded = text("padded", " goog")

        assertEquals(emptyList(), service("quotes", padded).matches("quotes?ticker=goog"))
    }

    @Test
    fun aRuleSetCriterionIsMetWhenTheRequestSatisfiesIt() {
        val byUrl = rules("customer 123", ruleSet(group("url", rule("""\b123\b""", type = "regex_required"))))
        val service = service("customer/{id}", byUrl)

        assertEquals(listOf(ScenarioMatch(byUrl, score = 1)), service.matches("customer/123"))
        assertEquals(emptyList(), service.matches("customer/1234"))
    }

    @Test
    fun theScoreOfARuleSetIsTheNumberOfRulesThatTheRequestSatisfies() {
        val ticker = rule("goog", key = "ticker")
        val exchange = rule("nasdaq", key = "exchange")
        val two = rules("two rules", ruleSet(group("parameters", ticker, exchange)))
        val groups = rules("two groups", ruleSet(group("parameters", ticker, exchange), group("body", rule("username"))))
        val service = service("quotes", two, groups)

        assertEquals(
            listOf(ScenarioMatch(two, score = 2), ScenarioMatch(groups, score = 3)),
            service.matches("quotes?ticker=goog&exchange=nasdaq", method = "POST", body = "username=jane"),
        )
        assertEquals(
            listOf(ScenarioMatch(groups, score = 2)),
            service.matches("quotes?ticker=goog&exchange=nyse", method = "POST", body = "username=jane"),
        )
        assertEquals(emptyList(), service.matches("quotes?ticker=goog&exchange=nyse"))
    }

    @Test
    fun aRuleSetThatIsNotWellFormedNeverMatches() {
        val service =
            service("quotes", rules("broken", """{"parameters": [{"key": "ticker""""), rules("blank", " "), rules("no rules", "{}"))

        assertEquals(emptyList(), service.matches("quotes?ticker=goog"))
    }

    @Test
    fun theLongerTextCriterionWins() {
        val service = service("codes", text("a short", "123"), text("b long", "ABC123"))

        assertEquals("b long", service.selected("codes?code=abc123"))
        assertEquals("a short", service.selected("codes?code=xyz123"))
    }

    @Test
    fun equalScoresGoToTheNameThatSortsFirstWithoutRegardToLetterCase() {
        val service = service("colors", text("beta", "red"), text("Alpha", "box"))

        assertEquals("Alpha", service.selected("colors?q=redbox"))
        assertEquals("beta", service.selected("colors?q=red"))
    }

    @Test
    fun equalScoresAndNamesGoToTheScenarioThatWasCreatedFirst() {
        val first = text("twin", "red")
        val second = text("Twin", "box")
        val third = text("TWIN", "dbo")

        val matches = service("colors", first, second, third).matches("colors?q=redbox")

        assertEquals(3, matches.size)
        assertEquals(first, matches.best()?.scenario)
        assertEquals(second, matches.drop(1).best()?.scenario)
    }

    @Test
    fun theHigherScoreWinsWhateverTheNamesAndTheOrder() {
        val long = text("zulu", "redbox")
        val short = text("alpha", "red")

        assertEquals("zulu", service("colors", short, long).selected("colors?q=redbox"))
        assertEquals("zulu", service("colors", long, short).selected("colors?q=redbox"))
    }

    @Test
    fun aScenarioWithoutACriterionIsNeverSelected() {
        assertNull(service("quotes", plain("no criterion")).selected("quotes?q=anything"))
        assertNull(service("quotes").selected("quotes?q=anything"))
    }

    @Test
    fun aTextCriterionAndARuleSetCompeteByTheirScores() {
        val byRule = ruleSet(group("parameters", rule("red", key = "q")))
        val tie = service("colors", text("b text", "r"), rules("a rule", byRule))
        val textWins = service("colors", rules("a rule", byRule), text("b text", "red"))

        assertEquals("a rule", tie.selected("colors?q=red"))
        assertEquals("b text", textWins.selected("colors?q=red"))
    }

    /**
     * A parity recording: its request, and the name of the scenario whose response its stated new response is.
     * That name is `null` where the stated new response is that of the error scenario, because no scenario matches.
     */
    private class Recording(
        val name: String,
        val scenario: String?,
        val target: String,
        val request: MockRequest,
    )

    /** A recording with its request as the server receives it: the headers that the parity harness adds come first. */
    private fun recording(
        name: String,
        scenario: String?,
        target: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "text/plain",
        headers: List<Header> = emptyList(),
    ): Recording {
        val harness = listOf(Header("Host", "127.0.0.1:8080"), Header("User-Agent", "mockey-parity"))
        return Recording(name, scenario, target, request(target, method, body, contentType, harness + headers))
    }

    private fun Definitions.assertSelectedAsStated(vararg recordings: Recording) {
        recordings.forEach { recording ->
            assertEquals(recording.scenario, selected(recording.target, recording.request)?.name, recording.name)
        }
    }

    /**
     * The services of the parity fixture `responses-text` and the requests of its recordings. The parameters are
     * evaluated in the order of the request (DEV-35), a `%` that starts no escape is left as it is (DEV-36), and
     * the value of a variable is evaluated (DEV-01).
     */
    @Test
    fun theScenariosOfTheTextRecordingsAreSelectedAsTheRecordingsState() {
        val quotes =
            service(
                "text/quotes",
                text("goog", "GOOG"),
                text("jane", "name=jane doe"),
                text("gold", "<tier>gold</tier>"),
                text("joined", "a=1&b=2"),
                text("plus", "first last"),
                plain("no match"),
            )
        val literal = service("text/orders/list", text("orders", "orders"), plain("no match"))
        val template = service("text-customer/{id}", text("customer 123", "123"), plain("no match"))
        val order = "<order><tier>gold</tier></order>"

        Definitions(services = listOf(quotes, literal, template)).assertSelectedAsStated(
            recording("response-text-case-insensitive", "goog", "text/quotes?ticker=goog"),
            recording("response-text-query-decoded", "jane", "text/quotes?name=jane%20doe"),
            recording("response-text-body", "gold", "text/quotes", "POST", order, "application/xml"),
            recording("response-text-params-joined", "joined", "text/quotes?a=1&b=2"),
            recording("response-text-params-reordered", null, "text/quotes?b=2&a=1"),
            recording("response-text-body-plus", "plus", "text/quotes", "POST", "first+last"),
            recording("response-text-body-percent", null, "text/quotes", "POST", "discount=100% off"),
            recording("response-text-no-match", null, "text/quotes?ticker=msft"),
            recording("response-text-literal-path", null, "text/orders/list"),
            recording("response-text-template-value", "customer 123", "text-customer/%7B123%7D"),
        )
    }

    /**
     * The service of the parity fixture `responses-parameters` and the requests of its recordings. A parameter is
     * written as the request has it, and `&` separates the parameters (DEV-58).
     */
    @Test
    fun theScenariosOfTheParameterRecordingsAreSelectedAsTheRecordingsState() {
        val evaluate =
            service(
                "parameters/evaluate",
                text("bare name", "debug"),
                text("name and equals sign", "debug="),
                text("unseparated", "a=1a=2"),
                text("separated", "a=1&a=2"),
                text("without value", "wsdl"),
                text("plus sign", "+1555"),
                text("parameter and body", "q=abcdef"),
                text("escape in body", "jane doe"),
                text("two parameters", "a=1&b=2"),
                plain("no match"),
            )

        Definitions(services = listOf(evaluate)).assertSelectedAsStated(
            recording("response-text-param-empty-value", "name and equals sign", "parameters/evaluate?debug="),
            recording("response-text-param-repeated", "separated", "parameters/evaluate?a=1&a=2"),
            recording("response-text-param-without-value", "without value", "parameters/evaluate?wsdl"),
            recording("response-text-param-encoded-plus", "plus sign", "parameters/evaluate?phone=%2B15551234"),
            recording("response-text-param-empty-piece", "two parameters", "parameters/evaluate?a=1&&b=2"),
            recording("response-text-param-then-body", "parameter and body", "parameters/evaluate?q=abc", "POST", "def"),
            recording("response-text-body-escape", "escape in body", "parameters/evaluate", "POST", "jane%20doe"),
        )
    }

    /** The service of the parity fixture `responses-methods` and the requests of its recordings. */
    @Test
    fun theScenariosOfTheMethodRecordingsAreSelectedAsTheRecordingsState() {
        val orders =
            service(
                "methods/orders",
                text("get orders", "order", method = "GET"),
                text("post orders", "order", method = "POST"),
                text("wildcard", "wild", method = "*"),
                text("unrestricted", "open"),
                text("lowercase post", "lower", method = "post"),
                plain("no eligible scenario"),
            )

        Definitions(services = listOf(orders)).assertSelectedAsStated(
            recording("response-method-get", "get orders", "methods/orders?q=order"),
            recording("response-method-post", "post orders", "methods/orders?q=order", "POST"),
            recording("response-method-not-eligible", null, "methods/orders?q=order", "DELETE"),
            recording("response-method-wildcard", "wildcard", "methods/orders?q=wild", "DELETE"),
            recording("response-method-unrestricted", "unrestricted", "methods/orders?q=open", "PUT"),
            recording("response-method-case-insensitive", "lowercase post", "methods/orders?q=lower", "POST"),
        )
    }

    /**
     * The services of the parity fixture `responses-ties` and the requests of its recordings. The scenarios are in
     * the order of their legacy IDs, which is the order in which they count as created.
     */
    @Test
    fun theScenariosOfTheTieRecordingsAreSelectedAsTheRecordingsState() {
        val byRule = ruleSet(group("parameters", rule("red", key = "q")))
        val longer = service("ties/longer", text("a short", "123"), text("b long", "ABC123"), plain("no match"))
        val name = service("ties/name", text("beta", "red"), text("Alpha", "box"), plain("no match"))
        val firstTwin = text("twin", "red")
        val created = service("ties/created", firstTwin, text("twin", "box"), plain("no match"))
        val lowerId = text("twin", "box")
        val idOrder = service("ties/id-order", lowerId, text("twin", "red"), plain("no match"))
        val textAndRule = service("ties/text-and-rule", text("b text", "r"), rules("a rule", byRule), plain("no match"))
        val textOverRule = service("ties/text-over-rule", rules("a rule", byRule), text("b text", "red"), plain("no match"))
        val definitions = Definitions(services = listOf(longer, name, created, idOrder, textAndRule, textOverRule))
        val twins = recording("response-tie-creation-order", "twin", "ties/created?q=redbox")
        val twinsById = recording("response-tie-id-order", "twin", "ties/id-order?q=redbox")

        definitions.assertSelectedAsStated(
            recording("response-score-longer-criterion", "b long", "ties/longer?code=abc123"),
            recording("response-tie-name-order", "Alpha", "ties/name?q=redbox"),
            twins,
            twinsById,
            recording("response-tie-text-and-rule", "a rule", "ties/text-and-rule?q=red"),
            recording("response-score-text-over-rule", "b text", "ties/text-over-rule?q=red"),
        )
        assertEquals(firstTwin, definitions.selected(twins.target, twins.request))
        assertEquals(lowerId, definitions.selected(twinsById.target, twinsById.request))
    }

    /**
     * The services of the parity fixture `responses-rules`, without the descriptions of their rules, and the
     * requests of its recordings.
     */
    @Test
    fun theScenariosOfTheRuleRecordingsAreSelectedAsTheRecordingsState() {
        val ticker = rule("goog", key = "ticker")
        val username = group("body", rule("username"))
        val byParameter = ruleSet(group("parameters", rule("g", key = "ticker")))
        val byHeader = ruleSet(group("headers", rule("gold", key = "X-Tier")))
        val byUrl = ruleSet(group("url", rule("rules-url/.*emea", type = "regex_required")))
        val score =
            service(
                "rules/score",
                rules("one rule", ruleSet(group("parameters", ticker))),
                rules("two rules", ruleSet(group("parameters", ticker, rule("nasdaq", key = "exchange")))),
                plain("no match"),
            )
        val services =
            listOf(
                service("rules/parameter", rules("ticker contains g", byParameter), plain("no match")),
                service("rules/header", rules("gold tier header", byHeader), plain("no match")),
                service("rules/body", rules("body has username", ruleSet(username)), plain("no match")),
                service("rules-url/{region}", rules("emea url", byUrl), plain("no match")),
                score,
                service("rules/groups", rules("either group", ruleSet(group("parameters", ticker), username)), plain("no match")),
                service("rules/malformed", rules("broken rules", """{"parameters": [{"key": "ticker""""), plain("no match")),
            )
        val json = "application/json"
        val gold = listOf(Header("X-Tier", "Gold"))
        val silver = listOf(Header("X-Tier", "silver"))

        Definitions(services = services).assertSelectedAsStated(
            recording("response-rules-parameter-satisfied", "ticker contains g", "rules/parameter?ticker=GOOG"),
            recording("response-rules-parameter-unsatisfied", null, "rules/parameter?ticker=FB"),
            recording("response-rules-header-satisfied", "gold tier header", "rules/header", headers = gold),
            recording("response-rules-header-unsatisfied", null, "rules/header", headers = silver),
            recording("response-rules-body-satisfied", "body has username", "rules/body", "POST", """{"username":"jane"}""", json),
            recording("response-rules-body-unsatisfied", null, "rules/body", "POST", """{"user":"jane"}""", json),
            recording("response-rules-url-satisfied", "emea url", "rules-url/%7Bemea%7D"),
            recording("response-rules-url-unsatisfied", null, "rules-url/%7Bapac%7D"),
            recording("response-rules-score-highest", "two rules", "rules/score?ticker=goog&exchange=nasdaq"),
            recording("response-rules-score-partial", "one rule", "rules/score?ticker=goog&exchange=nyse"),
            recording("response-rules-groups-or", "either group", "rules/groups?ticker=fb", "POST", "username=jane"),
            recording("response-rules-groups-none", null, "rules/groups?ticker=fb"),
            recording("response-rules-malformed", null, "rules/malformed?ticker=goog"),
        )
    }
}
