package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.definitions.outboundProxyInUse
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The upstream client with an outbound proxy, against the stub proxy and the stub upstream.
 *
 * The test JVM runs with `jdk.http.auth.tunneling.disabledSchemes` set to nothing, see the build file. What
 * the client does about that property itself shows in JVMs of their own.
 */
class OutboundProxyTest {
    private val text = "Content-Type" to "text/plain"
    private val credentials = ProxyCredentials("user", "secret")

    private fun headers(vararg headers: Pair<String, String>) = headers.map { (name, value) -> Header(name, value) }

    /** The settings for this stub as an outbound proxy that is enabled. */
    private fun StubProxy.enabled(credentials: ProxyCredentials? = null) = OutboundProxy(url, enabled = true, credentials = credentials)

    private fun JdkUpstreamClient.send(
        method: String,
        url: String,
        proxy: OutboundProxy?,
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): MockResponse = runBlocking { send(UpstreamRequest(method, url, headers, body, proxy)) }

    @Test
    fun aRequestForAnHttpUrlGoesThroughTheOutboundProxy() {
        StubUpstream().use { upstream ->
            StubProxy().use { proxy ->
                JdkUpstreamClient().use { client ->
                    val url = "${upstream.origin}/orders?page=2"
                    val sent = headers("X-Request-Id" to "7", "Content-Type" to "text/plain")

                    val answer = client.send("POST", url, proxy.enabled(), sent, "posted".toByteArray())

                    val viaProxy = proxy.requests.single()
                    val arrived = upstream.requests.single()
                    assertEquals(200 to "ok", answer.status to answer.bodyText)
                    assertEquals("POST" to url, viaProxy.method to viaProxy.target)
                    assertNull(viaProxy.header("Proxy-Authorization"))
                    assertEquals("POST" to "/orders?page=2", arrived.method to arrived.target)
                    assertEquals(listOf("7"), arrived.headerValues("X-Request-Id"))
                    assertEquals("posted", String(arrived.body))
                }
            }
        }
    }

    @Test
    fun aRequestForAnHttpsUrlGoesThroughATunnelOfTheOutboundProxy() {
        StubUpstream(StubTls.server).use { upstream ->
            StubProxy().use { proxy ->
                JdkUpstreamClient(sslContext = StubTls.trusting).use { client ->
                    val answer = client.send("GET", "${upstream.origin}/orders?page=2", proxy.enabled(), headers("X-Request-Id" to "7"))

                    val tunnel = proxy.requests.single()
                    val arrived = upstream.requests.single()
                    assertEquals(200 to "ok", answer.status to answer.bodyText)
                    assertEquals("CONNECT" to "127.0.0.1:${upstream.port}", tunnel.method to tunnel.target)
                    assertNull(tunnel.header("Proxy-Authorization"))
                    assertEquals("GET" to "/orders?page=2", arrived.method to arrived.target)
                    assertEquals(listOf("7"), arrived.headerValues("X-Request-Id"))
                }
            }
        }
    }

    @Test
    fun theCredentialsGoToTheProxyWithTheFirstRequestAndNeverToARealService() {
        StubUpstream().use { upstream ->
            StubUpstream(StubTls.server).use { secure ->
                StubProxy("user:secret").use { proxy ->
                    JdkUpstreamClient(sslContext = StubTls.trusting).use { client ->
                        val plain = client.send("GET", "${upstream.origin}/orders", proxy.enabled(credentials))
                        val tunneled = client.send("GET", "${secure.origin}/orders", proxy.enabled(credentials))

                        assertEquals(listOf(200, 200), listOf(plain.status, tunneled.status))
                        assertEquals(listOf("GET", "CONNECT"), proxy.requests.map { it.method })
                        assertEquals(
                            listOf(proxy.authorization, proxy.authorization),
                            proxy.requests.map { it.header("Proxy-Authorization") },
                        )
                        assertNull(upstream.requests.single().header("Proxy-Authorization"))
                        assertNull(secure.requests.single().header("Proxy-Authorization"))
                    }
                }
            }
        }
    }

