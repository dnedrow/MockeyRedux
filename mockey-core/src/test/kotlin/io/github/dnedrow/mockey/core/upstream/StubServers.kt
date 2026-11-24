package io.github.dnedrow.mockey.core.upstream

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

/** A request as a stub received it. [target] is the request target as it is written on the request line. */
internal class ReceivedRequest(
    val method: String,
    val target: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    /** The values of the headers with the name [name], in any letter case, in the order in which they arrived. */
    fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /** The first value of the header [name], or `null` when the request has no such header. */
    fun header(name: String): String? = headerValues(name).firstOrNull()

    /** The names of the headers in lower case, in the order in which they arrived. */
    val headerNames: List<String>
        get() = headers.map { it.first.lowercase() }

    override fun toString(): String = "$method $target $headers [${body.size} bytes]"
}

/** How a stub tells where the body of its response ends. */
internal enum class Framing {
    /** A `Content-Length` header states the length of the body. */
    CONTENT_LENGTH,

    /** The body is sent in chunks, with `Transfer-Encoding: chunked`. */
    CHUNKED,

    /** The body ends where the stub closes the connection. */
    UNTIL_CLOSE,

    /** A `Content-Length` header states ten bytes more than the stub sends before it closes the connection. */
    CUT_SHORT,
}

/** A response that a stub writes byte for byte: the names of the headers and their order are kept. */
internal class StubResponse(
    val status: Int = 200,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val framing: Framing = Framing.CONTENT_LENGTH,
) {
    constructor(status: Int, body: String, vararg headers: Pair<String, String>) : this(status, headers.toList(), body.toByteArray())
}

/**
 * A minimal HTTP/1.1 server on the loopback address that stands in for a real service. It records every request
 * and answers with what [respond] returns, or closes the connection without an answer when that is `null`. With
 * [tls] it speaks TLS, with the certificate of [StubTls].
 *
 * It writes a response exactly as it is given, keeps connections alive, reads the body of a request by its
 * `Content-Length`, and leaves the body out of the answer to a HEAD request.
 */
internal class StubUpstream(
    private val tls: SSLContext? = null,
    private val respond: (ReceivedRequest) -> StubResponse? = { StubResponse(200, "ok", "Content-Type" to "text/plain") },
) : AutoCloseable {
    private val listener: ServerSocket = listen(tls)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threads = stubThreads("stub-upstream")
    private val received = CopyOnWriteArrayList<ReceivedRequest>()
    private val accepted = AtomicInteger()

    /** Every request that arrived, in the order of arrival. */
    val requests: List<ReceivedRequest>
        get() = received.toList()

    /** How many connections were opened to the stub. */
    val connections: Int
        get() = accepted.get()

    val port: Int
        get() = listener.localPort

    /** The start of the URLs of the stub, such as `http://127.0.0.1:50123`. */
    val origin: String
        get() = "${if (tls == null) "http" else "https"}://127.0.0.1:$port"

    init {
        threads.execute {
            acceptConnections(listener) { socket ->
                accepted.incrementAndGet()
                sockets += socket
                threads.execute { serve(socket) }
            }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach(::closeQuietly)
        threads.shutdownNow()
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                while (true) {
                    val request = readRequest(input) ?: return
                    received += request
                    val response = respond(request) ?: return
                    writeResponse(output, response, withBody = request.method != "HEAD")
                    val closes = response.framing == Framing.UNTIL_CLOSE || response.framing == Framing.CUT_SHORT
                    if (closes || request.header("Connection").equals("close", ignoreCase = true)) return
                }
            }
        } catch (_: IOException) {
            // The client went away, the handshake failed, or the stub is closing.
        } catch (_: InterruptedException) {
            // The stub is closing while it prepares an answer.
        } finally {
            sockets -= socket
        }
    }
}

/**
 * A minimal HTTP proxy on the loopback address that stands in for an outbound proxy. It records every request
 * that it gets, also those that it refuses.
 *
 * It forwards a request with an absolute URL to the host of that URL, without the `Proxy-` headers, and relays
 * the answer. For a `CONNECT` request it opens a tunnel to the named host and port and passes the bytes of both
 * sides through. With [credentials], which is `user:password`, it answers 407 to every request that does not
 * carry these credentials for the scheme Basic.
 */
