package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * How the harness talks to the server under test. Every exchange uses HTTP/1.1 and a fixed `User-Agent`, so
 * that what a server receives, and what a proxied upstream sees of it, does not depend on the JDK that runs
 * the harness. The default JDK client would add HTTP/2 upgrade headers and its own version to every request.
 */
object ParityHttp {
    const val USER_AGENT = "mockey-parity"

    /** A server that has not answered after this time fails the exchange. Legacy never gives up on an upstream. */
    val requestTimeout: Duration = Duration.ofSeconds(30)

    private val connectTimeout: Duration = Duration.ofSeconds(10)

    fun newClient(): HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build()
}

internal data class ParityExchange(
    val response: ParityResponse,
    val elapsedMillis: Long,
)

/** Sends [request] to [baseUrl] and measures the time until the whole response has arrived. */
internal fun HttpClient.exchange(
    request: ParityRequest,
    baseUrl: URI,
): ParityExchange {
    val builder =
        HttpRequest
            .newBuilder(baseUrl.resolve(request.target))
            .timeout(ParityHttp.requestTimeout)
            .method(request.method, HttpRequest.BodyPublishers.ofByteArray(request.body))
    request.headers.forEach { (name, values) -> values.forEach { value -> builder.header(name, value) } }
    if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
        builder.header("User-Agent", ParityHttp.USER_AGENT)
    }
    val started = System.nanoTime()
    val response = send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
    return ParityExchange(ParityResponse(response.statusCode(), response.headers().map(), response.body()), elapsedMillis)
}

private const val NANOS_PER_MILLI = 1_000_000L
