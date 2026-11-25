package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory

/**
 * The mock endpoint: the one handler of the mock listener, which hands every request whose path begins with
 * `/service/` to the engine and writes the response of the engine to the client. It is no route of Ktor,
 * because the routing of Ktor does not leave a request target as the client sent it, see
 * `docs/decisions/http-stack.md`.
 *
 * A request reaches the engine as the client sent it: its method, whatever that is, the path and the query
 * string of its request target without any decoding, its headers, and its body.
 *
 * Two kinds of request do not reach the engine. A request with a body of more than [maxRequestBody] bytes
 * gets the status 413. A request that names no host gets the status 400, as HTTP has it: a request of
 * HTTP/1.1 without a `Host` header, and a request whose `Host` header holds what no name of a host holds.
 */
internal class MockEndpoint(
    private val engine: MockeyEngine,
    private val maxRequestBody: Long,
) {
    /** The module of the mock listener. */
    val module: suspend Application.() -> Unit = { intercept(ApplicationCallPipeline.Call) { handle(call) } }

    /** Answers the request of [call] when it is mock traffic. Ktor answers what is left unanswered with the status 404. */
    private suspend fun handle(call: ApplicationCall) {
        val connection = call.request.local
        val target = RequestTarget.parse(connection.uri)
        if (!target.path.startsWith(MockRequest.SERVICE_PATH)) return
        val host = hostOf(call) ?: return call.respond(plain(HttpStatusCode.BadRequest, NO_HOST))
        val url = target.url ?: "$SCHEME$host${target.path}"
        val body = bodyOf(call) ?: return call.respond(plain(HttpStatusCode.PayloadTooLarge, tooLarge()))
        val headers = headersOf(call)
        val method = connection.method.value
        val response =
            try {
                engine.handle(MockRequest(method, url, target.query, headers, body), connection.remoteAddress).response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The query string is left out, because it can hold a secret.
                log.error("The request {} {} failed.", method, target.path, failure)
                return call.respond(plain(HttpStatusCode.InternalServerError, FAILED))
            }
        write(call, method, response)
    }

    /**
     * Returns the host that the request names in its `Host` header, with its port, as the client wrote it.
     * A request of HTTP/1.0 need not name a host, and for such a request it is the address and the port of
     * the listener.
     *
     * Returns `null` for a request that names no host: when a request of a later version has no `Host`
     * header, and when the header holds what no name of a host holds.
     */
    private fun hostOf(call: ApplicationCall): String? {
        val connection = call.request.local
        val host = call.request.headers[HttpHeaders.Host]?.trim()
        return when {
            host.isNullOrEmpty() -> hostAndPort(connection.localAddress, connection.localPort).takeIf { connection.version == HTTP_1_0 }
            host.any { it in NOT_IN_A_HOST || it.isWhitespace() || it.isISOControl() } -> null
            else -> host
        }
    }

    private fun hostAndPort(
        address: String,
        port: Int,
    ): String = if (':' in address) "[$address]:$port" else "$address:$port"

    /**
     * Returns the headers of the request: the names in the order in which the client sent them, and with
     * each name all of its values, in their order.
     *
     * A name counts once, whatever its letter case, and its values go under its first spelling. Ktor lists
     * a name once for each spelling of it, and it gives the values of all spellings for each, so that a
     * header which a client sends as `x-id` and as `X-Id` would come out twice.
     */
    private fun headersOf(call: ApplicationCall): List<Header> {
        val headers = call.request.headers
        val seen = mutableSetOf<String>()
        return headers.names().filter { seen.add(it.lowercase()) }.flatMap { name ->
            headers.getAll(name).orEmpty().map { Header(name, it) }
        }
    }

    /**
     * Returns the body of the request, or `null` when it is longer than the server takes. A request that
     * states a length beyond that is not read at all, and any other is read as far as the limit goes. The
     * engine reads and drops the rest of a body that is not read here.
     *
     * The body is read from the channel of the request itself. The channel that Ktor gives a call for its
     * body is the same one, but on the way to it Ktor writes the request target with its query string to
     * the log, at the most verbose level.
     */
    private suspend fun bodyOf(call: ApplicationCall): ByteArray? {
        val stated =
            call.request.headers[HttpHeaders.ContentLength]
                ?.trim()
                ?.toLongOrNull()
        if (stated != null && stated > maxRequestBody) return null
        val bytes =
            call.request
                .receiveChannel()
                .readBuffer(maxRequestBody + 1)
                .readByteArray()
        return bytes.takeUnless { it.size > maxRequestBody }
    }

    private fun tooLarge(): String =
        "The body of the request is longer than $maxRequestBody bytes, which is the most that this server takes."

    /**
     * Writes [response] to the client that sent a request with [method]: its status, its headers in their
     * order, and its body.
     *
     * The server states the length of the body itself, whatever the headers of the response say about it,
     * so that the response is one that a client can read.
     *
     * The answer to a `HEAD` request, and a response whose status allows no body, end with their headers.
     * Such a response states the length of the body that it stands for: of the body that it has, and when
     * it has none, the length that its headers state. The answer of a real service to a `HEAD` request is
     * such a response without a body.
     */
    private suspend fun write(
        call: ApplicationCall,
        method: String,
        response: MockResponse,
    ) {
        if (response.status !in WRITABLE_STATUSES) {
            log.warn("A response with the status {} was not sent, because that is no status of HTTP.", response.status)
            val explanation = "The response has the status ${response.status}, which is no status of HTTP."
            return call.respond(plain(HttpStatusCode.InternalServerError, explanation))
        }
        for (header in response.headers.filterNot { it.name.lowercase() in SET_BY_THE_SERVER }) {
            try {
                call.response.headers.append(header.name, header.value, safeOnly = false)
            } catch (_: IllegalArgumentException) {
                // The value is left out, and so is the exception, which states the value: it can hold a secret.
                log.warn("The response header {} was not sent, because HTTP does not allow its name or its value.", header.name)
            }
        }
        val status = HttpStatusCode.fromValue(response.status)
        val body = response.body
        val endsWithItsHeaders =
            method.equals("HEAD", ignoreCase = true) || response.status < FIRST_FINAL_STATUS || response.status in STATUSES_WITHOUT_BODY
        if (endsWithItsHeaders) {
            val stated =
                response
                    .header(HttpHeaders.ContentLength)
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 }
            call.respond(HeadersOnly(status, if (body.isEmpty()) stated ?: 0 else body.size.toLong()))
        } else {
            call.respond(ByteArrayContent(body, status = status))
        }
    }

    private fun plain(
        status: HttpStatusCode,
        text: String,
    ): OutgoingContent = TextContent(text, ContentType.Text.Plain.withCharset(Charsets.UTF_8), status)

    /** A response that ends with its headers, which state [contentLength] as the length of a body that is not sent. */
    private class HeadersOnly(
        override val status: HttpStatusCode,
        override val contentLength: Long,
    ) : OutgoingContent.NoContent()

    private companion object {
        private val log = LoggerFactory.getLogger(MockEndpoint::class.java)

        private const val SCHEME = "http://"

        /** Characters that the name of a host with its port never holds, and that would end the host in a URL. */
        private const val NOT_IN_A_HOST = "/\\?#@"

        private const val HTTP_1_0 = "HTTP/1.0"
        private const val NO_HOST = "The request names no host in a Host header."
        private const val FAILED = "The server failed to handle the request. Its log says why."

        /** The statuses that a status line can state: numbers of three digits. */
        private val WRITABLE_STATUSES = 100..999
        private const val FIRST_FINAL_STATUS = 200

        /** The statuses whose response has no body, next to those of the 100 range. */
        private val STATUSES_WITHOUT_BODY = setOf(204, 304)

        /** The headers of a response that say how its body is sent. The server sends the body and says how. */
        private val SET_BY_THE_SERVER = setOf("content-length", "transfer-encoding")
    }
}

/**
 * The request target of a request, taken apart as the client wrote it. Nothing is decoded.
 *
 * [path] is the path, and [query] is the query string without its `?`, or `null` for a target without a
 * `?`. [url] is the URL that the client addressed, without its query string, for a target that is a URL
 * with a scheme and a host, as a client of a proxy writes it. It is `null` for a target that is a path.
 */
internal class RequestTarget private constructor(
    val url: String?,
    val path: String,
    val query: String?,
) {
    internal companion object {
        private val ABSOLUTE = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://[^/]*""")

        /** Takes [target] apart, which is the request target of a request line. */
        fun parse(target: String): RequestTarget {
            val withoutQuery = target.substringBefore('?')
            val query = if (withoutQuery.length < target.length) target.substring(withoutQuery.length + 1) else null
            val origin = ABSOLUTE.find(withoutQuery)?.value
            return if (origin ==
                null
            ) {
                RequestTarget(null, withoutQuery, query)
            } else {
                RequestTarget(withoutQuery, withoutQuery.removePrefix(origin), query)
            }
        }
    }
}
