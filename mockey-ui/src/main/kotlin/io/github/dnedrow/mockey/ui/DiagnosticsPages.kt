package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.ktor.server.routing.get
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul

/** The area of the page of the diagnostics. */
internal val DiagnosticsArea =
    Area { routing, ui ->
        routing.get(DiagnosticsPages.PATH) { ui.show(call) { _, _ -> DiagnosticsPages.all(ui.client.listDiagnostics()) } }
    }

/**
 * The page of the diagnostics: what a server found in its services that deserves attention and keeps it from
 * nothing, such as two services with the same mock URL. The marker of a service in the overview leads here.
 */
internal object DiagnosticsPages {
    /** The path of the page. */
    const val PATH = "/diagnostics"

    private const val KIND = "service"

    /** What the page calls the kinds of diagnostics that the admin API names. A kind that is not among them is shown by its name. */
    private val KINDS =
        mapOf(
            "sharedMockUrl" to "Shared mock URL",
            "sharedRealUrl" to "Shared real URL",
            "duplicateScenarios" to "Duplicate scenarios",
            "unknownInspector" to "Unknown inspector",
            "unreadableRuleSet" to "Unreadable rule set",
            "schemaViolation" to "Schema violation",
        )

    private const val ABOUT =
        "What the server found in its services that deserves attention. None of it keeps the server from answering a request."

    private const val NOTHING = "The server found nothing."

    /** The order of the services, of each of which a list holds the diagnostics: by their names, as in the overview. */
    private val BY_NAME =
        compareBy<List<DiagnosticDto>, String>(String.CASE_INSENSITIVE_ORDER) { it.first().serviceName }.thenBy { it.first().serviceId }

    /** Returns the address of what the page says of the service [serviceId]. */
    fun addressOf(serviceId: String): String = "$PATH#${Address.anchorOf(KIND, serviceId)}"

    /** The page that shows [diagnostics], service by service, in the order of the names of the services. */
    fun all(diagnostics: List<DiagnosticDto>): Page =
        Page("Diagnostics") {
            p { +ABOUT }
            if (diagnostics.isEmpty()) {
                p(classes = "count") { +NOTHING }
                return@Page
            }
            val byService = diagnostics.groupBy { it.serviceId }.values
            for (ofService in byService.sortedWith(BY_NAME)) {
                section(classes = "diagnostics") {
                    id = Address.anchorOf(KIND, ofService.first().serviceId)
                    h2 { +ofService.first().serviceName }
                    ul {
                        for (diagnostic in ofService) {
                            li {
                                span(classes = "kind") { +(KINDS[diagnostic.kind] ?: diagnostic.kind) }
                                +": ${diagnostic.message}"
                            }
                        }
                    }
                }
            }
        }
}
