package io.github.dnedrow.mockey.parity

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A response that [RawHttpServer] writes exactly as given: header names and their order are kept. */
internal class RawResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
)

/**
 * A minimal HTTP/1.1 server on the loopback address. It exists because the stubs must control every byte of a
 * response: the JDK's built-in server rewrites header names, and legacy Mockey treats `Set-Cookie` and
 * `Set-cookie` differently. The [handler] returns the response to a request, or null to close the connection
 * without answering.
 *
 * It keeps connections alive, reads request bodies by `Content-Length` only, adds a `Content-Length` header to
 * every response that may have a body, and leaves the body out of the response to a HEAD request.
 */
internal class RawHttpServer(
    bindPort: Int,
    private val handler: (RecordedRequest) -> RawResponse?,
) : AutoCloseable {
    private val listener = ServerSocket(bindPort, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "parity-stub").apply { isDaemon = true } }

    val port: Int
        get() = listener.localPort

    init {
        executor.execute(::acceptConnections)
    }

    /**
     * Stops the server. Its port is free when this returns, so that a server that is started next can listen
     * on the same port, as the stubs do that listen on the ports that the fixtures name.
     *
     * Closing the listener does not free the port by itself: the system keeps a listener for as long as a
     * thread waits in it for a connection, and that thread leaves it some time after the listener was
     * closed. So the server waits until its threads have ended.
     */
    override fun close() {
        listener.close()
        connections.forEach { it.close() }
        executor.shutdownNow()
        check(executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "The threads of the stub on port ${listener.localPort} did not end within $CLOSE_TIMEOUT_SECONDS seconds."
        }
    }

    private fun acceptConnections() {
        while (!listener.isClosed) {
            val connection =
                try {
                    listener.accept()
                } catch (_: IOException) {
                    return
                }
            connections += connection
            executor.execute { serve(connection) }
        }
    }

    private fun serve(connection: Socket) {
        try {
            connection.use {
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                while (true) {
                    val request = readRequest(input) ?: return
                    val response = handler(request) ?: return
                    output.write(serialize(response, includeBody = request.method != "HEAD"))
                    output.flush()
                }
            }
        } catch (_: IOException) {
            // The client went away, or the server is closing.
        } finally {
            connections -= connection
        }
    }

    private fun readRequest(input: InputStream): RecordedRequest? {
        val requestLine = readLine(input)?.takeIf { it.isNotEmpty() } ?: return null
        val parts = requestLine.split(" ")
        if (parts.size != REQUEST_LINE_PARTS) throw IOException("Malformed request line: $requestLine")
        val headers = linkedMapOf<String, MutableList<String>>()
        while (true) {
            val line = readLine(input) ?: throw IOException("Connection closed inside the request headers")
            if (line.isEmpty()) break
            val name = line.substringBefore(":").trim()
            headers.getOrPut(name) { mutableListOf() }.add(line.substringAfter(":", "").trim())
        }
        val head = RecordedRequest(parts[0], parts[1], headers, ByteArray(0))
        if (head.header("Transfer-Encoding") != null) throw IOException("The stub reads request bodies by Content-Length only")
        val length = head.header("Content-Length")?.toInt() ?: 0
        val body = input.readNBytes(length)
        if (body.size != length) throw IOException("Connection closed inside the request body")
        return head.copy(body = body)
    }

    /** Reads up to the next line feed and drops the line ending. Returns null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) return if (line.size() == 0) null else throw IOException("Connection closed inside a line")
            if (next == '\n'.code) break
            line.write(next)
        }
        return line.toString(StandardCharsets.ISO_8859_1).removeSuffix("\r")
    }

    private fun serialize(
        response: RawResponse,
        includeBody: Boolean,
    ): ByteArray {
        val head =
            buildString {
                append("HTTP/1.1 ${response.status} ${reasonPhrases[response.status] ?: "Status"}\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (response.status !in statusesWithoutBody) append("Content-Length: ${response.body.size}\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
        return if (includeBody && response.status !in statusesWithoutBody) head + response.body else head
    }

    private companion object {
        const val BACKLOG = 50
        const val CLOSE_TIMEOUT_SECONDS = 30L
        const val REQUEST_LINE_PARTS = 3
        val statusesWithoutBody = setOf(204, 304)
        val reasonPhrases =
            mapOf(
                200 to "OK",
                201 to "Created",
                204 to "No Content",
                301 to "Moved Permanently",
                302 to "Found",
                303 to "See Other",
                304 to "Not Modified",
                307 to "Temporary Redirect",
                404 to "Not Found",
                407 to "Proxy Authentication Required",
                500 to "Internal Server Error",
                503 to "Service Unavailable",
            )
    }
}
