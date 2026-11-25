package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import io.ktor.server.application.Application
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * The server while it runs: its engine, and its two listeners, which accept connections from the moment
 * that [start] returns.
 */
internal class MockeyServer private constructor(
    val engine: MockeyEngine,
    val listeners: Listeners,
) : AutoCloseable {
    /** Stops the server at once: its listeners first, and then its engine, which completes what it is saving. */
    override fun close() {
        listeners.stop()
        engine.close()
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(MockeyServer::class.java)

        /** The module of a listener that has nothing to serve: Ktor answers a request that nothing handles with the status 404. */
        private val nothing: suspend Application.() -> Unit = {}

        /**
         * Starts the server with [settings]: it sets the level of the log, writes the settings in effect to
         * the log, creates the engine with the definitions of the definitions location, and starts the two
         * listeners.
         *
         * @throws StartupFailure when the settings do not let the server start, when the definitions cannot
         *   be loaded, or when a listener cannot be bound.
         */
        fun start(settings: ServerSettings): MockeyServer {
            ServerLogging.configure(settings.logLevel)
            log.info("{} {} starts with these settings:", PROGRAM_NAME, ServerVersion.text)
            settings.summary().forEach { (name, value) -> log.info("  {} = {}", name, value) }

            val addresses = ListenerAddresses.of(settings)
            Listeners.requireFree(addresses)
            val engine = engineWith(settings)
            val listeners =
                try {
                    Listeners.start(addresses, mockModule = MockEndpoint(engine, settings.maxRequestBody).module, adminModule = nothing)
                } catch (failure: StartupFailure) {
                    engine.close()
                    throw failure
                }

            log.info("The {} accepts connections on {}.", ListenerKind.MOCK.title, listeners.mock.address.text())
            log.info("The {} accepts connections on {}.", ListenerKind.ADMIN.title, listeners.admin.address.text())
            if (addresses.adminBeyondLoopback && settings.adminToken == null) {
                log.warn(
                    "The {} accepts connections from other machines without an admin token. " +
                        "Whoever reaches it can change the definitions and make the server send requests.",
                    ListenerKind.ADMIN.title,
                )
            }
            return MockeyServer(engine, listeners)
        }

        /**
         * Creates the engine that [settings] ask for. It starts with the definitions that the definitions
         * location holds.
         *
         * @throws StartupFailure when the definitions cannot be loaded.
         */
        private fun engineWith(settings: ServerSettings): MockeyEngine =
            try {
                runBlocking {
                    MockeyEngine.create {
                        storage = FileDefinitionStorage(settings.definitions)
                        readOnly = settings.readOnly
                        tagFilter = settings.tagFilter
                        forwardUnknownTargets = settings.forwardUnknownTargets
                        historyLimit = settings.historyLimit
                        bodyCaptureLimit = settings.bodyCaptureLimit
                        connectTimeout = settings.upstreamConnectTimeout
                        responseTimeout = settings.upstreamResponseTimeout
                    }
                }
            } catch (failure: StorageException) {
                throw StartupFailure("The definitions cannot be loaded. ${failure.message}", failure)
            }
    }
}
