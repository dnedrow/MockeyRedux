package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RoutingTableTest {
    private fun service(
        name: String,
        mockUrl: String,
        vararg realUrls: String,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, realUrls = realUrls.toList())

    private fun table(vararg services: Service) = RoutingTable(Definitions(services = services.toList()))

    private fun RoutingTable.candidateNames(target: String): List<String> = candidates(Target(target)).map { it.service.name }

    private fun RoutingTable.selected(target: String): Candidate? = candidates(Target(target)).mostSpecific()

    private fun RoutingTable.selectedName(target: String): String? = selected(target)?.service?.name

    @Test
    fun aServiceIsACandidateThroughItsMockUrlPattern() {
        val customers = service("Customers", "customer/{id}")

        val candidate = table(customers).candidates(Target("customer/7")).single()

        assertEquals(customers, candidate.service)
        assertEquals(mapOf("id" to "7"), candidate.variables)
    }

    @Test
    fun aServiceIsACandidateThroughARealUrl() {
        val customers =
            service("Customers", "customer/{id}", "http://qa.example.com/customers/{id}", "http://api.example.com/customers/{id}")

        val candidate = table(customers).candidates(Target("http://api.example.com/customers/7")).single()

        assertEquals(customers, candidate.service)
        assertEquals(mapOf("id" to "7"), candidate.variables)
    }

    @Test
    fun aRealUrlWithoutAVariableLeadsToItsService() {
        val holder = service("real url holder", "real/holder", "http://127.0.0.1:18091/real/target")

        assertEquals(listOf("real url holder"), table(holder).candidateNames("http://127.0.0.1:18091/real/target"))
    }

    @Test
    fun aTargetThatNoPatternMatchesHasNoCandidate() {
        val table = table(service("Customers", "customer/{id}", "http://api.example.com/customers/{id}"))

        assertEquals(emptyList(), table.candidateNames("orders/7"))
        assertEquals(emptyList(), table.candidateNames("customer/7/orders"))
        assertEquals(emptyList(), table.candidateNames("http://api.example.com/customers"))
        assertNull(table.selected("orders/7"))
    }

    @Test
    fun noServicesGiveNoCandidate() {
        assertEquals(emptyList(), RoutingTable(Definitions.EMPTY).candidateNames("customer/7"))
    }

    @Test
    fun candidatesAreInTheOrderInWhichTheirServicesWereCreated() {
        val table =
            table(
                service("third segment", "a/b/{c}"),
                service("other", "x/y"),
                service("second segment", "a/{b}/c"),
                service("all", "a/b/c"),
            )

        assertEquals(listOf("third segment", "second segment", "all"), table.candidateNames("a/b/c"))
    }

    @Test
    fun aTargetIsComparedInItsNormalizedForm() {
        val table = table(service("Customers", "customer/{id}", "http://api.example.com/customers/{id}"))

        assertEquals(mapOf("id" to "Ab 12"), table.selected("Customer/Ab%2012/")?.variables)
        assertEquals(mapOf("id" to "7"), table.selected("HTTP://API.example.com:80/customers/7")?.variables)
    }

    @Test
    fun aLiteralPartOfThePathBeatsAVariable() {
        val summary = service("A", "api/cid/{customerId}/feature/summary")
        val feature = service("B", "api/cid/{customerId}/feature/{featureId}")

        assertEquals("A", table(summary, feature).selectedName("api/cid/42/feature/summary"))
        assertEquals("A", table(feature, summary).selectedName("api/cid/42/feature/summary"))
    }

    @Test
    fun theVariablePatternStillServesOtherValues() {
        val summary = service("A", "api/cid/{customerId}/feature/summary")
        val feature = service("B", "api/cid/{customerId}/feature/{featureId}")

        val selected = table(summary, feature).selected("api/cid/42/feature/details")

        assertEquals("B", selected?.service?.name)
        assertEquals(mapOf("customerId" to "42", "featureId" to "details"), selected?.variables)
    }

    /** The three services of upstream issue 109: a request for the first one was answered by one of the others. */
    @Test
    fun theServicesOfUpstreamIssue109EachAnswerTheirOwnUrl() {
        val table =
            table(
                service("Service#1", "api/v1/cxtid/{contextId}/cid/{customerId}/feature/somefunctionality"),
                service("Service#2", "api/v1/cxtid/{contextId}/cid/{customerId}/feature/{some-id}"),
                service("Service#3", "api/v1/cxtid/{contextId}/cid/{customerId}/feature/somefunctionality/subfunctionality"),
            )

        assertEquals("Service#1", table.selectedName("api/v1/cxtid/11/cid/22/feature/somefunctionality"))
        assertEquals("Service#2", table.selectedName("api/v1/cxtid/11/cid/22/feature/33"))
        assertEquals("Service#3", table.selectedName("api/v1/cxtid/11/cid/22/feature/somefunctionality/subfunctionality"))
        assertEquals(listOf("Service#1", "Service#2"), table.candidateNames("api/v1/cxtid/11/cid/22/feature/somefunctionality"))
        assertEquals(listOf("Service#3"), table.candidateNames("api/v1/cxtid/11/cid/22/feature/somefunctionality/subfunctionality"))
        assertEquals(
            mapOf("contextId" to "11", "customerId" to "22", "some-id" to "33"),
            table.selected("api/v1/cxtid/11/cid/22/feature/33")?.variables,
        )
    }

    @Test
    fun fewerVariablesBeatMoreLiteralCharacters() {
        val long = service("two variables", "inventory/warehouses/{warehouse}/items/{item}")
        val short = service("one variable", "{area}/warehouses/7/items/9")

        assertEquals("one variable", table(long, short).selectedName("inventory/warehouses/7/items/9"))
    }

    @Test
    fun amongPatternsWithAsManyVariablesTheOneWithMoreLiteralCharactersWins() {
        val item = service("item of orders", "orders/{id}")
        val seventh = service("seventh of anything", "{resource}/7")

        assertEquals("item of orders", table(item, seventh).selectedName("orders/7"))
        assertEquals("item of orders", table(seventh, item).selectedName("orders/7"))
    }

    @Test
    fun amongPatternsThatAreEquallySpecificTheServiceThatWasCreatedFirstWins() {
        val first = service("first", "{resource}/7")
        val second = service("second", "{kind}/7")

        assertEquals("first", table(first, second).selectedName("orders/7"))
        assertEquals("second", table(second, first).selectedName("orders/7"))
    }

    @Test
    fun servicesWithTheSameMockUrlAreBothCandidatesAndTheFirstWins() {
        val table = table(service("dup first", "dup"), service("dup second", "dup"))

        assertEquals(listOf("dup first", "dup second"), table.candidateNames("dup"))
        assertEquals("dup first", table.selectedName("dup"))
    }

    @Test
    fun aServiceIsACandidateOnceThroughItsMostSpecificPattern() {
        val customers = service("Customers", "http://api.example.com/{resource}/{id}", "http://api.example.com/customers/{id}")

        val candidate = table(customers).candidates(Target("http://api.example.com/customers/7")).single()

        assertEquals(mapOf("id" to "7"), candidate.variables)
        assertEquals(1, candidate.variableCount)
        assertEquals("http://api.example.com/customers/".length, candidate.literalLength)
    }

    @Test
    fun theMostSpecificPatternOfAServiceCanBeItsMockUrl() {
        val customers = service("Customers", "http://api.example.com/customers/{id}", "http://api.example.com/{resource}/{id}")

        val candidate = table(customers).candidates(Target("http://api.example.com/customers/7")).single()

        assertEquals(mapOf("id" to "7"), candidate.variables)
    }

    @Test
    fun aServiceCompetesWithThePatternThatMatchesAndNotWithItsOtherPatterns() {
        val catchAll = service("catch-all", "http://api.example.com/{resource}/{id}", "http://api.example.com/customers/1")
        val customers = service("Customers", "customer/{id}", "http://api.example.com/customers/{id}")

        assertEquals("Customers", table(catchAll, customers).selectedName("http://api.example.com/customers/7"))
        assertEquals("catch-all", table(catchAll, customers).selectedName("http://api.example.com/customers/1"))
    }

    @Test
    fun aMockUrlAndARealUrlThatAreTheSameGiveOneCandidate() {
        val backend = service("proxy root", "http://127.0.0.1:18091/backend", "http://127.0.0.1:18091/backend")

        assertEquals(listOf("proxy root"), table(backend).candidateNames("http://127.0.0.1:18091/backend"))
    }

    @Test
    fun theWholeTargetDecidesBetweenNestedPaths() {
        val table = table(service("nested short", "nest"), service("nested long", "nest/deep"))

        assertEquals(listOf("nested long"), table.candidateNames("nest/deep"))
        assertEquals(listOf("nested short"), table.candidateNames("nest"))
    }

    @Test
    fun aRealUrlWithAnEscapeLeadsToItsService() {
        val search = service("Search", "search", "http://api.example.com/search/hello%20world")

        assertEquals(listOf("Search"), table(search).candidateNames("http://api.example.com/search/hello%20world"))
    }

    @Test
    fun aServiceWithoutAMockUrlIsReachedThroughItsRealUrlsAndByTheEmptyTarget() {
        val table = table(service("no mock url", "", "http://api.example.com/orders"), service("Customers", "customer/{id}"))

        assertEquals(listOf("no mock url"), table.candidateNames("http://api.example.com/orders"))
        assertEquals(listOf("no mock url"), table.candidateNames(""))
        assertEquals(listOf("Customers"), table.candidateNames("customer/7"))
    }

    /** The services of the parity fixture `routing` and the targets of its recordings, with the service that answers. */
    @Test
    fun theTargetsOfTheRoutingRecordingsReachTheServicesThatTheRecordingsState() {
        val upstream = "http://127.0.0.1:18091"
        val table =
            table(
                service("static hello", "hello"),
                service("template customer", "$upstream/customer/{customerId}"),
                service("dup first", "dup"),
                service("nested short", "nest"),
                service("nested long", "nest/deep"),
                service("proxy root", "$upstream/backend", "$upstream/backend"),
                service("proxy template", "$upstream/tpl/{id}", upstream),
                service("real url holder", "real/holder", "$upstream/real/target"),
                service("real url template holder", "real/template-holder", "$upstream/real/{id}/item"),
            )
        val selected =
            listOf(
                "hello",
                "$upstream/hello",
                "$upstream/customer/%7Bx%7D",
                "$upstream/customer/789",
                "dup",
                "nest/deep",
                "$upstream/backend",
                "$upstream/tpl/%7Babc%7D",
                "$upstream/real/target",
                "$upstream/real/%7B7%7D/item",
                "$upstream/does/not/exist",
                "unknown-relative.invalid/bar",
                "127.0.0.1:18091/stub/echo",
                "prefix/$upstream/stub/echo",
            ).map { table.selectedName(it) }

        assertEquals(
            listOf(
                "static hello",
                null,
                "template customer",
                "template customer",
                "dup first",
                "nested long",
                "proxy root",
                "proxy template",
                "real url holder",
                "real url template holder",
                null,
                null,
                null,
                null,
            ),
            selected,
        )
        assertEquals(mapOf("customerId" to "{x}"), table.selected("$upstream/customer/%7Bx%7D")?.variables)
    }

    /** The services of the parity fixture `routing-normalization` and the targets of its recordings. */
    @Test
    fun theTargetsOfTheNormalizationRecordingsReachTheServicesThatTheRecordingsState() {
        val table =
            table(
                service("plain", "norm.invalid/plain"),
                service("space", "norm.invalid/a b"),
                service("plus sign", "norm.invalid/a+b"),
                service("slash at the end", "norm.invalid/slash/"),
                service("escape in the url", "norm.invalid/a%20c"),
                service("default port", "http://norm.invalid:80/port"),
                service("secure", "https://norm.invalid/secure"),
            )
        val selected =
            listOf(
                "NORM.INVALID/Plain",
                "norm.invalid/plain/",
                "/norm.invalid/plain",
                "norm.invalid/slash",
                "norm.invalid%2Fplain",
                "norm.invalid/a%20b",
                "norm.invalid/a+b",
                "norm.invalid/a%2Bb",
                "norm.invalid/a%20c",
                "http://norm.invalid/port",
                "https://norm.invalid:443/secure",
                "HTTP://norm.invalid/port",
            ).map { table.selectedName(it) }

        assertEquals(
            listOf(
                "plain",
                "plain",
                "plain",
                "slash at the end",
                "plain",
                "space",
                "plus sign",
                "plus sign",
                "escape in the url",
                "default port",
                "secure",
                "default port",
            ),
            selected,
        )
    }

    @Test
    fun aTableKeepsTheDefinitionsThatItWasMadeFrom() {
        val definitions = Definitions(services = listOf(service("Customers", "customer/{id}")))

        assertSame(definitions, RoutingTable(definitions).definitions)
    }

    @Test
    fun theMostSpecificOfNoCandidatesIsNone() {
        assertNull(emptyList<Candidate>().mostSpecific())
    }
}
