package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.runBlocking
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The upstream client with real services that speak TLS, and with real services that take too long. */
class CertificateAndTimeoutTest {
    private val text = "Content-Type" to "text/plain"

    private fun JdkUpstreamClient.get(
        url: String,
        proxy: OutboundProxy? = null,
    ): MockResponse = runBlocking { send(UpstreamRequest("GET", url, outboundProxy = proxy)) }

    private fun millisSince(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    /**
     * The stub signed its certificate itself, so the runtime does not know the issuer. A runtime finds the
     * same on a network that inspects TLS traffic, so the failure says what the trust store of the runtime
     * needs.
     */
    @Test
    fun aCertificateThatTheRuntimeDoesNotTrustIsAFailureAndNoRequestIsSent() {
        StubUpstream(StubTls.server).use { upstream ->
            JdkUpstreamClient().use { client ->
                val failure = assertFailsWith<UpstreamException> { client.get("${upstream.origin}/orders?token=1") }

                val start = "The real service at ${upstream.origin}/orders gave no complete answer: its certificate was not accepted."
                assertContains(failure.message!!, "$start $TRUST_STORE_HINT ")
                assertEquals(0, upstream.requests.size)
            }
        }
    }

    /**
     * A client with an SSL context of its own says nothing about the trust store of the runtime, because that
     * trust store did not make the decision.
     */
    @Test
    fun aClientWithATrustOfItsOwnDoesNotPointAtTheTrustStoreOfTheRuntime() {
        StubUpstream(StubTls.server).use { upstream ->
            JdkUpstreamClient(sslContext = StubTls.trustingAnother).use { client ->
                val failure = assertFailsWith<UpstreamException> { client.get("${upstream.origin}/orders") }

                assertContains(failure.message!!, "gave no complete answer: its certificate was not accepted. ")
                assertFalse("Java runtime" in failure.message!!, failure.message)
                assertEquals(0, upstream.requests.size)
            }
        }
    }

    /**
     * The reason points at the trust store only when the issuer is the cause: no path leads from the
     * certificate to one that the runtime trusts. A certificate for another host and an expired certificate
     * have nothing to do with the trust store, and neither does a certificate that an SSL context of the
     * client's own refused.
     */
    @Test
    fun onlyAnIssuerThatTheRuntimeDoesNotTrustGetsTheHintAtTheTrustStore() {
        val notAccepted = "its certificate was not accepted."
        val noPath = "unable to find valid certification path to requested target"
        val unknownIssuer = handshakeFailure("PKIX path building failed: $noPath", CertPathBuilderException(noPath))
        val anotherHost = handshakeFailure("No subject alternative names matching IP address 127.0.0.2 found")
        val over = CertPathValidatorException("validity check failed", CertificateExpiredException("NotAfter: Thu Nov 26 2026"))
        val expired = handshakeFailure("PKIX path validation failed: validity check failed", over)

        assertEquals("$notAccepted $TRUST_STORE_HINT ${unknownIssuer.message}", certificateReason(unknownIssuer, byTheRuntime = true))
        assertEquals("$notAccepted ${unknownIssuer.message}", certificateReason(unknownIssuer, byTheRuntime = false))
        assertEquals("$notAccepted ${anotherHost.message}", certificateReason(anotherHost, byTheRuntime = true))
        assertEquals("$notAccepted ${expired.message}", certificateReason(expired, byTheRuntime = true))
        assertEquals(notAccepted, certificateReason(handshakeFailure(null), byTheRuntime = true))
    }

    /**
     * Returns the failure of a handshake as the runtime reports it: an exception with [message], whose cause
     * holds what the check of the certificate [found].
     */
    private fun handshakeFailure(
        message: String?,
        found: Throwable? = null,
    ): SSLHandshakeException = SSLHandshakeException(message).apply { initCause(CertificateException(message, found)) }

    @Test
    fun aCertificateThatIsNotTrustedIsAFailureThroughATunnelAsWell() {
        StubUpstream(StubTls.server).use { upstream ->
            StubProxy().use { proxy ->
                JdkUpstreamClient().use { client ->
                    val failure =
                        assertFailsWith<UpstreamException> {
                            client.get(
                                "${upstream.origin}/orders",
                                OutboundProxy(proxy.url, enabled = true),
                            )
                        }

                    assertContains(failure.message!!, "its certificate was not accepted.")
                    assertEquals(listOf("CONNECT"), proxy.requests.map { it.method })
                    assertEquals(0, upstream.requests.size)
                }
            }
        }
    }

    @Test
    fun aRealServiceWithACertificateThatIsTrustedIsAsked() {
        StubUpstream(StubTls.server).use { upstream ->
            JdkUpstreamClient(sslContext = StubTls.trusting).use { client ->
                val byAddress = client.get("${upstream.origin}/orders")
                val byName = client.get("https://localhost:${upstream.port}/orders")

                assertEquals(listOf(200, 200), listOf(byAddress.status, byName.status))
                assertEquals(listOf("/orders", "/orders"), upstream.requests.map { it.target })
            }
        }
    }

    @Test
    fun aRealServiceThatDoesNotAnswerInTimeIsAFailure() {
        val slow = { _: ReceivedRequest ->
            Thread.sleep(SLOW_MILLIS)
            StubResponse(200, "late", text)
        }
        StubUpstream(respond = slow).use { upstream ->
            JdkUpstreamClient().use { client ->
                val request = UpstreamRequest("GET", "${upstream.origin}/slow?token=1")
                val start = System.nanoTime()

                val failure =
                    assertFailsWith<UpstreamException> { runBlocking { client.forward(request, UpstreamSession(), 300.milliseconds) } }

                val waited = millisSince(start)
                assertEquals(
                    "The real service at ${upstream.origin}/slow gave no complete answer within 300 milliseconds.",
                    failure.message,
                )
                assertTrue(waited in ALMOST_300_MILLIS until SLOW_MILLIS, "waited $waited ms")
                assertEquals(listOf("/slow?token=1"), upstream.requests.map { it.target })
            }
        }
    }

    @Test
    fun anAnswerThatComesInTimeIsReturned() {
        StubUpstream().use { upstream ->
            JdkUpstreamClient(connectTimeout = 5.seconds).use { client ->
                val answer =
                    runBlocking { client.forward(UpstreamRequest("GET", "${upstream.origin}/orders"), UpstreamSession(), 5.seconds) }

                assertEquals(200 to "ok", answer.status to answer.bodyText)
            }
        }
    }

    /**
     * The stub takes the connection and stays silent, so the handshake of the secure connection never ends.
     * The connect timeout covers that handshake.
     */
    @Test
    fun aConnectionThatDoesNotComeAboutInTimeIsAFailure() {
        StubSilence().use { silence ->
            JdkUpstreamClient(connectTimeout = 300.milliseconds, sslContext = StubTls.trusting).use { client ->
                val start = System.nanoTime()

                val failure = assertFailsWith<UpstreamException> { client.get("https://127.0.0.1:${silence.port}/orders?token=1") }

                val waited = millisSince(start)
                assertEquals(
                    "The real service at https://127.0.0.1:${silence.port}/orders gave no complete answer: " +
                        "no connection was made within 300 milliseconds.",
                    failure.message,
                )
                assertTrue(waited in ALMOST_300_MILLIS until SLOW_MILLIS, "waited $waited ms")
            }
        }
    }

    @Test
    fun aClientWithoutAConnectTimeoutWaitsForTheTimeOfTheAnswer() {
        StubSilence().use { silence ->
            JdkUpstreamClient(connectTimeout = Duration.INFINITE, sslContext = StubTls.trusting).use { client ->
                val request = UpstreamRequest("GET", "https://127.0.0.1:${silence.port}/orders")

                val failure =
                    assertFailsWith<UpstreamException> { runBlocking { client.forward(request, UpstreamSession(), 300.milliseconds) } }

                assertEquals(
                    "The real service at https://127.0.0.1:${silence.port}/orders gave no complete answer within 300 milliseconds.",
                    failure.message,
                )
            }
        }
    }

    @Test
    fun aConnectTimeoutIsATimeThatPasses() {
        assertFailsWith<IllegalArgumentException> { JdkUpstreamClient(connectTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { JdkUpstreamClient(connectTimeout = (-1).seconds) }
    }

    @Test
    fun aFailureStatesATimeInSecondsOrInMilliseconds() {
        assertEquals("10 seconds", inWords(10.seconds))
        assertEquals("1 second", inWords(1.seconds))
        assertEquals("60 seconds", inWords(DEFAULT_RESPONSE_TIMEOUT))
        assertEquals("1500 milliseconds", inWords(1500.milliseconds))
        assertEquals("300 milliseconds", inWords(300.milliseconds))
    }

    private companion object {
        /** What a failure says about a certificate whose issuer the trust store of the runtime does not know. */
        const val TRUST_STORE_HINT =
            "The Java runtime does not trust the issuer of the certificate. If the issuer is the certificate authority of a " +
                "company, or a proxy that inspects TLS traffic, the trust store of the runtime needs the root certificate of that issuer."

        const val SLOW_MILLIS = 5_000L

        /** A wait of 300 milliseconds as a clock with coarse steps may measure it. */
        const val ALMOST_300_MILLIS = 250L
    }
}
