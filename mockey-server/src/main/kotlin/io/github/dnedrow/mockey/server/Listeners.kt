package io.github.dnedrow.mockey.server

import io.ktor.server.application.Application
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/** The two listeners of the server, with the names of the settings for the address and the port of each. */
internal enum class ListenerKind(
    /** What a message calls the listener. */
    val title: String,
    private val hostSetting: String,
    private val portSetting: String,
) {
    /** The listener of the mock endpoint and of `/status`. */
    MOCK("mock listener", "mock-host", "mock-port"),

    /** The listener of the admin API. */
    ADMIN("admin listener", "admin-host", "admin-port"),
    ;

    /** The option and the environment variable of the address, as a message names them. */
    val hostSettingNames: String
        get() = settingNames(hostSetting)

    /** The option and the environment variable of the port, as a message names them. */
    val portSettingNames: String
        get() = settingNames(portSetting)
}

/** Returns the option and the environment variable of the setting [name], as a message names them. */
internal fun settingNames(name: String): String = "--$name or ${ServerCommand.variableOf(name)}"

/** Returns the address and the port as a URL writes them, with an address of IPv6 in brackets. */
internal fun InetSocketAddress.text(): String =
    if (address is Inet6Address) "[${address.hostAddress}]:$port" else "${address.hostAddress}:$port"

/**
 * The addresses and the ports on which the two listeners are to accept connections. The port 0 stands for
 * a port that the system picks when the listener is started.
 */
internal data class ListenerAddresses(
    val mock: InetSocketAddress,
    val admin: InetSocketAddress,
) {
    /** Whether the admin listener accepts connections that do not come from this machine. */
    val adminBeyondLoopback: Boolean
        get() = !admin.address.isLoopbackAddress

    internal companion object {
        /**
         * Returns the addresses of the listeners that [settings] ask for.
         *
         * An admin listener beyond the loopback address needs an admin token, because the admin API changes the
         * definitions and makes the server send requests. Without a token it is refused, unless the settings
         * allow an admin listener without protection.
         *
         * @throws StartupFailure when an address is not known, or when the admin listener is refused.
         */
        fun of(settings: ServerSettings): ListenerAddresses {
            val addresses =
                ListenerAddresses(
                    mock = resolved(ListenerKind.MOCK, settings.mockHost, settings.mockPort),
                    admin = resolved(ListenerKind.ADMIN, settings.adminHost, settings.adminPort),
                )
            if (addresses.adminBeyondLoopback && settings.adminToken == null && !settings.allowUnprotectedAdmin) {
                throw StartupFailure(
                    "The ${ListenerKind.ADMIN.title} is set to the address ${addresses.admin.address.hostAddress}, which other machines " +
                        "can reach, and there is no admin token. Whoever reaches the admin API can change the definitions and make the " +
                        "server send requests. Set a token with ${settingNames("admin-token")}, or allow the admin API without one with " +
                        "--allow-unprotected-admin or ${ServerCommand.variableOf("allow-unprotected-admin")}=true.",
                )
            }
            return addresses
        }

        /**
         * Returns the one address that a listener binds for [host]. A name is looked up once, here, so that
         * what is checked about the address holds for the address that is bound.
         */
        private fun resolved(
            kind: ListenerKind,
            host: String,
            port: Int,
        ): InetSocketAddress =
            try {
                InetSocketAddress(InetAddress.getByName(host), port)
            } catch (unknown: UnknownHostException) {
                throw StartupFailure(
                    "The address of the ${kind.title}, $host, is not known. Set ${kind.hostSettingNames} to an address of this machine.",
                    unknown,
                )
            }
    }
}

