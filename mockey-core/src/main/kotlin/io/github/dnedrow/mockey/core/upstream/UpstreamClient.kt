package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.response.MockResponse
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sends requests to real services. An engine forwards every request of proxy mode through one upstream client.
 * The client that the library brings sends over HTTP, see [overHttp], and a program can put its own in that
 * place, such as one that answers without a network.
 *
 * A client stands for one exchange at a time: it sends a request as it is given, and returns the answer as it
 * came. It follows no redirect and keeps no cookie. The engine does both, in the same way for every client.
 */
public interface UpstreamClient : AutoCloseable {
    /**
     * Sends [request] to the real service that its URL names, and returns the answer of that service as it
     * came, whatever its status is. A request that names an outbound proxy goes through that proxy.
     *
     * @throws UpstreamException when the real service gives no complete answer
     */
    public suspend fun send(request: UpstreamRequest): MockResponse

    /** Releases what the client holds, such as its connections. The client sends no request after that. */
    override fun close() {}

    public companion object {
        /**
         * Returns the upstream client that the library brings, which sends over HTTP. A connection to a real
         * service has to come about within [connectTimeout].
         *
         * An engine that is given no client makes this client for itself. A program asks for it to put a
         * client of its own around it: one that hands every request on to this client, and that looks at
         * the requests or changes them on their way.
         */
        public fun overHttp(connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT): UpstreamClient = JdkUpstreamClient(connectTimeout)
    }
}

/**
 * A request for a real service.
 *
 * [method] is the HTTP method, and [url] is the URL of the real service with its query string, such as
 * `http://api.example.com/customers/7?expand=orders`.
 *
 * [outboundProxy] is the proxy that the request goes through, with the credentials for that proxy when it
 * has some, and `null` for a request that goes to the real service directly. Its `enabled` flag has no say
 * here: an engine names the proxy of its settings only while that proxy is enabled.
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
        public val outboundProxy: OutboundProxy? = null,
    ) {
        /** The headers to send, in their order. A name can occur more than once. */
        public val headers: List<Header> = headers.toList()

        private val bodyBytes: ByteArray = body

        /** The body to send, which is empty for a request without one. Every read gives a new array. */
        public val body: ByteArray
            get() = bodyBytes.copyOf()

        /**
         * Returns the URL as a URI, which tells its parts, such as its host and its port. The client that the
         * library brings sends the request to this URI.
         *
         * `java.net.URI` refuses some characters that the URL of a request can hold, such as a pipe sign or a
         * brace. The URI has them escaped, and everything else as [url] has it.
         *
         * @throws URISyntaxException when [url] is no URL, also with those characters escaped
         */
        public fun uri(): URI = uriOf(url)

        /** Returns this request for another URL or with other headers. */
        internal fun copy(
            url: String = this.url,
            headers: List<Header> = this.headers,
        ): UpstreamRequest = UpstreamRequest(method, url, headers, bodyBytes, outboundProxy)

        /**
         * Returns the method and the URL without its query string and without a user name and a password. The
         * rest is left out as well, because it can hold secrets.
         */
        override fun toString(): String = "UpstreamRequest($method ${shown(url)})"
    }

/**
 * Tells that a real service gave no complete answer to a request: it could not be reached, its certificate was
 * not accepted, it took too long, its answer broke off, or the outbound proxy did not let the request
 * through. The message says what happened, in words for whoever sent the request.
 */
public class UpstreamException
    @JvmOverloads
    constructor(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

/** How long a connection to a real service may take to come about, where no other time is set. */
internal val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds

/** How long the complete answer to a forwarded request may take, where no other time is set. */
internal val DEFAULT_RESPONSE_TIMEOUT: Duration = 60.seconds

/** Returns what a failure message calls the real service of [request]: its URL as [shown]. */
internal fun whereOf(request: UpstreamRequest): String = "The real service at ${shown(request.url)}"

/** The user name and the password that a URL holds before its host, with the sign that ends them. */
private val USER_INFO = Regex("(?<=://)[^/?#@]*@")

/**
 * Returns [url] as a message shows it: without its query string, and without a user name and a password
 * before its host. Both can hold secrets, and a message can reach a client or a log.
 */
internal fun shown(url: String): String = url.substringBefore('?').replaceFirst(USER_INFO, "")

private const val MILLIS_IN_SECOND = 1000L

/** Returns [duration] as a failure message states it: in seconds where it is a whole number of them, and in milliseconds otherwise. */
internal fun inWords(duration: Duration): String {
    val millis = duration.inWholeMilliseconds
    return when {
        millis % MILLIS_IN_SECOND != 0L -> "$millis milliseconds"
        millis == MILLIS_IN_SECOND -> "1 second"
        else -> "${millis / MILLIS_IN_SECOND} seconds"
    }
}
