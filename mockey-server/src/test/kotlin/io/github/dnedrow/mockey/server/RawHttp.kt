package io.github.dnedrow.mockey.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * HTTP over a socket, for tests that have to say exactly what a client writes to a connection, or that have
 * to see everything that a server writes back.
 */
internal object RawHttp {
    const val CRLF = "\r\n"
    private const val WAIT_MILLIS = 5_000
    private const val BUFFER_SIZE = 8_192

    /** Ports below the ones that Linux, macOS, and Windows give to clients, and above the ones of well-known services. */
    private const val FIRST_PORT_OF_NO_CLIENT = 20_000
    private const val FIRST_PORT_OF_CLIENTS = 30_000
    private const val PORT_ATTEMPTS = 200

    /** What a server wrote to a connection, and whether it [ended] the connection. Every byte is one character. */
    class Answer(
        bytes: ByteArray,
        val ended: Boolean,
    ) {
        private val text = String(bytes, StandardCharsets.ISO_8859_1)
        private val head = text.substringBefore(CRLF + CRLF).split(CRLF)

        /** Whether the server wrote nothing at all. */
        val nothing: Boolean = bytes.isEmpty()
        val statusLine: String = head.first()

        /** The status code of the response, or `null` when the server wrote no status line. */
        val status: Int? = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        val headers: List<String> = head.drop(1)
        val body: String = text.substringAfter(CRLF + CRLF, missingDelimiterValue = "")

        /** Returns the values of the headers with [name], in their order. */
        fun headerValues(name: String): List<String> =
            headers.filter { it.substringBefore(':').equals(name, ignoreCase = true) }.map { it.substringAfter(':').trim() }

        override fun toString(): String = if (nothing) "nothing" else "$statusLine $headers [$body]"
    }

    /**
     * Returns the bytes of a request as a client writes them to a connection. Every character of the request
     * is one byte. A request with a [body] says how long the body is.
     */
    fun request(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        body: String = "",
    ): ByteArray {
        val length = if (body.isEmpty()) emptyList() else listOf("Content-Length: ${body.length}")
        val lines = listOf("$method $target HTTP/1.1", "Host: mockey.test", "Connection: close") + headers + length + listOf("", body)
        return lines.joinToString(CRLF).toByteArray(StandardCharsets.ISO_8859_1)
    }

    /**
     * Writes [request] to a new connection to [address], and returns what the server writes until it ends the
     * connection, or until it has written nothing for [waitMillis].
     *
     * @throws IOException when no connection is made within [waitMillis], or when nothing listens at
     *   [address].
     */
    fun exchange(
        address: InetSocketAddress,
        request: ByteArray,
        waitMillis: Int = WAIT_MILLIS,
    ): Answer =
        Socket().use { socket ->
            socket.connect(address, waitMillis)
            // A client can get the port that it connects to for its own end, when nothing listens there and
            // the port is one of those that the system gives to clients. Then it is connected to itself.
            if (socket.localSocketAddress == socket.remoteSocketAddress) throw ConnectException("Nothing listens at $address.")
            socket.soTimeout = waitMillis
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            val ended =
                try {
                    socket.getOutputStream().write(request)
                    socket.getOutputStream().flush()
                    generateSequence { socket.getInputStream().read(buffer).takeIf { it >= 0 } }.forEach { bytes.write(buffer, 0, it) }
                    true
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: SocketException) {
                    true
                }
            Answer(bytes.toByteArray(), ended)
        }

    /** Sends a GET request for [target] to [address] and returns the answer. */
    fun get(
        address: InetSocketAddress,
        target: String = "/",
        headers: List<String> = emptyList(),
    ): Answer = exchange(address, request("GET", target, headers))

    /**
     * Sends a GET request for [target] to [address] and returns the answer, or `null` when no connection is
     * made within [waitMillis] or the server writes nothing in that time.
     */
    fun getIfReached(
        address: InetSocketAddress,
        waitMillis: Int,
        target: String = "/",
    ): Answer? =
        try {
            exchange(address, request("GET", target), waitMillis).takeUnless { it.nothing }
        } catch (_: IOException) {
            null
        }

    /** Returns the loopback address with [port]. */
    fun loopback(port: Int): InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    /** Returns [count] ports that nothing listens on. */
    fun freePorts(count: Int): List<Int> {
        val sockets = List(count) { ServerSocket(0) }
        return sockets.map { it.localPort }.also { sockets.forEach(ServerSocket::close) }
    }

    /**
     * Returns [count] ports that nothing listens on, and that the system gives to no client for its own end
     * of a connection. A test needs such ports when it connects to a port before its server listens there:
     * a client that got the port of the server for itself would be connected to itself, and it would hold
     * the port when the server wants it.
     */
    fun freePortsThatNoClientGets(count: Int): List<Int> {
        val ports = mutableListOf<Int>()
        repeat(PORT_ATTEMPTS) {
            val candidate = Random.nextInt(FIRST_PORT_OF_NO_CLIENT, FIRST_PORT_OF_CLIENTS)
            if (ports.size < count && candidate !in ports && isFree(candidate)) ports += candidate
        }
        check(ports.size == count) { "Found only ${ports.size} of $count free ports." }
        return ports
    }

    /**
     * Returns whether nothing on the loopback address holds [port]: no listener, and no connection that is
     * open or that was closed a moment ago.
     */
    private fun isFree(port: Int): Boolean =
        try {
            ServerSocket().use {
                it.reuseAddress = false
                it.bind(loopback(port))
            }
            true
        } catch (_: IOException) {
            false
        }
}
