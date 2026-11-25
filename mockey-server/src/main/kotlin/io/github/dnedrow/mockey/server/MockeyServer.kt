package io.github.dnedrow.mockey.server

import io.ktor.server.application.Application
import org.slf4j.LoggerFactory

/**
 * The server while it runs: its two listeners, which accept connections from the moment that [start]
 * returns.
 */
internal class MockeyServer private constructor(
    val listeners: Listeners,
) : AutoCloseable {
    /** Stops the server at once. */
    override fun close() {
        listeners.stop()
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(MockeyServer::class.java)

        /** The module of a listener that has nothing to serve: Ktor answers a request that nothing handles with the status 404. */
        private val nothing: suspend Application.() -> Unit = {}

        /**
         * Starts the server with [settings]: it sets the level of the log, writes the settings in effect to
         * the log, and starts the two listeners.
         *
         * @throws StartupFailure when the settings do not let the server start, or when a listener cannot be bound.
         */
        fun start(settings: ServerSettings): MockeyServer {
            ServerLogging.configure(settings.logLevel)
            log.info("{} {} starts with these settings:", PROGRAM_NAME, ServerVersion.text)
            settings.summary().forEach { (name, value) -> log.info("  {} = {}", name, value) }

            val addresses = ListenerAddresses.of(settings)
            Listeners.requireFree(addresses)
            val listeners = Listeners.start(addresses, mockModule = nothing, adminModule = nothing)

            log.info("The {} accepts connections on {}.", ListenerKind.MOCK.title, listeners.mock.address.text())
            log.info("The {} accepts connections on {}.", ListenerKind.ADMIN.title, listeners.admin.address.text())
            if (addresses.adminBeyondLoopback && settings.adminToken == null) {
                log.warn(
                    "The {} accepts connections from other machines without an admin token. " +
                        "Whoever reaches it can change the definitions and make the server send requests.",
                    ListenerKind.ADMIN.title,
                )
            }
            return MockeyServer(listeners)
        }
    }
}
