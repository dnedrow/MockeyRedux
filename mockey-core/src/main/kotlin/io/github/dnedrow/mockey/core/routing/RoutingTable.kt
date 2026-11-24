package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service

/**
 * The URL patterns of the services of [definitions], compiled once. A table belongs to one value of the
 * definitions: after a change to them, a new table is needed.
 *
 * A target can reach a service through the mock URL pattern of the service and through each of its real URLs,
 * which are patterns as well.
 */
internal class RoutingTable(
    val definitions: Definitions,
) {
    private val entries: List<Entry> =
        definitions.services.map { service -> Entry(service, (listOf(service.mockUrl) + service.realUrls).map(UrlPattern::compile)) }

    /**
     * Returns the services that [target] can reach, in the order in which the services were created. A service
     * that the target reaches through several of its patterns is a candidate once, through the most specific
     * of them.
     */
    fun candidates(target: Target): List<Candidate> = entries.mapNotNull { it.candidate(target) }

    private class Entry(
        val service: Service,
        val patterns: List<UrlPattern>,
    ) {
        fun candidate(target: Target): Candidate? =
            patterns
                .mapNotNull { pattern ->
                    pattern.match(target)?.let { Candidate(service, it, pattern.variableCount, pattern.literalLength) }
                }.mostSpecific()
    }
}

/**
 * A service that a target can reach. [variables] are the values that the pattern yields through which the
 * target reaches the service, and [variableCount] and [literalLength] tell how specific that pattern is.
 */
internal data class Candidate(
    val service: Service,
    val variables: Map<String, String>,
    val variableCount: Int,
    val literalLength: Int,
)

/**
 * Returns the candidate whose pattern is the most specific: the one with the fewest variables, among those
 * the one with the most literal characters, and among those the first of the list. For candidates in the
 * order of [RoutingTable.candidates], the first is the one whose service was created first.
 */
internal fun List<Candidate>.mostSpecific(): Candidate? = minWithOrNull(MOST_SPECIFIC_FIRST)

private val MOST_SPECIFIC_FIRST: Comparator<Candidate> = compareBy<Candidate> { it.variableCount }.thenByDescending { it.literalLength }
