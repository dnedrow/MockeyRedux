package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

class ParityRecorder(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val store: ParityCaseStore = ParityCaseStore(),
) {
    fun record(
        case: ParityCase,
        baseUrl: URI,
        destination: Path,
    ): ParityCase {
        val response = execute(case.request, baseUrl)
        val recorded = case.copy(expectedResponse = response)
        store.write(recorded, destination)
        return recorded
    }

    private fun execute(
        request: ParityRequest,
        baseUrl: URI,
    ): ParityResponse {
        val builder =
            HttpRequest
                .newBuilder(baseUrl.resolve(request.target))
                .method(request.method, HttpRequest.BodyPublishers.ofByteArray(request.body))
        request.headers.forEach { (name, values) -> values.forEach { value -> builder.header(name, value) } }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return ParityResponse(response.statusCode(), response.headers().map(), response.body())
    }
}