/** A listener that accepts connections: an HTTP server on a socket of its own. */
internal class Listener private constructor(
    val kind: ListenerKind,
    private val server: HttpServer,
    private val listening: Channel,
    boundTo: InetAddress,
) {
    /**
     * The address and the port of the socket on which the listener accepts connections, as the system
     * states them. For a listener on all addresses of IPv4 this can be the address that stands for all
     * addresses of IPv6: where a machine has both, the Java runtime listens on both.
     */
    val socketAddress: InetSocketAddress = listening.localAddress() as InetSocketAddress

    /** The address that the listener was bound to, with the port that its socket has. */
    val address: InetSocketAddress = InetSocketAddress(boundTo, socketAddress.port)

    /** Stops the listener at once: it closes its socket and every connection. */
    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    internal companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Starts a listener on [address] that runs [module].
         *
         * @throws StartupFailure when the listener cannot be bound to the address and the port.
         */
        fun start(
            kind: ListenerKind,
            address: InetSocketAddress,
            module: suspend Application.() -> Unit,
        ): Listener {
            val listening = CopyOnWriteArrayList<Channel>()
            val server =
                HttpEngine.server(address.address.hostAddress, address.port, module) {
                    configureBootstrap = {
                        handler(
                            object : ChannelInitializer<Channel>() {
                                override fun initChannel(channel: Channel) {
                                    listening += channel
                                }
                            },
                        )
                    }
                }
            try {
                server.start(wait = false)
            } catch (failure: Exception) {
                // The server that did not start still holds what Ktor set up for it.
                runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
                throw cannotListen(kind, address, failure)
            }
            return Listener(kind, server, listening.single(), address.address)
        }
    }
}

/** The two listeners of the server while they accept connections. */
internal class Listeners private constructor(
    val mock: Listener,
    val admin: Listener,
) {
    /** Stops both listeners at once. */
    fun stop() {
        admin.stop()
        mock.stop()
    }

    internal companion object {
        /**
         * Checks that a socket can be bound to each of [addresses], and leaves nothing bound.
         *
         * The engine reports a socket that cannot be bound as well, but only seconds later, see
         * `docs/decisions/http-stack.md`. This check tells at once, and before the server has done anything
         * that lasts, such as storing its initial definitions.
         *
         * @throws StartupFailure when a socket cannot be bound.
         */
        fun requireFree(addresses: ListenerAddresses) {
            bound(ListenerKind.MOCK, addresses.mock).use {
                val samePort = addresses.admin.port != 0 && addresses.admin.port == addresses.mock.port
                bound(
                    ListenerKind.ADMIN,
                    addresses.admin,
                    hint = if (samePort) " The ${ListenerKind.MOCK.title} is set to the same port." else "",
                ).close()
            }
        }

        /**
         * Starts the two listeners on [addresses]. The mock listener runs [mockModule], and the admin listener
         * runs [adminModule].
         *
         * @throws StartupFailure when a listener cannot be bound. No listener accepts connections then.
         */
        fun start(
            addresses: ListenerAddresses,
            mockModule: suspend Application.() -> Unit,
            adminModule: suspend Application.() -> Unit,
        ): Listeners {
            val mock = Listener.start(ListenerKind.MOCK, addresses.mock, mockModule)
            val admin =
                try {
                    Listener.start(ListenerKind.ADMIN, addresses.admin, adminModule)
                } catch (failure: StartupFailure) {
                    mock.stop()
                    throw failure
                }
            return Listeners(mock, admin)
        }

        /** Returns a socket that is bound to [address], which shows that a listener can be bound to it. */
        private fun bound(
            kind: ListenerKind,
            address: InetSocketAddress,
            hint: String = "",
        ): ServerSocket {
            val socket = ServerSocket()
            try {
                socket.bind(address)
            } catch (failure: IOException) {
                socket.close()
                throw cannotListen(kind, address, failure, hint)
            }
            return socket
        }
    }
}

/** Returns the failure of a listener that cannot be bound to [address], which names the address and the port. */
private fun cannotListen(
    kind: ListenerKind,
    address: InetSocketAddress,
    failure: Exception,
    hint: String = "",
): StartupFailure =
    StartupFailure(
        "The ${kind.title} cannot accept connections on ${address.text()}: ${failure.message ?: failure}.$hint " +
            "The address is the setting ${kind.hostSettingNames}, and the port is ${kind.portSettingNames}.",
        failure,
    )
