package io.github.dnedrow.mockey.server

import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * The server program in a process of its own: the main function that the start script runs, on the class
 * path of this test, in the Java runtime of this test. It shows what only a process shows, such as the exit
 * status and what the program writes to its two output streams.
 */
internal object ServerProgram {
    private const val MAIN_CLASS = "io.github.dnedrow.mockey.server.MainKt"

    /** The command of the Java runtime of this test. */
    val java: String = Path(System.getProperty("java.home"), "bin", "java").toString()
    private const val TIME_LIMIT_SECONDS = 60L
    private const val POLL_MILLIS = 20L
    private val LISTENING = Regex("""The (mock|admin) listener accepts connections on (\S+):(\d+)\.""")

    /** What a run of the program gave: its exit status and what it wrote to the standard output and the standard error. */
    class Outcome(
        val status: Int,
        val output: String,
        val errors: String,
    ) {
        override fun toString(): String = "status $status\n--- standard output:\n$output--- standard error:\n$errors"
    }

    /** Where the listeners of a running program accept connections: the address as the log states it, and the port. */
    class Listening(
        val mockHost: String,
        val mockPort: Int,
        val adminHost: String,
        val adminPort: Int,
    ) {
        /** The mock listener as a program on this machine reaches it. */
        val mock: InetSocketAddress = RawHttp.loopback(mockPort)

        /** The admin listener as a program on this machine reaches it. */
        val admin: InetSocketAddress = RawHttp.loopback(adminPort)
    }

    /** The program while its process runs. Closing it ends the process. */
    class Running(
        private val process: Process,
        private val directory: Path,
    ) : AutoCloseable {
        /** What the program has written to the standard output so far. */
        val output: String
            get() = directory.resolve(OUTPUT).readText()

        /** What the program has written to the standard error so far. */
        val errors: String
            get() = directory.resolve(ERRORS).readText()

        /** The directory in which the program runs, which is its own. */
        val workingDirectory: Path
            get() = directory

        /** Waits until both listeners accept connections, and returns where they do. */
        fun listening(): Listening {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS)
            while (true) {
                val found = LISTENING.findAll(output).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3].toInt()) }
                val mock = found["mock"]
                val admin = found["admin"]
                if (mock != null && admin != null) return Listening(mock.first, mock.second, admin.first, admin.second)
                check(process.isAlive) { "The program ended before its listeners accepted connections: ${outcome()}" }
                check(
                    System.nanoTime() < deadline,
                ) { "The listeners did not accept connections within $TIME_LIMIT_SECONDS seconds: $output" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        /**
         * Sends the program the signal [name], such as `TERM`, as the command `kill` does.
         *
         * A program that was started with [withSignals] takes the signal as the system delivers it. Any
         * other program takes it as the process of the test does, which can be one that ignores it.
         */
        fun signal(name: String) {
            val kill = ProcessBuilder("kill", "-$name", "${process.pid()}").inheritIO().start()
            check(kill.waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS) && kill.exitValue() == 0) { "The signal $name was not sent." }
        }

        /** Returns whether the process of the program runs. */
        val isRunning: Boolean
            get() = process.isAlive

        /** Waits until the program has ended by itself, and returns what it gave. */
        fun awaitEnd(): Outcome {
            val ended = process.waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
            check(ended) { "The program did not end within $TIME_LIMIT_SECONDS seconds: $output" }
            return outcome()
        }

        private fun outcome() = Outcome(process.exitValue(), output, errors)

        override fun close() {
            process.destroyForcibly().waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
            directory.toFile().deleteRecursively()
        }
    }

    private const val OUTPUT = "output.txt"
    private const val ERRORS = "errors.txt"

    /**
     * Returns the command that starts a program with what the system does on the signals that end a program,
     * but for the signals [ignored], which the program is to ignore, as one that `nohup` started ignores
     * the end of its terminal.
     *
     * A process hands on to the programs that it starts which signals they ignore, and the Java runtime
     * leaves an ignored signal ignored. So a program that a test starts ignores what the process of the test
     * ignores, which depends on how the build was started. This command sets that right. It needs Perl,
     * which changes what a signal does and then becomes the program.
     */
    fun withSignals(ignored: List<String> = emptyList()): List<String> {
        val perl = runCatching { ProcessBuilder("perl", "-e", "1").start().waitFor() }.getOrNull() == 0
        TestEnvironment.needs(perl, "Perl, to start a program with the signals as the system delivers them")
        val script =
            "\$SIG{\$_} = 'DEFAULT' for qw(HUP INT TERM); \$SIG{\$_} = 'IGNORE' for qw(${ignored.joinToString(" ")}); " +
                "exec @ARGV or die \"exec: \$!\";"
        return listOf("perl", "-e", script)
    }

    /**
     * Starts the program with [arguments] in a directory of its own, which [prepare] fills first. Of the
     * environment of this test it gets everything but the variables of the program, in whose place it gets
     * [variables]. [starter] is a command that starts the program which follows it, see [withSignals], and
     * [runtimeOptions] are options for the Java runtime of the program.
     */
    fun start(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
        starter: List<String> = emptyList(),
        runtimeOptions: List<String> = emptyList(),
        prepare: (Path) -> Unit = {},
    ): Running {
        val program = listOf(java) + runtimeOptions + listOf("-cp", System.getProperty("java.class.path"), MAIN_CLASS)
        return start(starter + program + arguments, prepare) { environment ->
            environment.keys.removeIf { it.startsWith("MOCKEY_") }
            environment.putAll(variables)
        }
    }

    /**
     * Starts [command], which is the program as the build delivers it, with [arguments] in a directory of
     * its own. Of the environment of this test it gets nothing but where the commands of the system are,
     * and it gets [variables]: nothing tells a Java runtime which classes or which options to take.
     * [starter] is a command that starts the program which follows it, see [withSignals].
     */
    fun startDelivered(
        command: List<String>,
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
        starter: List<String> = emptyList(),
    ): Running =
        start(starter + command + arguments, prepare = {}) { environment ->
            environment.keys.retainAll(setOf("PATH"))
            environment.putAll(variables)
        }

    /** Starts [command] in a directory of its own, which [prepare] fills first, with the environment that [environment] leaves. */
    private fun start(
        command: List<String>,
        prepare: (Path) -> Unit,
        environment: (MutableMap<String, String>) -> Unit,
    ): Running {
        val directory = Files.createTempDirectory("mockey-server-program")
        prepare(directory)
        val builder =
            ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(directory.resolve(OUTPUT).toFile())
                .redirectError(directory.resolve(ERRORS).toFile())
        environment(builder.environment())
        return Running(builder.start(), directory)
    }

    /** Runs the program with [arguments] to its end, as [start] starts it, and returns what it gave. */
    fun run(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
    ): Outcome = start(*arguments, variables = variables).use { it.awaitEnd() }
}
