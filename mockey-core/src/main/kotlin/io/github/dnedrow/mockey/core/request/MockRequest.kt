package io.github.dnedrow.mockey.core.request

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.routing.percentDecoded

/**
 * A request to the mock endpoint, as a client sent it.
 *
 * [method] is the HTTP method. [url] is the URL that the client addressed, with its scheme, host, port, and
 * path as the client wrote them and without its query string, such as
 * `http://mockey.test:8080/service/customer/123`. [query] is the query string as the client wrote it, without
 * its `?`, and `null` for a request that has none.
 *
 * The request takes over the array of its body. Whoever creates a request must not change that array
 * afterwards.
 */
public class MockRequest
    @JvmOverloads
    constructor(
        public val method: String,
        public val url: String,
        public val query: String? = null,
        headers: List<Header> = emptyList(),
        body: ByteArray = ByteArray(0),
    ) {
        init {
            require('?' !in url) { "The URL of a request does not hold its query string." }
        }

        /** Every header of the request, in the order in which the client sent them. A name can occur more than once. */
        public val headers: List<Header> = headers.toList()

        private val bodyBytes: ByteArray = body

        /** The body, which is empty for a request without one. Every read gives a new array. */
        public val body: ByteArray
            get() = bodyBytes.copyOf()

        /** The value of the `Content-Type` header, or `null` for a request without that header. */
        public val contentType: String?
            get() = header("Content-Type")

        /**
         * The body as text, which is empty for a request without a body. The body is read in the charset that the
         * `Content-Type` of the request declares, and as UTF-8 when it declares none or one that is not known.
         * Bytes that are no text in that charset become the replacement character.
         */
        public val bodyText: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
            String(bodyBytes, charsetOf(contentType) ?: Charsets.UTF_8)
        }

        /**
         * The parameters of the request, in the order in which the client wrote them: those of the query string,
         * and after them the fields of the body when the `Content-Type` says that the body is a URL-encoded form.
         * A name can occur more than once.
         *
         * Names and values are decoded: a `+` stands for a space, and a percent escape for a byte of UTF-8 text.
         * A parameter without `=` has an empty value.
         */
        public val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val ofQuery = query?.let(::parametersOf).orEmpty()
            if (mediaTypeOf(contentType) == FORM_MEDIA_TYPE) ofQuery + parametersOf(bodyText) else ofQuery
        }

        /**
         * The cookies that the request carries in its `Cookie` headers, in the order in which the client wrote
         * them. A name can occur more than once. Names and values are as they are written, without the
         * whitespace around them. A cookie without `=` is a name with an empty value.
         */
        public val cookies: List<Cookie> by lazy(LazyThreadSafetyMode.PUBLICATION) { headerValues("Cookie").flatMap(::cookiesOf) }

        /** The number of bytes of the body. */
        internal val bodySize: Int
            get() = bodyBytes.size

        /** Returns the first [limit] bytes of the body, or the whole body when it is not longer than that. */
        internal fun bodyStart(limit: Int): ByteArray = bodyBytes.copyOf(minOf(limit, bodyBytes.size))

        /** Returns the values of the headers with the name [name], in any letter case, in the order of the request. */
        public fun headerValues(name: String): List<String> = headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

        /** Returns the first value of the header [name], in any letter case, or `null` when the request has no such header. */
        public fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

        /** Returns the values of the parameters with exactly the name [name], in the order of the request. */
        public fun parameterValues(name: String): List<String> = parameters.filter { it.name == name }.map { it.value }

        /** Returns the method and the URL. The query string, the headers, and the body are left out, because they can hold secrets. */
        override fun toString(): String = "MockRequest($method $url)"

        public companion object {
            /**
             * The start of the path of every request to the mock endpoint. What follows it in the path of a
             * request is the target of the request.
             */
            public const val SERVICE_PATH: String = "/service/"

            /**
             * Returns a request for [target], for a program that hands requests to an engine without a server
             * in between. The request is the one of a client that addresses the mock endpoint at
             * `http://localhost`: its URL is `http://localhost/service/` followed by [target].
             *
             * [target] is a relative target such as `customer/7`, or the URL of a real service. The query
             * string is no part of it: it is given as [query], without its `?`.
             */
            @JvmStatic
            @JvmOverloads
            public fun forTarget(
                method: String,
                target: String,
                query: String? = null,
                headers: List<Header> = emptyList(),
                body: ByteArray = ByteArray(0),
            ): MockRequest = MockRequest(method, "http://localhost$SERVICE_PATH$target", query, headers, body)
        }
    }

/** One parameter of a request: a name and a value of its query string or of its URL-encoded form, both decoded. */
public data class Parameter(
    public val name: String,
    public val value: String,
)

/** One cookie of a request: a name and a value of its `Cookie` header, both as the client wrote them. */
public data class Cookie(
    public val name: String,
    public val value: String,
)

private const val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded"

/**
 * Returns the cookies that [header] holds, which is the value of a `Cookie` header. Cookies are separated by
 * `;`, and the first `=` of a cookie separates its name from its value. Nothing between two `;` is no cookie.
 */
internal fun cookiesOf(header: String): List<Cookie> =
    header
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Cookie(it.substringBefore('=').trim(), it.substringAfter('=', missingDelimiterValue = "").trim()) }

/**
 * Returns the parameters that [text] holds, which is a query string or the text of a URL-encoded form.
 *
 * Parameters are separated by `&`, and the first `=` of a parameter separates its name from its value. A
 * parameter without `=` is a name with an empty value, and nothing between two `&` is no parameter. In a name
 * and in a value, a `+` stands for a space and a percent escape for a byte of UTF-8 text. A `%` that starts no
 * escape stays as it is.
 */
internal fun parametersOf(text: String): List<Parameter> =
    text
        .split('&')
        .filter { it.isNotEmpty() }
        .map { Parameter(formDecoded(it.substringBefore('=')), formDecoded(it.substringAfter('=', missingDelimiterValue = ""))) }

/**
 * Returns [text] decoded as form data is: a `+` stands for a space, and a percent escape for a byte of UTF-8
 * text. A `%` that starts no escape stays as it is.
 */
internal fun formDecoded(text: String): String = percentDecoded(text.replace('+', ' '))

/**
 * The body as text when there is something in it to evaluate, and `null` otherwise. A request without a body
 * has nothing to evaluate, and neither has a request whose body holds nothing but whitespace.
 */
internal val MockRequest.nonBlankBodyText: String?
    get() = bodyText.takeUnless { it.all(::isSpaceOrControl) }

/** What counts as whitespace around a text and in a body: a space, and every control character below it. */
internal fun isSpaceOrControl(char: Char): Boolean = char <= ' '
