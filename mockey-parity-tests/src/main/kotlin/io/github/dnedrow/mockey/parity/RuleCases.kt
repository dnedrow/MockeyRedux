package io.github.dnedrow.mockey.parity

/**
 * Request-rule and inspection cases recorded from legacy Mockey. Fixtures live in
 * src/test/resources/parity-fixtures.
 *
 * A `rule-` case sends one request to a dynamic-mode service whose scenarios carry rule sets. The response
 * names the scenario whose rule set was satisfied, or is the service's error scenario `no match` with status 404.
 * An `inspection-` case concerns a service whose own rule set inspects every request.
 */
object RuleCases {
    private const val KEYS = "/service/keys"
    private const val INPUTS = "/service/inputs"
    private const val TYPES = "/service/types"
    private const val MALFORMED = "/service/malformed"
    private const val VALUES = "/service/values"
    private const val INSPECTION = "/service/inspection"

    private val form = mapOf("Content-Type" to "application/x-www-form-urlencoded")
    private val text = mapOf("Content-Type" to "text/plain")
    private val latin1 = mapOf("Content-Type" to "text/plain; charset=ISO-8859-1")

    /** The word `caf\u00e9` in ISO-8859-1, in which its last letter is one byte that is not valid UTF-8. */
    private val accentedWordInLatin1 = "caf\u00e9".toByteArray(Charsets.ISO_8859_1)

    /** A request that fails the body rule and both parameter rules of the inspected service and omits `page_id`. */
    private const val FAILING_TARGET = "$INSPECTION/checked?ticker=msft&date=tomorrow"
    private val failingBody = "no amount here".toByteArray()

    val groups: List<ParityCaseGroup> =
        listOf(
            parityCaseGroup("rules-keys") {
                get("rule-key-absent-required", "$KEYS/required")
                get("rule-key-present-required", "$KEYS/required", mapOf("page_id" to "home"))
                get("rule-key-absent-optional", "$KEYS/optional?ticker=goog")
                get("rule-key-optional-mismatch", "$KEYS/optional?ticker=goog&date=tomorrow")
                get("rule-key-optional-match", "$KEYS/optional?ticker=goog&date=11%2F23%2F2026")
                get("rule-key-letter-case", "$KEYS/case", mapOf("X-HTTP-Method-Override" to "PUT"))
                get("rule-key-wildcard-parameter", "$KEYS/any-parameter?a=1&b=888")
                get("rule-key-wildcard-parameter-no-match", "$KEYS/any-parameter?a=1&b=2")
                get("rule-key-wildcard-without-parameters", "$KEYS/any-parameter")
                get("rule-key-wildcard-header", "$KEYS/any-header", mapOf("X-Tier" to "Gold"))
                get("rule-key-partial-wildcard", "$KEYS/partial-wildcard?other=888")
            },
            parityCaseGroup("rules-inputs") {
                case("rule-input-form-parameter", "POST", "$INPUTS/form-any", form, "ticker=GOOG".toByteArray())
                case("rule-input-form-parameter-keyed", "POST", "$INPUTS/form-keyed", form, "ticker=FB".toByteArray())
                get("rule-input-header-host", "$INPUTS/header-host")
                get("rule-input-header-cookie", "$INPUTS/header-cookie", mapOf("Cookie" to "session=abc"))
                post("rule-input-header-content-length", "$INPUTS/header-content-length", "7 bytes")
                get("rule-input-header-accept-encoding", "$INPUTS/header-accept-encoding", mapOf("Accept-Encoding" to "gzip"))
                get("rule-input-header-if-none-match", "$INPUTS/header-if-none-match", mapOf("If-None-Match" to "\"v1\""))
                get(
                    "rule-input-header-if-modified-since",
                    "$INPUTS/header-if-modified-since",
                    mapOf("If-Modified-Since" to "Mon, 23 Nov 2026 00:00:00 GMT"),
                )
                get("rule-input-body-absent-required", "$INPUTS/body-required")
                post("rule-input-body-present-required", "$INPUTS/body-required", "order 66")
                get("rule-input-body-absent-optional", "$INPUTS/body-optional")
                post("rule-input-body-present-optional-mismatch", "$INPUTS/body-optional", "no digits")
                get("rule-input-url-whole", "$INPUTS/url?x=1")
                get("rule-input-url-without-query", "$INPUTS/url-query?x=1")
            },
            parityCaseGroup("rules-types") {
                get("rule-type-string-empty-argument", "$TYPES/string-empty", mapOf("page_id" to "home"))
                get("rule-type-string-trimmed", "$TYPES/string-trimmed?ticker=xgoogx")
                get("rule-type-regex-found", "$TYPES/regex-required?id=123")
                get("rule-type-regex-not-found", "$TYPES/regex-required?id=12a")
                get("rule-type-letter-case", "$TYPES/letter-case?ticker=goog")
                get("rule-type-unknown", "$TYPES/unknown?count=five")
                get("rule-type-invalid-regex", "$TYPES/invalid-regex?ticker=goog")
                get("rule-type-missing-key", "$TYPES/missing-key", mapOf("X-Tier" to "gold"))
                get("rule-type-missing-argument", "$TYPES/missing-argument?ticker=goog")
                get("rule-set-empty", "$TYPES/empty?ticker=goog")
            },
            parityCaseGroup("rules-malformed") {
                get("rule-malformed-missing-key", "$MALFORMED/missing-key")
                get("rule-malformed-expression", "$MALFORMED/expression?ticker=goog")
                get("rule-malformed-unknown-member", "$MALFORMED/unknown-member")
                get("rule-malformed-group-not-an-array", "$MALFORMED/group-not-an-array")
                get("rule-malformed-rule-not-an-object", "$MALFORMED/rule-not-an-object?ticker=goog")
                get("rule-malformed-not-json", "$MALFORMED/not-json")
                get("rule-number-argument", "$MALFORMED/number-argument?id=1445")
            },
            parityCaseGroup("rules-values") {
                get("rule-key-wildcard-optional-without-parameters", "$VALUES/optional-any")
                get("rule-key-wildcard-optional-match", "$VALUES/optional-any?n=12")
                get("rule-key-two-letter-cases", "$VALUES/two-cases?Ticker=fb&ticker=goog")
                case("rule-input-body-declared-charset", "POST", "$VALUES/body-charset", latin1, accentedWordInLatin1)
                post("rule-input-body-utf8", "$VALUES/body-charset", "caf\u00e9", "text/plain; charset=UTF-8")
                post("rule-input-body-blank", "$VALUES/blank-body", "   ")
            },
            parityCaseGroup("inspection") {
                val failing = request("POST", FAILING_TARGET, text, failingBody)
                val passing =
                    request(
                        "POST",
                        "$INSPECTION/checked?ticker=goog",
                        text + ("page_id" to "home"),
                        """{"amount": 25}""".toByteArray(),
                    )
                case("inspection-response-unaffected", "POST", FAILING_TARGET, text, failingBody)
                get("inspection-inspectors-listed", "/requestinspectors")
                get("inspection-latest-entry", "/conversation/record/latest", before = listOf(failing))
                get(
                    "inspection-history-messages",
                    "/history",
                    before =
                        listOf(
                            failing,
                            passing,
                            request("GET", "$INSPECTION/disabled?ticker=msft"),
                            request("GET", "$INSPECTION/unreadable"),
                            request("GET", "$INSPECTION/disabled?foobarKey=1"),
                        ),
                    snapshot = true,
                )
            },
        )

    val all: Map<String, ParityCase> = casesOf(groups)
}
