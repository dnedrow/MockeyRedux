package io.github.dnedrow.mockey.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cases of the legacy `com.mockey.util.UrlTest`, with the names of its test methods. The legacy `Url` took
 * a URL apart into its scheme, host, port, and path. A [Target] does not: it tells the URL of a real service
 * from other text, and it gives the form in which URLs are compared. That form shows what legacy read.
 */
class LegacyUrlTest {
    private fun targetOf(requestedUrl: String): Target = assertNotNull(MockRequestUri.parse("/service/$requestedUrl")).target

    /** Legacy read the scheme `http`, the port 80, the host `www.google.com`, and the path `/test`. */
    @Test
    fun parsesFullUrl() {
        val google = Target("http://www.google.com/test")

        assertTrue(google.isAbsolute)
        assertEquals("http://www.google.com/test", google.normalized)
        assertEquals(google.normalized, Target("http://www.google.com:80/test").normalized)
    }

    /** Legacy read a missing path as `/`. A URL without a path and the same URL with the path `/` are one URL here. */
    @Test
    fun replacesEmptyPathWithSlash() {
        assertEquals(Target("http://www.google.com/").normalized, Target("http://www.google.com").normalized)
    }

    /** Legacy read the scheme `http`, whatever its letter case, and the port 8080. */
    @Test
    fun parsesPortFromUrl() {
        val tomcat = Target("hTTp://localhost:8080")

        assertTrue(tomcat.isAbsolute)
        assertEquals("hTTp://localhost:8080", tomcat.normalized)
        assertEquals("hTTp://localhost", Target("hTTp://localhost:80").normalized)
        assertNotEquals(Target("hTTp://localhost").normalized, tomcat.normalized)
    }

    /**
     * Legacy asked whether a URL is set, which an empty or blank text is not. It asked that of the URL of the
     * outbound proxy only, whose settings the legacy `ProxyServerModelTest` covers. Of a target the question is
     * whether it is the URL of a real service.
     */
    @Test
    fun validateEmptySetings() {
        assertFalse(Target("").isAbsolute)
        assertFalse(Target("   ").isAbsolute)
        assertTrue(Target("http://www.google.com").isAbsolute)
    }

    /**
     * Legacy cut the query string off a requested URL to get the URL of the service that it registered for an
     * unknown target, and kept the rest as it was. It refused a text without a scheme, which is a relative
     * target here: no service is ever registered for one (DEV-03). The legacy case also passed `null`, which
     * the type of a request URI does not admit.
     */
    @Test
    fun extractMockUrlFromFullUrl() {
        assertEquals("http://abc.google.com/xyz/someargument/", targetOf("http://abc.google.com/xyz/someargument/").text)
        assertEquals("http://abc.google.com/xyz/someargument//", targetOf("http://abc.google.com/xyz/someargument//").text)
        assertEquals("http://abc.google.com:80/xyz/someargument//", targetOf("http://abc.google.com:80/xyz/someargument//").text)
        assertEquals("http://abc.google.com/xyz/path/a/b/c", targetOf("http://abc.google.com/xyz/path/a/b/c").text)
        assertEquals("http://abc.google.com/xyz/path/a/b/c", targetOf("http://abc.google.com/xyz/path/a/b/c?arg=oaa&arg1=").text)

        assertFalse(targetOf("abc.google.com/xyz/path/a/b/c?arg=oaa&arg1=").isAbsolute)
        assertFalse(targetOf("sometextfile.txt").isAbsolute)
    }
}
