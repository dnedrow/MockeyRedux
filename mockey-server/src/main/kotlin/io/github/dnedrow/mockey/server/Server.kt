package io.github.dnedrow.mockey.server

import java.util.concurrent.CountDownLatch

/**
 * Runs the server program with [settings]: it starts the server and serves until the process is ended.
 *
 * @throws StartupFailure when the server does not start.
 */
internal fun serve(settings: ServerSettings) {
    MockeyServer.start(settings)
    CountDownLatch(1).await()
}
