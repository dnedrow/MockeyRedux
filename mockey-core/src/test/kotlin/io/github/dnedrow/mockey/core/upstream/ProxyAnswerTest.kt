package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a request in proxy mode is answered: by the real service, or after an upstream failure, and after the
 * delay of the service. The upstream clients of these tests answer without a network, in the time of the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyAnswerTest {
    private val url = "http://api.example.com/orders"
    private val refused = "The real service at http://api.example.com/orders gave no complete answer: no connection could be made."
    private val noProxy = "No outbound proxy is enabled."
    private val get = MockRequest("GET", "http://mockey.test:8080/service/$url")

    private val backendDown = scenario("backend down", "backend down", 503, listOf(Header("X-Error", "yes")))
    private val serviceError = scenario("service error", "service error", 500)
    private val universalError = scenario("universal error", "universal error", 503, listOf(Header("Retry-After", "30")))

    /** The service that holds the universal error scenario. No request of these tests goes to it. */
    private val holder =
        Service(
            id = ServiceId.generate(),
            name = "universal error holder",
            mockUrl = "proxy/universal-holder",
            responseMode = ResponseMode.STATIC,
            scenarios = listOf(universalError),
            defaultScenarioId = universalError.id,
        )
    private val universal = ScenarioReference(holder.id, universalError.id)

    private fun scenario(
        name: String,
        body: String,
        status: Int = 200,
        headers: List<Header> = emptyList(),
        delayMillis: Long = 0,
    ) = Scenario(
        id = ScenarioId.generate(),
        name = name,
        responseBody = body,
        statusCode = status,
        headers = headers,
        delayMillis = delayMillis,
    )

    /** A service in proxy mode for the URL of these tests, with [error] as its error scenario, if it has one. */
    private fun service(
        error: Scenario? = null,
        delayMillis: Long = 0,
    ) = Service(
        id = ServiceId.generate(),
        name = "Orders",
        mockUrl = url,
        realUrls = listOf(url),
        delayMillis = delayMillis,
        scenarios = listOfNotNull(error),
        errorScenarioId = error?.id,
    )

    /** A client that takes [time] and then answers with [answer], or fails with [failure], and that keeps what it was asked. */
    private class FakeClient(
        private val answer: MockResponse = MockResponse(200),
        private val failure: Exception? = null,
        private val time: Duration = Duration.ZERO,
    ) : UpstreamClient {
        val requests = mutableListOf<UpstreamRequest>()

        override suspend fun send(request: UpstreamRequest): MockResponse {
            requests += request
            delay(time)
            failure?.let { throw it }
            return answer
        }
    }

    private fun failing(message: String = refused) = FakeClient(failure = UpstreamException(message))

    /** Returns how [service] answers a request in proxy mode through [client], with these settings. */
    private suspend fun answer(
        service: Service,
        client: UpstreamClient,
        settings: Settings = Settings(),
        target: ForwardTarget = ForwardTarget.To(url),
        request: MockRequest = get,
    ): ProxyAnswer =
        Definitions(
            services = listOf(holder, service),
            settings = settings,
        ).proxyAnswer(service, request, target, client, UpstreamSession())

    private fun members(response: MockResponse): Map<String, String> =
        Json.parseToJsonElement(response.bodyText).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    @Test
    fun theAnswerOfTheRealServiceIsRelayed() =
        runTest {
            val body = """{"error":"not found"}""".toByteArray()
            val came =
                listOf(
                    Header("content-type", "application/json"),
                    Header("connection", "keep-alive"),
                    Header("set-cookie", "sid=abc; Path=/; HttpOnly"),
                )
            val client = FakeClient(MockResponse(404, came, body))
            val sent = listOf(Header("Host", "mockey.test:8080"), Header("Cookie", "local=1"), Header("X-Request-Id", "7"))
            val request = MockRequest("POST", "http://mockey.test:8080/service/$url", "page=2", sent, "posted".toByteArray())

            val answer =
                answer(service(backendDown), client, Settings(universalErrorScenario = universal), ForwardTarget.To("$url?page=2"), request)

            val response = assertIs<ProxyAnswer.Proxied>(answer).response()
            val asked = client.requests.single()
            assertEquals(404, response.status)
            assertContentEquals(body, response.body)
            assertEquals(
                listOf(
                    Header("content-type", "application/json"),
                    Header("set-cookie", "sid=abc; Path=/; HttpOnly"),
                    Header("Content-Length", "21"),
                ),
                response.headers,
            )
            assertEquals("POST" to "$url?page=2", asked.method to asked.url)
            assertEquals(listOf(Header("X-Request-Id", "7")), asked.headers)
            assertContentEquals("posted".toByteArray(), asked.body)
            assertEquals(null, asked.outboundProxy)
        }

    @Test
    fun theErrorScenarioOfTheServiceAnswersForAnUpstreamFailure() =
        runTest {
            val answer = answer(service(backendDown), failing())

            val response = answer.response()
            assertEquals(ProxyAnswer.UpstreamFailed(refused, backendDown, noProxy), answer)
            assertEquals(503 to "backend down", response.status to response.bodyText)
            assertEquals(listOf(Header("X-Error", "yes")), response.headers)
        }

    @Test
    fun theUniversalErrorScenarioAnswersForAServiceWithoutAnErrorScenario() =
        runTest {
            val answer = answer(service(error = null), failing(), Settings(universalErrorScenario = universal))

            val response = answer.response()
            assertEquals(ProxyAnswer.UpstreamFailed(refused, universalError, noProxy), answer)
            assertEquals(503 to "universal error", response.status to response.bodyText)
            assertEquals(listOf(Header("Retry-After", "30")), response.headers)
        }

    @Test
    fun theErrorScenarioOfTheServiceComesBeforeTheUniversalOne() =
        runTest {
            val answer = answer(service(serviceError), failing(), Settings(universalErrorScenario = universal))

            val response = answer.response()
            assertEquals(ProxyAnswer.UpstreamFailed(refused, serviceError, noProxy), answer)
            assertEquals(500 to "service error", response.status to response.bodyText)
            assertEquals(emptyList(), response.headers)
        }

    @Test
    fun withoutAnErrorScenarioAnUpstreamFailureGetsTheStatus502AndAJsonObject() =
        runTest {
            val answer = answer(service(error = null), failing())

            val response = answer.response()
            assertEquals(ProxyAnswer.UpstreamFailed(refused, scenario = null, proxyInfo = noProxy), answer)
            assertEquals(502, response.status)
            assertEquals(listOf(Header("Content-Type", "application/json")), response.headers)
            assertEquals(mapOf("fail" to refused, "proxyInfo" to noProxy), members(response))
        }

    @Test
    fun theUniversalErrorScenarioAnswersForARequestThatWasForwardedWithoutAService() =
        runTest {
            val definitions =
                Definitions(services = listOf(holder, service(backendDown)), settings = Settings(universalErrorScenario = universal))

            val answer = definitions.proxyAnswer(service = null, get, ForwardTarget.To(url), failing(), UpstreamSession())

            assertEquals(ProxyAnswer.UpstreamFailed(refused, universalError, noProxy), answer)
        }

    @Test
    fun aRequestThatWasForwardedWithoutAServiceGetsTheStatus502WhenThereIsNoUniversalErrorScenario() =
        runTest {
            val definitions = Definitions(services = listOf(holder, service(backendDown)))

            val answer = definitions.proxyAnswer(service = null, get, ForwardTarget.To(url), failing(), UpstreamSession())

            assertEquals(ProxyAnswer.UpstreamFailed(refused, scenario = null, proxyInfo = noProxy), answer)
            assertEquals(502, answer.response().status)
        }

    @Test
    fun aRequestThatWasForwardedWithoutAServiceWaitsForNoDelay() =
        runTest {
            val definitions = Definitions(services = listOf(holder, service(delayMillis = 300)))
            val client = FakeClient(MockResponse(200, body = "real".toByteArray()))

            val answer = definitions.proxyAnswer(service = null, get, ForwardTarget.To(url), client, UpstreamSession())

            assertEquals("real", assertIs<ProxyAnswer.Proxied>(answer).response().bodyText)
            assertEquals(0, currentTime)
        }

    @Test
    fun theDescriptionOfAFailureIsWrittenAsJsonText() =
        runTest {
            val description = "The real service at $url gave no complete answer: it said \"caf\u00e9\\\" and broke off.\n"

            val response = answer(service(error = null), failing(description)).response()

            assertEquals(description, members(response)["fail"])
            assertTrue("caf\u00e9".toByteArray(Charsets.UTF_8).toList() in response.body.toList().windowed(5))
        }

    @Test
    fun everyKindOfUpstreamFailureIsAnsweredInThePlaceOfTheRealService() =
        runTest {
            val redirecting = FakeClient(MockResponse(302, listOf(Header("Location", "/orders"))))
            val silent =
                object : UpstreamClient {
                    override suspend fun send(request: UpstreamRequest): MockResponse = awaitCancellation()
                }
            val service = "The real service at $url"
            val kinds =
                listOf(
                    failing("$service gave no complete answer: no connection could be made.") to "no connection could be made.",
                    failing("$service gave no complete answer: its certificate was not accepted.") to "its certificate was not accepted.",
                    failing("$service gave no complete answer: no connection was made within 10 seconds.") to "within 10 seconds.",
                    silent to "$service gave no complete answer within 60 seconds.",
                    redirecting to "$service redirects without end: 20 redirects in a row led to no answer.",
                    FakeClient(failure = IOException("The connection was reset")) to "The connection was reset",
                    FakeClient(failure = IOException()) to "$service gave no complete answer: IOException.",
                )

            val answers = kinds.map { (client, _) -> answer(service(backendDown), client) }

            assertEquals(kinds.map { backendDown }, answers.map { assertIs<ProxyAnswer.UpstreamFailed>(it).scenario })
            kinds.zip(answers).forEach { (kind, answer) ->
                assertTrue((answer as ProxyAnswer.UpstreamFailed).failure.endsWith(kind.second), kind.second)
            }
            assertEquals(kinds.map { 503 }, answers.map { it.response().status })
        }

    @Test
    fun theJsonObjectSaysWhetherAnOutboundProxyIsEnabledAndNamesItsHost() =
        runTest {
            val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("jane", "s3cret"))
            val enabled = failing()
            val disabled = failing()
            val withoutHost = failing()

            val throughProxy = answer(service(), enabled, Settings(outboundProxy = proxy)).response()
            val direct = answer(service(), disabled, Settings(outboundProxy = proxy.copy(enabled = false))).response()
            val unusable = answer(service(), withoutHost, Settings(outboundProxy = proxy.copy(url = ""))).response()

            assertEquals("An outbound proxy is enabled. Its host is proxy.example.com.", members(throughProxy)["proxyInfo"])
            assertEquals(noProxy, members(direct)["proxyInfo"])
            assertEquals("An outbound proxy is enabled, but its URL names no host.", members(unusable)["proxyInfo"])
            assertEquals(listOf(proxy, null), listOf(enabled.requests.single().outboundProxy, disabled.requests.single().outboundProxy))
            assertFalse(listOf(throughProxy, direct, unusable).any { "s3cret" in it.bodyText || "jane" in it.bodyText })
        }

    @Test
    fun aRequestWithNowhereToGoGetsTheStatus502AndAnExplanationAndNoErrorScenarioAnswers() =
        runTest {
            val explanation = "The service 'Orders' cannot forward the request for 'orders': the service has no real URL."
            val client = FakeClient()

            val answer =
                answer(service(backendDown), client, Settings(universalErrorScenario = universal), ForwardTarget.Nowhere(explanation))

            val response = answer.response()
            assertEquals(ProxyAnswer.NotForwarded(explanation), answer)
            assertEquals(502 to explanation, response.status to response.bodyText)
            assertEquals(listOf(Header("Content-Type", "text/plain; charset=utf-8")), response.headers)
            assertEquals(0, client.requests.size)
        }

    @Test
    fun aProxiedAnswerWaitsForTheDelayOfTheService() =
        runTest {
            val atOnce = answer(service(delayMillis = 300), FakeClient())
            val afterAnswer = currentTime
            answer(service(delayMillis = 300), FakeClient(time = 200.milliseconds))
            val afterSlowAnswer = currentTime - afterAnswer
            answer(service(delayMillis = 0), FakeClient())

            assertIs<ProxyAnswer.Proxied>(atOnce)
            assertEquals(300, afterAnswer)
            assertEquals(500, afterSlowAnswer)
            assertEquals(800, currentTime)
        }

    @Test
    fun anUpstreamFailureWaitsForTheDelayOfTheService() =
        runTest {
            val answer = answer(service(delayMillis = 300), failing())

            assertIs<ProxyAnswer.UpstreamFailed>(answer)
            assertEquals(300, currentTime)
        }

    @Test
    fun anErrorScenarioThatAnswersForAFailureWaitsForTheDelayOfTheServiceAndNotForItsOwn() =
        runTest {
            val slowError = scenario("slow error", "backend down", 503, delayMillis = 5_000)

            val delayed = answer(service(slowError, delayMillis = 300), failing())
            val afterDelayed = currentTime
            val atOnce = answer(service(slowError, delayMillis = 0), failing())

            assertEquals(listOf(slowError, slowError), listOf(delayed, atOnce).map { assertIs<ProxyAnswer.UpstreamFailed>(it).scenario })
            assertEquals(300, afterDelayed)
            assertEquals(300, currentTime)
        }

    @Test
    fun anAnswerToARequestWithNowhereToGoWaitsForTheDelayOfTheService() =
        runTest {
            answer(service(delayMillis = 300), FakeClient(), target = ForwardTarget.Nowhere("The service has no real URL."))

            assertEquals(300, currentTime)
        }

    @Test
    fun requestsInProxyModeWaitAtTheSameTimeAndNotOneAfterTheOther() =
        runTest {
            val delayed = service(delayMillis = 5_000)
            val prompt = service(delayMillis = 0)

            val waiting = (1..3).map { async { answer(delayed, FakeClient()) } }
            answer(prompt, FakeClient())
            val afterPrompt = currentTime
            waiting.awaitAll()

            assertEquals(0, afterPrompt)
            assertEquals(5_000, currentTime)
        }

    @Test
    fun aCallerThatGivesUpGetsNoAnswerInThePlaceOfTheRealService() =
        runTest {
            var outcome: Result<ProxyAnswer>? = null
            val silent =
                object : UpstreamClient {
                    override suspend fun send(request: UpstreamRequest): MockResponse = awaitCancellation()
                }
            val handling = launch { outcome = runCatching { answer(service(backendDown), silent) } }
            runCurrent()

            handling.cancelAndJoin()

            assertTrue(outcome?.exceptionOrNull() is CancellationException)
        }

    @Test
    fun aFaultThatIsNoFailureOfTheRealServiceIsNotAnswered() =
        runTest {
            val broken = FakeClient(failure = IllegalStateException("The upstream client is closed."))

            val fault = assertFailsWith<IllegalStateException> { answer(service(backendDown), broken) }

            assertEquals("The upstream client is closed.", fault.message)
        }

    @Test
    fun aFailureShowsNeitherTheQueryStringNorTheCredentialsOfAUrl() =
        runTest {
            val secret = "http://jane:s3cret@api.example.com/orders?token=t0ken"
            val silent =
                object : UpstreamClient {
                    override suspend fun send(request: UpstreamRequest): MockResponse = awaitCancellation()
                }

            val response = answer(service(), silent, target = ForwardTarget.To(secret)).response()

            assertEquals(
                "The real service at http://api.example.com/orders gave no complete answer within 60 seconds.",
                members(response)["fail"],
            )
            assertEquals("http://api.example.com/orders", shown(secret))
            assertEquals("http://api.example.com/a@b", shown("http://api.example.com/a@b?c=d@e"))
            assertEquals("UpstreamRequest(GET http://api.example.com/orders)", UpstreamRequest("GET", secret).toString())
        }

    /**
     * The services of the parity fixtures `proxy-failure`, `proxy-universal-error`, and `proxy-forwarding`, with
     * the upstream client of the library, and the stated new responses of their recordings (DEV-13). Nothing
     * listens on port 1 of the loopback address.
     */
    @Test
    fun theFailureAndDelayRecordingsGoAsTheyState() {
        val withUniversal = Settings(universalErrorScenario = universal)
        val unreachable = Settings(outboundProxy = OutboundProxy("http://127.0.0.1:1", enabled = true))

        val closesOrLoops = { request: ReceivedRequest ->
            if (request.target == "/stub/close") null else StubResponse(302, "", "Location" to request.target)
        }
        StubUpstream(respond = closesOrLoops).use { upstream ->
            JdkUpstreamClient().use { client ->
                fun proxied(
                    service: Service,
                    url: String,
                    settings: Settings = Settings(),
                ): MockResponse =
                    runBlocking {
                        Definitions(services = listOf(holder, service), settings = settings)
                            .proxyAnswer(service, get, ForwardTarget.To(url), client, UpstreamSession())
                            .response()
                    }

                val builtIn =
                    mapOf(
                        "proxy-failure-refused" to proxied(service(), "http://127.0.0.1:1/refused"),
                        "proxy-failure-unknown-host" to proxied(service(), "http://unknown-host.invalid/anything"),
                        "proxy-failure-connection-closed" to proxied(service(), "${upstream.origin}/stub/close"),
                        "proxy-redirect-loop" to proxied(service(), "${upstream.origin}/stub/redirect/loop"),
                        "proxy-outbound-unreachable" to proxied(service(), "${upstream.origin}/stub/echo", unreachable),
                    )
                val withServiceError = proxied(service(backendDown), "http://127.0.0.1:1/refused-with-error-scenario")
                val serviceFirst = proxied(service(serviceError), "http://127.0.0.1:1/refused-service-first", withUniversal)
                val universalSecond = proxied(service(), "http://127.0.0.1:1/refused-universal", withUniversal)
                val start = System.nanoTime()
                val delayed = proxied(service(delayMillis = 300), "http://127.0.0.1:1/refused-delayed")
                val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

                builtIn.forEach { (recording, response) ->
                    assertEquals(502 to "application/json", response.status to response.contentType, recording)
                    assertEquals(setOf("fail", "proxyInfo"), members(response).keys, recording)
                    assertTrue(members(response).getValue("fail").startsWith("The real service at "), recording)
                }
                assertTrue("127.0.0.1" in members(builtIn.getValue("proxy-outbound-unreachable")).getValue("proxyInfo"))
                assertEquals(Triple(503, listOf(Header("X-Error", "yes")), "backend down"), withServiceError.parts())
                assertEquals(Triple(500, emptyList(), "service error"), serviceFirst.parts())
                assertEquals(Triple(503, listOf(Header("Retry-After", "30")), "universal error"), universalSecond.parts())
                assertEquals(502, delayed.status)
                assertTrue(waited >= 300, "waited $waited ms")
            }
        }
    }

    /** The recording `proxy-delay-service`: the answer of a real service waits for the delay of the service. */
    @Test
    fun theAnswerOfARealServiceWaitsForTheDelayOfTheServiceAsTheRecordingStates() {
        StubUpstream().use { upstream ->
            JdkUpstreamClient().use { client ->
                val delayed = service(delayMillis = 300)
                val start = System.nanoTime()

                val answer =
                    runBlocking {
                        Definitions(services = listOf(delayed))
                            .proxyAnswer(delayed, get, ForwardTarget.To("${upstream.origin}/stub/echo/delayed"), client, UpstreamSession())
                    }

                val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertEquals(200 to "ok", answer.response().let { it.status to it.bodyText })
                assertEquals(listOf("/stub/echo/delayed"), upstream.requests.map { it.target })
                assertTrue(waited >= 300, "waited $waited ms")
            }
        }
    }

    private fun MockResponse.parts(): Triple<Int, List<Header>, String> = Triple(status, headers, bodyText)
}
