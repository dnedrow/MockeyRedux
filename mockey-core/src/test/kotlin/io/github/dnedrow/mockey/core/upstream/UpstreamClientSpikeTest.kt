package io.github.dnedrow.mockey.core.upstream

import java.io.IOException
import java.net.Authenticator
import java.net.CookieManager
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spike of task 7.1: what the HTTP client of the JDK does with requests that go through an outbound proxy
 * that wants credentials, and what else it does to a request and a response on their way. The tests state the
 * facts that `docs/decisions/upstream-client.md` rests on, against the stub proxy and the stub upstream.
 *
 * The test JVM runs with `jdk.http.auth.tunneling.disabledSchemes` set to nothing, see the build file.
 */
class UpstreamClientSpikeTest {
    private val text = "Content-Type" to "text/plain"

    private fun client(
        proxy: StubProxy? = null,
        authenticator: Authenticator? = null,
        tls: SSLContext? = StubTls.trusting,
    ): HttpClient {
        val selector = if (proxy == null) HttpClient.Builder.NO_PROXY else ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port))
        val builder =
            HttpClient
                .newBuilder()
                .version(
                    HttpClient.Version.HTTP_1_1,
                ).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(selector)
        authenticator?.let(builder::authenticator)
        tls?.let(builder::sslContext)
        return builder.build()
    }

    private fun request(
        url: String,
        vararg headers: Pair<String, String>,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI(url))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun HttpClient.get(
        url: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<ByteArray> = send(request(url, *headers), HttpResponse.BodyHandlers.ofByteArray())

    /** Answers for a proxy with the credentials `user:secret`, and for nothing else. */
    private val proxyCredentials =
        object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestorType == RequestorType.PROXY) PasswordAuthentication("user", "secret".toCharArray()) else null
        }

    @Test
    fun aRequestForAnHttpUrlGoesThroughAProxyThatWantsCredentials() {
        StubUpstream().use { upstream ->
            StubProxy("user:secret").use { proxy ->
                val url = "${upstream.origin}/orders?page=2"

                val response = client(proxy).get(url, "Proxy-Authorization" to proxy.authorization!!, "Authorization" to "Bearer abc")

                val viaProxy = proxy.requests.single()
                val arrived = upstream.requests.single()
                assertEquals(200, response.statusCode())
                assertEquals("GET" to url, viaProxy.method to viaProxy.target)
                assertEquals(proxy.authorization, viaProxy.header("Proxy-Authorization"))
                assertEquals("GET" to "/orders?page=2", arrived.method to arrived.target)
                assertEquals("Bearer abc", arrived.header("Authorization"))
                assertEquals("127.0.0.1:${upstream.port}", arrived.header("Host"))
            }
        }
    }

    @Test
    fun aRequestForAnHttpsUrlGoesThroughATunnelOfAProxyThatWantsCredentials() {
        StubUpstream(StubTls.server).use { upstream ->
            StubProxy("user:secret").use { proxy ->
                val url = "${upstream.origin}/orders?page=2"

                val response = client(proxy).get(url, "Proxy-Authorization" to proxy.authorization!!, "Authorization" to "Bearer abc")

                val tunnel = proxy.requests.single()
                val arrived = upstream.requests.single()
                assertEquals(200, response.statusCode())
                assertEquals("CONNECT" to "127.0.0.1:${upstream.port}", tunnel.method to tunnel.target)
                assertEquals(proxy.authorization, tunnel.header("Proxy-Authorization"))
                assertEquals("GET" to "/orders?page=2", arrived.method to arrived.target)
                assertEquals("Bearer abc", arrived.header("Authorization"))
                assertNull(arrived.header("Proxy-Authorization"))
            }
        }
    }

    /** That is why the adapter sends the credentials itself and gives the client no authenticator. */
    @Test
    fun aClientWithAnAuthenticatorDropsTheAuthorizationHeaderOfARequest() {
        StubUpstream().use { upstream ->
            StubProxy("user:secret").use { proxy ->
                val response = client(proxy, proxyCredentials).get("${upstream.origin}/orders", "Authorization" to "Bearer abc")

                assertEquals(200, response.statusCode())
                assertEquals(listOf(null, proxy.authorization), proxy.requests.map { it.header("Proxy-Authorization") })
                assertNull(upstream.requests.single().header("Authorization"))
            }
        }
    }

    @Test
    fun aProxyThatGetsNoCredentialsAnswersWithTheStatus407() {
        StubUpstream().use { upstream ->
            StubUpstream(StubTls.server).use { secure ->
                StubProxy("user:secret").use { proxy ->
                    val plain = client(proxy).get("${upstream.origin}/orders")
                    val tunneled = client(proxy).get("${secure.origin}/orders")

                    assertEquals(407, plain.statusCode())
                    assertEquals("proxy credentials required", String(plain.body()))
                    assertEquals(407, tunneled.statusCode())
                    assertNull(tunneled.body())
                    assertEquals(0, upstream.requests.size + secure.requests.size)
                }
            }
        }
    }

    /**
     * Each run is a JVM of its own, because the JDK reads the property once. Left alone, the JDK keeps Basic
     * credentials out of a request for a tunnel, so the proxy answers 407. With the property set to nothing, the
     * credentials are sent.
     */
    @Test
    fun theJdkSendsBasicCredentialsForATunnelOnlyWhenItsPropertyAllowsThem() {
        assertEquals("407", TunnelProbe.run(disabledSchemes = null)["status"])
        assertEquals("407", TunnelProbe.run(disabledSchemes = "Basic")["status"])
        assertEquals("200", TunnelProbe.run(disabledSchemes = "")["status"])
    }

    @Test
    fun theClientDoesNotLetARequestSetTheHeadersOfItsConnection() {
        listOf("Host", "Content-Length", "Connection", "Upgrade", "Expect").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { request("http://127.0.0.1:1/", name to "value") }
        }
        listOf("Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Cookie", "If-None-Match", "Accept-Encoding").forEach { name ->
            assertEquals(listOf("value"), request("http://127.0.0.1:1/", name to "value").headers().allValues(name), name)
        }
    }

    @Test
    fun theClientAddsAUserAgentOnlyToARequestWithoutOne() {
        StubUpstream().use { upstream ->
            client().get("${upstream.origin}/a")
            client().get("${upstream.origin}/b", "User-Agent" to "curl/8.7.1")

            assertTrue(upstream.requests[0].header("User-Agent")!!.startsWith("Java-http-client/"))
            assertEquals(listOf("curl/8.7.1"), upstream.requests[1].headerValues("User-Agent"))
        }
    }

    @Test
    fun aRequestWithoutABodyHasNoContentLengthOnlyWhenItIsBuiltWithoutABodyPublisher() {
        StubUpstream().use { upstream ->
            val url = URI("${upstream.origin}/a")
            val none = HttpRequest.BodyPublishers.noBody()
            val requests =
                listOf(
                    HttpRequest.newBuilder(url).GET().build(),
                    HttpRequest.newBuilder(url).DELETE().build(),
                    HttpRequest.newBuilder(url).HEAD().build(),
                    HttpRequest.newBuilder(url).method("GET", none).build(),
                    HttpRequest.newBuilder(url).method("OPTIONS", none).build(),
                    HttpRequest.newBuilder(url).method("POST", none).build(),
                    HttpRequest.newBuilder(url).method("PATCH", HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3))).build(),
                )

            requests.forEach { client().send(it, HttpResponse.BodyHandlers.discarding()) }

            assertEquals(listOf("GET", "DELETE", "HEAD", "GET", "OPTIONS", "POST", "PATCH"), upstream.requests.map { it.method })
            assertEquals(listOf(null, null, null, "0", "0", "0", "3"), upstream.requests.map { it.header("Content-Length") })
            assertContentEquals(byteArrayOf(1, 2, 3), upstream.requests.last().body)
        }
    }

    @Test
    fun theNamesOfResponseHeadersArriveInLowerCaseAndTheValuesOfANameInTheirOrder() {
        val headers =
            listOf(
                "X-Multi" to "a",
                "Set-Cookie" to "sid=abc; Path=/; HttpOnly",
                "x-multi" to "b",
                "Set-cookie" to "t=1; Max-Age=60",
                text,
            )
        StubUpstream(respond = { StubResponse(201, headers, "created".toByteArray()) }).use { upstream ->
            val response = client().get("${upstream.origin}/a")

            assertEquals(201, response.statusCode())
            assertEquals(
                listOf("content-length", "content-type", "set-cookie", "x-multi"),
                response
                    .headers()
                    .map()
                    .keys
                    .toList(),
            )
            assertEquals(listOf("a", "b"), response.headers().allValues("X-Multi"))
            assertEquals(listOf("sid=abc; Path=/; HttpOnly", "t=1; Max-Age=60"), response.headers().allValues("Set-Cookie"))
            assertContentEquals("created".toByteArray(), response.body())
        }
    }

    @Test
    fun theClientGivesTheBodyOfAResponseAsItArrivesHoweverItsEndIsMarked() {
        val bytes = ByteArray(256) { it.toByte() }
        val framings = mapOf("/length" to Framing.CONTENT_LENGTH, "/chunked" to Framing.CHUNKED, "/close" to Framing.UNTIL_CLOSE)
        StubUpstream(respond = { StubResponse(200, emptyList(), bytes, framings.getValue(it.target)) }).use { upstream ->
            framings.keys.forEach { path -> assertContentEquals(bytes, client().get("${upstream.origin}$path").body(), path) }
        }
    }

    @Test
    fun aResponseThatEndsEarlyOrDoesNotComeIsAnError() {
        StubUpstream(respond = {
            if (it.target ==
                "/cut"
            ) {
                StubResponse(200, emptyList(), "cut short".toByteArray(), Framing.CUT_SHORT)
            } else {
                null
            }
        }).use {
            assertFailsWith<IOException> { client().get("${it.origin}/cut") }
            assertFailsWith<IOException> { client().get("${it.origin}/none") }
        }
    }

    @Test
    fun aRedirectIsReturnedAsItIsWhenTheClientFollowsNone() {
        StubUpstream(respond = { StubResponse(302, "moved", "Location" to "/after") }).use { upstream ->
            val response = client().get("${upstream.origin}/before")

            assertEquals(302, response.statusCode())
            assertEquals(listOf("/after"), response.headers().allValues("Location"))
            assertEquals(1, upstream.requests.size)
        }
    }

    @Test
    fun theCookieStoreOfTheJdkWritesACookieWithAMaxAgeInAnOlderForm() {
        val cookies = CookieManager()
        val setCookies = listOf("sid=abc; Path=/stub/cookie; HttpOnly", "theme=dark; Path=/stub/cookie; Max-Age=3600")

        cookies.put(URI("http://127.0.0.1:18091/stub/cookie/set"), mapOf("Set-Cookie" to setCookies))

        val sent = cookies.get(URI("http://127.0.0.1:18091/stub/cookie/echo"), emptyMap())
        assertEquals(mapOf("Cookie" to listOf("sid=abc", "theme=\"dark\";\$Path=\"/stub/cookie\";\$Domain=\"127.0.0.1\"")), sent)
    }

    @Test
    fun aClientThatIsNotHeldToHttp11AsksAnHttpServerToChangeToHttp2() {
        StubUpstream().use { upstream ->
            HttpClient
                .newBuilder()
                .proxy(
                    HttpClient.Builder.NO_PROXY,
                ).build()
                .send(request("${upstream.origin}/a"), HttpResponse.BodyHandlers.discarding())
            client().get("${upstream.origin}/b")

            assertEquals(listOf("h2c"), upstream.requests[0].headerValues("Upgrade"))
            assertEquals(listOf("Upgrade, HTTP2-Settings"), upstream.requests[0].headerValues("Connection"))
            assertEquals(1, upstream.requests[0].headerValues("HTTP2-Settings").size)
            assertEquals(listOf("host", "user-agent"), upstream.requests[1].headerNames.sorted())
        }
    }

    @Test
    fun aCertificateThatTheRuntimeDoesNotTrustFailsTheHandshakeBeforeARequestIsSent() {
        StubUpstream(StubTls.server).use { upstream ->
            assertFailsWith<SSLHandshakeException> { client(tls = null).get("${upstream.origin}/orders") }
            assertEquals(0, upstream.requests.size)
        }
    }

    @Test
    fun cancelingARequestEndsItAtOnce() {
        StubUpstream(respond = {
            Thread.sleep(SLOW_MILLIS)
            StubResponse(200, "late", text)
        }).use { upstream ->
            val start = System.nanoTime()
            val pending = client().sendAsync(request("${upstream.origin}/slow"), HttpResponse.BodyHandlers.ofByteArray())
            while (upstream.requests.isEmpty()) Thread.sleep(POLL_MILLIS)

            pending.cancel(true)
            val failure = assertFails { pending.get(SLOW_MILLIS, TimeUnit.MILLISECONDS) }

            // The future ends with the cancellation itself, or with a failure that the cancellation caused,
            // whichever of the two the JDK client gets to first.
            assertIs<CancellationException>(if (failure is ExecutionException) failure.cause else failure)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < SLOW_MILLIS)
        }
    }

    @Test
    fun aUriDoesNotTakeEveryCharacterThatARequestTargetCanHold() {
        listOf("/a|b", "/a?q={x}", "/a b", "/a?q=\"x\"", "/a%zz").forEach { target ->
            assertFailsWith<URISyntaxException>(target) { URI("http://127.0.0.1:1$target") }
        }
        assertEquals("/a%7Cb?x=%7Bq%7D&y=a+b", URI("http://127.0.0.1:1/a%7Cb?x=%7Bq%7D&y=a+b").let { "${it.rawPath}?${it.rawQuery}" })
    }

    private companion object {
        const val SLOW_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
