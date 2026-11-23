package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
            .method(request.method, HttpRequest.BodyPublishers.ofByteArray(request.body))
    request.headers.forEach { (name, values) -> values.forEach { value -> builder.header(name, value) } }
    val started = System.nanoTime()
    val response = send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
    return ParityExchange(ParityResponse(response.statusCode(), response.headers().map(), response.body()), elapsedMillis)
}

private const val NANOS_PER_MILLI = 1_000_000L
