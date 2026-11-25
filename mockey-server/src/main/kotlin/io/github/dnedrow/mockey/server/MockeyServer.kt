package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * The server while it runs: its engine, and its two listeners, which accept connections from the moment
 * that [start] returns. [since] is the time at which the server started.
 *
 * The mock listener serves the mock endpoint and `/status`. The admin listener serves nothing of them.
 */
internal class MockeyServer private constructor(
    val engine: MockeyEngine,
    val listeners: Listeners,
    val since: Instant,
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
         * listeners. [clock] tells the time.
         *
         * The listeners are started last, when the engine has its definitions. So a client that gets an
         * answer from the server gets it from a server that is ready, also from `/status`.
         *
         * @throws StartupFailure when the settings do not let the server start, when the definitions cannot
         *   be loaded, or when a listener cannot be bound.
         */
        fun start(
            settings: ServerSettings,
            clock: Clock = Clock.systemUTC(),
        ): MockeyServer {
            ServerLogging.configure(settings.logLevel)
            log.info("{} {} starts with these settings:", PROGRAM_NAME, ServerVersion.text)
            settings.summary().forEach { (name, value) -> log.info("  {} = {}", name, value) }

            val addresses = ListenerAddresses.of(settings)
            Listeners.requireFree(addresses)
            val engine = engineWith(settings)
            val since = clock.instant()
            val mockModule = mockListenerModule(MockEndpoint(engine, settings.maxRequestBody, clock), StatusEndpoint(since))
            val listeners =
                try {
                    Listeners.start(addresses, mockModule, adminModule = nothing)
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
            return MockeyServer(engine, listeners, since)
        }

        /**
         * Returns the module of the mock listener: the [mock] endpoint for every path under `/service/`, and
         * `/status`, which says that the server runs since the time of [status]. Ktor answers every other
         * request with the status 404.
         */
        fun mockListenerModule(
            mock: MockEndpoint,
            status: StatusEndpoint,
        ): suspend Application.() -> Unit =
            {
                intercept(ApplicationCallPipeline.Call) {
                    if (!status.handle(call)) mock.handle(call)
                }
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
