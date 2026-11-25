package io.github.dnedrow.mockey.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A real service for tests: an HTTP server on a free port of the loopback address that keeps every request
 * exactly as it arrived and answers each one with the same response. It reads from a socket itself, so that
 * no server in between changes what a request target or a header looks like.
 */
internal class StubUpstream(
    private val status: String = "200 OK",
    private val headers: List<String> = listOf("Content-Type: text/plain"),
    private val body: String = "answered by the real service",
) : AutoCloseable {
    /** A request as it arrived: its method, its request target, its header lines, and its body. */
    class Received(
        val method: String,
        val target: String,
        val headers: List<String>,
        val body: ByteArray,
    ) {
        /** Returns the values of the headers with [name], in their order. */
        fun headerValues(name: String): List<String> =
            headers.filter { it.substringBefore(':').equals(name, ignoreCase = true) }.map { it.substringAfter(':').trim() }

        override fun toString(): String = "$method $target $headers [${body.size} bytes]"
    }

    private val server = ServerSocket()

    /** The requests that arrived, in their order. */
    val requests: MutableList<Received> = CopyOnWriteArrayList()

    /** The URL of the service, without a path. */
    val origin: String
        get() = "http://127.0.0.1:${server.localPort}"

    init {
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        thread(isDaemon = true, name = "stub-upstream") { acceptConnections() }
    }

    override fun close() {
        server.close()
    }

    private fun acceptConnections() {
        while (true) {
            val connection =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            thread(isDaemon = true, name = "stub-upstream-connection") { connection.use(::serve) }
        }
    }

    private fun serve(connection: Socket) {
        try {
            val input = connection.getInputStream()
            while (true) {
                val head = readHead(input) ?: return
                val lines = head.split(RawHttp.CRLF)
                val (method, target) = lines.first().split(' ')
                val received = Received(method, target, lines.drop(1), ByteArray(0))
                val length = received.headerValues("Content-Length").firstOrNull()?.toInt() ?: 0
                requests += Received(method, target, received.headers, input.readNBytes(length))
                val answer = if (method == "HEAD") "" else body
                val response = listOf("HTTP/1.1 $status") + headers + listOf("Content-Length: ${body.length}", "", answer)
                connection.getOutputStream().write(response.joinToString(RawHttp.CRLF).toByteArray(StandardCharsets.ISO_8859_1))
                connection.getOutputStream().flush()
            }
        } catch (_: IOException) {
            // The client ended the connection.
        }
    }

    /** Returns the request line and the header lines of the next request, or `null` when the connection has ended. */
    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return null
            bytes.write(next)
            val text = bytes.toString(StandardCharsets.ISO_8859_1)
            if (text.endsWith(RawHttp.CRLF + RawHttp.CRLF)) return text.removeSuffix(RawHttp.CRLF + RawHttp.CRLF)
        }
    }
}
