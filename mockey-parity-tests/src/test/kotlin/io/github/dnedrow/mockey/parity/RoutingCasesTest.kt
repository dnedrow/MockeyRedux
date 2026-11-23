package io.github.dnedrow.mockey.parity

import kotlin.test.Test

class RoutingCasesTest {
    @Test
    fun everyRoutingCaseHasARecordingOfItsCurrentFixtureAndRequests() = RecordedGroups.assertRecorded(RoutingCases.groups)

    @Test
    fun everyRoutingRecordingBelongsToACase() = RecordedGroups.assertNoOtherRecordings(RoutingCases.groups, "routing-")

    /**
     * Records the routing groups from legacy Mockey, see [RecordedGroups.recordFromLegacy]. "routing" selects every
     * group. The stub upstream runs for the duration of the recording.
     */
    @Test
    fun recordsGroupFromLegacy() = RecordedGroups.recordFromLegacy(RoutingCases.groups, ::withUpstream)

    /** Replays the routing recordings against legacy Mockey, see [RecordedGroups.replayAgainstLegacy]. */
    @Test
    fun replaysGroupAgainstLegacy() = RecordedGroups.replayAgainstLegacy(RoutingCases.groups, ::withUpstream)

    private fun withUpstream(action: () -> Unit) = StubHttpServer.upstream(RoutingCases.UPSTREAM_PORT).use { action() }
}
