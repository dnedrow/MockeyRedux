package io.github.dnedrow.mockey.ui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.dnedrow.mockey.api.AdminApiDescription
import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import kotlinx.serialization.encodeToString
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * What stands in for the admin API of a server in the tests of the UI. It answers a request as a test told
 * it to, see [on], and it keeps every request that it got, see [requests].
 *
 * It holds every request against the description of the admin API, see [ApiContract]. A request that the
 * API does not have, or that is not as the description says, gets the status 500 and is kept among the
 * [violations], which a test expects to be empty: the UI is to send only what the API takes.
 *
 * With a [token], it takes only the requests that present it as a bearer token, as a server with an admin
 * token does, and it answers every other request with the status 401. Like a server, it takes a request that
 * can change something only without an `Origin` header.
 *
 * It answers the request for the status of the server with [system], unless a test told it otherwise.
 */
internal class FakeAdminApi(
    private val token: String? = null,
) : AutoCloseable {
    /**
     * A request that the API got: its [method], its [path] and its [query] as the client wrote them, its
     * [headers] by their names in lower case, and its [body].
     */
    class Request(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        /** The request line without the prefix of the API: the method, and the path with the query string. */
        val line: String = "$method ${path.removePrefix(AdminApiDescription.PREFIX + "/")}" + (query?.let { "?$it" } ?: "")

        /** Returns the one value of the header [name], or `null` when the request has no such header, or several. */
        fun header(name: String): String? = headers[name.lowercase()]?.singleOrNull()

        override fun toString(): String = line
    }

    /**
     * What the API answers a request with: a [status], a [body] of the type [contentType], and further
     * [headers]. An empty body is none.
     */
    class Answer(
        val status: Int,
        val body: String = "",
        val contentType: String = JSON,
        val headers: Map<String, String> = emptyMap(),
    )

    /** What the API states of the server. */
    @Volatile
    var system: SystemStatusDto = SYSTEM

    /** Every request that the API got, in the order in which they came. */
    val requests: MutableList<Request> = CopyOnWriteArrayList()

    /** What the description of the admin API has against the requests that the API got. */
    val violations: MutableList<String> = CopyOnWriteArrayList()

    /** The names of the operations of the admin API that the requests asked for, as the description names them. */
    val operations: MutableList<String> = CopyOnWriteArrayList()

    private val answers = ConcurrentHashMap<String, (Request) -> Answer>()
    private val threads = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.executor = threads
        server.start()
    }

    /** The URL of the admin listener that the API stands in for. */
    val url: String = "http://127.0.0.1:${server.address.port}"

    /** The requests that the API got, but for the ones for the status of the server, each as its request line without the prefix. */
    val calls: List<String>
        get() = requests.map { it.line }.filter { it != "GET system" }

    /**
     * Lets the API answer a request with [method] for [path] with what [answer] returns. [path] is the path
     * below the prefix of the API, as a client writes it. An answer that is set later takes the place of
     * one that was set before.
     */
    fun on(
        method: String,
        path: String,
        answer: (Request) -> Answer,
    ) {
        answers["$method ${AdminApiDescription.PREFIX}/$path"] = answer
    }

    /** Stops the API. A request that comes from now on finds nothing that listens. */
    override fun close() {
        server.stop(0)
        threads.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() }
        val bytes = exchange.requestBody.readAllBytes()
        val target = exchange.requestURI
        val request = Request(exchange.requestMethod, target.rawPath, target.rawQuery, headers, String(bytes, Charsets.UTF_8))
        requests += request
        val answer = if (presentsToken(request)) answerTo(request, bytes) else problem(UNAUTHORIZED, NO_TOKEN)
        val body = answer.body.toByteArray(Charsets.UTF_8)
        answer.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(answer.status, NO_BODY)
        } else {
            exchange.responseHeaders.add("Content-Type", answer.contentType)
            exchange.sendResponseHeaders(answer.status, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }

    /** Returns whether [request] presents the token, when the API has one. */
    private fun presentsToken(request: Request): Boolean = token == null || request.header("authorization") == "Bearer $token"

    private fun answerTo(
        request: Request,
        body: ByteArray,
    ): Answer {
        val operation = ApiContract.operationFor(request.method, request.path)
        val ofAPage = "origin" in request.headers && request.method !in READING
        val against =
            when {
                operation == null -> listOf("The admin API has no operation for ${request.method} ${request.path}.")
                ofAPage -> listOf("The admin API takes no request with an Origin header that can change something: $request.")
                else -> ApiContract.violations(operation, request.query, body, request.header("content-type"))
            }
        if (operation != null) operations += operation.id
        violations += against
        if (against.isNotEmpty()) return problem(SERVER_ERROR, "The request is none that the admin API takes: $against")
        val answer = answers["${request.method} ${request.path}"]
        return when {
            answer != null -> answer(request)
            request.line == "GET system" -> json(system)
            else -> problem(NOT_FOUND, "The test said nothing of ${request.method} ${request.path}.").also { violations += it.body }
        }
    }

    internal companion object {
        const val JSON = "application/json"
        const val PROBLEM = "application/problem+json"
        private const val NO_BODY = -1L
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val SERVER_ERROR = 500
        private const val NO_TOKEN = "The request does not present the admin token."

        /** The methods of the requests that change nothing. */
        private val READING = setOf("GET", "HEAD")

        /** What a server states of itself that runs with its default settings and is reached at `mockey.test`. */
        val SYSTEM =
            SystemStatusDto(
                version = "3.0.0",
                startTime = "2026-01-02T03:04:05.678Z",
                definitionsLocation = "/srv/mockey/mockey-definitions",
                readOnly = true,
                tagFilter = emptyList(),
                enabledTwist = null,
                mockBaseUrl = "http://mockey.test:8080",
                legacyShimEnabled = false,
            )

        /** Returns the answer with [status] whose body is [value] as JSON, in the form that the wire types of the API give it. */
        inline fun <reified T> json(
            value: T,
            status: Int = 200,
        ): Answer = Answer(status, AdminApiJson.instance.encodeToString(value))

        /** Returns the answer without a body that says that a request was done. */
        fun done(): Answer = Answer(status = 204)

        /** Returns the answer that reports a failure with [status]: a problem document that says [detail] and names [errors]. */
        fun problem(
            status: Int,
            detail: String,
            errors: List<FieldErrorDto> = emptyList(),
        ): Answer {
            val document = ProblemDto("about:blank", "Problem", status, detail, errors)
            return Answer(status, AdminApiJson.instance.encodeToString(document), PROBLEM)
        }
    }
}
