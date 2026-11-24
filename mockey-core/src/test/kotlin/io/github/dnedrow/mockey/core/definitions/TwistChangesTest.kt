package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TwistChangesTest {
    private val toQa2 = TwistConfiguration(TwistId("twist-1"), "to-qa2", listOf(TwistPair("qa1", "qa2")))
    private val toSandbox = TwistConfiguration(TwistId("twist-2"), "to-sandbox", listOf(TwistPair("example.com", "sandbox.example.com")))
    private val both = Definitions(twists = listOf(toQa2, toSandbox))
    private val unknown = TwistId("no-such-twist")

    private fun Definitions.enabled(id: TwistId): Definitions = copy(settings = settings.copy(enabledTwistId = id))

    @Test
    fun aTwistConfigurationIsCreatedWithItsNameAndItsPairsInTheirOrder() {
        val pairs = listOf(TwistPair("qa1", "qa2"), TwistPair("example.com", "example.org"), TwistPair("a", "b"))

        val created = both.changed(TwistChanges.create { it.copy(name = "to-qa2-org", pairs = pairs) })

        assertEquals("to-qa2-org", created.value.name)
        assertEquals(pairs, created.value.pairs)
        assertEquals(listOf(toQa2, toSandbox, created.value), created.definitions.twists)
    }

    @Test
    fun aNewTwistConfigurationHasAnIdentifierOfItsOwnAndIsNotEnabled() {
        val first = Definitions.EMPTY.changed(TwistChanges.create { it.copy(name = "first") })
        val second = first.definitions.changed(TwistChanges.create { it.copy(name = "second") })

        assertNotEquals(first.value.id, second.value.id)
        assertNull(second.definitions.settings.enabledTwistId)
    }

    @Test
    fun theNameIsStoredWithoutSurroundingWhitespace() {
        val created = Definitions.EMPTY.changed(TwistChanges.create { it.copy(name = "  to qa2 \n") })

        assertEquals("to qa2", created.value.name)
    }

    @Test
    fun aTwistConfigurationWithoutANameGetsAGeneratedOne() {
        val created = Definitions.EMPTY.changed(TwistChanges.create { it })
        val renamed = both.changed(TwistChanges.update(toQa2.id) { it.copy(name = "   ") })

        assertEquals("TwistInfo (auto-generated)", created.value.name)
        assertEquals("TwistInfo (auto-generated)", renamed.value.name)
    }

    @Test
    fun aPairWithoutAnOriginationTextIsLeftOut() {
        val pairs = listOf(TwistPair("", "qa2"), TwistPair("qa1", ""), TwistPair("", ""), TwistPair(" ", "x"))

        val created = Definitions.EMPTY.changed(TwistChanges.create { it.copy(name = "to-qa2", pairs = pairs) })

        assertEquals(listOf(TwistPair("qa1", ""), TwistPair(" ", "x")), created.value.pairs)
    }

    @Test
    fun theTextsOfAPairAreStoredExactlyAsGiven() {
        val pairs = listOf(TwistPair(" a.b ", " \$1 "), TwistPair("(x|y)*", "\\d+"), TwistPair("QA1", "qa1"), TwistPair("QA1", "qa2"))

        val created = Definitions.EMPTY.changed(TwistChanges.create { it.copy(name = "odd", pairs = pairs) })

        assertEquals(pairs, created.value.pairs)
    }

    @Test
    fun anUpdateChangesTheNameAndThePairs() {
        val pairs = listOf(TwistPair("qa1", "qa3"), TwistPair("qa2", "qa3"))

        val updated = both.changed(TwistChanges.update(toQa2.id) { it.copy(name = "to-qa3", pairs = pairs) })

        assertEquals(TwistConfiguration(toQa2.id, "to-qa3", pairs), updated.value)
        assertEquals(listOf(updated.value, toSandbox), updated.definitions.twists)
    }

    @Test
    fun anUpdateLeavesTheEnabledConfigurationAsItIs() {
        val updatedEnabled = both.enabled(toQa2.id).changed(TwistChanges.update(toQa2.id) { it.copy(name = "renamed") })
        val updatedOther = both.enabled(toQa2.id).changed(TwistChanges.update(toSandbox.id) { it.copy(name = "renamed") })

        assertEquals(toQa2.id, updatedEnabled.definitions.settings.enabledTwistId)
        assertEquals(toQa2.id, updatedOther.definitions.settings.enabledTwistId)
    }

    @Test
    fun theIdentifierOfATwistConfigurationCannotBeChanged() {
        assertFailsWith<IllegalArgumentException> { TwistChanges.update(toQa2.id) { it.copy(id = TwistId("other")) }(both) }
        assertFailsWith<IllegalArgumentException> { TwistChanges.create { it.copy(id = TwistId("chosen")) }(both) }
    }

    @Test
    fun aTwistConfigurationThatDoesNotExistIsNotFound() {
        val changes =
            listOf(
                TwistChanges.update(unknown) { it },
                TwistChanges.delete(unknown),
                TwistChanges.enable(unknown),
                TwistChanges.disable(unknown),
            )

        val messages = changes.map { both.notFound(it) }

        assertEquals(List(4) { "No twist configuration has the identifier no-such-twist." }, messages)
    }

    @Test
    fun aTwistConfigurationIsDeleted() {
        val deleted = both.changed(TwistChanges.delete(toQa2.id))

        assertEquals(toQa2, deleted.value)
        assertEquals(listOf(toSandbox), deleted.definitions.twists)
    }

    @Test
    fun deletingTheEnabledTwistConfigurationLeavesNoneEnabled() {
        val deleted = both.enabled(toQa2.id).changed(TwistChanges.delete(toQa2.id))

        assertNull(deleted.definitions.settings.enabledTwistId)
    }

    @Test
    fun deletingAnotherTwistConfigurationLeavesTheEnabledOneEnabled() {
        val deleted = both.enabled(toQa2.id).changed(TwistChanges.delete(toSandbox.id))

        assertEquals(toQa2.id, deleted.definitions.settings.enabledTwistId)
    }

    @Test
    fun aTwistConfigurationIsEnabled() {
        val enabled = both.changed(TwistChanges.enable(toQa2.id))

        assertEquals(toQa2, enabled.value)
        assertEquals(toQa2.id, enabled.definitions.settings.enabledTwistId)
        assertEquals(both.twists, enabled.definitions.twists)
    }

    @Test
    fun enablingOneTwistConfigurationDisablesTheOther() {
        val enabled = both.enabled(toQa2.id).changed(TwistChanges.enable(toSandbox.id))

        assertEquals(toSandbox.id, enabled.definitions.settings.enabledTwistId)
    }

    @Test
    fun enablingTheEnabledTwistConfigurationChangesNothing() {
        val definitions = both.enabled(toQa2.id)

        assertEquals(definitions, definitions.changed(TwistChanges.enable(toQa2.id)).definitions)
    }

    @Test
    fun theEnabledTwistConfigurationIsDisabled() {
        val disabled = both.enabled(toQa2.id).changed(TwistChanges.disable(toQa2.id))

        assertEquals(toQa2, disabled.value)
        assertNull(disabled.definitions.settings.enabledTwistId)
        assertEquals(both.twists, disabled.definitions.twists)
    }

    @Test
    fun disablingATwistConfigurationThatIsNotEnabledLeavesTheEnabledOneEnabled() {
        val definitions = both.enabled(toQa2.id)

        assertEquals(definitions, definitions.changed(TwistChanges.disable(toSandbox.id)).definitions)
        assertEquals(both, both.changed(TwistChanges.disable(toSandbox.id)).definitions)
    }

    @Test
    fun aChangeToTwistConfigurationsLeavesTheOtherSettingsAsTheyAre() {
        val settings = Settings(enabledTwistId = toQa2.id, defaultPlanId = PlanId("plan-1"))
        val definitions = both.copy(settings = settings)

        val results =
            listOf(TwistChanges.enable(toSandbox.id), TwistChanges.disable(toQa2.id), TwistChanges.delete(toQa2.id))
                .map { definitions.changed(it).definitions.settings }

        assertEquals(
            listOf(
                settings.copy(enabledTwistId = toSandbox.id),
                settings.copy(enabledTwistId = null),
                settings.copy(enabledTwistId = null),
            ),
            results,
        )
    }
}
