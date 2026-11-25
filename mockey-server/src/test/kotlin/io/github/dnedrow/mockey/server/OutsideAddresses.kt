package io.github.dnedrow.mockey.server

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * The addresses of this machine beyond the loopback address, as far as a test can use them.
 *
 * On some machines a program does not reach a socket of the same machine through such an address, whatever
 * the socket listens on: a firewall or a tunnel takes the connection. A test that connects through such an
 * address would then say something about the machine and nothing about the server. So the addresses are
 * tried first with a socket of the test itself that listens on all addresses.
 */
internal object OutsideAddresses {
    private const val WAIT_MILLIS = 500
    private const val MARK = "reached"

    /** The IPv4 addresses beyond loopback through which a program on this machine reaches a socket on all addresses. */
    val usable: List<InetAddress> by lazy {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            thread(isDaemon = true, name = "outside-addresses") { answerEveryConnection(server) }
            candidates().filter { RawHttp.getIfReached(InetSocketAddress(it, server.localPort), WAIT_MILLIS)?.body == MARK }
        }
    }

    private fun candidates(): List<InetAddress> =
        NetworkInterface
            .networkInterfaces()
            .filter { it.isUp }
            .flatMap { it.inetAddresses() }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
            .toList()

    private fun answerEveryConnection(server: ServerSocket) {
        val response = listOf("HTTP/1.1 200 OK", "Content-Length: ${MARK.length}", "Connection: close", "", MARK).joinToString(RawHttp.CRLF)
        while (true) {
            try {
                server.accept().use { connection ->
                    connection.soTimeout = WAIT_MILLIS
                    connection.getInputStream().read(ByteArray(1_024))
                    connection.getOutputStream().write(response.toByteArray())
                    connection.getOutputStream().flush()
                }
            } catch (_: SocketException) {
                if (server.isClosed) return
            } catch (_: IOException) {
                // A connection that a firewall took. The next one is accepted.
            }
        }
    }
}
