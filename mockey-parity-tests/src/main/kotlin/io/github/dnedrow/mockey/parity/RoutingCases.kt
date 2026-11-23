package io.github.dnedrow.mockey.parity

/** Routing cases recorded from legacy Mockey. Fixtures live in src/test/resources/parity-fixtures. */
object RoutingCases {
    const val UPSTREAM_PORT = 18091

    /** Fixture file name (without .xml) to the legacy startup arguments that go with it. */
    val fixtureGroups: Map<String, List<String>> =
        mapOf(
            "routing" to emptyList(),
            "routing-tagged" to listOf("-F", "keep"),
        )

    private fun case(
        name: String,
        fixture: String,
        target: String,
        method: String = "GET",
    ) = name to
        ParityCase(
            label = "same",
            fixtureXml = "parity-fixtures/$fixture.xml",
            request = ParityRequest(method, target),
        )

    val all: Map<String, ParityCase> =
        listOf(
            case("routing-relative-target", "routing", "/service/hello"),
            case("routing-absolute-target", "routing", "/service/http://127.0.0.1:18091/hello"),
            case("routing-url-template-123", "routing", "/service/http://127.0.0.1:18091/customer/%7Bx%7D?id=123"),
            case("routing-url-template-456", "routing", "/service/http://127.0.0.1:18091/customer/%7Bx%7D?id=456"),
            case("routing-url-template-nomatch", "routing", "/service/http://127.0.0.1:18091/customer/789"),
            case("routing-competing-services", "routing", "/service/dup"),
            case("routing-nested-prefix", "routing", "/service/nest/deep"),
            case("routing-real-url", "routing", "/service/http://127.0.0.1:18091/backend"),
            case("routing-real-url-template", "routing", "/service/http://127.0.0.1:18091/tpl/%7Babc%7D"),
            case("routing-unknown-target", "routing", "/service/http://127.0.0.1:18091/does/not/exist"),
            case("routing-tag-visible", "routing-tagged", "/service/visible"),
            case("routing-tag-hidden", "routing-tagged", "/service/hidden"),
            case("routing-tag-untagged", "routing-tagged", "/service/untagged"),
        ).toMap()
}
