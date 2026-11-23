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
