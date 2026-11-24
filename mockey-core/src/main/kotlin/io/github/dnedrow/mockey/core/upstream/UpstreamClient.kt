package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.response.MockResponse
import java.io.IOException

/**
 * Sends requests to real services. An engine forwards every request of proxy mode through one upstream client.
 * The client that the library brings sends over HTTP, and a program can put its own in that place, such as
 * one that answers without a network.
 */
public interface UpstreamClient : AutoCloseable {
    /**
     * Sends [request] to the real service that its URL names, and returns the answer of that service as it
     * came, whatever its status is.
     *
     * @throws UpstreamException when the real service gives no complete answer
     */
    public suspend fun send(request: UpstreamRequest): MockResponse

    /** Releases what the client holds, such as its connections. The client sends no request after that. */
    override fun close() {}
}

/**
 * A request for a real service.
 *
 * [method] is the HTTP method, and [url] is the URL of the real service with its query string, such as
 * `http://api.example.com/customers/7?expand=orders`.
 *
 * The request takes over the array of its body. Whoever creates a request must not change that array
 * afterwards.
 */
public class UpstreamRequest
    @JvmOverloads
    constructor(
        public val method: String,
        public val url: String,
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ) {
        /** The headers to send, in their order. A name can occur more than once. */
        public val headers: List<Header> = headers.toList()

        private val bodyBytes: ByteArray = body

        /** The body to send, which is empty for a request without one. Every read gives a new array. */
        public val body: ByteArray
            get() = bodyBytes.copyOf()

        /** Returns the method and the URL without its query string. The rest is left out, because it can hold secrets. */
        override fun toString(): String = "UpstreamRequest($method ${url.substringBefore('?')})"
    }

/**
 * Tells that a real service gave no complete answer to a request: it could not be reached, its certificate was
 * not accepted, it took too long, or its answer broke off. The message says what happened, in words for
 * whoever sent the request.
 */
public class UpstreamException
    @JvmOverloads
    constructor(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)
