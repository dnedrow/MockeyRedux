package io.github.dnedrow.mockey.ui

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

/** The levels of the log, from the one that writes the least to the one that writes the most. */
internal enum class LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
}

/**
 * The log of the UI program. Every part of the program and every library in it logs through SLF4J, and
 * Logback writes the messages to the standard output, in the layout that the resource `logback.xml` gives.
 */
internal object UiLogging {
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