    @Test
    fun theCredentialsAreTheOnlyProxyAuthorizationOfARequestAndAreWrittenAsUtf8() {
        StubUpstream().use { upstream ->
            StubProxy("j\u00fcrgen:p\u00e4ss:word").use { proxy ->
                JdkUpstreamClient().use { client ->
                    val accented = ProxyCredentials("j\u00fcrgen", "p\u00e4ss:word")
                    val own = headers("Proxy-Authorization" to "Basic b3duOm93bg==", "X-Request-Id" to "7")

                    val answer = client.send("GET", "${upstream.origin}/orders", proxy.enabled(accented), own)

                    assertEquals(200, answer.status)
                    assertEquals(listOf(proxy.authorization), proxy.requests.single().headerValues("Proxy-Authorization"))
                    assertEquals(listOf("7"), upstream.requests.single().headerValues("X-Request-Id"))
                }
            }
        }
    }

    @Test
    fun anOutboundProxyOfTheSettingsThatIsNotEnabledIsNotUsed() {
        StubUpstream().use { upstream ->
            StubProxy("user:secret").use { proxy ->
                JdkUpstreamClient().use { client ->
                    val url = "${upstream.origin}/orders"
                    val request = MockRequest("GET", "http://mockey.test:8080/service/$url")
                    val disabled = Definitions(settings = Settings(outboundProxy = OutboundProxy(proxy.url, credentials = credentials)))
                    val enabled = Definitions(settings = Settings(outboundProxy = proxy.enabled(credentials)))

                    runBlocking { client.send(upstreamRequest(request, url, disabled.outboundProxyInUse)) }
                    val direct = proxy.requests.size to upstream.requests.map { it.header("Proxy-Authorization") }
                    runBlocking { client.send(upstreamRequest(request, url, enabled.outboundProxyInUse)) }

                    assertEquals(0 to listOf<String?>(null), direct)
                    assertEquals(1, proxy.requests.size)
                    assertEquals(2, upstream.requests.size)
                }
            }
        }
    }

    @Test
    fun aRequestGoesThroughTheProxyThatItNames() {
        StubUpstream().use { upstream ->
            StubProxy().use { first ->
                StubProxy().use { second ->
                    JdkUpstreamClient().use { client ->
                        val byName = OutboundProxy("http://localhost:${second.port}", enabled = true)

                        client.send("GET", "${upstream.origin}/a", first.enabled())
                        client.send("GET", "${upstream.origin}/b", second.enabled())
                        client.send("GET", "${upstream.origin}/c", first.enabled())
                        client.send("GET", "${upstream.origin}/d", null)
                        client.send("GET", "${upstream.origin}/e", byName)

                        assertEquals(listOf("/a", "/c"), first.requests.map { it.target.removePrefix(upstream.origin) })
                        assertEquals(listOf("/b", "/e"), second.requests.map { it.target.removePrefix(upstream.origin) })
                        assertEquals(listOf("/a", "/b", "/c", "/d", "/e"), upstream.requests.map { it.target })
                    }
                }
            }
        }
    }

    @Test
    fun aRedirectIsFollowedThroughTheProxyWithTheCookiesOfTheSession() {
        val respond = { request: ReceivedRequest ->
            when (request.target) {
                "/start" -> StubResponse(302, "", text, "Location" to "/final", "Set-Cookie" to "sid=abc; Path=/")
                else -> StubResponse(200, "arrived", text)
            }
        }
        StubUpstream(respond = respond).use { upstream ->
            StubProxy("user:secret").use { proxy ->
                JdkUpstreamClient().use { client ->
                    val request = UpstreamRequest("GET", "${upstream.origin}/start", outboundProxy = proxy.enabled(credentials))

                    val answer = runBlocking { client.forward(request, UpstreamSession()) }

                    assertEquals(200 to "arrived", answer.status to answer.bodyText)
                    assertEquals(listOf("${upstream.origin}/start", "${upstream.origin}/final"), proxy.requests.map { it.target })
                    assertEquals(listOf(null, "sid=abc"), upstream.requests.map { it.header("Cookie") })
                }
            }
        }
    }

