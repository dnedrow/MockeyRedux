package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The forwarding of a request through an upstream client that answers without a network, in the time of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardTest {
    private val start = UpstreamRequest("GET", "http://api.example.com/start?token=1")

    /** A client that takes [time] for every answer, and answers a request for `/start` with a redirect to `/final`. */
    private class RedirectingClient(
        private val time: Duration,
    ) : UpstreamClient {
        val requests = mutableListOf<UpstreamRequest>()

        override suspend fun send(request: UpstreamRequest): MockResponse {
            requests += request
            delay(time)
            val redirect = MockResponse(302, listOf(Header("Location", "/final"), Header("Set-Cookie", "sid=abc; Path=/")))
            return if (request.url.substringBefore('?').endsWith("/start")) redirect else MockResponse(200)
        }
    }

    /** A client that never answers, and that knows when its exchange was ended. */
    private class SilentClient : UpstreamClient {
        var ended = false

        override suspend fun send(request: UpstreamRequest): MockResponse =
            try {
                awaitCancellation()
            } finally {
                ended = true
            }
    }

    @Test
    fun anAnswerThatDoesNotComeWithinSixtySecondsIsAFailureUnlessAnotherTimeIsSet() =
        runTest {
            val client = SilentClient()

            val failure = assertFailsWith<UpstreamException> { client.forward(start, UpstreamSession()) }

            assertEquals("The real service at http://api.example.com/start gave no complete answer within 60 seconds.", failure.message)
            assertEquals(60_000, currentTime)
            assertTrue(client.ended)
        }

    @Test
    fun theTimeForAnAnswerCountsFromTheStartAcrossRedirects() =
        runTest {
            val client = RedirectingClient(time = 2.seconds)

            val failure = assertFailsWith<UpstreamException> { client.forward(start, UpstreamSession(), responseTimeout = 3.seconds) }

            assertEquals("The real service at http://api.example.com/start gave no complete answer within 3 seconds.", failure.message)
            assertEquals(3_000, currentTime)
            assertEquals(listOf("http://api.example.com/start?token=1", "http://api.example.com/final"), client.requests.map { it.url })
        }

    @Test
    fun anAnswerThatComesWithinTheTimeIsReturned() =
        runTest {
            val client = RedirectingClient(time = 2.seconds)

            val answer = client.forward(start, UpstreamSession(), responseTimeout = 5.seconds)

            assertEquals(200, answer.status)
            assertEquals(4_000, currentTime)
        }

    @Test
    fun aCallerThatGivesUpIsNoFailureOfTheRealService() =
        runTest {
            val client = SilentClient()
            var outcome: Throwable? = null
            val forwarding =
                launch {
                    outcome = runCatching { client.forward(start, UpstreamSession()) }.exceptionOrNull()
                }
            runCurrent()

            forwarding.cancelAndJoin()

            assertTrue(outcome is CancellationException)
            assertTrue(client.ended)
            assertEquals(0, currentTime)
        }

    @Test
    fun aFailureOfTheClientIsPassedOnWithItsMessage() =
        runTest {
            val broken = UpstreamException("The real service at http://api.example.com/start gave no complete answer: it broke off.")
            val client =
                object : UpstreamClient {
                    override suspend fun send(request: UpstreamRequest): MockResponse = throw broken
                }

            val failure = assertFailsWith<UpstreamException> { client.forward(start, UpstreamSession()) }

            assertEquals(broken.message, failure.message)
            assertEquals(0, currentTime)
        }

    @Test
    fun theRequestThatFollowsARedirectIsTheSameRequestForAnotherUrl() =
        runTest {
            val client = RedirectingClient(time = Duration.ZERO)
            val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("jane", "s3cret"))
            val headers = listOf(Header("X-Request-Id", "7"))
            val request = UpstreamRequest("GET", "http://api.example.com/start", headers, "asked".toByteArray(), proxy)

            client.forward(request, UpstreamSession())

            val (first, second) = client.requests
            assertEquals("http://api.example.com/start" to "http://api.example.com/final", first.url to second.url)
            assertEquals(listOf("GET", "GET"), listOf(first.method, second.method))
            assertEquals(headers to headers + Header("Cookie", "sid=abc"), first.headers to second.headers)
            assertContentEquals("asked".toByteArray(), second.body)
            assertEquals(listOf(proxy, proxy), listOf(first.outboundProxy, second.outboundProxy))
        }

    @Test
    fun aConnectionHasTenSecondsAndAnAnswerSixtyUnlessOtherTimesAreSet() {
        assertEquals(10.seconds, DEFAULT_CONNECT_TIMEOUT)
        assertEquals(60.seconds, DEFAULT_RESPONSE_TIMEOUT)
    }
}
