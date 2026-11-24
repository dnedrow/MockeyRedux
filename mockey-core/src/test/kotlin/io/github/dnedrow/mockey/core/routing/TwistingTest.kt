package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TwistingTest {
    private fun twist(vararg pairs: Pair<String, String>) =
        TwistConfiguration(TwistId.generate(), "twist", pairs.map { (origination, destination) -> TwistPair(origination, destination) })

    private fun twisted(
        target: String,
        vararg pairs: Pair<String, String>,
    ): String = twist(*pairs).twisted(Target(target)).text

    private fun enabled(twist: TwistConfiguration) = Definitions(twists = listOf(twist), settings = Settings(enabledTwistId = twist.id))

    @Test
    fun onlyTheFirstPairThatOccursInTheTargetApplies() {
        val twisted = twisted("http://qa1.example.com/orders", "qa1" to "qa2", "example.com" to "example.org")

        assertEquals("http://qa2.example.com/orders", twisted)
    }

    @Test
    fun aLaterPairAppliesWhenNoEarlierOneOccurs() {
        val twisted = twisted("http://uat1.example.com/orders", "qa1" to "qa2", "example.com" to "example.org", "orders" to "invoices")

        assertEquals("http://uat1.example.org/orders", twisted)
    }

    @Test
    fun noPairAppliesToTheResultOfAnother() {
        assertEquals("b", twisted("a", "a" to "b", "b" to "c"))
        assertEquals("plain/two", twisted("plain/one", "a.b" to "c", "one" to "two", "two" to "three"))
    }

    @Test
    fun theOriginationTextIsLiteral() {
        val target = Target("twist/axb")

        assertSame(target, twist("a.b" to "c").twisted(target))
        assertEquals("twist/c/axb", twisted("twist/a.b/axb", "a.b" to "c"))
    }

    @Test
    fun charactersThatMeanSomethingInARegularExpressionAreLiteralInBothTexts() {
        assertEquals("price/\$1", twisted("price/(usd)", "(usd)" to "\$1"))
        assertEquals("a\\b", twisted("a[b", "[" to "\\"))
        assertEquals("v2/x", twisted("v1+/x", "v1+" to "v2"))
        assertEquals("a.b", twisted("a.b", ".*" to "x"))
    }

    @Test
    fun everyOccurrenceOfTheOriginationTextIsReplaced() {
        assertEquals("http://qa2.example.com/qa2/orders/qa2", twisted("http://qa1.example.com/qa1/orders/qa1", "qa1" to "qa2"))
        assertEquals("baba", twisted("aaaa", "aa" to "ba"))
    }

    @Test
    fun theOriginationTextIsLookedForInItsLetterCase() {
        assertEquals("http://QA1.example.com/orders", twisted("http://QA1.example.com/orders", "qa1" to "qa2"))
    }

    @Test
    fun theOriginationTextIsLookedForInTheTargetAsTheClientWroteIt() {
        assertEquals("a%20b", twisted("a%20b", "a b" to "x"))
        assertEquals("x", twisted("a%20b", "a%20b" to "x"))
    }

    @Test
    fun aDestinationTextCanBeEmpty() {
        assertEquals("http://api.example.com/orders", twisted("http://api-qa.example.com/orders", "-qa" to ""))
    }

    @Test
    fun aPairWithoutAnOriginationTextNeverApplies() {
        assertEquals("plain/zero", twisted("plain/zero", "" to "x"))
        assertEquals("plain/two", twisted("plain/one", "" to "x", "one" to "two"))
    }

    @Test
    fun aTargetThatNoPairAppliesToStaysTheSameTarget() {
        val target = Target("plain/zero")

        assertSame(target, twist("a.b" to "c", "one" to "two").twisted(target))
        assertSame(target, twist().twisted(target))
    }

    @Test
    fun aTwistedTargetIsReadAnew() {
        val absolute = twist("billing" to "http://billing.example.com").twisted(Target("billing/invoices"))
        val relative = twist("http://qa1.example.com/" to "").twisted(Target("http://qa1.example.com/orders"))

        assertTrue(absolute.isAbsolute)
        assertEquals("http://billing.example.com/invoices", absolute.text)
        assertFalse(relative.isAbsolute)
        assertEquals("orders", relative.text)
    }

    @Test
    fun withoutAnEnabledTwistConfigurationTheTargetIsUsedAsReceived() {
        val target = Target("http://qa1.example.com/orders")
        val twist = twist("qa1" to "qa2")

        assertSame(target, Definitions(twists = listOf(twist)).twisted(target))
        assertSame(target, Definitions.EMPTY.twisted(target))
        assertNull(Definitions(twists = listOf(twist)).enabledTwist)
    }

    @Test
    fun theEnabledTwistConfigurationRewritesTheTarget() {
        val toQa2 = twist("qa1" to "qa2")
        val toQa3 = twist("qa1" to "qa3")
        val definitions = Definitions(twists = listOf(toQa2, toQa3), settings = Settings(enabledTwistId = toQa3.id))

        assertSame(toQa3, definitions.enabledTwist)
        assertEquals(Target("http://qa3.example.com/orders"), definitions.twisted(Target("http://qa1.example.com/orders")))
    }

    @Test
    fun anEnabledIdentifierThatNamesNoConfigurationTwistsNothing() {
        val target = Target("http://qa1.example.com/orders")
        val definitions = Definitions(twists = listOf(twist("qa1" to "qa2")), settings = Settings(enabledTwistId = TwistId("gone")))

        assertNull(definitions.enabledTwist)
        assertSame(target, definitions.twisted(target))
    }

    /** The services and the twist configuration of the parity fixture `routing-twist`, and the targets of its recordings. */
    @Test
    fun theTargetsOfTheTwistRecordingsReachTheServicesThatTheRecordingsState() {
        fun service(
            name: String,
            mockUrl: String,
        ) = Service(id = ServiceId.generate(), name = name, mockUrl = mockUrl)
        val toStub = twist("a.b" to "c", "one" to "two", "qa1.invalid" to "127.0.0.1:18091")
        val definitions =
            enabled(toStub).copy(
                services =
                    listOf(
                        service("twist origin", "twist/a.b"),
                        service("twist destination", "twist/c"),
                        service("twist regular expression", "twist/c/c"),
                        service("twist literal", "twist/c/axb"),
                        service("twist first pair", "twist/c/one"),
                        service("twist both pairs", "twist/c/two"),
                        service("twist second pair", "plain/two"),
                        service("twist untouched", "plain/zero"),
                    ),
            )
        val table = RoutingTable(definitions)

        fun route(target: String): Route = table.route(definitions.twisted(Target(target)), Tags.EMPTY, forwardUnknownTargets = true)

        fun answering(target: String): String? = (route(target) as? Route.ToService)?.service?.name

        assertEquals("twist destination", answering("twist/a.b"))
        assertEquals("twist literal", answering("twist/a.b/axb"))
        assertEquals("twist first pair", answering("twist/a.b/one"))
        assertEquals("twist second pair", answering("plain/one"))
        assertEquals("twist untouched", answering("plain/zero"))
        assertEquals(Route.Forward(Target("http://127.0.0.1:18091/stub/echo"), registers = true), route("http://qa1.invalid/stub/echo"))
    }
}
