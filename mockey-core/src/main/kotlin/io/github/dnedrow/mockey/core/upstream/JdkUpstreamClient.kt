package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 * [sslContext] is what the client trusts the certificates of real services with. Without one, that is the
 * trust store of the runtime.
 */
internal class JdkUpstreamClient(
    private val sslContext: SSLContext? = null,
) : UpstreamClient {
    /** The JDK client is created with the first request, so that a client that never sends starts no thread. */
    private val client: Lazy<HttpClient> =
        lazy {
            val builder =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(HttpClient.Builder.NO_PROXY)
            sslContext?.let(builder::sslContext)
            builder.build()
        }

    override suspend fun send(request: UpstreamRequest): MockResponse {
        val exchange = jdkRequest(request)
        val response =
            try {
                client.value.sendAsync(exchange, HttpResponse.BodyHandlers.ofByteArray()).awaitOrCancel()
            } catch (e: IOException) {
                throw UpstreamException("${whereOf(request)} gave no complete answer: ${reasonOf(e)}", e)
            }
        val headers = response.headers().map().flatMap { (name, values) -> values.map { Header(name, it) } }
        return MockResponse(response.statusCode(), headers, response.body() ?: ByteArray(0))
    }

    override fun close() {
        if (client.isInitialized()) client.value.shutdownNow()
    }

    /** Returns [request] as a request of the JDK client, or fails when the JDK client cannot send it. */
    private fun jdkRequest(request: UpstreamRequest): HttpRequest {
        val body = request.body
        return try {
            val builder = HttpRequest.newBuilder(uriOf(request.url))
            when {
                // Only a request that is built without a body publisher carries no Content-Length.
                body.isEmpty() && request.method == "GET" -> builder.GET()

                body.isEmpty() && request.method == "HEAD" -> builder.HEAD()

                body.isEmpty() && request.method == "DELETE" -> builder.DELETE()

                body.isEmpty() -> builder.method(request.method, HttpRequest.BodyPublishers.noBody())

                else -> builder.method(request.method, HttpRequest.BodyPublishers.ofByteArray(body))
            }
            request.headers.forEach { builder.addIfAccepted(it) }
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
}

/**
 * Returns [url] as a URI. `java.net.URI` refuses some characters that the target of a request can hold, such
 * as a pipe sign or a brace. A URL with such characters is taken with those characters escaped, and every
 * other URL as it is written.
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

/** What a URL can hold as it is: the characters that RFC 3986 names, without the brackets of an IPv6 address. */
private const val URL_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#@!$&'()*+,;="

/**
 * Returns [url] with every character escaped that a URL cannot hold as it is, as the bytes of that character
 * in UTF-8. A percent sign stays where it starts an escape. What comes before the path stays as it is.
 */
private fun escaped(url: String): String {
    val pathStart = url.indexOf('/', startIndex = url.indexOf("://") + ESCAPE_LENGTH).let { if (it < 0) url.length else it }
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

private const val BYTE_MASK = 0xFF

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

/** Returns what a failure message calls the real service of [request]: its URL without the query string. */
private fun whereOf(request: UpstreamRequest): String = "The real service at ${request.url.substringBefore('?')}"

/** Returns why an exchange with a real service failed, in words. */
private fun reasonOf(failure: IOException): String =
    when (failure) {
        is ConnectException -> "no connection could be made."
        is SSLException -> "the secure connection could not be set up. ${failure.message.orEmpty()}".trim()
        else -> failure.message?.let { "$it." } ?: "${failure.javaClass.simpleName}."
    }

/**
 * Waits for the value of the future and returns it. When the coroutine is canceled while it waits, the future
 * is canceled as well, which ends the exchange that it stands for.
 */
private suspend fun <T> CompletableFuture<T>.awaitOrCancel(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            when (failure) {
                null -> continuation.resume(value)
                is CompletionException -> continuation.resumeWithException(failure.cause ?: failure)
                else -> continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
