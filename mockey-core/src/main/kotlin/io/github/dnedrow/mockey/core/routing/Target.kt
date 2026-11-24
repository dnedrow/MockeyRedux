package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * What a request to the mock endpoint asks for: the text that follows `/service/` in the path of the request,
 * as the client wrote it. The query string of the request is not part of it.
 *
 * A target that begins with `http://` or `https://`, in any letter case, is absolute: it is the URL of a real
 * service. Every other target is relative, also one that holds a URL further on or that begins with the name
 * of a host.
 */
internal data class Target(
    val text: String,
) {
    /** Whether the target is the URL of a real service. */
    val isAbsolute: Boolean
        get() = schemeOf(text) != null

    /**
     * The target in the form in which it is compared with URL patterns, see [normalizedUrl]. A request is
     * forwarded to [text], never to this form.
     */
    val normalized: String by lazy(LazyThreadSafetyMode.PUBLICATION) { normalizedUrl(text) }

    /** What [normalized] holds between its slashes. A URL pattern matches the target one such part at a time. */
    val segments: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { normalized.split('/') }
}

/**
 * The request URI of a request to the mock endpoint, taken apart. [query] is the query string as the client
 * wrote it, without its `?`, and `null` for a URI without a `?`.
 */
internal data class MockRequestUri(
    val target: Target,
    val query: String?,
) {
    companion object {
        /** The start of the path of every request that is mock traffic. */
        const val PREFIX = MockRequest.SERVICE_PATH

        /**
         * Takes [requestUri] apart: the path of a request and its query string, as the client sent them.
         * Returns `null` when the path does not begin with [PREFIX], because such a request is no mock traffic.
         */
        fun parse(requestUri: String): MockRequestUri? {
            val path = requestUri.substringBefore('?')
            if (!path.startsWith(PREFIX)) return null
            val query = if (path.length < requestUri.length) requestUri.substring(path.length + 1) else null
            return MockRequestUri(Target(path.removePrefix(PREFIX)), query)
        }
    }
}

/** The characters that the scheme of a URL can hold after its first letter. */
private const val SCHEME_CHARACTERS = "+-."

/**
 * Returns the path of [url]: what follows its scheme, its host, and its port, as it is written. A URL that
 * ends with its host has an empty path, and a text without a scheme is a path as it is.
 */
internal fun pathOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    val hasScheme = schemeEnd > 0 && url.substring(0, schemeEnd).all { it.isLetterOrDigit() || it in SCHEME_CHARACTERS }
    if (!hasScheme) return url
    val pathStart = url.indexOf('/', schemeEnd + "://".length)
    return if (pathStart < 0) "" else url.substring(pathStart)
}

/**
 * Returns the target that a request for [url] asks for: what follows `/service/` in the path of the URL.
 * Returns `null` when the path does not begin with that, because such a request is no mock traffic.
 */
internal fun targetOf(url: String): Target? = MockRequestUri.parse(pathOf(url))?.target
