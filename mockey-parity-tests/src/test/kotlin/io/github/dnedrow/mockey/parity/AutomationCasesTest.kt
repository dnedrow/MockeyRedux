package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class AutomationCasesTest {
    @Test
    fun everyAutomationCaseHasARecordingOfItsCurrentFixtureAndRequests() = RecordedGroups.assertRecorded(AutomationCases.groups)

    @Test
    fun everyAutomationRecordingBelongsToACase() = RecordedGroups.assertNoOtherRecordings(AutomationCases.groups, "automation-")

    /**
     * Records the automation group from legacy Mockey, see [RecordedGroups.recordFromLegacy]. A stub serves the
     * initialization fixture to the case that loads it from a URL. The cases that load it from a file need it as
     * [AutomationCases.INIT_DOCUMENT] in the working directory of legacy Mockey: scripts/record-cases.sh puts it there.
     */
    @Test
    fun recordsGroupFromLegacy() = RecordedGroups.recordFromLegacy(AutomationCases.groups, ::withDocumentStub)

    /** Replays the automation recordings against legacy Mockey, see [RecordedGroups.replayAgainstLegacy]. */
    @Test
    fun replaysGroupAgainstLegacy() = RecordedGroups.replayAgainstLegacy(AutomationCases.groups, ::withDocumentStub)

    private fun withDocumentStub(action: () -> Unit) {
        val document = Files.readAllBytes(Path.of("src/test/resources").resolve(AutomationCases.INIT_FIXTURE))
        StubHttpServer(
            role = "definitions",
            responseBody = document,
            responseHeaders = mapOf("Content-Type" to "text/xml"),
            bindPort = AutomationCases.DOCUMENT_PORT,
        ).use { action() }
    }
}
