package io.github.dnedrow.mockey.server

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The server program as a process: its exit status, its two output streams, and its environment. */
class ServerProgramTest {
    @Test
    fun theHelpOptionPrintsTheSettingsAndEndsTheProgramWithStatusZero() {
        val outcome = ServerProgram.run("--help")

        assertEquals(0, outcome.status, "$outcome")
        assertTrue(outcome.output.startsWith("Usage: mockey-server [<options>]\n"), "$outcome")
        assertContains(outcome.output, "  --mock-port=<port>\n")
        assertContains(outcome.output, "      Environment variable: MOCKEY_MOCK_PORT\n      Default: 8080\n")
        assertEquals("", outcome.errors)
    }

    @Test
    fun theVersionOptionPrintsTheVersionAndEndsTheProgramWithStatusZero() {
        val outcome = ServerProgram.run("--version")

        assertEquals(0, outcome.status, "$outcome")
        assertEquals("mockey-server version ${System.getProperty("mockey.version")}\n", outcome.output)
        assertEquals("", outcome.errors)
    }

    @Test
    fun anArgumentThatCannotBeUsedEndsTheProgramWithAnotherStatus() {
        val outcome = ServerProgram.run("--mock-port", "http")

        assertEquals(1, outcome.status, "$outcome")
        assertEquals(
            "Usage: mockey-server [<options>]\n\nError: invalid value for --mock-port: http is not a valid integer\n",
            outcome.errors,
        )
        assertEquals("", outcome.output)
    }

    @Test
    fun aVariableThatCannotBeUsedEndsTheProgramWithAnotherStatus() {
        val outcome = ServerProgram.run(variables = mapOf("MOCKEY_ADMIN_PORT" to "70000"))

        assertEquals(1, outcome.status, "$outcome")
        assertContains(outcome.errors, "Error: invalid value for MOCKEY_ADMIN_PORT: 70000 is not in the valid range of 0 to 65535")
        assertEquals("", outcome.output)
    }

    @Test
    fun theProgramTakesItsSettingsFromItsOptionsThenFromItsEnvironment() {
        val variables = mapOf("MOCKEY_MOCK_PORT" to "9000", "MOCKEY_ADMIN_PORT" to "9200", "MOCKEY_TAG_FILTER" to "regression")

        val outcome = ServerProgram.run("--mock-port", "9100", variables = variables)

        assertEquals(0, outcome.status, "$outcome")
        assertContains(outcome.output, " - mockey-server ${System.getProperty("mockey.version")} starts with these settings:\n")
        assertContains(outcome.output, " -   mock-port = 9100\n")
        assertContains(outcome.output, " -   admin-port = 9200\n")
        assertContains(outcome.output, " -   tag-filter = regression\n")
        assertContains(outcome.output, " -   history-limit = 1000\n")
        assertEquals("", outcome.errors)
    }

    @Test
    fun theProgramWritesItsLogToTheStandardOutputWithoutTheAdminToken() {
        val ofVariable = "tk-0b7d43e19a6c"
        val ofOption = "tk-e52f8c0a1d37"

        val outcome =
            ServerProgram.run(
                "--log-level",
                "trace",
                "--admin-token",
                ofOption,
                variables =
                    mapOf("MOCKEY_ADMIN_TOKEN" to ofVariable),
            )

        assertEquals(0, outcome.status, "$outcome")
        assertContains(outcome.output, " -   admin-token = set\n")
        assertContains(outcome.output, " -   log-level = trace\n")
        assertEquals("", outcome.errors)
        for (token in listOf(ofVariable, ofOption)) {
            assertFalse(token in outcome.output, "$outcome")
        }
    }
}
