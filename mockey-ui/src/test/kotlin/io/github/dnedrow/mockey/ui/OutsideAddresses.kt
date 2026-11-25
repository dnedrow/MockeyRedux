package io.github.dnedrow.mockey.ui

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * The addresses of this machine beyond the loopback address, as far as a test can use them.
 *
 * On some machines a program does not reach a socket of the same machine through such an address, whatever
 * the socket listens on: a firewall or a tunnel takes the connection. A test that connects through such an
 * address would then say something about the machine and nothing about the UI. So the addresses are tried
 * first with a socket of the test itself that listens on all addresses.
 */
internal object OutsideAddresses {
    private const val WAIT_MILLIS = 500
    private const val MARK = 42

    /** The IPv4 addresses beyond loopback through which a program on this machine reaches a socket on all addresses. */
    val usable: List<InetAddress> by lazy {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            thread(isDaemon = true, name = "outside-addresses") { markEveryConnection(server) }
            candidates().filter { markedAt(InetSocketAddress(it, server.localPort)) }
        }
    }

    /** Returns whether a connection to [address] is made, and by a program that answers it. */
    fun reaches(address: InetSocketAddress): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(address, WAIT_MILLIS)
                true
            }
        } catch (_: IOException) {
            false
        }

    private fun markedAt(address: InetSocketAddress): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(address, WAIT_MILLIS)
                socket.soTimeout = WAIT_MILLIS
                socket.getInputStream().read() == MARK
            }
        } catch (_: IOException) {
            false
        }

    private fun candidates(): List<InetAddress> =
        NetworkInterface
            .networkInterfaces()
            .filter { it.isUp }
            .flatMap { it.inetAddresses() }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
            .toList()

    private fun markEveryConnection(server: ServerSocket) {
        while (true) {
            try {
                server.accept().use { connection ->
                    connection.getOutputStream().write(MARK)
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
