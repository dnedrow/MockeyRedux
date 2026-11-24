package io.github.dnedrow.mockey.server

import java.nio.file.Files
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

    /** What a run of the program gave: its exit status and what it wrote to the standard output and the standard error. */
    class Outcome(
        val status: Int,
        val output: String,
        val errors: String,
    ) {
        override fun toString(): String = "status $status\n--- standard output:\n$output--- standard error:\n$errors"
    }

    /**
     * Runs the program with [arguments] to its end. Of the environment of this test it gets everything but
     * the variables of the program, in whose place it gets [variables].
     */
    fun run(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
    ): Outcome {
        val java = Path(System.getProperty("java.home"), "bin", "java").toString()
        val directory = Files.createTempDirectory("mockey-server-program")
        val output = directory.resolve("output.txt")
        val errors = directory.resolve("errors.txt")
        try {
            val builder =
                ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + arguments)
                    .directory(directory.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(errors.toFile())
            builder.environment().keys.removeIf { it.startsWith("MOCKEY_") }
            builder.environment().putAll(variables)
            val process = builder.start()
            try {
                val ended = process.waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
                check(ended) { "The program did not end within $TIME_LIMIT_SECONDS seconds." }
            } finally {
                process.destroyForcibly()
            }
            return Outcome(process.exitValue(), output.readText(), errors.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
