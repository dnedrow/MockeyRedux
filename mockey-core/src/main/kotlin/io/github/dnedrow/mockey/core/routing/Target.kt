package io.github.dnedrow.mockey.core.routing

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
        const val PREFIX = "/service/"

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
