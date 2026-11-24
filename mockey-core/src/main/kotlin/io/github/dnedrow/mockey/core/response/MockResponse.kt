package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.charsetOf

/**
 * A response to a request to the mock endpoint.
 *
 * [status] is the HTTP status code. The response takes over the array of its body. Whoever creates a response
 * must not change that array afterwards.
 */
public class MockResponse
    @JvmOverloads
    constructor(
        public val status: Int,
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ) {
        /** Every header of the response, in the order in which they are sent. A name can occur more than once. */
        public val headers: List<Header> = headers.toList()

        private val bodyBytes: ByteArray = body

        /** The body, which is empty for a response without one. Every read gives a new array. */
        public val body: ByteArray
            get() = bodyBytes.copyOf()

        /** The number of bytes of the body. */
        internal val bodySize: Int
            get() = bodyBytes.size

        /** Returns the first [limit] bytes of the body, or the whole body when it is not longer than that. */
        internal fun bodyStart(limit: Int): ByteArray = bodyBytes.copyOf(minOf(limit, bodyBytes.size))

        /** The value of the `Content-Type` header, or `null` for a response without that header. */
        public val contentType: String?
            get() = header("Content-Type")

        /**
         * The body as text, which is empty for a response without a body. The body is read in the charset that the
         * `Content-Type` of the response declares, and as UTF-8 when it declares none or one that is not known.
         * Bytes that are no text in that charset become the replacement character.
         */
        public val bodyText: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
            String(bodyBytes, charsetOf(contentType) ?: Charsets.UTF_8)
        }

        /** Returns the values of the headers with the name [name], in any letter case, in the order of the response. */
        public fun headerValues(name: String): List<String> = headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

        /** Returns the first value of the header [name], in any letter case, or `null` when the response has no such header. */
        public fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

        /** Returns the status. The headers and the body are left out, because they can hold secrets. */
        override fun toString(): String = "MockResponse($status)"
    }

/**
 * Returns a response that the system gives on its own, where no scenario and no real service answers: the
 * [status], and the [explanation] as plain text in UTF-8.
 */
internal fun builtInResponse(
    status: Int,
    explanation: String,
): MockResponse = MockResponse(status, listOf(Header("Content-Type", "text/plain; charset=utf-8")), explanation.toByteArray(Charsets.UTF_8))
