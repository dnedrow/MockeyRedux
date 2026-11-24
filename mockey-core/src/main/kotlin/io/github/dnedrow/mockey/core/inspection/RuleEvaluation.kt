package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.isSpaceOrControl
import io.github.dnedrow.mockey.core.request.nonBlankBodyText

/**
 * What evaluating a rule set against a request gave. [satisfiedRuleCount] is the number of rules that the
 * request satisfies, in all groups, also in those that are not satisfied as a whole. [failedRules] are the other
 * rules, in the order of the rule set.
 */
internal data class RuleSetEvaluation(
    val satisfied: Boolean,
    val satisfiedRuleCount: Int,
    val failedRules: List<Rule>,
)

/**
 * Evaluates the rule set against [request].
 *
 * A group is satisfied when the request satisfies every rule of it. The rule set is satisfied when at least one
 * of its groups is, where only a group with a rule counts. A rule set without a rule is therefore never
 * satisfied.
 */
internal fun RuleSet.evaluate(request: MockRequest): RuleSetEvaluation {
    val results = rules.map { rule -> rule to rule.isSatisfiedBy(request) }
    val groups = results.groupBy({ (rule, _) -> rule.group }, { (_, satisfied) -> satisfied })
    return RuleSetEvaluation(
        satisfied = groups.values.any { group -> group.all { it } },
        satisfiedRuleCount = results.count { (_, satisfied) -> satisfied },
        failedRules = results.filterNot { (_, satisfied) -> satisfied }.map { (rule, _) -> rule },
    )
}

/**
 * Evaluates what was read of a rule set against [request]. A rule set that is not well formed is never
 * satisfied: no rule of it is counted, and none has failed.
 */
internal fun RuleSetReading.evaluate(request: MockRequest): RuleSetEvaluation =
    when (this) {
        is RuleSetReading.WellFormed -> ruleSet.evaluate(request)
        is RuleSetReading.NotWellFormed -> NEVER_SATISFIED
    }

private val NEVER_SATISFIED = RuleSetEvaluation(satisfied = false, satisfiedRuleCount = 0, failedRules = emptyList())

/** Returns whether [request] satisfies the rule: whether the [values] that the rule evaluates do, see [isSatisfiedBy]. */
internal fun Rule.isSatisfiedBy(request: MockRequest): Boolean = isSatisfiedBy(values(request))

/**
 * Returns the values of [request] that the rule evaluates.
 *
 * - A rule of `parameters` evaluates the values of the query parameters and form fields whose name is the key
 *   of the rule, in any letter case. With the key `*` it evaluates the values of all of them.
 * - A rule of `headers` evaluates the values of the headers in the same way. It sees every header of the request.
 * - A rule of `body` evaluates the body as text. A body that holds nothing but whitespace is no value.
 * - A rule of `url` evaluates the URL that the client addressed, without its query string.
 */
internal fun Rule.values(request: MockRequest): List<String> =
    when (group) {
        RuleGroup.PARAMETERS -> request.parameters.filter { evaluates(it.name) }.map { it.value }
        RuleGroup.HEADERS -> request.headers.filter { evaluates(it.name) }.map { it.value }
        RuleGroup.BODY -> listOfNotNull(request.nonBlankBodyText)
        RuleGroup.URL -> listOf(request.url)
    }

/**
 * Returns whether [values] satisfy the rule.
 *
 * - `string_required`: a value contains the argument, without regard to letter case and to whitespace around
 *   the argument. An empty argument asks for no more than a value.
 * - `regex_required`: the expression is found in a value.
 * - `regex_optional`: there is no value, or the expression is found in a value.
 */
internal fun Rule.isSatisfiedBy(values: List<String>): Boolean =
    when (type) {
        RuleType.STRING_REQUIRED -> wantedText.lowercase().let { wanted -> values.any { wanted in it.lowercase() } }
        RuleType.REGEX_REQUIRED -> values.any(::finds)
        RuleType.REGEX_OPTIONAL -> values.isEmpty() || values.any(::finds)
    }

/** The text that a `string_required` rule looks for: its argument without the whitespace around it. */
internal val Rule.wantedText: String
    get() = argument.trim(::isSpaceOrControl)

/** The key that stands for every parameter or every header. */
private const val WILDCARD = "*"

private fun Rule.evaluates(name: String): Boolean = key == WILDCARD || name.equals(key, ignoreCase = true)

/** Returns whether the expression of the rule is found in [value]. A search that overflows the stack finds nothing. */
private fun Rule.finds(value: String): Boolean =
    try {
        expression?.containsMatchIn(value) ?: false
    } catch (e: StackOverflowError) {
        // The matcher calls itself once for every character that some expressions take, such as a repeated choice.
        false
    }
