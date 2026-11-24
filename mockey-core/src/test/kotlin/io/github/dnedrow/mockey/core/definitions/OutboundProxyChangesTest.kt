package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.TwistId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboundProxyChangesTest {
    private val credentials = ProxyCredentials("jane", "s3cret")
    private val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = credentials)
    private val withProxy = Definitions(settings = Settings(outboundProxy = proxy))

    /** Sets the outbound proxy of these definitions to [proxy] and returns the settings as they are stored. */
    private fun Definitions.stored(proxy: OutboundProxy?): OutboundProxy? = changed(OutboundProxyChanges.update { proxy }).value

    private fun urlErrors(url: String): List<FieldError> = Definitions.EMPTY.rejected(OutboundProxyChanges.update { OutboundProxy(url) })

    @Test
    fun theOutboundProxyIsSetWithItsUrlItsFlagAndItsCredentials() {
        val others = Settings(enabledTwistId = TwistId("twist-1"), defaultPlanId = PlanId("plan-1"))

        val changed = Definitions(settings = others).changed(OutboundProxyChanges.update { proxy })

        assertEquals(proxy, changed.value)
        assertEquals(credentials, changed.value?.credentials)
        assertEquals(others.copy(outboundProxy = proxy), changed.definitions.settings)
    }

    @Test
    fun theUrlIsStoredWithoutSurroundingWhitespace() {
        val stored = Definitions.EMPTY.stored(OutboundProxy(" http://proxy.example.com:3128 \n"))

        assertEquals(OutboundProxy("http://proxy.example.com:3128"), stored)
    }

    @Test
    fun settingsWithoutAValueAreNoSettings() {
        val blank = ProxyCredentials(username = " ", password = "")

        assertNull(Definitions.EMPTY.stored(OutboundProxy(url = "")))
        assertNull(Definitions.EMPTY.stored(OutboundProxy(url = "  \t")))
        assertNull(Definitions.EMPTY.stored(OutboundProxy(url = "", credentials = blank)))
        assertNull(withProxy.stored(OutboundProxy(url = "")))
        assertNull(withProxy.stored(null))
        assertNull(
            withProxy
                .changed(OutboundProxyChanges.update { null })
                .definitions.settings.outboundProxy,
        )
    }

    @Test
    fun credentialsWithoutAUserNameAndAPasswordAreNoCredentials() {
        val url = "http://proxy.example.com:3128"
        val userOnly = ProxyCredentials(username = "jane", password = "")
        val passwordOnly = ProxyCredentials(username = "", password = " s3cret ")

        assertEquals(OutboundProxy(url), Definitions.EMPTY.stored(OutboundProxy(url, credentials = ProxyCredentials(" ", ""))))
        assertEquals(userOnly, Definitions.EMPTY.stored(OutboundProxy(url, credentials = userOnly))?.credentials)
        assertEquals(passwordOnly, Definitions.EMPTY.stored(OutboundProxy(url, credentials = passwordOnly))?.credentials)
    }

    @Test
    fun settingsCanHoldCredentialsBeforeTheyHoldAUrl() {
        val withoutUrl = OutboundProxy(url = "", credentials = credentials)

        assertEquals(withoutUrl, Definitions.EMPTY.stored(withoutUrl))
    }

    @Test
    fun anOutboundProxyIsNotEnabledBeforeItHasAUrl() {
        val needsUrl = listOf(FieldError("url", "An outbound proxy needs a URL before it can be enabled."))

        assertEquals(needsUrl, Definitions.EMPTY.rejected(OutboundProxyChanges.update { OutboundProxy(url = " ", enabled = true) }))
        assertEquals(needsUrl, withProxy.rejected(OutboundProxyChanges.update { it?.copy(url = "") }))
    }

    @Test
    fun aUrlNamesTheHostOfTheProxyAndCanNameItsPort() {
        val addresses =
            mapOf(
                "http://proxy.example.com:3128" to "proxy.example.com:3128",
                "http://proxy.example.com" to "proxy.example.com:80",
                "HTTP://Proxy.Example.com:3128/" to "Proxy.Example.com:3128",
                "http://10.0.0.1:8080" to "10.0.0.1:8080",
                "http://[::1]:3128" to "[::1]:3128",
                "http://proxy_emea:8080" to "proxy_emea:8080",
                "http://proxy_emea" to "proxy_emea:80",
                " http://localhost:65535 " to "localhost:65535",
            )

        val stored = addresses.keys.map { Definitions.EMPTY.stored(OutboundProxy(it, enabled = true))?.url }

        assertEquals(addresses.keys.map { it.trim() }, stored)
        assertEquals(addresses.values.toList(), addresses.keys.map { proxyAddressOf(it).toString() })
    }

    @Test
    fun aUrlThatDoesNotNameWhereAProxyListensIsRejected() {
        val scheme = "The URL of the outbound proxy must start with http://."
        val port = "The URL of the outbound proxy names a port that does not exist."
        val more = "The URL of the outbound proxy must name a host and a port and nothing else."
        val invalid = "The URL of the outbound proxy is not a valid URL."
        val problems =
            mapOf(
                "proxy.example.com:3128" to scheme,
                "https://proxy.example.com:3128" to scheme,
                "socks5://proxy.example.com:1080" to scheme,
                "//proxy.example.com:3128" to scheme,
                "http://jane:s3cret@proxy.example.com:3128" to "The URL of the outbound proxy must not hold a user name or a password.",
                "http://jane@proxy_emea:3128" to "The URL of the outbound proxy must not hold a user name or a password.",
                "http:///proxy" to "The URL of the outbound proxy names no host.",
                "http://proxy.example.com:0" to port,
                "http://proxy.example.com:65536" to port,
                "http://proxy.example.com:port" to port,
                "http://proxy.example.com:3128/path" to more,
                "http://proxy.example.com:3128?x=1" to more,
                "http://proxy.example.com:3128/#top" to more,
                "http://proxy example.com" to invalid,
                "http://" to invalid,
            )

        val errors = problems.keys.map(::urlErrors)

        assertEquals(problems.values.map { listOf(FieldError("url", it)) }, errors)
        assertFalse(errors.any { "s3cret" in it.toString() })
        problems.keys.forEach { url -> assertFailsWith<IllegalArgumentException>(url) { proxyAddressOf(url) } }
    }

    @Test
    fun theUserNameCannotHoldAColon() {
        val colon = FieldError("credentials.username", "The user name for an outbound proxy must not contain a colon.")
        val named = proxy.copy(credentials = ProxyCredentials("jane:doe", "pass:word"))

        val one = Definitions.EMPTY.rejected(OutboundProxyChanges.update { named })
        val both = Definitions.EMPTY.rejected(OutboundProxyChanges.update { named.copy(url = "proxy.example.com") })

        assertEquals(listOf(colon), one)
        assertEquals(listOf(FieldError("url", "The URL of the outbound proxy must start with http://."), colon), both)
        assertEquals(
            proxy.copy(credentials = ProxyCredentials("jane", "pass:word")),
            Definitions.EMPTY.stored(named.copy(credentials = ProxyCredentials("jane", "pass:word"))),
        )
    }

    @Test
    fun aChangeThatReturnsTheCredentialsThatItGotKeepsThem() {
        val disabled = withProxy.changed(OutboundProxyChanges.update { it?.copy(enabled = false) })
        val moved = disabled.definitions.changed(OutboundProxyChanges.update { it?.copy(url = "http://other.example.com:8080") })

        assertEquals(proxy.copy(enabled = false), disabled.value)
        assertEquals(OutboundProxy("http://other.example.com:8080", enabled = false, credentials = credentials), moved.value)
    }

    @Test
    fun aChangeGetsNoSettingsWhereThereAreNone() {
        var given: OutboundProxy? = proxy

        val changed =
            Definitions.EMPTY.changed(
                OutboundProxyChanges.update {
                    given = it
                    null
                },
            )

        assertNull(given)
        assertNull(changed.value)
        assertEquals(Definitions.EMPTY, changed.definitions)
    }

    @Test
    fun aChangeThatIsRejectedLeavesTheSettingsAsTheyAre() {
        val errors = withProxy.rejected(OutboundProxyChanges.update { it?.copy(url = "ftp://proxy.example.com") })

        assertEquals(listOf("url"), errors.map { it.field })
        assertEquals(proxy, withProxy.settings.outboundProxy)
    }

    @Test
    fun theProxyInUseIsTheOneOfTheSettingsWhileItIsEnabled() {
        val disabled = Definitions(settings = Settings(outboundProxy = proxy.copy(enabled = false)))

        assertEquals(proxy, withProxy.outboundProxyInUse)
        assertNull(disabled.outboundProxyInUse)
        assertNull(Definitions.EMPTY.outboundProxyInUse)
    }

    @Test
    fun aReaderIsShownThatAPasswordIsSetAndNotThePassword() {
        val shown = proxy.view
        val withoutPassword = proxy.copy(credentials = ProxyCredentials("jane", "")).view
        val withoutCredentials = proxy.copy(enabled = false, credentials = null).view

        assertEquals(OutboundProxyView("http://proxy.example.com:3128", enabled = true, username = "jane", passwordSet = true), shown)
        assertFalse("s3cret" in shown.toString())
        assertEquals(
            OutboundProxyView("http://proxy.example.com:3128", enabled = true, username = "jane", passwordSet = false),
            withoutPassword,
        )
        assertEquals(
            OutboundProxyView("http://proxy.example.com:3128", enabled = false, username = null, passwordSet = false),
            withoutCredentials,
        )
    }

    @Test
    fun settingsAreAUrlAUserNameOrAPassword() {
        val none: OutboundProxy? = null

        assertTrue(proxy.hasSettings)
        assertTrue(OutboundProxy("http://proxy.example.com:3128").hasSettings)
        assertTrue(OutboundProxy("", credentials = ProxyCredentials("jane", "")).hasSettings)
        assertTrue(OutboundProxy(" ", credentials = ProxyCredentials(" ", "s3cret")).hasSettings)
        assertFalse(OutboundProxy(" ", enabled = true, credentials = ProxyCredentials(" ", "\t")).hasSettings)
        assertFalse(none.hasSettings)
    }
}
