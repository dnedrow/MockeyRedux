package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.SearchResultDto
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The search page: a form whose search term is in the address, and the services, scenarios, and plans that
 * the server found for that term.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind this page.
 */
internal val SearchArea =
    Area { routing, ui ->
        routing.get(SearchPages.PAGE) {
            val term = call.request.queryParameters[SearchPages.TERM].orEmpty()
            // The server finds nothing for a blank term, so the UI doesn't ask it.
            ui.show(call) { _, _ -> SearchPages.search(term, if (term.isBlank()) null else ui.client.search(term)) }
        }
    }

/**
 * The form that searches the definitions for [term]. It leads to the search page, with the term in the
 * address. [hint] is shown below the field.
 */
internal fun FlowContent.searchFormOf(
    term: String,
    hint: String,
) {
    getForm(SearchPages.PAGE, classes = "search") {
        textField(FormState.of(SearchPages.TERM to term), SearchPages.TERM, "Search", hint = hint)
        submit("Search")
    }
}

/** The search page. */
internal object SearchPages {
    /** The path of the page. */
    const val PAGE = "/search"

    /** The address parameter that holds the search term. */
    const val TERM = "term"

    /** The hint below the search field on other pages. It says in a few words what a search covers. */
    const val BRIEF_HINT = "Searches the services, the scenarios, and the plans of the server."

    private const val HINT =
        "Finds services by name, tag, mock URL, and real URL, scenarios by name, tag, and response body, " +
            "and plans by name and tag. Letter case does not matter."

    private const val SERVICE = "service"
    private const val SCENARIO = "scenario"
    private const val PLAN = "plan"

    /** What the page calls the kinds of results that the admin API names. An unknown kind is shown by its name. */
    private val KINDS = mapOf(SERVICE to "Service", SCENARIO to "Scenario", PLAN to "Plan")

    private val COLUMNS = listOf("Name", "Kind", "Service")

    /**
     * Returns the search page for [term]. [found] is what the server found, in the order of the server, or
     * `null` if the term is blank. In that case, the page only shows the form.
     */
    fun search(
        term: String,
        found: List<SearchResultDto>?,
    ): Page =
        Page("Search") {
            searchFormOf(term, HINT)
            if (found == null) return@Page
            p(classes = "count") { +countOf(found.size, term.trim()) }
            if (found.isEmpty()) return@Page
            div(classes = "scroll") {
                table(classes = "results") {
                    thead {
                        tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                    }
                    tbody { found.forEach { row(it) } }
                }
            }
        }

    /** Returns the sentence that says how many results the server found for [term]. */
    private fun countOf(
        count: Int,
        term: String,
    ): String {
        val asked = "\"${briefly(term)}\""
        return when (count) {
            0 -> "Nothing was found for $asked."
            1 -> "1 result for $asked"
            else -> "$count results for $asked"
        }
    }

    /** The row of [result]: its name as a link to its page, its kind, and the service of a scenario. */
    private fun TBODY.row(result: SearchResultDto) {
        tr {
            th(scope = ThScope.row) {
                val address = addressOf(result)
                if (address == null) +result.name else a(href = address) { +result.name }
            }
            td { +(KINDS[result.type] ?: result.type) }
            td {
                val serviceId = result.serviceId
                if (serviceId != null) a(href = ServicePages.addressOf(serviceId)) { +(result.serviceName ?: serviceId) }
            }
        }
    }

    /** Returns the address of the page for [result], or `null` if the UI has no page for a result of its kind. */
    private fun addressOf(result: SearchResultDto): String? {
        val serviceId = result.serviceId
        return when (result.type) {
            SERVICE -> ServicePages.addressOf(result.id)
            SCENARIO -> serviceId?.let { ScenarioPages.addressOf(it, result.id) }
            PLAN -> PlanPages.addressOf(result.id)
            else -> null
        }
    }
}
