package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import java.time.Instant
import java.util.Base64

/** Builds the history entries of the tests. Every entry starts from [Samples.entry], an exchange that the service Customers answered. */
internal object Entries {
    private val FIRST: Instant = Instant.parse("2026-01-02T03:00:00Z")

    /** Returns the time of the entry [id]: the entries are one second apart, so a later entry has a later time. */
    fun timeOf(id: Long): String = FIRST.plusSeconds(id).toString()

    /** Returns the time of the entry [id] the way the pages display it. */
    fun shownTimeOf(id: Long): String = timeOf(id).replace('T', ' ').removeSuffix("Z") + " UTC"

    /**
     * Returns the entry [id] for a request with [method] for [url] and [query], with [headers], and with
     * [body]. The response has [responseHeaders] and [responseBody].
     */
    fun of(
        id: Long,
        url: String = "http://mockey.test:8080/service/customer/$id",
        method: String = "GET",
        query: String? = null,
        headers: List<HeaderDto> = listOf(HeaderDto("Accept", "*/*")),
        parameters: List<NameValueDto> = emptyList(),
        body: RecordedBodyDto = Samples.body(""),
        responseHeaders: List<HeaderDto> = Samples.entry.response.headers,
        responseBody: RecordedBodyDto = Samples.entry.response.body,
    ): HistoryEntryDto {
        val addressed = Samples.entry.request.copy(method = method, url = url, query = query)
        val request = addressed.copy(headers = headers, parameters = parameters, body = body)
        val response = Samples.entry.response.copy(headers = responseHeaders, body = responseBody)
        return Samples.entry.copy(id = id, time = timeOf(id), request = request, response = response, target = "customer/$id")
    }

    /** Returns a body that holds [bytes], which need not be text. */
    fun bytes(vararg bytes: Int): RecordedBodyDto {
        val held = ByteArray(bytes.size) { bytes[it].toByte() }
        return RecordedBodyDto(Base64.getEncoder().encodeToString(held), held.size.toLong(), isCut = false)
    }

    /** Returns a body of [size] bytes of which the entry only holds [kept], because the body was cut. */
    fun cut(
        kept: ByteArray,
        size: Long,
    ): RecordedBodyDto = RecordedBodyDto(Base64.getEncoder().encodeToString(kept), size, isCut = true)
}
