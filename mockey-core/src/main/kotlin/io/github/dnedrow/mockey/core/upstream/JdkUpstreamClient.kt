package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.definitions.ProxyAddress
import io.github.dnedrow.mockey.core.definitions.proxyAddressOf
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The upstream client that the library brings. It sends requests with the HTTP client of the JDK, over
 * HTTP/1.1. `docs/decisions/upstream-client.md` says why, and what follows from it.
 *
 * The client sends a request as it is given: the method, the URL, the headers, and the body byte for byte.
 * The JDK client sets `Host` and `Content-Length` to fit, and it has a few ways of its own:
 *
 * - It adds a `User-Agent` to a request that has none.
 * - It does not let a request carry `Expect`, so that header is left out. The same goes for a header whose
 *   name or value it does not accept.
 * - It gives the names of the headers of an answer in lower case and in alphabetical order. The values of one
 *   name keep their order.
 *
 * A request that names an outbound proxy goes through that proxy: a request for an `http` URL is handed to
 * the proxy, and a request for an `https` URL goes through a tunnel that the proxy opens. The credentials of
 * the proxy go to the proxy with every such request, for the scheme Basic, and never to a real service. For a
 * tunnel, the JDK sends them only when the system property `jdk.http.auth.tunneling.disabledSchemes` allows
 * the scheme Basic. The client sets that property to nothing when it is created, unless the property is set.
 * The JDK reads the property once, when any part of its HTTP client is first used. A program that uses the
 * HTTP client of the JDK elsewhere therefore creates this client first, or sets the property itself.
 *
 * A connection has to come about within [connectTimeout], which covers the handshake of a secure connection.
 * [sslContext] is what the client trusts the certificates of real services with. Without one, that is the
 * trust store of the runtime.
 */
