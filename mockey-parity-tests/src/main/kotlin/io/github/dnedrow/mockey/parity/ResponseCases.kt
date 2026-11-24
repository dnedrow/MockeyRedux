package io.github.dnedrow.mockey.parity

/**
 * Static and dynamic response cases recorded from legacy Mockey.
 * Fixtures live in src/test/resources/parity-fixtures.
 */
object ResponseCases {
    val groups: List<ParityCaseGroup> =
        listOf(
            parityCaseGroup("responses-static") {
                get("response-static-default", "/service/static/plan")
                post("response-static-any-request", "/service/static/plan?plan=silver", "silver")
                get("response-static-ignores-criteria", "/service/static/criteria?ticker=goog")
                get("response-static-dangling-default", "/service/static/dangling")
            },
            parityCaseGroup("responses-status-headers") {
                get("response-status-201-headers", "/service/status/created")
                get("response-status-302", "/service/status/found")
                get("response-status-503", "/service/status/unavailable")
                get("response-headers-multiple", "/service/headers/multiple")
                get("response-headers-repeated", "/service/headers/repeated")
                get("response-headers-default", "/service/headers/default")
                get("response-headers-malformed", "/service/headers/malformed")
            },
            parityCaseGroup("responses-body-charset") {
                get("response-body-trailing-newline", "/service/body/exact")
                get("response-body-empty", "/service/body/empty")
                get("response-body-whitespace", "/service/body/whitespace")
                get("response-body-markup", "/service/body/markup")
                get("response-charset-none", "/service/charset/none")
                get("response-charset-utf8", "/service/charset/utf8")
                get("response-charset-latin1", "/service/charset/latin1")
                get("response-charset-json", "/service/charset/json")
                get("response-charset-json-utf8", "/service/charset/json-utf8")
                get("response-charset-other-header", "/service/charset/other-header")
            },
            parityCaseGroup("responses-text") {
                get("response-text-case-insensitive", "/service/text/quotes?ticker=goog")
                get("response-text-query-decoded", "/service/text/quotes?name=jane%20doe")
                post("response-text-body", "/service/text/quotes", "<order><tier>gold</tier></order>", "application/xml")
                get("response-text-params-joined", "/service/text/quotes?a=1&b=2")
                get("response-text-params-reordered", "/service/text/quotes?b=2&a=1")
                post("response-text-body-plus", "/service/text/quotes", "first+last")
                post("response-text-body-percent", "/service/text/quotes", "discount=100% off")
                get("response-text-no-match", "/service/text/quotes?ticker=msft")
                get("response-text-literal-path", "/service/text/orders/list")
                get("response-text-template-value", "/service/text-customer/%7B123%7D")
            },
            parityCaseGroup("responses-parameters") {
                get("response-text-param-empty-value", "/service/parameters/evaluate?debug=")
                get("response-text-param-repeated", "/service/parameters/evaluate?a=1&a=2")
                get("response-text-param-without-value", "/service/parameters/evaluate?wsdl")
                get("response-text-param-encoded-plus", "/service/parameters/evaluate?phone=%2B15551234")
                get("response-text-param-empty-piece", "/service/parameters/evaluate?a=1&&b=2")
                post("response-text-param-then-body", "/service/parameters/evaluate?q=abc", "def")
                post("response-text-body-escape", "/service/parameters/evaluate", "jane%20doe")
            },
            parityCaseGroup("responses-rules") {
                get("response-rules-parameter-satisfied", "/service/rules/parameter?ticker=GOOG")
                get("response-rules-parameter-unsatisfied", "/service/rules/parameter?ticker=FB")
                get("response-rules-header-satisfied", "/service/rules/header", mapOf("X-Tier" to "Gold"))
                get("response-rules-header-unsatisfied", "/service/rules/header", mapOf("X-Tier" to "silver"))
                post("response-rules-body-satisfied", "/service/rules/body", """{"username":"jane"}""", "application/json")
                post("response-rules-body-unsatisfied", "/service/rules/body", """{"user":"jane"}""", "application/json")
                get("response-rules-url-satisfied", "/service/rules-url/%7Bemea%7D")
                get("response-rules-url-unsatisfied", "/service/rules-url/%7Bapac%7D")
                get("response-rules-score-highest", "/service/rules/score?ticker=goog&exchange=nasdaq")
                get("response-rules-score-partial", "/service/rules/score?ticker=goog&exchange=nyse")
                post("response-rules-groups-or", "/service/rules/groups?ticker=fb", "username=jane")
                get("response-rules-groups-none", "/service/rules/groups?ticker=fb")
                get("response-rules-malformed", "/service/rules/malformed?ticker=goog")
            },
            parityCaseGroup("responses-methods") {
                get("response-method-get", "/service/methods/orders?q=order")
                case("response-method-post", "POST", "/service/methods/orders?q=order")
                case("response-method-not-eligible", "DELETE", "/service/methods/orders?q=order")
                case("response-method-wildcard", "DELETE", "/service/methods/orders?q=wild")
                case("response-method-unrestricted", "PUT", "/service/methods/orders?q=open")
                case("response-method-case-insensitive", "POST", "/service/methods/orders?q=lower")
            },
            parityCaseGroup("responses-ties") {
                get("response-score-longer-criterion", "/service/ties/longer?code=abc123")
                get("response-tie-name-order", "/service/ties/name?q=redbox")
                get("response-tie-creation-order", "/service/ties/created?q=redbox")
                get("response-tie-id-order", "/service/ties/id-order?q=redbox")
                get("response-tie-text-and-rule", "/service/ties/text-and-rule?q=red")
                get("response-score-text-over-rule", "/service/ties/text-over-rule?q=red")
            },
            parityCaseGroup("responses-fallback") {
                get("response-fallback-service-error", "/service/fallback/service-error?q=other")
                get("response-fallback-builtin", "/service/fallback/builtin?q=other")
                get("response-fallback-no-criterion", "/service/fallback/no-criterion?q=anything")
            },
            parityCaseGroup("responses-universal-error") {
                get("response-fallback-service-error-first", "/service/fallback/service-first?q=other")
                get("response-fallback-universal-error", "/service/fallback/universal-second?q=other")
            },
            parityCaseGroup("responses-delays") {
                get("response-delay-service", "/service/delay/service", timing = ParityTiming(atLeastMillis = 300))
                get("response-delay-scenario-overrides", "/service/delay/scenario-longer", timing = ParityTiming(atLeastMillis = 500))
                get(
                    "response-delay-scenario-shorter",
                    "/service/delay/scenario-shorter",
                    timing = ParityTiming(atLeastMillis = 200, lessThanMillis = 1000),
                )
                get("response-delay-dynamic-match", "/service/delay/dynamic?q=slow", timing = ParityTiming(atLeastMillis = 400))
                get("response-delay-error-scenario", "/service/delay/dynamic?q=other", timing = ParityTiming(lessThanMillis = 1000))
                get("response-delay-fallback-service", "/service/delay/fallback?q=other", timing = ParityTiming(atLeastMillis = 300))
            },
            parityCaseGroup("responses-builtin-delay") {
                get("response-delay-builtin", "/service/delay/builtin?q=other", timing = ParityTiming(atLeastMillis = 300))
            },
        )

    val all: Map<String, ParityCase> = casesOf(groups)
}
