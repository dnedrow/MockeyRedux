package io.github.dnedrow.mockey.ui

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** The log of the UI in a test. The UI writes it to the standard output. */
internal object TestLog {
    /** Returns what [block] writes to the standard output, which is the log of everything that it runs. */
    fun of(block: () -> Unit): String {
        val before = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            block()
        } finally {
            System.setOut(before)
        }
        return captured.toString()
    }

    /**
     * Returns the messages that [log] holds at [level], each without the time, the level, the thread, and
     * the logger, in their order. Without a level, these are the messages of every level.
     *
     * What Ktor says of its own start is none of them. It writes a part of that on a thread of its own, a
     * moment after the UI has started.
     */
    fun messages(
        log: String,
        level: String? = null,
    ): List<String> =
        log
            .lines()
            .filter { it.isNotEmpty() && (level == null || " ${level.padEnd(LEVEL_WIDTH)} [" in it) }
            .map { it.substringAfter(" - ") }
            .filterNot { message -> OF_KTOR.any(message::startsWith) }

    private const val LEVEL_WIDTH = 5

    /** How the messages begin that Ktor writes when it starts. */
    private val OF_KTOR = listOf("Autoreload is disabled ", "Application started in ", "Responding at ")
}
