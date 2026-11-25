package io.github.dnedrow.mockey.server

import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.fail

/**
 * A named pipe: a file that a program reads like any other, but whose content comes from a writer at the
 * moment that the writer chooses. Whoever opens it to read waits until a writer has opened it, and whoever
 * opens it to write waits until a reader has. So a test knows the moment at which a program reads the file,
 * and holds the program in that moment for as long as it likes.
 */
internal object NamedPipe {
    private const val TIME_LIMIT_SECONDS = 20L

    /** Makes a named pipe at [path] with the command `mkfifo`, and returns whether there is one now. */
    fun create(path: Path): Boolean =
        try {
            val making = ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start()
            making.inputStream.readAllBytes()
            making.waitFor(TIME_LIMIT_SECONDS, TimeUnit.SECONDS) && making.exitValue() == 0
        } catch (_: IOException) {
            false
        }

    /**
     * Waits until a program has opened the pipe at [path] to read it, and returns the stream that writes
     * what the program reads. The program reads on until the stream is closed.
     *
     * The test fails when nothing reads the pipe in time.
     */
    fun awaitReader(path: Path): OutputStream {
        val opening = CompletableFuture<OutputStream>()
        thread(isDaemon = true, name = "named-pipe-writer") {
            try {
                val stream = FileOutputStream(path.toFile())
                if (!opening.complete(stream)) stream.close()
            } catch (failure: IOException) {
                opening.completeExceptionally(failure)
            }
        }
        return try {
            opening.get(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            opening.cancel(false)
            release(path)
            fail("Nothing read the file $path within $TIME_LIMIT_SECONDS seconds.")
        }
    }

    /**
     * Lets everything go on that waits for the other end of the pipe at [path]: a reader finds the end of
     * the file, and a writer finds a reader.
     */
    fun release(path: Path) {
        try {
            RandomAccessFile(path.toFile(), "rw").close()
        } catch (_: IOException) {
            // There is no pipe, and so nothing waits for it.
        }
    }
}
