package io.github.dnedrow.mockey.server

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the tests take for the messages of a log. */
class TestLogTest {
    private val log =
        """
        2026-11-25T02:31:30.517-05:00 INFO  [main] io.ktor.server.Application - Application started in 0.001 seconds.
        2026-11-25T02:31:30.518-05:00 INFO  [main] i.g.d.mockey.server.MockeyServer - The server stops.
        2026-11-25T02:31:30.519-05:00 INFO  [DefaultDispatcher-worker-1] io.ktor.server.Application - Responding at http://127.0.0.1:51946
        2026-11-25T02:31:30.520-05:00 WARN  [main] i.g.d.mockey.server.MockeyServer - 1 connection got no answer.

        2026-11-25T02:31:30.521-05:00 INFO  [main] i.g.d.mockey.server.MockeyServer - The server has stopped.
        """.trimIndent()

    /** Ktor says where a listener responds at a time of its own, so a test that lists what a server says would pass by chance. */
    @Test
    fun theMessagesOfALogAreItsLinesWithoutTheOneThatKtorWritesAtATimeOfItsOwn() {
        assertEquals(
            listOf("Application started in 0.001 seconds.", "The server stops.", "1 connection got no answer.", "The server has stopped."),
            TestLog.messages(log),
        )
    }

    @Test
    fun theMessagesOfALevelAreTheOnesAtThatLevel() {
        assertEquals(listOf("1 connection got no answer."), TestLog.messages(log, "WARN"))
        assertEquals(emptyList(), TestLog.messages(log, "ERROR"))
    }
}
