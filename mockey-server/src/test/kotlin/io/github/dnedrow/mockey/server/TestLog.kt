package io.github.dnedrow.mockey.server

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** The log of the server in a test. The server writes it to the standard output. */
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
     * The message with which Ktor says where a listener responds is none of them. Ktor writes it on a thread
     * of its own, a moment after the listener has started: before what follows the start at one time, and in
     * the middle of it at another.
     */
    fun messages(
        log: String,
        level: String? = null,
    ): List<String> =
        log
            .lines()
            .filter { it.isNotEmpty() && (level == null || " ${level.padEnd(LEVEL_WIDTH)} [" in it) }
            .map { it.substringAfter(" - ") }
            .filterNot { it.startsWith(KTOR_RESPONDS) }

    private const val LEVEL_WIDTH = 5
    private const val KTOR_RESPONDS = "Responding at "
}
