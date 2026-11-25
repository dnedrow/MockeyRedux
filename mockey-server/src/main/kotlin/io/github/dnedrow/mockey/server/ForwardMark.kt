package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import java.net.URISyntaxException

/**
 * The mark of a request that the server forwards to the port of its own admin listener. By the mark the
 * admin listener knows such a request, and it keeps the request from the admin API.
 * `docs/decisions/forwarding-to-the-admin-listener.md` says why it is done this way.
 *
 * The mock endpoint forwards a request to any URL, and by default the mock listener takes requests from
 * other machines. The admin listener trusts this machine: by default it takes requests from the loopback
 * address only, and without a token. The server is on this machine, so without the mark a client of the
 * mock endpoint would reach the admin API through the server.
 *
 * - The server gives the mark to every request that it forwards to a URL with the port of the admin
 *   listener, whatever host that URL names, see [client]. A request that a redirect leads to such a URL gets
 *   the mark as well, because the upstream client sends every request of such a chain.
 * - The admin listener answers a request that carries the mark with the status 403, whatever the request is
 *   for, and whether or not it presents the admin token, see [refuses].
 *
 * The mark is the header [HEADER]. Only the port of a URL decides about it. No name is looked up, so it does
 * not matter which address a name stands for at the moment at which the connection is made, and an address
 * can be written in any way. A real service that listens on a port with the same number on another machine
 * gets its requests with the mark.
 *
 * [adminPort] is the port of the admin listener, or `null` while it is not known: for a listener that is
 * set to the port 0, the system picks the port when the listener starts, and the mock listener accepts
 * connections before. A request that is to be forwarded then waits until [adminListensOn] names the port. So
 * no request gets to the admin listener without the mark, and none carries the mark to another port.
 */
internal class ForwardMark(
    adminPort: Int?,
) {
    private val adminPort = CompletableDeferred<Int>().apply { if (adminPort != null) complete(adminPort) }

    /** Says that the admin listener accepts connections on [port]. A port that is known already stays. */
    fun adminListensOn(port: Int) {
        adminPort.complete(port)
    }

    /**
     * Returns an upstream client that sends every request with [client], and that gives the mark to a request
     * before, when [marks] says so. Closing it closes [client].
     */
    fun client(client: UpstreamClient): UpstreamClient = Marking(client)

    /**
     * Returns whether [request] gets the mark: it goes to the port of the admin listener, or it cannot be
     * told where it goes. The answer comes when the port of the admin listener is known.
     *
     * The port is the one of the URI of the request, to which the upstream client of the library sends it,
     * or else the port of its scheme. It cannot be told for a URL that is none, for one whose URI names no
     * host, and for a scheme other than `http` and `https`. The upstream client of the library sends none of
     * these, so the mark on them is seen by nobody.
     */
    suspend fun marks(request: UpstreamRequest): Boolean {
        val admin = adminPort.await()
        val uri =
            try {
                request.uri()
            } catch (_: URISyntaxException) {
                return true
            }
        val port = if (uri.port >= 0) uri.port else PORTS_OF_SCHEMES[uri.scheme?.lowercase()]
        return uri.host == null || port == null || port == admin
    }

    /**
     * Answers the request of [call] when it carries the mark, and returns whether it does. The answer is a
     * problem document with the status 403. Nothing else is looked at before: not the path, not the admin
     * token, and not the body, which nothing reads.
     *
     * Only the name of the header counts. Whoever sends it to the admin listener says that the request was
     * forwarded, and gets the same answer.
     */
    suspend fun refuses(call: ApplicationCall): Boolean {
        if (!call.request.headers.contains(HEADER)) return false
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val problem = AdminProblem(HttpStatusCode.Forbidden, FORWARDED)
        call.respond(TextContent(AdminApiJson.instance.encodeToString(problem.document()), AdminProblem.CONTENT_TYPE, problem.status))
        return true
    }

    /** Sends with [client], and gives the mark to the requests that get it. */
    private inner class Marking(
        private val client: UpstreamClient,
    ) : UpstreamClient {
        override suspend fun send(request: UpstreamRequest): MockResponse = client.send(if (marks(request)) marked(request) else request)

        override fun close() {
            client.close()
        }
    }

    /** Returns [request] with the mark after its headers. Every other part of it stays as it is. */
    private fun marked(request: UpstreamRequest): UpstreamRequest =
        UpstreamRequest(request.method, request.url, request.headers + Header(HEADER, VALUE), request.body, request.outboundProxy)

    internal companion object {
        /** The name of the header that is the mark. */
        const val HEADER = "Mockey-Forwarded"

        /** The value that the server gives the header. The admin listener does not look at it. */
        const val VALUE = "true"

        private val PORTS_OF_SCHEMES = mapOf("http" to 80, "https" to 443)

        private const val FORWARDED =
            "The request carries the header $HEADER, which says that the mock endpoint of a Mockey server forwarded it. " +
                "The admin listener takes no request that was forwarded."
    }
}
