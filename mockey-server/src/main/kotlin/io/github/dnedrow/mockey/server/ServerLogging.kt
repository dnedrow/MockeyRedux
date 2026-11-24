package io.github.dnedrow.mockey.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

/**
 * The log of the server program. Every part of the program and every library in it logs through SLF4J, and
 * Logback writes the messages to the standard output, in the layout that the resource `logback.xml` gives.
 */
internal object ServerLogging {
    /** Sets the level from which on messages are written. It holds for every logger of the process. */
    fun configure(level: LogLevel) {
        root().level = level.ofLogback
    }

    /** Returns the level from which on messages are written. */
    fun level(): LogLevel = LogLevel.entries.single { it.ofLogback == root().level }

    private fun root() = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private val LogLevel.ofLogback: Level
        get() =
            when (this) {
                LogLevel.ERROR -> Level.ERROR
                LogLevel.WARN -> Level.WARN
                LogLevel.INFO -> Level.INFO
                LogLevel.DEBUG -> Level.DEBUG
                LogLevel.TRACE -> Level.TRACE
            }
}
