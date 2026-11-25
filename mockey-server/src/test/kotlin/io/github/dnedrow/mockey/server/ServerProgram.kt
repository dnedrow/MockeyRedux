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
     * Starts the program with [arguments] in a directory of its own. Of the environment of this test it gets
     * everything but the variables of the program, in whose place it gets [variables].
     */
    fun start(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
    ): Running {
        val java = Path(System.getProperty("java.home"), "bin", "java").toString()
        val directory = Files.createTempDirectory("mockey-server-program")
        val builder =
            ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + arguments)
                .directory(directory.toFile())
                .redirectOutput(directory.resolve(OUTPUT).toFile())
                .redirectError(directory.resolve(ERRORS).toFile())
        builder.environment().keys.removeIf { it.startsWith("MOCKEY_") }
        builder.environment().putAll(variables)
        return Running(builder.start(), directory)
    }

    /** Runs the program with [arguments] to its end, as [start] starts it, and returns what it gave. */
    fun run(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
    ): Outcome = start(*arguments, variables = variables).use { it.awaitEnd() }
}