internal class JdkUpstreamClient(
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val sslContext: SSLContext? = null,
) : UpstreamClient {
    init {
        require(connectTimeout.isPositive()) { "The connect timeout must be a time that passes." }
        allowBasicCredentialsForTunnels()
    }

    @Volatile
    private var closed = false

    /** The JDK client for direct connections. The first request that needs it creates it, so a client that never sends starts no thread. */
    private var direct: HttpClient? = null

    /** The JDK client for the outbound proxy that was last used, with the address of that proxy. */
    private var throughProxy: Pair<ProxyAddress, HttpClient>? = null

    override suspend fun send(request: UpstreamRequest): MockResponse {
        val proxy = request.outboundProxy
        val address = proxy?.let { addressOf(it, request) }
        val exchange = jdkRequest(request, proxy?.credentials)
        val response =
            try {
                clientFor(address).sendAsync(exchange, HttpResponse.BodyHandlers.ofByteArray()).awaitOrCancel()
            } catch (e: IOException) {
                throw UpstreamException("${whereOf(request)} gave no complete answer: ${reasonOf(e, address)}", e)
            }
        if (address != null && response.statusCode() == PROXY_AUTHENTICATION_REQUIRED) throw refused(request, address, proxy.credentials)
        val headers = response.headers().map().flatMap { (name, values) -> values.map { Header(name, it) } }
        return MockResponse(response.statusCode(), headers, response.body() ?: ByteArray(0))
    }

    @Synchronized
    override fun close() {
        closed = true
        direct?.shutdownNow()
        throughProxy?.second?.shutdownNow()
    }

    /**
     * Returns the JDK client for requests that go through the proxy at [proxy], or directly for `null`. One
     * client serves all direct requests, and one serves the proxy that is in use. When another proxy comes into
     * use, the client for the proxy before it finishes the requests that it has and then ends.
     */
    @Synchronized
    private fun clientFor(proxy: ProxyAddress?): HttpClient {
        check(!closed) { "The upstream client is closed." }
        if (proxy == null) return direct ?: jdkClient(null).also { direct = it }
        val current = throughProxy
        if (current != null && current.first == proxy) return current.second
        current?.second?.shutdown()
        return jdkClient(proxy).also { throughProxy = proxy to it }
    }

    private fun jdkClient(proxy: ProxyAddress?): HttpClient {
        val builder =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(proxy?.let { ProxySelector.of(InetSocketAddress.createUnresolved(it.host, it.port)) } ?: HttpClient.Builder.NO_PROXY)
        if (connectTimeout.isFinite()) builder.connectTimeout(connectTimeout.toJavaDuration())
        sslContext?.let(builder::sslContext)
        return builder.build()
    }

    /** Returns where the outbound proxy of [request] listens, or fails when its URL does not say. */
    private fun addressOf(
        proxy: OutboundProxy,
        request: UpstreamRequest,
    ): ProxyAddress =
        try {
            proxyAddressOf(proxy.url)
        } catch (e: IllegalArgumentException) {
            throw UpstreamException("${whereOf(request)} cannot be asked: ${e.message.orEmpty().replaceFirstChar(Char::lowercase)}", e)
        }

    /**
     * Returns [request] as a request of the JDK client, or fails when the JDK client cannot send it.
     * [credentials] are those of the outbound proxy that the request goes through, if it has some.
     */
    private fun jdkRequest(
        request: UpstreamRequest,
        credentials: ProxyCredentials?,
    ): HttpRequest {
        val body = request.body
        return try {
            val builder = HttpRequest.newBuilder(request.uri())
            when {
                // Only a request that is built without a body publisher carries no Content-Length.
                body.isEmpty() && request.method == "GET" -> builder.GET()

                body.isEmpty() && request.method == "HEAD" -> builder.HEAD()

                body.isEmpty() && request.method == "DELETE" -> builder.DELETE()

                body.isEmpty() -> builder.method(request.method, HttpRequest.BodyPublishers.noBody())

                else -> builder.method(request.method, HttpRequest.BodyPublishers.ofByteArray(body))
            }
            headersWith(request.headers, credentials).forEach { builder.addIfAccepted(it) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw UpstreamException("${whereOf(request)} cannot be asked: ${e.message}", e)
        } catch (e: URISyntaxException) {
            throw UpstreamException("${whereOf(request)} cannot be asked: the URL is not valid. ${e.reason}", e)
        }
    }

    /** Adds [header] unless the JDK client refuses it, as it does for `Expect` and for a name or a value that is not valid. */
    private fun HttpRequest.Builder.addIfAccepted(header: Header) {
        try {
            header(header.name, header.value)
        } catch (_: IllegalArgumentException) {
            // The request is sent without this header.
        }
    }

    /** Returns why an exchange failed, in words. [proxy] is where the outbound proxy of the exchange listens, if it has one. */
    private fun reasonOf(
        failure: IOException,
        proxy: ProxyAddress?,
    ): String {
        val toProxy = proxy?.let { " to the outbound proxy at $it" }.orEmpty()
        val message = failure.message.orEmpty()
        return when {
            closed -> "the upstream client was closed."
            failure is HttpConnectTimeoutException -> "no connection was made$toProxy within ${inWords(connectTimeout)}."
            failure is ConnectException -> "no connection could be made$toProxy."
            failure is SSLException && failure.isAboutCertificate() -> "its certificate was not accepted. $message".trim()
            failure is SSLException -> "the secure connection could not be set up. $message".trim()
            else -> failure.message?.let { "$it." } ?: "${failure.javaClass.simpleName}."
        }
    }

    private companion object {
        const val PROXY_AUTHENTICATION_REQUIRED = 407
        const val PROXY_AUTHORIZATION = "Proxy-Authorization"
        const val TUNNELING_PROPERTY = "jdk.http.auth.tunneling.disabledSchemes"
        const val TUNNEL_HINT =
            "If they are right, start the Java runtime with -D$TUNNELING_PROPERTY= so that it sends them for an https URL."

        /**
         * Lets the JDK send Basic credentials with the request for a tunnel through a proxy. The JDK keeps them
         * out by default, and it reads its setting once, with the first use of any part of its HTTP client. A
         * value that the property has already is left as it is.
         */
        fun allowBasicCredentialsForTunnels() {
            if (System.getProperty(TUNNELING_PROPERTY) == null) System.setProperty(TUNNELING_PROPERTY, "")
        }

        /** Returns [headers] with [credentials] for an outbound proxy as their only `Proxy-Authorization` header, if there are some. */
        fun headersWith(
            headers: List<Header>,
            credentials: ProxyCredentials?,
        ): List<Header> {
            if (credentials == null) return headers
            val pair = "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8)
            val authorization = Header(PROXY_AUTHORIZATION, "Basic ${Base64.getEncoder().encodeToString(pair)}")
            return headers.filterNot { it.name.equals(PROXY_AUTHORIZATION, ignoreCase = true) } + authorization
        }

        /** Returns the failure for a request that the outbound proxy at [proxy] answered with the status 407 itself. */
        fun refused(
            request: UpstreamRequest,
            proxy: ProxyAddress,
            credentials: ProxyCredentials?,
        ): UpstreamException {
            val notAccepted = "did not accept the user name and the password that are set."
            val reason =
                when {
                    credentials == null -> "wants credentials, and none are set."
                    request.url.startsWith("https:", ignoreCase = true) -> "$notAccepted $TUNNEL_HINT"
                    else -> notAccepted
                }
            return UpstreamException("${whereOf(request)} gave no complete answer: the outbound proxy at $proxy $reason")
        }

        /** Returns whether a secure connection failed because of the certificate of the other side. */
        fun SSLException.isAboutCertificate(): Boolean = generateSequence<Throwable>(this) { it.cause }.any { it is CertificateException }
    }
}

/**
 * Returns [url] as a URI. [url] is a URL, or a reference to one as a `Location` header holds it, such as a
 * path. `java.net.URI` refuses some characters that the target of a request can hold, such as a pipe sign or
 * a brace. A URL with such characters is taken with those characters escaped, and every other URL as it is
 * written.
 */
internal fun uriOf(url: String): URI {
    val uri =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            URI(escaped(url))
        }
    return URI(uri.toASCIIString())
}