    @Test
    fun aProxyThatDoesNotLetARequestThroughIsAFailure() {
        StubUpstream().use { upstream ->
            StubUpstream(StubTls.server).use { secure ->
                StubProxy("user:secret").use { proxy ->
                    JdkUpstreamClient(sslContext = StubTls.trusting).use { client ->
                        val wrong = ProxyCredentials("user", "wr0ng-pass")
                        val byProxy = "gave no complete answer: the outbound proxy at 127.0.0.1:${proxy.port}"
                        val plain = "The real service at ${upstream.origin}/orders $byProxy"
                        val tunneled = "The real service at ${secure.origin}/orders $byProxy"
                        val notAccepted = "did not accept the user name and the password that are set."
                        val hint =
                            "If they are right, start the Java runtime with -Djdk.http.auth.tunneling.disabledSchemes= " +
                                "so that it sends them for an https URL."

                        val none =
                            assertFailsWith<UpstreamException> { client.send("GET", "${upstream.origin}/orders?token=1", proxy.enabled()) }
                        val noneForTunnel =
                            assertFailsWith<UpstreamException> { client.send("GET", "${secure.origin}/orders", proxy.enabled()) }
                        val refused =
                            assertFailsWith<UpstreamException> { client.send("GET", "${upstream.origin}/orders", proxy.enabled(wrong)) }
                        val refusedForTunnel =
                            assertFailsWith<UpstreamException> { client.send("GET", "${secure.origin}/orders", proxy.enabled(wrong)) }

                        assertEquals("$plain wants credentials, and none are set.", none.message)
                        assertEquals("$tunneled wants credentials, and none are set.", noneForTunnel.message)
                        assertEquals("$plain $notAccepted", refused.message)
                        assertEquals("$tunneled $notAccepted $hint", refusedForTunnel.message)
                        assertEquals(0, upstream.requests.size + secure.requests.size)
                    }
                }
            }
        }
    }

    @Test
    fun aRealServiceThatAnswers407ItselfIsRelayedWhileNoProxyIsInUse() {
        val wantsCredentials = StubResponse(407, "who are you", text, "Proxy-Authenticate" to "Basic realm=\"orders\"")
        StubUpstream(respond = { wantsCredentials }).use { upstream ->
            JdkUpstreamClient().use { client ->
                val answer = client.send("GET", "${upstream.origin}/orders", null)

                assertEquals(407 to "who are you", answer.status to answer.bodyText)
                assertEquals(listOf("Basic realm=\"orders\""), answer.headerValues("Proxy-Authenticate"))
            }
        }
    }

    @Test
    fun anOutboundProxyThatCannotBeReachedIsAFailure() {
        JdkUpstreamClient().use { client ->
            val url = "http://api.example.com/orders?token=1"
            val service = "The real service at http://api.example.com/orders"
            val start = "$service gave no complete answer: no connection could be made to the outbound proxy at"

            val refused =
                assertFailsWith<UpstreamException> { client.send("GET", url, OutboundProxy("http://127.0.0.1:1", enabled = true)) }
            val unknown =
                assertFailsWith<UpstreamException> {
                    client.send(
                        "GET",
                        url,
                        OutboundProxy("http://unknown-proxy.invalid:3128", enabled = true),
                    )
                }

            assertEquals("$start 127.0.0.1:1.", refused.message)
            assertEquals("$start unknown-proxy.invalid:3128.", unknown.message)
        }
    }

    @Test
    fun anOutboundProxyWhoseUrlNamesNoProxyIsAFailure() {
        val reasons =
            mapOf(
                " " to "the outbound proxy has no URL.",
                "https://proxy.example.com:3128" to "the URL of the outbound proxy must start with http://.",
                "http://user:s3cret@proxy.example.com:3128" to "the URL of the outbound proxy must not hold a user name or a password.",
                "http://proxy.example.com:3128/path" to "the URL of the outbound proxy must name a host and a port and nothing else.",
            )
        StubUpstream().use { upstream ->
            JdkUpstreamClient().use { client ->
                val failures =
                    reasons.keys.map { url ->
                        assertFailsWith<UpstreamException>(
                            url,
                        ) { client.send("GET", "${upstream.origin}/orders", OutboundProxy(url, enabled = true)) }
                    }

                assertEquals(
                    reasons.values.map { "The real service at ${upstream.origin}/orders cannot be asked: $it" },
                    failures.map { it.message },
                )
                assertFalse(failures.any { "s3cret" in it.message.orEmpty() })
                assertEquals(0, upstream.requests.size)
            }
        }
    }

