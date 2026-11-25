package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * A server in the process of a test, for the tests of a module that sees nothing else of the server, such as
 * the parity suite. It is the server that the program starts with the same arguments: [start] reads them as
 * the program does and starts what the program starts, so that the definitions get into the server as they
 * get into the one of the program. Only the end differs: the server runs until the test closes it, and not
 * until the process is told to end.
 *
 * [enableTwist] and [useOutboundProxy] change what the program has no setting for. They stand in for the
 * admin API, which changes it in a running server, until the server has one.
 */
public class ServerUnderTest private constructor(
    private val server: MockeyServer,
) : AutoCloseable {
    /** The address of the mock listener as a client writes it, such as `http://127.0.0.1:49152`. */
    public val mockUrl: URI = URI("http://${server.listeners.mock.address.text()}")

    /**
     * Enables the twist configuration with the name [name].
     *
     * @throws IllegalArgumentException when not exactly one twist configuration has that name.
     */
    public fun enableTwist(name: String) {
        val twist =
            server.engine.definitions.twists
                .singleOrNull { it.name == name }
        requireNotNull(twist) { "Not exactly one twist configuration has the name '$name'." }
        runBlocking { server.engine.twists.enable(twist.id) }.getOrThrow()
    }

    /**
     * Sends the requests to real services through the proxy at [url], to which the server presents [username]
     * and [password]. The definitions hold no credentials, so no document brings them into a server.
     *
     * @throws IllegalArgumentException when [url] is no address of a proxy.
     */
    public fun useOutboundProxy(
        url: String,
        username: String,
        password: String,
    ) {
        val proxy = OutboundProxy(url, enabled = true, credentials = ProxyCredentials(username, password))
        runBlocking { server.engine.updateOutboundProxy { proxy } }.getOrThrow()
    }

    /** Stops the server at once. A request that is in progress gets no answer. */
    override fun close() {
        server.close()
    }

    public companion object {
        /**
         * Starts the server that the program starts with [arguments], and returns when it accepts connections.
         * The environment variables of the program count for nothing: a setting that the arguments do not
         * name has its default.
         *
         * @throws IllegalArgumentException when the arguments start no server: they cannot be used, they ask
         *   for the help, the version, or a command, or the server does not start. The message holds what the
         *   program prints then.
         */
        public fun start(arguments: List<String>): ServerUnderTest {
            var started: MockeyServer? = null
            val printed = StringBuilder()
            val status = program(printed) { started = MockeyServer.start(it) }.execute(arguments)
            return ServerUnderTest(requireNotNull(started) { "No server started, and the program ends with the status $status: $printed" })
        }

        /**
         * Runs the program with [arguments] that name a command which ends by itself, such as `import-legacy`,
         * and returns what the program prints. The environment variables of the program count for nothing.
         *
         * @throws IllegalArgumentException when the arguments start a server, or when the program ends with
         *   another status than 0. The message holds what the program prints then.
         */
        public fun run(arguments: List<String>): String {
            var serves = false
            val printed = StringBuilder()
            val status = program(printed) { serves = true }.execute(arguments)
            require(!serves) { "The arguments $arguments name no command: the program starts a server with them." }
            require(status == 0) { "The program ends with the status $status: $printed" }
            return printed.toString()
        }

        /** The program without its environment, which prints to [printed] and runs the server with [serve]. */
        private fun program(
            printed: StringBuilder,
            serve: (ServerSettings) -> Unit,
        ) = ServerCommand(environment = { null }, output = printed, errors = printed, serve = serve)
    }
}
