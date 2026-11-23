package io.github.dnedrow.mockey.parity

import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StubServersTest {
    private val client = ParityHttp.newClient()

    @Test
    fun upstreamStubRecordsRequests() {
        StubHttpServer.upstream().use { upstream ->
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:${upstream.port}/orders?id=7"))
                        .POST(HttpRequest.BodyPublishers.ofString("payload"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            assertEquals("upstream:/orders?id=7", response.body())
            val request = upstream.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/orders?id=7", request.target)
            assertEquals("payload", String(request.body))
        }
    }

    @Test
    fun outboundProxyStubRecordsProxiedRequests() {
        StubHttpServer.outboundProxy().use { proxy ->
            val client =
                HttpClient
                    .newBuilder()
                    .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port)))
                    .build()
            val response =
                client.send(
                    HttpRequest.newBuilder(URI("http://example.invalid/resource")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            assertEquals("outbound-proxy:http://example.invalid/resource", response.body())
            val request = proxy.requests.single()
            assertEquals("GET", request.method)
            assertTrue(request.target.endsWith("example.invalid/resource"), request.target)
        }
    }

    @Test
    fun echoRouteDescribesTheRequest() {
        StubHttpServer.upstream().use { upstream ->
            val headers = mapOf("X-Request-Id" to "7", "Accept" to "text/plain", "User-Agent" to "test", "Via" to "1.1 unlisted")
            val response = send(upstream, "POST", "/stub/echo?b=2&a=1", headers, "hi".toByteArray())

            assertEquals(200, response.statusCode())
            assertEquals(listOf("text/plain"), response.headers().allValues("content-type"))
            assertEquals(
                """
                method: POST
                target: /stub/echo?b=2&a=1
                header accept: text/plain
                header host: 127.0.0.1:${upstream.port}
                header user-agent: test
                header x-request-id: 7
                body-length: 2
                body-hex: 6869

                """.trimIndent(),
                String(response.body()),
            )
            assertEquals("/stub/echo?b=2&a=1", upstream.requests.single().target)
        }
    }

    @Test
    fun echoRouteAnswersBelowItsPathAndInsideTheCookiePath() {
        StubHttpServer.upstream().use { upstream ->
            assertTrue(String(send(upstream, "GET", "/stub/echo/delayed").body()).startsWith("method: GET\ntarget: /stub/echo/delayed\n"))
            assertTrue(String(send(upstream, "GET", "/stub/cookie/echo").body()).startsWith("method: GET\ntarget: /stub/cookie/echo\n"))
        }
    }

    @Test
    fun headRequestGetsTheHeadersWithoutTheBody() {
        StubHttpServer.upstream().use { upstream ->
            val response = send(upstream, "HEAD", "/stub/echo")

            assertEquals(200, response.statusCode())
            assertEquals(0, response.body().size)
            assertTrue(response.headers().firstValueAsLong("content-length").asLong > 0)
        }
    }

    @Test
    fun statusRouteAnswersWithTheStatusInItsPath() {
        StubHttpServer.upstream().use { upstream ->
            val notFound = send(upstream, "GET", "/stub/status/404")
            assertEquals(404, notFound.statusCode())
            assertEquals("status 404", String(notFound.body()))

            val noContent = send(upstream, "GET", "/stub/status/204")
            assertEquals(204, noContent.statusCode())
            assertEquals(0, noContent.body().size)
        }
    }

    @Test
    fun headersRouteAnswersWithARepeatedHeader() {
        StubHttpServer.upstream().use { upstream ->
            val response = send(upstream, "GET", "/stub/headers")

            assertEquals(201, response.statusCode())
            assertEquals(listOf("a", "b"), response.headers().allValues("x-multi"))
            assertEquals(listOf("one"), response.headers().allValues("x-upstream"))
            assertEquals(listOf("max-age=60"), response.headers().allValues("cache-control"))
            assertEquals(listOf("/created/9"), response.headers().allValues("location"))
            assertEquals("with headers", String(response.body()))
        }
    }

    @Test
    fun cookieRouteSetsTwoCookiesForItsPath() {
        StubHttpServer.upstream().use { upstream ->
            val response = send(upstream, "GET", "/stub/cookie/set")

            assertEquals(
                listOf("sid=abc; Path=/stub/cookie; HttpOnly", "theme=dark; Path=/stub/cookie; Max-Age=3600"),
                response.headers().allValues("set-cookie"),
            )
            assertEquals("cookie set", String(response.body()))
        }
    }

    @Test
    fun bodyRoutesAnswerWithExactBytes() {
        StubHttpServer.upstream().use { upstream ->
            val binary = send(upstream, "GET", "/stub/binary")
            assertEquals(listOf("application/octet-stream"), binary.headers().allValues("content-type"))
            assertContentEquals(ByteArray(256) { it.toByte() }, binary.body())

            val latin1 = send(upstream, "GET", "/stub/text/latin1")
            assertEquals(listOf("text/plain; charset=ISO-8859-1"), latin1.headers().allValues("content-type"))
            assertContentEquals("café".toByteArray(StandardCharsets.ISO_8859_1), latin1.body())

            val undeclared = send(upstream, "GET", "/stub/text/undeclared")
            assertEquals(listOf("text/plain"), undeclared.headers().allValues("content-type"))
            assertContentEquals("café".toByteArray(StandardCharsets.UTF_8), undeclared.body())
        }
    }

    @Test
    fun redirectRoutesPointAtTheEchoRouteOrAtThemselves() {
        StubHttpServer.upstream().use { upstream ->
            val base = "http://127.0.0.1:${upstream.port}/stub"

            val temporary = send(upstream, "POST", "/stub/redirect/307")
            assertEquals(307, temporary.statusCode())
            assertEquals(listOf("$base/echo?after=redirect"), temporary.headers().allValues("location"))
            assertEquals("redirect 307", String(temporary.body()))

            val loop = send(upstream, "GET", "/stub/redirect/loop")
            assertEquals(302, loop.statusCode())
            assertEquals(listOf("$base/redirect/loop"), loop.headers().allValues("location"))
        }
    }

    @Test
    fun closeRouteDropsTheConnectionWithoutAnAnswer() {
        StubHttpServer.upstream().use { upstream ->
            assertFailsWith<IOException> { send(upstream, "GET", "/stub/close") }
            // The JDK client tries an idempotent request again when the connection is lost, so there can be several.
            assertEquals(setOf("/stub/close"), upstream.requests.map { it.target }.toSet())
        }
    }

    @Test
    fun unknownStubRouteIsNotFound() {
        StubHttpServer.upstream().use { upstream ->
            val response = send(upstream, "GET", "/stub/unknown")

            assertEquals(404, response.statusCode())
            assertEquals("no stub route: unknown", String(response.body()))
        }
    }

    @Test
    fun authenticatingProxyStubWantsCredentialsFirst() {
        StubHttpServer.outboundProxy(requireProxyCredentials = true).use { proxy ->
            val anonymous = proxiedClient(proxy).build()
            val challenge = anonymous.send(get("http://upstream.invalid/stub/echo"), HttpResponse.BodyHandlers.ofString())
            assertEquals(407, challenge.statusCode())
            assertEquals(listOf("Basic realm=\"mockey-parity\""), challenge.headers().allValues("proxy-authenticate"))
            assertEquals(407, anonymous.send(get("http://upstream.invalid/outside"), HttpResponse.BodyHandlers.ofString()).statusCode())

            val credentials =
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication("parity-user", "parity-pass".toCharArray())
                }
            val accepted =
                proxiedClient(proxy)
                    .authenticator(credentials)
                    .build()
                    .send(get("http://upstream.invalid/stub/echo?x=1"), HttpResponse.BodyHandlers.ofString())

            assertEquals(200, accepted.statusCode())
            val echo = accepted.body()
            assertTrue(echo.startsWith("via: outbound-proxy\nmethod: GET\ntarget: http://upstream.invalid/stub/echo?x=1\n"), echo)
            val basic = Base64.getEncoder().encodeToString("parity-user:parity-pass".toByteArray())
            assertTrue("header proxy-authorization: Basic $basic\n" in echo, echo)
        }
    }

    private fun proxiedClient(proxy: StubHttpServer): HttpClient.Builder =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port)))

    private fun get(url: String): HttpRequest = HttpRequest.newBuilder(URI(url)).GET().build()

    private fun send(
        server: StubHttpServer,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
}
