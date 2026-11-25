package io.github.dnedrow.mockey.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * A connection to a server that a test keeps open, as a client does that sends several requests over one
 * connection. The test writes what it likes and reads one response at a time.
 */
internal class OpenConnection(
    address: InetSocketAddress,
) : AutoCloseable {
    private val socket = Socket()

    init {
        socket.connect(address, WAIT_MILLIS)
        socket.soTimeout = WAIT_MILLIS
    }

    /** Writes [text] to the connection, of which every character is one byte. */
    fun send(text: String) {
        socket.getOutputStream().write(text.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }

    /** Sends a GET request for [target] that does not ask for the connection to be closed. */
    fun get(target: String) {
        send(listOf("GET $target HTTP/1.1", "Host: mockey.test", "", "").joinToString(RawHttp.CRLF))
    }

    /**
     * Reads one response: its head, and as many bytes of its body as its `Content-Length` says. The answer
     * says that the connection ended when it ended before the response was whole.
     *
     * @throws SocketTimeoutException when the server writes nothing for five seconds.
     */
    fun response(): RawHttp.Answer = body(head())

    /**
     * Reads the head of a response and leaves its body unread. The answer says that the connection ended
     * when it ended before the head was whole.
     *
     * @throws SocketTimeoutException when the server writes nothing for five seconds.
     */
    fun head(): RawHttp.Answer {
        val bytes = ByteArrayOutputStream()
        val ended =
            reading {
                var next = 0
                while (next >= 0 && !bytes.toString(StandardCharsets.ISO_8859_1).endsWith(RawHttp.CRLF + RawHttp.CRLF)) {
                    next = socket.getInputStream().read()
                    if (next >= 0) bytes.write(next)
                }
                next < 0
            }
        return RawHttp.Answer(bytes.toByteArray(), ended)
    }

    /**
     * Reads the body of the response whose head is [head], and returns the response with it. The answer
     * says that the connection ended when it ended before the body was whole.
     *
     * @throws SocketTimeoutException when the server writes nothing for five seconds.
     */
    fun body(head: RawHttp.Answer): RawHttp.Answer {
        if (head.ended) return head
        val length = head.headerValues("Content-Length").firstOrNull()?.toInt() ?: 0
        val bytes = ByteArrayOutputStream()
        bytes.write(head.text.toByteArray(StandardCharsets.ISO_8859_1))
        val ended =
            reading {
                val body = socket.getInputStream().readNBytes(length)
                bytes.write(body)
                body.size < length
            }
        return RawHttp.Answer(bytes.toByteArray(), ended)
    }

    /**
     * Reads the body of the response whose head is [head] piece by piece, without keeping it, which suits a
     * body that is large. [take] gets each piece and the number of its bytes that count. Returns the number
     * of bytes that arrived, which is less than the `Content-Length` when the connection ended before.
     *
     * @throws SocketTimeoutException when the server writes nothing for five seconds.
     */
    fun body(
        head: RawHttp.Answer,
        take: (ByteArray, Int) -> Unit,
    ): Int {
        val length = head.headerValues("Content-Length").firstOrNull()?.toInt() ?: 0
        val piece = ByteArray(PIECE_BYTES)
        var arrived = 0
        reading {
            while (arrived < length) {
                val read = socket.getInputStream().read(piece, 0, minOf(piece.size, length - arrived))
                if (read < 0) break
                take(piece, read)
                arrived += read
            }
            arrived < length
        }
        return arrived
    }

    /** Returns what [read] returns, which is whether the connection ended, and `true` when the server reset the connection. */
    private fun reading(read: () -> Boolean): Boolean =
        try {
            read()
        } catch (timeout: SocketTimeoutException) {
            throw timeout
        } catch (_: IOException) {
            true
        }

    /** Returns whether the server ends the connection within [waitMillis], and writes nothing more before it does. */
    fun ended(waitMillis: Int = WAIT_MILLIS): Boolean {
        socket.soTimeout = waitMillis
        return try {
            socket.getInputStream().read() < 0
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        } finally {
            socket.soTimeout = WAIT_MILLIS
        }
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val WAIT_MILLIS = 5_000
        const val PIECE_BYTES = 64 * 1024
    }
}
