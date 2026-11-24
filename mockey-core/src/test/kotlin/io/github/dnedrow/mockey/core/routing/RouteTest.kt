package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RouteTest {
    private val regression = Tags.of("regression")

    private fun service(
        name: String,
        mockUrl: String,
        tags: Tags = Tags.EMPTY,
        realUrls: List<String> = emptyList(),
        scenarios: List<Scenario> = emptyList(),
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, tags = tags, realUrls = realUrls, scenarios = scenarios)

    private fun scenario(
        name: String,
        tags: Tags = Tags.EMPTY,
    ) = Scenario(id = ScenarioId.generate(), name = name, tags = tags)

    private fun route(
        services: List<Service>,
        target: String,
        tagFilter: Tags = Tags.EMPTY,
        forwardUnknownTargets: Boolean = true,
    ): Route = RoutingTable(Definitions(services = services)).route(Target(target), tagFilter, forwardUnknownTargets)

    private fun Route.serviceName(): String? = (this as? Route.ToService)?.service?.name

    @Test
    fun aRequestGoesToTheMostSpecificCandidateWithTheValuesOfItsVariables() {
        val summary = service("A", "api/cid/{customerId}/feature/summary")
        val feature = service("B", "api/cid/{customerId}/feature/{featureId}")

        val route = route(listOf(feature, summary), "api/cid/42/feature/summary")

        assertEquals(Route.ToService(Target("api/cid/42/feature/summary"), summary, mapOf("customerId" to "42")), route)
    }

    @Test
    fun aServiceWithoutATagOfTheFilterIsIgnored() {
        val orders = service("Orders", "orders/{id}", tags = Tags.of("smoke"), scenarios = listOf(scenario("open", Tags.of("nightly"))))

        val route = route(listOf(orders), "orders/7", tagFilter = regression)

        assertContains(assertIs<Route.NotFound>(route).explanation, "orders/7")
    }

    @Test
    fun aTagOfOneScenarioIsEnough() {
        val orders =
            service("Orders", "orders/{id}", scenarios = listOf(scenario("open"), scenario("closed", Tags.of("nightly regression"))))

        assertEquals("Orders", route(listOf(orders), "orders/7", tagFilter = regression).serviceName())
    }

    @Test
    fun aTagOfTheServiceIsEnough() {
        val orders = service("Orders", "orders/{id}", tags = Tags.of("regression smoke"), scenarios = listOf(scenario("open")))

        assertEquals("Orders", route(listOf(orders), "orders/7", tagFilter = regression).serviceName())
    }

    @Test
    fun oneOfTheTagsOfTheFilterIsEnough() {
        val orders = service("Orders", "orders/{id}", tags = Tags.of("smoke"))
        val billing = service("Billing", "billing/{id}", tags = Tags.of("nightly"))
        val filter = Tags.of("regression smoke")

        assertEquals("Orders", route(listOf(orders, billing), "orders/7", tagFilter = filter).serviceName())
        assertIs<Route.NotFound>(route(listOf(orders, billing), "billing/7", tagFilter = filter))
    }

    @Test
    fun aTagOfAnotherServiceDoesNotCount() {
        val orders = service("Orders", "orders/{id}")
        val billing = service("Billing", "billing/{id}", tags = regression, scenarios = listOf(scenario("paid", regression)))

        assertIs<Route.NotFound>(route(listOf(orders, billing), "orders/7", tagFilter = regression))
    }

    @Test
    fun withoutAFilterEveryServiceCanBeSelected() {
        val orders = service("Orders", "orders/{id}")
        val billing = service("Billing", "billing/{id}", tags = regression)

        assertEquals("Orders", route(listOf(orders, billing), "orders/7").serviceName())
        assertEquals("Billing", route(listOf(orders, billing), "billing/7").serviceName())
    }

    @Test
    fun theFilterAppliesBeforeTheMostSpecificCandidateIsSelected() {
        val summary = service("summary", "api/feature/summary")
        val feature = service("any feature", "api/feature/{featureId}", tags = regression)
        val services = listOf(summary, feature)

        assertEquals("summary", route(services, "api/feature/summary").serviceName())
        assertEquals("any feature", route(services, "api/feature/summary", tagFilter = regression).serviceName())
    }

    @Test
    fun anUnknownAbsoluteTargetIsForwardedAndGetsAService() {
        val orders = service("Orders", "orders/{id}", realUrls = listOf("http://api.example.com/orders/{id}"))
        val target = "http://api.example.com/v1/customers"

        assertEquals(Route.Forward(Target(target), registers = true), route(listOf(orders), target))
        assertEquals(Route.Forward(Target(target), registers = true), route(emptyList(), target))
    }

    @Test
    fun aForwardedRequestKeepsItsTargetAndItsQueryStringAsTheClientWroteThem() {
        val uri = assertNotNull(MockRequestUri.parse("/service/http://api.example.com/v1/customers?active=true&name=jane%20doe"))

        val route = RoutingTable(Definitions.EMPTY).route(uri.target, Tags.EMPTY, forwardUnknownTargets = true)

        assertEquals("http://api.example.com/v1/customers", assertIs<Route.Forward>(route).target.text)
        assertEquals("active=true&name=jane%20doe", uri.query)
    }

    @Test
    fun anUnknownAbsoluteTargetIsNotForwardedWhileHandlingIsDisabled() {
        val target = "http://api.example.com/v1/customers"

        val route = route(emptyList(), target, forwardUnknownTargets = false)

        val explanation = "No service matches the target 'http://api.example.com/v1/customers'. Unknown targets are not forwarded."
        assertEquals(Route.NotFound(Target(target), explanation), route)
    }

    @Test
    fun disabledHandlingOfUnknownTargetsLeavesKnownTargetsAlone() {
        val customers = service("Customers", "http://api.example.com/v1/customers")
        val orders = service("Orders", "orders/{id}")
        val services = listOf(customers, orders)

        assertEquals("Customers", route(services, "http://api.example.com/v1/customers", forwardUnknownTargets = false).serviceName())
        assertEquals("Orders", route(services, "orders/7", forwardUnknownTargets = false).serviceName())
    }

    @Test
    fun anAbsoluteTargetThatTheFilterHidesIsForwardedAndGetsNoSecondService() {
        val customers = service("Customers", "http://api.example.com/v1/customers", tags = Tags.of("smoke"))
        val target = "http://api.example.com/v1/customers"

        assertEquals(Route.Forward(Target(target), registers = false), route(listOf(customers), target, tagFilter = regression))
    }

    @Test
    fun anAbsoluteTargetThatIsHiddenBehindARealUrlOrAVariableGetsNoSecondServiceEither() {
        val orders = service("Orders", "orders/{id}", realUrls = listOf("http://api.example.com/orders/{id}"))
        val anything = service("Anything", "http://api.example.com/{resource}/{id}")

        val throughRealUrl = route(listOf(orders), "http://api.example.com/orders/7", tagFilter = regression)
        val throughVariables = route(listOf(anything), "http://api.example.com/customers/7", tagFilter = regression)

        assertEquals(Route.Forward(Target("http://api.example.com/orders/7"), registers = false), throughRealUrl)
        assertEquals(Route.Forward(Target("http://api.example.com/customers/7"), registers = false), throughVariables)
    }

    @Test
    fun aHiddenAbsoluteTargetIsNotForwardedWhileHandlingIsDisabled() {
        val customers = service("Customers", "http://api.example.com/v1/customers")
        val target = "http://api.example.com/v1/customers"

        assertIs<Route.NotFound>(route(listOf(customers), target, tagFilter = regression, forwardUnknownTargets = false))
    }

    @Test
    fun anUnknownRelativeTargetIsNotFound() {
        val orders = service("Orders", "orders/{id}")

        val route = route(listOf(orders), "foo/bar")

        assertEquals(Route.NotFound(Target("foo/bar"), "No service matches the target 'foo/bar'."), route)
    }

    @Test
    fun aRelativeTargetThatNamesAHostOrHoldsAUrlIsNotForwarded() {
        val asHost = route(emptyList(), "127.0.0.1:18091/stub/echo")
        val embedded = route(emptyList(), "prefix/http://127.0.0.1:18091/stub/echo")

        assertContains(assertIs<Route.NotFound>(asHost).explanation, "127.0.0.1:18091/stub/echo")
        assertContains(assertIs<Route.NotFound>(embedded).explanation, "prefix/http://127.0.0.1:18091/stub/echo")
    }

    @Test
    fun theExplanationNamesTheTargetAsTheClientWroteIt() {
        val route = route(emptyList(), "Customer/a%20b/")

        assertEquals("No service matches the target 'Customer/a%20b/'.", assertIs<Route.NotFound>(route).explanation)
    }

    /** The services of the parity fixture `routing-tagged` under its tag filter `keep`, and the targets of its recordings. */
    @Test
    fun theTargetsOfTheTagFilterRecordingsTakeTheRoutesThatTheRecordingsState() {
        val keep = Tags.of("keep")
        val services =
            listOf(
                service("visible", "visible", tags = keep, scenarios = listOf(scenario("answer"))),
                service("scenario tagged", "scenario-tagged", scenarios = listOf(scenario("answer", keep))),
                service("hidden", "hidden.invalid/tagged", tags = Tags.of("drop"), scenarios = listOf(scenario("answer"))),
                service("untagged", "untagged.invalid/plain", scenarios = listOf(scenario("answer"))),
                service(
                    "hidden absolute",
                    "http://127.0.0.1:18091/tagged/hidden",
                    tags = Tags.of("drop"),
                    scenarios = listOf(scenario("answer")),
                ),
            )

        assertEquals("visible", route(services, "visible", tagFilter = keep).serviceName())
        assertEquals("scenario tagged", route(services, "scenario-tagged", tagFilter = keep).serviceName())
        assertIs<Route.NotFound>(route(services, "hidden.invalid/tagged", tagFilter = keep))
        assertIs<Route.NotFound>(route(services, "untagged.invalid/plain", tagFilter = keep))
        assertEquals(
            Route.Forward(Target("http://127.0.0.1:18091/tagged/hidden"), registers = false),
            route(services, "http://127.0.0.1:18091/tagged/hidden", tagFilter = keep),
        )
    }
}
