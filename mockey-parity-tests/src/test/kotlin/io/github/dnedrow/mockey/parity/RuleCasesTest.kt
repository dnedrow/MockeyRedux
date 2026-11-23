package io.github.dnedrow.mockey.parity

import kotlin.test.Test

class RuleCasesTest {
    private val ruleGroups = RuleCases.groups.filter { it.name.startsWith("rules-") }
    private val inspectionGroups = RuleCases.groups - ruleGroups.toSet()

    @Test
    fun everyRuleCaseHasARecordingOfItsCurrentFixtureAndRequests() = RecordedGroups.assertRecorded(RuleCases.groups)

    @Test
    fun everyRuleRecordingBelongsToACase() {
        RecordedGroups.assertNoOtherRecordings(ruleGroups, "rule-")
        RecordedGroups.assertNoOtherRecordings(inspectionGroups, "inspection-")
    }

    /**
     * Records the rule and inspection groups from legacy Mockey, see [RecordedGroups.recordFromLegacy]. "rules"
     * selects the three rule groups, and "inspection" the inspection group.
     */
    @Test
    fun recordsGroupFromLegacy() = RecordedGroups.recordFromLegacy(RuleCases.groups)

    /** Replays the rule and inspection recordings against legacy Mockey, see [RecordedGroups.replayAgainstLegacy]. */
    @Test
    fun replaysGroupAgainstLegacy() = RecordedGroups.replayAgainstLegacy(RuleCases.groups)
}
