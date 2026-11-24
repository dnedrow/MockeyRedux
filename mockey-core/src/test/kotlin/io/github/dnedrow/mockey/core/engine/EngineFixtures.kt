package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.history.NOON
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** A clock that tells the time that a test gives it. */
internal class TestClock(
    @Volatile var now: Instant = NOON,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * An upstream client that answers without a network: with what [answer] says to a request. It keeps the
 * requests that it was sent, and it knows whether it was closed.
 */
internal class FakeUpstream(
    private val answer: suspend (UpstreamRequest) -> MockResponse = { MockResponse(200) },
) : UpstreamClient {
    val requests = CopyOnWriteArrayList<UpstreamRequest>()

    @Volatile
    var closed = false
        private set

    /** The URLs of the requests that the client was sent, in their order. */
    val urls: List<String>
        get() = requests.map { it.url }

    override suspend fun send(request: UpstreamRequest): MockResponse {
        requests += request
        return answer(request)
    }

    override fun close() {
        closed = true
    }
}

/** An upstream client for a test in which no request is to reach a real service: it fails the test when one does. */
internal fun noUpstream(): FakeUpstream =
    FakeUpstream {
        throw AssertionError("No request was to go to a real service, but one went to ${it.url}.")
    }

/**
 * Returns an engine for a test, with [definitions]. Unless [configure] says otherwise, the engine has no
 * inspectors, its clock stands at noon, and its upstream client fails the test when a request reaches it.
 */
internal fun engineOf(
    definitions: Definitions = Definitions.EMPTY,
    configure: EngineOptions.() -> Unit = {},
): MockeyEngine {
    val options = EngineOptions()
    options.inspectors = emptyList()
    options.clock = TestClock()
    options.upstreamClient = noUpstream()
    options.configure()
    return MockeyEngine(options, definitions)
}
