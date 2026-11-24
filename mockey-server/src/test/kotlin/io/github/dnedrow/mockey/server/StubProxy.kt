package io.github.dnedrow.mockey.server

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An HTTP proxy on a free port of the loopback address that forwards nothing: it answers every request
 * itself, with the status 200 and [ANSWER], and keeps what the request presented as its credentials.
 */
internal class StubProxy : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** The value of the `Proxy-Authorization` header of each request, or `null` for a request without one. */
    val authorizations: MutableList<String?> = CopyOnWriteArrayList()

    /** The targets of the requests, as their request lines gave them. */
    val targets: MutableList<String> = CopyOnWriteArrayList()

    /** The URL of the proxy, as the settings of an outbound proxy take it. */
    val url: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Proxy-Authorization")
            targets += exchange.requestURI.toString()
            val body = ANSWER.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val ANSWER = "answered by the proxy"
    }
}
