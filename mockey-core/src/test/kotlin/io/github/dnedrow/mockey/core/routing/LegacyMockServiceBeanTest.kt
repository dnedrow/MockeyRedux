package io.github.dnedrow.mockey.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cases of the legacy `com.mockey.MockServiceBeanTest`, with the names of its test methods. Both test the
 * legacy `Url`, as the legacy `UrlTest` does.
 *
 * Legacy read a text without a scheme as a host and gave it the scheme `http`. Such a text is a relative
 * target now (DEV-03), so the checks of that reading cannot pass. They state here what holds instead.
 */
class LegacyMockServiceBeanTest {
    /** Legacy read the host `mfasa-qa2.chase.com` and the path `/auth/fcc/login` (DEV-03). */
    @Test
    fun parsesRealServiceUrlIntoHostAndPath() {
        val url = Target("mfasa-qa2.chase.com/auth/fcc/login")

        assertFalse(url.isAbsolute)
        assertEquals("mfasa-qa2.chase.com/auth/fcc/login", url.normalized)
    }

    /**
     * Legacy read the scheme `http` in any letter case and the scheme `https`, each with its host. The scheme
     * shows here in the port that is left out as its default. Of `wired.com` legacy read the host and the
     * scheme `http` (DEV-03).
     */
    @Test
    fun parsesSchemeFromRealServiceUrl() {
        val google = Target("HTtP://www.google.com")
        assertTrue(google.isAbsolute)
        assertEquals(google.normalized, Target("HTtP://www.google.com:80").normalized)
        assertNotEquals(google.normalized, Target("HTtP://www.google.com:443").normalized)

        val gmail = Target("https://gmail.com")
        assertTrue(gmail.isAbsolute)
        assertEquals(gmail.normalized, Target("https://gmail.com:443").normalized)
        assertNotEquals(gmail.normalized, Target("https://gmail.com:80").normalized)

        assertFalse(Target("wired.com").isAbsolute)
    }
}
