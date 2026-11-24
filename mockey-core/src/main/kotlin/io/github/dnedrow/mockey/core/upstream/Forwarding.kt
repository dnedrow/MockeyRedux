package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse

/** The headers that tell about one connection and not about the message that it carries. They end at every hop. */
private val HOP_BY_HOP =
    setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade")

/**
 * The headers of a request to the mock endpoint that are not forwarded, next to the hop-by-hop headers.
 *
 * - `Host` and `Content-Length` fit the request of the client, and the forwarded request gets its own.
 * - `Accept-Encoding` is left out so that the real service answers without compression.
 * - `Cookie` is left out because the cookies of real services are those of the upstream session.
 * - `If-None-Match` and `If-Modified-Since` are left out so that the real service answers in full.
 */
private val NOT_FORWARDED = HOP_BY_HOP + setOf("host", "content-length", "accept-encoding", "cookie", "if-none-match", "if-modified-since")

/** The statuses whose response has no body, next to those of the 100 range. */
private val STATUSES_WITHOUT_BODY = setOf(204, 304)

private const val FIRST_FINAL_STATUS = 200
private const val CONTENT_LENGTH = "Content-Length"

/**
 * Returns the request that forwards [request] to [url], which is the URL of a real service with its query
 * string. The method and the body are those of [request], and the headers are its [forwardedHeaders].
 */
internal fun upstreamRequest(
    request: MockRequest,
    url: String,
): UpstreamRequest = UpstreamRequest(request.method, url, forwardedHeaders(request.headers), request.body)

/**
 * Returns the headers of a request to the mock endpoint that are forwarded to a real service, in their order.
 * These are all headers but the hop-by-hop headers and `Host`, `Content-Length`, `Accept-Encoding`, `Cookie`,
 * `If-None-Match`, and `If-Modified-Since`.
 */
internal fun forwardedHeaders(headers: List<Header>): List<Header> = headers.filterNot { it.name.lowercase() in NOT_FORWARDED }

/**
 * Returns the answer of a real service as the client gets it. [method] is the method of the request that the
 * real service answered.
 *
 * The status and the body are those of [response]. The headers are those of [response] without the hop-by-hop
 * headers, with every value of a header that occurs more than once. `Content-Length` states the length of the
 * body, also when the real service did not send that header. It is left as the real service sent it only
 * where it does not describe the body that is relayed: in the answer to a HEAD request, and in an answer with
 * a status that has no body.
 */
internal fun relayed(
    response: MockResponse,
    method: String,
): MockResponse {
    val headers = response.headers.filterNot { it.name.lowercase() in HOP_BY_HOP }
    val describesBody = method != "HEAD" && response.status >= FIRST_FINAL_STATUS && response.status !in STATUSES_WITHOUT_BODY
    val body = response.body
    return MockResponse(response.status, if (describesBody) headers.withContentLength(body.size) else headers, body)
}

/** Returns the headers with one `Content-Length` of [length], in the place of the first such header, or else at the end. */
private fun List<Header>.withContentLength(length: Int): List<Header> {
    val first = indexOfFirst { it.name.equals(CONTENT_LENGTH, ignoreCase = true) }
    if (first < 0) return this + Header(CONTENT_LENGTH, length.toString())
    return filterIndexed { index, header -> index <= first || !header.name.equals(CONTENT_LENGTH, ignoreCase = true) }
        .mapIndexed { index, header -> if (index == first) Header(header.name, length.toString()) else header }
}
