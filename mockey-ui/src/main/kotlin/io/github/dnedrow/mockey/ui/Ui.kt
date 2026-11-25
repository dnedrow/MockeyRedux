package io.github.dnedrow.mockey.ui

import org.slf4j.LoggerFactory
import sun.misc.Signal
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Duration.Companion.seconds

/** The time that requests in progress get to finish in when the UI is told to stop. */
private val SHUTDOWN_GRACE_PERIOD = 5.seconds

/**
 * Runs the UI program with [settings]: it starts the UI, serves until the system tells the process to end,
 * and then stops the UI and returns. The requests in progress get a few seconds to finish, see
 * [MockeyUi.stop]. The program ends with the status 0 after that.
 *
 * A signal that arrives while the UI starts is not lost: the UI starts, and stops then.
 *
 * @throws StartupFailure when the UI does not start.
 */
internal fun serve(settings: UiSettings) {
    val termination = TerminationSignal()
    val ui = MockeyUi.start(settings)
    val signal = termination.await()
    LoggerFactory.getLogger(MockeyUi::class.java).info("The program got the signal {}.", signal)
    ui.stop(SHUTDOWN_GRACE_PERIOD)
}

/**
 * The signal with which the system tells the program to end. The Java runtime ends a program on such a
 * signal by itself, at once and with a status other than 0. From the moment that this is created, the
 * signal ends nothing by itself: the program waits for it with [await] and ends in its own time.
 *
 * A signal that is ignored when the program starts stays ignored, as for a program that `nohup` started:
 * the Java runtime hands no such signal on. A runtime that was told to leave the signals alone, with its
 * option `-Xrs`, ends the program on a signal as the system does.
 */
private class TerminationSignal {
    private val arrived = LinkedBlockingQueue<String>()

    init {
        for (name in NAMES) {
            try {
                Signal.handle(Signal(name)) { signal -> arrived.offer("$signal") }
            } catch (_: IllegalArgumentException) {
                // The system has no signal of that name, or the runtime keeps the signal to itself.
            }
        }
    }

    /** Waits until a signal has arrived, and returns its name, such as `SIGTERM`. A signal that arrives after it changes nothing. */
    fun await(): String = arrived.take()

    private companion object {
        /** The signals that end a program: the one of `kill`, the interrupt from the keyboard, and the end of the terminal. */
        val NAMES = listOf("TERM", "INT", "HUP")
    }
}
