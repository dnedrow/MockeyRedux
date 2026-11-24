package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.routing.Route
import io.github.dnedrow.mockey.core.routing.RoutingTable
import io.github.dnedrow.mockey.core.routing.Target
import io.github.dnedrow.mockey.core.routing.twisted
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ForwardTargetTest {
    private fun service(
        mockUrl: String,
        vararg realUrls: String,
        defaultRealUrlIndex: Int = 0,
    ) = Service(
        id = ServiceId.generate(),
        name = "Customers",
        mockUrl = mockUrl,
        realUrls = realUrls.toList(),
        defaultRealUrlIndex = defaultRealUrlIndex,
    )

    /**
     * Routes a request for [target], which is what follows `/service/` with the query string, and returns where
     * the request goes when the service that it reaches forwards it.
     */
    private fun Definitions.forwarded(target: String): ForwardTarget {
        val query = if ('?' in target) target.substringAfter('?') else null
        val twisted = twisted(Target(target.substringBefore('?')))
        return when (val route = RoutingTable(this).route(twisted, Tags.EMPTY, forwardUnknownTargets = true)) {
            is Route.ToService -> forwardTarget(route.service, route.target, route.variables, query)
            is Route.Forward -> forwardTarget(route.target, query)
            is Route.NotFound -> error("No route for $target: ${route.explanation}")
        }
    }

    private fun Service.forwarded(target: String): ForwardTarget = Definitions(services = listOf(this)).forwarded(target)

    private fun to(url: String) = ForwardTarget.To(url)

    @Test
    fun anAbsoluteTargetIsForwardedWithTheQueryStringOfTheRequest() {
        val service = service("http://api.example.com/users")

        assertEquals(to("http://api.example.com/users?page=2"), service.forwarded("http://api.example.com/users?page=2"))
        assertEquals(to("http://api.example.com/users"), service.forwarded("http://api.example.com/users"))
        assertEquals(to("http://api.example.com/users?"), service.forwarded("http://api.example.com/users?"))
    }

    @Test
    fun anAbsoluteTargetIsForwardedAsItIsWritten() {
        val service = service("http://api.example.com/users/{id}")
        val target = "HTTP://API.Example.com:80/Users/Jane%20Doe/"

        assertEquals(to("$target?b=2&a=1&name=jane%20doe"), service.forwarded("$target?b=2&a=1&name=jane%20doe"))
    }

    @Test
    fun theRealUrlsOfAServiceHaveNoSayForAnAbsoluteTarget() {
        val service = service("http://api.example.com/users", "http://qa1.example.com/users", "http://qa2.example.com/users")

        assertEquals(to("http://api.example.com/users?page=2"), service.forwarded("http://api.example.com/users?page=2"))
        assertEquals(to("http://qa2.example.com/users"), service.forwarded("http://qa2.example.com/users"))
    }

    @Test
    fun anAbsoluteTargetThatNoServiceKnowsIsForwardedAsItIs() {
        assertEquals(
            to("http://api.example.com/v1/customers?active=true"),
            Definitions().forwarded("http://api.example.com/v1/customers?active=true"),
        )
        assertFailsWith<IllegalArgumentException> { forwardTarget(Target("customer/7"), query = null) }
    }

    @Test
    fun anAbsoluteTargetIsForwardedAsItStandsAfterTwisting() {
        val twist = TwistConfiguration(id = TwistId.generate(), name = "to-qa2", pairs = listOf(TwistPair("qa1", "qa2")))
        val definitions = Definitions(twists = listOf(twist), settings = Settings(enabledTwistId = twist.id))

        assertEquals(to("http://qa2.example.com/orders?page=2"), definitions.forwarded("http://qa1.example.com/orders?page=2"))
    }

    @Test
    fun aRelativeTargetIsForwardedToTheDefaultRealUrlWithTheCapturedValues() {
        val service = service("customer/{id}", "http://api.example.com/customers/{id}")

        assertEquals(to("http://api.example.com/customers/Ab12?expand=orders"), service.forwarded("customer/Ab12?expand=orders"))
        assertEquals(to("http://api.example.com/customers/7"), service.forwarded("Customer/7/"))
    }

    @Test
    fun theDefaultRealUrlIsTheOneThatTheServiceDesignates() {
        val urls = arrayOf("http://qa1.example.com/customers/{id}", "http://qa2.example.com/customers/{id}")

        assertEquals(to("http://qa1.example.com/customers/7"), service("customer/{id}", *urls).forwarded("customer/7"))
        assertEquals(
            to("http://qa2.example.com/customers/7"),
            service("customer/{id}", *urls, defaultRealUrlIndex = 1).forwarded("customer/7"),
        )
        assertEquals(
            to("http://qa1.example.com/customers/7"),
            service("customer/{id}", *urls, defaultRealUrlIndex = 5).forwarded("customer/7"),
        )
    }

    @Test
    fun everyVariableOfTheRealUrlGetsTheValueOfTheVariableOfTheSameName() {
        val service = service("hotels/{hotel}/bookings/{booking}", "http://api.example.com/{booking}/of/{hotel}/and/{booking}")

        assertEquals(to("http://api.example.com/42/of/1/and/42"), service.forwarded("hotels/1/bookings/42"))
    }

    @Test
    fun aRealUrlWithoutVariablesIsTheUrlThatIsForwardedTo() {
        val service = service("relative-proxy.invalid/stub/echo", "http://127.0.0.1:18091/stub/echo/relative")

        assertEquals(to("http://127.0.0.1:18091/stub/echo/relative"), service.forwarded("relative-proxy.invalid/stub/echo"))
        assertEquals(to("http://127.0.0.1:18091/stub/echo/relative?page=2"), service.forwarded("relative-proxy.invalid/stub/echo?page=2"))
    }

    @Test
    fun aValueIsWrittenIntoTheRealUrlAsData() {
        val service = service("customer/{id}", "http://api.example.com/customers/{id}/orders")

        assertEquals(to("http://api.example.com/customers/jane%20doe/orders"), service.forwarded("customer/jane%20doe"))
        assertEquals(to("http://api.example.com/customers/a%3Fb%23c/orders"), service.forwarded("customer/a%3Fb%23c"))
        assertEquals("http://api.example.com/a%2Fb/a%2Fb", substituted("http://api.example.com/{id}/{id}", mapOf("id" to "a/b")))
        assertEquals(to("http://api.example.com/customers/caf%C3%A9/orders"), service.forwarded("customer/caf%C3%A9"))
        assertEquals(to("http://api.example.com/customers/100%25/orders"), service.forwarded("customer/100%25"))
        assertEquals(to("http://api.example.com/customers/A-b.c_d~e/orders"), service.forwarded("customer/A-b.c_d~e"))
    }

    @Test
    fun theQueryStringOfTheRequestFollowsAQueryStringOfTheRealUrl() {
        val service = service("customer/{id}", "http://api.example.com/lookup?customer={id}")

        assertEquals(to("http://api.example.com/lookup?customer=7&expand=orders"), service.forwarded("customer/7?expand=orders"))
        assertEquals(to("http://api.example.com/lookup?customer=a%26b%3Dc"), service.forwarded("customer/a&b=c"))
    }

    @Test
    fun aServiceWithoutARealUrlForwardsARelativeTargetNowhere() {
        val nowhere = assertIs<ForwardTarget.Nowhere>(service("customer/{id}").forwarded("customer/7?expand=orders"))

        assertEquals(
            "The service 'Customers' cannot forward the request for 'customer/7': the service has no real URL.",
            nowhere.explanation,
        )
    }

    @Test
    fun aVariableWithoutAValueForwardsTheRequestNowhere() {
        val service = service("customer/{id}", "http://api.example.com/{region}/customers/{id}")

        val nowhere = assertIs<ForwardTarget.Nowhere>(service.forwarded("customer/7"))

        assertContains(nowhere.explanation, "the variable 'region'")
        assertEquals(
            "The service 'Customers' cannot forward the request for 'customer/7': its real URL " +
                "http://api.example.com/{region}/customers/{id} has the variable 'region', for which the request has no value.",
            nowhere.explanation,
        )
    }

    @Test
    fun everyVariableWithoutAValueIsNamedOnce() {
        val service = service("customer/list", "http://api.example.com/{region}/{zone}/customers/{region}")

        val nowhere = assertIs<ForwardTarget.Nowhere>(service.forwarded("customer/list"))

        assertContains(nowhere.explanation, "has the variables 'region', 'zone', for which the request has no values.")
    }

    @Test
    fun theNameOfAVariableIsComparedLetterForLetter() {
        val service = service("customer/{id}", "http://api.example.com/customers/{ID}")

        assertContains(assertIs<ForwardTarget.Nowhere>(service.forwarded("customer/7")).explanation, "the variable 'ID'")
    }

    /**
     * The two services of the parity fixture `proxy-forwarding` with a relative mock URL, and the URLs whose
     * paths the stated new responses of `proxy-relative-target` and `proxy-relative-target-default-real-url`
     * report (DEV-12).
     */
    @Test
    fun theRelativeTargetsOfTheForwardingRecordingsGoToTheDefaultRealUrls() {
        val relative = service("relative-proxy.invalid/stub/echo", "http://127.0.0.1:18091/stub/echo/relative")
        val first = "http://127.0.0.1:18091/stub/echo/first"
        val second = service("relative-second.invalid/stub/echo", first, "http://127.0.0.1:18091/stub/echo/second", defaultRealUrlIndex = 1)
        val definitions =
            Definitions(services = listOf(relative, second.copy(id = ServiceId.generate(), name = "proxy relative second real url")))

        assertEquals(to("http://127.0.0.1:18091/stub/echo/relative"), definitions.forwarded("relative-proxy.invalid/stub/echo"))
        assertEquals(to("http://127.0.0.1:18091/stub/echo/second"), definitions.forwarded("relative-second.invalid/stub/echo"))
    }
}
