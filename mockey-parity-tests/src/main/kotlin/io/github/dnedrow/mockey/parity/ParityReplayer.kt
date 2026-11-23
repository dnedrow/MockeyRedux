package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
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
        val exchange = client.exchange(case.request, baseUrl)
        val differences = compare(expected, exchange.response) + compareTiming(case.timing, exchange.elapsedMillis)
        return ReplayResult(recording.fileName.toString(), differences)
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

    private fun compareTiming(
        expected: ParityTiming?,
        elapsedMillis: Long,
    ): List<String> =
        if (expected == null || expected.allows(elapsedMillis)) {
            emptyList()
        } else {
            listOf("elapsed time: expected ${expected.describe()}, got $elapsedMillis ms")
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
