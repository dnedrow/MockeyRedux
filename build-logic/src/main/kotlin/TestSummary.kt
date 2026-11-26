import org.gradle.api.logging.Logger
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

/**
 * Says in the log of a build how many tests of a module ran, once its test task has run them. A build that
 * left tests out shows there.
 */
class TestSummary(
    private val module: String,
    private val log: Logger,
) : TestListener {
    override fun beforeSuite(suite: TestDescriptor) = Unit

    override fun afterSuite(
        suite: TestDescriptor,
        result: TestResult,
    ) {
        // The suite without a parent holds every test of the task.
        if (suite.parent == null) {
            log.lifecycle("$module: ${result.testCount} tests, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
        }
    }

    override fun beforeTest(test: TestDescriptor) = Unit

    override fun afterTest(
        test: TestDescriptor,
        result: TestResult,
    ) = Unit
}
