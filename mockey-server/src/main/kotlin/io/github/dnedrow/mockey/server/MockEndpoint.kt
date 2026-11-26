package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The mock endpoint: the one handler of the mock listener, which hands every request whose path begins with
 * `/service/` to the engine and writes the response of the engine to the client. It is no route of Ktor,
 * because the routing of Ktor does not leave a request target as the client sent it, see
 * `docs/decisions/http-stack.md`.
 *
 * A request reaches the engine as the client sent it: its method, whatever that is, the path and the query
 * string of its request target without any decoding, its headers, and its body.
 *
 * A request with a body of more than [maxRequestBody] bytes does not reach the engine. It gets the status
 * 413. The URL of a request names the host that the request names, see [RequestHost]. A listener passes no
 * request on that names none.
 *
 * Every response of the mock endpoint carries the five default headers of mock responses, see
 * [withDefaults]. [clock] tells the time that one of them states.
 */
internal class MockEndpoint(
    private val engine: MockeyEngine,
    private val maxRequestBody: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Answers the request of [call] when it is mock traffic, and returns whether it is. */
    suspend fun handle(call: ApplicationCall): Boolean {
        val connection = call.request.local
        val target = RequestTarget.parse(connection.uri)
        if (!target.path.startsWith(MockRequest.SERVICE_PATH)) return false
        answer(call, target)
        return true
    }

    /** Answers the request of [call], which is mock traffic for [target]. */
    private suspend fun answer(
        call: ApplicationCall,
        target: RequestTarget,
    ) {
        val connection = call.request.local
        val host = checkNotNull(RequestHost.of(call)) { "A listener passes no request on that names no host." }
        val url = target.url ?: "$SCHEME$host${target.path}"
        val body = bodyOf(call, maxRequestBody) ?: return refuse(call, HttpStatusCode.PayloadTooLarge, tooLarge())
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
                return refuse(call, HttpStatusCode.InternalServerError, FAILED)
            }
        write(call, method, response)
    }

    /**
     * Answers a request that the engine does not handle with [status] and [explanation], as plain text. The
     * body of such a request may not have been read, see [UnreadBody].
     */
    private suspend fun refuse(
        call: ApplicationCall,
        status: HttpStatusCode,
        explanation: String,
    ) {
        write(call, call.request.local.method.value, builtIn(status.value, explanation))
    }

    private fun builtIn(
        status: Int,
        explanation: String,
    ): MockResponse {
        val headers = listOf(Header(HttpHeaders.ContentType, Refusal.PLAIN_TEXT.toString()))
        return MockResponse(status, headers, explanation.toByteArray(Charsets.UTF_8))
    }

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
            return write(call, method, builtIn(HttpStatusCode.InternalServerError.value, explanation))
        }
        for (header in withDefaults(response.headers).filterNot { it.name.lowercase() in SET_BY_THE_SERVER }) {
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

    /**
     * Returns [headers] with the default headers of mock responses after them: `Access-Control-Allow-Origin`,
     * `Cache-Control`, `Pragma`, `Expires`, and `Last-Modified` with the time of now. A default is left out
     * when [headers] hold a header of its name, in any letter case, so that a scenario or a real service that
     * sets such a header has its way.
     */
    private fun withDefaults(headers: List<Header>): List<Header> {
        val defined = headers.map { it.name.lowercase() }.toSet()
        val defaults = FIXED_DEFAULTS + Header(HttpHeaders.LastModified, HTTP_DATE.format(clock.instant()))
        return headers + defaults.filterNot { it.name.lowercase() in defined }
    }

    /** A response that ends with its headers, which state [contentLength] as the length of a body that is not sent. */
    private class HeadersOnly(
        override val status: HttpStatusCode,
        override val contentLength: Long,
    ) : OutgoingContent.NoContent()

    private companion object {
        private val log = LoggerFactory.getLogger(MockEndpoint::class.java)

        private const val SCHEME = "http://"

        /** The default headers of mock responses whose values are always the same. */
        private val FIXED_DEFAULTS =
            listOf(
                Header(HttpHeaders.AccessControlAllowOrigin, "*"),
                Header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0"),
                Header(HttpHeaders.Pragma, "no-cache"),
                Header(HttpHeaders.Expires, "Tue, 03 Jul 2001 06:00:00 GMT"),
            )

        /** A time as an HTTP header states it, such as `Tue, 03 Jul 2001 06:00:00 GMT`. */
        private val HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

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
            val origin = ABSOLUTE.find(withoutQuery)?.value ?: return RequestTarget(null, withoutQuery, query)
            return RequestTarget(withoutQuery, withoutQuery.removePrefix(origin), query)
        }
    }
}
