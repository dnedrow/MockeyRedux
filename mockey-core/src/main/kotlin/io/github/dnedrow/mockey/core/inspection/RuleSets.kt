package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service

/**
 * The rule sets of [definitions], read once: the request rules of every service, and the match criterion of
 * every scenario whose criterion is a rule set. One value of this class belongs to one value of the
 * definitions: after a change to them, a new one is needed.
 *
 * A stored rule set need not be well formed, because definitions also come from elsewhere than the changes that
 * check a rule set, such as an import. A rule set that is not well formed is never satisfied, see [evaluate].
 */
internal class RuleSets(
    val definitions: Definitions,
) {
    private val readings: Map<String, RuleSetReading> =
        definitions.services
            .flatMap { service -> listOfNotNull(service.requestRules) + service.scenarios.mapNotNull { it.ruleSetSource } }
            .distinct()
            .associateWith(::readRuleSet)

    /** Returns the request rules of [service] as they were read, or `null` for a service without request rules. */
    fun requestRules(service: Service): RuleSetReading? = service.requestRules?.let(::reading)

    /** Returns the match criterion of [scenario] as it was read, or `null` for a scenario whose criterion is no rule set. */
    fun criterion(scenario: Scenario): RuleSetReading? = scenario.ruleSetSource?.let(::reading)

    /** A rule set that the definitions of this value do not hold is read when it is asked for. */
    private fun reading(source: String): RuleSetReading = readings[source] ?: readRuleSet(source)
}

private val Scenario.ruleSetSource: String?
    get() = (matchCriterion as? MatchCriterion.Rules)?.source
