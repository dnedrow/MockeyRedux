package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.server.TestDefinitions.staticService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the server program ends when the system tells it to: on a signal it stops the server in order, and
 * its process ends with the status 0. The program runs in a process of its own, which gets the signals as
 * the system delivers them.
 */
class ServerProgramStopTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun endWhatTheTestStarted() {
        closeables.reversed().forEach(AutoCloseable::close)
    }

    /**
     * Starts the program on free ports of the loopback address, with [arguments], with the signals that
     * [starter] gives it, and with [runtimeOptions] for its Java runtime.
     */
    private fun start(
        vararg arguments: String,
        starter: List<String> = ServerProgram.withSignals(),
        runtimeOptions: List<String> = emptyList(),
        prepare: (Path) -> Unit = {},
    ): ServerProgram.Running {
        val onLoopback = arrayOf("--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0")
        val program = ServerProgram.start(*onLoopback, *arguments, starter = starter, runtimeOptions = runtimeOptions, prepare = prepare)
        return program.also { closeables += it }
    }

    private fun heldService(): HeldService = HeldService().also { closeables += it }

    private fun <T> CompletableFuture<T>.result(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

    /** Returns the messages with which the program in [outcome] said how it stopped: the ones from the signal on. */
    private fun stopMessages(outcome: ServerProgram.Outcome): List<String> =
        TestLog.messages(outcome.output).dropWhile { !it.startsWith("The program got the signal") }

    private fun stopped(gracePeriod: String) =
        listOf(
            "The server stops. It accepts no more connections, and the requests in progress have $gracePeriod to finish.",
            "The server has stopped.",
        )

    @Test
    fun theProgramStopsTheServerAndEndsWithStatusZeroOnEachSignalThatEndsAProgram() {
        for (signal in listOf("TERM", "INT", "HUP")) {
            val program = start()
            val listening = program.listening()
            val whileRunning = RawHttp.get(listening.mock, "/status").status

            program.signal(signal)
            val outcome = program.awaitEnd()

            assertEquals(200, whileRunning, signal)
            assertEquals(0, outcome.status, "$signal: $outcome")
            assertEquals(listOf("The program got the signal SIG$signal.") + stopped("10s"), stopMessages(outcome), signal)
            assertEquals("", outcome.errors, signal)
            assertNull(RawHttp.getIfReached(listening.mock, REFUSAL_MILLIS, "/status"), "$signal: the mock listener")
            assertNull(RawHttp.getIfReached(listening.admin, REFUSAL_MILLIS, "/status"), "$signal: the admin listener")
        }
    }

    @Test
    fun aRequestInProgressIsAnsweredBeforeTheProgramEnds() {
        val service = heldService()
        val program = start("--shutdown-grace-period", "1m")
        val mock = program.listening().mock
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(mock, service.target) }
        service.awaitRequest()

        program.signal("TERM")
        Thread.sleep(STAYING_MILLIS)
        val runsWithTheRequestInProgress = program.isRunning
        service.release()
        val answer = inProgress.result()
        val outcome = program.awaitEnd()

        assertTrue(runsWithTheRequestInProgress, "The program waits for the request: $outcome")
        assertEquals(200 to HeldService.ANSWER, answer.status to answer.body)
        assertEquals(0, outcome.status, "$outcome")
        assertEquals(listOf("The program got the signal SIGTERM.") + stopped("1m"), stopMessages(outcome))
    }

    @Test
    fun aRequestThatOutlastsTheGracePeriodDoesNotKeepTheProgramFromEndingWithStatusZero() {
        val service = heldService()
        val program = start("--shutdown-grace-period", "300ms")
        val mock = program.listening().mock
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(mock, service.target) }
        service.awaitRequest()

        program.signal("TERM")
        val outcome = program.awaitEnd()
        val answer = inProgress.result()

        val cut = "1 connection still had a request in progress after 300ms and got no answer."
        assertEquals(0, outcome.status, "$outcome")
        assertTrue(answer.nothing && answer.ended, "The client got $answer")
        assertEquals(
            listOf("The program got the signal SIGTERM.", stopped("300ms").first(), cut, stopped("300ms").last()),
            stopMessages(outcome),
        )
    }

    @Test
    fun furtherSignalsChangeNothingWhileTheServerStops() {
        val service = heldService()
        val program = start("--shutdown-grace-period", "1m")
        val mock = program.listening().mock
        val inProgress = CompletableFuture.supplyAsync { RawHttp.get(mock, service.target) }
        service.awaitRequest()

        listOf("TERM", "INT", "TERM", "HUP").forEach(program::signal)
        Thread.sleep(STAYING_MILLIS)
        val runsWithTheRequestInProgress = program.isRunning
        service.release()
        val answer = inProgress.result()
        val outcome = program.awaitEnd()

        assertTrue(runsWithTheRequestInProgress, "The program waits for the request: $outcome")
        assertEquals(200 to HeldService.ANSWER, answer.status to answer.body)
        assertEquals(0, outcome.status, "$outcome")
        assertEquals(listOf("The program got the signal SIGTERM.") + stopped("1m"), stopMessages(outcome))
    }

    /**
     * The scenario "Save in progress completes". The save is the one of a server in writable mode that
     * stores its initial definitions, which are many: the signal arrives when the first of them is stored.
     */
    @Test
    fun aSaveOfTheDefinitionsThatIsUnderWayWhenTheSignalArrivesIsCompletedAndTheProgramEndsWithStatusZero() {
        val document = TestDefinitions.document { repeat(SERVICES) { staticService("Service $it", "service/$it", "only" to "answer $it") } }
        val program =
            start("--writable", "--initial-definitions", "initial.json") { Files.writeString(it.resolve("initial.json"), document) }
        val location = program.workingDirectory.resolve("mockey-definitions")
        val mainFile = location.resolve("mockey.json")
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!Files.isDirectory(location.resolve("services"))) {
            check(program.isRunning && System.nanoTime() < end) { "The program stored nothing: ${program.output}${program.errors}" }
        }

        val wholeAtTheSignal = Files.exists(mainFile)
        program.signal("TERM")
        val outcome = program.awaitEnd()

        assertFalse(wholeAtTheSignal, "The save was under way when the signal was sent")
        assertEquals(0, outcome.status, "$outcome")
        assertEquals("", outcome.errors)
        val stored = runBlocking { FileDefinitionStorage(location).load() }
        assertEquals(List(SERVICES) { "Service $it" }, stored.services.map { it.name })
        assertEquals(List(SERVICES) { "answer $it" }, stored.services.map { it.scenarios.single().responseBody })
        assertEquals(listOf("The program got the signal SIGTERM.") + stopped("10s"), stopMessages(outcome))
    }

    /** A program that `nohup` starts ignores the end of its terminal, and so does the server that is started that way. */
    @Test
    fun aSignalThatTheProgramWasStartedToIgnoreDoesNotEndIt() {
        val program = start(starter = ServerProgram.withSignals(ignored = listOf("HUP")))
        val mock = program.listening().mock

        program.signal("HUP")
        Thread.sleep(STAYING_MILLIS)
        val runsAfterTheIgnoredSignal = program.isRunning
        val answer = RawHttp.get(mock, "/status")
        program.signal("TERM")
        val outcome = program.awaitEnd()

        assertTrue(runsAfterTheIgnoredSignal, "$outcome")
        assertEquals(200, answer.status)
        assertEquals(0, outcome.status, "$outcome")
        assertEquals(listOf("The program got the signal SIGTERM.") + stopped("10s"), stopMessages(outcome))
    }

    /**
     * The option `-Xrs` tells the Java runtime to leave the signals alone, as a program wants that sees to
     * them itself. The server cannot wait for a signal then, and it runs all the same. The signal ends it
     * as the system ends a program: at once, and with a status that tells the signal.
     */
    @Test
    fun aRuntimeThatLeavesTheSignalsAloneRunsTheServerAndEndsAtOnceOnASignal() {
        val program = start(runtimeOptions = listOf("-Xrs"))
        val mock = program.listening().mock

        val answer = RawHttp.get(mock, "/status")
        program.signal("TERM")
        val outcome = program.awaitEnd()

        assertEquals(200, answer.status)
        assertEquals(SIGNAL_EXIT + SIGTERM_NUMBER, outcome.status, "$outcome")
        assertEquals(emptyList(), stopMessages(outcome))
    }

    private companion object {
        const val WAIT_SECONDS = 20L

        /** The status with which a process ends that a signal ended is this number plus the number of the signal. */
        const val SIGNAL_EXIT = 128
        const val SIGTERM_NUMBER = 15

        /** The time within which a client that the server does not serve finds that out. */
        const val REFUSAL_MILLIS = 500

        /** The time for which a program that waits is watched. */
        const val STAYING_MILLIS = 400L

        /**
         * So many services that the server takes a while to store them: it writes each one for good before it
         * goes on, which takes some milliseconds.
         */
        const val SERVICES = 200
    }
}
