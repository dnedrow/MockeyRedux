package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The case of the legacy `com.mockey.model.ProxyServerModelTest`, with the name of its test method. A legacy
 * proxy model held a URL, a user name, and a password, each of which could be missing. The settings hold an
 * [OutboundProxy] or none: a URL, and next to it the credentials with a user name and a password.
 */
class LegacyProxyServerModelTest {
    /** Returns the settings that are stored when the outbound proxy is set to [proxy]. */
    private fun stored(proxy: OutboundProxy?): OutboundProxy? = Definitions.EMPTY.changed(OutboundProxyChanges.update { proxy }).value

    /**
     * Legacy asked a proxy model whether it holds any setting, and did not count an empty text as one, because
     * definitions are written and merged by hand. The same question is asked of the settings here, and settings
     * that hold nothing are stored as no settings.
     */
    @Test
    fun validateSettingChecker() {
        val none: OutboundProxy? = null
        val emptyUrl = OutboundProxy(url = "")
        val emptyUsername = OutboundProxy(url = "", credentials = ProxyCredentials(username = "", password = ""))
        val username = OutboundProxy(url = "", credentials = ProxyCredentials(username = "ausername", password = ""))
        val password = OutboundProxy(url = "", credentials = ProxyCredentials(username = "", password = "apassword"))

        assertFalse(none.hasSettings)
        assertFalse(emptyUrl.hasSettings)
        assertFalse(emptyUsername.hasSettings)
        assertTrue(username.hasSettings)
        assertTrue(password.hasSettings)

        assertNull(stored(none))
        assertNull(stored(emptyUrl))
        assertNull(stored(emptyUsername))
        assertEquals(username, stored(username))
        assertEquals(password, stored(password))
    }
}
