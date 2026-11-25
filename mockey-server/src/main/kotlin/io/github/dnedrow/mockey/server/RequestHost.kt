package io.github.dnedrow.mockey.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond

/**
 * The host that a request names. HTTP has it that a request of HTTP/1.1 names the host that it is for in
 * one `Host` header, and that a server answers a request that does not with the status 400. A listener does
 * so before anything else sees the request.
 *
 * The HTTP server of Mockey 2.x refused a request without a `Host` header as well. Other than that server,
 * a listener also refuses a request with more than one such header, and a `Host` header that holds what no
 * name of a host holds. The mock endpoint makes the URL of a request from the header, and such a header
 * would make another URL than the client addressed.
 */
internal object RequestHost {
    /** What a listener says to a request that names no host. */
    const val MISSING = "The request names no host in a Host header."

    /** What a listener says to a request that names a host more than once. */
    const val SEVERAL = "The request has more than one Host header."

    private const val HTTP_1_0 = "HTTP/1.0"

    /** Characters that the name of a host with its port never holds, and that would end the host in a URL. */
    private const val NOT_IN_A_HOST = "/\\?#@"

    /**
     * Returns the host that the request of [call] names in its `Host` header, with its port, as the client
     * wrote it. A request of HTTP/1.0 need not name a host, and for such a request it is the address and
     * the port of the listener.
     *
     * Returns `null` for a request that names no host: when a request of a later version has no `Host`
     * header or an empty one, when a request has more than one, and when the header holds what no name of
     * a host holds.
     */
    fun of(call: ApplicationCall): String? {
        val connection = call.request.local
        val hosts = headersOf(call)
        val host = hosts.firstOrNull()?.trim()
        return when {
            hosts.size > 1 -> null
            host.isNullOrEmpty() -> hostAndPort(connection.localAddress, connection.localPort).takeIf { connection.version == HTTP_1_0 }
            host.any { it in NOT_IN_A_HOST || it.isWhitespace() || it.isISOControl() } -> null
            else -> host
        }
    }

    private fun headersOf(call: ApplicationCall): List<String> =
        call.request.headers
            .getAll(HttpHeaders.Host)
            .orEmpty()

    private fun hostAndPort(
        address: String,
        port: Int,
    ): String = if (':' in address) "[$address]:$port" else "$address:$port"

    /**
     * Makes [application] answer a request that names no host with the status 400 and the reason, and lets
     * nothing else of the application see such a request.
     */
    fun require(application: Application) {
        application.intercept(ApplicationCallPipeline.Plugins) {
            if (of(call) == null) {
                call.respond(Refusal.content(HttpStatusCode.BadRequest, if (headersOf(call).size > 1) SEVERAL else MISSING))
                finish()
            }
        }
    }
}
