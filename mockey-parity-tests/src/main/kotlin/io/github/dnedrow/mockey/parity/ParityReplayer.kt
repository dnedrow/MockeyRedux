package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

class ParityReplayer(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val store: ParityCaseStore = ParityCaseStore(),
) {
    fun replay(
        recording: Path,
        baseUrl: URI,
    ): ReplayResult {
        val case = store.read(recording)
        val expected = requireNotNull(case.expectedResponse)
        val actual = execute(case.request, baseUrl)
        return ReplayResult(recording.fileName.toString(), compare(expected, actual))
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

    private fun compare(
        expected: ParityResponse,
        actual: ParityResponse,
    ): List<String> =
        buildList {
            if (expected.status != actual.status) add("status: expected ${expected.status}, got ${actual.status}")
            val expectedHeaders = normalizeHeaders(expected.headers)
            val actualHeaders = normalizeHeaders(actual.headers)
            if (expectedHeaders != actualHeaders) add("headers: expected $expectedHeaders, got $actualHeaders")
            if (!expected.body.contentEquals(actual.body)) add("body bytes differ")
        }

    private fun normalizeHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers
            .filterKeys { it.lowercase() !in ignoredHeaders }
            .entries
            .associate { (name, values) -> name.lowercase() to values }

    private companion object {
        val ignoredHeaders = setOf("content-length", "date", "last-modified", "set-cookie")
    }
}
