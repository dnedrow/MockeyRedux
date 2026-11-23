package io.github.dnedrow.mockey.parity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

data class RecordedRequest(
    val method: String,
    val target: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

/**
 * A loopback HTTP stub that records every request it receives and answers with a fixed response.
 * Used as a stub upstream, or as a stub outbound proxy (which receives absolute-form targets).
 */
class StubHttpServer(
    val role: String,
    private val status: Int = 200,
    private val responseBody: ByteArray = role.toByteArray(),
    private val responseHeaders: Map<String, String> = mapOf("Content-Type" to "text/plain"),
) : AutoCloseable {
    private val received = CopyOnWriteArrayList<RecordedRequest>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val requests: List<RecordedRequest>
        get() = received.toList()

    val port: Int
        get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            received +=
                RecordedRequest(
                    method = exchange.requestMethod,
                    target = exchange.requestURI.toString(),
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = exchange.requestBody.readAllBytes(),
                )
            responseHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(status, if (responseBody.isEmpty()) -1 else responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()
    }

    override fun close() = server.stop(0)

    companion object {
        fun upstream() = StubHttpServer("upstream")

        fun outboundProxy() = StubHttpServer("outbound-proxy")
    }
}
