package io.github.dnedrow.mockey.parity

import java.util.concurrent.CopyOnWriteArrayList

/** A request as a stub received it. [target] is the request target as written on the request line. */
data class RecordedRequest(
    val method: String,
    val target: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** The first value of the header [name], whatever its letter case. */
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

/**
 * A loopback HTTP stub that records every request it receives. Used as a stub upstream, or as a stub outbound
 * proxy (which receives absolute-form targets).
 *
 * A request whose path starts with `/stub/` gets the scripted response of [StubRoutes]. Any other request gets
 * the fixed response given to the constructor. With [requireProxyCredentials] the stub answers 407 until a
 * request carries a `Proxy-Authorization` header, as an authenticating proxy does.
 */
class StubHttpServer(
    val role: String,
    private val status: Int = 200,
    private val responseBody: ByteArray? = null,
    private val responseHeaders: Map<String, String> = mapOf("Content-Type" to "text/plain"),
    bindPort: Int = 0,
    private val requireProxyCredentials: Boolean = false,
) : AutoCloseable {
    private val received = CopyOnWriteArrayList<RecordedRequest>()
    private val server = RawHttpServer(bindPort, ::respond)

    val requests: List<RecordedRequest>
        get() = received.toList()

    val port: Int
        get() = server.port

    override fun close() = server.close()

    private fun respond(request: RecordedRequest): RawResponse? {
        received += request
        return when {
            requireProxyCredentials && request.header("Proxy-Authorization") == null -> StubRoutes.requireProxyCredentials()
            StubRoutes.handles(request) -> StubRoutes.respond(request, port, viaRole = role.takeIf { it == OUTBOUND_PROXY })
            else -> RawResponse(status, responseHeaders.toList(), responseBody ?: "$role:${request.target}".toByteArray())
        }
    }

    companion object {
        private const val UPSTREAM = "upstream"
        private const val OUTBOUND_PROXY = "outbound-proxy"

        fun upstream(bindPort: Int = 0) = StubHttpServer(UPSTREAM, bindPort = bindPort)

        fun outboundProxy(
            bindPort: Int = 0,
            requireProxyCredentials: Boolean = false,
        ) = StubHttpServer(OUTBOUND_PROXY, bindPort = bindPort, requireProxyCredentials = requireProxyCredentials)
    }
}