private const val HEX = 16
private const val ESCAPE_LENGTH = 3
private const val SCHEME_END = "://"

/** What a URL can hold as it is: the characters that RFC 3986 names, without the brackets of an IPv6 address. */
private const val URL_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#@!$&'()*+,;="

/**
 * Returns [url] with every character escaped that a URL cannot hold as it is, as the bytes of that character
 * in UTF-8. A percent sign stays where it starts an escape. What comes before the path stays as it is.
 */
private fun escaped(url: String): String {
    val pathStart = pathStartOf(url)
    val result = StringBuilder(url.substring(0, pathStart))
    var index = pathStart
    while (index < url.length) {
        val char = url[index]
        val startsEscape =
            char == '%' && index + ESCAPE_LENGTH <= url.length && url.substring(index + 1, index + ESCAPE_LENGTH).all(::isHexDigit)
        if (char in URL_CHARACTERS || startsEscape) {
            result.append(char)
            index++
        } else {
            val codePoint = url.codePointAt(index)
            String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).forEach { byte ->
                result.append('%').append((byte.toInt() and BYTE_MASK).toString(HEX).uppercase().padStart(2, '0'))
            }
            index += Character.charCount(codePoint)
        }
    }
    return result.toString()
}

/** Returns where the path of [url] starts: after the host of a URL that names one, and at the start of any other. */
private fun pathStartOf(url: String): Int {
    val delimiters = charArrayOf('/', '?', '#')
    val schemeEnd = url.indexOf(SCHEME_END)
    val hostStart =
        when {
            schemeEnd >= 0 && url.indexOfAny(delimiters) > schemeEnd -> schemeEnd + SCHEME_END.length
            url.startsWith("//") -> 2
            else -> return 0
        }
    return url.indexOfAny(delimiters, hostStart).let { if (it < 0) url.length else it }
}

private const val BYTE_MASK = 0xFF

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

/**
 * Waits for the value of the future and returns it. When the coroutine is canceled while it waits, the future
 * is canceled as well, which ends the exchange that it stands for.
 *
 * A future that someone else cancels fails the wait with an [IOException]. The coroutine that waits is not
 * canceled by that, so the cancellation of the future is not passed on as one of the coroutine.
 */
internal suspend fun <T> CompletableFuture<T>.awaitOrCancel(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            val cause = if (failure is CompletionException) failure.cause ?: failure else failure
            when (cause) {
                null -> continuation.resume(value)
                is CancellationException -> continuation.resumeWithException(IOException("the exchange was ended from outside", cause))
                else -> continuation.resumeWithException(cause)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
