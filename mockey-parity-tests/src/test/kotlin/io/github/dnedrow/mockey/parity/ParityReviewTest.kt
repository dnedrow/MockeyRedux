package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParityReviewTest {
    private val store = ParityCaseStore()
    private val registered = setOf("DEV-06", "DEV-13")
    private val mockHeaders = mapOf("Last-Modified" to listOf(ANY_VALUE))

    /** Task 2.9: every recording is labeled `same` or with registered deviations, and a deviation states its new response. */
    @Test
    fun everyRecordingIsReviewed() {
        val register = ParityReview.registeredDeviations(Path.of("../docs/deviations.md"))
        assertTrue(register.size >= SEEDED_DEVIATIONS, "deviations found in the register: $register")

        assertEquals(emptyList(), ParityReview.problems(Path.of("src/test/resources/parity"), register))
    }

    /**
     * Task 15.3: the register and the labels of the recordings say the same. Every recording that the
     * register names is there, and the register accounts for every recording that carries a deviation.
     */
    @Test
    fun theRegisterMatchesTheLabelsOfTheRecordings() {
        val problems = ParityReview.registerProblems(Path.of("../docs/deviations.md"), Path.of("src/test/resources/parity"))

        assertEquals(emptyList(), problems)
    }

    /**
     * A row accounts for the recordings that carry its ID in one of three ways: it names each of them, by
     * its name or by the start of several names, it names some and says how many more there are, or it
     * states their number and names examples. A row may also name a recording that carries another label,
     * and whatever else it writes in backticks is no recording.
     */
    @Test
    fun acceptsARegisterThatAccountsForTheLabeledRecordings() {
        val recordings = Files.createTempDirectory("mockey-parity-register-")
        write(recordings, "routing-one", "DEV-01", NewResponse(200, mockHeaders))
        write(recordings, "routing-two", "DEV-01 DEV-02", NewResponse(200, mockHeaders))
        write(recordings, "proxy-a", "DEV-02 DEV-03", NewResponse(200, mockHeaders))
        write(recordings, "proxy-b", "DEV-03 DEV-04", NewResponse(200, mockHeaders))
        write(recordings, "proxy-c", "DEV-03 DEV-04", NewResponse(200, mockHeaders))
        write(recordings, "rule-same", ParityCase.SAME)
        val register =
            register(
                "| DEV-01 | Old. | New. | run: `Servlet.service` and `a \\| b` in `utf-8`, with `routing-one` and `routing-two` |",
                "| DEV-02 | Old. | New. | run: `routing-two`, and 1 more recording. `rule-same` shows what legacy does. |",
                "| DEV-03 | Old. | New. | run: the three `proxy-*` recordings |",
                "| DEV-04 | Old. | New. | run: 2 recordings, such as `proxy-c`. `rule-same` shows what legacy does. |",
                "| DEV-05 | Old \\| older. | New. | read: `Servlet.service`. `routing-one` shows what legacy does. |",
                "| DEV-06 | Old. | New. | run: probe |",
            )

        assertEquals(emptyList(), ParityReview.registerProblems(register, recordings))
    }

    @Test
    fun rejectsARegisterThatDoesNotMatchTheLabelsOfTheRecordings() {
        val recordings = Files.createTempDirectory("mockey-parity-register-")
        write(recordings, "routing-one", "DEV-01 DEV-02", NewResponse(200, mockHeaders))
        write(recordings, "routing-two", "DEV-01 DEV-02 DEV-04", NewResponse(200, mockHeaders))
        write(recordings, "proxy-a", "DEV-02 DEV-03 DEV-04 DEV-05 DEV-06", NewResponse(200, mockHeaders))
        write(recordings, "proxy-b", "DEV-03 DEV-04 DEV-06", NewResponse(200, mockHeaders))
        val register =
            register(
                "| DEV-01 | Old. | New. | run: `routing-one`, `routing-three`, and the `proxy-c*` recordings |",
                "| DEV-02 | Old. | New. | run: `routing-one`, and 1 more recording |",
                "| DEV-03 | Old. | New. | run: 3 recordings, such as `proxy-a` |",
                "| DEV-04 | Old. | New. | run: 3 recordings, such as `routing-one` |",
                "| DEV-05 | Old. | New. | read: `Servlet.service` |",
                "| DEV-06 | Old. | New. | run: 1 recording, such as `proxy-a` |",
                "| DEV-08 | Old. | New. | run: probe |",
                "| DEV-08 | Old. | New. | run: probe |",
            )

        assertEquals(
            listOf(
                "the register has DEV-08 where DEV-07 is next",
                "DEV-01 names `routing-three`, and no recording has that name",
                "DEV-01 names `proxy-c*`, and no recording has that name",
                "DEV-01 is the label of `routing-two`, which its evidence does not name",
                "DEV-02 is the label of 3 recordings, and its evidence names 1 of them and states 1 more",
                "DEV-03 is the label of 2 recordings, and its evidence states 3",
                "DEV-04 is the label of 3 recordings, and its evidence names none of them",
                "DEV-05 is the label of 1 recording, and its evidence is not of the kind run",
                "DEV-06 is the label of 2 recordings, and its evidence states 1",
            ),
            ParityReview.registerProblems(register, recordings),
        )
    }

    @Test
    fun acceptsReviewedRecordings() {
        val recordings = Files.createTempDirectory("mockey-parity-review-")
        write(recordings, "same", ParityCase.SAME)
        write(recordings, "mock-deviation", "DEV-06", NewResponse(200, mockHeaders, ExpectedBody.Exact("x".toByteArray())))
        write(recordings, "two-deviations", "DEV-06 DEV-13", NewResponse(503, mockHeaders))
        write(recordings, "automation", "DEV-13", NewResponse(200, body = ExpectedBody.Json("""{"result":"<any>"}""")), "/definitions")
        Files.writeString(recordings.resolve("README.md"), "A file that is no recording.")

        assertEquals(emptyList(), ParityReview.problems(recordings, registered))
    }

    @Test
    fun rejectsARecordingThatIsNotReviewed() {
        val recordings = Files.createTempDirectory("mockey-parity-review-")
        write(recordings, "fresh", ParityCase.UNREVIEWED)
        write(recordings, "reviewed", ParityCase.SAME)

        assertEquals(listOf("fresh: is not reviewed"), ParityReview.problems(recordings, registered))
    }

    @Test
    fun rejectsALabelThatSaysNothingUsable() {
        val recordings = Files.createTempDirectory("mockey-parity-review-")
        write(recordings, "a-misspelled", "Same")
        write(recordings, "b-unknown", "DEV-06 DEV-99", NewResponse(200, mockHeaders))
        write(recordings, "c-unordered", "DEV-13 DEV-06", NewResponse(200, mockHeaders))

        assertEquals(
            listOf(
                "a-misspelled: 'Same' is neither 'same' nor a list of deviation IDs",
                "b-unknown: DEV-99 is not in the deviation register",
                "c-unordered: the deviation IDs 'DEV-13 DEV-06' are not in ascending order",
            ),
            ParityReview.problems(recordings, registered),
        )
    }

    @Test
    fun rejectsAReviewWhoseStatementDoesNotFitItsLabel() {
        val recordings = Files.createTempDirectory("mockey-parity-review-")
        write(recordings, "a-same-with-statement", ParityCase.SAME, NewResponse(200, mockHeaders))
        write(recordings, "b-deviation-without-statement", "DEV-06")
        write(recordings, "c-mock-without-headers", "DEV-06", NewResponse(200))
        write(recordings, "d-automation-with-headers", "DEV-06", NewResponse(200, mockHeaders), "/definitions")
        write(recordings, "e-broken-json", "DEV-06", NewResponse(200, body = ExpectedBody.Json("""{"result":""")), "/definitions")

        assertEquals(
            listOf(
                "a-same-with-statement: is labeled same but states a new response",
                "b-deviation-without-statement: deviates but states no new response",
                "c-mock-without-headers: states no headers for a response of the mock endpoint",
                "d-automation-with-headers: states headers that are not compared outside the mock endpoint",
                "e-broken-json: states a body that is no JSON: unexpected end at offset 10",
            ),
            ParityReview.problems(recordings, registered),
        )
    }

    @Test
    fun rejectsARecordingThatCannotBeRead() {
        val recordings = Files.createTempDirectory("mockey-parity-review-")
        write(recordings, "truncated", ParityCase.SAME)
        Files.writeString(recordings.resolve("truncated/expected-response.properties"), "body=\n")

        assertEquals(listOf("truncated: Missing 'status' in parity recording."), ParityReview.problems(recordings, registered))
    }

    @Test
    fun readsTheDeviationsOfTheRegister() {
        val register = Files.createTempFile("mockey-deviations-", ".md")
        Files.writeString(
            register,
            """
            # Deviation register

            | ID | Legacy behavior | New behavior | Evidence |
            |---|---|---|---|
            | DEV-01 | Mentions DEV-77 in its text. | New. | run |
            |DEV-02| Tight. | New. | read |
            | DEV-131 | Three digits. | New. | recorded |

            DEV-88 is mentioned outside a table.
            """.trimIndent(),
        )

        assertEquals(setOf("DEV-01", "DEV-02", "DEV-131"), ParityReview.registeredDeviations(register))
    }

    /** Writes a register with [rows] and returns it. */
    private fun register(vararg rows: String): Path {
        val register = Files.createTempFile("mockey-deviations-", ".md")
        val table = listOf("| ID | Legacy behavior | New behavior | Evidence |", "|---|---|---|---|") + rows
        Files.writeString(register, "# Deviation register\n\n" + table.joinToString("\n") + "\n")
        return register
    }

    private fun write(
        recordings: Path,
        name: String,
        label: String,
        newResponse: NewResponse? = null,
        target: String = "/service/reviewed",
    ) {
        val case =
            ParityCase(
                label = label,
                fixtureXml = "<mockey/>",
                request = ParityRequest("GET", target),
                expectedResponse = ParityResponse(200, emptyMap(), ByteArray(0)),
                newResponse = newResponse,
            )
        store.write(case, recordings.resolve(name))
    }

    private companion object {
        const val SEEDED_DEVIATIONS = 30
    }
}
