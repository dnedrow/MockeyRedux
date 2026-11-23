package io.github.dnedrow.mockey.parity

import java.nio.charset.StandardCharsets

/**
 * How a response is compared with a recorded or a stated one.
 *
 * A response of the mock endpoint, which is every target under `/service/`, is compared whole: the status, the
 * headers, and the body bytes. Headers that describe the connection or the server software are left out. Of
 * `Last-Modified`, whose value is the time of the response, only the presence counts, and a `Content-Type` is
 * compared by its media type and parameters, not by its spelling.
 *
 * For every other endpoint the specifications define a status and a result, but no headers. There only the
 * status and the body are compared, and a body that is a JSON object or array is compared as a document.
 */
internal object ResponseComparison {
    /** The differences between [actual] and the recorded legacy response [expected] to [request]. */
    fun differences(
        request: ParityRequest,
        expected: ParityResponse,
        actual: ParityResponse,
        snapshot: Boolean = false,
    ): List<String> =
        buildList {
            addAll(statusDifferences(expected.status, actual.status))
            if (snapshot) return@buildList
            if (isMockTraffic(request)) {
                addAll(headerDifferences(expected.headers, actual.headers))
                addAll(byteDifferences(expected.body, actual.body))
            } else {
                val document = jsonDocument(expected.body)
                addAll(if (document == null) byteDifferences(expected.body, actual.body) else jsonDifferences(document, actual.body))
            }
        }

    /** The differences between [actual] and the response [expected] that is stated for the new server. */
    fun differences(
        request: ParityRequest,
        expected: NewResponse,
        actual: ParityResponse,
    ): List<String> =
        buildList {
            addAll(statusDifferences(expected.status, actual.status))
            if (isMockTraffic(request)) addAll(headerDifferences(expected.headers, actual.headers))
            when (val body = expected.body) {
                is ExpectedBody.Exact -> addAll(byteDifferences(body.bytes, actual.body))
                is ExpectedBody.Json -> addAll(jsonDifferences(JsonDocuments.parse(body.template), actual.body, ANY_VALUE))
                is ExpectedBody.Contains -> addAll(missingFragments(body.fragments, actual.body))
                ExpectedBody.Unspecified -> Unit
            }
        }

    fun isMockTraffic(request: ParityRequest): Boolean = request.target.startsWith(MOCK_ENDPOINT)

    /** The headers that count in a comparison, with lower-case names and with values in a comparable form. */
    fun comparedHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.entries
            .groupBy({ it.key.lowercase() }, { it.value })
            .filterKeys { it !in connectionHeaders }
            .mapValues { (name, values) -> comparedValues(name, values.flatten()) }
            .toSortedMap()

    private fun comparedValues(
        name: String,
        values: List<String>,
    ): List<String> =
        when (name) {
            LAST_MODIFIED -> listOf(ANY_VALUE)
            CONTENT_TYPE -> values.map(::comparedContentType)
            else -> values
        }

    /** `text/plain;charset=UTF-8` and `text/plain; charset=utf-8` are the same content type. */
    private fun comparedContentType(value: String): String {
        val parts = value.split(";").map(String::trim).filter(String::isNotEmpty)
        val mediaType = parts.firstOrNull()?.lowercase() ?: return value
        val parameters =
            parts.drop(1).map { parameter ->
                val name = parameter.substringBefore("=").trim().lowercase()
                val text = parameter.substringAfter("=", "").trim()
                if (name == CHARSET) "$name=${text.trim('"').lowercase()}" else "$name=$text"
            }
        return (listOf(mediaType) + parameters).joinToString("; ")
    }

    private fun statusDifferences(
        expected: Int,
        actual: Int,
    ): List<String> = if (expected == actual) emptyList() else listOf("status: expected $expected, got $actual")

    private fun headerDifferences(
        expected: Map<String, List<String>>,
        actual: Map<String, List<String>>,
    ): List<String> {
        val expectedHeaders = comparedHeaders(expected)
        val actualHeaders = comparedHeaders(actual)
        return (expectedHeaders.keys + actualHeaders.keys).sorted().mapNotNull { name ->
            val wanted = expectedHeaders[name]
            val got = actualHeaders[name]
            when {
                wanted == null -> "header $name: not expected, got $got"
                got == null -> "header $name: expected $wanted, got none"
                wanted.size == got.size && wanted.indices.all { accepts(wanted[it], got[it]) } -> null
                else -> "header $name: expected $wanted, got $got"
            }
        }
    }

    /** An expected value that ends in [ANY_VALUE] says only how the value starts. */
    private fun accepts(
        expected: String,
        actual: String,
    ): Boolean = if (expected.endsWith(ANY_VALUE)) actual.startsWith(expected.removeSuffix(ANY_VALUE)) else expected == actual

    private fun byteDifferences(
        expected: ByteArray,
        actual: ByteArray,
    ): List<String> =
        if (expected.contentEquals(actual)) {
            emptyList()
        } else {
            listOf("body: expected ${describe(expected)}, got ${describe(actual)}")
        }

    private fun jsonDifferences(
        expected: Any,
        actual: ByteArray,
        anyValue: String? = null,
    ): List<String> {
        val document = jsonDocument(actual) ?: return listOf("body: expected a JSON document, got ${describe(actual)}")
        return JsonDocuments.differences(expected, document, anyValue).map { "body: $it" }
    }

    private fun missingFragments(
        fragments: List<String>,
        actual: ByteArray,
    ): List<String> {
        val text = String(actual, StandardCharsets.UTF_8)
        return fragments.filter { it !in text }.map { "body: expected to contain '$it', got ${describe(actual)}" }
    }

    /** The JSON object or array that [body] holds, or null when it holds something else. */
    private fun jsonDocument(body: ByteArray): Any? =
        runCatching { JsonDocuments.parse(String(body, StandardCharsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it is Map<*, *> || it is List<*> }

    private fun describe(body: ByteArray): String {
        val text = String(body, StandardCharsets.UTF_8)
        val readable = text.none { it == REPLACEMENT_CHARACTER || (it.isISOControl() && it !in "\r\n\t") }
        val shown = if (readable) "'${text.take(PREVIEW_LENGTH)}'" else hexadecimal(body.take(PREVIEW_LENGTH / 2))
        return "$shown (${body.size} bytes)"
    }

    private fun hexadecimal(bytes: List<Byte>): String = bytes.joinToString("") { "%02x".format(it) }

    private const val MOCK_ENDPOINT = "/service/"
    private const val LAST_MODIFIED = "last-modified"
    private const val CONTENT_TYPE = "content-type"
    private const val CHARSET = "charset"
    private const val REPLACEMENT_CHARACTER = '\uFFFD'
    private const val PREVIEW_LENGTH = 160

    /** Headers that describe the connection or the server software, not the answer of the application. */
    private val connectionHeaders = setOf("connection", "content-length", "date", "keep-alive", "server", "transfer-encoding")
}
