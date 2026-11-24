package io.github.dnedrow.mockey.server

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.github.dnedrow.mockey.server.Server")

/**
 * Runs the server program with [settings]: it sets the level of the log and writes the settings in effect
 * to it.
 */
internal fun serve(settings: ServerSettings) {
    ServerLogging.configure(settings.logLevel)
    log.info("{} {} starts with these settings:", PROGRAM_NAME, ServerVersion.text)
    settings.summary().forEach { (name, value) -> log.info("  {} = {}", name, value) }
}
