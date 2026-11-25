package io.github.dnedrow.mockey.ui

import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import kotlin.time.Duration

/**
 * The UI while it runs: a web server that accepts connections on [address] from the moment that [start]
 * returns, and that shows and changes what a Mockey server holds, through the admin API of that server.
 *
 * The UI holds no definitions and no history itself. What it shows, it asks the server for when a page is
 * loaded, so that a UI that is started anew shows what the one before it showed.
 */
internal class MockeyUi private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val client: AdminClient,
    /** The address and the port on which the UI accepts connections. */
    val address: InetSocketAddress,
) : AutoCloseable {
    private var stopped = false

    /**
     * Stops the UI, and returns when it has stopped. It accepts no connection from now on, and the requests
     * that are in progress get [gracePeriod] to finish. A UI that has stopped stays stopped.
     */
    @Synchronized
    fun stop(gracePeriod: Duration) {
        if (stopped) return
        stopped = true
        log.info("The UI stops. The requests in progress have {} to finish.", gracePeriod)
        server.stop(gracePeriod.inWholeMilliseconds, gracePeriod.inWholeMilliseconds + STOP_TIMEOUT_MILLIS)
        client.close()
        log.info("The UI has stopped.")
    }

    /** Stops the UI at once: a request that is in progress gets no answer. */
    override fun close() {
        stop(Duration.ZERO)
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(MockeyUi::class.java)

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The longest request line that the UI takes, in characters. Netty takes 4,096 characters unless it
         * is told otherwise, which a page with many filters in its address can exceed.
         */
        private const val MAX_REQUEST_LINE_LENGTH = 64 * 1024

        /**
         * Starts the UI with [settings]: it sets the level of the log, writes the settings in effect to the
         * log, and starts the web server. The UI does not ask the server that it manages for anything
         * before a page is loaded, so it starts whether or not that server runs.
         *
         * The UI asks the server through the client that [clientOf] makes for the settings, and it closes
         * that client when it stops. Its pages are those of [areas], and it takes a form of at most
         * [formLimit] bytes.
         *
         * @throws StartupFailure when the address of the UI is not known, or when the UI cannot accept
         *   connections on its address and port.
         */
        fun start(
            settings: UiSettings,
            clientOf: (UiSettings) -> AdminClient = { AdminClient(it.adminUrl, it.adminToken) },
            areas: List<Area> = UiApplication.AREAS,
            formLimit: Long = FormReader.LIMIT,
        ): MockeyUi {
            UiLogging.configure(settings.logLevel)
            log.info("Mockey UI {} starts with these settings:", UiVersion.text)
            settings.summary().forEach { (name, value) -> log.info("  {} = {}", name, value) }
            val address = resolved(settings.host, settings.port)
            requireFree(address)
            if (!address.address.isLoopbackAddress) log.warn(BEYOND_LOOPBACK, address.address.hostAddress)

            val client = clientOf(settings)
            val hosts = AllowedHosts.of(settings)
            val application = UiApplication(settings.adminUrl, client, settings.adminToken != null, hosts, areas, formLimit)
            val server =
                embeddedServer(
                    Netty,
                    serverConfig {
                        developmentMode = false
                        module { application.install(this) }
                    },
                ) {
                    connector {
                        host = address.address.hostAddress
                        port = address.port
                    }
                    maxInitialLineLength = MAX_REQUEST_LINE_LENGTH
                }
            try {
                server.start(wait = false)
            } catch (failure: Exception) {
                // The server that did not start still holds what Ktor set up for it.
                runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
                client.close()
                throw cannotListen(address, failure)
            }
            val connectors = runBlocking { server.engine.resolvedConnectors() }
            val listening = InetSocketAddress(address.address, connectors.first().port)
            log.info("The UI accepts connections at http://{}. It manages the server at {}.", listening.text(), settings.adminUrl)
            return MockeyUi(server, client, listening)
        }

        private const val BEYOND_LOOPBACK =
            "The UI accepts connections on the address {}, which other machines can reach. Everyone who reaches the UI " +
                "can do what the admin API of the server lets its clients do, because the UI knows no users."

        /** Returns the one address that the UI binds for [host]. A name is looked up once, here. */
        private fun resolved(
            host: String,
            port: Int,
        ): InetSocketAddress =
            try {
                InetSocketAddress(InetAddress.getByName(host), port)
            } catch (unknown: UnknownHostException) {
                val how = "Set ${names("host")} to an address of this machine."
                throw StartupFailure("The address of the UI, $host, is not known. $how", unknown)
            }

        /**
         * Checks that a socket can be bound to [address], and leaves nothing bound. The HTTP engine reports
         * a socket that cannot be bound as well, but only seconds later.
         */
        private fun requireFree(address: InetSocketAddress) {
            val socket = ServerSocket()
            try {
                socket.bind(address)
            } catch (failure: IOException) {
                throw cannotListen(address, failure)
            } finally {
                socket.close()
            }
        }

        private fun cannotListen(
            address: InetSocketAddress,
            failure: Exception,
        ): StartupFailure =
            StartupFailure(
                "The UI cannot accept connections on ${address.text()}: ${failure.message ?: failure}. " +
                    "The address is the setting ${names("host")}, and the port is ${names("port")}.",
                failure,
            )

        /** Returns the option and the environment variable of the setting [name], as a message names them. */
        private fun names(name: String): String = "--$name or ${UiCommand.variableOf(name)}"
    }
}

/** Returns the address and the port as a URL writes them, with an address of IPv6 in brackets. */
internal fun InetSocketAddress.text(): String =
    if (address is Inet6Address) "[${address.hostAddress}]:$port" else "${address.hostAddress}:$port"
