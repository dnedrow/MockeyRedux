package io.github.dnedrow.mockey.parity

import kotlin.test.Test

class ResponseCasesTest {
    @Test
    fun everyResponseCaseHasARecordingOfItsCurrentFixtureAndRequest() = RecordedGroups.assertRecorded(ResponseCases.groups)

    @Test
    fun everyResponseRecordingBelongsToACase() = RecordedGroups.assertNoOtherRecordings(ResponseCases.groups, "response-")

    /**
     * Records the response groups from legacy Mockey, see [RecordedGroups.recordFromLegacy]. "responses" selects
     * every group.
     */
    @Test
    fun recordsGroupFromLegacy() = RecordedGroups.recordFromLegacy(ResponseCases.groups)

    /** Replays the response recordings against legacy Mockey, see [RecordedGroups.replayAgainstLegacy]. */
    @Test
    fun replaysGroupAgainstLegacy() = RecordedGroups.replayAgainstLegacy(ResponseCases.groups)
}
