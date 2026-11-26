package io.github.dnedrow.mockey.ui

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * The UI program in a process of its own, as the build delivers it. A process shows what a test in the
 * runtime of the tests can't: the exit status of the program, and what it writes to its two output streams.
 */
internal object UiProgram {
    /** The command of the Java runtime of this test. */
    val java: String = Path(System.getProperty("java.home"), "bin", "java").toString()
    private const val TIME_LIMIT_SECONDS = 60L
    private const val POLL_MILLIS = 20L
    private val LISTENING = Regex("""The UI accepts connections at http://\S+:(\d+)\. It manages""")
    private const val OUTPUT = "output.txt"
    private const val ERRORS = "errors.txt"

    /** What a run of the program gave: its exit status, and what it wrote to the standard output and to the standard error. */
    class Outcome(
        val status: Int,
        val output: String,
        val errors: String,
    ) {
        override fun toString(): String = "status $status\n--- standard output:\n$output--- standard error:\n$errors"
    }

    /**
     * The program while its process runs. Closing it ends the process and what the process started, and
     * deletes [files], the directory of what the program writes to its output streams.
     */
    class Running(
        private val process: Process,
        private val files: Path,
    ) : AutoCloseable {
        /** What the program has written to the standard output so far. */
        val output: String
            get() = files.resolve(OUTPUT).readText()

        /** What the program has written to the standard error so far. */
        val errors: String
            get() = files.resolve(ERRORS).readText()

        /** Waits until the UI accepts connections, and returns the port on which it does. */
        fun listening(): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS)
            while (true) {
                LISTENING.find(output)?.let { return it.groupValues[1].toInt() }
                check(process.isAlive) { "The program ended before the UI accepted connections: ${outcome()}" }
                check(System.nanoTime() < deadline) { "The UI did not accept connections within $TIME_LIMIT_SECONDS seconds: $output" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        /**
         * Sends the program the signal [name], such as `TERM`, as the command `kill` does. Where a shell
         * started the program and waits for it, the signal goes to the program and not to the shell, which
         * then ends as its script says.
         */
        fun signal(name: String) {
            val started = process.descendants().filter { it.isAlive }.toList()
            check(started.size <= 1) { "The process runs more than one program: ${started.map { it.info().command().orElse("?") }}" }
            val program = started.singleOrNull()?.pid() ?: process.pid()
            val kill = ProcessBuilder("kill", "-$name", "$program").inheritIO().start()
            check(kill.waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS) && kill.exitValue() == 0) { "The signal $name was not sent." }
        }

        /** Waits until the program has ended by itself, and returns what it gave. */
        fun awaitEnd(): Outcome {
            val ended = process.waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
            check(ended) { "The program did not end within $TIME_LIMIT_SECONDS seconds: $output" }
            return outcome()
        }

        private fun outcome() = Outcome(process.exitValue(), output, errors)

        override fun close() {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
            files.toFile().deleteRecursively()
        }
    }

    /**
     * Returns the command that starts a program with what the system does on the signals that end a
     * program.
     *
     * A process hands on to the programs that it starts which signals they ignore, and the Java runtime
     * leaves an ignored signal ignored. So a program that a test starts ignores what the process of the test
     * ignores, which depends on how the build was started. This command sets that right. It needs Perl,
     * which changes what a signal does and then becomes the program.
     */
    fun withSignals(): List<String> {
        val perl = runCatching { ProcessBuilder("perl", "-e", "1").start().waitFor() }.getOrNull() == 0
        TestEnvironment.needs(perl, "Perl, to start a program with the signals as the system delivers them")
        val script = "\$SIG{\$_} = 'DEFAULT' for qw(HUP INT TERM); exec @ARGV or die \"exec: \$!\";"
        return listOf("perl", "-e", script)
    }

    /**
     * Starts [command], which is the program as the build delivers it, with [arguments] in a directory of
     * its own. Of the environment of this test, it gets nothing but where the commands of the system are,
     * and it gets [variables]. So nothing tells a Java runtime which classes or which options to take.
     * [starter] is a command that starts the program which follows it, see [withSignals].
     */
    fun startDelivered(
        command: List<String>,
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
        starter: List<String> = emptyList(),
    ): Running {
        val directory = Files.createTempDirectory("mockey-ui-program")
        return start(starter + command + arguments, directory, directory) { environment ->
            environment.keys.retainAll(setOf("PATH"))
            environment.putAll(variables)
        }
    }

    /**
     * Starts [command] in the directory [directory], which stays as the command leaves it when the command
     * has ended. [environment] makes the environment of the command from the one of this test.
     */
    fun startIn(
        directory: Path,
        command: List<String>,
        environment: (MutableMap<String, String>) -> Unit,
    ): Running = start(command, Files.createTempDirectory("mockey-ui-program"), directory, environment)

    /** Starts [command] in [workingDirectory]. What it writes to its output streams goes to files in [files]. */
    private fun start(
        command: List<String>,
        files: Path,
        workingDirectory: Path,
        environment: (MutableMap<String, String>) -> Unit,
    ): Running {
        val builder =
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectOutput(files.resolve(OUTPUT).toFile())
                .redirectError(files.resolve(ERRORS).toFile())
        environment(builder.environment())
        return Running(builder.start(), files)
    }
}
