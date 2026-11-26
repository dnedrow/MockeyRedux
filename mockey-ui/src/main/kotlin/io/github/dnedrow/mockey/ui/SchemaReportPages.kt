package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/**
 * The page with the schema report of a service: what the response schema of the service says about the
 * response body of each of its scenarios.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind this page.
 */
internal val SchemaReportArea =
    Area { routing, ui ->
        routing.get(SchemaReportPages.PAGE) {
            val id = call.partOfAddress(ServicesPages.ID)
            ui.show(call) { _, _ ->
                val service = ui.client.getService(id)
                SchemaReportPages.report(service, ui.client.getSchemaReport(id))
            }
        }
    }

/** The page with the schema report of a service. */
internal object SchemaReportPages {
    /** The path of the page. */
    const val PAGE = "/service/schema-report"

    private val COLUMNS = listOf("Scenario", "Result", "Violations")

    /** Returns the address of the schema report for the service [serviceId]. */
    fun addressOf(serviceId: String): String = Address.of(PAGE, ServicesPages.ID to serviceId)

    /**
     * Returns the page that shows [report], which is what the server found when it checked the scenarios of
     * [service] against the response schema of the service.
     */
    fun report(
        service: ServiceDto,
        report: SchemaReportDto,
    ): Page =
        Page("Schema report of ${service.name}") {
            p { +ABOUT }
            when (report) {
                is SchemaReportDto.Checked -> {
                    checkedOf(service, report.scenarios)
                }

                is SchemaReportDto.UnsupportedDialect -> {
                    p(classes = "problem") { +UNSUPPORTED }
                    p { code { +report.dialect } }
                }

                is SchemaReportDto.UnusableSchema -> {
                    p(classes = "problem") { +UNUSABLE }
                    ul { report.problems.forEach { li { +it } } }
                }
            }
            p(classes = "actions") {
                a(href = ServicePages.addressOf(service.id)) { +"Back to the service" }
                a(href = Address.of(ServicePages.EDIT, ServicesPages.ID to service.id)) { +"Edit the service" }
            }
        }

    /** Shows what the schema of [service] says about each of [scenarios], in a table below a sentence that sums it up. */
    private fun FlowContent.checkedOf(
        service: ServiceDto,
        scenarios: List<ScenarioConformanceDto>,
    ) {
        p(classes = "count") { +summaryOf(scenarios) }
        if (scenarios.isEmpty()) return
        div(classes = "scroll") {
            table(classes = "conformance") {
                thead {
                    tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                }
                tbody {
                    for (scenario in scenarios) {
                        tr {
                            th(scope = ThScope.row) {
                                a(href = ScenarioPages.addressOf(service.id, scenario.scenarioId)) { +scenario.scenarioName }
                            }
                            td { +(if (scenario.conforms) CONFORMS else DOES_NOT_CONFORM) }
                            td { ul(classes = "plain") { scenario.violations.forEach { li { +it } } } }
                        }
                    }
                }
            }
        }
    }

    /** Returns the sentence that says how many of [scenarios] conform to the schema. */
    private fun summaryOf(scenarios: List<ScenarioConformanceDto>): String {
        val total = scenarios.size
        val failing = scenarios.count { !it.conforms }
        return when {
            total == 0 -> "The service has no scenario to check."
            total == 1 -> if (failing == 0) "The scenario conforms to the schema." else "The scenario does not conform to the schema."
            failing == 0 -> "All $total scenarios conform to the schema."
            failing == 1 -> "1 of $total scenarios does not conform to the schema."
            else -> "$failing of $total scenarios do not conform to the schema."
        }
    }

    private const val ABOUT =
        "The server checked the response body of each scenario against the response schema of the service. " +
            "The report changes nothing: a scenario that does not conform is stored and served as before."
    private const val CONFORMS = "conforms"
    private const val DOES_NOT_CONFORM = "does not conform"
    private const val UNSUPPORTED =
        "The server did not check the scenarios, because the schema declares a dialect that it does not support:"
    private const val UNUSABLE = "The server did not check the scenarios, because it cannot apply the schema:"
}
