package io.github.dnedrow.mockey.server

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine

/** A Ktor server on the HTTP engine of Mockey. */
internal typealias HttpServer = EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

/**
 * The HTTP engine of the listeners: Ktor on Netty, with the settings that the listeners need. Task 11.0 chose
 * the engine and found the settings, see `docs/decisions/http-stack.md`.
 */
internal object HttpEngine {
    /**
     * The longest request line that a listener takes, in characters: the method, the request target, and the
     * version of HTTP together. Netty takes 4,096 characters unless it is told otherwise, which is too short
     * for a target that holds the URL of a real service with its query string.
     */
    const val MAX_REQUEST_LINE_LENGTH: Int = 64 * 1024

    /**
     * Creates a server that listens on [host] and [port] once it is started, and that runs [module]. The port
     * 0 stands for a port that the system picks.
     *
     * The server is never in the development mode of Ktor, whatever the system property of that mode says.
     */
    fun server(
        host: String,
        port: Int,
        module: suspend Application.() -> Unit,
    ): HttpServer =
        embeddedServer(
            Netty,
            serverConfig {
                developmentMode = false
                module(body = module)
            },
        ) {
            connector {
                this.host = host
                this.port = port
            }
            maxInitialLineLength = MAX_REQUEST_LINE_LENGTH
        }
}
