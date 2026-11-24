package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.inspection.evaluate
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.formDecoded
import io.github.dnedrow.mockey.core.request.isSpaceOrControl
import io.github.dnedrow.mockey.core.request.nonBlankBodyText

/** A scenario that matches a request in dynamic mode, and the [score] of the match. */
internal data class ScenarioMatch(
    val scenario: Scenario,
    val score: Int,
)

/**
 * Returns the scenario of [service] that answers [request] in dynamic mode: the [best] of the scenarios that
 * match the request, see [scenarioMatches]. Returns `null` when no scenario matches.
 */
internal fun bestMatch(
    service: Service,
    variables: Map<String, String>,
    request: MockRequest,
    ruleSets: RuleSets,
): Scenario? = scenarioMatches(service, variables, request, ruleSets).best()?.scenario

/**
 * Returns the scenarios of [service] that match [request] in dynamic mode, in the order in which the scenarios
 * were created. [variables] are the values that routing captured for the variables of the URL pattern through
 * which the request reaches the service.
 *
 * Only a scenario that is eligible can match: one whose method restriction admits the method of the request,
 * see [admits]. An eligible scenario matches when the request meets its match criterion:
 *
 * - A text criterion is met when the [evaluationText] of the request contains the text, without regard to
 *   letter case. The score is the number of characters of the text without the whitespace around it.
 * - A rule set is met when the request satisfies it. The score is the number of rules that the request
 *   satisfies, in all groups of the rule set. A rule set that is not well formed is never satisfied.
 * - A scenario without a criterion never matches.
 */
internal fun scenarioMatches(
    service: Service,
    variables: Map<String, String>,
    request: MockRequest,
    ruleSets: RuleSets,
): List<ScenarioMatch> {
    val text by lazy(LazyThreadSafetyMode.NONE) { evaluationText(variables, request).lowercase() }
    return service.scenarios.filter { it.admits(request.method) }.mapNotNull { scenario ->
        val score =
            when (val criterion = scenario.matchCriterion) {
                is MatchCriterion.None -> null
                is MatchCriterion.Text -> criterion.score.takeIf { criterion.text.lowercase() in text }
                is MatchCriterion.Rules -> ruleScore(scenario, request, ruleSets)
            }
        score?.let { ScenarioMatch(scenario, it) }
    }
}

/** Returns the score of [scenario] when [request] satisfies the rule set that is its criterion, and `null` when it does not. */
private fun ruleScore(
    scenario: Scenario,
    request: MockRequest,
    ruleSets: RuleSets,
): Int? =
    ruleSets
        .criterion(scenario)
        ?.evaluate(request)
        ?.takeIf { it.satisfied }
        ?.satisfiedRuleCount

/**
 * Returns the match that answers, or `null` when there is no match: the one with the highest score. Of the
 * matches with that score, it is the one whose scenario has the name that sorts first without regard to letter
 * case, and of those with the same name the first of the list. For matches in the order of [scenarioMatches],
 * the first is the one whose scenario was created first.
 */
internal fun List<ScenarioMatch>.best(): ScenarioMatch? = minWithOrNull(BEST_FIRST)

private val BEST_FIRST: Comparator<ScenarioMatch> =
    compareByDescending<ScenarioMatch> { it.score }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.scenario.name }

/**
 * Returns whether the scenario is eligible for a request with [method]: whether it has no method restriction,
 * the restriction `*`, or a restriction that is [method] in any letter case.
 */
internal fun Scenario.admits(method: String): Boolean {
    val restriction = methodRestriction?.trim().orEmpty()
    return restriction.isEmpty() || restriction == ANY_METHOD || restriction.equals(method, ignoreCase = true)
}

/** The method restriction that admits every method. */
private const val ANY_METHOD = "*"

/** The score of a match with the text criterion: the number of its characters without the whitespace around it. */
private val MatchCriterion.Text.score: Int
    get() = text.trim(::isSpaceOrControl).length

/**
 * Returns the text in which the text criteria of scenarios are looked for. It holds these parts of [request],
 * one after the other and with nothing between them:
 *
 * 1. The [variables]: the values that the variables of the URL pattern have for the request, in their order.
 * 2. The parameters of the query string as the request has them, in its order, with `&` between one parameter
 *    and the next. An empty parameter, such as the one between the two `&` of `a=1&&b=2`, is left out.
 * 3. The body, unless it holds nothing but whitespace. The fields of a URL-encoded form are part of the body.
 *
 * The text is then decoded as form data is: a `+` stands for a space, and a percent escape for a byte of UTF-8
 * text. A `%` that starts no escape stays as it is.
 *
 * The literal text of the URL pattern is no part of the evaluation text, and neither are the headers.
 */
internal fun evaluationText(
    variables: Map<String, String>,
    request: MockRequest,
): String {
    val values = variables.values.joinToString(separator = "")
    val parameters =
        request.query
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.joinToString(separator = "&")
            .orEmpty()
    return formDecoded(values + parameters + request.nonBlankBodyText.orEmpty())
}