internal class StubProxy(
    private val credentials: String? = null,
) : AutoCloseable {
    private val listener: ServerSocket = listen(tls = null)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threads = stubThreads("stub-proxy")
    private val received = CopyOnWriteArrayList<ReceivedRequest>()

    /** Every request that the proxy got, in the order of arrival: requests with an absolute URL, and `CONNECT` requests. */
    val requests: List<ReceivedRequest>
        get() = received.toList()

    val port: Int
        get() = listener.localPort

    val url: String
        get() = "http://127.0.0.1:$port"

    /** What a request carries in `Proxy-Authorization` when it has the [credentials]. */
    val authorization: String?
        get() = credentials?.let { "Basic " + Base64.getEncoder().encodeToString(it.toByteArray()) }

    init {
        threads.execute {
            acceptConnections(listener) { socket ->
                sockets += socket
                threads.execute { serve(socket) }
            }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach(::closeQuietly)
        threads.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                while (true) {
                    val request = readRequest(input) ?: return
                    received += request
                    when {
                        authorization != null && request.header("Proxy-Authorization") != authorization -> {
                            val challenge = listOf("Proxy-Authenticate" to "Basic realm=\"stub\"", "Content-Type" to "text/plain")
                            writeResponse(output, StubResponse(407, challenge, "proxy credentials required".toByteArray()), withBody = true)
                        }

                        request.method == "CONNECT" -> {
                            return tunnel(request, input, output)
                        }

                        else -> {
                            return forward(request, output)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // The client or the target went away, or the stub is closing.
        } finally {
            sockets -= client
        }
    }

    /** Passes the bytes of the client and of the target of a `CONNECT` request through, until one side closes. */
    private fun tunnel(
        request: ReceivedRequest,
        fromClient: InputStream,
        toClient: OutputStream,
    ) {
        val host = request.target.substringBeforeLast(':')
        val port = request.target.substringAfterLast(':').toInt()
        Socket(host, port).use { target ->
            sockets += target
            toClient.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            toClient.flush()
            val upward =
                threads.submit {
                    try {
                        fromClient.copyTo(target.getOutputStream())
                    } catch (_: IOException) {
                        // One side closed the tunnel.
                    } finally {
                        closeQuietly(target)
                    }
                }
            try {
                target.getInputStream().copyTo(toClient)
            } finally {
                upward.cancel(true)
                sockets -= target
            }
        }
    }

    /** Sends a request with an absolute URL to the host of that URL and relays the answer, after which the connection ends. */
    private fun forward(
        request: ReceivedRequest,
        toClient: OutputStream,
    ) {
        val url = URI(request.target)
        val path = url.rawPath.ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "")
        val headers =
            request.headers.filterNot { (name, _) ->
                name.startsWith("Proxy-", ignoreCase = true) ||
                    name.equals("Connection", true)
            }
        Socket(url.host, if (url.port < 0) HTTP_PORT else url.port).use { target ->
            sockets += target
            try {
                val head = StringBuilder("${request.method} $path HTTP/1.1\r\n")
                (headers + ("Connection" to "close")).forEach { (name, value) -> head.append("$name: $value\r\n") }
                target.getOutputStream().apply {
                    write(head.append("\r\n").toString().toByteArray(StandardCharsets.ISO_8859_1))
                    write(request.body)
                    flush()
                }
                target.getInputStream().copyTo(toClient)
                toClient.flush()
            } finally {
                sockets -= target
            }
        }
    }

    private companion object {
        const val HTTP_PORT = 80
    }
}

private const val BACKLOG = 50

private fun listen(tls: SSLContext?): ServerSocket {
    val loopback = InetAddress.getByName("127.0.0.1")
    return if (tls == null) ServerSocket(0, BACKLOG, loopback) else tls.serverSocketFactory.createServerSocket(0, BACKLOG, loopback)
}

private fun stubThreads(name: String): ExecutorService =
    Executors.newCachedThreadPool { task ->
        Thread(task, name).apply {
            isDaemon = true
        }
    }

private fun acceptConnections(
    listener: ServerSocket,
    accept: (Socket) -> Unit,
) {
    while (!listener.isClosed) {
        val socket =
            try {
                listener.accept()
            } catch (_: IOException) {
                return
            }
        accept(socket)
    }
}

private fun closeQuietly(socket: Socket) {
    try {
        socket.close()
    } catch (_: IOException) {
        // Closing is all that was wanted.
    }
}

private const val REQUEST_LINE_PARTS = 3

/** Reads one request. Returns `null` when the client closed the connection before a request began. */
private fun readRequest(input: InputStream): ReceivedRequest? {
    val requestLine = readLine(input)?.takeIf { it.isNotEmpty() } ?: return null
    val parts = requestLine.split(" ")
    if (parts.size != REQUEST_LINE_PARTS) throw IOException("Malformed request line: $requestLine")
    val headers = mutableListOf<Pair<String, String>>()
    while (true) {
        val line = readLine(input) ?: throw IOException("The connection closed inside the headers of a request")
        if (line.isEmpty()) break
        headers += line.substringBefore(":").trim() to line.substringAfter(":", "").trim()
    }
    val head = ReceivedRequest(parts[0], parts[1], headers, ByteArray(0))
    if (head.header("Transfer-Encoding") != null) throw IOException("The stub reads the body of a request by its Content-Length only")
    val length = head.header("Content-Length")?.toInt() ?: 0
    val body = input.readNBytes(length)
    if (body.size != length) throw IOException("The connection closed inside the body of a request")
    return ReceivedRequest(head.method, head.target, headers, body)
}

/** Reads up to the next line feed and drops the line ending. Returns `null` at the end of the stream. */
private fun readLine(input: InputStream): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next == -1) return if (line.size() == 0) null else throw IOException("The connection closed inside a line")
        if (next == '\n'.code) break
        line.write(next)
    }
    return line.toString(StandardCharsets.ISO_8859_1).removeSuffix("\r")
}

private val statusesWithoutBody = setOf(204, 304)
private const val CUT_OFF = 10
private const val CHUNK_SIZE = 7

private fun writeResponse(
    output: OutputStream,
    response: StubResponse,
    withBody: Boolean,
) {
    val hasBody = response.status !in statusesWithoutBody
    val head = StringBuilder("HTTP/1.1 ${response.status} Stub\r\n")
    response.headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
    if (hasBody) {
        when (response.framing) {
            Framing.CONTENT_LENGTH -> head.append("Content-Length: ${response.body.size}\r\n")
            Framing.CHUNKED -> head.append("Transfer-Encoding: chunked\r\n")
            Framing.UNTIL_CLOSE -> head.append("Connection: close\r\n")
            Framing.CUT_SHORT -> head.append("Content-Length: ${response.body.size + CUT_OFF}\r\n")
        }
    }
    output.write(head.append("\r\n").toString().toByteArray(StandardCharsets.ISO_8859_1))
    if (hasBody && withBody) {
        if (response.framing == Framing.CHUNKED) {
            response.body.toList().chunked(CHUNK_SIZE).forEach { chunk ->
                output.write("${chunk.size.toString(radix = 16)}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write(chunk.toByteArray())
                output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
            output.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        } else {
            output.write(response.body)
        }
    }
    output.flush()
}
