package io.github.dnedrow.mockey.server

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertTrue

/** What a test asks of the machine that it runs on. */
internal object TestEnvironment {
    /**
     * Whether no test may be left out for want of something on this machine. That holds where the
     * continuous integration runs the tests, which says so in the environment variable `CI`.
     */
    val everyTestRuns: Boolean = System.getenv("CI") == "true"

    /**
     * Leaves the test out when the machine does not have [what] the test needs. Where every test has to
     * run, the test fails instead, so that a machine that lacks something does not pass for a test that
     * passed.
     */
    fun needs(
        available: Boolean,
        what: String,
    ) {
        if (everyTestRuns) assertTrue(available, "This machine has to have $what.")
        assumeTrue(available, "This machine does not have $what.")
    }

    /**
     * Gives [path] the permissions [permissions], in the notation of `ls`, such as `r-xr-xr-x`. Returns
     * whether the file system has such permissions: one that has none leaves the file as it is.
     */
    fun permit(
        path: Path,
        permissions: String,
    ): Boolean =
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
            true
        } catch (_: UnsupportedOperationException) {
            false
        }
}
