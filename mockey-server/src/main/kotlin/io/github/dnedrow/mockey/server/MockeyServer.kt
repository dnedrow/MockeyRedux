package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

/**
 * The server while it runs: its engine, and its two listeners, which accept connections from the moment
 * that [start] returns. [since] is the time at which the server started.
 *
 * The mock listener serves the mock endpoint and `/status`. The admin listener serves the admin API, and
 * neither listener serves what the other one does.
 */
internal class MockeyServer private constructor(
    val engine: MockeyEngine,
    val listeners: Listeners,
    val since: Instant,
) : AutoCloseable {
    private var stopped = false

    /**
     * Stops the server, and returns when it has stopped.
     *
     * 1. The listeners accept no connection from now on.
     * 2. The requests that are in progress get [gracePeriod] to finish. A connection is closed as soon as
     *    none is in progress on it, and the server goes on as soon as no connection is left. A request
     *    that is in progress when the time is over gets no answer.
     * 3. The engine is closed, which completes a save of the definitions that is under way, however long
     *    it takes.
     *
     * A server that has stopped stays stopped: a further call does nothing, and one that comes while the
     * server stops returns when it has stopped.
     */
    @Synchronized
    fun stop(gracePeriod: Duration) {
        if (stopped) return
        stopped = true
        log.info("The server stops. It accepts no more connections, and the requests in progress have {} to finish.", gracePeriod)
        val abandoned = listeners.stop(gracePeriod)
        if (abandoned > 0) {
            val connections = if (abandoned == 1) "1 connection" else "$abandoned connections"
            log.warn("{} still had a request in progress after {} and got no answer.", connections, gracePeriod)
        }
        engine.close()
        log.info("The server has stopped.")
    }

    /** Stops the server at once: a request that is in progress gets no answer. The engine completes what it is saving. */
    override fun close() {
        stop(Duration.ZERO)
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(MockeyServer::class.java)

        /**
         * Starts the server with [settings]: it sets the level of the log, writes the settings in effect to
         * the log, creates the engine with the definitions that the server starts with, see [ServerEngine],
         * and starts the two listeners. [clock] tells the time.
         *
         * The listeners are started last, when the engine has its definitions. So a client that gets an
         * answer from the server gets it from a server that is ready, also from `/status`.
         *
         * The server looks at the ports of its listeners before it does anything else, so that a port in
         * use ends the start before any definition is read or stored.
         *
         * @throws StartupFailure when the settings do not let the server start, when the definitions cannot
         *   be loaded, when the initial definitions cannot be taken, or when a listener cannot be bound.
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
            val engine = ServerEngine.start(settings)
            val since = clock.instant()
            val mockModule = mockListenerModule(MockEndpoint(engine, settings.maxRequestBody, clock), StatusEndpoint(since))
            val listeners =
                try {
                    Listeners.start(addresses, mockModule) { mock ->
                        adminListenerModule(AdminApi.of(engine, settings, since, mock.address))
                    }
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
         * Returns the module of the admin listener: the admin [api] for the path `/api/v1` and every path
         * below it. Ktor answers every other request with the status 404.
         */
        fun adminListenerModule(api: AdminApi): suspend Application.() -> Unit =
            {
                intercept(ApplicationCallPipeline.Call) {
                    api.handle(call)
                }
            }
    }
}
