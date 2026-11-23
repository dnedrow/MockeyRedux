package io.github.dnedrow.mockey.parity

import java.nio.charset.StandardCharsets

/**
 * The scripted responses that a [StubHttpServer] gives to requests under `/stub/`. Each route is deterministic,
 * so that a response relayed by a proxying server can be recorded and compared byte for byte. Header names are
 * written as they are spelled here.
 *
 * | Route | Response |
 * |---|---|
 * | `echo`, `echo/...`, `cookie/echo` | 200 and a description of the request, see [echo] |
 * | `status/<code>` | that status, with the body `status <code>` where the status allows a body |
 * | `headers` | 201 with `X-Upstream`, `X-Multi` twice, `Cache-Control: max-age=60`, and `Location` |
 * | `cookie/set` | 200 with two `Set-Cookie` headers scoped to `/stub/cookie` |
 * | `binary` | 200 with the 256 bytes 00 to FF as `application/octet-stream` |
 * | `text/latin1` | `café` in ISO-8859-1, with that charset declared |
 * | `text/undeclared` | `café` in UTF-8, with no charset declared |
 * | `redirect/<code>` | that status with a `Location` of this stub's `echo?after=redirect` |
 * | `redirect/loop` | 302 with a `Location` of itself |
 * | `close` | no response: the connection is closed |
 */
internal object StubRoutes {
    const val PREFIX = "/stub/"

    /** Request headers that [echo] reports, in addition to every header whose name starts with `x-`. */
    private val echoedHeaders =
        setOf(
            "accept",
            "accept-encoding",
            "authorization",
            "content-type",
            "cookie",
            "host",
            "if-modified-since",
            "if-none-match",
            "keep-alive",
            "proxy-authorization",
            "te",
            "trailer",
            "user-agent",
        )

    private val statusesWithoutBody = setOf(204, 304)
    private val absoluteForm = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?]*")
    private val text = listOf("Content-Type" to "text/plain")

    private val fixedResponses =
        mapOf(
            "headers" to
                RawResponse(
                    201,
                    text +
                        listOf(
                            "X-Upstream" to "one",
                            "X-Multi" to "a",
                            "X-Multi" to "b",
                            "Cache-Control" to "max-age=60",
                            "Location" to "/created/9",
                        ),
                    "with headers".toByteArray(),
                ),
            "cookie/set" to
                RawResponse(
                    200,
                    text +
                        listOf(
                            "Set-Cookie" to "sid=abc; Path=/stub/cookie; HttpOnly",
                            "Set-Cookie" to "theme=dark; Path=/stub/cookie; Max-Age=3600",
                        ),
                    "cookie set".toByteArray(),
                ),
            "binary" to RawResponse(200, listOf("Content-Type" to "application/octet-stream"), ByteArray(256) { it.toByte() }),
            "text/latin1" to
                RawResponse(
                    200,
                    listOf("Content-Type" to "text/plain; charset=ISO-8859-1"),
                    "café".toByteArray(StandardCharsets.ISO_8859_1),
                ),
            "text/undeclared" to RawResponse(200, text, "café".toByteArray(StandardCharsets.UTF_8)),
        )

    /** Whether [request] addresses a scripted route, in origin form or in the absolute form that a proxy receives. */
    fun handles(request: RecordedRequest): Boolean = path(request).startsWith(PREFIX)

    /** The response to [request], or null where the route closes the connection. [port] is the stub's own port. */
    fun respond(
        request: RecordedRequest,
        port: Int,
        viaRole: String?,
    ): RawResponse? {
        val route = path(request).removePrefix(PREFIX)
        val fixed = fixedResponses[route]
        return when {
            fixed != null -> fixed
            isEcho(route) -> RawResponse(200, text, echo(request, viaRole).toByteArray(StandardCharsets.US_ASCII))
            route.startsWith("status/") -> status(route.removePrefix("status/").toInt())
            route == "redirect/loop" -> redirect(302, port, route)
            route.startsWith("redirect/") -> redirect(route.removePrefix("redirect/").toInt(), port, "echo?after=redirect")
            route == "close" -> null
            else -> RawResponse(404, text, "no stub route: $route".toByteArray())
        }
    }

    /** Answers as a proxy that wants credentials before it forwards anything. */
    fun requireProxyCredentials() =
        RawResponse(
            407,
            text + ("Proxy-Authenticate" to "Basic realm=\"mockey-parity\""),
            "proxy credentials required".toByteArray(),
        )

    private fun path(request: RecordedRequest): String = request.target.replaceFirst(absoluteForm, "").substringBefore("?")

    private fun isEcho(route: String) = route == "echo" || route.startsWith("echo/") || route == "cookie/echo"

    /**
     * Describes [request] in ASCII, one item to a line: `via` when an outbound proxy answers, `method`, `target`,
     * one `header` line for each value of the [echoedHeaders] and `x-` headers that the request carries, sorted by
     * name, then `body-length` and `body-hex`. A header that is not listed was not received, or is not reported.
     */
    fun echo(
        request: RecordedRequest,
        viaRole: String?,
    ): String =
        buildString {
            viaRole?.let { append("via: ").append(it).append('\n') }
            append("method: ").append(request.method).append('\n')
            append("target: ").append(request.target).append('\n')
            request.headers
                .mapKeys { it.key.lowercase() }
                .filterKeys { it in echoedHeaders || it.startsWith("x-") }
                .toSortedMap()
                .forEach { (name, values) -> values.forEach { value -> append("header $name: $value\n") } }
            append("body-length: ").append(request.body.size).append('\n')
            append("body-hex: ").append(request.body.joinToString("") { "%02x".format(it) }).append('\n')
        }

    private fun status(code: Int) =
        if (code in statusesWithoutBody) RawResponse(code) else RawResponse(code, text, "status $code".toByteArray())

    private fun redirect(
        code: Int,
        port: Int,
        route: String,
    ) = RawResponse(code, text + ("Location" to "http://127.0.0.1:$port$PREFIX$route"), "redirect $code".toByteArray())
}
