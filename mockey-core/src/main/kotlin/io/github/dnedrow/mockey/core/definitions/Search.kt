package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId

/** The kind of definition that a [SearchResult] found. */
public enum class SearchResultType {
    SERVICE,
    SCENARIO,
    PLAN,
}

/**
 * A definition that a search found: its [type], the text of its identifier, and its name. For a scenario,
 * [serviceId] and [serviceName] say which service it belongs to.
 */
public data class SearchResult(
    public val type: SearchResultType,
    public val id: String,
    public val name: String,
    public val serviceId: ServiceId? = null,
    public val serviceName: String? = null,
)

/**
 * Returns the plans, services, and scenarios that contain [term], without regard to letter case and to
 * whitespace around the term. A service is searched in its name, its tags, its real URLs, and its mock URL
 * pattern, a scenario in its name, its tags, and its response body, and a plan in its name and its tags. The
 * plans come first, and each service is followed by its scenarios. A blank term finds nothing.
 */
internal fun Definitions.search(term: String): List<SearchResult> {
    val wanted = term.trim()
    if (wanted.isEmpty()) return emptyList()

    fun Iterable<String>.hold(): Boolean = any { it.contains(wanted, ignoreCase = true) }

    fun Plan.matches(): Boolean = (tags + name).hold()

    fun Service.matches(): Boolean = (tags + name + mockUrl + realUrls).hold()

    fun Scenario.matches(): Boolean = (tags + name + responseBody).hold()

    val ofPlans = plans.filter { it.matches() }.map { SearchResult(SearchResultType.PLAN, it.id.value, it.name) }
    val ofServices =
        services.flatMap { service ->
            val ofService =
                if (service.matches()) {
                    listOf(
                        SearchResult(SearchResultType.SERVICE, service.id.value, service.name),
                    )
                } else {
                    emptyList()
                }
            ofService +
                service.scenarios
                    .filter { it.matches() }
                    .map { SearchResult(SearchResultType.SCENARIO, it.id.value, it.name, service.id, service.name) }
        }
    return ofPlans + ofServices
}