    /**
     * Each run is a JVM of its own, because the JDK reads the property once. In a JVM that leaves the property
     * unset, the client sets it to nothing, and the credentials reach the proxy with the request for the
     * tunnel. A value that the JVM was started with stays, and with `Basic` the proxy gets no credentials.
     */
    @Test
    fun theClientLetsTheJdkSendCredentialsForATunnelUnlessThePropertyIsSet() {
        val leftAlone = TunnelProbe.run(disabledSchemes = null, sender = "adapter")
        val forbidden = TunnelProbe.run(disabledSchemes = "Basic", sender = "adapter")
        val others = TunnelProbe.run(disabledSchemes = "NTLM", sender = "adapter")

        assertEquals(mapOf("status" to "200", "property" to ""), leftAlone)
        assertEquals("Basic", forbidden["property"])
        assertEquals(true, forbidden["failure"]?.contains("did not accept the user name and the password that are set."))
        assertEquals(mapOf("status" to "200", "property" to "NTLM"), others)
    }

    /**
     * The requests of the parity group `proxy-outbound`, as the harness sends them, and what its recordings
     * state: the proxy gets the whole target with its port (DEV-43), and with the credentials when some are
     * set. A proxy that is not enabled is not asked, and one that cannot be reached is a failure that names
     * its host (DEV-13).
     */
    @Test
    fun theOutboundRecordingsGoAsTheyState() {
        val harness = headers("Host" to "127.0.0.1:8080", "User-Agent" to "mockey-parity")
        StubUpstream().use { upstream ->
            StubProxy().use { open ->
                StubProxy("parity-user:parity-pass").use { authenticating ->
                    JdkUpstreamClient().use { client ->
                        val echo = "${upstream.origin}/stub/echo"
                        val posted = harness + Header("Content-Type", "text/plain") + Header("Content-Length", "17")
                        val parity = ProxyCredentials("parity-user", "parity-pass")

                        fun proxied(
                            method: String,
                            url: String,
                            proxy: OutboundProxy?,
                            headers: List<Header> = harness,
                            body: ByteArray = ByteArray(0),
                        ): MockResponse {
                            val query = url.substringAfter('?', missingDelimiterValue = "").ifEmpty { null }
                            val request =
                                MockRequest(method, "http://127.0.0.1:8080/service/${url.substringBefore('?')}", query, headers, body)
                            return runBlocking { client.forward(upstreamRequest(request, url, proxy), UpstreamSession()) }
                        }

                        // proxy-outbound-get
                        proxied("GET", "$echo?via=proxy", open.enabled())
                        assertEquals("GET" to "$echo?via=proxy", open.requests.last().let { it.method to it.target })
                        assertEquals(
                            "127.0.0.1:${upstream.port}" to "mockey-parity",
                            open.requests.last().let {
                                it.header("Host") to
                                    it.header("User-Agent")
                            },
                        )

                        // proxy-outbound-post
                        proxied("POST", echo, open.enabled(), posted, "through the proxy".toByteArray())
                        assertEquals("POST" to echo, open.requests.last().let { it.method to it.target })
                        assertEquals(
                            "text/plain" to "through the proxy",
                            open.requests.last().let {
                                it.header("Content-Type") to
                                    String(it.body)
                            },
                        )

                        // proxy-outbound-credentials
                        proxied("GET", echo, authenticating.enabled(parity))
                        assertEquals("GET" to echo, authenticating.requests.single().let { it.method to it.target })
                        assertEquals(
                            "Basic cGFyaXR5LXVzZXI6cGFyaXR5LXBhc3M=",
                            authenticating.requests.single().header("Proxy-Authorization"),
                        )

                        // proxy-outbound-disabled
                        val viaProxy = open.requests.size
                        proxied("GET", echo, Definitions(settings = Settings(outboundProxy = OutboundProxy(open.url))).outboundProxyInUse)
                        assertEquals(viaProxy, open.requests.size)
                        assertEquals("GET" to "/stub/echo", upstream.requests.last().let { it.method to it.target })

                        // proxy-outbound-unreachable
                        val failure =
                            assertFailsWith<UpstreamException> { proxied("GET", echo, OutboundProxy("http://127.0.0.1:1", enabled = true)) }
                        assertEquals(true, failure.message?.endsWith("no connection could be made to the outbound proxy at 127.0.0.1:1."))
                    }
                }
            }
        }
    }
}
