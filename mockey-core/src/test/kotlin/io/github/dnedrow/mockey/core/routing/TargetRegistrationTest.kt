package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.definitions.changed
import io.github.dnedrow.mockey.core.engine.ChangeObserver
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.EngineState
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TargetRegistrationTest {
    private val regression = Tags.of("regression")
    private val customers = Target("http://api.example.com/v1/customers")

    private fun service(
        name: String,
        mockUrl: String,
        tags: Tags = Tags.EMPTY,
        realUrls: List<String> = emptyList(),
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl, tags = tags, realUrls = realUrls)

    private fun Definitions.registered(
        target: Target,
        tagFilter: Tags = Tags.EMPTY,
    ): ChangeResult.Changed<Route> = changed(TargetRegistration.register(target, tagFilter))

    private fun Definitions.route(
        target: String,
        tagFilter: Tags = Tags.EMPTY,
    ): Route = RoutingTable(this).route(Target(target), tagFilter, forwardUnknownTargets = true)

    @Test
    fun aServiceIsRegisteredForAnUnknownAbsoluteTarget() {
        val orders = service("Orders", "orders/{id}")

        val registered = Definitions(services = listOf(orders)).registered(customers)

        val service = registered.definitions.services.last()
        assertEquals(listOf(orders, service), registered.definitions.services)
        assertEquals("customers (auto generated)", service.name)
        assertEquals("http://api.example.com/v1/customers", service.mockUrl)
        assertEquals(listOf("http://api.example.com/v1/customers"), service.realUrls)
        assertEquals(ResponseMode.PROXY, service.responseMode)
        assertEquals(emptyList(), service.scenarios)
        assertEquals(Tags.EMPTY, service.tags)
    }

    @Test
    fun theQueryStringOfTheRequestIsNotPartOfTheRegisteredService() {
        val uri = assertNotNull(MockRequestUri.parse("/service/http://api.example.com/v1/customers?active=true"))

        val service =
            Definitions.EMPTY
                .registered(uri.target)
                .definitions.services
                .single()

        assertEquals("customers (auto generated)", service.name)
        assertEquals("http://api.example.com/v1/customers", service.mockUrl)
        assertEquals(listOf("http://api.example.com/v1/customers"), service.realUrls)
    }

    @Test
    fun theRequestGoesToTheServiceThatWasRegisteredForIt() {
        val registered = Definitions.EMPTY.registered(customers)

        assertEquals(Route.ToService(customers, registered.definitions.services.single(), emptyMap()), registered.value)
    }

    @Test
    fun theRegisteredServiceCarriesTheTagsOfTheFilter() {
        val filter = Tags.of("regression smoke")

        val registered = Definitions.EMPTY.registered(customers, filter)

        assertEquals(
            filter,
            registered.definitions.services
                .single()
                .tags,
        )
        assertIs<Route.ToService>(registered.definitions.route(customers.text, filter))
    }

    @Test
    fun laterRequestsForTheTargetGoToTheRegisteredService() {
        val definitions = Definitions.EMPTY.registered(customers).definitions
        val service = definitions.services.single()
        val sameUrls =
            listOf("http://api.example.com/v1/customers", "HTTP://API.example.com:80/v1/Customers/", "http://api.example.com/v1/customers/")

        assertEquals(List(sameUrls.size) { service }, sameUrls.map { (definitions.route(it) as? Route.ToService)?.service })
        assertEquals(
            Route.Forward(Target("http://api.example.com/v1/orders"), registers = true),
            definitions.route("http://api.example.com/v1/orders"),
        )
    }

    @Test
    fun registeringATargetAgainAddsNoSecondService() {
        val first = Definitions.EMPTY.registered(customers)

        val second = first.definitions.registered(customers)

        assertSame(first.definitions, second.definitions)
        assertEquals(first.value, second.value)
    }

    @Test
    fun aTargetWithAnEscapeIsRegisteredOnceAndFoundAgain() {
        val target = Target("http://127.0.0.1:18091/hello%20world")

        val first = Definitions.EMPTY.registered(target)
        val second = first.definitions.registered(target)

        val service = first.definitions.services.single()
        assertEquals("hello%20world (auto generated)", service.name)
        assertEquals("http://127.0.0.1:18091/hello%20world", service.mockUrl)
        assertEquals(listOf(service), second.definitions.services)
        assertEquals(Route.ToService(target, service, emptyMap()), second.value)
    }

    @Test
    fun aTargetIsRegisteredAsTheClientWroteIt() {
        val target = Target("HTTP://Api.Example.com:80/V1/Customers/")

        val service =
            Definitions.EMPTY
                .registered(target)
                .definitions.services
                .single()

        assertEquals("HTTP://Api.Example.com:80/V1/Customers/", service.mockUrl)
        assertEquals(listOf("HTTP://Api.Example.com:80/V1/Customers/"), service.realUrls)
        assertEquals("Customers (auto generated)", service.name)
    }

    @Test
    fun aTargetWithoutAPathIsNamedAfterItsHost() {
        val service =
            Definitions.EMPTY
                .registered(Target("http://api.example.com"))
                .definitions.services
                .single()

        assertEquals("api.example.com (auto generated)", service.name)
    }

    @Test
    fun bracesInATargetMakeAVariableOfTheRegisteredService() {
        val target = Target("http://api.example.com/customers/{id}")

        val registered = Definitions.EMPTY.registered(target)

        assertEquals(Route.ToService(target, registered.definitions.services.single(), mapOf("id" to "{id}")), registered.value)
        assertEquals(mapOf("id" to "7"), (registered.definitions.route("http://api.example.com/customers/7") as Route.ToService).variables)
    }

    @Test
    fun aTargetThatTheFilterHidesGetsNoSecondService() {
        val hidden = service("Customers", "http://api.example.com/v1/customers", tags = Tags.of("smoke"))
        val definitions = Definitions(services = listOf(hidden))

        val registered = definitions.registered(customers, regression)

        assertSame(definitions, registered.definitions)
        assertEquals(Route.Forward(customers, registers = false), registered.value)
    }

    @Test
    fun aTargetThatAHiddenPatternCoversGetsNoSecondService() {
        val throughMockUrl = service("Customers", "http://api.example.com/{version}/customers", tags = Tags.of("smoke"))
        val throughRealUrl = service("Customers", "customers", realUrls = listOf("http://api.example.com/{version}/customers"))

        val registered = listOf(throughMockUrl, throughRealUrl).map { Definitions(services = listOf(it)).registered(customers, regression) }

        assertEquals(listOf(listOf(throughMockUrl), listOf(throughRealUrl)), registered.map { it.definitions.services })
        assertEquals(List(2) { Route.Forward(customers, registers = false) }, registered.map { it.value })
    }

    @Test
    fun aTargetThatAServiceHasComeToKnowGoesToThatService() {
        val created = service("Customers", "http://api.example.com/{version}/customers")
        val definitions = Definitions(services = listOf(created))

        val registered = definitions.registered(customers)

        assertSame(definitions, registered.definitions)
        assertEquals(Route.ToService(customers, created, mapOf("version" to "v1")), registered.value)
    }

    @Test
    fun aRelativeTargetIsNeverRegistered() {
        val registered = Definitions.EMPTY.registered(Target("foo/bar"))

        assertSame(Definitions.EMPTY, registered.definitions)
        assertIs<Route.NotFound>(registered.value)
    }

    @Test
    fun aTargetWhoseServiceWouldHaveNoValidNameIsForwardedWithoutAService() {
        val target = Target("http://api.example.com/tokens/" + "t".repeat(300))

        val registered = Definitions.EMPTY.registered(target)

        assertSame(Definitions.EMPTY, registered.definitions)
        assertEquals(Route.Forward(target, registers = false), registered.value)
    }

    @Test
    fun aTargetWhoseUrlAnotherServiceAlreadyHasIsForwardedWithoutAService() {
        val padded = Target("http://api.example.com/v1/customers ")
        val definitions = Definitions.EMPTY.registered(padded).definitions

        val again = definitions.registered(padded)

        assertEquals("http://api.example.com/v1/customers", definitions.services.single().mockUrl)
        assertSame(definitions, again.definitions)
        assertEquals(Route.Forward(padded, registers = false), again.value)
    }

    @Test
    fun theChangeRoutesWithTheTableThatItIsGiven() {
        val definitions = Definitions(services = listOf(service("Orders", "orders/{id}")))
        val table = RoutingTable(definitions)
        val asked = mutableListOf<Definitions>()

        val registered =
            definitions.changed(
                TargetRegistration.register(customers, Tags.EMPTY) {
                    asked += it
                    table
                },
            )

        assertEquals(listOf(definitions), asked)
        assertIs<Route.ToService>(registered.value)
    }

    @Test
    @Timeout(30)
    fun requestsForOneUnknownTargetAtTheSameTimeRegisterOneService() {
        val changes = AtomicInteger()
        val state = EngineState(observers = listOf(ChangeObserver { _, _ -> changes.incrementAndGet() }))

        val routes =
            runBlocking(Dispatchers.Default) {
                List(64) { async { state.change(TargetRegistration.register(customers, regression)) } }.awaitAll()
            }

        val service = state.current.services.single()
        assertEquals(1, changes.get())
        assertTrue(routes.all { it is ChangeResult.Changed })
        assertEquals(List(64) { Route.ToService(customers, service, emptyMap()) }, routes.map { (it as ChangeResult.Changed).value })
    }
}
