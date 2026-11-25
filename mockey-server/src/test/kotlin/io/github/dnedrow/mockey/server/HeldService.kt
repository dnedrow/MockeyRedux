package io.github.dnedrow.mockey.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A real service that keeps the answer to every request until the test lets it go. A request that a server
 * forwards to it is in progress for as long as the test likes.
 */
internal class HeldService : AutoCloseable {
    private val held = CountDownLatch(1)
    private val stub = StubUpstream(body = ANSWER, beforeAnswer = { held.await() })

    /** The target of the mock endpoint that a server forwards to this service. */
    val target: String = "/service/${stub.origin}/held"

    /** Waits until a request has arrived, which is in progress from then on. */
    fun awaitRequest() {
        awaitRequests(1)
    }

    /** Waits until [count] requests have arrived, which are in progress from then on. */
    fun awaitRequests(count: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (stub.requests.size < count) {
            check(System.nanoTime() < end) { "Of $count requests, ${stub.requests.size} arrived at the real service." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Lets the service answer. */
    fun release() {
        held.countDown()
    }

    override fun close() {
        release()
        stub.close()
    }

    internal companion object {
        /** The body of the answer of the service. */
        const val ANSWER = "answered by the service that was held"
        private const val WAIT_SECONDS = 10L
        private const val POLL_MILLIS = 5L
    }
}
