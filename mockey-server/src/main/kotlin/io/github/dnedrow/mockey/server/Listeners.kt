package io.github.dnedrow.mockey.server

import io.ktor.server.application.Application
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    private val connections: Connections,
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

    /**
     * Closes every connection as soon as no request is in progress on it, and the socket on which the
     * listener accepts connections. The connections with a request in progress stay until [stop].
     *
     * A client that comes from now on gets no answer: its connection is refused, or it is reset, see
     * `docs/decisions/http-stack.md`.
     */
    fun takeNoMoreRequests() {
        connections.closeWhenIdle()
        listening.close().syncUninterruptibly()
    }

    /** Waits until no connection is open, for [timeout] at most. */
    fun awaitNoConnections(timeout: Duration) {
        connections.awaitClosed(timeout)
    }

    /**
     * Stops the listener at once: it closes its socket and every connection, also one with a request in
     * progress, which gets no answer. Returns the number of connections that had one.
     *
     * The handlers of such requests are ended before the engine is stopped, so that none of them goes on
     * when the listener has stopped: a handler that waits for a real service or for the delay of a scenario
     * would try to answer on a connection that is no more. Ktor runs every handler in a coroutine of its
     * application, so the listener ends those and waits until they have ended.
     */
    fun stop(): Int {
        val abandoned = connections.close()
        val handlers = server.application.coroutineContext.job
        handlers.cancelChildren(CancellationException("The listener stops."))
        runBlocking { withTimeoutOrNull(HANDLERS_TIMEOUT) { handlers.children.toList().joinAll() } }
        server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
        return abandoned
    }

    internal companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The time that the handlers get to end in when they are told to. A handler that is told ends at once. */
        private val HANDLERS_TIMEOUT = 5.seconds

        /**
         * Starts a listener on [address] that runs [module] for the requests that name a host. The listener
         * answers every other request itself, see [RequestHost]. It also sees to it that a client gets the
         * answer to a request whose body nothing has read, see [UnreadBody].
         *
         * @throws StartupFailure when the listener cannot be bound to the address and the port.
         */
        fun start(
            kind: ListenerKind,
            address: InetSocketAddress,
            module: suspend Application.() -> Unit,
        ): Listener {
            val listening = CopyOnWriteArrayList<Channel>()
            val connections = Connections()
            val guarded: suspend Application.() -> Unit = {
                RequestHost.require(this)
                UnreadBody.dropBeforeTheLastAnswer(this)
                module()
            }
            val server =
                HttpEngine.server(address.address.hostAddress, address.port, guarded) {
                    configureBootstrap = {
                        handler(
                            object : ChannelInitializer<Channel>() {
                                override fun initChannel(channel: Channel) {
                                    listening += channel
                                }
                            },
                        )
                    }
                    channelPipelineConfig = { connections.watch(this) }
                }
            try {
                server.start(wait = false)
            } catch (failure: Exception) {
                // The server that did not start still holds what Ktor set up for it.
                runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
                throw cannotListen(kind, address, failure)
            }
            return Listener(kind, server, listening.single(), connections, address.address)
        }
    }
}

/** The two listeners of the server while they accept connections. */
internal class Listeners private constructor(
    val mock: Listener,
    val admin: Listener,
) {
    /**
     * Stops both listeners. They accept no connection from now on, and they let the requests that are in
     * progress finish, for [gracePeriod] at most. A request that is in progress after that gets no answer.
     * Returns the number of connections that were open then.
     */
    fun stop(gracePeriod: Duration): Int {
        val both = listOf(admin, mock)
        both.forEach(Listener::takeNoMoreRequests)
        val end = TimeSource.Monotonic.markNow() + gracePeriod
        both.forEach { it.awaitNoConnections(-end.elapsedNow()) }
        return both.sumOf(Listener::stop)
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
         * Starts the two listeners on [addresses]. The mock listener runs [mockModule]. The admin listener
         * runs the module that [adminModule] returns for the mock listener, which accepts connections by
         * then: what the admin listener serves says where the mock endpoint is.
         *
         * @throws StartupFailure when a listener cannot be bound. No listener accepts connections then.
         */
        fun start(
            addresses: ListenerAddresses,
            mockModule: suspend Application.() -> Unit,
            adminModule: (mock: Listener) -> suspend Application.() -> Unit,
        ): Listeners {
            val mock = Listener.start(ListenerKind.MOCK, addresses.mock, mockModule)
            val admin =
                try {
                    Listener.start(ListenerKind.ADMIN, addresses.admin, adminModule(mock))
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
