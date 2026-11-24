package io.github.dnedrow.mockey.parity

/**
 * Routing cases recorded from legacy Mockey: how a target is read, normalized, and matched, which service
 * answers, what the tag filter hides, what becomes of an unknown target, and how twisting rewrites a target.
 * Fixtures live in src/test/resources/parity-fixtures. The stub routes are described in [StubRoutes].
 */
object RoutingCases {
    const val UPSTREAM_PORT = 18091

    /** The stub upstream as a host and port, and as a mock endpoint target. */
    private const val UPSTREAM_HOST = "127.0.0.1:$UPSTREAM_PORT"
    private const val UPSTREAM = "/service/http://$UPSTREAM_HOST"

    private const val DEFINITIONS = "/definitions"

    val groups: List<ParityCaseGroup> =
        listOf(
            parityCaseGroup("routing") {
                get("routing-relative-target", "/service/hello")
                get("routing-absolute-target", "$UPSTREAM/hello")
                get("routing-url-template-123", "$UPSTREAM/customer/%7Bx%7D?id=123")
                get("routing-url-template-456", "$UPSTREAM/customer/%7Bx%7D?id=456")
                get("routing-url-template-nomatch", "$UPSTREAM/customer/789")
                get("routing-competing-services", "/service/dup")
                get("routing-nested-prefix", "/service/nest/deep")
                get("routing-real-url", "$UPSTREAM/backend")
                get("routing-real-url-template", "$UPSTREAM/tpl/%7Babc%7D")
                get("routing-real-url-of-relative-service", "$UPSTREAM/real/target")
                get("routing-real-url-with-variable", "$UPSTREAM/real/%7B7%7D/item")
                get("routing-unknown-target", "$UPSTREAM/does/not/exist")
                get("routing-unknown-target-registered", DEFINITIONS, before = step("$UPSTREAM/does/not/exist?active=true"))
                get("routing-unknown-relative-target", "/service/unknown-relative.invalid/bar")
                get("routing-relative-target-as-host", "/service/$UPSTREAM_HOST/stub/echo")
                get("routing-embedded-absolute-target", "/service/prefix/http://$UPSTREAM_HOST/stub/echo")
            },
            parityCaseGroup("routing-normalization") {
                get("routing-letter-case", "/service/NORM.INVALID/Plain")
                get("routing-trailing-slash", "/service/norm.invalid/plain/")
                get("routing-leading-slash", "/service//norm.invalid/plain")
                get("routing-url-with-trailing-slash", "/service/norm.invalid/slash")
                get("routing-encoded-slash", "/service/norm.invalid%2Fplain")
                get("routing-encoded-space", "/service/norm.invalid/a%20b")
                get("routing-plus-sign", "/service/norm.invalid/a+b")
                get("routing-encoded-plus-sign", "/service/norm.invalid/a%2Bb")
                get("routing-escape-in-url", "/service/norm.invalid/a%20c")
                get("routing-default-port-omitted", "/service/http://norm.invalid/port")
                get("routing-default-port-given", "/service/https://norm.invalid:443/secure")
                get("routing-scheme-letter-case", "/service/HTTP://norm.invalid/port")
                get(
                    "routing-unknown-target-with-escape-registered",
                    DEFINITIONS,
                    before = step("$UPSTREAM/hello%20world") + step("$UPSTREAM/hello%20world"),
                )
            },
            parityCaseGroup("routing-tagged", tagFilter = "keep") {
                get("routing-tag-visible", "/service/visible")
                get("routing-tag-scenario", "/service/scenario-tagged")
                get("routing-tag-hidden", "/service/hidden.invalid/tagged")
                get("routing-tag-untagged", "/service/untagged.invalid/plain")
                get("routing-tag-hidden-absolute", "$UPSTREAM/tagged/hidden")
                get("routing-tag-hidden-absolute-registered", DEFINITIONS, before = step("$UPSTREAM/tagged/hidden"))
            },
            parityCaseGroup("routing-twist", enabledTwist = EnabledTwist("to stub", legacyId = 1)) {
                get("routing-twist-applied", "/service/twist/a.b")
                get("routing-twist-regular-expression", "/service/twist/a.b/axb")
                get("routing-twist-first-pair-only", "/service/twist/a.b/one")
                get("routing-twist-second-pair", "/service/plain/one")
                get("routing-twist-no-pair", "/service/plain/zero")
                get("routing-twist-forwarded", "/service/http://qa1.invalid/stub/echo?x=1")
            },
            parityCaseGroup("routing-twist-document") {
                get("routing-twist-document-enabled", "/service/twist/a.b")
            },
        )

    val all: Map<String, ParityCase> = casesOf(groups)
}
